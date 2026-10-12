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

import io.github.molelabs.aspectk.runtime.Aspect
import io.github.molelabs.aspectk.runtime.Before
import io.github.molelabs.aspectk.runtime.JoinPoint
import io.github.molelabs.aspectk.runtime.getArgOrNull
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/**
 * Logs the exception a failure lambda receives, as `message` followed by the stack trace or,
 * with `stackTrace = false`, by the exception message:
 *
 *     runCatching { ... }.onFailure @LogFailure("Failed to load: ", priority = ERROR) {}
 *
 * SOURCE retention, so that it can be written on a lambda passed to an inline function.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LogFailure(
    val message: String = "",
    val priority: LogPriority = LogPriority.DEBUG,
    val stackTrace: Boolean = true,
)

@Aspect
object LogFailureAspect {
    @Before(LogFailure::class)
    fun log(joinPoint: JoinPoint) {
        val failure = joinPoint.args.firstOrNull { it is Throwable } as? Throwable ?: return
        val annotation = joinPoint.signature.annotations.first { it.type == LogFailure::class }
        val detail = if (annotation.getArgOrNull<Boolean>("stackTrace") == false) failure.message else failure.asLog()
        logcat(joinPoint.tag(), annotation.getArgOrNull<LogPriority>("priority") ?: LogPriority.DEBUG) {
            (annotation.getArgOrNull<String>("message") ?: "") + detail
        }
    }
}
