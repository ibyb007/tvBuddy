package com.example.data.parser

import com.example.data.local.entity.ChannelEntity
import com.example.data.model.DrmType
import org.json.JSONArray
import org.json.JSONObject

object JsonPlaylistParser {

    fun parse(
        content: String,
        playlistId: Long,
        defaultUserAgent: String? = null,
        defaultHeaders: Map<String, String> = emptyMap()
    ): List<ChannelEntity> {
        val channels = mutableListOf<ChannelEntity>()
        val trimmed = content.trim()

        try {
            val jsonArray: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                when {
                    root.has("channels") -> root.getJSONArray("channels")
                    root.has("items") -> root.getJSONArray("items")
                    root.has("streams") -> root.getJSONArray("streams")
                    else -> JSONArray()
                }
            }

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val name = item.optString("name", item.optString("title", "Channel ${i + 1}"))
                val streamUrl = item.optString("url", item.optString("streamUrl", item.optString("stream_url", "")))
                if (streamUrl.isBlank()) continue

                val logo = item.optString("logo", item.optString("logoUrl", item.optString("icon", ""))).ifBlank { null }
                val group = item.optString("group", item.optString("category", item.optString("groupTitle", "General"))).ifBlank { "General" }
                val id = item.optString("id", item.optString("streamId", "json_${i + 1}"))
                val chno = item.optInt("channelNumber", item.optInt("chno", i + 1))

                var drmType: DrmType = DrmType.NONE
                var drmLicenseUrl: String? = null
                var drmKeyIdKey: String? = null
                var drmHeadersJson: String? = null

                if (item.has("drm")) {
                    val drmObj = item.optJSONObject("drm")
                    if (drmObj != null) {
                        drmType = DrmType.fromString(drmObj.optString("type"))
                        drmLicenseUrl = drmObj.optString("licenseUrl", drmObj.optString("license_url", "")).ifBlank { null }
                        drmKeyIdKey = drmObj.optString("keyIdKey", drmObj.optString("key", "")).ifBlank { null }
                        if (drmObj.has("headers")) {
                            drmHeadersJson = drmObj.optJSONObject("headers")?.toString()
                        }
                    }
                }

                val userAgent = item.optString("userAgent", item.optString("user_agent", defaultUserAgent ?: "")).ifBlank { defaultUserAgent }
                val headersJson = if (item.has("headers")) {
                    item.optJSONObject("headers")?.toString()
                } else if (defaultHeaders.isNotEmpty()) {
                    JSONObject(defaultHeaders).toString()
                } else null

                channels.add(
                    ChannelEntity(
                        playlistId = playlistId,
                        streamId = id,
                        name = name,
                        streamUrl = streamUrl,
                        logoUrl = logo,
                        groupTitle = group,
                        channelNumber = chno,
                        drmType = if (drmType != DrmType.NONE) drmType.name else null,
                        drmLicenseUrl = drmLicenseUrl,
                        drmHeadersJson = drmHeadersJson,
                        drmKeyIdKey = drmKeyIdKey,
                        httpUserAgent = userAgent,
                        httpHeadersJson = headersJson
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return channels
    }
}
