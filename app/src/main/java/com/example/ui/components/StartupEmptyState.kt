package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The same blue the "tv" of the logo has in the TV rail. */
private val StartupBlue = Color(0xFF3B9BFF)
private val StartupButtonBg = Color(0xFF25292D)
private val StartupFocused = Color(0xFFF1F3F4)

/**
 * "No content yet" start page shared by the TV and the phone UI: same texts, same two buttons
 * (Add playlist / Settings), the "tv" of tvBuddy in blue.
 *
 * @param compact   phone sizing (smaller type, tighter spacing); false = TV sizing
 * @param selected  TV only: index of the D-pad highlighted button (0 = Add playlist, 1 = Settings), -1 = none
 * @param touchMode phone only: Add playlist is drawn as the filled primary button (no D-pad highlight exists)
 */
@Composable
fun StartupEmptyState(
    onAddPlaylist: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selected: Int = -1,
    touchMode: Boolean = false,
    background: Color = Color(0xFF14181B)
) {
    val titleSize = if (compact) 20.sp else 26.sp
    val subtitleSize = if (compact) 14.sp else 20.sp
    val tileSize = if (compact) 68.dp else 76.dp
    val iconSize = if (compact) 34.dp else 40.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .background(
                Brush.radialGradient(
                    colors = listOf(StartupBlue.copy(alpha = 0.14f), Color.Transparent)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = if (compact) 28.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // logo tile
            Box(
                modifier = Modifier
                    .size(tileSize)
                    .clip(RoundedCornerShape(22.dp))
                    .background(StartupBlue.copy(alpha = 0.14f))
                    .border(1.dp, StartupBlue.copy(alpha = 0.40f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = StartupBlue, modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.height(if (compact) 22.dp else 28.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = StartupBlue, fontWeight = FontWeight.Bold)) { append("tv") }
                    append("Buddy does not provide any content")
                },
                color = Color.White,
                fontSize = titleSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Add your own playlist to start watching",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = subtitleSize,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(if (compact) 32.dp else 44.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StartupButton(
                    label = "Add playlist",
                    icon = Icons.Default.Add,
                    compact = compact,
                    focused = selected == 0,
                    primary = touchMode,
                    onClick = onAddPlaylist
                )
                StartupButton(
                    label = "Settings",
                    icon = Icons.Default.Settings,
                    compact = compact,
                    focused = selected == 1,
                    primary = false,
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun StartupButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    compact: Boolean,
    focused: Boolean,
    primary: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "startupBtnScale")
    val bg = when {
        focused -> StartupFocused
        primary -> StartupBlue
        else -> StartupButtonBg
    }
    val fg = if (focused || primary) Color.Black else Color.White
    val shape = RoundedCornerShape(if (compact) 12.dp else 10.dp)
    Row(
        modifier = Modifier
            .scale(scale)
            .clip(shape)
            .background(bg)
            .then(if (!focused && !primary) Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 18.dp else 28.dp,
                vertical = if (compact) 12.dp else 16.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        Text(
            text = label,
            color = fg,
            fontSize = if (compact) 14.sp else 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
