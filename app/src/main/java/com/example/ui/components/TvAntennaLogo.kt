package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * App-bar logo: a retro TV set with two antennas, a cut-out screen showing a play button, two control
 * dots and small feet. Drawn on a 24 x 24 grid, so it scales cleanly; the screen is a real cut-out
 * (shows whatever is behind the logo).
 */
@Composable
fun TvAntennaLogo(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val u = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        // antennas: slim V with round tips
        val sw = 1.5f * u
        drawLine(tint, p(11.2f, 8.2f), p(6.2f, 2.6f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, p(12.8f, 8.2f), p(17.8f, 2.6f), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(tint, radius = 1.25f * u, center = p(6.0f, 2.4f))
        drawCircle(tint, radius = 1.25f * u, center = p(18.0f, 2.4f))

        // body
        drawRoundRect(tint, topLeft = p(1.5f, 7.6f), size = Size(21f * u, 13f * u), cornerRadius = CornerRadius(4f * u))
        // feet
        drawLine(tint, p(6f, 22.2f), p(8f, 22.2f), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
        drawLine(tint, p(16f, 22.2f), p(18f, 22.2f), strokeWidth = 1.6f * u, cap = StrokeCap.Round)

        // screen cut-out
        drawRoundRect(
            Color.Black, topLeft = p(4f, 10f), size = Size(11.5f * u, 8.2f * u),
            cornerRadius = CornerRadius(2.4f * u), blendMode = BlendMode.Clear
        )
        // play triangle inside the screen
        val play = Path().apply {
            moveTo(8.3f * u, 11.9f * u)
            lineTo(8.3f * u, 16.3f * u)
            lineTo(12.2f * u, 14.1f * u)
            close()
        }
        drawPath(play, tint)

        // control dots on the right
        drawCircle(Color.Black, radius = 1.05f * u, center = p(19.1f, 12.2f), blendMode = BlendMode.Clear)
        drawCircle(Color.Black, radius = 1.05f * u, center = p(19.1f, 16.0f), blendMode = BlendMode.Clear)
    }
}
