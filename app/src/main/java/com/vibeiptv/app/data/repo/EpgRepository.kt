package com.vibeiptv.app.data.repo

import android.content.Context
import com.vibeiptv.app.data.api.EpgParser
import com.vibeiptv.app.data.api.Http
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.EpgMetaEntity
import com.vibeiptv.app.data.db.EpgProgrammeEntity
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.EpgProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class EpgRepository(private val ctx: Context) {

    private val content = ContentRepository(ctx)
    private val db = AppDatabase.get(ctx)

    companion object {
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Key candidates to try, in order: the xmltv id, then the app's channel id. */
        fun epgKeysFor(ch: Channel): List<String> =
            listOfNotNull(ch.epgChannelId?.ifBlank { null }, ch.id)
    }

    private fun EpgProgrammeEntity.toModel(): EpgProgramme =
        EpgProgramme(channelKey, startUtc, stopUtc, title, desc)

    /** Downloads and caches the full XMLTV guide; skips if refreshed < 24h ago unless forced. */
    suspend fun refreshIfNeeded(force: Boolean = false) = withContext(Dispatchers.IO) {
        val last = lastUpdated()
        if (!force && last > 0 && System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) {
            return@withContext
        }
        val url = try {
            content.epgXmlUrl()
        } catch (_: Exception) {
            null
        } ?: return@withContext
        try {
            val req = Request.Builder().url(url).build()
            val programmes = Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body ?: return@use emptyList()
                EpgParser.parse(body.byteStream()).programmes
            }
            db.epgDao().clearAll()
            if (programmes.isNotEmpty()) {
                db.epgDao().insertAll(
                    programmes.map {
                        EpgProgrammeEntity(
                            channelKey = it.channelKey,
                            startUtc = it.startUtc,
                            stopUtc = it.stopUtc,
                            title = it.title,
                            desc = it.desc
                        )
                    }
                )
            }
            db.epgMetaDao().put(
                EpgMetaEntity(KEY_LAST_UPDATED, System.currentTimeMillis().toString())
            )
        } catch (_: Exception) {
            // Keep the existing cache on failure.
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        db.epgDao().clearAll()
        db.epgMetaDao().put(EpgMetaEntity(KEY_LAST_UPDATED, "0"))
    }

    /** Epoch millis of the last successful refresh, 0 if never. */
    suspend fun lastUpdated(): Long = withContext(Dispatchers.IO) {
        db.epgMetaDao().get(KEY_LAST_UPDATED)?.toLongOrNull() ?: 0L
    }

    suspend fun nowNext(rawChannelKey: String): Pair<EpgProgramme?, EpgProgramme?> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val nowProg = db.epgDao().nowAt(rawChannelKey, now)?.toModel()
            val nextProg = db.epgDao().nextAfter(rawChannelKey, now)?.toModel()
            nowProg to nextProg
        }

    suspend fun programmesForWindow(
        rawKeys: List<String>,
        fromUtc: Long,
        toUtc: Long
    ): Map<String, List<EpgProgramme>> = withContext(Dispatchers.IO) {
        rawKeys.associateWith { k ->
            db.epgDao().window(k, fromUtc, toUtc).map { it.toModel() }
        }
    }

    /** Tries the channel's EPG keys in order (epgChannelId, then channel id). */
    suspend fun nowNextForChannel(ch: Channel): Pair<EpgProgramme?, EpgProgramme?> =
        withContext(Dispatchers.IO) {
            var fallback: Pair<EpgProgramme?, EpgProgramme?>? = null
            for (k in epgKeysFor(ch)) {
                val pair = nowNext(k)
                if (pair.first != null) return@withContext pair
                if (fallback == null && pair.second != null) fallback = pair
            }
            fallback ?: (null to null)
        }

    /** Returns programmes per channel, keyed by [Channel.id]. */
    suspend fun windowForChannels(
        channels: List<Channel>,
        from: Long,
        to: Long
    ): Map<String, List<EpgProgramme>> = withContext(Dispatchers.IO) {
        channels.associate { ch ->
            val list = epgKeysFor(ch).asSequence()
                .map { k -> db.epgDao().window(k, from, to).map { it.toModel() } }
                .firstOrNull { it.isNotEmpty() }
                ?: emptyList()
            ch.id to list
        }
    }
}
