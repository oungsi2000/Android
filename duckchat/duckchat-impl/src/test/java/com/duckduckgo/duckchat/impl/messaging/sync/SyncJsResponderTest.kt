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
package com.duckduckgo.duckchat.impl.messaging.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.duckduckgo.js.messaging.api.JsMessage
import com.duckduckgo.js.messaging.api.JsMessaging
import logcat.LogPriority
import logcat.LogPriority.ERROR
import logcat.LogcatLogger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class SyncJsResponderTest {

    private val logs = mutableListOf<Pair<LogPriority, String>>()
    private val jsMessaging: JsMessaging = mock()
    private val testee = SyncJsResponder(jsMessaging, JsMessage("context", "feature", "method", JSONObject(), "id"), "feature")

    @Before
    fun setup() {
        LogcatLogger.install(
            object : LogcatLogger {
                override fun log(priority: LogPriority, tag: String, message: String) {
                    logs += priority to message
                }
            },
        )
    }

    @After
    fun tearDown() {
        LogcatLogger.uninstall()
    }

    @Test
    fun whenSendingSuccessFailsThenFailureIsLogged() {
        doThrow(IllegalStateException("boom")).`when`(jsMessaging).onResponse(any())

        testee.sendSuccess(JSONObject())

        assertEquals(listOf(ERROR to "DuckChat-Sync: failed to send success response: boom"), logs)
    }

    @Test
    fun whenSendingSuccessWorksThenNothingIsLogged() {
        testee.sendSuccess(JSONObject())

        assertEquals(emptyList<Any>(), logs)
    }
}
