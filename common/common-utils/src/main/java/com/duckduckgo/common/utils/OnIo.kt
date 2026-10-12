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

import io.github.molelabs.aspectk.runtime.Around
import io.github.molelabs.aspectk.runtime.Aspect
import io.github.molelabs.aspectk.runtime.SuspendProceedingJoinPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the annotated suspend function on the IO dispatcher, as if its body were wrapped in
 * `withContext(dispatcherProvider.io()) { ... }`.
 * The module declaring the function must apply the `io.github.mole-labs.aspectk` plugin.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class OnIo

@Aspect
object OnIoAspect {
    /** Replaced by `CoroutineTestRule` in tests. */
    @Volatile
    var dispatcher: CoroutineDispatcher = Dispatchers.IO

    @Around(OnIo::class)
    suspend fun onIo(joinPoint: SuspendProceedingJoinPoint): Any? = withContext(dispatcher) { joinPoint.proceed() }
}
