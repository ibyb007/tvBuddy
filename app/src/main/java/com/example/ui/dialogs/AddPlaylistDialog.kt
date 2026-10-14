package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.SpoofPreset
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import kotlin.random.Random

@Composable
fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onAddM3u: (name: String, source: String, isRaw: Boolean, spoof: SpoofPreset) -> Unit,
    onAddXtream: (name: String, serverUrl: String, user: String, pass: String, spoof: SpoofPreset) -> Unit,
    onAddStalker: (name: String, portalUrl: String, mac: String, spoof: SpoofPreset) -> Unit,
    onAddJson: (name: String, source: String, isRaw: Boolean, spoof: SpoofPreset) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("M3U / M3U8", "Xtream Codes", "Stalker / MAC", "Raw JSON")

    var selectedSpoof by remember { mutableStateOf(SpoofPreset.TIVIMATE) }
    var spoofDropdownExpanded by remember { mutableStateOf(false) }

    // M3U state
    var m3uName by remember { mutableStateOf("") }
    var m3uSource by remember { mutableStateOf("") }
    var m3uIsRaw by remember { mutableStateOf(false) }

    // Xtream state
    var xtreamName by remember { mutableStateOf("") }
    var xtreamServer by remember { mutableStateOf("") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPass by remember { mutableStateOf("") }

    // Stalker state
    var stalkerName by remember { mutableStateOf("") }
    var stalkerPortal by remember { mutableStateOf("") }
    var stalkerMac by remember { mutableStateOf("00:1A:79:") }

    // JSON state
    var jsonName by remember { mutableStateOf("") }
    var jsonSource by remember { mutableStateOf("") }
    var jsonIsRaw by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp)),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Add IPTV Playlist",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Import M3U, Xtream, Stalker Portal or Raw JSON",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Spoof Signature Selector Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Player Signature Spoofing",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Disguises requests as TiviMate, OTT Navigator, or other players so providers won't block connections.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Box {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceElevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { spoofDropdownExpanded = true }
                                    .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = selectedSpoof.displayName,
                                        color = Color.White,
                                        fontSize = 13.sp,
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
                                                Text(preset.description, color = Color(0xFF94A3B8), fontSize = 10.sp)
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
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkSurface,
                    contentColor = AccentCyan,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = AccentCyan
                        )
                    }
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == index) AccentCyan else Color(0xFF94A3B8)
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Content
                when (selectedTab) {
                    0 -> { // M3U Tab
                        OutlinedTextField(
                            value = m3uName,
                            onValueChange = { m3uName = it },
                            label = { Text("Playlist Name") },
                            placeholder = { Text("e.g. My M3U Playlist") },
                            modifier = Modifier.fillMaxWidth().testTag("m3u_name_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TextButton(
                                onClick = { m3uIsRaw = false },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (!m3uIsRaw) AccentCyan else Color(0xFF94A3B8)
                                )
                            ) {
                                Text("URL", fontWeight = if (!m3uIsRaw) FontWeight.Bold else FontWeight.Normal)
                            }
                            TextButton(
                                onClick = { m3uIsRaw = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (m3uIsRaw) AccentCyan else Color(0xFF94A3B8)
                                )
                            ) {
                                Text("Paste Text", fontWeight = if (m3uIsRaw) FontWeight.Bold else FontWeight.Normal)
                            }
                        }

                        OutlinedTextField(
                            value = m3uSource,
                            onValueChange = { m3uSource = it },
                            label = { Text(if (m3uIsRaw) "M3U Content" else "M3U / M3U8 URL") },
                            placeholder = { Text(if (m3uIsRaw) "#EXTM3U\n#EXTINF:-1,Channel 1\nhttp://..." else "http://example.com/playlist.m3u8") },
                            modifier = Modifier.fillMaxWidth().testTag("m3u_source_input"),
                            maxLines = if (m3uIsRaw) 6 else 2,
                            colors = iptvTextFieldColors()
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val name = m3uName.ifBlank { "M3U Playlist" }
                                if (m3uSource.isNotBlank()) {
                                    onAddM3u(name, m3uSource.trim(), m3uIsRaw, selectedSpoof)
                                    onDismiss()
                                }
                            },
                            enabled = m3uSource.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("m3u_submit_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                        ) {
                            Text("Import M3U Playlist", fontWeight = FontWeight.Bold)
                        }
                    }

                    1 -> { // Xtream Codes Tab
                        OutlinedTextField(
                            value = xtreamName,
                            onValueChange = { xtreamName = it },
                            label = { Text("Playlist Name") },
                            placeholder = { Text("e.g. Xtream Service") },
                            modifier = Modifier.fillMaxWidth().testTag("xtream_name_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = xtreamServer,
                            onValueChange = { xtreamServer = it },
                            label = { Text("Server URL (http://domain:port)") },
                            placeholder = { Text("http://example.com:8080") },
                            modifier = Modifier.fillMaxWidth().testTag("xtream_server_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = xtreamUser,
                            onValueChange = { xtreamUser = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth().testTag("xtream_user_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = xtreamPass,
                            onValueChange = { xtreamPass = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth().testTag("xtream_pass_input"),
                            colors = iptvTextFieldColors()
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val name = xtreamName.ifBlank { "Xtream Playlist" }
                                if (xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()) {
                                    onAddXtream(name, xtreamServer.trim(), xtreamUser.trim(), xtreamPass.trim(), selectedSpoof)
                                    onDismiss()
                                }
                            },
                            enabled = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("xtream_submit_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                        ) {
                            Text("Connect Xtream Codes", fontWeight = FontWeight.Bold)
                        }
                    }

                    2 -> { // Stalker Portal Tab
                        OutlinedTextField(
                            value = stalkerName,
                            onValueChange = { stalkerName = it },
                            label = { Text("Playlist Name") },
                            placeholder = { Text("e.g. Stalker MAG Portal") },
                            modifier = Modifier.fillMaxWidth().testTag("stalker_name_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = stalkerPortal,
                            onValueChange = { stalkerPortal = it },
                            label = { Text("Portal URL") },
                            placeholder = { Text("http://portal.domain.com/c/") },
                            modifier = Modifier.fillMaxWidth().testTag("stalker_portal_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = stalkerMac,
                                onValueChange = { stalkerMac = it.uppercase() },
                                label = { Text("MAC Address") },
                                placeholder = { Text("00:1A:79:XX:XX:XX") },
                                modifier = Modifier.weight(1f).testTag("stalker_mac_input"),
                                colors = iptvTextFieldColors()
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceElevated,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        // Generate standard MAG250 random MAC
                                        val b1 = String.format("%02X", Random.nextInt(256))
                                        val b2 = String.format("%02X", Random.nextInt(256))
                                        val b3 = String.format("%02X", Random.nextInt(256))
                                        stalkerMac = "00:1A:79:$b1:$b2:$b3"
                                    }
                                    .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Random", fontSize = 11.sp, color = AccentCyan)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val name = stalkerName.ifBlank { "Stalker Portal" }
                                if (stalkerPortal.isNotBlank() && stalkerMac.isNotBlank()) {
                                    onAddStalker(name, stalkerPortal.trim(), stalkerMac.trim(), selectedSpoof)
                                    onDismiss()
                                }
                            },
                            enabled = stalkerPortal.isNotBlank() && stalkerMac.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("stalker_submit_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                        ) {
                            Text("Connect Stalker Portal", fontWeight = FontWeight.Bold)
                        }
                    }

                    3 -> { // Raw JSON Tab
                        OutlinedTextField(
                            value = jsonName,
                            onValueChange = { jsonName = it },
                            label = { Text("Playlist Name") },
                            placeholder = { Text("e.g. Custom JSON Playlist") },
                            modifier = Modifier.fillMaxWidth().testTag("json_name_input"),
                            colors = iptvTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TextButton(
                                onClick = { jsonIsRaw = false },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (!jsonIsRaw) AccentCyan else Color(0xFF94A3B8)
                                )
                            ) {
                                Text("JSON URL", fontWeight = if (!jsonIsRaw) FontWeight.Bold else FontWeight.Normal)
                            }
                            TextButton(
                                onClick = { jsonIsRaw = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (jsonIsRaw) AccentCyan else Color(0xFF94A3B8)
                                )
                            ) {
                                Text("Paste JSON", fontWeight = if (jsonIsRaw) FontWeight.Bold else FontWeight.Normal)
                            }
                        }

                        OutlinedTextField(
                            value = jsonSource,
                            onValueChange = { jsonSource = it },
                            label = { Text(if (jsonIsRaw) "JSON Text" else "JSON URL") },
                            placeholder = { Text(if (jsonIsRaw) "{\n  \"channels\": [\n    {\"name\": \"...\", \"url\": \"...\"}\n  ]\n}" else "http://example.com/channels.json") },
                            modifier = Modifier.fillMaxWidth().testTag("json_source_input"),
                            maxLines = if (jsonIsRaw) 6 else 2,
                            colors = iptvTextFieldColors()
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val name = jsonName.ifBlank { "JSON Playlist" }
                                if (jsonSource.isNotBlank()) {
                                    onAddJson(name, jsonSource.trim(), jsonIsRaw, selectedSpoof)
                                    onDismiss()
                                }
                            },
                            enabled = jsonSource.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("json_submit_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                        ) {
                            Text("Import JSON Playlist", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
