package com.vibeiptv.app.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.vibeiptv.app.data.db.ResumeEntity
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.Episode
import com.vibeiptv.app.data.model.SeriesItem
import com.vibeiptv.app.data.model.VodDetail
import com.vibeiptv.app.data.model.VodItem
import com.vibeiptv.app.ui.player.PlayerActivity

/**
 * Single contract every launcher uses to open [PlayerActivity].
 * EXTRA_FILE_URI is always stored as a String (Uri.toString()), so both
 * [playFile] and [playUrl] are read identically by the player.
 */
object PlayerContract {
    const val EXTRA_MODE = "mode"
    const val MODE_LIVE = "live"
    const val MODE_VOD = "vod"
    const val MODE_EPISODE = "episode"
    const val MODE_FILE = "file"

    const val EXTRA_CHANNEL_JSON = "channel_json"
    const val EXTRA_CHANNEL_LIST_JSON = "channel_list_json"
    const val EXTRA_INDEX = "index"

    const val EXTRA_VOD_JSON = "vod_json"
    const val EXTRA_SUBS_JSON = "subs_json"

    const val EXTRA_EPISODE_JSON = "episode_json"

    const val EXTRA_FILE_URI = "file_uri"
    const val EXTRA_TITLE = "title"

    const val EXTRA_RESUME_MS = "resume_ms"
    const val EXTRA_CONTENT_ID = "content_id"

    fun playLive(a: Activity, channels: List<Channel>, index: Int) {
        val i = Intent(a, PlayerActivity::class.java)
        i.putExtra(EXTRA_MODE, MODE_LIVE)
        i.putExtra(EXTRA_CHANNEL_LIST_JSON, Json.gson.toJson(channels))
        i.putExtra(EXTRA_INDEX, index.coerceIn(0, maxOf(0, channels.size - 1)))
        if (channels.isNotEmpty()) {
            val ch = channels[index.coerceIn(channels.indices)]
            i.putExtra(EXTRA_CHANNEL_JSON, Json.gson.toJson(ch))
            i.putExtra(EXTRA_TITLE, ch.name)
        }
        a.startActivity(i)
    }

    fun playVod(a: Activity, detail: VodDetail, resumeMs: Long = 0) {
        val i = Intent(a, PlayerActivity::class.java)
        i.putExtra(EXTRA_MODE, MODE_VOD)
        i.putExtra(EXTRA_VOD_JSON, Json.gson.toJson(detail.item))
        i.putExtra(EXTRA_SUBS_JSON, Json.gson.toJson(detail.subtitles))
        i.putExtra(EXTRA_TITLE, detail.item.name)
        i.putExtra(EXTRA_RESUME_MS, resumeMs)
        i.putExtra(EXTRA_CONTENT_ID, vodContentId(detail.item))
        a.startActivity(i)
    }

    fun playEpisode(a: Activity, series: SeriesItem, ep: Episode, resumeMs: Long = 0) {
        val i = Intent(a, PlayerActivity::class.java)
        i.putExtra(EXTRA_MODE, MODE_EPISODE)
        i.putExtra(EXTRA_EPISODE_JSON, Json.gson.toJson(ep))
        i.putExtra(EXTRA_TITLE, "${series.name} · S${ep.season} E${ep.episodeNum}")
        i.putExtra(EXTRA_RESUME_MS, resumeMs)
        i.putExtra(EXTRA_CONTENT_ID, episodeContentId(ep))
        a.startActivity(i)
    }

    fun playFile(a: Activity, uri: Uri, title: String) {
        val i = Intent(a, PlayerActivity::class.java)
        i.putExtra(EXTRA_MODE, MODE_FILE)
        i.putExtra(EXTRA_FILE_URI, uri.toString())
        i.putExtra(EXTRA_TITLE, title)
        a.startActivity(i)
    }

    /** Timeshift / catch-up: a raw URL string launched as MODE_FILE (player picks HLS for .m3u8). */
    fun playUrl(a: Activity, url: String, title: String) {
        val i = Intent(a, PlayerActivity::class.java)
        i.putExtra(EXTRA_MODE, MODE_FILE)
        i.putExtra(EXTRA_FILE_URI, url)
        i.putExtra(EXTRA_TITLE, title)
        a.startActivity(i)
    }

    fun vodContentId(v: VodItem) = "vod:${v.streamId}"
    fun episodeContentId(e: Episode) = "ep:${e.episodeId}"

    /** Watched = resumed past 90% of a known duration. */
    fun isWatched(resume: ResumeEntity?): Boolean =
        resume != null && resume.durationMs > 0 && resume.positionMs >= resume.durationMs * 0.9
}
