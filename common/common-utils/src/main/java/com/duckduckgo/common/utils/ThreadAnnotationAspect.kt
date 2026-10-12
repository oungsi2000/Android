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

import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.annotation.MainThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import io.github.molelabs.aspectk.runtime.Aspect
import io.github.molelabs.aspectk.runtime.Before
import io.github.molelabs.aspectk.runtime.JoinPoint
import logcat.LogPriority.WARN
import logcat.logcat

/**
 * Advice on annotations the code base already carries. Nothing has to be added to a function:
 * it is enough that its module applies the `io.github.mole-labs.aspectk` plugin.
 */
@Aspect
object ThreadAnnotationAspect {
    @Before(WorkerThread::class)
    fun onWorkerThread(joinPoint: JoinPoint) {
        if (isMainThread() == true) warn(joinPoint, "@WorkerThread", "the main thread")
    }

    @Before(UiThread::class, MainThread::class)
    fun onMainThread(joinPoint: JoinPoint) {
        if (isMainThread() == false) warn(joinPoint, "a main-thread", Thread.currentThread().name)
    }

    @Before(JavascriptInterface::class)
    fun onJavascriptCall(joinPoint: JoinPoint) {
        logcat(joinPoint.tag()) { "JS called ${joinPoint.signature.methodName}" }
    }

    // null where there is no main looper to compare with: in a plain JVM unit test Looper is either
    // a stub that throws or one that returns null
    private fun isMainThread(): Boolean? = runCatching { Looper.getMainLooper()?.let { it == Looper.myLooper() } }.getOrNull()

    private fun warn(joinPoint: JoinPoint, annotation: String, thread: String) {
        logcat(joinPoint.tag(), WARN) { "${joinPoint.signature.methodName} is $annotation function but runs on $thread" }
    }
}

internal fun JoinPoint.tag(): String = target?.javaClass?.simpleName?.takeIf { it.isNotEmpty() } ?: "AspectK"
