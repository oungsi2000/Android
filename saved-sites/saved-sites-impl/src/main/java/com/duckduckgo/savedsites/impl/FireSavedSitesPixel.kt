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
package com.duckduckgo.savedsites.impl

import com.duckduckgo.app.statistics.pixels.HasPixel
import com.duckduckgo.app.statistics.pixels.Pixel.PixelType.Count
import com.duckduckgo.app.statistics.pixels.Pixel.PixelType.Daily
import io.github.molelabs.aspectk.runtime.Aspect
import io.github.molelabs.aspectk.runtime.Before
import io.github.molelabs.aspectk.runtime.JoinPoint
import io.github.molelabs.aspectk.runtime.getArg
import io.github.molelabs.aspectk.runtime.getArgOrNull
import io.github.molelabs.aspectk.runtime.getTarget

/** Fires [pixel] before the function runs. The enclosing class must implement [HasPixel]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Repeatable
annotation class FireSavedSitesPixel(
    val pixel: SavedSitesPixelName,
    val daily: Boolean = false,
)

@Aspect
object FireSavedSitesPixelAspect {
    @Before(FireSavedSitesPixel::class)
    fun fire(joinPoint: JoinPoint) {
        val pixel = joinPoint.getTarget<HasPixel>().pixel
        joinPoint.signature.annotations.filter { it.type == FireSavedSitesPixel::class }.forEach {
            val daily = it.getArgOrNull<Boolean>("daily") ?: false
            pixel.fire(it.getArg<SavedSitesPixelName>("pixel"), type = if (daily) Daily() else Count)
        }
    }
}
