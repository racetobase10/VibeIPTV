package com.vibeiptv.app.data.repo

import android.content.Context
import com.google.gson.JsonParser
import com.vibeiptv.app.data.api.Http
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.RatingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * External ratings lookup with a Room cache.
 *
 * API keys are configured in Settings → Ratings and stored encrypted.
 * TMDB is tried first when its key is present, then OMDb (which is the
 * source of the IMDb rating — there is no public IMDb API, hence the
 * honest "IMDb" label on OMDb results). Results — and misses — are cached
 * per title+year+type+source for 30 days so detail screens don't refetch.
 */
class RatingRepository(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val db by lazy { AppDatabase.get(appCtx) }
    private val store by lazy { PortalStore(appCtx) }

    data class Rating(val value: Double, val source: String)

    companion object {
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        /** Cached marker for "this source had no rating" — avoids refetching. */
        private const val MISS = -1.0
        const val SOURCE_TMDB = "TMDB"
        const val SOURCE_IMDB = "IMDb"
    }

    private fun cacheKey(title: String, year: String?, isSeries: Boolean, source: String): String {
        val norm = title.trim().lowercase().replace(Regex("\\s+"), " ")
        return "${if (isSeries) "tv" else "movie"}|$norm|${year?.trim() ?: ""}|$source"
    }

    /**
     * Returns null when no API key is configured or no source has a rating.
     * Only call when the provider's own rating is missing.
     */
    suspend fun ratingFor(title: String, year: String?, isSeries: Boolean): Rating? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val tmdbKey = try { store.getTmdbApiKey() } catch (_: Exception) { null }
            val omdbKey = try { store.getOmdbApiKey() } catch (_: Exception) { null }
            if (tmdbKey.isNullOrBlank() && omdbKey.isNullOrBlank()) return@withContext null

            val now = System.currentTimeMillis()
            // TMDB first, OMDb (IMDb rating) as fallback.
            if (!tmdbKey.isNullOrBlank()) {
                val r = lookupWithCache(title, year, isSeries, SOURCE_TMDB, now) {
                    tryTmdb(title, year, isSeries, tmdbKey)
                }
                if (r != null) return@withContext r
            }
            if (!omdbKey.isNullOrBlank()) {
                val r = lookupWithCache(title, year, isSeries, SOURCE_IMDB, now) {
                    tryOmdb(title, year, omdbKey)
                }
                if (r != null) return@withContext r
            }
            null
        }

    /** Cache-aware single-source lookup; caches misses too. */
    private suspend fun lookupWithCache(
        title: String,
        year: String?,
        isSeries: Boolean,
        source: String,
        now: Long,
        fetch: () -> Double?
    ): Rating? {
        val key = cacheKey(title, year, isSeries, source)
        try {
            val cached = db.ratingDao().get(key)
            if (cached != null && now - cached.updatedAt < CACHE_TTL_MS) {
                return if (cached.rating >= 0) Rating(cached.rating, cached.source) else null
            }
        } catch (_: Exception) { }

        val value = try { fetch() } catch (_: Exception) { null }
        try {
            // Cache misses as well so we don't hammer the API on every open.
            db.ratingDao().put(RatingEntity(key, value ?: MISS, source, now))
        } catch (_: Exception) { }
        return if (value != null && value > 0) Rating(value, source) else null
    }

    private fun getJson(url: String): String? {
        return try {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", "VibeIPTV/1.0").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryTmdb(title: String, year: String?, isSeries: Boolean, key: String): Double? {
        val q = URLEncoder.encode(title, "UTF-8")
        val kind = if (isSeries) "tv" else "movie"
        val yearParam = if (!year.isNullOrBlank() && year.length >= 4)
            "&year=${year.take(4)}" else ""
        val url = "https://api.themoviedb.org/3/search/$kind" +
            "?api_key=${URLEncoder.encode(key, "UTF-8")}&query=$q$yearParam"
        val body = getJson(url) ?: return null
        return try {
            val results = JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("results")
            if (results == null || results.size() == 0) return null
            val vote = results[0].asJsonObject.get("vote_average")?.asDouble ?: 0.0
            if (vote > 0) vote else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOmdb(title: String, year: String?, key: String): Double? {
        val t = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (!year.isNullOrBlank() && year.length >= 4)
            "&y=${year.take(4)}" else ""
        val url = "https://www.omdbapi.com/?apikey=${URLEncoder.encode(key, "UTF-8")}" +
            "&t=$t$yearParam"
        val body = getJson(url) ?: return null
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            if (obj.get("Response")?.asString != "True") return null
            val v = obj.get("imdbRating")?.asString?.toDoubleOrNull()
            if (v != null && v > 0) v else null
        } catch (_: Exception) {
            null
        }
    }
}
