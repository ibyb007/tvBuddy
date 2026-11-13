package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** App-bar logo: a TV set with two antennas on top (drawn, no icon asset needed). */
@Composable
fun TvAntennaLogo(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.075f
        // antennas: two thin rods leaving the top of the set in a V, with a small ball on each tip
        val baseY = h * 0.36f
        val baseX = w * 0.5f
        val leftTip = Offset(w * 0.24f, h * 0.06f)
        val rightTip = Offset(w * 0.76f, h * 0.06f)
        drawLine(tint, Offset(baseX - w * 0.04f, baseY), leftTip, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(baseX + w * 0.04f, baseY), rightTip, strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = stroke * 0.9f, center = leftTip)
        drawCircle(tint, radius = stroke * 0.9f, center = rightTip)
        // TV body (outline) + screen
        val bodyTop = h * 0.36f
        val bodyH = h * 0.58f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2, bodyTop),
            size = Size(w - stroke, bodyH),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.35f),
            topLeft = Offset(w * 0.18f, bodyTop + bodyH * 0.17f),
            size = Size(w * 0.64f, bodyH * 0.66f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
    }
}
