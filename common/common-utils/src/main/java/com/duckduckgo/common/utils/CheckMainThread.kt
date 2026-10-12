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

package com.duckduckgo.common.utils

import android.os.Looper
import io.github.molelabs.aspectk.runtime.Aspect
import io.github.molelabs.aspectk.runtime.Before
import io.github.molelabs.aspectk.runtime.JoinPoint

fun checkMainThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "Not expected to be called on the main thread but was "
    }
}

/**
 * Makes the annotated function throw when it is called on the main thread.
 * The module declaring the function must apply the `io.github.mole-labs.aspectk` plugin.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CheckNotMainThread

@Aspect
object CheckNotMainThreadAspect {
    @Before(CheckNotMainThread::class)
    fun check(joinPoint: JoinPoint) = checkMainThread()
}
