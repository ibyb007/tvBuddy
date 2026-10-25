package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

// One decode size for every channel logo in the app. Coil keys its memory cache on the target size, so a logo that
// was decoded for the TV page's rows is found again, instantly, in the channel list (and vice versa) instead of
// being decoded from the disk cache once per screen while the list scrolls.
private const val LOGO_W_PX = 192
private const val LOGO_H_PX = 128

/** Channel logo with a fixed decode size and no cross-fade (cheap to bind while a list scrolls). */
@Composable
fun ChannelLogo(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(LOGO_W_PX, LOGO_H_PX)
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
