# Vibe IPTV — Architecture Contract (READ FIRST)

App: **Vibe IPTV** · package **`com.vibeiptv.app`** · versionCode 1 / versionName "1.0"
100% original code. No third-party set-top-box trademarks anywhere in code, strings, or resources.
No bundled streams/playlists/portals/credentials. First run = portal setup.

## Global rules (ALL agents)
- Kotlin, XML layouts, **ViewBinding** (`buildFeatures.viewBinding = true`). NEVER `kotlinx.android.synthetic`.
- Every interactive view: `android:focusable="true"` + `android:background="@drawable/item_focusable"`
  (or `@drawable/btn_accent_focusable` for primary buttons). Focus highlight must be visible for d-pad/TV remotes AND touch must work.
- Theme: `@style/Theme.MyTVPlayer`. Colors: `@color/bg` #0B0F1A, `@color/surface` #151C2E,
  `@color/surface2` #1C2540, `@color/divider` #232C44, `@color/accent` #E63946,
  `@color/text_primary` white, `@color/text_secondary` #9AA3B2.
- Coroutines: `lifecycleScope.launch { }` in activities; `Dispatchers.IO` for network/DB.
- Image loading: Coil — `imageView.load(url) { crossfade(true); placeholder(...); error(...) }`.
- App name shown to user: **"Vibe IPTV"** (`@string/app_name`). Never write "MyTV Player".
- Base package for ALL Kotlin files: `com.vibeiptv.app`.
- Do NOT edit `res/values/*`, `AndroidManifest.xml`, `app/build.gradle`, or files owned by another agent.
  You MAY add new files under `res/layout/`, `res/drawable/` (new names only), `res/menu/` if needed.
- Do NOT add dependencies. Available: media3 (exoplayer, hls, ui) 1.5.1, okhttp 4.12.0,
  retrofit 2.11.0 + converter-gson, gson 2.10.1, coil 2.6.0, room 2.6.1 (kapt), coroutines 1.7.3,
  security-crypto 1.1.0-alpha06, appcompat 1.7.0, material 1.12.0, constraintlayout 2.1.4,
  recyclerview 1.3.2, lifecycle-runtime-ktx 2.8.7, core-ktx 1.13.1.

## Shared models — data/model/Models.kt (DATA agent creates; UI agents use EXACTLY these)
```kotlin
package com.vibeiptv.app.data.model
enum class PortalType { XTREAM, M3U }
data class PortalConfig(val id: String, val name: String, val type: PortalType,
    val serverUrl: String, val username: String = "", val password: String = "", val epgUrl: String = "")
data class Category(val id: String, val name: String, val count: Int = 0)
data class Channel(val id: String, val name: String, val number: Int, val logo: String?,
    val categoryId: String, val categoryName: String, val streamId: Int?,
    val epgChannelId: String?, val tvArchive: Boolean, val directSource: String? = null)
data class VodItem(val id: String, val streamId: Int, val name: String, val poster: String?,
    val backdrop: String?, val categoryId: String, val rating: Double, val addedEpoch: Long,
    val ext: String, val plot: String?, val director: String?, val cast: String?,
    val genre: String?, val year: String?, val duration: String?)
data class VodDetail(val item: VodItem, val subtitles: Map<String, String>)
data class SeriesItem(val id: String, val seriesId: Int, val name: String, val cover: String?,
    val backdrop: String?, val categoryId: String, val rating: Double, val plot: String?,
    val cast: String?, val genre: String?, val releaseDate: String?, val addedEpoch: Long)
data class Episode(val id: String, val episodeId: Int, val season: Int, val episodeNum: Int,
    val title: String, val plot: String?, val durationSecs: Long?, val airDate: String?, val ext: String)
data class SeriesDetail(val item: SeriesItem, val seasons: Map<Int, List<Episode>>)
data class EpgProgramme(val channelKey: String, val startUtc: Long, val stopUtc: Long,
    val title: String, val desc: String?)
object FavType { const val LIVE="live"; const val MOVIE="movie"; const val SERIES="series" }
```

## util/Json.kt (DATA agent)
```kotlin
package com.vibeiptv.app.util
object Json { val gson: Gson = Gson() }   // com.google.gson.Gson
```
UI agents: serialize with `Json.gson.toJson(obj)`, deserialize with `Json.gson.fromJson(s, X::class.java)`
using `TypeToken` for lists: `object : TypeToken<List<Channel>>() {}.type`.

## util/Format.kt (DATA agent) — helpers UI agents may use
```kotlin
package com.vibeiptv.app.util
object Format {
    fun timeHm(utc: Long): String            // "14:30" local time
    fun dateHm(utc: Long): String            // "21 Sep 14:30"
    fun durationHm(minsOrSecs: Long, isSecs: Boolean = false): String
    fun fileSize(bytes: Long): String
}
```

## data/db (DATA agent) — database name "vibeiptv.db"
- `FavoriteEntity(@PrimaryKey val refId: String, val type: String, val name: String, val logo: String?, val addedAt: Long)`
- `ResumeEntity(@PrimaryKey val contentId: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long)`
- `EpgProgrammeEntity(@PrimaryKey(autoGenerate=true) val uid: Long = 0, val channelKey: String, val startUtc: Long, val stopUtc: Long, val title: String, val desc: String?)`
- `EpgMetaEntity(@PrimaryKey val key: String, val value: String)`
- `FavoriteDao`: `@Insert(onConflict=REPLACE) suspend fun upsert(f)`, `@Query("DELETE FROM favorites WHERE refId=:id") suspend fun delete(id)`,
  `@Query("SELECT * FROM favorites WHERE type=:t ORDER BY addedAt DESC") suspend fun byType(t): List<FavoriteEntity>`,
  `@Query("SELECT refId FROM favorites WHERE type=:t") suspend fun idsByType(t): Set<String>`
- `ResumeDao`: upsert, `@Query("SELECT * FROM resume WHERE contentId=:id") suspend fun get(id): ResumeEntity?`,
  `@Query("DELETE FROM resume WHERE contentId=:id") suspend fun delete(id)`
- `EpgDao`: `@Insert suspend fun insertAll(list)`, `@Query("DELETE FROM epg_programmes") suspend fun clearAll()`,
  `@Query("SELECT * FROM epg_programmes WHERE channelKey=:k AND stopUtc>:from AND startUtc<:to ORDER BY startUtc") suspend fun window(k,from,to): List<EpgProgrammeEntity>`,
  `@Query("SELECT * FROM epg_programmes WHERE channelKey=:k AND startUtc<=:now AND stopUtc>:now LIMIT 1") suspend fun nowAt(k,now): EpgProgrammeEntity?`,
  `@Query("SELECT * FROM epg_programmes WHERE channelKey=:k AND startUtc>=:now ORDER BY startUtc LIMIT 1") suspend fun nextAfter(k,now): EpgProgrammeEntity?`,
  EpgMetaDao: `@Insert(REPLACE) suspend fun put(m)`, `@Query("SELECT value FROM epg_meta WHERE `key`=:k") suspend fun get(k): String?`
- `AppDatabase : RoomDatabase`, entities x4, `abstract fun favoriteDao()...`, companion `fun get(ctx): AppDatabase` (singleton, `Room.databaseBuilder(ctx, AppDatabase::class.java, "vibeiptv.db").fallbackToDestructiveMigration().build()`)

## data/repo/PortalStore.kt (DATA agent) — EncryptedSharedPreferences
File "vibe_secure" via EncryptedSharedPreferences (MasterKey AES256_GCM). Portals as JSON list under key "portals_json".
```kotlin
package com.vibeiptv.app.data.repo
class PortalStore(ctx: Context) {
    fun getPortals(): List<PortalConfig>
    fun savePortal(p: PortalConfig)          // upsert by id
    fun deletePortal(id: String)
    fun getActivePortal(): PortalConfig?
    fun setActivePortal(id: String)
    var decoderMode: String                 // "auto" | "software"  (plain prefs file "vibe_prefs")
    var bufferMs: Int                       // default 30000
    fun getPin(): String?                   // 4-digit or null (secure prefs)
    fun setPin(pin: String?)                // null clears
    fun getLockedCategoryIds(): Set<String>  // secure prefs, key "locked_cats"
    fun setLockedCategoryIds(ids: Set<String>)
}
```

## data/api/XtreamApi.kt (DATA agent) — Retrofit interface, baseUrl = portal.serverUrl.trimEnd('/') + "/"
```kotlin
package com.vibeiptv.app.data.api
interface XtreamApi {
    @GET("player_api.php") suspend fun login(@Query("username") u: String, @Query("password") p: String): LoginResponse
    @GET("player_api.php") suspend fun liveCategories(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_live_categories"): List<XtreamCategory>
    @GET("player_api.php") suspend fun liveStreams(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_live_streams"): List<XtreamLiveStream>
    @GET("player_api.php") suspend fun vodCategories(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_vod_categories"): List<XtreamCategory>
    @GET("player_api.php") suspend fun vodStreams(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_vod_streams"): List<XtreamVodStream>
    @GET("player_api.php") suspend fun vodInfo(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_vod_info", @Query("vod_id") id: Int): VodInfoResponse
    @GET("player_api.php") suspend fun seriesCategories(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_series_categories"): List<XtreamCategory>
    @GET("player_api.php") suspend fun seriesList(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_series"): List<XtreamSeries>
    @GET("player_api.php") suspend fun seriesInfo(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_series_info", @Query("series_id") id: Int): SeriesInfoResponse
    @GET("player_api.php") suspend fun shortEpg(@Query("username") u: String, @Query("password") p: String, @Query("action") a: String = "get_short_epg", @Query("stream_id") id: Int, @Query("limit") limit: Int = 4): ShortEpgResponse
    companion object { fun create(serverUrl: String): XtreamApi }  // Retrofit + GsonConverterFactory, 20s timeouts
}
```
DTOs (all fields nullable-safe, use @SerializedName):
- `LoginResponse(val user_info: UserInfo?, val server_info: ServerInfo?)`; `UserInfo(val auth: Int = 0, ...)`; `ServerInfo(val xui: String? = null, ...)`
- `XtreamCategory(val category_id: String, val category_name: String)` (category_id may be numeric in JSON — declare String and rely on Gson lenient? NO — declare as com.google.gson.JsonElement? Simpler: `val category_id: String` fails if number. Use custom: declare `val category_id: Any?` then toString. DECISION: use `String` won't parse ints. So: `data class XtreamCategory(@SerializedName("category_id") val categoryIdRaw: com.google.gson.JsonElement?, @SerializedName("category_name") val category_name: String?)` and expose `val id: String get() = categoryIdRaw?.asString ?: ""`. Keep simple: DTOs use JsonElement for ids and String for names, with computed vals.)
- `XtreamLiveStream`: stream_id (JsonElement), name, stream_icon, epg_channel_id, tv_archive (JsonElement; "1"/1/true => true), category_id (JsonElement), added (String epoch), tv_archive_duration.
- `XtreamVodStream`: stream_id, name, stream_icon, rating (String "8.5"), category_id, container_extension, added.
- `VodInfoResponse`: info: VodInfo? (movie_image, backdrop_path: List<String>?, plot, director, cast, genre, releaseDate, duration, rating), movie_data: MovieData? (stream_id, name, container_extension, added), subtitles: Map<String, SubtitleEntry>? where SubtitleEntry has "file" field. (Some servers return "movie_data" only; handle null info gracefully.)
- `XtreamSeries`: series_id, name, cover, backdrop_path (List<String>?), rating, category_id, plot, cast, genre, releaseDate, last_modified/added.
- `SeriesInfoResponse`: info: SeriesInfo? (name, cover, plot, cast, genre, releaseDate, rating, backdrop_path), episodes: Map<String, List<SeriesEpisode>>? (keys = season numbers), seasons: List<Any>? (ignore).
- `SeriesEpisode`: id (JsonElement), episode_num (JsonElement), title, plot, duration (String secs), added, container_extension, info: EpisodeInfo? (plot, duration, release_date/movie_image).
- `ShortEpgResponse`: epg_listings: List<EpgListing>?; `EpgListing`: title (base64!), description (base64!), start (String "2026-09-21 14:00:00"), stop, start_timestamp, stop_timestamp. NOTE: Xtream returns title/desc base64-encoded — decode with android.util.Base64.

## data/api/M3uParser.kt (DATA agent)
```kotlin
package com.vibeiptv.app.data.api
data class M3uEntry(val name: String, val tvgId: String?, val tvgLogo: String?, val groupTitle: String?, val url: String)
object M3uParser { fun parse(text: String): List<M3uEntry> }
```
Parse `#EXTINF:-1 tvg-id="x" tvg-logo="y" group-title="Sports",Channel Name` then next non-empty non-# line = URL.
Attribute regex: `([a-zA-Z0-9_-]+)="([^"]*)"`. Display name = text after last comma. Skip `#EXTM3U` header.

## data/api/EpgParser.kt (DATA agent) — XmlPullParser over xmltv
```kotlin
package com.vibeiptv.app.data.api
data class EpgChannel(val id: String, val name: String, val logo: String?)
object EpgParser {
    data class Result(val channels: List<EpgChannel>, val programmes: List<EpgProgramme>)
    fun parse(input: InputStream): Result
}
```
- `<channel id="..">` → display-name, icon src.
- `<programme start="20260921140000 +0000" stop="..." channel="...">` → title, desc. Date fmt: `yyyyMMddHHmmss Z` (SimpleDateFormat, Locale.US); fallback without zone.
- channelKey for programmes = programme's channel attribute.

## data/repo/ContentRepository.kt (DATA agent) — ONE class, branches on portal.type
```kotlin
package com.vibeiptv.app.data.repo
class ContentRepository(private val ctx: Context) {
    val portalStore = PortalStore(ctx)
    private fun api(): XtreamApi  // throws IllegalStateException if no active portal or not XTREAM
    private fun active(): PortalConfig // throws if none
    suspend fun login(portal: PortalConfig): Boolean      // GET player_api.php, user_info.auth==1
    suspend fun liveCategories(): List<Category>          // XTREAM: get_live_categories (+count via streams); M3U: group-titles (+count)
    suspend fun liveChannels(categoryId: String?): List<Channel>  // null/"all" => all; else filter; numbers assigned 1..N in returned order
    fun liveStreamUrl(ch: Channel): String                // XTREAM: {srv}/live/{u}/{p}/{streamId}.m3u8 ; M3U: ch.directSource!!
    suspend fun shortEpg(ch: Channel, limit: Int = 4): List<EpgProgramme>  // XTREAM only; M3U => emptyList()
    suspend fun vodCategories(): List<Category>           // XTREAM only; M3U => emptyList()
    suspend fun vodList(categoryId: String?): List<VodItem>
    suspend fun vodDetail(vod: VodItem): VodDetail
    fun vodStreamUrl(vod: VodItem): String                // {srv}/movie/{u}/{p}/{streamId}.{ext}
    suspend fun seriesCategories(): List<Category>
    suspend fun seriesList(categoryId: String?): List<SeriesItem>
    suspend fun seriesDetail(s: SeriesItem): SeriesDetail
    fun episodeStreamUrl(ep: Episode): String             // {srv}/series/{u}/{p}/{episodeId}.{ext}
    fun timeshiftUrl(ch: Channel, prog: EpgProgramme, durationMin: Int): String?
        // XTREAM & ch.tvArchive: {srv}/timeshift/{u}/{p}/{durationMin}/{utcStart}/{streamId}.m3u8  (utcStart = prog.startUtc/1000); else null
    fun epgXmlUrl(): String?                              // XTREAM: {srv}/xmltv.php?username={u}&password={p} ; M3U: portal.epgUrl.ifBlank{null}
    // In-memory caches for M3U entries (parsed once per portal) and Xtream lists where sensible.
}
```
URL-encode username/password with URLEncoder when building stream URLs.

## data/repo/EpgRepository.kt (DATA agent)
```kotlin
package com.vibeiptv.app.data.repo
class EpgRepository(private val ctx: Context) {
    private val content = ContentRepository(ctx)
    private val db = AppDatabase.get(ctx)
    suspend fun refreshIfNeeded(force: Boolean = false)  // skip if last refresh < 24h unless force; download epgXmlUrl() via OkHttp; parse; store programmes with channelKey mapping:
        // For each programme: key candidates = programme.channel attr. Store under the raw channel attr AND, when resolvable, under matching Channel.epgChannelId / Channel.id.
        // Simplest robust approach: store programme once with key = programme.channel (raw xmltv id). Callers look up by raw id first, then fall back.
    suspend fun clearCache()
    suspend fun lastUpdated(): Long                       // EpgMetaDao "last_updated" epoch ms, 0 if never
    suspend fun nowNext(rawChannelKey: String): Pair<EpgProgramme?, EpgProgramme?>  // by raw key; nulls if none
    suspend fun programmesForWindow(rawKeys: List<String>, fromUtc: Long, toUtc: Long): Map<String, List<EpgProgramme>>
}
```
Channel→EPG key resolution helper (DATA agent, in EpgRepository companion or util):
`fun epgKeysFor(ch: Channel): List<String>` = listOfNotNull(ch.epgChannelId, ch.id). UI agents call EpgRepository methods that try keys in order — so expose:
`suspend fun nowNextForChannel(ch: Channel): Pair<EpgProgramme?, EpgProgramme?>` and
`suspend fun windowForChannels(channels: List<Channel>, from, to): Map<String /*channel.id*/, List<EpgProgramme>>`.
(DATA agent implements the fallback inside.)

## MyTvApp.kt (DATA agent)
```kotlin
package com.vibeiptv.app
class MyTvApp : Application() { override fun onCreate() { super.onCreate(); AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) } }
```
Manifest already references `.MyTvApp`.

## util/ParentalGate.kt (UI-A agent creates; UI-B uses)
```kotlin
package com.vibeiptv.app.util
object ParentalGate {
    // If portalStore.getPin() != null && categoryId in locked set -> show PIN dialog; on success run onUnlocked.
    fun check(activity: AppCompatActivity, categoryId: String, onUnlocked: () -> Unit)
}
```
PIN dialog: AlertDialog with EditText (inputType numberPassword, maxLength 4).

## Player contract — util/PlayerContract.kt (UI-A agent creates; UI-B uses to LAUNCH)
```kotlin
package com.vibeiptv.app.util
object PlayerContract {
    const val EXTRA_MODE="mode"; const val MODE_LIVE="live"; const val MODE_VOD="vod"; const val MODE_EPISODE="episode"; const val MODE_FILE="file"
    const val EXTRA_CHANNEL_JSON="channel_json"; const val EXTRA_CHANNEL_LIST_JSON="channel_list_json"; const val EXTRA_INDEX="index"
    const val EXTRA_VOD_JSON="vod_json"; const val EXTRA_SUBS_JSON="subs_json"
    const val EXTRA_EPISODE_JSON="episode_json"
    const val EXTRA_FILE_URI="file_uri"; const val EXTRA_TITLE="title"
    const val EXTRA_RESUME_MS="resume_ms"; const val EXTRA_CONTENT_ID="content_id"
    fun playLive(a: Activity, channels: List<Channel>, index: Int)
    fun playVod(a: Activity, detail: VodDetail, resumeMs: Long = 0)
    fun playEpisode(a: Activity, series: SeriesItem, ep: Episode, resumeMs: Long = 0)
    fun playFile(a: Activity, uri: Uri, title: String)
    // contentId helpers:
    fun vodContentId(v: VodItem) = "vod:${v.streamId}"
    fun episodeContentId(e: Episode) = "ep:${e.episodeId}"
    fun isWatched(resume: ResumeEntity?): Boolean  // resume != null && durationMs>0 && positionMs >= durationMs*0.9
}
```
ResumeEntity is in data.db — PlayerContract imports it (fine).

## UI-A agent owns: ui/portal, ui/home, ui/live, ui/player (+ util/ParentalGate.kt, util/PlayerContract.kt)
### ui/portal/PortalSetupActivity.kt
- If `PortalStore(this).getActivePortal() != null` → start HomeActivity, finish.
- Else (or when launched with EXTRA_EDIT_ID for editing): form with type toggle (Xtream Codes / M3U Playlist):
  - Xtream: name, server URL (hint "http://host:port"), username, password.
  - M3U: name, playlist URL, optional EPG URL.
- "Save & Connect": validate non-empty; for XTREAM call `ContentRepository(this).login(portal)` (IO, progress) require true else error toast "Login failed — check details"; for M3U download first 64KB and require "#EXTM3U" present. Then `portalStore.savePortal`, `setActivePortal`, go Home.
- Cancel button when editing (back to PortalManager).
### ui/portal/PortalManagerActivity.kt
- List portals (name, type, server), actions per row: Switch (radio), Edit, Delete (confirm; if deleting active → clear active and go to PortalSetup). "Add portal" button → PortalSetup (new). Back → Settings or Home.
### ui/home/HomeActivity.kt
- Top bar: "Vibe IPTV" title, active portal name, live clock (HH:mm, Handler 30s), settings icon button → SettingsActivity.
- Grid (RecyclerView, GridLayoutManager 3 cols portrait / 4 landscape): tiles Live TV 📺, Movies 🎬, Series 📼, TV Guide 📅, Favorites ⭐, Recordings ⏺, Settings ⚙. Tile = icon TextView + label, background item_focusable, focusable.
- Back press on Home with no portals? PortalSetup handles. Home onResume refresh portal name/clock.
- If no active portal (e.g. deleted) → go PortalSetup.
### ui/live/LiveTvActivity.kt
- Layout: left pane categories RecyclerView (width ~300dp), right: search EditText + channels RecyclerView.
- Categories: "All Channels", "Favorites ⭐", then repo.liveCategories() with counts. Locked categories (ParentalGate) show 🔒; selecting → PIN dialog → on unlock show.
- Channel row: logo (Coil, 64dp), number, name, now/next line: `EpgRepository(this).nowNextForChannel(ch)` → "Now: X • Next: Y" or "No EPG info". Load EPG lazily per bind is heavy — instead: after channels load, launch one coroutine that fetches nowNext for visible channels? SIMPLE: fetch nowNext inside bind via lifecycleScope (cached map in activity to avoid refetch). Acceptable.
- Click row → PlayerContract.playLive(this, filteredChannels, position). Long-press → toggle favorite (FavoriteDao, FavType.LIVE, refId=ch.id) + toast + refresh star indicator. Also a star ImageButton in row? Keep long-press + show ⭐ prefix in name if favorited.
- Search: TextWatcher filters by name.
### ui/player/PlayerActivity.kt — fullscreen custom OSD, NO PlayerView default controller (app:use_controller="false")
- Build ExoPlayer: decoderMode from PortalStore ("software" → DefaultRenderersFactory.setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)), bufferMs → DefaultLoadControl.Builder().setBufferDurationsMs(bufferMs, bufferMs*2, 1500, 3000).
- Media source: url endsWith ".m3u8" → HlsMediaSource else ProgressiveMediaSource. VOD/EPISODE: add sidecar subtitles from subs map (SubTitleConfiguration per entry: url end vtt→MimeTypes.TEXT_VTT else TEXT_SUBRIP, label = map key).
- Layout: PlayerView (match_parent) + top info banner (channel/poster title, now/next or VOD meta) + bottom control bar: btnPlayPause ⏯, btnRew "-10s", btnFwd "+10s", btnChUp "CH +", btnChDown "CH −" (VISIBLE only MODE_LIVE), btnRecord ⏺ (VISIBLE only MODE_LIVE && !url.contains(".m3u8")), btnAspect "16:9", btnAudio "AUD", btnSubs "CC", btnSleep "⏾", btnInfo "ⓘ". OSD auto-hide 4s after interaction (Handler); any key/touch shows OSD.
- Info banner content: live → channel name + number + now/next with desc; vod/episode → title + meta. Toggle via btnInfo.
- Track dialogs: AlertDialog single-choice from player.currentTracks.groups → audio: list group trackFormats with labels; subs: "Off" + subtitle tracks. Apply via trackSelector.setParameters.
- Aspect: cycle PlayerView.RESIZE_MODE_FIT → FILL → ZOOM, toast label.
- Sleep timer: dialog Off/15/30/60/90 → Handler stop player after delay; toast confirm.
- Record: MODE_LIVE && non-HLS → start RecordService with url + suggested name; toast "Recording started". If HLS the button is GONE (spec: hide/disable + toast only if somehow pressed).
- Live CH+/−: new index = (index±1) mod size; rebuild player with channels[newIndex]; update banner. Persist current index in field.
- PiP: onUserLeaveHint → if playing → enterPictureInPictureMode(PictureInPictureParams.Builder().build()).
- Resume: MODE_VOD/EPISODE: seek to EXTRA_RESUME_MS on ready (once); onPause/onStop/onDestroy → save ResumeEntity(contentId, position, duration). Mark watched is derived (>90%).
- Error: Player.Listener onPlayerError → toast message; keep OSD visible.
- Back: finish().

## UI-B agent owns: ui/guide, ui/vod, ui/series, ui/favorites, ui/recordings, ui/settings
### ui/guide/GuideActivity.kt
- 2-hour window [now-30min, now+90min]. Header: "TV Guide", date, portal name, "Now" button (scrolls/resets to now).
- Body: vertical RecyclerView rows (max ~80 channels from repo.liveChannels(null)). Row layout: left fixed cell (logo 48dp, name, number; width 180dp) + HorizontalScrollView → LinearLayout of programme blocks (TextView, width = durationMin * 6dp, min 48dp; focusable item_focusable; text = time + title, 2 lines max).
- Timeline header above rows: 30-min labels across same 2h scale (simple LinearLayout with TextViews sized 30*6=180dp each).
- Now-line: skip precise overlay (hard in RecyclerView) — instead highlight the "now" programme block with accent border: if prog.startUtc<=now<stopUtc use @drawable/bg_now_program (create: accent-tinted). This satisfies "now indicator" honestly; note in BUILD_NOTES.
- OK/click on block: live → playLive(channelsOfRow, 0)? Need channel object — keep channel per row; play single: PlayerContract.playLive(activity, listOf(ch), 0). past → if ch.tvArchive && timeshiftUrl != null → playFile? timeshift is .m3u8 → use MODE_LIVE? Catch-up is a VOD-like m3u8 — launch with MODE_FILE? MODE_FILE builds progressive... FIX: PlayerActivity must treat .m3u8 as HLS regardless of mode. So play via MODE_FILE with url → but contract playFile takes Uri. Add `PlayerContract.playUrl(a, url, title)` building MODE_FILE with EXTRA_FILE_URI=url string. (UI-A: implement playUrl; MODE_FILE: if uri string endsWith .m3u8 → HlsMediaSource.) past w/o archive → toast "No catch-up available". future → details AlertDialog (title, time, desc).
- d-pad: blocks focusable; rows scroll.
### ui/vod/MoviesActivity.kt + ui/vod/VodDetailActivity.kt
- MoviesActivity: left categories (All + repo.vodCategories()), right GridLayout (3 cols) posters (Coil, 2:3). Sort: dialog (Recently added/Name/Rating) via menu button "Sort". Search EditText. Empty state text if M3U portal ("Movies unavailable for M3U playlists").
- VodDetailActivity: backdrop (Coil), poster, title, year • genre • duration • rating, plot, director, cast; buttons: Play (accent, focused by default), "▶ Resume from 12:34" if resume exists, Favorite toggle ☆/★. Play → repo.vodDetail → PlayerContract.playVod.
### ui/series/SeriesActivity.kt + ui/series/SeriesDetailActivity.kt
- SeriesActivity: categories left + grid (like Movies). Empty state for M3U.
- SeriesDetailActivity: cover/backdrop header, title, meta, plot; season selector = horizontal RecyclerView buttons "S1.."; episode list rows: "E3 · Title", plot (2 lines), airdate • duration, watched badge "✓" (accent) if isWatched(resume). Click episode → resume lookup → playEpisode. Favorite toggle.
### ui/favorites/FavoritesActivity.kt
- Three tab buttons (Live/Movies/Series) switching one RecyclerView. Rows show name/logo + "Remove" via long-press (confirm dialog). Click: live → need Channel object — favorites store only refId/name/logo. For live: look up channel in repo.liveChannels(null) by id; if missing toast. Movies: vodList lookup by id; Series: seriesList lookup by id. (Cache lists in memory for the session.)
### ui/recordings/RecordingsActivity.kt + ui/recordings/RecordService.kt
- RecordService: foreground service; intent extras "url", "filename". Notification channel "recordings". Downloads via OkHttp (30s timeouts) streaming to `getExternalFilesDir(Environment.DIRECTORY_MOVIES)/Recordings/<filename>.ts`. On complete → stopSelf, toast via broadcast? Keep simple: notification "Recording saved". STOP action extra stops.
- Code comment: honest note that HLS (.m3u8 segmented) recording is not supported — button hidden in player for HLS.
- RecordingsActivity: list files (name, size via Format.fileSize, date), click → PlayerContract.playFile(uri=FileProvider? NO — use Uri.fromFile? For app-private external files dir, `Uri.fromFile` works with ExoPlayer. Use Uri.fromFile.), long-press → delete (confirm).
- Manifest already has service with foregroundServiceType dataSync. POST_NOTIFICATIONS runtime permission: request in RecordingsActivity/ActivityResultLauncher if API 33+.
### ui/settings/SettingsActivity.kt — sections (single RecyclerView or LinearLayout of rows; use simple vertical LinearLayout with section headers):
- Portal: active portal name + buttons "Manage portals" → PortalManagerActivity.
- Player: "Decoder mode" → dialog (Auto/Software) → portalStore.decoderMode; "Buffer size" → dialog (15s/30s/60s) → bufferMs.
- EPG: "Refresh EPG now" → EpgRepository.refreshIfNeeded(force=true) with progress; "Clear EPG cache"; "Last updated: ..." text.
- Parental: "Set/Change PIN" → dialog (4-digit, confirm); "Remove PIN" (confirm); "Locked categories" → multi-choice dialog of live category names → setLockedCategoryIds. (Applies to Live TV categories; note in UI.)
- About: "Vibe IPTV v1.0" + "Original software. Bring your own portal — no streams included." + package name.

## Networking details
- OkHttpClient shared: `object Http { val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20,SECONDS).readTimeout(30,SECONDS).build() }` (DATA agent, data/api/Http.kt).
- Xtream base URLs: serverUrl may include path — Retrofit baseUrl must end with "/". `Retrofit.Builder().baseUrl(portal.serverUrl.trimEnd('/') + "/").addConverterFactory(GsonConverterFactory.create()).client(Http.client).build()`.
- Xtream JSON sometimes returns `[]` instead of `{}` for empty objects (PHP quirk). Gson will throw on VodInfoResponse if "info":[] — mitigate: register a lenient TypeAdapter? SIMPLEST: in ContentRepository wrap API calls in try/catch and treat failures as empty. Also for Map fields Gson handles []→Map? No, it throws. Accept the limitation; catch exceptions per-call and return empty/defaults. (Note in BUILD_NOTES.)

## EPG refresh trigger
- HomeActivity.onCreate: `lifecycleScope.launch(Dispatchers.IO) { EpgRepository(this@HomeActivity).refreshIfNeeded() }` (UI-A adds this one line to Home).
