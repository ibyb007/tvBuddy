package com.example.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import android.os.Build
import android.view.HapticFeedbackConstants
import com.example.ui.backdrop.backdrops.LayerBackdrop
import com.example.ui.backdrop.drawBackdrop
import com.example.ui.backdrop.effects.blur
import com.example.ui.backdrop.effects.colorControls
import com.example.ui.backdrop.effects.lens
import com.example.ui.backdrop.highlight.Highlight
import com.example.ui.backdrop.isRenderEffectSupported
import com.example.ui.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.example.data.local.entity.PlaylistEntity
import com.example.ui.theme.AccentCyan

/** Default opacity of the glass bar and the playlist panel (changeable in Settings on the phone). */
const val DEFAULT_NAVBAR_OPACITY = 0.7f

/** What the phone list shows: live TV, movies or shows (the last two only when a playlist has VOD). */
enum class MobileSection { LIVE, MOVIES, SHOWS }

/**
 * Liquid-glass surface: a frosted dark tint at [alpha] opacity, a soft specular sheen on the top edge,
 * a faint cyan glow along the bottom edge (light refracting through the lower rim), a diagonal
 * "light catch" rim (bright top-left, dim bottom-right) and a soft drop shadow so the bar floats.
 * List items scrolling underneath stay visible through it.
 */
fun Modifier.liquidGlass(shape: Shape, alpha: Float = DEFAULT_NAVBAR_OPACITY): Modifier = this
    .shadow(
        elevation = 14.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.35f),
        spotColor = Color.Black.copy(alpha = 0.45f)
    )
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color(0xFF1B2540).copy(alpha = alpha),
                Color(0xFF0B101C).copy(alpha = alpha)
            )
        )
    )
    .drawWithContent {
        val w = size.width
        val h = size.height
        // specular sheen: bright cap on the top half that fades out
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.17f),
                0.45f to Color.White.copy(alpha = 0.03f),
                1f to Color.Transparent,
                startY = 0f,
                endY = h
            )
        )
        // light source glint near the top-left
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w * 0.22f, 0f),
                radius = w * 0.45f
            )
        )
        // refracted glow along the bottom rim
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to AccentCyan.copy(alpha = 0.10f),
                startY = h * 0.55f,
                endY = h
            )
        )
        drawContent()
        // diagonal rim: catches light top-left and bottom-right, dark in between
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(
            outline = outline,
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.60f),
                0.35f to Color.White.copy(alpha = 0.10f),
                0.65f to Color.White.copy(alpha = 0.04f),
                1f to Color.White.copy(alpha = 0.32f),
                start = Offset.Zero,
                end = Offset(w, h)
            ),
            style = Stroke(width = 1.3.dp.toPx())
        )
    }

private val NavPillShape = RoundedCornerShape(percent = 50)

/** Resolution the backdrop is processed at; the blur hides the upscaling. Pixel sizes are scaled by it. */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/**
 * Real liquid glass (Android 12+): blurs and saturates whatever is behind it in [backdrop], bends the
 * light along the rim (lens refraction with chromatic aberration on Android 13+), and adds a directional
 * edge highlight and a soft shadow. [alpha] is the Navbar opacity setting: it only sets how strongly the
 * dark tint covers the blurred background.
 */
@Composable
fun Modifier.backdropGlass(backdrop: LayerBackdrop, shape: CornerBasedShape, alpha: Float): Modifier {
    val density = LocalDensity.current
    val blurPx = with(density) { 8.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { 24.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { 24.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val tint = Color(0xFF0E1424).copy(alpha = (alpha * alpha).coerceIn(0.1f, 1f))
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.5f)
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(color = tint, size = size) },
        backdropScale = GLASS_RESOLUTION_SCALE
    )
}

/** Backdrop glass when [backdrop] is given and the phone supports it, else the flat [liquidGlass] look. */
@Composable
fun Modifier.glassSurface(shape: CornerBasedShape, alpha: Float, backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null && isRenderEffectSupported()) backdropGlass(backdrop, shape, alpha)
    else liquidGlass(shape, alpha)

/** Layout of one nav item inside the bar, used to slide the glass lens under the selected item. */
private data class NavBounds(val x: Float, val width: Float, val height: Float)

private class NavEntry(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit
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
    opacity: Float = DEFAULT_NAVBAR_OPACITY,
    settingsOpen: Boolean = false,
    /** Recorded list content to blur behind the bar. Null (or Android 11 and older) keeps the flat glass. */
    backdrop: LayerBackdrop? = null
) {
    val sectionActive = !playlistsOpen && !settingsOpen
    val entries = buildList {
        add(NavEntry("tv", "TV", Icons.Default.Tv, section == MobileSection.LIVE && sectionActive) {
            onSelectSection(MobileSection.LIVE)
        })
        if (hasMovies) {
            add(NavEntry("movies", "Movies", Icons.Default.Movie, section == MobileSection.MOVIES && sectionActive) {
                onSelectSection(MobileSection.MOVIES)
            })
        }
        if (hasShows) {
            add(NavEntry("shows", "Shows", Icons.Default.VideoLibrary, section == MobileSection.SHOWS && sectionActive) {
                onSelectSection(MobileSection.SHOWS)
            })
        }
        add(NavEntry("playlists", "Playlists", Icons.Default.FormatListBulleted, playlistsOpen, onTogglePlaylists))
        add(NavEntry("settings", "Settings", Icons.Default.Settings, settingsOpen, onOpenSettings))
    }

    val density = LocalDensity.current
    val view = LocalView.current
    val bounds = remember { mutableStateMapOf<String, NavBounds>() }
    val target = entries.firstOrNull { it.selected }?.let { bounds[it.key] }

    // The lens glides to the selected item. Position and width use different springs, so it
    // stretches slightly while travelling and settles with a small overshoot (liquid feel).
    val lensX = remember { Animatable(0f) }
    val lensW = remember { Animatable(0f) }
    var lensPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (!lensPlaced) {
            lensX.snapTo(target.x)
            lensW.snapTo(target.width)
            lensPlaced = true
        } else {
            launch { lensX.animateTo(target.x, spring(dampingRatio = 0.68f, stiffness = 420f)) }
            launch { lensW.animateTo(target.width, spring(dampingRatio = 0.8f, stiffness = 300f)) }
        }
    }

    Box(
        modifier = modifier
            .glassSurface(NavPillShape, opacity, backdrop)
            .padding(horizontal = 5.dp, vertical = 4.dp)
    ) {
        if (target != null && lensPlaced) {
            val lensShape = CircleShape
            Box(
                modifier = Modifier
                    .offset { IntOffset(lensX.value.roundToInt(), 0) }
                    .width(with(density) { lensW.value.toDp() })
                    .height(with(density) { target.height.toDp() })
                    .graphicsLayer {
                        val lag = (abs(target.x - lensX.value) / target.width).coerceIn(0f, 1f)
                        scaleX = 1f + lag * 0.16f
                        scaleY = 1f - lag * 0.08f
                    }
                    .clip(lensShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(AccentCyan.copy(alpha = 0.30f), AccentCyan.copy(alpha = 0.12f))
                        )
                    )
                    .drawBehind {
                        // soft highlight on the upper half of the lens
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.22f),
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = size.height * 0.55f
                            )
                        )
                    }
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.30f)
                            )
                        ),
                        shape = lensShape
                    )
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            entries.forEach { e ->
                NavItem(
                    label = e.label,
                    icon = e.icon,
                    selected = e.selected,
                    onClick = {
                        if (!e.selected) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        e.onClick()
                    },
                    modifier = Modifier.onGloballyPositioned {
                        val b = NavBounds(it.positionInParent().x, it.size.width.toFloat(), it.size.height.toFloat())
                        if (bounds[e.key] != b) bounds[e.key] = b
                    }
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint by animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = 0.72f),
        label = "navTint"
    )
    val iconTint by animateColorAsState(
        if (selected) AccentCyan else Color.White.copy(alpha = 0.78f),
        label = "navIconTint"
    )
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "navScale"
    )
    Column(
        modifier = modifier
            .widthIn(min = 58.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
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
    opacity: Float = DEFAULT_NAVBAR_OPACITY,
    backdrop: LayerBackdrop? = null
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
                .navigationBarsPadding()
                .padding(bottom = 76.dp)
                .width(176.dp)
                .glassSurface(RoundedCornerShape(20.dp), opacity, backdrop)
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
