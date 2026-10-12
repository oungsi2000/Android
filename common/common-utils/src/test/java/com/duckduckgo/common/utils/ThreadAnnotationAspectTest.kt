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
package com.duckduckgo.common.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import logcat.LogPriority
import logcat.LogPriority.WARN
import logcat.LogcatLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/** [SingleLiveEvent.call] is annotated with androidx's @MainThread and nothing else. */
@RunWith(AndroidJUnit4::class)
class ThreadAnnotationAspectTest {

    private val logs = mutableListOf<Triple<LogPriority, String, String>>()

    @Before
    fun setup() {
        LogcatLogger.install(
            object : LogcatLogger {
                override fun log(priority: LogPriority, tag: String, message: String) {
                    synchronized(logs) { logs += Triple(priority, tag, message) }
                }
            },
        )
    }

    @After
    fun tearDown() {
        LogcatLogger.uninstall()
    }

    @Test
    fun whenMainThreadFunctionRunsOffMainThreadThenWarns() {
        val event = SingleLiveEvent<String>()

        // LiveData itself rejects the call afterwards
        thread(name = "worker") { runCatching { event.call() } }.join()

        assertEquals(Triple(WARN, "SingleLiveEvent", "call is a main-thread function but runs on worker"), logs.first())
    }

    @Test
    fun whenMainThreadFunctionRunsOnMainThreadThenSilent() {
        SingleLiveEvent<String>().call()

        assertEquals(emptyList<Any>(), logs)
    }
}
