package com.example.player.drm

import android.os.Looper
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

data class ClearKeyEntry(
    val kidBytes: ByteArray,
    val kidBase64Url: String,
    val keyBase64Url: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClearKeyEntry
        if (!kidBytes.contentEquals(other.kidBytes)) return false
        if (kidBase64Url != other.kidBase64Url) return false
        if (keyBase64Url != other.keyBase64Url) return false
        return true
    }

    override fun hashCode(): Int {
        var result = kidBytes.contentHashCode()
        result = 31 * result + kidBase64Url.hashCode()
        result = 31 * result + keyBase64Url.hashCode()
        return result
    }
}

object ClearKeyHelper {

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("-", "").trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun bytesToBase64Url(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()
    }

    fun normalizeToBase64Url(str: String): String {
        val trimmed = str.trim()
        val cleanHex = trimmed.replace("-", "")
        if (cleanHex.length == 32 && cleanHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return bytesToBase64Url(hexToBytes(cleanHex))
        }
        return trimmed.replace('+', '-').replace('/', '_').replace("=", "")
    }

    fun parseClearKeys(raw: String): List<ClearKeyEntry> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val results = mutableListOf<ClearKeyEntry>()

        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                if (json.has("keys")) {
                    val arr = json.getJSONArray("keys")
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val k = obj.optString("k")
                        val kid = obj.optString("kid")
                        if (k.isNotBlank() && kid.isNotBlank()) {
                            val kidB64 = normalizeToBase64Url(kid)
                            val keyB64 = normalizeToBase64Url(k)
                            val kidBytes = try {
                                Base64.decode(kid.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
                            } catch (_: Exception) {
                                hexToBytes(kid)
                            }
                            results.add(ClearKeyEntry(kidBytes, kidB64, keyB64))
                        }
                    }
                    return results
                } else {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val kid = keys.next()
                        val k = json.getString(kid)
                        val kidB64 = normalizeToBase64Url(kid)
                        val keyB64 = normalizeToBase64Url(k)
                        val kidBytes = try {
                            hexToBytes(kid)
                        } catch (_: Exception) {
                            Base64.decode(kid, Base64.DEFAULT)
                        }
                        results.add(ClearKeyEntry(kidBytes, kidB64, keyB64))
                    }
                    return results
                }
            } catch (_: Exception) {}
        }

        // Delimited list of kid:key (e.g., kid1:key1,kid2:key2 or kid:key)
        val tokens = trimmed.replace(";", ",").replace("\n", ",").split(",")
        for (token in tokens) {
            val parts = token.trim().split(":")
            if (parts.size == 2) {
                val rawKid = parts[0].trim()
                val rawKey = parts[1].trim()
                if (rawKid.isNotBlank() && rawKey.isNotBlank()) {
                    val kidB64 = normalizeToBase64Url(rawKid)
                    val keyB64 = normalizeToBase64Url(rawKey)
                    val kidBytes = try {
                        val clean = rawKid.replace("-", "")
                        if (clean.length == 32) hexToBytes(clean) else Base64.decode(rawKid, Base64.DEFAULT)
                    } catch (_: Exception) {
                        ByteArray(16)
                    }
                    results.add(ClearKeyEntry(kidBytes, kidB64, keyB64))
                }
            }
        }
        return results
    }

    fun buildJwkBytes(entries: List<ClearKeyEntry>): ByteArray {
        val keysArray = JSONArray()
        for (entry in entries) {
            val item = JSONObject().apply {
                put("kty", "oct")
                put("k", entry.keyBase64Url)
                put("kid", entry.kidBase64Url)
            }
            keysArray.put(item)
        }
        val jwk = JSONObject().apply {
            put("keys", keysArray)
            put("type", "temporary")
        }
        return jwk.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildPsshAtom(systemUuid: UUID, kidBytesList: List<ByteArray>): ByteArray {
        val validKids = kidBytesList.filter { it.size == 16 }
        val numKids = if (validKids.isNotEmpty()) validKids.size else 1
        val totalSize = 32 + (numKids * 16) + 4
        val buf = ByteBuffer.allocate(totalSize)
        buf.putInt(totalSize)
        buf.put("pssh".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x01000000) // version 1, flags 0
        buf.putLong(systemUuid.mostSignificantBits)
        buf.putLong(systemUuid.leastSignificantBits)
        buf.putInt(numKids)
        if (validKids.isNotEmpty()) {
            for (kid in validKids) {
                buf.put(kid)
            }
        } else {
            buf.put(ByteArray(16))
        }
        buf.putInt(0) // Data size = 0
        return buf.array()
    }

    fun createClearKeyDrmSessionManager(keyIdKeyString: String): DrmSessionManager? {
        val entries = parseClearKeys(keyIdKeyString)
        if (entries.isEmpty()) return null

        val isClearKeySupported = try {
            FrameworkMediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID)
        } catch (_: Throwable) {
            false
        }
        if (!isClearKeySupported) {
            return null
        }

        val jwkBytes = buildJwkBytes(entries)
        val callback = LocalClearKeyDrmCallback(jwkBytes)
        val defaultDrmSessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(callback)

        return ClearKeyDrmSessionManager(defaultDrmSessionManager, entries)
    }
}

class LocalClearKeyDrmCallback(private val jwkBytes: ByteArray) : MediaDrmCallback {
    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest
    ): ByteArray {
        return ByteArray(0)
    }

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest
    ): ByteArray {
        return jwkBytes
    }
}

class ClearKeyDrmSessionManager(
    private val delegate: DrmSessionManager,
    private val keyEntries: List<ClearKeyEntry>
) : DrmSessionManager {

    private val psshCommon: ByteArray by lazy {
        ClearKeyHelper.buildPsshAtom(C.COMMON_PSSH_UUID, keyEntries.map { it.kidBytes })
    }

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {
        delegate.setPlayer(playbackLooper, playerId)
    }

    override fun prepare() {
        delegate.prepare()
    }

    override fun release() {
        delegate.release()
    }

    override fun getCryptoType(format: Format): Int {
        val patched = patchFormat(format)
        return delegate.getCryptoType(patched)
    }

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSession? {
        val patched = patchFormat(format)
        return delegate.acquireSession(eventDispatcher, patched)
    }

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSessionManager.DrmSessionReference {
        val patched = patchFormat(format)
        return delegate.preacquireSession(eventDispatcher, patched)
    }

    fun patchFormat(format: Format): Format {
        // Do not attach DRM to subtitle or metadata tracks
        val sampleMime = format.sampleMimeType
        if (sampleMime != null &&
            !MimeTypes.isVideo(sampleMime) &&
            !MimeTypes.isAudio(sampleMime) &&
            format.drmInitData == null
        ) {
            return format
        }

        val existingInitData = format.drmInitData
        val schemeDatas = mutableListOf<DrmInitData.SchemeData>()

        // CRITICAL FIX: Android libclearkey InitDataParser::parse ONLY accepts "video/mp4",
        // "audio/mp4", or "cenc". Passing format.sampleMimeType (e.g. "video/avc", "audio/mp4a-latm")
        // causes InitDataParser::parse to fail immediately with ERROR_DRM_CANNOT_HANDLE (cdmError: 4).
        val mime = if (sampleMime?.let { MimeTypes.isAudio(it) } == true) {
            MimeTypes.AUDIO_MP4
        } else {
            MimeTypes.VIDEO_MP4
        }

        // CRITICAL FIX: Android libclearkey isClearKeyUUID ONLY recognizes kCommonPsshBoxUUID (1077efec...)
        // or kClearKeyUUID (e2719d58...). It does NOT recognize C.CLEARKEY_UUID (e676aa15...) inside the PSSH!
        // DefaultDrmSessionManager explicitly matches C.COMMON_PSSH_UUID when initialized with C.CLEARKEY_UUID.
        // Therefore, psshCommon (built with C.COMMON_PSSH_UUID) MUST be used as the PSSH payload.
        schemeDatas.add(DrmInitData.SchemeData(C.COMMON_PSSH_UUID, mime, psshCommon))
        schemeDatas.add(DrmInitData.SchemeData(C.CLEARKEY_UUID, mime, psshCommon))

        if (existingInitData != null) {
            for (i in 0 until existingInitData.schemeDataCount) {
                val orig = existingInitData[i]
                if (orig.uuid != C.CLEARKEY_UUID && orig.uuid != C.COMMON_PSSH_UUID) {
                    schemeDatas.add(orig)
                }
            }
        }

        val patchedInitData = DrmInitData(existingInitData?.schemeType ?: "cenc", schemeDatas)
        return format.buildUpon().setDrmInitData(patchedInitData).build()
    }
}
