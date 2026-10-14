package com.example.ui.dialogs

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.local.entity.ChannelEntity
import com.example.data.model.DrmType
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.StatusDrm

@Composable
fun DrmDebuggerDialog(
    onDismiss: () -> Unit,
    onTestStream: (ChannelEntity) -> Unit
) {
    var streamUrl by remember { mutableStateOf("https://storage.googleapis.com/shaka-demo-assets/angel-one-widevine/dash.mpd") }
    var streamName by remember { mutableStateOf("Custom DRM Stream") }
    var drmType by remember { mutableStateOf(DrmType.WIDEVINE) }
    var licenseUrl by remember { mutableStateOf("https://cwip-shaka-proxy.appspot.com/no_auth") }
    var keyIdKey by remember { mutableStateOf("") }
    var customAuthHeader by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp)),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = StatusDrm, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DRM Playback Debugger",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                Text(
                    text = "Test encrypted IPTV streams directly with Widevine Modular or ClearKey DRM license endpoints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = streamName,
                    onValueChange = { streamName = it },
                    label = { Text("Stream Label") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = iptvTextFieldColors()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("Stream Manifest URL (.mpd / .m3u8)") },
                    modifier = Modifier.fillMaxWidth().testTag("drm_stream_url_input"),
                    colors = iptvTextFieldColors()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // DRM Type Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DrmType.entries.filter { it != DrmType.NONE }.forEach { type ->
                        val isSelected = (drmType == type)
                        Button(
                            onClick = {
                                drmType = type
                                if (type == DrmType.WIDEVINE && licenseUrl.isBlank()) {
                                    licenseUrl = "https://cwip-shaka-proxy.appspot.com/no_auth"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) StatusDrm else Color(0xFF1E293B),
                                contentColor = if (isSelected) Color.Black else Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(type.title.substringBefore(" "), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = licenseUrl,
                    onValueChange = { licenseUrl = it },
                    label = { Text("DRM License Server URL") },
                    placeholder = { Text("https://license.server/get_license") },
                    modifier = Modifier.fillMaxWidth().testTag("drm_license_url_input"),
                    colors = iptvTextFieldColors()
                )

                if (drmType == DrmType.CLEARKEY) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyIdKey,
                        onValueChange = { keyIdKey = it },
                        label = { Text("ClearKey (keyId:key in hex)") },
                        placeholder = { Text("1a8330defa338271e1ee304d9e030b6b:052c922579b1836f6d8959954a781b24") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = iptvTextFieldColors()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customAuthHeader,
                    onValueChange = { customAuthHeader = it },
                    label = { Text("Authorization / Custom License Header") },
                    placeholder = { Text("Bearer eyJhbGciOi...") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = iptvTextFieldColors()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        val headersJson = if (customAuthHeader.isNotBlank()) {
                            "{\"Authorization\": \"${customAuthHeader.replace("\"", "\\\"")}\"}"
                        } else null

                        val testChannel = ChannelEntity(
                            id = 99999,
                            playlistId = 0,
                            name = streamName.ifBlank { "Test DRM Stream" },
                            streamUrl = streamUrl.trim(),
                            groupTitle = "DRM Testing",
                            drmType = drmType.name,
                            drmLicenseUrl = licenseUrl.trim().ifBlank { null },
                            drmKeyIdKey = keyIdKey.trim().ifBlank { null },
                            drmHeadersJson = headersJson
                        )
                        onTestStream(testChannel)
                        onDismiss()
                    },
                    enabled = streamUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("play_drm_test_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDrm, contentColor = Color.Black)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start DRM Playback", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
