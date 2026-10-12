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

package com.duckduckgo.app.anr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.duckduckgo.anrs.api.CrashLogger
import com.duckduckgo.app.anrs.store.UncaughtExceptionDao
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class RealCrashLoggerTest {

    private val uncaughtExceptionDao: UncaughtExceptionDao = mock()
    private val crash = CrashLogger.Crash("shortName", RuntimeException("Deliberate"))
    private val testee = RealCrashLogger(
        appBuildConfig = mock { whenever(it.versionName).thenReturn("1.0") },
        uncaughtExceptionDao = uncaughtExceptionDao,
        webViewVersionProvider = mock { whenever(it.getFullVersion()).thenReturn("1") },
        customTabDetector = mock(),
        processName = "main",
    )

    @Test
    fun whenLogCrashCalledOnMainThreadThenThrows() {
        assertThrows(IllegalStateException::class.java) { testee.logCrash(crash) }

        verify(uncaughtExceptionDao, never()).add(any())
    }

    @Test
    fun whenLogCrashCalledOffMainThreadThenCrashStored() {
        thread { testee.logCrash(crash) }.join()

        verify(uncaughtExceptionDao).add(any())
    }
}
