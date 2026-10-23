<p align="center">
  <h1 align="center">Matchday</h1>
</p>

<p align="center">
  <b>Live TV and sports for Android TV</b><br>
  <sub>ESPN fixtures, scores and standings — matched against your own channels, one tap to play</sub>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Compose for TV" src="https://img.shields.io/badge/Jetpack%20Compose-for%20TV-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Player" src="https://img.shields.io/badge/engines-libmpv%20%2B%20ExoPlayer-FB8C00">
  <img alt="License" src="https://img.shields.io/badge/license-GPLv3-blue">
</p>

<p align="center">
  <a href="https://github.com/mkissarli/SportioTV/actions/workflows/android.yml">
    <img alt="Android CI" src="https://github.com/mkissarli/SportioTV/actions/workflows/android.yml/badge.svg">
  </a>
</p>

---

Matchday is a **player-first Android TV app for live TV and sports**, and a fork of
[SportioTV](https://github.com/trenntl/SportioTV). It keeps SportioTV's complete data and matching
layer — ESPN fixtures, scores, standings and tables for 81 leagues, matched against **your own live
channels** (names and guide text, both teams required) so one tap starts playback — and restyles the
presentation into the **Matchday Sports Hub**: a sport-coin sidebar, fixtures grouped by day and
league, and a Today-focused view.

It runs **two playback engines** — libmpv (FFmpeg) for the widest compatibility, ExoPlayer (Media3)
for near-instant Live TV — and it is a *player only*: you bring your own **Xtream** login, **M3U**
playlist (URL or a local file) or **Stalker/Ministra** portal.

> ⚠️ Matchday ships **no** channels, playlists, subscriptions or streams. You are responsible for the
> sources you add.

Open source, GPLv3. Forked from [SportioTV](https://github.com/trenntl/SportioTV), itself forked
from [OwnTV](https://github.com/ahXN00/OwnTV) — thanks for the shoulders.
**Android TV only** — leanback launcher, D-pad first.

---

## ✨ Features

### 🏟️ Sports Hub (Matchday style)
- **Sport-coin sidebar** — All, Football, Tennis, Basketball, Golf, Gridiron, Baseball, Hockey and
  more, each with a live-count badge; one click filters the whole board to that sport
- Fixtures grouped in **day bands → foldable league bands**, with time rail, team colours, logos,
  live chips and scorelines
- **Live / Today / Upcoming / Past pills**, a Favourites board (active leagues, followed teams and
  athletes) and an Add-leagues picker
- **Fixture → channel matching** across channel names and guide text, with a language filter
  (exact event channels always pass); single-sport views for tennis, Formula 1 and more
- **Manage leagues, teams and athletes from a phone** (QR companion page over Wi-Fi)

### 📡 Live TV
- **Two engines** — mpv for compatibility, ExoPlayer for instant start, with automatic fallback
- **4K HDR** direct rendering, auto frame rate, surround with stereo safety net, 150% volume boost
- Subtitles, closed captions and OpenSubtitles search; resume, mini-player, audio-only mode
- **TV Guide** (XMLTV) with live Now marker, catch-up up to 7 days, live rewind
- **Protected (DRM) channels** — Widevine and ClearKey, read from the playlist
- Custom **DNS**, per-playlist player and latency settings
- An **error log you can export** for any stream that misbehaves

### 🎨 Look & robustness
- **Dark and Light themes** across app and player
- Interface and popup font sizing, glass effect, your own accent colour
- **25 interface languages**, RTL-aware, chosen on the fresh-install welcome screen
- **Backup & Restore** to a single `.own` file, optionally encrypted, locally or over Wi-Fi
- Profiles with PIN locks, per-profile startup, favourites and history
- In-app updates; memory-safe lists, auto-reconnect and offline detection throughout

> The full feature reference lives in [docs/](docs/INDEX.md); end-user docs are in
> [extras/USER_GUIDE.md](extras/USER_GUIDE.md).

---

## 🧱 Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin 2.4.20 (no `kotlin-android` plugin) |
| Build | AGP 9.4.0 / Gradle 9.7.1, KSP2 2.3.11 |
| UI | Jetpack Compose for TV (`androidx.tv:tv-material` 1.1.0), Compose BOM 2026.08.00 |
| Media | **libmpv** (FFmpeg) · **ExoPlayer/Media3** 1.11.1 |
| Database | Room 2.8.5 + Paging 3.5.1 + FTS4 |
| DI · Network · Images | Koin 4.2.2 · OkHttp 5 · Coil 3.6.0 |

`minSdk 26`, `targetSdk 36`, `compileSdk 37`, `applicationId dev.matchday.tv`.

### Project layout

This repository is the **Android TV app**. Everything underneath it — database, sync, parsers, EPG,
backup, settings storage, the playback engines and every translated string — is a separate core
library, [**SportioTV_Core**](https://github.com/trenntl/SportioTV_Core), published as
`tv.own.owntv:core` / `:player-core`.

```
SportioTV/  (this repo)
tv.own.owntv/
├── player/      the TV player HUD, surfaces and mini-player
├── ui/          theme + reusable components
├── features/    setup, shell, live, sport, epg, profiles, settings
└── di/          Koin modules
```

## 📥 Installing (Fire TV / Android TV)

Grab the signed APK from the [**latest release**](https://github.com/mkissarli/SportioTV/releases/latest).
This link always points at the newest build:

```
https://github.com/mkissarli/SportioTV/releases/latest/download/Matchday.apk
```

- Sideload with *Send files to TV*, a USB drive, or `adb install Matchday.apk`. The URL above can
  also be typed into the *Downloader* app on Fire TV.
- Otherwise build from source (below).

> Install only from this repository's Releases. Each release ships **two APKs**:
> `Matchday.apk` (arm — every real TV box) and `Matchday-x86_64-vX.X.X.apk` (emulators and rare
> Intel boxes).

> Coming from the earlier Matchday app (v0.1.5 and below)? This fork continues the Matchday line on
> the SportioTV base, but under a **new signing key** — uninstall the old app once, then install
> this one. Your own playlists/sources are yours; re-add them here.

## 🛠️ Building & running

> Only if you want to build from source — otherwise just install the APK.

Gradle downloads the core library from Sportio's public Maven repository by itself — **no account
or token needed**.

1. **Get the code** — `git clone https://github.com/mkissarli/SportioTV.git` (or download the ZIP).
2. **Open it** in [Android Studio](https://developer.android.com/studio) and let Gradle sync.
3. **Pick the build variant** — `standard` for real devices and arm emulators, `x86_64` for x86_64
   emulators. This matters: the native player only loads on a matching ABI.
4. **Run** ▶. Minimum **Android 8.0 / API 26**.

Command line: `./gradlew assembleDebug` (`gradlew.bat` on Windows). The APK lands in
`app/build/outputs/apk/`.

## 🤝 Contributing

Bug reports and ideas are welcome — open an issue or a pull request. Please keep the project's
live-TV-and-sports positioning and match the existing code style. Agent rules live in
[AGENTS.md](AGENTS.md); the docs map is at [docs/INDEX.md](docs/INDEX.md).

<!-- i18n-contribution:start -->
## Help translate

Interface translations happen upstream on [Hosted Weblate](https://hosted.weblate.org/projects/owntv/)
across OwnTV's resource components. The strings themselves live in [OwnTV's core library
repository](https://github.com/ahXN00/OwnTV_Core), together with the language contributor guide
covering identifiers, validation, and promotion policy.
<!-- i18n-contribution:end -->

## 🙏 Credits

<img src="extras/partner-logos/Weblate_logo.svg" alt="Weblate" width="200">

Matchday speaks 25 languages through translations made on
[**Weblate**](https://weblate.org/), which hosts the project free of charge for libre software.
Thank you to Weblate and to every translator.

<img src="extras/partner-logos/tmdb_logo.svg" alt="TMDB" width="200">

Metadata and trailers are provided by [TMDB](https://www.themoviedb.org/).
**This product uses the TMDB API but is not endorsed or certified by TMDB.**

<img src="extras/partner-logos/opensubtitles_logo.svg" alt="OpenSubtitles" width="200">

Subtitle search & download is powered by [OpenSubtitles.com](https://www.opensubtitles.com/).
**This product uses the OpenSubtitles API but is not endorsed or certified by OpenSubtitles.** You sign
in with your own OpenSubtitles account and are subject to their terms and download quotas.

Forked from **[SportioTV](https://github.com/trenntl/SportioTV)** (by
[trenntl](https://github.com/trenntl)) — the Sports Hub, matching layer and branding fork of
**[OwnTV](https://github.com/ahXN00/OwnTV)** — database, sync, parsers, player engines and the
design system come from there. Core lives on as
**[SportioTV_Core](https://github.com/trenntl/SportioTV_Core)**.

### ▶️ Playback engines

- **[mpv](https://mpv.io/)** (via [libmpv](https://github.com/mpv-android/mpv-android), built on
  [FFmpeg](https://ffmpeg.org/)) — the default engine, for the widest IPTV/codec, audio and HDR support.
- **[Media3 / ExoPlayer](https://github.com/androidx/media)** — the fast-start engine for Live TV
  preview and HLS.

## ⚖️ Legal

Matchday is a media **player** only. It ships with no channels, playlists, subscriptions, or content,
and does not endorse or facilitate access to unauthorized streams. Users are solely responsible for
the sources they add and for complying with the laws and rights that apply to them.

## 📄 License

Released under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE).

---

<sub>Matchday — live TV and sports for Android TV. A fork of SportioTV, which forked OwnTV; built with the help of AI.</sub>
