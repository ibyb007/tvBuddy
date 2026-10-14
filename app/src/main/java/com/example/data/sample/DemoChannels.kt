package com.example.data.sample

import com.example.data.local.entity.ChannelEntity
import com.example.data.local.entity.PlaylistEntity
import com.example.data.model.DrmType
import com.example.data.model.PlaylistType

object DemoChannels {

    val demoPlaylist = PlaylistEntity(
        id = 1,
        name = "tvBuddy Curated & DRM Test Streams",
        type = PlaylistType.M3U,
        sourceUrl = "local://curated_demo.m3u",
        spoofPreset = "TIVIMATE",
        channelCount = 8,
        isActive = true
    )

    fun getDemoChannels(playlistId: Long = 1): List<ChannelEntity> {
        return listOf(
            // DRM Widevine Test Channel
            ChannelEntity(
                playlistId = playlistId,
                streamId = "drm_widevine_demo",
                name = "[DRM Widevine] Angel One HD (Test Stream)",
                streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one-widevine/dash.mpd",
                logoUrl = "https://images.unsplash.com/photo-1518173946687-a4c8892bbd9f?w=200",
                groupTitle = "DRM Protected Streams",
                channelNumber = 1,
                drmType = DrmType.WIDEVINE.name,
                drmLicenseUrl = "https://cwip-shaka-proxy.appspot.com/no_auth",
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            ),

            // DRM ClearKey Test Channel
            ChannelEntity(
                playlistId = playlistId,
                streamId = "drm_clearkey_demo",
                name = "[DRM ClearKey] Big Buck Bunny 4K (ClearKey)",
                streamUrl = "https://dash.akamaized.net/dash/vod/sample/drm/clearkey/bbb_clearkey.mpd",
                logoUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=200",
                groupTitle = "DRM Protected Streams",
                channelNumber = 2,
                drmType = DrmType.CLEARKEY.name,
                drmKeyIdKey = "1a8330defa338271e1ee304d9e030b6b:052c922579b1836f6d8959954a781b24",
                drmLicenseUrl = "https://cwip-shaka-proxy.appspot.com/clearkey?key_id=1a8330defa338271e1ee304d9e030b6b&key=052c922579b1836f6d8959954a781b24",
                httpUserAgent = "OTT Navigator/1.6.8.5 (Android TV; Android 11; OTT-Nav)"
            ),

            // Live News Channels
            ChannelEntity(
                playlistId = playlistId,
                streamId = "nasa_tv_hd",
                name = "NASA TV HD (Public Broadcast)",
                streamUrl = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1614728894747-a83421e2b9c9?w=200",
                groupTitle = "News & Science",
                channelNumber = 3,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)",
                isFavorite = true
            ),

            ChannelEntity(
                playlistId = playlistId,
                streamId = "france24_en",
                name = "France 24 English HD",
                streamUrl = "https://static.france24.com/live/F24_EN_LO_HLS/live_tv.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=200",
                groupTitle = "News & Science",
                channelNumber = 4,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            ),

            ChannelEntity(
                playlistId = playlistId,
                streamId = "redbull_tv",
                name = "Red Bull TV Live Sports",
                streamUrl = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=200",
                groupTitle = "Sports & Action",
                channelNumber = 5,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)",
                isFavorite = true
            ),

            ChannelEntity(
                playlistId = playlistId,
                streamId = "dw_english",
                name = "DW News Global HD",
                streamUrl = "https://dwstream724-lh.akamaihd.net/i/dwtv_eng@191632/master.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=200",
                groupTitle = "News & Science",
                channelNumber = 6,
                httpUserAgent = "OTT Navigator/1.6.8.5 (Android TV; Android 11; OTT-Nav)"
            ),

            ChannelEntity(
                playlistId = playlistId,
                streamId = "aljazeera_en",
                name = "Al Jazeera English HD",
                streamUrl = "https://live-hls-web-aje.getaj.net/AJE/03.m3u8",
                logoUrl = "https://images.unsplash.com/photo-1495020689067-958852a7765e?w=200",
                groupTitle = "News & Science",
                channelNumber = 7,
                httpUserAgent = "VLC/3.5.4 (Linux; Android 12; LibVLC/3.5.4)"
            ),

            ChannelEntity(
                playlistId = playlistId,
                streamId = "bbb_trailer",
                name = "Tears of Steel 4K UHD",
                streamUrl = "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd",
                logoUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=200",
                groupTitle = "Movies & Cinema",
                channelNumber = 8,
                httpUserAgent = "TiviMate/5.0.4 (Linux; Android 12)"
            )
        )
    }
}
