# tvBuddy 📺

> **Open-Source Alternative to TiviMate & OTT Navigator**  
> Modern IPTV & VOD player designed for both **Android TV** and **Mobile & Tablets**, featuring hardware-accelerated **DRM Playback** (Widevine, ClearKey, PlayReady), multi-format playlist ingestion (M3U, Xtream Codes, Stalker MAC Portal, Raw JSON), and **Player Signature Spoofing**.

---

## ✨ Features

### 🔐 DRM Playback Support
- **Widevine Modular** (`com.widevine.alpha`) with license acquisition servers & custom authentication headers.
- **ClearKey DRM** (`org.w3.clearkey`) with hex `key_id:key` support and JSON Web Key endpoints.
- **Kodi DRM Tags** support inside M3U files:
  - `#KODIPROP:inputstream.adaptive.license_type=...`
  - `#KODIPROP:inputstream.adaptive.license_key=...`
  - `#KODIPROP:inputstream.adaptive.manifest_type=...`
- **Built-in DRM Debugger**: Test encrypted MPEG-DASH & HLS streams on the fly with live key/license parameter customization.

---

### 📡 Supported IPTV Formats
1. **M3U / M3U8 Playlists**:
   - Extended M3U parser supporting `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-chno`.
   - Pipe syntax header injection: `http://stream.m3u8|User-Agent=...&Referer=...`
   - VLC directives: `#EXTVLCOPT:http-user-agent=...`, `#EXTVLCOPT:http-referrer=...`
   - Support for URL or raw M3U text paste.
2. **Xtream Codes API**:
   - Connect via `Server URL`, `Username`, and `Password`.
   - Ingests Live streams, categories, logos, and EPG mapping via `player_api.php`.
3. **Stalker Portal / MAC Portal (MAG-style)**:
   - Handshake with custom or randomly generated MAC addresses (`00:1A:79:XX:XX:XX`).
   - Profile authentication, ITV genre retrieval, all-channel listing, and dynamic stream link creation.
4. **Raw JSON Playlists**:
   - Structured JSON format or array-based IPTV streams with custom headers and DRM metadata.

---

### 🎭 Player Signature Spoofing
Some IPTV providers block generic media players or enforce specific client signatures. **tvBuddy** includes a built-in spoofing engine that disguises all stream segments, manifests, and license requests:
- **TiviMate** (`TiviMate/5.0.4 Android TV`)
- **OTT Navigator** (`OTT Navigator/1.6.8.5 Android TV`)
- **VLC Media Player** (`LibVLC/3.5.4`)
- **Apple TV / CoreMedia** (`AppleCoreMedia/1.0.0`)
- **ExoPlayer Standard**
- **Custom User-Agent & Headers**

---

### 🖥️ Dual UI: Android TV & Mobile
- **Android TV Leanback Mode**:
  - TiviMate-inspired dark UI with cyan & indigo accents.
  - Remote control / D-Pad navigation:
    - `UP` / `DOWN`: Instant channel zapping.
    - `LEFT`: Opens quick channel guide overlay without stopping video playback.
    - `OK` / `ENTER`: Toggles on-screen display (OSD) controls.
  - Aspect ratio switcher (Fit, Fill/Stretch, Zoom, 4:3, 16:9).
  - Audio track and Subtitle / Closed Caption language picker.
  - Live DRM status & video resolution badges.
- **Mobile & Tablet Touch Mode**:
  - Modern Material Design 3 touch interface.
  - Categories horizontal filter pills.
  - Instant channel search & favorites list.
  - Mobile video player with touch gestures, fullscreen button, and track selectors.
  - Instant one-tap switch between Mobile and TV mode.

---

## 🚀 Building the APK with GitHub Actions

The repository includes a ready-to-run GitHub Actions workflow at [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

### Automatic Build:
1. Push your repository to GitHub.
2. Navigate to the **Actions** tab in your GitHub repository.
3. Select **"Build tvBuddy APK (Mobile & TV)"** and click **Run workflow**.
4. Once completed, download the built APK from the **Artifacts** section (`tvBuddy-Universal-Debug-APK`).
5. Pushing a tag (e.g. `v1.0.0`) automatically creates a GitHub Release with the APK attached.

### Local Build:
```bash
gradle :app:assembleDebug
```
The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🛠️ Architecture & Tech Stack
- **Language**: Kotlin 2.2.10
- **UI Framework**: Jetpack Compose (Material 3) + Android TV Leanback enhancements
- **Media Engine**: AndroidX Media3 (ExoPlayer 1.5.1) with HLS, DASH, RTSP, and OkHttp data sources
- **Local Persistence**: AndroidX Room Database (Playlists, Channels, Favorites, Playback History)
- **Image Loading**: Coil 2.7.0
- **Network**: OkHttp 4.10.0
