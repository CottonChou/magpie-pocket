package com.magpie.pocket.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small, consistently weighted outlines matching the Pocket navigation reference. */
@Composable
fun NavigationMark(tab: AppTab, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(21.dp)) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.7f * s, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), stroke.width, StrokeCap.Round)
        when (tab) {
            AppTab.OVERVIEW -> {
                for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) {
                    drawRoundRect(tint, point(x, y), Size(7f * s, 7f * s), CornerRadius(s, s), style = stroke)
                }
            }
            AppTab.ROUTING -> {
                drawCircle(tint, 2.5f * s, point(6f, 19f), style = stroke)
                drawCircle(tint, 2.5f * s, point(18f, 5f), style = stroke)
                line(6f, 3f, 6f, 16.5f)
                val branch = Path().apply {
                    moveTo(6f * s, 14f * s)
                    cubicTo(6f * s, 8f * s, 13f * s, 7f * s, 15.5f * s, 5f * s)
                }
                drawPath(branch, tint, style = stroke)
            }
            AppTab.USAGE -> {
                line(5f, 20f, 5f, 14f)
                line(12f, 20f, 12f, 7f)
                line(19f, 20f, 19f, 2f)
            }
            AppTab.REQUESTS -> {
                line(3f, 5f, 21f, 5f)
                line(6f, 12f, 18f, 12f)
                line(10f, 19f, 14f, 19f)
            }
        }
    }
}

@Composable
fun DecisionMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val s = size.minDimension / 24f
        val stroke = Stroke(1.7f * s, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(15f * s, 5f * s)
            lineTo(6f * s, 5f * s)
            cubicTo(0f, 5f * s, 0f, 13f * s, 6f * s, 13f * s)
            lineTo(17f * s, 13f * s)
            cubicTo(23f * s, 13f * s, 23f * s, 21f * s, 17f * s, 21f * s)
            lineTo(9f * s, 21f * s)
        }
        drawPath(path, tint, style = stroke)
        drawCircle(tint, 3f * s, Offset(18f * s, 5f * s), style = stroke)
        drawCircle(tint, 3f * s, Offset(6f * s, 21f * s), style = stroke)
    }
}
