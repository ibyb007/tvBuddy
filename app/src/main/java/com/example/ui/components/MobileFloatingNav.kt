package com.example.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.PlaylistEntity
import com.example.ui.theme.AccentCyan
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Default opacity of the glass bar and the playlist panel (changeable in Settings on the phone). */
const val DEFAULT_NAVBAR_OPACITY = 0.7f

/** What the phone list shows: live TV, movies or shows (the last two only when a playlist has VOD). */
enum class MobileSection { LIVE, MOVIES, SHOWS }

/**
 * The screen content that shows through (and gets blurred behind) the glass surfaces. Attach it to the
 * content with [glassBackdropSource] and provide it to the navbar / playlist panel with [LocalGlassBackdrop].
 */
@Stable
class GlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Marks this content as the thing the glass blurs: it is drawn normally and also recorded into the backdrop layer. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
            this@drawWithContent.drawContent()
        }
        drawLayer(backdrop.layer)
    }

/**
 * Liquid-glass surface: the content behind it is blurred (Android 12+; older phones get the plain tint),
 * covered by a dark tint, soft edge shading for depth, a light-catching gradient rim and a drop shadow.
 * [alpha] is the user's navbar opacity; with the blur on, the tint is lighter so the blurred content shows.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    alpha: Float = DEFAULT_NAVBAR_OPACITY,
    blurRadius: Dp = 22.dp
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val blurLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurOn = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tintAlpha = if (blurOn) alpha * 0.6f else alpha

    return this
        .shadow(
            elevation = 20.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = Color.Black.copy(alpha = 0.6f)
        )
        .clip(shape)
        .then(
            if (blurOn && backdrop != null) {
                Modifier
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .drawBehind {
                        val r = blurRadius.toPx()
                        blurLayer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
                        blurLayer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                            translate(left = backdrop.origin.x - origin.x, top = backdrop.origin.y - origin.y) {
                                drawLayer(backdrop.layer)
                            }
                        }
                        drawLayer(blurLayer)
                    }
            } else Modifier
        )
        .background(Color(0xFF111726).copy(alpha = tintAlpha))
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.05f))
            )
        )
        .dropletDepth()
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.40f))
            ),
            shape
        )
}

/**
 * Fake 3D volume, drawn behind the content: edges that darken as they curve away and a shadowed lower belly.
 */
private fun Modifier.dropletDepth(strength: Float = 1f): Modifier = this.drawBehind {
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.22f * strength),
            0.14f to Color.Transparent,
            0.86f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.22f * strength)
        )
    )
    drawRect(
        Brush.verticalGradient(
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.30f * strength)
        )
    )
}

private class NavEntry(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    /** icon starts rotated by this many degrees when it becomes active, then springs back */
    val fromRotation: Float,
    /** icon starts this many dp lower when it becomes active, then bounces up */
    val fromLift: Float,
    /** which section this entry opens, or null for Playlists / Settings */
    val section: MobileSection?,
    val onClick: () -> Unit
)

/**
 * Floating bottom bar: TV · Movies · Shows (VOD only) · Playlists · Settings.
 * The active item sits in a lighter glass "lens" that springs between items and reveals its label.
 * You can also press and drag across the bar: the lens follows the finger and selects on release.
 */
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
    settingsOpen: Boolean = false
) {
    val entries = buildList {
        add(NavEntry("TV", Icons.Default.Tv, section == MobileSection.LIVE && !playlistsOpen && !settingsOpen, -10f, 4f, MobileSection.LIVE) {
            onSelectSection(MobileSection.LIVE)
        })
        if (hasMovies) {
            add(NavEntry("Movies", Icons.Default.Movie, section == MobileSection.MOVIES && !playlistsOpen && !settingsOpen, -20f, 3f, MobileSection.MOVIES) {
                onSelectSection(MobileSection.MOVIES)
            })
        }
        if (hasShows) {
            add(NavEntry("Shows", Icons.Default.VideoLibrary, section == MobileSection.SHOWS && !playlistsOpen && !settingsOpen, 0f, 6f, MobileSection.SHOWS) {
                onSelectSection(MobileSection.SHOWS)
            })
        }
        add(NavEntry("Playlists", Icons.Default.FormatListBulleted, playlistsOpen, 0f, 6f, null, onTogglePlaylists))
        add(NavEntry("Settings", Icons.Default.Settings, settingsOpen, 140f, 0f, null, onOpenSettings))
    }
    val selectedIndex = entries.indexOfFirst { it.selected }
    val currentEntries by rememberUpdatedState(entries)
    val currentSelected by rememberUpdatedState(selectedIndex)

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // item bounds (left, width) in px; only used to find which item is under the finger while dragging
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    var dragIndex by remember { mutableStateOf<Int?>(null) }

    // Optimistic selection: the lens and label react on the very frame of the tap, without waiting for the
    // parent screen to recompose and hand us the new state. Cleared as soon as the real state arrives.
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedIndex) { pending = null }
    val activeIndex = dragIndex ?: pending ?: selectedIndex

    fun choose(index: Int) {
        val e = currentEntries.getOrNull(index) ?: return
        if (index != currentSelected) {
            pending = index
        } else if (e.section == null && e.label == "Playlists") {
            // closing the playlist panel: the lens goes back to the current section
            val back = currentEntries.indexOfFirst { it.section == section }
            if (back >= 0) pending = back
        }
        e.onClick()
    }

    // Lens target computed from the item sizes (icon slot + measured label width), not from the live layout,
    // so it can start moving immediately and never chases a still-expanding label.
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    val labelWidths = remember(entries.map { it.label }) {
        entries.map { textMeasurer.measure(it.label, labelStyle, maxLines = 1).size.width.toFloat() }
    }
    val slotPx = with(density) { 52.dp.toPx() }
    val labelGapPx = with(density) { 6.dp.toPx() }
    val targetX = if (activeIndex >= 0) slotPx * activeIndex else 0f
    val targetW = if (activeIndex in labelWidths.indices) slotPx + labelGapPx + labelWidths[activeIndex] else slotPx

    val lensX = remember { Animatable(0f) }
    val lensW = remember { Animatable(0f) }
    var lensReady by remember { mutableStateOf(false) }
    LaunchedEffect(targetX, targetW) {
        if (activeIndex < 0) return@LaunchedEffect
        if (!lensReady) {
            lensX.snapTo(targetX)
            lensW.snapTo(targetW)
            lensReady = true
        } else {
            coroutineScope {
                launch { lensX.animateTo(targetX, spring(dampingRatio = 0.7f, stiffness = 650f)) }
                launch { lensW.animateTo(targetW, spring(dampingRatio = 0.8f, stiffness = 700f)) }
            }
        }
    }
    val lensAlpha by animateFloatAsState(if (activeIndex >= 0 && lensReady) 1f else 0f, tween(180), label = "lensAlpha")
    // the lens swells slightly while the finger is down and dragging
    val lensSquish by animateFloatAsState(
        if (dragIndex != null) 1.1f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "lensSquish"
    )

    /** the last item whose left edge is at or before [x] (clamps to the first / last item) */
    fun hit(x: Float): Int? {
        var best: Int? = null
        for (i in 0 until currentEntries.size) {
            val b = bounds[i] ?: continue
            if (best == null || x >= b.first) best = i
        }
        return best
    }

    Box(
        modifier = modifier
            .liquidGlass(RoundedCornerShape(50), opacity)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // lens: lighter glass bubble behind the active item
            Box(
                modifier = Modifier
                    .offset { IntOffset(lensX.value.roundToInt(), 0) }
                    .width(with(density) { lensW.value.toDp() })
                    .height(44.dp)
                    .graphicsLayer {
                        alpha = lensAlpha
                        scaleX = lensSquish
                        scaleY = 1f + (lensSquish - 1f) * 0.6f
                    }
                    .shadow(8.dp, CircleShape, clip = false, ambientColor = AccentCyan.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.55f))
                    .clip(CircleShape)
                    .background(AccentCyan.copy(alpha = 0.20f))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))
                        )
                    )
                    .dropletDepth(0.9f)
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.6f), AccentCyan.copy(alpha = 0.15f), Color.White.copy(alpha = 0.35f))
                        ),
                        CircleShape
                    )
            )
            Row(
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragIndex = hit(it.x) },
                        onDragEnd = {
                            val i = dragIndex
                            dragIndex = null
                            if (i != null && i != currentSelected) choose(i)
                        },
                        onDragCancel = { dragIndex = null }
                    ) { change, _ ->
                        change.consume()
                        val i = hit(change.position.x)
                        if (i != null && i != dragIndex) {
                            dragIndex = i
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entries.forEachIndexed { index, entry ->
                    NavItem(
                        entry = entry,
                        active = index == activeIndex,
                        onBounds = { x, w -> bounds[index] = x to w },
                        onClick = { choose(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(entry: NavEntry, active: Boolean, onBounds: (Float, Float) -> Unit, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) AccentCyan else Color.White.copy(alpha = 0.85f), label = "navTint")

    // icon "pop" when it becomes active: squash, then spring back with a bounce (plus tilt / lift)
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            launch { scale.snapTo(0.7f); scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 420f)) }
            launch { rotation.snapTo(entry.fromRotation); rotation.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
            launch { lift.snapTo(entry.fromLift); lift.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 380f)) }
        }
    }
    val liftPx = with(LocalDensity.current) { lift.value.dp.toPx() }

    Row(
        modifier = Modifier
            .height(44.dp)
            .onGloballyPositioned { onBounds(it.positionInParent().x, it.size.width.toFloat()) }
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            entry.icon,
            contentDescription = entry.label,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    rotationZ = rotation.value
                    translationY = liftPx
                }
        )
        // label reveal: slides open to the right of the icon (the item, lens and bar all grow with it)
        AnimatedVisibility(
            visible = active,
            enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 500f)) + fadeOut(tween(90))
        ) {
            Text(
                text = entry.label,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
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
                .navigationBarsPadding()
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
