# Vibe IPTV

Vibe IPTV is a modern, open IPTV player for Android — built from scratch for
Android TV, TV boxes, phones, and tablets. One app, one APK: a 10-foot
leanback interface with full remote / D-pad navigation on TV, and the same
feature set with touch on phones and tablets.

Bring your own provider: connect any Xtream Codes (XC) portal, M3U playlist,
or XMLTV guide. Vibe IPTV ships with **no bundled streams, playlists, portals,
or credentials** — on first launch you add your own.

## Features

- **Multi-portal manager** — save multiple IPTV portals and switch between them
- **Xtream Codes API** — Live TV, Movies (VOD), Series, short EPG, catch-up
  (TV archive) where the provider supports it
- **M3U playlists + XMLTV guides** — import local or remote playlists with
  electronic program guide data
- **TV guide** — timeline grid, now/next indicators
- **Live TV** — categories, search, favorites, channel OSD (on-screen display)
  with now/next info while watching
- **Movies & Series** — details screens with plot, cast, ratings, artwork;
  sorting, subtitles, resume playback, watched state
- **Unified favorites** across Live TV, Movies, and Series
- **Recording** — direct recording of non-HLS streams, with playback and
  deletion management
- **Player** — ExoPlayer (Media3): audio/subtitle track selection, aspect
  ratio, sleep timer, picture-in-picture
- **Parental controls** — PIN lock with per-category locks
- **Settings** — EPG refresh controls, player preferences, portal management
- **Privacy** — portal credentials stored encrypted on-device (EncryptedSharedPreferences)

## Screenshots

_TODO: add screenshots_

## Requirements

- Android 8.0 (API 26) or higher — phones, tablets, Android TV / Google TV,
  and generic Android TV boxes
- For Android TV: install via sideload (APK) or your preferred method

## Building

```bash
# Android SDK with platform 35 and build-tools 35
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the developer's keystore (not included in this
repository). Debug builds work out of the box.

## Project structure

```
app/src/main/java/com/vibeiptv/app/
├── data/        # Room database, DAOs, entities, API clients (Xtream, M3U, XMLTV), repositories
├── di/          # Manual dependency injection
├── security/    # Encrypted credential storage
├── ui/          # Activities & fragments: home, portal setup, Live TV, EPG, VOD, series,
│                #   player, recordings, favorites, search, settings
├── playback/    # Media3/ExoPlayer service, PiP, sleep timer
├── recording/   # Direct-stream recorder
└── util/        # Helpers
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the module contract and coding rules.

## Disclaimer

Vibe IPTV is a player only. It does not provide, host, or include any content,
channels, or subscriptions. You must supply your own legally obtained IPTV
service or playlists. The developers are not responsible for third-party
content accessed through this app.

## License

_TODO: choose a license_
