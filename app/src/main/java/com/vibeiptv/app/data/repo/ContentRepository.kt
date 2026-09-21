package com.vibeiptv.app.data.repo

import android.content.Context
import com.vibeiptv.app.data.api.Http
import com.vibeiptv.app.data.api.M3uEntry
import com.vibeiptv.app.data.api.M3uParser
import com.vibeiptv.app.data.api.XtreamApi
import com.vibeiptv.app.data.api.XtreamLiveStream
import com.vibeiptv.app.data.api.XtreamSeries
import com.vibeiptv.app.data.api.XtreamVodStream
import com.vibeiptv.app.data.model.Category
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.EpgProgramme
import com.vibeiptv.app.data.model.PortalConfig
import com.vibeiptv.app.data.model.PortalType
import com.vibeiptv.app.data.model.SeriesDetail
import com.vibeiptv.app.data.model.SeriesItem
import com.vibeiptv.app.data.model.VodDetail
import com.vibeiptv.app.data.model.VodItem
import com.vibeiptv.app.data.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Single entry point for portal content. Branches on [PortalType]:
 * XTREAM portals go through the Xtream Codes player_api; M3U portals use the
 * downloaded playlist (parsed once per portal, cached in memory).
 */
class ContentRepository(private val ctx: Context) {

    val portalStore = PortalStore(ctx)

    // ---------- portal / api plumbing ----------

    private fun active(): PortalConfig =
        portalStore.getActivePortal() ?: throw IllegalStateException("No active portal")

    private fun api(): XtreamApi {
        val p = active()
        if (p.type != PortalType.XTREAM) throw IllegalStateException("Not an Xtream portal")
        return XtreamApi.create(p.serverUrl)
    }

    private fun srv(): String = active().serverUrl.trimEnd('/')

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /** Xtream "added"/"last_modified" are epoch seconds; normalize to millis. */
    private fun epochToMs(s: String?): Long {
        val v = s?.toLongOrNull() ?: return 0L
        return if (v in 1 until 100_000_000_000L) v * 1000 else v
    }

    // ---------- in-memory caches ----------

    private val m3uCache = mutableMapOf<String, List<M3uEntry>>()
    private val xtreamLiveCache = mutableMapOf<String, List<XtreamLiveStream>>()
    private val xtreamVodCache = mutableMapOf<String, List<XtreamVodStream>>()
    private val xtreamSeriesCache = mutableMapOf<String, List<XtreamSeries>>()

    private suspend fun m3uEntries(portal: PortalConfig = active()): List<M3uEntry> =
        withContext(Dispatchers.IO) {
            val cached = m3uCache[portal.id]
            if (cached != null) return@withContext cached
            val entries = try {
                val req = Request.Builder().url(portal.serverUrl).build()
                Http.client.newCall(req).execute().use { resp ->
                    M3uParser.parse(resp.body?.string().orEmpty())
                }
            } catch (_: Exception) {
                emptyList()
            }
            m3uCache[portal.id] = entries
            entries
        }

    private suspend fun xtreamLiveStreams(): List<XtreamLiveStream> = withContext(Dispatchers.IO) {
        val p = active()
        xtreamLiveCache[p.id] ?: try {
            api().liveStreams(p.username, p.password).also { xtreamLiveCache[p.id] = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun xtreamVodStreams(): List<XtreamVodStream> = withContext(Dispatchers.IO) {
        val p = active()
        xtreamVodCache[p.id] ?: try {
            api().vodStreams(p.username, p.password).also { xtreamVodCache[p.id] = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun xtreamSeriesList(): List<XtreamSeries> = withContext(Dispatchers.IO) {
        val p = active()
        xtreamSeriesCache[p.id] ?: try {
            api().seriesList(p.username, p.password).also { xtreamSeriesCache[p.id] = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- auth ----------

    suspend fun login(portal: PortalConfig): Boolean = withContext(Dispatchers.IO) {
        if (portal.type != PortalType.XTREAM) return@withContext false
        try {
            XtreamApi.create(portal.serverUrl)
                .login(portal.username, portal.password)
                .userInfo?.auth == 1
        } catch (_: Exception) {
            false
        }
    }

    // ---------- live ----------

    suspend fun liveCategories(): List<Category> = withContext(Dispatchers.IO) {
        val p = active()
        try {
            if (p.type == PortalType.M3U) {
                m3uEntries(p)
                    .groupBy { it.groupTitle?.ifBlank { null } ?: "Uncategorized" }
                    .map { (g, list) -> Category(id = g, name = g, count = list.size) }
                    .sortedBy { it.name.lowercase() }
            } else {
                val cats = api().liveCategories(p.username, p.password)
                val counts = xtreamLiveStreams()
                    .groupBy { it.categoryId.ifBlank { "Uncategorized" } }
                    .mapValues { it.value.size }
                cats.map { c ->
                    val id = c.id.ifBlank { "Uncategorized" }
                    Category(id = id, name = c.name.ifBlank { id }, count = counts[id] ?: 0)
                }.sortedBy { it.name.lowercase() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun liveChannels(categoryId: String?): List<Channel> = withContext(Dispatchers.IO) {
        val p = active()
        try {
            if (p.type == PortalType.M3U) {
                val catNames = liveCategories().associate { it.id to it.name }
                m3uEntries(p)
                    .mapIndexedNotNull { idx, e ->
                        val group = e.groupTitle?.ifBlank { null } ?: "Uncategorized"
                        if (categoryId != null && categoryId != "all" && group != categoryId) {
                            return@mapIndexedNotNull null
                        }
                        Channel(
                            id = "m3u:$idx",
                            name = e.name,
                            number = 0,
                            logo = e.tvgLogo,
                            categoryId = group,
                            categoryName = catNames[group] ?: group,
                            streamId = null,
                            epgChannelId = e.tvgId,
                            tvArchive = false,
                            directSource = e.url
                        )
                    }
                    .mapIndexed { i, c -> c.copy(number = i + 1) }
            } else {
                val catNames = liveCategories().associate { it.id to it.name }
                xtreamLiveStreams()
                    .mapIndexedNotNull { _, s ->
                        val catId = s.categoryId.ifBlank { "Uncategorized" }
                        if (categoryId != null && categoryId != "all" && catId != categoryId) {
                            return@mapIndexedNotNull null
                        }
                        Channel(
                            id = "xtream:${s.streamId}",
                            name = s.name?.ifBlank { "Channel ${s.streamId}" }
                                ?: "Channel ${s.streamId}",
                            number = 0,
                            logo = s.streamIcon?.ifBlank { null },
                            categoryId = catId,
                            categoryName = catNames[catId] ?: catId,
                            streamId = s.streamId,
                            epgChannelId = s.epgChannelId?.ifBlank { null },
                            tvArchive = s.tvArchive,
                            directSource = null
                        )
                    }
                    .mapIndexed { i, c -> c.copy(number = i + 1) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun liveStreamUrl(ch: Channel): String {
        val p = active()
        return if (p.type == PortalType.M3U) {
            ch.directSource ?: throw IllegalStateException("No source for channel")
        } else {
            "${srv()}/live/${enc(p.username)}/${enc(p.password)}/${ch.streamId}.m3u8"
        }
    }

    suspend fun shortEpg(ch: Channel, limit: Int = 4): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            val p = active()
            if (p.type != PortalType.XTREAM) return@withContext emptyList()
            val sid = ch.streamId ?: return@withContext emptyList()
            try {
                val resp = api().shortEpg(p.username, p.password, id = sid, limit = limit)
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                (resp.epgListings ?: emptyList()).mapNotNull { l ->
                    val start = try {
                        fmt.parse(l.start ?: "")?.time
                    } catch (_: Exception) {
                        null
                    } ?: return@mapNotNull null
                    val stop = try {
                        fmt.parse(l.stop ?: "")?.time
                    } catch (_: Exception) {
                        null
                    } ?: return@mapNotNull null
                    EpgProgramme(
                        channelKey = ch.epgChannelId ?: ch.id,
                        startUtc = start,
                        stopUtc = stop,
                        title = l.decodedTitle,
                        desc = l.decodedDescription.ifBlank { null }
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    // ---------- vod ----------

    suspend fun vodCategories(): List<Category> = withContext(Dispatchers.IO) {
        val p = active()
        if (p.type != PortalType.XTREAM) return@withContext emptyList()
        try {
            val cats = api().vodCategories(p.username, p.password)
            val counts = xtreamVodStreams()
                .groupBy { it.categoryId.ifBlank { "Uncategorized" } }
                .mapValues { it.value.size }
            cats.map { c ->
                val id = c.id.ifBlank { "Uncategorized" }
                Category(id = id, name = c.name.ifBlank { id }, count = counts[id] ?: 0)
            }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun vodList(categoryId: String?): List<VodItem> = withContext(Dispatchers.IO) {
        val p = active()
        if (p.type != PortalType.XTREAM) return@withContext emptyList()
        try {
            xtreamVodStreams().mapNotNull { s ->
                val catId = s.categoryId.ifBlank { "Uncategorized" }
                if (categoryId != null && categoryId != "all" && catId != categoryId) {
                    return@mapNotNull null
                }
                VodItem(
                    id = "vod:${s.streamId}",
                    streamId = s.streamId,
                    name = s.name?.ifBlank { "Movie ${s.streamId}" } ?: "Movie ${s.streamId}",
                    poster = s.streamIcon?.ifBlank { null },
                    backdrop = null,
                    categoryId = catId,
                    rating = s.ratingValue,
                    addedEpoch = epochToMs(s.added),
                    ext = s.containerExtension?.ifBlank { "mp4" } ?: "mp4",
                    plot = null,
                    director = null,
                    cast = null,
                    genre = null,
                    year = null,
                    duration = null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun vodDetail(vod: VodItem): VodDetail = withContext(Dispatchers.IO) {
        val p = active()
        if (p.type != PortalType.XTREAM) throw IllegalStateException("VOD is not supported for M3U portals")
        try {
            val r = api().vodInfo(p.username, p.password, id = vod.streamId)
            val info = r.info
            val md = r.movieData
            val item = vod.copy(
                poster = info?.movieImage?.ifBlank { null } ?: vod.poster,
                backdrop = info?.backdropPath?.firstOrNull()?.ifBlank { null },
                plot = info?.plot?.ifBlank { null },
                director = info?.director?.ifBlank { null },
                cast = info?.cast?.ifBlank { null },
                genre = info?.genre?.ifBlank { null },
                year = info?.releaseDate?.ifBlank { null },
                duration = info?.duration?.ifBlank { null },
                rating = info?.rating?.toDoubleOrNull() ?: vod.rating,
                ext = md?.containerExtension?.ifBlank { null } ?: vod.ext,
                addedEpoch = epochToMs(md?.added).takeIf { it != 0L } ?: vod.addedEpoch,
                name = md?.name?.ifBlank { null } ?: vod.name
            )
            val subs = (r.subtitles ?: emptyMap()).mapNotNull { (lang, se) ->
                val f = se.file?.ifBlank { null } ?: return@mapNotNull null
                lang to f
            }.toMap()
            VodDetail(item, subs)
        } catch (_: Exception) {
            VodDetail(vod, emptyMap())
        }
    }

    fun vodStreamUrl(vod: VodItem): String {
        val p = active()
        if (p.type != PortalType.XTREAM) throw IllegalStateException("VOD is not supported for M3U portals")
        return "${srv()}/movie/${enc(p.username)}/${enc(p.password)}/${vod.streamId}.${vod.ext}"
    }

    // ---------- series ----------

    suspend fun seriesCategories(): List<Category> = withContext(Dispatchers.IO) {
        val p = active()
        if (p.type != PortalType.XTREAM) return@withContext emptyList()
        try {
            val cats = api().seriesCategories(p.username, p.password)
            val counts = xtreamSeriesList()
                .groupBy { it.categoryId.ifBlank { "Uncategorized" } }
                .mapValues { it.value.size }
            cats.map { c ->
                val id = c.id.ifBlank { "Uncategorized" }
                Category(id = id, name = c.name.ifBlank { id }, count = counts[id] ?: 0)
            }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun seriesList(categoryId: String?): List<SeriesItem> = withContext(Dispatchers.IO) {
        val p = active()
        if (p.type != PortalType.XTREAM) return@withContext emptyList()
        try {
            xtreamSeriesList().mapNotNull { s ->
                val catId = s.categoryId.ifBlank { "Uncategorized" }
                if (categoryId != null && categoryId != "all" && catId != categoryId) {
                    return@mapNotNull null
                }
                SeriesItem(
                    id = "series:${s.seriesId}",
                    seriesId = s.seriesId,
                    name = s.name?.ifBlank { "Series ${s.seriesId}" } ?: "Series ${s.seriesId}",
                    cover = s.cover?.ifBlank { null },
                    backdrop = s.backdropPath?.firstOrNull()?.ifBlank { null },
                    categoryId = catId,
                    rating = s.ratingValue,
                    plot = s.plot?.ifBlank { null },
                    cast = s.cast?.ifBlank { null },
                    genre = s.genre?.ifBlank { null },
                    releaseDate = s.releaseDate?.ifBlank { null },
                    addedEpoch = epochToMs(s.lastModified)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun seriesDetail(s: SeriesItem): SeriesDetail = withContext(Dispatchers.IO) {
        val p = active()
        if (p.type != PortalType.XTREAM) throw IllegalStateException("Series is not supported for M3U portals")
        try {
            val r = api().seriesInfo(p.username, p.password, id = s.seriesId)
            val info = r.info
            val item = s.copy(
                name = info?.name?.ifBlank { null } ?: s.name,
                cover = info?.cover?.ifBlank { null } ?: s.cover,
                backdrop = info?.backdropPath?.firstOrNull()?.ifBlank { null } ?: s.backdrop,
                plot = info?.plot?.ifBlank { null } ?: s.plot,
                cast = info?.cast?.ifBlank { null } ?: s.cast,
                genre = info?.genre?.ifBlank { null } ?: s.genre,
                releaseDate = info?.releaseDate?.ifBlank { null } ?: s.releaseDate,
                rating = info?.rating?.toDoubleOrNull() ?: s.rating
            )
            val seasons = (r.episodes ?: emptyMap())
                .mapNotNull { (seasonKey, eps) ->
                    val season = seasonKey.toIntOrNull() ?: return@mapNotNull null
                    val episodes = eps.map { e ->
                        Episode(
                            id = "ep:${e.episodeId}",
                            episodeId = e.episodeId,
                            season = season,
                            episodeNum = e.episodeNum,
                            title = e.title?.ifBlank { "Episode ${e.episodeNum}" }
                                ?: "Episode ${e.episodeNum}",
                            plot = e.plot?.ifBlank { null } ?: e.info?.plot?.ifBlank { null },
                            durationSecs = (e.duration ?: e.info?.duration)?.toLongOrNull(),
                            airDate = e.info?.releaseDate?.ifBlank { null },
                            ext = (e.containerExtension ?: "mp4").ifBlank { "mp4" }
                        )
                    }.sortedBy { it.episodeNum }
                    season to episodes
                }
                .toMap()
                .toSortedMap()
            SeriesDetail(item, seasons)
        } catch (_: Exception) {
            SeriesDetail(s, emptyMap())
        }
    }

    fun episodeStreamUrl(ep: Episode): String {
        val p = active()
        if (p.type != PortalType.XTREAM) throw IllegalStateException("Series is not supported for M3U portals")
        return "${srv()}/series/${enc(p.username)}/${enc(p.password)}/${ep.episodeId}.${ep.ext}"
    }

    // ---------- timeshift / epg ----------

    fun timeshiftUrl(ch: Channel, prog: EpgProgramme, durationMin: Int): String? {
        val p = active()
        if (p.type != PortalType.XTREAM || !ch.tvArchive) return null
        val sid = ch.streamId ?: return null
        val utcStart = prog.startUtc / 1000
        return "${srv()}/timeshift/${enc(p.username)}/${enc(p.password)}/$durationMin/$utcStart/$sid.m3u8"
    }

    fun epgXmlUrl(): String? {
        val p = active()
        return if (p.type == PortalType.XTREAM) {
            "${srv()}/xmltv.php?username=${enc(p.username)}&password=${enc(p.password)}"
        } else {
            p.epgUrl.ifBlank { null }
        }
    }
}
