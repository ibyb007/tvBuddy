package com.example.data.sample

import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.local.entity.ProgramEntity
import com.example.data.model.DrmType
import com.example.data.model.PlaylistType

object DemoChannels {

    val demoPlaylist = PlaylistEntity(
        id = 1,
        name = "tvBuddy Curated & DRM Test Streams",
        type = PlaylistType.M3U,
        sourceUrl = "local://curated_demo.m3u",
        spoofPreset = "TIVIMATE",
        channelCount = 7,
        isActive = true
    )

    fun getDemoChannels(playlistId: Long = 1): List<ChannelEntity> {
        return listOf(
            // Channel 1: Big Buck Bunny Live HLS (World standard ultra-reliable multi-bitrate HLS)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "mux_bbb_hls",
                name = "Big Buck Bunny HD (Live HLS)",
                streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=200",
                groupTitle = "Entertainment",
                channelNumber = 1,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)",
                isFavorite = true
            ),

            // Channel 2: Tears of Steel UHD (Adaptive MP4 / Google CDN)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "tears_of_steel",
                name = "Tears of Steel 4K UHD",
                streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                logoUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=200",
                groupTitle = "Movies & Cinema",
                channelNumber = 2,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)",
                isFavorite = true
            ),

            // Channel 3: Sintel Clear DASH (Multi-language Audio & Subtitles)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "sintel_dash",
                name = "Sintel Movie (Multi-Audio & Subtitles DASH)",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/sintel/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1578328819058-b69f3a3b0f6b?w=200",
                groupTitle = "Movies & Cinema",
                channelNumber = 3,
                httpUserAgent = "OTT Navigator/1.6.8.5 (Android TV; Android 11; OTT-Nav)"
            ),

            // Channel 4: Angel One Clear DASH (Google Shaka Multi-track)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "angel_one_dash",
                name = "Angel One Sci-Fi (DASH Multi-track)",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1518173946687-a4c8892bbd9f?w=200",
                groupTitle = "Sci-Fi & Series",
                channelNumber = 4,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            ),

            // Channel 5: Elephants Dream (Animation Classics)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "elephants_dream",
                name = "Elephants Dream Animation (HD)",
                streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                logoUrl = "https://images.unsplash.com/photo-1579783902614-a3fb3927b675?w=200",
                groupTitle = "Entertainment",
                channelNumber = 5,
                httpUserAgent = "VLC/3.5.4 (Linux; Android 12; LibVLC/3.5.4)"
            ),

            // Channel 6: ClearKey DRM Test Stream (Angel One with ClearKey)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "drm_clearkey_demo",
                name = "[DRM ClearKey] Angel One ClearKey Protected",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one-clearkey/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1526374965328-7f61d4dc18c5?w=200",
                groupTitle = "DRM Protected Streams",
                channelNumber = 6,
                drmType = DrmType.CLEARKEY.name,
                drmKeyIdKey = "43215678123412341234123412341234:12341234123412341234123412341234",
                drmLicenseUrl = "https://cwip-shaka-proxy.appspot.com/clearkey?key_id=43215678123412341234123412341234&key=12341234123412341234123412341234",
                httpUserAgent = "OTT Navigator/1.6.8.5 (Android TV; Android 11; OTT-Nav)"
            ),

            // Channel 7: Widevine DRM Test Stream (Angel One with Widevine)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "drm_widevine_demo",
                name = "[DRM Widevine] Angel One Widevine Modular",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one-widevine/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=200",
                groupTitle = "DRM Protected Streams",
                channelNumber = 7,
                drmType = DrmType.WIDEVINE.name,
                drmLicenseUrl = "https://cwip-shaka-proxy.appspot.com/no_auth",
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            )
        )
    }

    fun getDemoPrograms(): List<ProgramEntity> {
        val now = System.currentTimeMillis()
        val hour = 3600_000L

        return listOf(
            // Channel 1: Big Buck Bunny
            ProgramEntity(
                epgChannelId = "mux_bbb_hls",
                title = "Big Buck Bunny: Forest Adventure",
                description = "Follow the gentle giant rabbit through an idyllic forest clearing as he confronts mischievous forest creatures.",
                category = "Animation",
                startTimeMs = now - (20 * 60_000L),
                endTimeMs = now + (40 * 60_000L)
            ),
            ProgramEntity(
                epgChannelId = "mux_bbb_hls",
                title = "Open Source Cinema: Behind The Scenes",
                description = "A documentary detailing the creation of pioneering open-source films and Blender CGI rendering.",
                category = "Documentary",
                startTimeMs = now + (40 * 60_000L),
                endTimeMs = now + (100 * 60_000L)
            ),

            // Channel 2: Tears of Steel
            ProgramEntity(
                epgChannelId = "tears_of_steel",
                title = "Tears of Steel: Dystopian Amsterdam",
                description = "In a dystopian future, a group of warriors and scientists gather at the Oude Kerk in Amsterdam to stage a crucial technological experiment.",
                category = "Sci-Fi",
                startTimeMs = now - (15 * 60_000L),
                endTimeMs = now + (45 * 60_000L)
            ),
            ProgramEntity(
                epgChannelId = "tears_of_steel",
                title = "Cybernetic Frontiers",
                description = "Deep dive into visual effects, motion tracking, and robotics in modern cinematic art.",
                category = "Sci-Fi",
                startTimeMs = now + (45 * 60_000L),
                endTimeMs = now + (105 * 60_000L)
            ),

            // Channel 3: Sintel
            ProgramEntity(
                epgChannelId = "sintel_dash",
                title = "Sintel: The Dragon Chronicles",
                description = "A lonely young woman searches the world for a baby dragon she befriended and nursed back to health.",
                category = "Fantasy",
                startTimeMs = now - (10 * 60_000L),
                endTimeMs = now + (50 * 60_000L)
            ),
            ProgramEntity(
                epgChannelId = "sintel_dash",
                title = "Sintel: Extended Lore & Audio Tracks",
                description = "Exploration of the fantasy landscapes and multilingual audio tracks produced for Sintel.",
                category = "Fantasy",
                startTimeMs = now + (50 * 60_000L),
                endTimeMs = now + (110 * 60_000L)
            ),

            // Channel 4: Angel One
            ProgramEntity(
                epgChannelId = "angel_one_dash",
                title = "Star Explorer: Mission to Angel One",
                description = "Crew explores a constitutional oligarchy planet ruled by a council, navigating diplomatic challenges.",
                category = "Sci-Fi",
                startTimeMs = now - (30 * 60_000L),
                endTimeMs = now + (30 * 60_000L)
            ),

            // Channel 6: ClearKey DRM
            ProgramEntity(
                epgChannelId = "drm_clearkey_demo",
                title = "ClearKey Encrypted Showcase",
                description = "Demonstration of W3C ClearKey DRM standard with dynamic key exchange and cryptographic stream decryption.",
                category = "Technology",
                startTimeMs = now - (25 * 60_000L),
                endTimeMs = now + (35 * 60_000L)
            ),

            // Channel 7: Widevine DRM
            ProgramEntity(
                epgChannelId = "drm_widevine_demo",
                title = "Widevine Modular Test Broadcast",
                description = "Google Widevine Modular CDM test stream verifying hardware and software decryptor pipeline.",
                category = "Technology",
                startTimeMs = now - (25 * 60_000L),
                endTimeMs = now + (35 * 60_000L)
            )
        )
    }
}
