package com.example.scanapp.ui

import android.os.Build
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Floating "liquid glass" bottom nav, styled after tvBuddy's MobileGlassNavBar.
 *
 * One instance lives in MainActivity above every top-level screen (Home, Tools,
 * Backup, Settings) instead of one per screen, so the selection lens can really
 * spring from tab to tab as you navigate. Screens pad their own content by
 * [LocalFloatingNavHeight] so nothing hides behind the pill.
 */

/** Height the floating nav occupies at the bottom right now (0.dp while it is hidden). */
val LocalFloatingNavHeight = compositionLocalOf { 0.dp }

/**
 * The screen content that shows through — and gets blurred behind — the glass.
 * Attach it to the content with [glassBackdropSource] and hand it to the nav /
 * any glass surface through [LocalGlassBackdrop].
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

/** Marks this content as what the glass blurs: drawn normally, and also recorded into the backdrop layer. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
            this@drawWithContent.drawContent()
        }
        drawLayer(backdrop.layer)
    }

/**
 * Liquid-glass surface: the content behind it is blurred (Android 12+; older
 * phones get the plain tint), covered by a theme-coloured tint, soft edge
 * shading for depth, a light-catching gradient rim and a drop shadow.
 *
 * [alpha] is the user's navbar opacity (Settings). With blur on the tint is
 * lighter so the blurred content shows through, and the blur strength follows
 * the same value: 100 % opacity = [blurRadius] (max blur), 0 % = no blur.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    alpha: Float = NavBarPreferences.DEFAULT_GLASS_OPACITY,
    blurRadius: Dp = 22.dp
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val blurLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurOn = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tintAlpha = if (blurOn) alpha * 0.6f else alpha

    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val tint = colors.surfaceContainer
    val rim = if (isDark) {
        listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.40f))
    } else {
        listOf(Color.White.copy(alpha = 0.95f), colors.outline.copy(alpha = 0.30f), Color.White.copy(alpha = 0.70f))
    }

    return this
        .shadow(
            elevation = 20.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.18f),
            spotColor = Color.Black.copy(alpha = if (isDark) 0.60f else 0.28f)
        )
        .clip(shape)
        .then(
            if (blurOn && backdrop != null) {
                Modifier
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .drawBehind {
                        val r = blurRadius.toPx() * alpha.coerceIn(0f, 1f)
                        blurLayer.renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                        blurLayer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                            translate(left = backdrop.origin.x - origin.x, top = backdrop.origin.y - origin.y) {
                                drawLayer(backdrop.layer)
                            }
                        }
                        drawLayer(blurLayer)
                    }
            } else Modifier
        )
        .background(tint.copy(alpha = tintAlpha))
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.05f))
            )
        )
        .dropletDepth(if (isDark) 1f else 0.35f)
        .border(1.dp, Brush.linearGradient(rim), shape)
}

/** Fake 3D volume, drawn behind the content: edges that darken as they curve away and a shadowed lower belly. */
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
    /** icon starts rotated by this many degrees when it becomes active, then springs back */
    val fromRotation: Float,
    /** icon starts this many dp lower when it becomes active, then bounces up */
    val fromLift: Float,
    val onClick: () -> Unit
)

/**
 * Floating bottom bar: Home · Tools · Backup · Settings.
 * The active item sits in a lighter glass "lens" that springs between items
 * and reveals its label. You can also press and drag across the bar: the lens
 * follows the finger (with a haptic tick per item) and selects on release.
 */
@Composable
internal fun ScanAppBottomNav(
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
    glassOpacity: Float = NavBarPreferences.DEFAULT_GLASS_OPACITY,
    onHomeClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onToolsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {}
) {
    val entries = listOf(
        NavEntry("Home", Icons.Filled.Home, -10f, 4f, onHomeClick),
        NavEntry("Tools", Icons.Filled.Description, -20f, 3f, onToolsClick),
        NavEntry("Backup", Icons.Filled.Folder, 0f, 6f, onBackupClick),
        NavEntry("Settings", Icons.Filled.Settings, 140f, 0f, onSettingsClick)
    )
    val currentEntries by rememberUpdatedState(entries)
    val currentSelected by rememberUpdatedState(selectedIndex)

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary

    // item bounds (left, width) in px; only used to find which item is under the finger while dragging
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    var dragIndex by remember { mutableStateOf<Int?>(null) }

    // Optimistic selection: the lens and label react on the very frame of the tap, without waiting for the
    // parent to switch screens and hand us the new index. Cleared as soon as the real index arrives.
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedIndex) { pending = null }
    val activeIndex = dragIndex ?: pending ?: selectedIndex

    fun choose(index: Int) {
        val entry = currentEntries.getOrNull(index) ?: return
        if (index == currentSelected) return
        pending = index
        entry.onClick()
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

    // The outer box keeps the system-navigation-bar inset and spans the width so the pill is centred
    // wherever the caller aligns it; its measured height is what screens reserve via LocalFloatingNavHeight.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.liquidGlass(RoundedCornerShape(50), glassOpacity)) {
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
                        .shadow(
                            8.dp, CircleShape, clip = false,
                            ambientColor = accent.copy(alpha = 0.4f),
                            spotColor = Color.Black.copy(alpha = 0.55f)
                        )
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.20f))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))
                            )
                        )
                        .dropletDepth(0.9f)
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.6f), accent.copy(alpha = 0.15f), Color.White.copy(alpha = 0.35f))
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
}

@Composable
private fun NavItem(entry: NavEntry, active: Boolean, onBounds: (Float, Float) -> Unit, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val tint by animateColorAsState(if (active) accent else inactive, label = "navTint")

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
