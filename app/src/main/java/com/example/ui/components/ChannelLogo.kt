package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

// One decode size for every channel logo in the app. Coil keys its memory cache on the target size, so a logo that
// was decoded for the TV page's rows is found again, instantly, in the channel list (and vice versa) instead of
// being decoded from the disk cache once per screen while the list scrolls.
private const val LOGO_W_PX = 192
private const val LOGO_H_PX = 128
// the big logo of the TV info banner is shown at ~160 px, so it is decoded larger to stay sharp
private const val LOGO_LARGE_W_PX = 384
private const val LOGO_LARGE_H_PX = 288

/** Channel logo with a fixed decode size and no cross-fade (cheap to bind while a list scrolls). */
@Composable
fun ChannelLogo(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val context = LocalContext.current
    val request = remember(url, large) {
        ImageRequest.Builder(context)
            .data(url)
            .size(if (large) LOGO_LARGE_W_PX else LOGO_W_PX, if (large) LOGO_LARGE_H_PX else LOGO_H_PX)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

/** Warms Coil's memory cache with exactly the request [ChannelLogo] will make for [url] (same size, no cross-fade). */
internal fun prefetchChannelLogo(context: android.content.Context, url: String?) {
    if (url.isNullOrBlank()) return
    context.imageLoader.enqueue(
        ImageRequest.Builder(context).data(url).size(LOGO_W_PX, LOGO_H_PX).crossfade(false).build()
    )
}

/**
 * Frosted "glass" card that sits behind a channel logo in the phone lists. Mid-light on purpose: logos whose lettering
 * is black stay readable (they vanished on the old dark tile), and white lettering still keeps some contrast.
 * Size the card from outside; the logo inside keeps its own size.
 */
@Composable
fun GlassLogoCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF2D6DDE7), Color(0xF2A9B4C3))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.25f))),
                shape
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}
