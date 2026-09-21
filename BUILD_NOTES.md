# Vibe IPTV — Build Notes

## Current release

- App name: **Vibe IPTV**
- Package: `com.vibeiptv.app`
- versionCode `1`, versionName `1.0`
- minSdk `26`, targetSdk `35`, compileSdk `35`
- APK: `~/workspace/your_files/VibeIPTV.apk` (~8.8 MB)
- Signed with `~/workspace/mytv_player/release.keystore`
  (alias `mytvplayer`, cert `CN=Vibe IPTV, OU=Mobile, O=Vibe IPTV`, valid 30y).
  Password is in `~/workspace/your_files/VibeIPTV-KEYSTORE-PASSWORD.txt`
  — never commit it; it is git-ignored.
- Keystore copy (no password inside): `~/workspace/your_files/VibeIPTV-release.keystore`

Verified 2026-09-21:
- `apksigner verify --print-certs` → signature valid, cert DN `CN=Vibe IPTV`
- `aapt dump badging` → package `com.vibeiptv.app`, label `Vibe IPTV`,
  `launchable-activity` (phone/tablet) + `leanback-launchable-activity` (TV) present

## How to rebuild

The sandbox has no direct internet for Gradle; all dependencies were
pre-fetched with `curl` (which works) into `~/workspace/m2repo`
(Maven Central + Google Maven, i.e. `https://dl.google.com/dl/android/maven2`).

```bash
cd ~/workspace/mytv_player
export JAVA_HOME=/home/hatch/tools/jdk17
export PATH=/home/hatch/tools/jdk17/bin:/home/hatch/tools/gradle-8.7/bin:$PATH
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
export KS_PW=$(grep -oP '(?<=Password: ).*' \
  ~/workspace/your_files/VibeIPTV-KEYSTORE-PASSWORD.txt | tr -d '\r\n')
gradle assembleRelease --no-daemon --console=plain
# never pipe through `tail` without preserving the exit status
cp app/build/outputs/apk/release/app-release.apk ~/workspace/your_files/VibeIPTV.apk
```

Key project files:
- `settings.gradle` — single `file:///home/hatch/workspace/m2repo` repo,
  `FAIL_ON_PROJECT_REPOS` (do NOT add `allprojects { repositories {} }`
  in `build.gradle`; it breaks the build).
- Root `build.gradle` — buildscript classpaths for AGP 8.5.2 and
  kotlin-gradle-plugin 2.0.21 (plugins DSL can't reach the Plugin Portal here).
- `/tmp/fetch_deps.py` — recursive Maven fetcher used to populate `~/workspace/m2repo`
  (handles BOM imports, `${project.*}` props, AndroidX `[x.y.z]` version ranges,
  Google Maven fallback). Re-run it if a build reports a missing module,
  adding the module as a SEED if needed.

## Build issues fixed (2026-09-21)

1. `FAIL_ON_PROJECT_REPOS` vs `allprojects.repositories` — removed the block.
2. Missing `androidx.databinding:viewbinding:8.5.2` (AGP-bundled) — fetched.
3. Missing KGP modules for kapt: `kotlin-build-tools-impl`,
   `kotlin-daemon-embeddable`, `kotlin-annotation-processing-gradle` — fetched.
4. Room: `@Query` returning `Set<String>` is unsupported — changed to `List<String>`.
5. `EpgRepository.windowForChannels`: suspend DAO calls inside `associate`/`Sequence.map`
   lambdas — rewrote as plain loops.
6. Media3 1.5.1 API corrections in `PlayerActivity`:
   `TrackSelectionOverride` is in `androidx.media3.common` (not `exoplayer.trackselection`);
   `MimeTypes.TEXT_SUBRIP` → `MimeTypes.APPLICATION_SUBRIP`;
   seek increments via `ExoPlayer.Builder.setSeekBackIncrementMs/setSeekForwardIncrementMs`.
7. `RecordService`: `startForeground` needs a `Notification` — added `.build()`.

## Features in this build

- Xtream login, live TV, movies, series, EPG, catch-up (provider-dependent)
- M3U playlist import + playback; XMLTV EPG with Room cache
- Multi-portal management, encrypted credentials, parental PIN + category locks
- Favorites, resume/watched state, search, sorting
- Media3 player: audio/subtitle track dialogs, aspect ratio, sleep timer,
  channel up/down, PiP hook, recording (direct streams; HLS shows
  "Recording not supported for HLS streams.")
- **External ratings (new):** TMDB / OMDb API keys in Settings → Ratings
  (encrypted storage). When the provider supplies no rating and a key is set,
  detail screens look up by title+year — TMDB first, IMDb (via OMDb) as fallback —
  and show e.g. `TMDB 7.5` / `IMDb 8.1`. Results and misses cached 30 days in
  Room (`rating_cache`, keyed title+year+type+source; DB v2).
- **Subtitle file loading (new):** player Subtitles dialog → "Load from device…"
  opens the system picker (ACTION_OPEN_DOCUMENT, persistable URI permission)
  for `.srt` / `.vtt` / `.ass` / `.ssa`; the content URI is added to the player
  as a subtitle track and auto-selected without losing position.
  Server-provided subtitles are untouched.

## Honest caveats

- No physical Android TV / D-pad / phone testing — needs Utkarsh's devices.
- Xtream payload/URL variations differ by provider; catch-up needs provider archive support.
- Movies/series unavailable for plain M3U portals. Recording = direct streams only.
- No streams, credentials, or playlists bundled — bring your own portal.
