/*
 * Copyright (c) 2026 DuckDuckGo
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

package com.duckduckgo.app.fire.promo

import com.duckduckgo.app.fire.promo.FireTabsPromos.Companion.FIRE_TABS_PROMO_MESSAGE_ID
import com.duckduckgo.app.fire.store.FireDataStore
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.remote.messaging.api.RemoteMessageModel
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject

/**
 * Local (client-side) state for the Fire Tabs tab-switcher banner.
 */
interface FireTabsPromos {
    companion object {
        const val FIRE_TABS_PROMO_MESSAGE_ID = "android_fire_tabs_promo"
    }

    /** True if the tab-switcher banner hasn't been shown/dismissed yet (show-once). */
    suspend fun canShowTabSwitcherPromo(): Boolean
    suspend fun onTabSwitcherPromoShown()
    suspend fun onFireModeEntered()
}

@ContributesBinding(AppScope::class)
class RealFireTabsPromos @Inject constructor(
    private val fireDataStore: FireDataStore,
    private val remoteMessageModel: RemoteMessageModel,
    private val dispatchers: DispatcherProvider,
) : FireTabsPromos {

    @OnIo
    override suspend fun canShowTabSwitcherPromo(): Boolean {
        return !fireDataStore.isTabSwitcherPromoDismissed()
    }

    @OnIo
    override suspend fun onTabSwitcherPromoShown() {
        fireDataStore.setTabSwitcherPromoDismissed(true)
    }

    @OnIo
    override suspend fun onFireModeEntered() {
        fireDataStore.setUsedFireMode(true)
        fireDataStore.setTabSwitcherPromoDismissed(true)
        val activeMessage = remoteMessageModel.getActiveMessage()
        if (activeMessage?.id == FIRE_TABS_PROMO_MESSAGE_ID) {
            remoteMessageModel.onMessageDismissed(activeMessage)
        }
    }
}
