/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.istech.privacycamera.data.MaskingEngine.MaskRegion
import com.istech.privacycamera.data.MaskingEngine.MaskSpec
import com.istech.privacycamera.data.MaskingEngine.MaskStyle
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [MaskingEngine.render] must never recycle the bitmap it is given (2026-11-26: the Pro mask
 * editor crashed with "Canvas: trying to use a recycled bitmap" on API 29).
 *
 * The editor keeps its preview source on screen and hands that same bitmap to render. For a
 * small photo the preview source is the decoded original itself, which is immutable; asked for
 * its own full frame, [Bitmap.createBitmap] hands back that very bitmap instead of a copy, and
 * the engine then recycled it as if it were its own intermediate. Choosing 範囲指定 with no
 * region drawn yet shows the preview source again, and drawing it threw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskingEngineSourceKeptTest {

    /** An immutable bitmap, as BitmapFactory hands back a decoded photo. */
    private fun decodedPhoto(w: Int = 64, h: Int = 48): Bitmap {
        val drawn = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) for (y in 0 until h) drawn.setPixel(x, y, Color.rgb(x * 4, y * 5, 128))
        val jpeg = ByteArrayOutputStream().also { drawn.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        drawn.recycle()
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertFalse("the fixture must be immutable like a decoded photo", decoded.isMutable)
        return decoded
    }

    private fun assertKept(spec: MaskSpec, label: String, w: Int = 64, h: Int = 48) {
        val source = decodedPhoto(w, h)
        val out = MaskingEngine.render(source, spec)
        assertFalse("$label: the caller's bitmap was recycled", source.isRecycled)
        assertFalse("$label: the result was recycled", out.isRecycled)
        // Both must still be drawable, which is what the editor does with them next.
        source.getPixel(0, 0)
        out.getPixel(0, 0)
        // The result is the caller's to recycle: doing so must leave the source usable.
        assertTrue("$label: the result must be a separate bitmap", out !== source)
        out.recycle()
        assertFalse("$label: recycling the result destroyed the source", source.isRecycled)
        source.getPixel(w - 1, h - 1)
    }

    @Test fun `whole-frame mosaic keeps the source`() =
        assertKept(MaskSpec(wholeFrame = true, style = MaskStyle.MOSAIC), "whole mosaic")

    @Test fun `whole-frame blur keeps the source`() =
        assertKept(MaskSpec(wholeFrame = true, style = MaskStyle.BLUR), "whole blur")

    @Test fun `empty region list falls back to the whole frame and keeps the source`() =
        assertKept(MaskSpec(wholeFrame = false, style = MaskStyle.MOSAIC, regions = emptyList()), "empty regions")

    @Test fun `a region covering the whole photo keeps the source`() {
        val all = MaskRegion(0f, 0f, 1f, 1f)
        assertKept(MaskSpec(wholeFrame = false, style = MaskStyle.MOSAIC, regions = listOf(all, all)), "full region x2")
        assertKept(MaskSpec(wholeFrame = false, style = MaskStyle.BLUR, regions = listOf(all)), "full region blur")
    }

    @Test fun `coarseness at or above the width keeps the source`() {
        assertKept(MaskSpec(wholeFrame = true, style = MaskStyle.MOSAIC, columns = 64), "columns = width")
        // Square photos: columns equal to, and above, both sides - every step is a no-op and
        // the engine's own calls hand back the source itself.
        assertKept(MaskSpec(wholeFrame = true, style = MaskStyle.MOSAIC, columns = 48), "square, columns = side", 48, 48)
        assertKept(MaskSpec(wholeFrame = true, style = MaskStyle.BLUR, columns = 200), "square, columns > side", 48, 48)
        val all = MaskRegion(0f, 0f, 1f, 1f)
        assertKept(MaskSpec(wholeFrame = false, style = MaskStyle.MOSAIC, columns = 200, regions = listOf(all, all)),
            "square, full regions, columns > side", 48, 48)
    }

    @Test fun `a partial region still masks and keeps the source`() {
        val source = decodedPhoto()
        val before = source.getPixel(10, 10)
        val out = MaskingEngine.render(source, MaskSpec(wholeFrame = false, style = MaskStyle.SOLID,
            regions = listOf(MaskRegion(0f, 0f, 0.5f, 0.5f))))
        assertFalse(source.isRecycled)
        assertTrue("solid region is black", out.getPixel(10, 10) == Color.BLACK)
        assertTrue("the original pixels are untouched", source.getPixel(10, 10) == before)
    }
}
