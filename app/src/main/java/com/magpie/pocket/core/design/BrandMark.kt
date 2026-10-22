package com.magpie.pocket.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lucide "bird" outline mark, viewport 24x24.
 *
 * Paths are from Lucide (ISC License, https://lucide.dev/icons/bird).
 * Copyright (c) Lucide Contributors. The ISC notice is retained in
 * docs/decisions.md; the artwork here is a plain Compose Canvas port.
 */
@Composable
fun BirdMark(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Float = 1.8f
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        val stroke = Stroke(
            width = strokeWidth * scale,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        // M3.4 18H12a8 8 0 0 0 8-8V7a4 4 0 0 0-7.28-2.3L2 20
        val body = Path().apply {
            moveTo(3.4f * scale, 18f * scale)
            lineTo(12f * scale, 18f * scale)
            cubicTo(16.418f * scale, 18f * scale, 20f * scale, 14.418f * scale, 20f * scale, 10f * scale)
            lineTo(20f * scale, 7f * scale)
            cubicTo(20f * scale, 4.791f * scale, 18.209f * scale, 3f * scale, 16f * scale, 3f * scale)
            cubicTo(14.6f * scale, 3f * scale, 13.36f * scale, 3.72f * scale, 12.72f * scale, 4.7f * scale)
            lineTo(2f * scale, 20f * scale)
        }
        drawPath(body, tint, style = stroke)

        // m20 7 2 .5-2 .5
        val beak = Path().apply {
            moveTo(20f * scale, 7f * scale)
            lineTo(22f * scale, 7.5f * scale)
            lineTo(20f * scale, 8f * scale)
        }
        drawPath(beak, tint, style = stroke)

        // M10 18v3 and M14 17.75V21
        drawLine(tint, p(10f, 18f), p(10f, 21f), stroke.width, StrokeCap.Round)
        drawLine(tint, p(14f, 17.75f), p(14f, 21f), stroke.width, StrokeCap.Round)

        // M7 18a6 6 0 0 0 3.84-10.61
        val wing = Path().apply {
            moveTo(7f * scale, 18f * scale)
            cubicTo(10.314f * scale, 18f * scale, 13f * scale, 15.314f * scale, 13f * scale, 12f * scale)
            cubicTo(13f * scale, 11.09f * scale, 12.8f * scale, 10.23f * scale, 12.43f * scale, 9.46f * scale)
        }
        drawPath(wing, tint, style = stroke)

        // M16 7h.01 — Lucide renders this as a tiny round dot.
        drawCircle(tint, radius = stroke.width / 2f, center = p(16f, 7f))
    }
}
