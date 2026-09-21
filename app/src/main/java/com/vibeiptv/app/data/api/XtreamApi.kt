package com.vibeiptv.app.data.api

import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Xtream Codes player_api.php client.
 *
 * PHP quirk: id-ish fields may arrive as JSON numbers OR strings, so they are
 * declared as [JsonElement] with computed String/Int vals.
 */
interface XtreamApi {

    @GET("player_api.php")
    suspend fun login(
        @Query("username") u: String,
        @Query("password") p: String
    ): LoginResponse

    @GET("player_api.php")
    suspend fun liveCategories(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_live_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun liveStreams(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_live_streams"
    ): List<XtreamLiveStream>

    @GET("player_api.php")
    suspend fun vodCategories(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_vod_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun vodStreams(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_vod_streams"
    ): List<XtreamVodStream>

    @GET("player_api.php")
    suspend fun vodInfo(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_vod_info",
        @Query("vod_id") id: Int
    ): VodInfoResponse

    @GET("player_api.php")
    suspend fun seriesCategories(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_series_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun seriesList(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_series"
    ): List<XtreamSeries>

    @GET("player_api.php")
    suspend fun seriesInfo(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_series_info",
        @Query("series_id") id: Int
    ): SeriesInfoResponse

    @GET("player_api.php")
    suspend fun shortEpg(
        @Query("username") u: String,
        @Query("password") p: String,
        @Query("action") a: String = "get_short_epg",
        @Query("stream_id") id: Int,
        @Query("limit") limit: Int = 4
    ): ShortEpgResponse

    companion object {
        fun create(serverUrl: String): XtreamApi {
            return Retrofit.Builder()
                .baseUrl(serverUrl.trimEnd('/') + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(Http.client)
                .build()
                .create(XtreamApi::class.java)
        }
    }
}

// ---------- DTOs ----------

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    @SerializedName("auth") val auth: Int = 0,
    @SerializedName("username") val username: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("exp_date") val expDate: String? = null,
    @SerializedName("max_connections") val maxConnections: String? = null,
    @SerializedName("active_cons") val activeCons: String? = null
)

data class ServerInfo(
    @SerializedName("xui") val xui: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("port") val port: String? = null,
    @SerializedName("https_port") val httpsPort: String? = null,
    @SerializedName("server_protocol") val serverProtocol: String? = null
)

data class XtreamCategory(
    @SerializedName("category_id") val categoryIdRaw: JsonElement?,
    @SerializedName("category_name") val categoryName: String?
) {
    val id: String get() = categoryIdRaw.strId()
    val name: String get() = categoryName?.ifBlank { "Unnamed" } ?: "Unnamed"
}

data class XtreamLiveStream(
    @SerializedName("stream_id") val streamIdRaw: JsonElement?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("tv_archive") val tvArchiveRaw: JsonElement?,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: String?,
    @SerializedName("category_id") val categoryIdRaw: JsonElement?,
    @SerializedName("added") val added: String?
) {
    val streamId: Int get() = streamIdRaw.intId()
    val categoryId: String get() = categoryIdRaw.strId()
    /** tv_archive may be "1", 1, or "true". */
    val tvArchive: Boolean get() {
        val s = tvArchiveRaw.strId().lowercase()
        return s == "1" || s == "true"
    }
}

data class XtreamVodStream(
    @SerializedName("stream_id") val streamIdRaw: JsonElement?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("category_id") val categoryIdRaw: JsonElement?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("added") val added: String?
) {
    val streamId: Int get() = streamIdRaw.intId()
    val categoryId: String get() = categoryIdRaw.strId()
    val ratingValue: Double get() = rating?.toDoubleOrNull() ?: 0.0
}

data class VodInfoResponse(
    @SerializedName("info") val info: VodInfo?,
    @SerializedName("movie_data") val movieData: MovieData?,
    @SerializedName("subtitles") val subtitles: Map<String, SubtitleEntry>?
)

data class VodInfo(
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("rating") val rating: String?
)

data class MovieData(
    @SerializedName("stream_id") val streamIdRaw: JsonElement?,
    @SerializedName("name") val name: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("added") val added: String?
) {
    val streamId: Int get() = streamIdRaw.intId()
}

data class SubtitleEntry(
    @SerializedName("file") val file: String?
)

data class XtreamSeries(
    @SerializedName("series_id") val seriesIdRaw: JsonElement?,
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("category_id") val categoryIdRaw: JsonElement?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("last_modified") val lastModified: String?
) {
    val seriesId: Int get() = seriesIdRaw.intId()
    val categoryId: String get() = categoryIdRaw.strId()
    val ratingValue: Double get() = rating?.toDoubleOrNull() ?: 0.0
}

data class SeriesInfoResponse(
    @SerializedName("info") val info: SeriesInfo?,
    @SerializedName("episodes") val episodes: Map<String, List<SeriesEpisode>>?,
    @SerializedName("seasons") val seasons: List<JsonElement>?
)

data class SeriesInfo(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?
)

data class SeriesEpisode(
    @SerializedName("id") val idRaw: JsonElement?,
    @SerializedName("episode_num") val episodeNumRaw: JsonElement?,
    @SerializedName("title") val title: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: EpisodeInfo?
) {
    val episodeId: Int get() = idRaw.intId()
    val episodeNum: Int get() = episodeNumRaw.intId()
}

data class EpisodeInfo(
    @SerializedName("plot") val plot: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("movie_image") val movieImage: String?
)

data class ShortEpgResponse(
    @SerializedName("epg_listings") val epgListings: List<EpgListing>?
)

data class EpgListing(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("stop") val stop: String?,
    @SerializedName("start_timestamp") val startTimestamp: String?,
    @SerializedName("stop_timestamp") val stopTimestamp: String?
) {
    /** Xtream returns title/description base64-encoded. */
    val decodedTitle: String get() = decodeBase64(title)
    val decodedDescription: String get() = decodeBase64(description)
}

// ---------- helpers ----------

internal fun JsonElement?.strId(): String =
    if (this == null || isJsonNull) "" else try {
        asString
    } catch (_: Exception) {
        ""
    }

internal fun JsonElement?.intId(): Int = strId().toIntOrNull() ?: 0

internal fun decodeBase64(s: String?): String {
    if (s.isNullOrBlank()) return ""
    return try {
        String(Base64.decode(s.trim(), Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        s
    }
}
