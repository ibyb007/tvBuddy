package com.example.data.parser

import com.example.data.local.entity.ChannelEntity
import com.example.data.model.DrmType
import java.io.BufferedReader
import java.io.StringReader

object M3uParser {

    fun normalizeM3uContent(rawContent: String): String {
        var text = rawContent.replace("\r\n", "\n").replace("\r", "\n")
        // Split tags if they appear joined without preceding newline
        text = text.replace(Regex("(?<!\n)(#(?:EXTINF|KODIPROP|EXTVLCOPT|EXTGRP|EXT-X-|EXTM3U)):"), "\n$1:")
        // Split URLs if attached directly after a tag value or channel name without newline
        text = text.replace(Regex("([^\\s\\n\"=,])(https?://)"), "$1\n$2")
        return text
    }

    fun parse(
        content: String,
        playlistId: Long,
        defaultUserAgent: String? = null,
        defaultHeaders: Map<String, String> = emptyMap()
    ): List<ChannelEntity> {
        val channels = mutableListOf<ChannelEntity>()
        val normalized = normalizeM3uContent(content)
        val reader = BufferedReader(StringReader(normalized))
        var line: String?

        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = "General"
        var currentTvgId = ""
        var currentChannelNum = 0
        var currentDrmType: DrmType = DrmType.NONE
        var currentDrmLicenseUrl: String? = null
        var currentDrmKeyIdKey: String? = null
        var currentUserAgent: String? = defaultUserAgent
        val currentHeaders = defaultHeaders.toMutableMap()
        var hasPendingChannel = false

        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
                    hasPendingChannel = true
                    currentTvgId = extractAttribute(trimmed, "tvg-id")
                    currentName = extractAttribute(trimmed, "tvg-name").ifBlank {
                        extractChannelName(trimmed)
                    }
                    currentLogo = extractAttribute(trimmed, "tvg-logo").ifBlank { null }
                    val group = extractAttribute(trimmed, "group-title")
                    if (group.isNotBlank()) {
                        currentGroup = group
                    }
                    val chno = extractAttribute(trimmed, "tvg-chno")
                    if (chno.isNotBlank()) {
                        currentChannelNum = chno.toIntOrNull() ?: (channels.size + 1)
                    } else {
                        currentChannelNum = channels.size + 1
                    }
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val grp = trimmed.substringAfter(":").trim()
                    if (grp.isNotBlank()) currentGroup = grp
                }
                trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = trimmed.substringAfter(":")
                    if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                        currentUserAgent = opt.substringAfter("=").trim()
                    } else if (opt.startsWith("http-referrer=", ignoreCase = true)) {
                        currentHeaders["Referer"] = opt.substringAfter("=").trim()
                    }
                }
                trimmed.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val prop = trimmed.substringAfter(":")
                    val key = prop.substringBefore("=").trim().lowercase()
                    val value = prop.substringAfter("=").trim()
                    when (key) {
                        "inputstream.adaptive.license_type" -> {
                            currentDrmType = DrmType.fromString(value)
                        }
                        "inputstream.adaptive.license_key" -> {
                            if (value.startsWith("{") || (value.contains(":") && !value.startsWith("http"))) {
                                currentDrmKeyIdKey = value
                                if (currentDrmType == DrmType.NONE) currentDrmType = DrmType.CLEARKEY
                            } else {
                                currentDrmLicenseUrl = value
                            }
                        }
                    }
                }
                !trimmed.startsWith("#") -> {
                    if (hasPendingChannel || trimmed.startsWith("http") || trimmed.startsWith("rtsp")) {
                        var streamUrl = trimmed
                        // Parse pipe syntax: http://stream.m3u8|User-Agent=...&Referer=...
                        if (streamUrl.contains("|")) {
                            val parts = streamUrl.split("|", limit = 2)
                            streamUrl = parts[0]
                            val queryParams = parts[1].split("&")
                            for (param in queryParams) {
                                val kv = param.split("=", limit = 2)
                                if (kv.size == 2) {
                                    val k = kv[0].trim()
                                    val v = kv[1].trim()
                                    if (k.equals("User-Agent", ignoreCase = true)) {
                                        currentUserAgent = v
                                    } else {
                                        currentHeaders[k] = v
                                    }
                                }
                            }
                        }

                        val name = if (currentName.isNotBlank()) currentName else "Channel ${channels.size + 1}"
                        val headersJson = if (currentHeaders.isNotEmpty()) {
                            currentHeaders.entries.joinToString(prefix = "{", postfix = "}") {
                                "\"${it.key}\": \"${it.value.replace("\"", "\\\"")}\""
                            }
                        } else null

                        channels.add(
                            ChannelEntity(
                                playlistId = playlistId,
                                streamId = currentTvgId.ifBlank { "m3u_${channels.size + 1}" },
                                name = name,
                                streamUrl = streamUrl,
                                logoUrl = currentLogo,
                                groupTitle = currentGroup,
                                channelNumber = currentChannelNum,
                                drmType = if (currentDrmType != DrmType.NONE) currentDrmType.name else null,
                                drmLicenseUrl = currentDrmLicenseUrl,
                                drmKeyIdKey = currentDrmKeyIdKey,
                                httpUserAgent = currentUserAgent,
                                httpHeadersJson = headersJson
                            )
                        )

                        // Reset channel state
                        currentName = ""
                        currentLogo = null
                        currentTvgId = ""
                        currentDrmType = DrmType.NONE
                        currentDrmLicenseUrl = null
                        currentDrmKeyIdKey = null
                        currentUserAgent = defaultUserAgent
                        currentHeaders.clear()
                        currentHeaders.putAll(defaultHeaders)
                        hasPendingChannel = false
                    }
                }
            }
        }

        return channels
    }

    private fun extractAttribute(line: String, attribute: String): String {
        val pattern = """$attribute="([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = pattern.find(line)
        if (match != null) {
            return match.groupValues[1]
        }
        val patternNoQuotes = """$attribute=([^,\s]+)""".toRegex(RegexOption.IGNORE_CASE)
        return patternNoQuotes.find(line)?.groupValues?.get(1) ?: ""
    }

    private fun extractChannelName(line: String): String {
        val commaIndex = line.lastIndexOf(',')
        return if (commaIndex != -1 && commaIndex < line.length - 1) {
            line.substring(commaIndex + 1).trim()
        } else {
            ""
        }
    }
}
