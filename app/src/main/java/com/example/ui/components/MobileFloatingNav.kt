package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.PlaylistEntity
import com.example.ui.theme.AccentCyan

/** Default opacity of the glass bar and the playlist panel (changeable in Settings on the phone). */
const val DEFAULT_NAVBAR_OPACITY = 0.7f

/** What the phone list shows: live TV, movies or shows (the last two only when a playlist has VOD). */
enum class MobileSection { LIVE, MOVIES, SHOWS }

/**
 * "Liquid glass" surface: a dark tint at [alpha] opacity, a soft top-down light sheen and a bright
 * rim that fades out along the edge, so list items scrolling underneath stay visible through it.
 */
fun Modifier.liquidGlass(shape: Shape, alpha: Float = DEFAULT_NAVBAR_OPACITY): Modifier = this
    .clip(shape)
    .background(Color(0xFF111726).copy(alpha = alpha))
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.03f))
        )
    )
    .border(
        BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.60f),
                    Color.White.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.28f)
                )
            )
        ),
        shape
    )

/** Floating bottom bar: TV · Movies · Shows (VOD only) · Playlists · Settings. */
@Composable
fun MobileGlassNavBar(
    section: MobileSection,
    hasMovies: Boolean,
    hasShows: Boolean,
    playlistsOpen: Boolean,
    onSelectSection: (MobileSection) -> Unit,
    onTogglePlaylists: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = DEFAULT_NAVBAR_OPACITY
) {
    Row(
        modifier = modifier
            .liquidGlass(RoundedCornerShape(26.dp), opacity)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem("TV", Icons.Default.Tv, selected = section == MobileSection.LIVE && !playlistsOpen) {
            onSelectSection(MobileSection.LIVE)
        }
        if (hasMovies) {
            NavItem("Movies", Icons.Default.Movie, selected = section == MobileSection.MOVIES && !playlistsOpen) {
                onSelectSection(MobileSection.MOVIES)
            }
        }
        if (hasShows) {
            NavItem("Shows", Icons.Default.VideoLibrary, selected = section == MobileSection.SHOWS && !playlistsOpen) {
                onSelectSection(MobileSection.SHOWS)
            }
        }
        NavItem("Playlists", Icons.Default.FormatListBulleted, selected = playlistsOpen, onClick = onTogglePlaylists)
        NavItem("Settings", Icons.Default.Settings, selected = false, onClick = onOpenSettings)
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (selected) AccentCyan else Color.White.copy(alpha = 0.85f), label = "navTint")
    val pill by animateColorAsState(if (selected) AccentCyan.copy(alpha = 0.22f) else Color.Transparent, label = "navPill")
    Column(
        modifier = Modifier
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // slim indicator: only a small pill behind the icon, not the whole item
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(pill),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            text = label,
            color = tint,
            fontSize = 9.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Vertical playlist picker that opens above the navbar: Add playlist, All playlists, then one row per
 * playlist, the selected one marked with a tick on the right. Names have a fixed small width and
 * scroll automatically when they are longer than that (about 15 letters).
 * Draws its own transparent scrim: tapping outside (or BACK) closes it.
 */
@Composable
fun MobilePlaylistPanel(
    playlists: List<PlaylistEntity>,
    selectedPlaylistId: Long?,
    onSelect: (Long?) -> Unit,
    onAddPlaylist: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = DEFAULT_NAVBAR_OPACITY
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp)
                .width(176.dp)
                .liquidGlass(RoundedCornerShape(20.dp), opacity)
                // swallow taps inside the panel so they don't reach the scrim
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(vertical = 6.dp)
        ) {
            PanelRow(
                label = "Add playlist",
                labelColor = AccentCyan,
                selected = false,
                leadingAdd = true,
                onClick = onAddPlaylist
            )
            Box(
                Modifier
                    .padding(horizontal = 14.dp, vertical = 3.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            )
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                item {
                    PanelRow(
                        label = "All playlists",
                        labelColor = Color.White,
                        selected = selectedPlaylistId == null,
                        onClick = { onSelect(null) }
                    )
                }
                items(playlists.filter { it.isActive }, key = { it.id }) { pl ->
                    PanelRow(
                        label = pl.name,
                        labelColor = Color.White,
                        selected = selectedPlaylistId == pl.id,
                        onClick = { onSelect(pl.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelRow(
    label: String,
    labelColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    leadingAdd: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingAdd) {
            Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        // fixed small width (~15 letters): longer names scroll on their own
        Text(
            text = label,
            color = if (selected) AccentCyan else labelColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 800)
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = AccentCyan, modifier = Modifier.size(16.dp))
        }
    }
}
