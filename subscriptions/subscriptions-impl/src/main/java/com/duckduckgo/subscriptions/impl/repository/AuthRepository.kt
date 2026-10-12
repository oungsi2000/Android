/*
 * Copyright (c) 2024 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.subscriptions.impl.repository

import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.subscriptions.api.ActiveOfferType
import com.duckduckgo.subscriptions.api.Product
import com.duckduckgo.subscriptions.api.SubscriptionStatus
import com.duckduckgo.subscriptions.api.SubscriptionStatus.AUTO_RENEWABLE
import com.duckduckgo.subscriptions.api.SubscriptionStatus.EXPIRED
import com.duckduckgo.subscriptions.api.SubscriptionStatus.GRACE_PERIOD
import com.duckduckgo.subscriptions.api.SubscriptionStatus.INACTIVE
import com.duckduckgo.subscriptions.api.SubscriptionStatus.NOT_AUTO_RENEWABLE
import com.duckduckgo.subscriptions.api.SubscriptionStatus.UNKNOWN
import com.duckduckgo.subscriptions.api.SubscriptionStatus.WAITING
import com.duckduckgo.subscriptions.api.model.Entitlement
import com.duckduckgo.subscriptions.impl.SubscriptionTier
import com.duckduckgo.subscriptions.impl.SubscriptionsFeature
import com.duckduckgo.subscriptions.impl.serp_promo.SerpPromo
import com.duckduckgo.subscriptions.impl.store.SubscriptionsDataStore
import com.duckduckgo.subscriptions.impl.store.SubscriptionsEncryptedDataStore
import com.duckduckgo.subscriptions.impl.toStatus
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.moshi.Moshi
import com.squareup.moshi.Moshi.Builder
import com.squareup.moshi.Types
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn
import kotlinx.coroutines.withContext
import logcat.logcat
import java.time.Instant

interface AuthRepository {
    suspend fun setAccessTokenV2(accessToken: AccessToken?)
    suspend fun getAccessTokenV2(): AccessToken?
    suspend fun setRefreshTokenV2(refreshToken: RefreshToken?)
    suspend fun getRefreshTokenV2(): RefreshToken?
    suspend fun setAccessToken(accessToken: String?)
    suspend fun getAccessToken(): String?
    suspend fun setAuthToken(authToken: String?)
    suspend fun getAuthToken(): String?
    suspend fun setAccount(account: Account?)
    suspend fun getAccount(): Account?
    suspend fun setSubscription(subscription: Subscription?)
    suspend fun getSubscription(): Subscription?
    suspend fun setEntitlements(entitlements: List<Entitlement>)
    suspend fun getEntitlements(): List<Entitlement>
    suspend fun purchaseToWaitingStatus()
    suspend fun getStatus(): SubscriptionStatus
    suspend fun canSupportEncryption(): Boolean
    suspend fun setFeatures(basePlanId: String, features: Set<String>)
    suspend fun getFeatures(basePlanId: String): Set<String>
    suspend fun setFeaturesV2(basePlanId: String, features: Set<Entitlement>)
    suspend fun getFeaturesV2(basePlanId: String): Set<Entitlement>
    suspend fun isFreeTrialActive(): Boolean
    suspend fun registerLocalPurchasedAt()
    suspend fun getLocalPurchasedAt(): Long?
    suspend fun removeLocalPurchasedAt()
    suspend fun setPendingPlans(pendingPlans: List<PendingPlan>)
    suspend fun getPendingPlans(): List<PendingPlan>
}

@Module
@ContributesTo(AppScope::class)
object AuthRepositoryModule {
    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideAuthRepository(
        dispatcherProvider: DispatcherProvider,
        sharedPreferencesProvider: SharedPreferencesProvider,
        serpPromo: SerpPromo,
        subscriptionsFeature: dagger.Lazy<SubscriptionsFeature>,
    ): AuthRepository {
        return RealAuthRepository(SubscriptionsEncryptedDataStore(sharedPreferencesProvider), dispatcherProvider, serpPromo, subscriptionsFeature)
    }
}

internal class RealAuthRepository constructor(
    private val subscriptionsDataStore: SubscriptionsDataStore,
    private val dispatcherProvider: DispatcherProvider,
    private val serpPromo: SerpPromo,
    private val subscriptionsFeature: dagger.Lazy<SubscriptionsFeature>,
) : AuthRepository {

    private val moshi = Builder().build()

    private val featuresAdapter by lazy {
        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Set::class.java,
        )
        moshi.adapter<Map<String, Set<String>>>(type)
    }

    private val featuresV2Adapter by lazy {
        val entitlementSetType = Types.newParameterizedType(Set::class.java, Entitlement::class.java)
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, entitlementSetType)
        moshi.adapter<Map<String, Set<Entitlement>>>(mapType)
    }

    private val pendingPlansListAdapter by lazy {
        val type = Types.newParameterizedType(List::class.java, PendingPlanJson::class.java)
        moshi.adapter<List<PendingPlanJson>>(type)
    }

    private data class PendingPlanJson(
        val productId: String,
        val billingPeriod: String,
        val effectiveAt: Long,
        val status: String,
        val tier: String,
    )

    private inline fun <reified T> Moshi.listToJson(list: List<T>): String {
        return adapter<List<T>>(Types.newParameterizedType(List::class.java, T::class.java)).toJson(list)
    }
    private inline fun <reified T> Moshi.parseList(jsonString: String): List<T>? {
        return adapter<List<T>>(Types.newParameterizedType(List::class.java, T::class.java)).fromJson(jsonString)
    }

    override suspend fun setAccessTokenV2(accessToken: AccessToken?) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.accessTokenV2 = accessToken?.jwt
        subscriptionsDataStore.accessTokenV2ExpiresAt = accessToken?.expiresAt
        updateSerpPromoCookie()
    }

    @OnIo
    override suspend fun getAccessTokenV2(): AccessToken? {
        val jwt = subscriptionsDataStore.accessTokenV2 ?: return null
        val expiresAt = subscriptionsDataStore.accessTokenV2ExpiresAt ?: return null
        return AccessToken(jwt, expiresAt)
    }

    override suspend fun setRefreshTokenV2(refreshToken: RefreshToken?) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.refreshTokenV2 = refreshToken?.jwt
        subscriptionsDataStore.refreshTokenV2ExpiresAt = refreshToken?.expiresAt
    }

    @OnIo
    override suspend fun getRefreshTokenV2(): RefreshToken? {
        val jwt = subscriptionsDataStore.refreshTokenV2 ?: return null
        val expiresAt = subscriptionsDataStore.refreshTokenV2ExpiresAt ?: return null
        return RefreshToken(jwt, expiresAt)
    }

    override suspend fun setEntitlements(entitlements: List<Entitlement>) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.entitlements = moshi.listToJson(entitlements)
    }

    @OnIo
    override suspend fun getEntitlements(): List<Entitlement> {
        return subscriptionsDataStore.entitlements?.let { moshi.parseList(it) } ?: emptyList()
    }

    override suspend fun setAccessToken(accessToken: String?) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.accessToken = accessToken
        updateSerpPromoCookie()
    }

    override suspend fun setAuthToken(authToken: String?) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.authToken = authToken
    }

    override suspend fun purchaseToWaitingStatus() = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.status = WAITING.statusName
    }

    @OnIo
    override suspend fun getStatus(): SubscriptionStatus {
        return subscriptionsDataStore.status?.toStatus() ?: UNKNOWN
    }

    @OnIo
    override suspend fun getAccessToken(): String? {
        return subscriptionsDataStore.accessToken
    }

    @OnIo
    override suspend fun getAuthToken(): String? {
        return subscriptionsDataStore.authToken
    }

    override suspend fun setSubscription(subscription: Subscription?) = withContext(dispatcherProvider.io()) {
        logcat {
            "setSubscription called with subscription: $subscription"
        }
        with(subscriptionsDataStore) {
            productId = subscription?.productId
            billingPeriod = subscription?.billingPeriod
            startedAt = subscription?.startedAt
            expiresOrRenewsAt = subscription?.expiresOrRenewsAt
            status = subscription?.status?.statusName
            platform = subscription?.platform
            freeTrialActive = subscription?.activeOffers?.contains(ActiveOfferType.TRIAL) ?: false
            pendingPlans = subscription?.pendingPlans?.let { plans ->
                if (plans.isEmpty()) {
                    null
                } else {
                    runCatching {
                        pendingPlansListAdapter.toJson(
                            plans.map { plan ->
                                PendingPlanJson(
                                    productId = plan.productId,
                                    billingPeriod = plan.billingPeriod,
                                    effectiveAt = plan.effectiveAt,
                                    status = plan.status,
                                    tier = plan.tier.value,
                                )
                            },
                        )
                    }.getOrNull()
                }
            }
        }
    }

    @OnIo
    override suspend fun getSubscription(): Subscription? {
        val productId = subscriptionsDataStore.productId ?: return null
        val billingPeriod = subscriptionsDataStore.billingPeriod ?: return null
        val startedAt = subscriptionsDataStore.startedAt ?: return null
        val expiresOrRenewsAt = subscriptionsDataStore.expiresOrRenewsAt ?: return null
        val status = subscriptionsDataStore.status?.toStatus() ?: return null
        val platform = subscriptionsDataStore.platform ?: return null
        val activeOffers = if (subscriptionsDataStore.freeTrialActive) listOf(ActiveOfferType.TRIAL) else listOf()
        val pendingPlans = subscriptionsDataStore.pendingPlans?.let { json ->
            runCatching {
                pendingPlansListAdapter.fromJson(json)?.map { planJson ->
                    PendingPlan(
                        productId = planJson.productId,
                        billingPeriod = planJson.billingPeriod,
                        effectiveAt = planJson.effectiveAt,
                        status = planJson.status,
                        tier = SubscriptionTier.fromTierString(planJson.tier),
                    )
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return Subscription(
            productId = productId,
            billingPeriod = billingPeriod,
            startedAt = startedAt,
            expiresOrRenewsAt = expiresOrRenewsAt,
            status = status,
            platform = platform,
            activeOffers = activeOffers,
            pendingPlans = pendingPlans,
        )
    }

    override suspend fun setAccount(account: Account?) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.externalId = account?.externalId
        subscriptionsDataStore.email = account?.email
    }

    @OnIo
    override suspend fun getAccount(): Account? {
        val externalId = subscriptionsDataStore.externalId ?: return null
        val email = subscriptionsDataStore.email

        return Account(
            email = email,
            externalId = externalId,
        )
    }

    @OnIo
    override suspend fun canSupportEncryption(): Boolean {
        return subscriptionsDataStore.canUseEncryption()
    }

    override suspend fun setFeatures(
        basePlanId: String,
        features: Set<String>,
    ) = withContext(dispatcherProvider.io()) {
        val featuresMap = subscriptionsDataStore.subscriptionFeatures
            ?.let(featuresAdapter::fromJson)
            ?.toMutableMap() ?: mutableMapOf()

        featuresMap[basePlanId] = features

        subscriptionsDataStore.subscriptionFeatures = featuresAdapter.toJson(featuresMap)
    }

    @OnIo
    override suspend fun getFeatures(basePlanId: String): Set<String> {
        if (subscriptionsFeature.get().tierMessagingEnabled().isEnabled()) {
            // When flag is ON, try v2 first
            val v2Features = getFeaturesV2(basePlanId)
            if (v2Features.isNotEmpty()) {
                return v2Features.map { it.product }.toSet()
            }
            // Fallback to v1 for smooth runtime flag transitions (until fetcher runs on next app start)
        }
        // Use v1 storage
        return subscriptionsDataStore.subscriptionFeatures
            ?.let(featuresAdapter::fromJson)
            ?.get(basePlanId) ?: emptySet()
    }

    override suspend fun setFeaturesV2(
        basePlanId: String,
        features: Set<Entitlement>,
    ) = withContext(dispatcherProvider.io()) {
        val featuresMap = subscriptionsDataStore.subscriptionEntitlements
            ?.let(featuresV2Adapter::fromJson)
            ?.toMutableMap() ?: mutableMapOf()
        featuresMap[basePlanId] = features
        subscriptionsDataStore.subscriptionEntitlements = featuresV2Adapter.toJson(featuresMap)
    }

    @OnIo
    override suspend fun getFeaturesV2(basePlanId: String): Set<Entitlement> {
        return subscriptionsDataStore.subscriptionEntitlements
            ?.let(featuresV2Adapter::fromJson)
            ?.get(basePlanId) ?: emptySet()
    }

    private suspend fun updateSerpPromoCookie() = withContext(dispatcherProvider.io()) {
        val accessToken = subscriptionsDataStore.run { accessTokenV2 ?: accessToken }
        serpPromo.injectCookie(accessToken)
    }

    override suspend fun isFreeTrialActive(): Boolean {
        return subscriptionsDataStore.freeTrialActive
    }

    override suspend fun registerLocalPurchasedAt() {
        subscriptionsDataStore.localPurchasedAt = System.currentTimeMillis()
    }

    override suspend fun getLocalPurchasedAt(): Long? {
        return subscriptionsDataStore.localPurchasedAt
    }

    override suspend fun removeLocalPurchasedAt() {
        subscriptionsDataStore.localPurchasedAt = null
    }

    override suspend fun setPendingPlans(pendingPlans: List<PendingPlan>) = withContext(dispatcherProvider.io()) {
        subscriptionsDataStore.pendingPlans = if (pendingPlans.isEmpty()) {
            null
        } else {
            runCatching {
                pendingPlansListAdapter.toJson(
                    pendingPlans.map { plan ->
                        PendingPlanJson(
                            productId = plan.productId,
                            billingPeriod = plan.billingPeriod,
                            effectiveAt = plan.effectiveAt,
                            status = plan.status,
                            tier = plan.tier.value,
                        )
                    },
                )
            }.getOrNull()
        }
    }

    @OnIo
    override suspend fun getPendingPlans(): List<PendingPlan> {
        return subscriptionsDataStore.pendingPlans?.let { json ->
            runCatching {
                pendingPlansListAdapter.fromJson(json)?.map { planJson ->
                    PendingPlan(
                        productId = planJson.productId,
                        billingPeriod = planJson.billingPeriod,
                        effectiveAt = planJson.effectiveAt,
                        status = planJson.status,
                        tier = SubscriptionTier.fromTierString(planJson.tier),
                    )
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }
}

data class AccessToken(
    val jwt: String,
    val expiresAt: Instant,
)

data class RefreshToken(
    val jwt: String,
    val expiresAt: Instant,
)

data class Account(
    val email: String?,
    val externalId: String,
)

data class PendingPlan(
    val productId: String,
    val billingPeriod: String,
    val effectiveAt: Long,
    val status: String,
    val tier: SubscriptionTier,
)

data class Subscription(
    val productId: String, // this is the plan id returned by the backend
    val billingPeriod: String,
    val startedAt: Long,
    val expiresOrRenewsAt: Long,
    val status: SubscriptionStatus,
    val platform: String,
    val activeOffers: List<ActiveOfferType>,
    val pendingPlans: List<PendingPlan> = emptyList(),
) {
    fun isActive(): Boolean = status.isActive()

    val tier: SubscriptionTier
        get() = SubscriptionTier.fromPlanId(productId)

    // Computed from first pending plan (if any)
    val effectiveTier: SubscriptionTier
        get() = pendingPlans.firstOrNull()?.tier ?: tier

    val hasPendingChange: Boolean
        get() = pendingPlans.isNotEmpty()
}

fun SubscriptionStatus.isActive(): Boolean {
    return when (this) {
        AUTO_RENEWABLE, NOT_AUTO_RENEWABLE, GRACE_PERIOD -> true
        else -> false
    }
}

fun SubscriptionStatus.isExpired(): Boolean =
    this == EXPIRED || this == INACTIVE

fun SubscriptionStatus.isActiveOrWaiting(): Boolean {
    return this.isActive() || this == WAITING
}

fun List<Entitlement>.toProductList(): List<Product> {
    return try {
        this.mapNotNull { entitlement ->
            Product.entries.find { it.value == entitlement.product }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
