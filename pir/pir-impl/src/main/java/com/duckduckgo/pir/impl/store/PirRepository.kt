/*
 * Copyright (c) 2025 DuckDuckGo
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

package com.duckduckgo.pir.impl.store

import com.duckduckgo.common.utils.CurrentTimeProvider
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.pir.impl.common.refreshedWith
import com.duckduckgo.pir.impl.common.toKey
import com.duckduckgo.pir.impl.models.Address
import com.duckduckgo.pir.impl.models.AddressCityState
import com.duckduckgo.pir.impl.models.Broker
import com.duckduckgo.pir.impl.models.ExtractedProfile
import com.duckduckgo.pir.impl.models.MirrorSite
import com.duckduckgo.pir.impl.models.ProfileQuery
import com.duckduckgo.pir.impl.models.scheduling.BrokerSchedulingConfig
import com.duckduckgo.pir.impl.models.scheduling.JobRecord.EmailConfirmationJobRecord.EmailData
import com.duckduckgo.pir.impl.pixels.PirPixelSender
import com.duckduckgo.pir.impl.service.DbpService
import com.duckduckgo.pir.impl.service.DbpService.PirEmailConfirmationDataRequest
import com.duckduckgo.pir.impl.service.DbpService.PirJsonBroker
import com.duckduckgo.pir.impl.store.PirRepository.BrokerJson
import com.duckduckgo.pir.impl.store.PirRepository.EmailConfirmationLinkFetchStatus
import com.duckduckgo.pir.impl.store.PirRepository.GeneratedEmailData
import com.duckduckgo.pir.impl.store.db.BrokerDao
import com.duckduckgo.pir.impl.store.db.BrokerEntity
import com.duckduckgo.pir.impl.store.db.BrokerJsonDao
import com.duckduckgo.pir.impl.store.db.BrokerJsonEtag
import com.duckduckgo.pir.impl.store.db.BrokerOptOut
import com.duckduckgo.pir.impl.store.db.BrokerScan
import com.duckduckgo.pir.impl.store.db.BrokerSchedulingConfigEntity
import com.duckduckgo.pir.impl.store.db.ExtractedProfileDao
import com.duckduckgo.pir.impl.store.db.MirrorSiteEntity
import com.duckduckgo.pir.impl.store.db.StoredExtractedProfile
import com.duckduckgo.pir.impl.store.db.UserName
import com.duckduckgo.pir.impl.store.db.UserProfile
import com.duckduckgo.pir.impl.store.db.UserProfileDao
import com.duckduckgo.pir.impl.store.secure.PirSecureStorageDatabaseFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.logcat
import java.util.concurrent.TimeUnit

interface PirRepository {
    /**
     * @return Returns `true` if the repository and underlying database is available for use, `false` otherwise.
     */
    suspend fun isRepositoryAvailable(): Boolean

    /**
     * Returns the time in ms when the feature was first allowed to be run on the user's device. If the feature is not allowed to be run, we get 0L.
     */
    suspend fun getFeatureReceivedMs(): Long

    /**
     * Set the time in ms when the feature was first allowed to be run on the user's device
     */
    suspend fun setFeatureReceivedMs(long: Long)

    suspend fun getCurrentMainEtag(): String?

    suspend fun updateMainEtag(etag: String?)

    suspend fun updateBrokerJsons(brokers: List<BrokerJson>)

    /**
     * Clears all broker configuration: both the download etags and the parsed broker details
     * (details, scan/optOut steps, scheduling config, mirror sites).
     */
    suspend fun clearAllBrokerData()

    suspend fun getAllLocalBrokerJsons(): List<BrokerJson>

    suspend fun getStoredBrokersCount(): Int

    suspend fun getAllActiveBrokers(): List<String>

    suspend fun getAllActiveBrokerObjects(): List<Broker>

    suspend fun getAllBrokerObjects(): List<Broker>

    suspend fun getBrokerForName(name: String): Broker?

    suspend fun getAllMirrorSitesForBroker(brokerName: String): List<MirrorSite>

    suspend fun getAllMirrorSites(): List<MirrorSite>

    suspend fun getAllBrokersForScan(): List<String>

    /**
     * Returns a map of broker names to their opt-out URLs.
     */
    suspend fun getAllBrokerOptOutUrls(): Map<String, String?>

    suspend fun getEtagForFilename(fileName: String): String?

    suspend fun updateBrokerData(
        fileName: String,
        broker: PirJsonBroker,
    )

    suspend fun getBrokerSchedulingConfig(brokerName: String): BrokerSchedulingConfig?

    suspend fun getAllBrokerSchedulingConfigs(): List<BrokerSchedulingConfig>

    suspend fun getBrokerScanSteps(name: String): String?

    suspend fun getBrokerOptOutSteps(name: String): String?

    /**
     * Returns a list of broker names for which an extract profile result is available.
     *
     * @param formOptOutOnly - True to only return the brokers with a form opt-out type.
     */
    suspend fun getBrokersForOptOut(formOptOutOnly: Boolean): List<String>

    /**
     * This method saves the extracted profiles to the database.
     *
     * Profiles we already store (see indices of the table) are refreshed with the newly scraped
     * attributes, keeping their local id, date added and deprecated flag. Extras are merged into
     * the stored extras so that a key missing from this scrape keeps the value we already hold.
     */
    suspend fun saveExtractedProfiles(extractedProfiles: List<ExtractedProfile>)

    /**
     * Returns a list of all [ExtractedProfile] found for this particular broker.
     *
     * @param brokerName - Name of the broker
     *  @param profileQueryId - Profile id of the user submitted profile
     */
    suspend fun getExtractedProfiles(
        brokerName: String,
        profileQueryId: Long,
    ): List<ExtractedProfile>

    suspend fun getExtractedProfile(
        extractedProfileId: Long,
    ): ExtractedProfile?

    fun getAllExtractedProfilesFlow(): Flow<List<ExtractedProfile>>

    suspend fun getAllExtractedProfiles(): List<ExtractedProfile>

    suspend fun markExtractedProfileAsDeprecated(extractedProfileId: Long)

    suspend fun getUserProfileQuery(id: Long): ProfileQuery?

    /**
     * Returns all user profile queries stored in the database, including deprecated ones.
     * In some cases we still want to run jobs on them.
     */
    suspend fun getAllUserProfileQueries(): List<ProfileQuery>

    /**
     * Returns all user profile queries that are not marked as deprecated.
     */
    suspend fun getValidUserProfileQueries(): List<ProfileQuery>

    suspend fun getUserProfileQueriesWithIds(ids: List<Long>): List<ProfileQuery>

    suspend fun deleteAllUserProfilesQueries()

    suspend fun replaceUserProfile(profileQuery: ProfileQuery)

    suspend fun updateProfileQueries(
        profileQueriesToAdd: List<ProfileQuery>,
        profileQueriesToUpdate: List<ProfileQuery>,
        profileQueryIdsToDelete: List<Long>,
    ): Boolean

    suspend fun getEmailForBroker(dataBroker: String): GeneratedEmailData

    suspend fun getEmailConfirmationLinkStatus(emailData: List<EmailData>): Map<EmailData, EmailConfirmationLinkFetchStatus>

    suspend fun deleteEmailData(emailData: List<EmailData>)

    suspend fun getCustomStatsPixelsLastSentMs(): Long

    suspend fun setCustomStatsPixelsLastSentMs(timeMs: Long)

    suspend fun getLastPirDauPixelTimeMs(): Long

    suspend fun setLastPirDauPixelTimeMs(timeMs: Long)

    suspend fun getLastPirWauPixelTimeMs(): Long

    suspend fun setLastPirWauPixelTimeMs(timeMs: Long)

    suspend fun getLastPirMauPixelTimeMs(): Long

    suspend fun setLastPirMauPixelTimeMs(timeMs: Long)

    suspend fun getLastPirInteractionDauPixelTimeMs(): Long

    suspend fun setLastPirInteractionDauPixelTimeMs(timeMs: Long)

    suspend fun getLastPirInteractionWauPixelTimeMs(): Long

    suspend fun setLastPirInteractionWauPixelTimeMs(timeMs: Long)

    suspend fun getLastPirInteractionMauPixelTimeMs(): Long

    suspend fun setLastPirInteractionMauPixelTimeMs(timeMs: Long)

    suspend fun getWeeklyStatLastSentMs(): Long

    suspend fun setWeeklyStatLastSentMs(timeMs: Long)

    suspend fun setHasBrokerConfigBeenManuallyUpdated(updated: Boolean)

    suspend fun hasBrokerConfigBeenManuallyUpdated(): Boolean

    suspend fun latestBackgroundScanRunInMs(): Long

    suspend fun setLatestBackgroundScanRunInMs(timeMs: Long)

    /**
     * This method deletes all data in the PIR repository, including brokers, extracted profiles, user profiles and resets the data store.
     */
    suspend fun clearAllData()

    /**
     * This method deletes all data in the PIR repository that is related to the user: user profiles, extracted profiles and
     * resets the user data in the data store.
     */
    suspend fun clearUserData()

    data class GeneratedEmailData(
        val emailAddress: String,
        val pattern: String,
    )

    data class BrokerJson(
        val fileName: String,
        val etag: String,
    )

    sealed class EmailConfirmationLinkFetchStatus(val statusString: String) {

        data class Ready(
            /**
             * This represents the data we receive from the backend where the key could be "link" or "verification_code"
             * And the value is the actual link or code.
             */
            val data: Map<String, String>,
            val emailReceivedAtMs: Long,
        ) : EmailConfirmationLinkFetchStatus(STATUS_READY)

        data object Pending : EmailConfirmationLinkFetchStatus(STATUS_PENDING)

        data class Unknown(
            val errorCode: String = "unknown_error",
        ) : EmailConfirmationLinkFetchStatus(STATUS_UNKNOWN)

        data class Error(
            val errorCode: String,
            val error: String,
        ) : EmailConfirmationLinkFetchStatus(STATUS_ERROR)

        companion object {
            const val STATUS_READY = "ready"
            const val STATUS_PENDING = "pending"
            const val STATUS_UNKNOWN = "unknown"
            const val STATUS_ERROR = "error"

            fun fromString(
                status: String,
                emailReceivedAtMs: Long = 0L,
                data: Map<String, String>? = null,
                errorCode: String? = null,
                error: String? = null,
            ): EmailConfirmationLinkFetchStatus = when (status.lowercase()) {
                STATUS_READY -> Ready(
                    data = data ?: emptyMap(),
                    emailReceivedAtMs = emailReceivedAtMs,
                )

                STATUS_PENDING -> Pending
                STATUS_UNKNOWN -> if (errorCode != null) Unknown(errorCode) else Unknown()
                STATUS_ERROR -> Error(errorCode ?: "unknown_error", error ?: "Unknown error")
                else -> Unknown()
            }
        }
    }
}

class RealPirRepository(
    private val dispatcherProvider: DispatcherProvider,
    private val pirDataStore: PirDataStore,
    private val currentTimeProvider: CurrentTimeProvider,
    private val databaseFactory: PirSecureStorageDatabaseFactory,
    private val dbpService: DbpService,
    private val pixelSender: PirPixelSender,
    appCoroutineScope: CoroutineScope,
) : PirRepository {

    private val database: Deferred<PirDatabase?> = appCoroutineScope.async(start = CoroutineStart.LAZY) {
        prepareDatabase()
    }

    // Kotlin defaults are needed here so that addresses stored before a field was introduced still parse.
    private val addressCityStateAdapter by lazy {
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(AddressCityState::class.java)
    }

    override suspend fun isRepositoryAvailable(): Boolean = database.await() != null

    @OnIo
    override suspend fun getFeatureReceivedMs(): Long {
        return pirDataStore.featureReceivedMs
    }

    @OnIo
    override suspend fun setFeatureReceivedMs(long: Long) {
        pirDataStore.featureReceivedMs = long
    }

    override suspend fun getCurrentMainEtag(): String? = pirDataStore.mainConfigEtag

    override suspend fun updateMainEtag(etag: String?) {
        pirDataStore.mainConfigEtag = etag
    }

    @OnIo
    override suspend fun updateBrokerJsons(brokers: List<BrokerJson>) {
        brokers
            .map {
                BrokerJsonEtag(
                    fileName = it.fileName,
                    etag = it.etag,
                )
            }.also {
                brokerJsonDao()?.insertBrokerJsonEtags(it)
            }
    }

    @OnIo
    override suspend fun clearAllBrokerData() {
        brokerJsonDao()?.deleteAll()
        brokerDao()?.deleteAll()
    }

    @OnIo
    override suspend fun getAllLocalBrokerJsons(): List<BrokerJson> {
        return brokerJsonDao()?.getAllBrokers()?.map {
            BrokerJson(
                fileName = it.fileName,
                etag = it.etag,
            )
        }.orEmpty()
    }

    @OnIo
    override suspend fun getStoredBrokersCount(): Int {
        return brokerJsonDao()?.getAllBrokersCount() ?: 0
    }

    @OnIo
    override suspend fun getAllActiveBrokers(): List<String> {
        return brokerDao()?.getAllActiveBrokers()?.map {
            it.name
        }.orEmpty()
    }

    @OnIo
    override suspend fun getAllActiveBrokerObjects(): List<Broker> {
        return brokerDao()?.getAllActiveBrokers()?.map { it.toBroker() }.orEmpty()
    }

    @OnIo
    override suspend fun getAllBrokerObjects(): List<Broker> {
        return brokerDao()?.getAllBrokers()?.map { it.toBroker() }.orEmpty()
    }

    @OnIo
    override suspend fun getBrokerForName(name: String): Broker? {
        return brokerDao()?.getBrokerDetails(name)?.toBroker()
    }

    override suspend fun getAllMirrorSitesForBroker(brokerName: String): List<MirrorSite> =
        brokerDao()?.getAllMirrorSitesForBroker(brokerName)?.map {
            MirrorSite(
                name = it.name,
                url = it.url,
                addedAt = it.addedAt,
                removedAt = it.removedAt,
                optOutUrl = it.optOutUrl,
                parentSite = it.parentSite,
            )
        }.orEmpty()

    override suspend fun getAllMirrorSites(): List<MirrorSite> =
        brokerDao()?.getAllMirrorSites()?.map {
            MirrorSite(
                name = it.name,
                url = it.url,
                addedAt = it.addedAt,
                removedAt = it.removedAt,
                optOutUrl = it.optOutUrl,
                parentSite = it.parentSite,
            )
        }.orEmpty()

    @OnIo
    override suspend fun getAllBrokersForScan(): List<String> {
        return brokerDao()?.getAllBrokersNamesWithScanSteps().orEmpty()
    }

    @OnIo
    override suspend fun getAllBrokerOptOutUrls(): Map<String, String?> {
        val brokerOptOuts = brokerDao()?.getAllBrokerOptOuts()
        return brokerOptOuts?.associate { it.brokerName to it.optOutUrl }.orEmpty()
    }

    @OnIo
    override suspend fun getEtagForFilename(fileName: String): String {
        return brokerJsonDao()?.getEtag(fileName).orEmpty()
    }

    @OnIo
    override suspend fun updateBrokerData(
        fileName: String,
        broker: PirJsonBroker,
    ) {
        brokerDao()?.upsert(
            broker =
            BrokerEntity(
                name = broker.name,
                fileName = fileName,
                url = broker.url,
                version = broker.version,
                parent = broker.parent,
                addedDatetime = broker.addedDatetime,
                removedAt = broker.removedAt ?: 0L,
            ),
            brokerScan =
            BrokerScan(
                brokerName = broker.name,
                stepsJson = broker.steps.first { it.contains("\"stepType\":\"scan\"") },
            ),
            brokerOptOut =
            BrokerOptOut(
                brokerName = broker.name,
                stepsJson = broker.steps.first { it.contains("\"stepType\":\"optOut\"") },
                optOutUrl = broker.optOutUrl,
            ),
            schedulingConfig =
            BrokerSchedulingConfigEntity(
                brokerName = broker.name,
                retryError = broker.schedulingConfig.retryError,
                confirmOptOutScan = broker.schedulingConfig.confirmOptOutScan,
                maintenanceScan = broker.schedulingConfig.maintenanceScan,
                maxAttempts = broker.schedulingConfig.maxAttempts,
            ),
            mirrorSiteEntity =
            broker.mirrorSites.map {
                MirrorSiteEntity(
                    name = it.name,
                    url = it.url,
                    addedAt = it.addedAt,
                    removedAt = it.removedAt ?: 0L,
                    optOutUrl = it.optOutUrl.orEmpty(),
                    parentSite = broker.name,
                )
            },
        )
    }

    @OnIo
    override suspend fun getBrokerSchedulingConfig(brokerName: String): BrokerSchedulingConfig? {
        return brokerDao()?.getSchedulingConfig(brokerName)?.run {
            BrokerSchedulingConfig(
                brokerName = this.brokerName,
                retryErrorInMillis = TimeUnit.HOURS.toMillis(this.retryError.toLong()),
                confirmOptOutScanInMillis = TimeUnit.HOURS.toMillis(this.confirmOptOutScan.toLong()),
                maintenanceScanInMillis = TimeUnit.HOURS.toMillis(this.maintenanceScan.toLong()),
                maxAttempts = this.maxAttempts ?: -1,
            )
        }
    }

    @OnIo
    override suspend fun getAllBrokerSchedulingConfigs(): List<BrokerSchedulingConfig> {
        return brokerDao()?.getAllSchedulingConfigs()?.map {
            BrokerSchedulingConfig(
                brokerName = it.brokerName,
                retryErrorInMillis = TimeUnit.HOURS.toMillis(it.retryError.toLong()),
                confirmOptOutScanInMillis = TimeUnit.HOURS.toMillis(it.confirmOptOutScan.toLong()),
                maintenanceScanInMillis = TimeUnit.HOURS.toMillis(it.maintenanceScan.toLong()),
                maxAttempts = it.maxAttempts ?: -1,
            )
        }.orEmpty()
    }

    @OnIo
    override suspend fun getBrokerScanSteps(name: String): String? {
        return brokerDao()?.getScanJson(name)
    }

    @OnIo
    override suspend fun getBrokerOptOutSteps(name: String): String? {
        return brokerDao()?.getOptOutJson(name)
    }

    @OnIo
    override suspend fun getBrokersForOptOut(formOptOutOnly: Boolean): List<String> {
        return extractedProfileDao()
            ?.getAllExtractedProfiles()
            ?.map {
                it.brokerName
            }?.distinct()
            ?.run {
                if (formOptOutOnly) {
                    this.filter {
                        brokerDao()
                            ?.getOptOutJson(it)
                            ?.contains("\"optOutType\":\"formOptOut\"") == true
                    }
                } else {
                    this
                }
            }.orEmpty()
    }

    @OnIo
    override suspend fun saveExtractedProfiles(extractedProfiles: List<ExtractedProfile>) {
        if (extractedProfiles.isEmpty()) {
            return
        }

        val db = database.await() ?: return
        val userProfileDao = db.userProfileDao()
        val dao = db.extractedProfileDao()
        val profileQueryId = extractedProfiles.first().profileQueryId

        db.runInTransaction {
            if (userProfileDao.getUserProfile(profileQueryId)?.deprecated == true) {
                // we should not store any new extracted profiles for a deprecated user profile
                // also don't mark them as deprecated as we still want to show them on the UI
                return@runInTransaction
            }

            val storedProfiles = extractedProfiles
                .map { it.brokerName }
                .distinct()
                .flatMap { dao.getExtractedProfilesForBrokerAndProfile(it, profileQueryId) }
                .map { it.toExtractedProfile() }
                .associateBy { it.toKey() }

            val (alreadyStored, newlyFound) = extractedProfiles.partition { it.toKey() in storedProfiles }

            dao.insertNewExtractedProfiles(newlyFound.map { it.toStoredExtractedProfile() })
            dao.updateExtractedProfiles(
                alreadyStored.map { scraped ->
                    storedProfiles.getValue(scraped.toKey()).refreshedWith(scraped).toStoredExtractedProfile()
                },
            )
        }
    }

    @OnIo
    override suspend fun getExtractedProfiles(
        brokerName: String,
        profileQueryId: Long,
    ): List<ExtractedProfile> {
        return extractedProfileDao()
            ?.getExtractedProfilesForBrokerAndProfile(
                brokerName,
                profileQueryId,
            )?.map {
                it.toExtractedProfile()
            }.orEmpty()
    }

    @OnIo
    override suspend fun getExtractedProfile(
        extractedProfileId: Long,
    ): ExtractedProfile? {
        return extractedProfileDao()?.getExtractedProfile(extractedProfileId)?.toExtractedProfile()
    }

    override fun getAllExtractedProfilesFlow(): Flow<List<ExtractedProfile>> {
        return flow {
            emitAll(
                extractedProfileDao()?.getAllExtractedProfileFlow()?.map { list ->
                    list.map {
                        it.toExtractedProfile()
                    }
                } ?: flowOf(emptyList()),
            )
        }
    }

    @OnIo
    override suspend fun getAllExtractedProfiles(): List<ExtractedProfile> {
        return extractedProfileDao()?.getAllExtractedProfiles()?.map {
            it.toExtractedProfile()
        }.orEmpty()
    }

    @OnIo
    override suspend fun markExtractedProfileAsDeprecated(extractedProfileId: Long) {
        extractedProfileDao()?.updateExtractedProfileDeprecated(extractedProfileId, deprecated = true)
    }

    @OnIo
    override suspend fun getUserProfileQuery(id: Long): ProfileQuery? {
        return userProfileDao()?.getUserProfile(id)?.toProfileQuery()
    }

    @OnIo
    override suspend fun getAllUserProfileQueries(): List<ProfileQuery> {
        return userProfileDao()?.getAllUserProfiles()?.map {
            it.toProfileQuery()
        }.orEmpty()
    }

    @OnIo
    override suspend fun getValidUserProfileQueries(): List<ProfileQuery> {
        return userProfileDao()?.getValidUserProfiles()?.map {
            it.toProfileQuery()
        }.orEmpty()
    }

    @OnIo
    override suspend fun getUserProfileQueriesWithIds(ids: List<Long>): List<ProfileQuery> {
        return userProfileDao()?.getUserProfilesWithIds(ids)?.map {
            it.toProfileQuery()
        }.orEmpty()
    }

    @OnIo
    override suspend fun deleteAllUserProfilesQueries() {
        userProfileDao()?.deleteAllProfiles()
        extractedProfileDao()?.deleteAllExtractedProfiles()
    }

    private fun UserProfile.toProfileQuery(): ProfileQuery =
        ProfileQuery(
            id = this.id,
            firstName = this.userName.firstName,
            lastName = this.userName.lastName,
            city = this.addresses.city,
            state = this.addresses.state,
            addresses =
            listOf(
                Address(
                    city = this.addresses.city,
                    state = this.addresses.state,
                ),
            ),
            birthYear = this.birthYear,
            fullName =
            this.userName.middleName?.let { middleName ->
                "${this.userName.firstName} $middleName ${this.userName.lastName}"
            } ?: "${this.userName.firstName} ${this.userName.lastName}",
            age = currentTimeProvider.localDateTimeNow().year - this.birthYear,
            deprecated = this.deprecated,
        )

    @OnIo
    override suspend fun replaceUserProfile(profileQuery: ProfileQuery) {
        userProfileDao()?.deleteAllProfiles()
        userProfileDao()?.insertUserProfile(profileQuery.toUserProfile())
    }

    @OnIo
    override suspend fun updateProfileQueries(
        profileQueriesToAdd: List<ProfileQuery>,
        profileQueriesToUpdate: List<ProfileQuery>,
        profileQueryIdsToDelete: List<Long>,
    ): Boolean {
        return try {
            userProfileDao()?.updateUserProfiles(
                profilesToAdd = profileQueriesToAdd.map { query -> query.toUserProfile() },
                profilesToUpdate = profileQueriesToUpdate.map { query -> query.toUserProfile() },
                profileIdsToDelete = profileQueryIdsToDelete,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    @OnIo
    override suspend fun getEmailForBroker(dataBroker: String): GeneratedEmailData {
        return dbpService.getEmail(brokerDao()?.getBrokerDetails(dataBroker)!!.url).run {
            GeneratedEmailData(
                emailAddress,
                pattern,
            )
        }
    }

    override suspend fun getEmailConfirmationLinkStatus(emailData: List<EmailData>): Map<EmailData, EmailConfirmationLinkFetchStatus> =
        withContext(dispatcherProvider.io()) {
            val batchedEmailData = emailData.chunked(EMAIL_DATA_BATCH_SIZE)
            logcat { "PIR-EMAIL-CONFIRMATION: total size to fetch: ${emailData.size}" }
            return@withContext batchedEmailData.map { emailDataSubList ->
                logcat { "PIR-EMAIL-CONFIRMATION: batch size to fetch: ${emailDataSubList.size}" }
                async {
                    // For more information https://github.com/duckduckgo/Android/pull/6847#discussion_r2395023539
                    dbpService.getEmailConfirmationLinkStatus(emailDataSubList.toRequest()).items.associate { response ->
                        val key =
                            EmailData(
                                email = response.email,
                                attemptId = response.attemptId,
                            )
                        val value = EmailConfirmationLinkFetchStatus.fromString(
                            status = response.status,
                            emailReceivedAtMs = TimeUnit.SECONDS.toMillis(response.emailReceivedAt),
                            data = response.data.associate {
                                it.name to it.value
                            },
                            errorCode = response.errorCode,
                            error = response.error,
                        )
                        key to value
                    }
                }
            }.awaitAll().reduce { acc, map -> acc + map }
        }

    override suspend fun deleteEmailData(emailData: List<EmailData>) =
        withContext(dispatcherProvider.io()) {
            logcat { "PIR-EMAIL-CONFIRMATION: total size to delete: ${emailData.size}" }
            emailData
                .chunked(EMAIL_DATA_BATCH_SIZE)
                .forEach { batch ->
                    logcat { "PIR-EMAIL-CONFIRMATION: batch size to delete: ${batch.size}" }
                    dbpService.deleteEmailData(batch.toRequest())
                }
            return@withContext
        }

    @OnIo
    override suspend fun getCustomStatsPixelsLastSentMs(): Long {
        return pirDataStore.customStatsPixelsLastSentMs
    }

    override suspend fun setCustomStatsPixelsLastSentMs(timeMs: Long) = withContext(dispatcherProvider.io()) {
        pirDataStore.customStatsPixelsLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getLastPirDauPixelTimeMs(): Long {
        return pirDataStore.dauLastSentMs
    }

    @OnIo
    override suspend fun setLastPirDauPixelTimeMs(timeMs: Long) {
        pirDataStore.dauLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getLastPirWauPixelTimeMs(): Long {
        return pirDataStore.wauLastSentMs
    }

    @OnIo
    override suspend fun setLastPirWauPixelTimeMs(timeMs: Long) {
        pirDataStore.wauLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getLastPirMauPixelTimeMs(): Long {
        return pirDataStore.mauLastSentMs
    }

    @OnIo
    override suspend fun setLastPirMauPixelTimeMs(timeMs: Long) {
        pirDataStore.mauLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getLastPirInteractionDauPixelTimeMs(): Long {
        return pirDataStore.interactionDauLastSentMs
    }

    @OnIo
    override suspend fun setLastPirInteractionDauPixelTimeMs(timeMs: Long) {
        pirDataStore.interactionDauLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getLastPirInteractionWauPixelTimeMs(): Long {
        return pirDataStore.interactionWauLastSentMs
    }

    @OnIo
    override suspend fun setLastPirInteractionWauPixelTimeMs(timeMs: Long) {
        pirDataStore.interactionWauLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getLastPirInteractionMauPixelTimeMs(): Long {
        return pirDataStore.interactionMauLastSentMs
    }

    @OnIo
    override suspend fun setLastPirInteractionMauPixelTimeMs(timeMs: Long) {
        pirDataStore.interactionMauLastSentMs = timeMs
    }

    @OnIo
    override suspend fun getWeeklyStatLastSentMs(): Long {
        return pirDataStore.weeklyStatLastSentMs
    }

    @OnIo
    override suspend fun setWeeklyStatLastSentMs(timeMs: Long) {
        pirDataStore.weeklyStatLastSentMs = timeMs
    }

    @OnIo
    override suspend fun setHasBrokerConfigBeenManuallyUpdated(updated: Boolean) {
        pirDataStore.hasBrokerConfigBeenManuallyUpdated = updated
    }

    @OnIo
    override suspend fun hasBrokerConfigBeenManuallyUpdated(): Boolean {
        return pirDataStore.hasBrokerConfigBeenManuallyUpdated
    }

    @OnIo
    override suspend fun latestBackgroundScanRunInMs(): Long {
        return pirDataStore.latestBackgroundScanRunInMs
    }

    @OnIo
    override suspend fun setLatestBackgroundScanRunInMs(timeMs: Long) {
        pirDataStore.latestBackgroundScanRunInMs = timeMs
    }

    @OnIo
    override suspend fun clearAllData() {
        brokerJsonDao()?.deleteAll()
        brokerDao()?.deleteAll()
        extractedProfileDao()?.deleteAllExtractedProfiles()
        userProfileDao()?.deleteAllProfiles()
        pirDataStore.reset()
    }

    @OnIo
    override suspend fun clearUserData() {
        extractedProfileDao()?.deleteAllExtractedProfiles()
        userProfileDao()?.deleteAllProfiles()
        pirDataStore.resetUserData()
    }

    private fun List<EmailData>.toRequest(): PirEmailConfirmationDataRequest =
        PirEmailConfirmationDataRequest(
            items =
            this.map {
                PirEmailConfirmationDataRequest.RequestEmailData(
                    email = it.email,
                    attemptId = it.attemptId,
                )
            },
        )

    private fun StoredExtractedProfile.toExtractedProfile(): ExtractedProfile =
        ExtractedProfile(
            dbId = this.id,
            profileUrl = this.profileUrl,
            profileQueryId = this.profileQueryId,
            brokerName = this.brokerName,
            name = this.name,
            alternativeNames = this.alternativeNames,
            age = this.age,
            addresses =
            this.addresses.mapNotNull {
                addressCityStateAdapter.fromJson(it)
            },
            phoneNumbers = this.phoneNumbers,
            relatives = this.relatives,
            identifier = this.identifier,
            reportId = this.reportId,
            email = this.email,
            fullName = this.fullName,
            dateAddedInMillis = this.dateAddedInMillis,
            deprecated = this.deprecated,
            extras = this.extras,
        )

    private fun ExtractedProfile.toStoredExtractedProfile(): StoredExtractedProfile =
        StoredExtractedProfile(
            id = this.dbId,
            profileQueryId = this.profileQueryId,
            brokerName = this.brokerName,
            name = this.name,
            alternativeNames = this.alternativeNames,
            age = this.age,
            addresses =
            this.addresses.mapNotNull {
                addressCityStateAdapter.toJson(it)
            },
            phoneNumbers = this.phoneNumbers,
            relatives = this.relatives,
            reportId = this.reportId,
            email = this.email,
            fullName = this.fullName,
            profileUrl = this.profileUrl,
            identifier = this.identifier,
            dateAddedInMillis =
            if (this.dateAddedInMillis == 0L) {
                currentTimeProvider.currentTimeMillis()
            } else {
                this.dateAddedInMillis
            },
            deprecated = this.deprecated,
            extras = this.extras,
        )

    private fun ProfileQuery.toUserProfile(): UserProfile =
        UserProfile(
            id = this.id,
            userName =
            UserName(
                firstName = this.firstName,
                lastName = this.lastName,
                middleName = this.middleName,
            ),
            addresses =
            com.duckduckgo.pir.impl.store.db.Address(
                city = this.city,
                state = this.state,
            ),
            birthYear = this.birthYear,
            deprecated = this.deprecated,
        )

    private fun BrokerEntity.toBroker() = Broker(
        name = name,
        fileName = fileName,
        url = url,
        version = version,
        parent = parent,
        addedDatetime = addedDatetime,
        removedAt = removedAt,
    )

    private suspend fun prepareDatabase(): PirDatabase? {
        val database = databaseFactory.getDatabase()
        return if (database != null && database.databaseContentsAreReadable()) {
            database
        } else {
            pixelSender.reportSecureStorageUnavailable()
            logcat(ERROR) { "PIR-DB: PIR database is not readable" }
            null
        }
    }

    private fun PirDatabase.databaseContentsAreReadable(): Boolean {
        return kotlin.runCatching {
            // Try to read from the database to verify it's accessible
            brokerJsonDao().getAllBrokersCount()
            true
        }.getOrElse {
            logcat(ERROR) { "PIR-DB: Error reading from PIR database: ${it.message}" }
            false
        }
    }

    private suspend fun brokerJsonDao(): BrokerJsonDao? = database.await()?.brokerJsonDao()

    private suspend fun brokerDao(): BrokerDao? = database.await()?.brokerDao()

    private suspend fun extractedProfileDao(): ExtractedProfileDao? = database.await()?.extractedProfileDao()

    private suspend fun userProfileDao(): UserProfileDao? = database.await()?.userProfileDao()

    companion object {
        private const val EMAIL_DATA_BATCH_SIZE = 100
    }
}
