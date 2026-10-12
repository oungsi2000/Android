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

package com.duckduckgo.autofill.impl.importing

import com.duckduckgo.autofill.api.AutofillFeature
import com.duckduckgo.autofill.impl.importing.capability.PasswordImportAvailability
import com.duckduckgo.autofill.impl.store.InternalAutofillStore
import com.duckduckgo.autofill.impl.store.NeverSavedSiteRepository
import com.duckduckgo.browsermode.api.BrowserMode
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import kotlinx.coroutines.flow.firstOrNull
import logcat.logcat
import javax.inject.Inject

interface InBrowserImportPromo {
    suspend fun canShowPromo(
        credentialsAvailableForCurrentPage: Boolean,
        url: String?,
        browserMode: BrowserMode,
    ): Boolean
}

private const val TAG = "InBrowserImportPromo"

@ContributesBinding(AppScope::class)
class RealInBrowserImportPromo @Inject constructor(
    private val autofillStore: InternalAutofillStore,
    private val dispatchers: DispatcherProvider,
    private val neverSavedSiteRepository: NeverSavedSiteRepository,
    private val autofillFeature: AutofillFeature,
    private val passwordImportAvailability: PasswordImportAvailability,
    private val inBrowserPromoPreviousPromptsStore: InBrowserPromoPreviousPromptsStore,
) : InBrowserImportPromo {

    @OnIo
    override suspend fun canShowPromo(
        credentialsAvailableForCurrentPage: Boolean,
        url: String?,
        browserMode: BrowserMode,
    ): Boolean {
        if (credentialsAvailableForCurrentPage) {
            return false
        }

        // Fire mode is an ephemeral session and never offers to import passwords from Google.
        if (browserMode == BrowserMode.FIRE) {
            return false
        }

        if (url == null || url.isExcludedFromPromo()) {
            return false
        }

        if (inBrowserPromoPreviousPromptsStore.hasPromoBeenDisplayed(url)) {
            return false
        }

        if (featureEnabled().not()) {
            return false
        }

        if (autofillStore.hasEverImportedPasswords) {
            return false
        }

        if (autofillStore.hasDeclinedInBrowserPasswordImportPromo) {
            return false
        }

        autofillStore.getCredentialCount().firstOrNull()?.fold(
            onSuccess = { value ->
                if ((value) >= MAX_CREDENTIALS_FOR_PROMO) {
                    return false
                }
            },
            onFailure = {
                logcat(TAG) { "Credential count retrieval failed, fallback to disabled" }
                return false
            },
        ) ?: return false

        if (autofillStore.inBrowserImportPromoShownCount >= MAX_PROMO_SHOWN_COUNT) {
            return false
        }

        if (neverSavedSiteRepository.isInNeverSaveList(url)) {
            return false
        }

        if (passwordImportAvailability.canImport().not()) {
            return false
        }

        return true
    }

    private fun String.isExcludedFromPromo(): Boolean {
        return this.startsWith(EMAIL_PROTECTION_SETTINGS_URL_PREFIX)
    }

    private fun featureEnabled(): Boolean {
        if (autofillFeature.self().isEnabled().not()) return false
        if (autofillFeature.canPromoteImportGooglePasswordsInBrowser().isEnabled().not()) return false
        return true
    }

    companion object {
        const val MAX_PROMO_SHOWN_COUNT = 5
        const val MAX_CREDENTIALS_FOR_PROMO = 25
        private const val EMAIL_PROTECTION_SETTINGS_URL_PREFIX = "https://duckduckgo.com/email/"
    }
}
