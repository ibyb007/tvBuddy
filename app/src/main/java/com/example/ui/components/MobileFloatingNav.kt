package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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

/** Opacity of the glass bar and the playlist panel. */
const val GLASS_ALPHA = 0.5f

/** What the phone list shows: live TV, movies or shows (the last two only when a playlist has VOD). */
enum class MobileSection { LIVE, MOVIES, SHOWS }

/**
 * "Liquid glass" surface: a dark tint at [alpha] opacity, a soft top-down light sheen and a bright
 * rim that fades out along the edge, so list items scrolling underneath stay visible through it.
 */
fun Modifier.liquidGlass(shape: Shape, alpha: Float = GLASS_ALPHA): Modifier = this
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .liquidGlass(RoundedCornerShape(32.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
    val pill by animateColorAsState(if (selected) AccentCyan.copy(alpha = 0.20f) else Color.Transparent, label = "navPill")
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clip(shape)
            .background(pill)
            .then(if (selected) Modifier.border(1.dp, AccentCyan.copy(alpha = 0.35f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Vertical playlist picker that opens above the navbar: Add playlist, All playlists, then one row per
 * playlist. Names have a fixed small width and scroll sideways when they are longer.
 * Draws its own transparent scrim: tapping outside (or BACK) closes it.
 */
@Composable
fun MobilePlaylistPanel(
    playlists: List<PlaylistEntity>,
    selectedPlaylistId: Long?,
    onSelect: (Long?) -> Unit,
    onAddPlaylist: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
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
                .padding(bottom = 96.dp)
                .width(236.dp)
                .liquidGlass(RoundedCornerShape(24.dp))
                // swallow taps inside the panel so they don't reach the scrim
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(vertical = 8.dp)
        ) {
            PanelRow(
                label = "Add playlist",
                leading = { Icon(Icons.Default.Add, null, tint = AccentCyan, modifier = Modifier.size(20.dp)) },
                labelColor = AccentCyan,
                selected = false,
                scrollable = false,
                onClick = onAddPlaylist
            )
            Box(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .heightIn(min = 1.dp, max = 1.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            )
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                item {
                    PanelRow(
                        label = "All playlists",
                        leading = { Icon(Icons.Default.FormatListBulleted, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
                        labelColor = Color.White,
                        selected = selectedPlaylistId == null,
                        scrollable = false,
                        onClick = { onSelect(null) }
                    )
                }
                items(playlists.filter { it.isActive }, key = { it.id }) { pl ->
                    PanelRow(
                        label = pl.name,
                        leading = { Box(Modifier.size(20.dp)) },
                        labelColor = Color.White,
                        selected = selectedPlaylistId == pl.id,
                        scrollable = true,
                        onClick = { onSelect(pl.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelRow(
    label: String,
    leading: @Composable () -> Unit,
    labelColor: Color,
    selected: Boolean,
    scrollable: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (selected) AccentCyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        // fixed small width: long names scroll sideways instead of growing the panel
        Text(
            text = label,
            color = if (selected) AccentCyan else labelColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .width(120.dp)
                .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = AccentCyan, modifier = Modifier.size(18.dp))
        }
    }
}
