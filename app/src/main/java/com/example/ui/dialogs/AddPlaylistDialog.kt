package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.SpoofPreset
import com.example.ui.ImportUiState
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.relocation.bringIntoViewRequester

private val Muted = Color(0xFF94A3B8)
private val Hint = Color(0xFF64748B)
private val Line = Color(0xFF334155)

/** Optional Stalker portal fields behind the "Advanced" button, plus which content to fetch. */
data class StalkerAdvanced(
    val username: String = "",
    val password: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val serial: String = "",
    val includeTv: Boolean = true,
    val includeVod: Boolean = false
)

/** "portal.example.com" from a playlist / portal address (used as the default playlist name, like TiviMate). */
private fun hostOf(address: String): String? = try {
    val a = address.trim()
    java.net.URI(if (a.contains("://")) a else "http://$a").host?.removePrefix("www.")?.ifBlank { null }
} catch (_: Exception) {
    null
}

/**
 * Add-playlist dialog. In TV mode it is a compact, D-pad friendly card: every control shows a
 * clear focus border, fields are single line where possible and focus starts on the tab row so
 * the on-screen keyboard only opens when a field is chosen.
 */
@Composable
fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onAddM3u: (name: String, source: String, isRaw: Boolean, spoof: SpoofPreset) -> Unit,
    onAddXtream: (name: String, serverUrl: String, user: String, pass: String, spoof: SpoofPreset, includeTv: Boolean, includeVod: Boolean) -> Unit,
    onAddStalker: (name: String, portalUrl: String, mac: String, spoof: SpoofPreset, advanced: StalkerAdvanced) -> Unit,
    onAddJson: (name: String, source: String, isRaw: Boolean, spoof: SpoofPreset) -> Unit,
    isTvMode: Boolean = false,
    importState: ImportUiState = ImportUiState.Idle,
    onFinishImport: (playlistId: Long, name: String) -> Unit = { _, _ -> },
    onDiscardImport: (playlistId: Long) -> Unit = {}
) {
    val tv = isTvMode
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("M3U", "XTREAM", "STALKER", "RAW JSON")

    var selectedSpoof by remember { mutableStateOf(SpoofPreset.TIVIMATE) }
    var spoofDropdownExpanded by remember { mutableStateOf(false) }

    // M3U state
    var m3uName by remember { mutableStateOf("") }
    var m3uSource by remember { mutableStateOf("") }

    // Xtream state
    var xtreamName by remember { mutableStateOf("") }
    var xtreamServer by remember { mutableStateOf("") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPass by remember { mutableStateOf("") }
    var xtreamIncludeTv by remember { mutableStateOf(true) }
    var xtreamIncludeVod by remember { mutableStateOf(false) }

    // Stalker state
    var stalkerName by remember { mutableStateOf("") }
    var stalkerPortal by remember { mutableStateOf("") }
    var stalkerMac by remember { mutableStateOf("") }
    var stalkerAdvancedOpen by remember { mutableStateOf(false) }
    var stalkerUser by remember { mutableStateOf("") }
    var stalkerPass by remember { mutableStateOf("") }
    var stalkerDeviceId by remember { mutableStateOf("") }
    var stalkerDeviceId2 by remember { mutableStateOf("") }
    var stalkerSerial by remember { mutableStateOf("") }
    var stalkerIncludeTv by remember { mutableStateOf(true) }
    var stalkerIncludeVod by remember { mutableStateOf(false) }

    // JSON state
    var jsonName by remember { mutableStateOf("") }
    var jsonSource by remember { mutableStateOf("") }
    var jsonIsRaw by remember { mutableStateOf(false) }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tv) {
            delay(150)
            try { firstTabFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    val gap = if (tv) 8.dp else 12.dp
    val shape = RoundedCornerShape(if (tv) 16.dp else 20.dp)

    val processing = importState is ImportUiState.Processing
    Dialog(
        // BACK / outside taps do nothing while the portal is being processed
        onDismissRequest = { if (!processing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // safeDrawingPadding = status / navigation bars + on-screen keyboard: the card shrinks above the keyboard
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = (if (tv) Modifier.width(520.dp) else Modifier.fillMaxWidth(0.92f))
                .heightIn(max = (screenHeightDp * 0.94f).dp)
                .clip(shape)
                .border(1.dp, Line, shape),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .padding(if (tv) 16.dp else 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add playlist",
                            fontSize = if (tv) 20.sp else 22.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { if (!processing) onDismiss() }, modifier = Modifier.size(if (tv) 36.dp else 48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                Spacer(Modifier.height(gap))

                if (importState is ImportUiState.Processing) {
                    ProcessingPanel(importState.message)
                } else if (importState is ImportUiState.Done) {
                    ProcessedPanel(
                        state = importState,
                        onFinish = { name -> onFinishImport(importState.playlistId, name); onDismiss() },
                        onBack = { onDiscardImport(importState.playlistId) }
                    )
                } else {
                if (importState is ImportUiState.Failed) {
                    Text(
                        text = importState.message,
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = gap)
                    )
                }

                // Tabs (segmented, focusable)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        val selected = selectedTab == index
                        FocusSurface(
                            onClick = { selectedTab = index },
                            selected = selected,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (index == 0) Modifier.focusRequester(firstTabFocus) else Modifier)
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                maxLines = 1,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.Black else Color.White,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = if (tv) 8.dp else 10.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(gap))

                // Player signature selector
                Text("Player signature", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Box {
                    FocusSurface(
                        onClick = { spoofDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = if (tv) 8.dp else 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = selectedSpoof.displayName,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(text = "▼", color = AccentCyan, fontSize = 10.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = spoofDropdownExpanded,
                        onDismissRequest = { spoofDropdownExpanded = false },
                        modifier = Modifier.background(DarkSurfaceElevated)
                    ) {
                        SpoofPreset.entries.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(preset.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(preset.description, color = Muted, fontSize = 10.sp)
                                    }
                                },
                                onClick = {
                                    selectedSpoof = preset
                                    spoofDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                if (selectedTab == 2) {
                    // Stalker: what to fetch sits right under the player signature, outside "Advanced"
                    Spacer(Modifier.height(gap))
                    OptionCheck("Include TV channels", stalkerIncludeTv) { stalkerIncludeTv = it }
                    Spacer(Modifier.height(4.dp))
                    OptionCheck("Include VOD (movies and shows)", stalkerIncludeVod) { stalkerIncludeVod = it }
                }

                Spacer(Modifier.height(gap + 2.dp))

                // Tab content
                when (selectedTab) {
                    0 -> {
                        PlaylistField(
                            label = "Playlist name",
                            value = m3uName,
                            onValueChange = { m3uName = it },
                            placeholder = "e.g. My M3U playlist",
                            modifier = Modifier.testTag("m3u_name_input")
                        )
                        Spacer(Modifier.height(gap))
                        PlaylistField(
                            label = "M3U / M3U8 URL",
                            value = m3uSource,
                            onValueChange = { m3uSource = it },
                            placeholder = "http://example.com/playlist.m3u8",
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                            modifier = Modifier.testTag("m3u_source_input")
                        )
                        Spacer(Modifier.height(gap + 4.dp))
                        SubmitButton(
                            text = "Import M3U playlist",
                            enabled = m3uSource.isNotBlank(),
                            modifier = Modifier.testTag("m3u_submit_btn")
                        ) {
                            val name = m3uName.ifBlank { hostOf(m3uSource) ?: "M3U Playlist" }
                            if (m3uSource.isNotBlank()) {
                                onAddM3u(name, m3uSource.trim(), false, selectedSpoof)
                            }
                        }
                    }

                    1 -> {
                        PlaylistField(
                            label = "Playlist name",
                            value = xtreamName,
                            onValueChange = { xtreamName = it },
                            placeholder = "e.g. Xtream service",
                            modifier = Modifier.testTag("xtream_name_input")
                        )
                        Spacer(Modifier.height(gap))
                        PlaylistField(
                            label = "Server URL",
                            value = xtreamServer,
                            onValueChange = { xtreamServer = it },
                            placeholder = "http://example.com:8080",
                            keyboardType = KeyboardType.Uri,
                            modifier = Modifier.testTag("xtream_server_input")
                        )
                        Spacer(Modifier.height(gap))
                        if (tv) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlaylistField(
                                    label = "Username",
                                    value = xtreamUser,
                                    onValueChange = { xtreamUser = it },
                                    modifier = Modifier.weight(1f).testTag("xtream_user_input")
                                )
                                PlaylistField(
                                    label = "Password",
                                    value = xtreamPass,
                                    onValueChange = { xtreamPass = it },
                                    imeAction = ImeAction.Done,
                                    modifier = Modifier.weight(1f).testTag("xtream_pass_input")
                                )
                            }
                        } else {
                            PlaylistField(
                                label = "Username",
                                value = xtreamUser,
                                onValueChange = { xtreamUser = it },
                                modifier = Modifier.testTag("xtream_user_input")
                            )
                            Spacer(Modifier.height(gap))
                            PlaylistField(
                                label = "Password",
                                value = xtreamPass,
                                onValueChange = { xtreamPass = it },
                                imeAction = ImeAction.Done,
                                modifier = Modifier.testTag("xtream_pass_input")
                            )
                        }
                        Spacer(Modifier.height(gap))
                        OptionCheck("Include TV channels", xtreamIncludeTv) { xtreamIncludeTv = it }
                        Spacer(Modifier.height(4.dp))
                        OptionCheck("Include VOD (movies and shows)", xtreamIncludeVod) { xtreamIncludeVod = it }
                        val ready = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank() &&
                            (xtreamIncludeTv || xtreamIncludeVod)
                        Spacer(Modifier.height(gap + 4.dp))
                        SubmitButton(
                            text = "Connect Xtream Codes",
                            enabled = ready,
                            modifier = Modifier.testTag("xtream_submit_btn")
                        ) {
                            val name = xtreamName.ifBlank { hostOf(xtreamServer) ?: "Xtream Playlist" }
                            if (ready) {
                                onAddXtream(name, xtreamServer.trim(), xtreamUser.trim(), xtreamPass.trim(), selectedSpoof, xtreamIncludeTv, xtreamIncludeVod)
                            }
                        }
                    }

                    2 -> {
                        PlaylistField(
                            label = "Playlist name",
                            value = stalkerName,
                            onValueChange = { stalkerName = it },
                            placeholder = "e.g. Stalker MAG portal",
                            modifier = Modifier.testTag("stalker_name_input")
                        )
                        Spacer(Modifier.height(gap))
                        PlaylistField(
                            label = "Portal URL",
                            value = stalkerPortal,
                            onValueChange = { stalkerPortal = it },
                            placeholder = "http://portal.domain.com/c/",
                            keyboardType = KeyboardType.Uri,
                            modifier = Modifier.testTag("stalker_portal_input")
                        )
                        Spacer(Modifier.height(gap))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PlaylistField(
                                label = "MAC address",
                                value = stalkerMac,
                                onValueChange = { stalkerMac = it.uppercase() },
                                placeholder = "00:1A:79:XX:XX:XX",
                                imeAction = ImeAction.Done,
                                modifier = Modifier.weight(1f).testTag("stalker_mac_input")
                            )
                            FocusSurface(
                                onClick = { stalkerAdvancedOpen = !stalkerAdvancedOpen },
                                selected = false,
                                modifier = Modifier.testTag("stalker_advanced_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = if (tv) 8.dp else 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Advanced", fontSize = 12.sp, color = AccentCyan, fontWeight = FontWeight.Medium)
                                    Text(if (stalkerAdvancedOpen) " ▲" else " ▼", fontSize = 9.sp, color = AccentCyan)
                                }
                            }
                        }
                        if (stalkerAdvancedOpen) {
                            Spacer(Modifier.height(gap))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlaylistField(
                                    label = "Username (optional)",
                                    value = stalkerUser,
                                    onValueChange = { stalkerUser = it },
                                    modifier = Modifier.weight(1f).testTag("stalker_user_input")
                                )
                                PlaylistField(
                                    label = "Password (optional)",
                                    value = stalkerPass,
                                    onValueChange = { stalkerPass = it },
                                    modifier = Modifier.weight(1f).testTag("stalker_pass_input")
                                )
                            }
                            Spacer(Modifier.height(gap))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlaylistField(
                                    label = "Device ID (optional)",
                                    value = stalkerDeviceId,
                                    onValueChange = { stalkerDeviceId = it },
                                    modifier = Modifier.weight(1f).testTag("stalker_device_input")
                                )
                                PlaylistField(
                                    label = "Device ID 2 (optional)",
                                    value = stalkerDeviceId2,
                                    onValueChange = { stalkerDeviceId2 = it },
                                    modifier = Modifier.weight(1f).testTag("stalker_device2_input")
                                )
                            }
                            Spacer(Modifier.height(gap))
                            PlaylistField(
                                label = "Serial number (optional)",
                                value = stalkerSerial,
                                onValueChange = { stalkerSerial = it },
                                imeAction = ImeAction.Done,
                                modifier = Modifier.testTag("stalker_serial_input")
                            )
                        }
                        val ready = stalkerPortal.isNotBlank() && stalkerMac.isNotBlank() &&
                            (stalkerIncludeTv || stalkerIncludeVod)
                        Spacer(Modifier.height(gap + 4.dp))
                        SubmitButton(
                            text = "Connect Stalker portal",
                            enabled = ready,
                            modifier = Modifier.testTag("stalker_submit_btn")
                        ) {
                            val name = stalkerName.ifBlank { hostOf(stalkerPortal) ?: "Stalker Portal" }
                            if (ready) {
                                onAddStalker(
                                    name, stalkerPortal.trim(), stalkerMac.trim(), selectedSpoof,
                                    StalkerAdvanced(
                                        username = stalkerUser.trim(),
                                        password = stalkerPass.trim(),
                                        deviceId = stalkerDeviceId.trim(),
                                        deviceId2 = stalkerDeviceId2.trim(),
                                        serial = stalkerSerial.trim(),
                                        includeTv = stalkerIncludeTv,
                                        includeVod = stalkerIncludeVod
                                    )
                                )
                            }
                        }
                    }

                    3 -> {
                        PlaylistField(
                            label = "Playlist name",
                            value = jsonName,
                            onValueChange = { jsonName = it },
                            placeholder = "e.g. Custom JSON playlist",
                            modifier = Modifier.testTag("json_name_input")
                        )
                        Spacer(Modifier.height(gap))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ModeChip("JSON URL", !jsonIsRaw) { jsonIsRaw = false }
                            ModeChip("Paste JSON", jsonIsRaw) { jsonIsRaw = true }
                        }
                        Spacer(Modifier.height(gap))
                        PlaylistField(
                            label = if (jsonIsRaw) "JSON text" else "JSON URL",
                            value = jsonSource,
                            onValueChange = { jsonSource = it },
                            placeholder = if (jsonIsRaw) "{\n  \"channels\": [\n    {\"name\": \"...\", \"url\": \"...\"}\n  ]\n}" else "http://example.com/channels.json",
                            singleLine = !jsonIsRaw,
                            minLines = if (jsonIsRaw) 3 else 1,
                            maxLines = if (jsonIsRaw) 6 else 1,
                            keyboardType = if (jsonIsRaw) KeyboardType.Text else KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                            modifier = Modifier.testTag("json_source_input")
                        )
                        Spacer(Modifier.height(gap + 4.dp))
                        SubmitButton(
                            text = "Import JSON playlist",
                            enabled = jsonSource.isNotBlank(),
                            modifier = Modifier.testTag("json_submit_btn")
                        ) {
                            val name = jsonName.ifBlank { (if (jsonIsRaw) null else hostOf(jsonSource)) ?: "JSON Playlist" }
                            if (jsonSource.isNotBlank()) {
                                onAddJson(name, jsonSource.trim(), jsonIsRaw, selectedSpoof)
                            }
                        }
                    }
                }
                } // end of form (not processing / processed)
            }
        }
        }
    }
}

/** Check box row (D-pad focusable) used for "Include TV channels" / "Include VOD". */
@Composable
private fun OptionCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FocusSurface(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) AccentCyan else Muted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ProcessingPanel(progress: String = "") {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = AccentCyan, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text("Processing...", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (progress.isNotBlank()) progress else "Large portals with movies and shows can take a minute.",
            fontSize = 12.sp,
            color = Muted
        )
    }
}

/** "Playlist is processed": what was found (channels, and movies / shows when VOD was fetched) + name. */
@Composable
private fun ProcessedPanel(
    state: ImportUiState.Done,
    onFinish: (name: String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(state) { mutableStateOf(state.name) }
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(state) {
        delay(120)
        try { doneFocus.requestFocus() } catch (_: Exception) {}
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Playlist is processed", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Channels: ${state.counts.tv}", fontSize = 14.sp, color = Muted)
        if (state.counts.hasVod) {
            Text("Movies: ${state.counts.movies}", fontSize = 14.sp, color = Muted)
            Text("Shows: ${state.counts.shows}", fontSize = 14.sp, color = Muted)
        }
        if (state.counts.incomplete) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Some movies / shows could not be loaded because the portal refused part of the requests. Update the playlist later to fetch the rest.",
                fontSize = 12.sp,
                color = Color(0xFFFFB74D)
            )
        }
        Spacer(Modifier.height(12.dp))
        PlaylistField(
            label = "Playlist name",
            value = name,
            onValueChange = { name = it },
            imeAction = ImeAction.Done,
            modifier = Modifier.testTag("processed_name_input")
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubmitButton(
                text = "Done",
                enabled = true,
                modifier = Modifier.weight(1f).focusRequester(doneFocus).testTag("processed_done_btn")
            ) { onFinish(name) }
            FocusSurface(onClick = onBack, modifier = Modifier.weight(1f).testTag("processed_back_btn")) {
                Text(
                    text = "Back",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 11.dp)
                )
            }
        }
    }
}

/** Rounded, clickable box that draws a bright border while focused (D-pad). */
@Composable
private fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    container: Color = DarkSurfaceVariant,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) AccentCyan else container)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Line, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, selected = selected) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SubmitButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FocusSurface(
        onClick = onClick,
        selected = enabled,
        container = DarkSurfaceElevated,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.Black else Hint,
            modifier = Modifier.padding(vertical = 11.dp)
        )
    }
}

/** Compact labelled text field (label above, placeholder inside) with a focus-highlighted border. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier = modifier.bringIntoViewRequester(bringIntoView)) {
        Text(label, fontSize = 12.sp, color = if (focused) AccentCyan else Muted, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(AccentCyan),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) imeAction else ImeAction.Default
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        // the card shrinks when the keyboard opens: scroll so the field (and its label) stay above it
                        scope.launch {
                            delay(150); bringIntoView.bringIntoView()
                            delay(450); bringIntoView.bringIntoView()
                        }
                    }
                }
                .clip(shape)
                .background(DarkSurfaceVariant)
                .border(if (focused) 2.dp else 1.dp, if (focused) AccentCyan else Line, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = Hint, fontSize = 14.sp)
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
fun iptvTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = Color(0xFF334155),
    focusedLabelColor = AccentCyan,
    unfocusedLabelColor = Color(0xFF94A3B8),
    cursorColor = AccentCyan,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)
