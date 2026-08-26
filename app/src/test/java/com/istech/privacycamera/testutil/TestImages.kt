/*
 * Copyright 2026 istech
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
package com.istech.privacycamera.testutil

import android.graphics.Bitmap
import android.graphics.Color

/** マスキング/ウォーターマークのテストで使う決定的な合成画像(乱数・I/Oなし)。 */
object TestImages {

    /**
     * 横方向に色相が変化するグラデーション+白の対角線。
     * 対角線が入っているため、モザイク化・領域マスクの効果がピクセル比較で確実に検出できる。
     */
    fun gradient(width: Int = 96, height: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = x * 255 / (width - 1)
                val g = y * 255 / (height - 1)
                val b = (x + y) * 255 / (width + height - 2)
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        for (i in 0 until minOf(width, height)) {
            bmp.setPixel(i, i, Color.WHITE)
        }
        return bmp
    }
}
