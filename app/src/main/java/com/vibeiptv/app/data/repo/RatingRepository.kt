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
 * External ratings lookup (TMDB, OMDb) with a Room cache.
 *
 * API keys are configured in Settings and stored encrypted. TMDB is tried
 * first when its key is present, then OMDb. Results are cached for 30 days
 * so detail screens rarely hit the network.
 */
class RatingRepository(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val db by lazy { AppDatabase.get(appCtx) }
    private val store by lazy { PortalStore(appCtx) }

    data class Rating(val value: Double, val source: String)

    companion object {
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }

    private fun cacheKey(title: String, year: String?, isSeries: Boolean): String {
        val norm = title.trim().lowercase().replace(Regex("\\s+"), " ")
        return "${if (isSeries) "tv" else "movie"}|$norm|${year?.trim() ?: ""}"
    }

    /** Returns null when no API key is configured or the lookup fails. */
    suspend fun ratingFor(title: String, year: String?, isSeries: Boolean): Rating? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val key = cacheKey(title, year, isSeries)
            val now = System.currentTimeMillis()
            try {
                val cached = db.ratingDao().get(key)
                if (cached != null && now - cached.updatedAt < CACHE_TTL_MS) {
                    return@withContext Rating(cached.rating, cached.source)
                }
            } catch (_: Exception) { }

            val tmdbKey = try { store.getTmdbApiKey() } catch (_: Exception) { null }
            val omdbKey = try { store.getOmdbApiKey() } catch (_: Exception) { null }

            var result: Rating? = null
            if (!tmdbKey.isNullOrBlank()) {
                result = tryTmdb(title, year, isSeries, tmdbKey)
            }
            if (result == null && !omdbKey.isNullOrBlank()) {
                result = tryOmdb(title, year, omdbKey)
            }
            if (result != null) {
                try {
                    db.ratingDao().put(RatingEntity(key, result.value, result.source, now))
                } catch (_: Exception) { }
            }
            result
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

    private fun tryTmdb(title: String, year: String?, isSeries: Boolean, key: String): Rating? {
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
            val first = results[0].asJsonObject
            val vote = first.get("vote_average")?.asDouble ?: 0.0
            if (vote > 0) Rating(vote, "TMDB") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOmdb(title: String, year: String?, key: String): Rating? {
        val t = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (!year.isNullOrBlank() && year.length >= 4)
            "&y=${year.take(4)}" else ""
        val url = "https://www.omdbapi.com/?apikey=${URLEncoder.encode(key, "UTF-8")}" +
            "&t=$t$yearParam"
        val body = getJson(url) ?: return null
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            if (obj.get("Response")?.asString != "True") return null
            val raw = obj.get("imdbRating")?.asString
            val v = raw?.toDoubleOrNull()
            if (v != null && v > 0) Rating(v, "OMDb") else null
        } catch (_: Exception) {
            null
        }
    }
}
