/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The frame as drawn (2026-11-27). The protected screen cannot be captured (FLAG_SECURE), so the
 * frame's own drawing is run onto a bitmap that already holds a synthetic picture, at 3.5x
 * (560dpi), and its pixels are read back: halo and line in the frame's room, the picture
 * untouched inside it, for a white and a black picture.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoFrameDrawTest {
    private val density = 3.5f
    private val page = Color(0xFFFDF8FD)

    private fun draw(style: PhotoFrame.Style, picture: Color): android.graphics.Bitmap {
        val w = 600; val h = 420
        val reserve = PhotoFrame.RESERVE.value * density
        val bmp = ImageBitmap(w, h)
        val canvas = Canvas(bmp)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
            drawRect(page)
            // The picture fills the inside of the frame's room, as FramedPhoto lays it out.
            drawRect(picture, topLeft = androidx.compose.ui.geometry.Offset(reserve, reserve), size = Size(w - 2 * reserve, h - 2 * reserve))
            drawFrame(style, reserve)
        }
        return bmp.asAndroidBitmap()
    }

    private fun near(px: Int, want: Color, what: String) {
        val c = Color(px)
        val d = maxOf(kotlin.math.abs(c.red - want.red), kotlin.math.abs(c.green - want.green), kotlin.math.abs(c.blue - want.blue))
        assertTrue("$what: got #${Integer.toHexString(px)} want $want", d < 0.04f)
    }

    private fun check(style: PhotoFrame.Style, picture: Color) {
        val img = draw(style, picture)
        val reserve = PhotoFrame.RESERVE.value * density
        val line = style.lineWidth.value * density
        val halo = style.haloWidth.value * density
        val haloAt = (reserve - line - halo / 2f).toInt()
        val lineAt = (reserve - line / 2f).toInt()
        val yMid = img.height / 2
        val xMid = img.width / 2
        near(img.getPixel(haloAt, yMid), style.halo, "halo left")
        near(img.getPixel(lineAt, yMid), style.line, "line left")
        near(img.getPixel(img.width - 1 - lineAt, yMid), style.line, "line right")
        near(img.getPixel(xMid, lineAt), style.line, "line top")
        near(img.getPixel(xMid, img.height - 1 - lineAt), style.line, "line bottom")
        near(img.getPixel(img.width - 1 - haloAt, yMid), style.halo, "halo right")
        // The frame stays in its room: the picture is untouched right next to it and in the middle.
        near(img.getPixel(kotlin.math.ceil(reserve).toInt() + 1, yMid), picture, "picture just inside")
        near(img.getPixel(xMid, yMid), picture, "picture centre")
    }

    @Test fun maskFrameRoundAWhitePicture() = check(PhotoFrame.MASK, Color.White)
    @Test fun maskFrameRoundABlackPicture() = check(PhotoFrame.MASK, Color.Black)
    @Test fun originalFrameRoundAWhitePicture() = check(PhotoFrame.ORIGINAL, Color.White)
    @Test fun originalFrameRoundABlackPicture() = check(PhotoFrame.ORIGINAL, Color.Black)
}
