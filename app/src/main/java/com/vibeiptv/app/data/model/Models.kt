package com.vibeiptv.app.data.model

enum class PortalType { XTREAM, M3U }

data class PortalConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: PortalType,
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = ""
)

data class Category(val id: String, val name: String, val count: Int = 0)

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val logo: String?,
    val categoryId: String,
    val categoryName: String,
    val streamId: Int?,
    val epgChannelId: String?,
    val tvArchive: Boolean,
    val directSource: String? = null
)

data class VodItem(
    val id: String,
    val streamId: Int,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val categoryId: String,
    val rating: Double,
    val addedEpoch: Long,
    val ext: String,
    val plot: String?,
    val director: String?,
    val cast: String?,
    val genre: String?,
    val year: String?,
    val duration: String?
)

data class VodDetail(val item: VodItem, val subtitles: Map<String, String>)

data class SeriesItem(
    val id: String,
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val categoryId: String,
    val rating: Double,
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val releaseDate: String?,
    val addedEpoch: Long
)

data class Episode(
    val id: String,
    val episodeId: Int,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val plot: String?,
    val durationSecs: Long?,
    val airDate: String?,
    val ext: String
)

data class SeriesDetail(val item: SeriesItem, val seasons: Map<Int, List<Episode>>)

data class EpgProgramme(
    val channelKey: String,
    val startUtc: Long,
    val stopUtc: Long,
    val title: String,
    val desc: String?
)

object FavType {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"
}
