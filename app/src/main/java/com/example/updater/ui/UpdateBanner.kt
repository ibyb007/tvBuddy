package com.example.updater.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.updater.AppUpdate
import com.example.updater.ReleaseNotes
import com.example.updater.UpdateUiState
import com.example.updater.VersionUtils
import kotlinx.coroutines.delay

private val BannerBg = Color(0xFF1E2229)
private val BannerBorder = Color(0xFF2E3440)
private val BannerText = Color(0xFFF1F3F4)
private val BannerSubText = Color(0xFF9AA0A6)
private val BannerAccent = Color(0xFF4285F4)
private val BannerSuccess = Color(0xFF34A853)
private val BannerError = Color(0xFFFF8A80)
private const val AUTO_DISMISS_MS = 20_000L

/**
 * Draws the update banner over the top of the app, plus the release notes / unknown-sources dialogs and the
 * "latest version" toasts. Put it in a Box above the app content.
 */
@Composable
fun UpdateBannerHost(
    state: UpdateUiState,
    onDismissBanner: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismissUnknownSources: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onFeedbackShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showReleaseNotes by remember { mutableStateOf(false) }

    LaunchedEffect(state.update?.tag) { showReleaseNotes = false }

    LaunchedEffect(state.feedbackMessage) {
        val message = state.feedbackMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onFeedbackShown()
    }

    // Back from the system "install unknown apps" screen: carry on with the install
    DisposableEffect(lifecycleOwner, state.showUnknownSourcesDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.showUnknownSourcesDialog) onInstall()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A newer release takes the banner; otherwise it can report the release that was just installed automatically.
    val update = state.update ?: state.installedUpdate
    val showBanner = state.showBanner && update != null
    val isInstalled = state.update == null && state.installedUpdate != null

    // "Updated" banner closes by itself
    LaunchedEffect(showBanner, isInstalled, showReleaseNotes) {
        if (showBanner && isInstalled && !showReleaseNotes) {
            delay(AUTO_DISMISS_MS)
            onDismissBanner()
        }
    }

    AnimatedVisibility(
        modifier = modifier,
        visible = showBanner,
        enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(300)) + fadeIn(tween(180)),
        exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(240)) + fadeOut(tween(150))
    ) {
        update?.let {
            UpdateBanner(
                state = state,
                update = it,
                isInstalledUpdate = isInstalled,
                onDownload = onDownload,
                onInstall = onInstall,
                onShowReleaseNotes = { showReleaseNotes = true },
                onDismiss = onDismissBanner
            )
        }
    }

    if (showReleaseNotes && update != null) {
        UpdateReleaseNotesDialog(update = update, onDismiss = { showReleaseNotes = false })
    }

    if (state.showUnknownSourcesDialog) {
        UpdateUnknownSourcesDialog(onOpenSettings = onOpenUnknownSources, onDismiss = onDismissUnknownSources)
    }
}

@Composable
private fun UpdateBanner(
    state: UpdateUiState,
    update: AppUpdate,
    isInstalledUpdate: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onShowReleaseNotes: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val progress by animateFloatAsState(
        targetValue = (if (state.isDownloading) state.downloadProgress ?: 0f else 0f).coerceIn(0f, 1f),
        animationSpec = tween(180),
        label = "updateBannerProgress"
    )
    val subtitle = when {
        isInstalledUpdate -> "tvBuddy was updated automatically"
        state.errorMessage != null -> state.errorMessage
        state.isDownloading && state.downloadProgress != null -> "Downloading… ${(state.downloadProgress * 100).toInt().coerceIn(0, 100)}%"
        state.isDownloading -> "Preparing download…"
        state.downloadedApkPath != null -> "Download complete. Ready to install"
        else -> "A new version is available"
    }
    val label = listOfNotNull(
        VersionUtils.display(update.tag),
        update.assetSizeBytes?.takeUnless { isInstalledUpdate }?.let { Formatter.formatShortFileSize(context, it) }
    ).joinToString(" • ")
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                drawRect(BannerBg)
                if (progress > 0f) {
                    drawRect(BannerAccent.copy(alpha = 0.35f), size = Size(size.width * progress, size.height))
                }
            }
            .border(1.dp, BannerBorder, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isInstalledUpdate) {
                Box(Modifier.size(26.dp).background(BannerSuccess, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = BannerText, modifier = Modifier.size(26.dp))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = BannerText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle.orEmpty(),
                    color = if (state.errorMessage != null) BannerError else BannerSubText,
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }

            if (ReleaseNotes.summarize(update.notes).isNotEmpty()) {
                BannerButton(icon = Icons.Default.Info, description = "Release notes", onClick = onShowReleaseNotes)
            }

            if (!isInstalledUpdate) {
                BannerButton(
                    label = when {
                        state.isDownloading -> "Downloading…"
                        state.downloadedApkPath != null -> "Install"
                        state.errorMessage != null -> "Retry"
                        else -> "Download"
                    },
                    primary = true,
                    enabled = !state.isDownloading,
                    onClick = { if (state.downloadedApkPath != null) onInstall() else onDownload() }
                )
            }

            if (!state.isDownloading) {
                BannerButton(icon = Icons.Default.Close, description = "Close", onClick = onDismiss)
            }
        }
    }
}

/** Pill / round button that shows a white ring while focused (D-pad), since the banner is used on TV too. */
@Composable
private fun BannerButton(
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = if (label != null) RoundedCornerShape(50) else CircleShape
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (primary) BannerAccent else Color.White.copy(alpha = 0.10f))
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .then(if (label != null) Modifier.padding(horizontal = 18.dp, vertical = 10.dp) else Modifier.size(42.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, tint = BannerText, modifier = Modifier.size(20.dp))
            }
        }
        if (label != null) {
            Text(
                label,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** What changed in a release: at most [ReleaseNotes.MAX_ENTRIES] lines, so nothing needs scrolling. */
@Composable
private fun UpdateReleaseNotesDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val entries = remember(update.notes) { ReleaseNotes.summarize(update.notes) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(4) {
            try { closeFocus.requestFocus(); return@LaunchedEffect } catch (_: Exception) { delay(60) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BannerBg,
        titleContentColor = BannerText,
        title = { Text("What's new in ${VersionUtils.display(update.tag)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) {
                    Text("No release notes.", color = BannerSubText, fontSize = 14.sp)
                } else {
                    entries.forEach { Text("•  $it", color = BannerSubText, fontSize = 14.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)) {
                Text("Close", color = BannerAccent)
            }
        }
    )
}

@Composable
private fun UpdateUnknownSourcesDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val openFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(4) {
            try { openFocus.requestFocus(); return@LaunchedEffect } catch (_: Exception) { delay(60) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BannerBg,
        titleContentColor = BannerText,
        textContentColor = BannerSubText,
        title = { Text("Allow installs", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text("To install the update, allow tvBuddy to install apps from this source, then come back.", fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onOpenSettings, modifier = Modifier.focusRequester(openFocus)) {
                Text("Open settings", color = BannerAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = BannerSubText) } }
    )
}
