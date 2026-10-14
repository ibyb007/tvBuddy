# tvBuddy 📺

> **Open-Source Alternative to TiviMate & OTT Navigator**  
> Modern IPTV & VOD player designed for both **Android TV** and **Mobile & Tablets**, featuring hardware-accelerated **DRM Playback** (Widevine, ClearKey, PlayReady), multi-format playlist ingestion (M3U, Xtream Codes, Stalker MAC Portal, Raw JSON), **XMLTV Electronic Program Guide (EPG)**, and **Player Signature Spoofing**.

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

### 📅 Electronic Program Guide (EPG)
- **XMLTV Standard Support**: Ingests program schedules in standard `.xml` or compressed `.xml.gz` formats.
- **Live Program Progress**: Shows currently airing program with real-time percentage progress bar right on channel list and player OSD.
- **Program Details Dialog**: Access complete synopsis, genre, duration, and upcoming schedule.
- **Channel EPG Guide View**: Full interactive timeline sheet showing current and upcoming shows per channel.

---

### 🎚️ Adaptive Video Quality Picker
- Switch between **Auto (Adaptive)**, **4K UHD (2160p)**, **Full HD (1080p)**, **HD (720p)**, **SD (480p)**, and **Data Saver (360p)**.
- Integrated into both Mobile player controls and Android TV OSD.

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
  - Video Quality selector (Auto, 4K, 1080p, 720p, etc.).
  - EPG current show display with progress bar.
  - Aspect ratio switcher (Fit, Fill/Stretch, Zoom, 4:3, 16:9).
  - Audio track and Subtitle / Closed Caption language picker.
  - Live DRM status & video resolution badges.
- **Mobile & Tablet Touch Mode**:
  - Modern Material Design 3 touch interface.
  - Categories horizontal filter pills.
  - Instant channel search & favorites list.
  - Mobile video player with touch gestures, fullscreen button, quality picker, and track selectors.
  - Instant one-tap switch between Mobile and TV mode.

---

## 🚀 Building the APK with GitHub Actions

The repository includes a ready-to-run GitHub Actions workflow at [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

### Automatic CI/CD Pipeline:
- **Triggers**: Every push to `main` branch, pull request, or manual dispatch.
- **Testing**: Runs unit tests (`gradle testDebugUnitTest`) before building.
- **Dual Builds**: Generates both Universal **Debug** and **Signed Release** APKs.
- **Artifacts**: Uploads `tvBuddy-Mobile-TV-Debug-APK` and `tvBuddy-Mobile-TV-Release-APK` artifacts.
- **Releases**: Pushing tags (e.g. `v1.0.0`) automatically publishes a GitHub Release with both APKs attached.

### Local Build:
```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
```
The APKs will be located at:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## 🛠️ Architecture & Tech Stack
- **Language**: Kotlin 2.2.10
- **UI Framework**: Jetpack Compose (Material 3) + Android TV Leanback enhancements
- **Media Engine**: AndroidX Media3 (ExoPlayer 1.5.1) with HLS, DASH, RTSP, and OkHttp data sources
- **EPG Engine**: XMLTV Parser (`.xml` & `.xml.gz` GZip streaming) + Room Database schedule cache
- **Local Persistence**: AndroidX Room Database (Playlists, Channels, Programs, Favorites, Playback History)
- **Image Loading**: Coil 2.7.0
- **Network**: OkHttp 4.10.0
