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
        channelCount = 9,
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

            // Channel 2: Tears of Steel UHD (Akamai Live HLS)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "tears_of_steel",
                name = "Tears of Steel HD (Akamai Live HLS)",
                streamUrl = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8",
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

            // Channel 4: NASA TV Official Live Stream
            ChannelEntity(
                playlistId = playlistId,
                streamId = "nasa_tv_hls",
                name = "NASA TV Official (Live Stream)",
                streamUrl = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=200",
                groupTitle = "Documentary & Science",
                channelNumber = 4,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            ),

            // Channel 5: Apple Advanced BipBop (Multi-bitrate HLS)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "apple_bipbop_hls",
                name = "Apple Test BipBop 1080p (Adaptive HLS)",
                streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1579783902614-a3fb3927b675?w=200",
                groupTitle = "Entertainment",
                channelNumber = 5,
                httpUserAgent = "AppleCoreMedia/1.0.0.19E241 (Apple TV; U; CPU OS 15_4 like Mac OS X; en_us)"
            ),

            // Channel 6: Deutsche Welle News English
            ChannelEntity(
                playlistId = playlistId,
                streamId = "dw_english_hls",
                name = "DW News English (Live 24/7)",
                streamUrl = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=200",
                groupTitle = "News & Info",
                channelNumber = 6,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            ),

            // Channel 7: Angel One Clear DASH (Google Shaka Multi-track)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "angel_one_dash",
                name = "Angel One Sci-Fi (DASH Multi-track)",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1518173946687-a4c8892bbd9f?w=200",
                groupTitle = "Sci-Fi & Series",
                channelNumber = 7,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            ),

            // Channel 8: MN+ HD (ClearKey DRM DASH)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "MN+.HD.in",
                name = "MN+ HD",
                streamUrl = "https://times-ott-live.akamaized.net/mnplus_wv_drm/index.mpd",
                logoUrl = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/india/mn-plus-hd-in.png",
                groupTitle = "Movies",
                channelNumber = 8,
                drmType = DrmType.CLEARKEY.name,
                drmKeyIdKey = "40f019b86241d23ef075633fd7f1e927:058dec845bd340178a388edd104a015e",
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)",
                isFavorite = true
            ),

            // Channel 9: Widevine DRM Test Stream (Tagged for testing on physical devices)
            ChannelEntity(
                playlistId = playlistId,
                streamId = "drm_widevine_demo",
                name = "[DRM Widevine] Angel One Widevine Modular",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one-widevine/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=200",
                groupTitle = "DRM Protected Streams",
                channelNumber = 9,
                drmType = DrmType.WIDEVINE.name,
                drmLicenseUrl = "https://cwip-shaka-proxy.appspot.com/no_auth",
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            )
        )
    }

    fun getDemoPrograms(): List<ProgramEntity> {
        val now = System.currentTimeMillis()

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

            // Channel 4: NASA TV
            ProgramEntity(
                epgChannelId = "nasa_tv_hls",
                title = "NASA Space Station Live & Earth Views",
                description = "Live views of Earth from the International Space Station with commentary on ongoing orbital science experiments.",
                category = "Science",
                startTimeMs = now - (30 * 60_000L),
                endTimeMs = now + (60 * 60_000L)
            ),

            // Channel 5: Apple BipBop
            ProgramEntity(
                epgChannelId = "apple_bipbop_hls",
                title = "Adaptive Bitrate Benchmark (1080p60)",
                description = "Apple HLS technical test patterns with audio timecode synchronization and multi-bitrate ladder demonstration.",
                category = "Technology",
                startTimeMs = now - (10 * 60_000L),
                endTimeMs = now + (50 * 60_000L)
            ),

            // Channel 6: DW News
            ProgramEntity(
                epgChannelId = "dw_english_hls",
                title = "DW Global News Bulletin",
                description = "International news, in-depth reports, and business analysis broadcast live worldwide from Berlin.",
                category = "News",
                startTimeMs = now - (15 * 60_000L),
                endTimeMs = now + (45 * 60_000L)
            ),

            // Channel 7: Angel One
            ProgramEntity(
                epgChannelId = "angel_one_dash",
                title = "Star Explorer: Mission to Angel One",
                description = "Crew explores a constitutional oligarchy planet ruled by a council, navigating diplomatic challenges.",
                category = "Sci-Fi",
                startTimeMs = now - (30 * 60_000L),
                endTimeMs = now + (30 * 60_000L)
            ),

            // Channel 8: MN+ HD
            ProgramEntity(
                epgChannelId = "MN+.HD.in",
                title = "Hollywood Blockbuster Prime",
                description = "High-definition premiere of international cinema and critically acclaimed award-winning feature films in pristine surround audio.",
                category = "Movies",
                startTimeMs = now - (30 * 60_000L),
                endTimeMs = now + (90 * 60_000L)
            ),

            // Channel 9: Widevine DRM
            ProgramEntity(
                epgChannelId = "drm_widevine_demo",
                title = "Widevine Modular Test Broadcast",
                description = "Google Widevine Modular CDM test stream verifying hardware and software decryptor pipeline on certified devices.",
                category = "Technology",
                startTimeMs = now - (25 * 60_000L),
                endTimeMs = now + (35 * 60_000L)
            )
        )
    }
}
