/*
 * Copyright (c) 2023 DuckDuckGo
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

package com.duckduckgo.autofill.impl

import com.duckduckgo.autofill.api.AutofillCapabilityChecker
import com.duckduckgo.autofill.api.AutofillFeature
import com.duckduckgo.autofill.api.InternalTestUserChecker
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject

@ContributesBinding(AppScope::class)
class AutofillCapabilityCheckerImpl @Inject constructor(
    private val autofillFeature: AutofillFeature,
    private val internalTestUserChecker: InternalTestUserChecker,
    private val autofillGlobalCapabilityChecker: AutofillGlobalCapabilityChecker,
    private val dispatcherProvider: DispatcherProvider,
) : AutofillCapabilityChecker {

    @OnIo
    override suspend fun canInjectCredentialsToWebView(url: String): Boolean {
        if (!isSecureAutofillAvailable()) return false
        if (!isAutofillEnabledByConfiguration(url)) return false
        if (!isAutofillEnabledByUser()) return false

        if (isInternalTester()) return true

        return autofillFeature.canInjectCredentials().isEnabled()
    }

    @OnIo
    override suspend fun canSaveCredentialsFromWebView(url: String): Boolean {
        if (!isSecureAutofillAvailable()) return false
        if (!isAutofillEnabledByConfiguration(url)) return false
        if (!isAutofillEnabledByUser()) return false

        if (isInternalTester()) return true

        return autofillFeature.canSaveCredentials().isEnabled()
    }

    @OnIo
    override suspend fun canGeneratePasswordFromWebView(url: String): Boolean {
        if (!isSecureAutofillAvailable()) return false
        if (!isAutofillEnabledByConfiguration(url)) return false
        if (!isAutofillEnabledByUser()) return false

        if (isInternalTester()) return true

        return autofillFeature.canGeneratePasswords().isEnabled()
    }

    /**
     * Because the credential management screen handles the states where the user has toggled autofill off, or the device can't support it,
     * this feature is not dependent those checks.
     *
     * We purposely don't couple this check against [isSecureAutofillAvailable] or [isAutofillEnabledByUser].
     */
    @OnIo
    override suspend fun canAccessCredentialManagementScreen(): Boolean {
        if (isInternalTester()) return true
        if (!isGlobalFeatureEnabled()) return false
        return autofillFeature.canAccessCredentialManagement().isEnabled()
    }

    @OnIo
    private suspend fun isInternalTester(): Boolean {
        return internalTestUserChecker.isInternalTestUser
    }

    @OnIo
    private suspend fun isGlobalFeatureEnabled(): Boolean {
        return autofillFeature.self().isEnabled()
    }

    override suspend fun isAutofillEnabledByConfiguration(url: String) = autofillGlobalCapabilityChecker.isAutofillEnabledByConfiguration(url)

    private suspend fun isSecureAutofillAvailable() = autofillGlobalCapabilityChecker.isSecureAutofillAvailable()

    private suspend fun isAutofillEnabledByUser() = autofillGlobalCapabilityChecker.isAutofillEnabledByUser()
}
