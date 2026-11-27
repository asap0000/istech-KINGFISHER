/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The photo screen tells what is on screen by the photo's frame (2026-11-27): a thin blue-grey
 * frame round the mask, a thicker orange one while the original is shown. Drawn on screen only,
 * outside the picture: nothing is written into any file, and the export stays the mask as stored.
 *
 * Both frames take the same room ([RESERVE]) so switching never moves or rescales the picture;
 * only the colours and the line width inside that room change.
 */
internal object PhotoFrame {
    /** Room kept round the picture for either frame: line plus halo, always the same. */
    val RESERVE: Dp = 4.dp

    /** [line] sits against the picture; [halo] outside it separates the line from the page. */
    data class Style(val line: Color, val lineWidth: Dp, val halo: Color, val haloWidth: Dp)

    val MASK = Style(line = Color(0xFF506270), lineWidth = 2.dp, halo = Color(0xFFD5DFE2), haloWidth = 2.dp)
    val ORIGINAL = Style(line = Color(0xFFA5490E), lineWidth = 3.dp, halo = Color(0xFFF3C88D), haloWidth = 1.dp)

    const val TAG = "photoFrame"
    const val STATE_MASK = "マスク表示中"
    const val STATE_ORIGINAL = "正規表示中（アプリ内のみ）"

    /** The size [imageWidth]x[imageHeight] takes when fitted (whole picture, no crop) into [boxWidth]x[boxHeight]. */
    fun fit(imageWidth: Int, imageHeight: Int, boxWidth: Float, boxHeight: Float): Size {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return Size(0f, 0f)
        val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
        return Size(imageWidth * scale, imageHeight * scale)
    }
}

/**
 * The picture, fitted into the space it has less the frame's room, with the frame drawn round
 * it. Zoom and pan stay inside the frame (the picture is clipped to it), so the frame stays in
 * sight and keeps saying what is shown however far the picture is zoomed.
 */
@Composable
internal fun FramedPhoto(
    bitmap: ImageBitmap,
    original: Boolean,
    contentDescription: String,
    zoomKey: Any?,
    modifier: Modifier = Modifier
) {
    val style = if (original) PhotoFrame.ORIGINAL else PhotoFrame.MASK
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val reservePx = with(density) { PhotoFrame.RESERVE.toPx() }
        val fitted = PhotoFrame.fit(
            bitmap.width, bitmap.height,
            constraints.maxWidth - 2 * reservePx, constraints.maxHeight - 2 * reservePx
        )
        val outerWidth = with(density) { (fitted.width + 2 * reservePx).toDp() }
        val outerHeight = with(density) { (fitted.height + 2 * reservePx).toDp() }
        Box(
            Modifier
                .size(outerWidth, outerHeight)
                .testTag(PhotoFrame.TAG)
                // The state, for TalkBack: separate from the eye button, which names an action.
                .semantics { stateDescription = if (original) PhotoFrame.STATE_ORIGINAL else PhotoFrame.STATE_MASK }
                .drawBehind { drawFrame(style, reservePx) }
                .padding(PhotoFrame.RESERVE)
                .clipToBounds()
        ) {
            ZoomableImage(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                resetKey = zoomKey
            )
        }
    }
}

/** The line against the picture, the halo outside it; both inside the reserved room. */
internal fun DrawScope.drawFrame(style: PhotoFrame.Style, reservePx: Float) {
    val line = style.lineWidth.toPx()
    val halo = style.haloWidth.toPx()
    fun band(insetToCentre: Float, width: Float, color: Color) = drawRect(
        color = color,
        topLeft = Offset(insetToCentre, insetToCentre),
        size = Size(size.width - 2 * insetToCentre, size.height - 2 * insetToCentre),
        style = Stroke(width = width)
    )
    band(reservePx - line - halo / 2f, halo, style.halo)
    band(reservePx - line / 2f, line, style.line)
}
