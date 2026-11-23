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

/** Tells the dialog when one of its text fields has focus (the TV keyboard covers the lower half of the screen). */
private val LocalOnFieldFocus = androidx.compose.runtime.compositionLocalOf<(Boolean) -> Unit> { {} }

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

/** What the "Auto detect" tab decided the pasted address is. */
private enum class DetectedType(val label: String) { M3U("M3U playlist"), XTREAM("Xtream Codes"), STALKER("Stalker portal") }

private class Detection(
    val type: DetectedType,
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val mac: String = ""
)

private val MacRegex = Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")

/**
 * Works out what kind of playlist a pasted address is:
 *  - get.php / player_api.php / xmltv.php (or username + password parameters) -> Xtream Codes,
 *  - a MAC address in the text, a /c/ or stalker_portal or portal.php path     -> Stalker portal,
 *  - anything else                                                             -> M3U playlist.
 */
private fun detectPlaylist(input: String, macField: String): Detection? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val mac = macField.trim().ifBlank { MacRegex.find(text)?.value.orEmpty() }.uppercase()
    val address = text.split(Regex("\\s+")).firstOrNull { it.startsWith("http", true) }
        ?: text.split(Regex("\\s+")).firstOrNull { !MacRegex.matches(it) }
        ?: return null
    val uri = try { android.net.Uri.parse(if (address.contains("://")) address else "http://$address") } catch (_: Exception) { null }
        ?: return null
    val path = uri.path.orEmpty().lowercase()
    val user = uri.getQueryParameter("username").orEmpty()
    val pass = uri.getQueryParameter("password").orEmpty()
    val root = uri.scheme.orEmpty().ifBlank { "http" } + "://" + uri.authority.orEmpty()
    val xtreamPath = path.endsWith("get.php") || path.endsWith("player_api.php") || path.endsWith("xmltv.php")
    if ((xtreamPath && user.isNotBlank() && pass.isNotBlank()) || (user.isNotBlank() && pass.isNotBlank() && !path.contains("/c"))) {
        return Detection(DetectedType.XTREAM, server = root, user = user, pass = pass)
    }
    val stalkerPath = path.endsWith("/c") || path.contains("/c/") || path.contains("stalker_portal") ||
        path.endsWith("portal.php") || path.contains("/portal")
    if (mac.isNotEmpty() || stalkerPath) return Detection(DetectedType.STALKER, server = address, mac = mac)
    return Detection(DetectedType.M3U, server = address)
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

/** Device ids derived from the MAC address (the way IPTVnator / MAG emulators do): serial = MD5(mac)[0..13], ids = SHA-256(mac). */
private fun deviceIdsFromMac(mac: String): Triple<String, String, String> {
    val m = mac.trim().uppercase()
    if (m.isEmpty()) return Triple("", "", "")
    val sha = hex(java.security.MessageDigest.getInstance("SHA-256").digest(m.toByteArray()))
    val md5 = hex(java.security.MessageDigest.getInstance("MD5").digest(m.toByteArray()))
    return Triple(sha, sha, md5.take(13))
}

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
    isTvMode: Boolean = false,
    importState: ImportUiState = ImportUiState.Idle,
    onFinishImport: (playlistId: Long, name: String) -> Unit = { _, _ -> },
    onDiscardImport: (playlistId: Long) -> Unit = {}
) {
    val tv = isTvMode
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("M3U", "XTREAM", "STALKER", "AUTO")

    var selectedSpoof by remember { mutableStateOf(SpoofPreset.TIVIMATE) }
    var spoofDropdownExpanded by remember { mutableStateOf(false) }

    // M3U state
    var m3uName by remember { mutableStateOf("") }
    var m3uSource by remember { mutableStateOf("") }
    var m3uFileName by remember { mutableStateOf("") }
    var m3uFileContent by remember { mutableStateOf("") }
    var m3uFileError by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val m3uPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (text.isBlank()) {
                    m3uFileError = "The file is empty"
                } else {
                    m3uFileContent = text
                    m3uFileError = ""
                    var display = ""
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) display = c.getString(0).orEmpty()
                        }
                    } catch (_: Exception) {}
                    m3uFileName = display.ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }.ifBlank { "playlist.m3u" }
                }
            } catch (e: Exception) {
                m3uFileError = "Could not read the file"
            }
        }
    }

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
    var stalkerGenIds by remember { mutableStateOf(false) }
    var stalkerIncludeTv by remember { mutableStateOf(true) }
    var stalkerIncludeVod by remember { mutableStateOf(false) }

    // Auto detect state
    var autoName by remember { mutableStateOf("") }
    var autoInput by remember { mutableStateOf("") }
    var autoMac by remember { mutableStateOf("") }
    var autoIncludeTv by remember { mutableStateOf(true) }
    var autoIncludeVod by remember { mutableStateOf(false) }

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

    // TV: the on-screen keyboard is a panel over the lower ~half of the screen and does not resize the window.
    // While a field is being edited the card moves to the top and shrinks to the free area above the keyboard.
    var focusedFields by remember { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(focusedFields > 0) {
        if (focusedFields > 0) typing = true else { delay(400); typing = false }   // no flicker when hopping between fields
    }
    val compactForKeyboard = tv && typing
    val onFieldFocus: (Boolean) -> Unit = { gained -> focusedFields = (focusedFields + if (gained) 1 else -1).coerceAtLeast(0) }
    Dialog(
        // BACK / outside taps do nothing while the portal is being processed
        onDismissRequest = { if (!processing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // safeDrawingPadding = status / navigation bars + on-screen keyboard: the card shrinks above the keyboard
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = if (compactForKeyboard) Alignment.TopCenter else Alignment.Center
        ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOnFieldFocus provides onFieldFocus) {
        Surface(
            modifier = (if (tv) Modifier.width(520.dp) else Modifier.fillMaxWidth(0.92f))
                .padding(top = if (compactForKeyboard) 8.dp else 0.dp)
                .heightIn(max = (screenHeightDp * (if (compactForKeyboard) 0.5f else 0.94f)).dp)
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

                if (selectedTab == 0) {
                    // M3U: import a playlist file from the device, right above the player signature
                    Text("Playlist file", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    FocusSurface(
                        onClick = {
                            try { m3uPicker.launch(arrayOf("*/*")) } catch (_: Exception) { m3uFileError = "No file picker available on this device" }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("m3u_import_file_btn")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (tv) 8.dp else 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (m3uFileName.isNotBlank()) m3uFileName else "Import M3U file",
                                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(text = if (m3uFileName.isNotBlank()) "Change" else "Browse", color = AccentCyan, fontSize = 12.sp)
                        }
                    }
                    if (m3uFileError.isNotBlank()) {
                        Text(m3uFileError, color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    } else if (m3uFileName.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("The file is used instead of the URL below", color = Hint, fontSize = 11.sp)
                            Text(
                                "Remove", color = AccentCyan, fontSize = 11.sp,
                                modifier = Modifier.clickable { m3uFileName = ""; m3uFileContent = "" }
                            )
                        }
                    }
                    Spacer(Modifier.height(gap))
                }

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
                            label = if (m3uFileContent.isNotBlank()) "M3U / M3U8 URL (ignored, file selected)" else "M3U / M3U8 URL",
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
                            enabled = m3uSource.isNotBlank() || m3uFileContent.isNotBlank(),
                            modifier = Modifier.testTag("m3u_submit_btn")
                        ) {
                            if (m3uFileContent.isNotBlank()) {
                                val name = m3uName.ifBlank { m3uFileName.substringBeforeLast('.').ifBlank { "M3U Playlist" } }
                                onAddM3u(name, m3uFileContent, true, selectedSpoof)
                            } else if (m3uSource.isNotBlank()) {
                                val name = m3uName.ifBlank { hostOf(m3uSource) ?: "M3U Playlist" }
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
                            OptionCheck("Generate device IDs from MAC address", stalkerGenIds) { stalkerGenIds = it }
                            Spacer(Modifier.height(gap))
                            if (stalkerGenIds) {
                                Text(
                                    "Device ID, Device ID 2 and serial number are derived from the MAC address above.",
                                    fontSize = 11.sp, color = Hint
                                )
                            } else {
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
                                val gen = if (stalkerGenIds) deviceIdsFromMac(stalkerMac) else null
                                onAddStalker(
                                    name, stalkerPortal.trim(), stalkerMac.trim(), selectedSpoof,
                                    StalkerAdvanced(
                                        username = stalkerUser.trim(),
                                        password = stalkerPass.trim(),
                                        deviceId = gen?.first ?: stalkerDeviceId.trim(),
                                        deviceId2 = gen?.second ?: stalkerDeviceId2.trim(),
                                        serial = gen?.third ?: stalkerSerial.trim(),
                                        includeTv = stalkerIncludeTv,
                                        includeVod = stalkerIncludeVod
                                    )
                                )
                            }
                        }
                    }

                    3 -> {
                        val detection = detectPlaylist(autoInput, autoMac)
                        PlaylistField(
                            label = "Playlist name (optional)",
                            value = autoName,
                            onValueChange = { autoName = it },
                            placeholder = "e.g. My playlist",
                            modifier = Modifier.testTag("auto_name_input")
                        )
                        Spacer(Modifier.height(gap))
                        PlaylistField(
                            label = "Playlist URL or portal address",
                            value = autoInput,
                            onValueChange = { autoInput = it },
                            placeholder = "M3U link, Xtream get.php link or Stalker portal (+ MAC)",
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                            modifier = Modifier.testTag("auto_input")
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = detection?.let { "Detected: ${it.type.label}" } ?: "Paste a link and the type is detected for you",
                            fontSize = 12.sp,
                            color = if (detection != null) AccentCyan else Hint,
                            fontWeight = FontWeight.Medium
                        )
                        if (detection?.type == DetectedType.STALKER) {
                            Spacer(Modifier.height(gap))
                            PlaylistField(
                                label = "MAC address",
                                value = if (autoMac.isBlank()) detection.mac else autoMac,
                                onValueChange = { autoMac = it.uppercase() },
                                placeholder = "00:1A:79:XX:XX:XX",
                                modifier = Modifier.testTag("auto_mac_input")
                            )
                        }
                        if (detection != null && detection.type != DetectedType.M3U) {
                            Spacer(Modifier.height(gap))
                            OptionCheck("Include TV channels", autoIncludeTv) { autoIncludeTv = it }
                            Spacer(Modifier.height(4.dp))
                            OptionCheck("Include VOD (movies and shows)", autoIncludeVod) { autoIncludeVod = it }
                        }
                        val ready = detection != null && when (detection.type) {
                            DetectedType.M3U -> true
                            DetectedType.XTREAM -> autoIncludeTv || autoIncludeVod
                            DetectedType.STALKER -> (autoMac.ifBlank { detection.mac }).isNotBlank() && (autoIncludeTv || autoIncludeVod)
                        }
                        Spacer(Modifier.height(gap + 4.dp))
                        SubmitButton(
                            text = "Detect and import",
                            enabled = ready,
                            modifier = Modifier.testTag("auto_submit_btn")
                        ) {
                            if (detection != null && ready) {
                                when (detection.type) {
                                    DetectedType.M3U -> onAddM3u(
                                        autoName.ifBlank { hostOf(detection.server) ?: "M3U Playlist" },
                                        detection.server, false, selectedSpoof
                                    )
                                    DetectedType.XTREAM -> onAddXtream(
                                        autoName.ifBlank { hostOf(detection.server) ?: "Xtream Playlist" },
                                        detection.server, detection.user, detection.pass, selectedSpoof, autoIncludeTv, autoIncludeVod
                                    )
                                    DetectedType.STALKER -> onAddStalker(
                                        autoName.ifBlank { hostOf(detection.server) ?: "Stalker Portal" },
                                        detection.server, autoMac.ifBlank { detection.mac }.trim(), selectedSpoof,
                                        StalkerAdvanced(includeTv = autoIncludeTv, includeVod = autoIncludeVod)
                                    )
                                }
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
    val onFieldFocus = LocalOnFieldFocus.current
    var reported by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (reported) onFieldFocus(false) } }
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
                    if (it.isFocused != reported) { reported = it.isFocused; onFieldFocus(it.isFocused) }
                    if (it.isFocused) {
                        // the card shrinks when the keyboard opens: scroll so the field (and its label) stay above it
                        scope.launch {
                            delay(200); bringIntoView.bringIntoView()
                            delay(500); bringIntoView.bringIntoView()
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
