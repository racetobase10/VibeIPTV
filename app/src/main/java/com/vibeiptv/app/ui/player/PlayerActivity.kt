package com.vibeiptv.app.ui.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.gson.reflect.TypeToken
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.ResumeEntity
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.Episode
import com.vibeiptv.app.data.model.EpgProgramme
import com.vibeiptv.app.data.model.VodItem
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.EpgRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivityPlayerBinding
import com.vibeiptv.app.ui.recordings.RecordService
import com.vibeiptv.app.util.Format
import com.vibeiptv.app.util.Json
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var trackSelector: DefaultTrackSelector
    private var player: ExoPlayer? = null

    private var mode: String = PlayerContract.MODE_FILE
    private var title: String = ""
    private var streamUrl: String = ""
    private var subs: Map<String, String> = emptyMap()

    /** Subtitle files the user loaded from device storage this session. */
    private val externalSubs = mutableListOf<MediaItem.SubtitleConfiguration>()
    /** Label of a freshly loaded subtitle, auto-selected once tracks arrive. */
    private var pendingSubLabel: String? = null

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) loadSubtitleFile(uri)
        }

    // Live state
    private var channels: List<Channel> = emptyList()
    private var channelIndex = 0
    private var channel: Channel? = null

    // VOD / episode state
    private var vod: VodItem? = null
    private var episode: Episode? = null
    private var contentId: String? = null
    private var resumeMs = 0L
    private var seekDone = false

    // OSD
    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOsdNow() }
    private var osdVisible = false
    private var bannerVisible = true
    private var sleepRunnable: Runnable? = null

    // Aspect
    private val aspects = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val aspectNames = arrayOf("Fit", "Fill", "Zoom")
    private var aspectIdx = 0

    private data class TrackSel(val label: String, val group: TrackGroup, val track: Int)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && !seekDone && resumeMs > 0 &&
                (mode == PlayerContract.MODE_VOD || mode == PlayerContract.MODE_EPISODE)
            ) {
                seekDone = true
                player?.seekTo(resumeMs)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                "Playback error: ${error.message ?: "unknown"}",
                Toast.LENGTH_LONG
            ).show()
            showOsd() // keep controls visible on error
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val want = pendingSubLabel ?: return
            pendingSubLabel = null
            val b = trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            outer@ for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    if (f.label?.toString() == want) {
                        b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        break@outer
                    }
                }
            }
            trackSelector.setParameters(b)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!parseIntent()) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val isLive = mode == PlayerContract.MODE_LIVE
        val isHls = streamUrl.endsWith(".m3u8", ignoreCase = true)

        binding.btnChUp.visibility = if (isLive) View.VISIBLE else View.GONE
        binding.btnChDown.visibility = if (isLive) View.VISIBLE else View.GONE
        // HLS (.m3u8 segmented) recording is not supported — hide the button.
        binding.btnRecord.visibility = if (isLive && !isHls) View.VISIBLE else View.GONE

        binding.btnPlayPause.setOnClickListener { togglePlayPause(); showOsd() }
        binding.btnRew.setOnClickListener {
            val p = player
            if (p != null) p.seekTo(maxOf(0, p.currentPosition - 10_000))
            showOsd()
        }
        binding.btnFwd.setOnClickListener {
            val p = player
            if (p != null && p.duration > 0) p.seekTo(minOf(p.duration, p.currentPosition + 10_000))
            showOsd()
        }
        binding.btnChUp.setOnClickListener { changeChannel(+1) }
        binding.btnChDown.setOnClickListener { changeChannel(-1) }
        binding.btnRecord.setOnClickListener { startRecording(); showOsd() }
        binding.btnAspect.setOnClickListener { cycleAspect(); showOsd() }
        binding.btnAudio.setOnClickListener { showAudioDialog(); showOsd() }
        binding.btnSubs.setOnClickListener { showSubsDialog(); showOsd() }
        binding.btnSleep.setOnClickListener { showSleepDialog(); showOsd() }
        binding.btnInfo.setOnClickListener { toggleBanner(); showOsd() }

        binding.rootView.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) showOsd()
            false
        }

        buildPlayer()
        updateBanner()
        showOsd()
    }

    /** Reads the PlayerContract extras. Returns false when there is nothing playable. */
    private fun parseIntent(): Boolean {
        mode = intent.getStringExtra(PlayerContract.EXTRA_MODE) ?: PlayerContract.MODE_FILE
        title = intent.getStringExtra(PlayerContract.EXTRA_TITLE) ?: "Playing"
        resumeMs = intent.getLongExtra(PlayerContract.EXTRA_RESUME_MS, 0L)
        contentId = intent.getStringExtra(PlayerContract.EXTRA_CONTENT_ID)

        return when (mode) {
            PlayerContract.MODE_LIVE -> {
                val listJson = intent.getStringExtra(PlayerContract.EXTRA_CHANNEL_LIST_JSON)
                    ?: return false
                channels = Json.gson.fromJson(
                    listJson, object : TypeToken<List<Channel>>() {}.type
                )
                if (channels.isEmpty()) return false
                channelIndex = intent.getIntExtra(PlayerContract.EXTRA_INDEX, 0)
                    .coerceIn(channels.indices)
                channel = channels[channelIndex]
                title = channel?.name ?: title
                streamUrl = ContentRepository(this).liveStreamUrl(channel!!)
                subs = emptyMap()
                streamUrl.isNotEmpty()
            }
            PlayerContract.MODE_VOD -> {
                val vodJson = intent.getStringExtra(PlayerContract.EXTRA_VOD_JSON) ?: return false
                vod = Json.gson.fromJson(vodJson, VodItem::class.java)
                val subsJson = intent.getStringExtra(PlayerContract.EXTRA_SUBS_JSON)
                subs = if (subsJson.isNullOrEmpty()) emptyMap()
                else Json.gson.fromJson(subsJson, object : TypeToken<Map<String, String>>() {}.type)
                streamUrl = ContentRepository(this).vodStreamUrl(vod!!)
                streamUrl.isNotEmpty()
            }
            PlayerContract.MODE_EPISODE -> {
                val epJson = intent.getStringExtra(PlayerContract.EXTRA_EPISODE_JSON) ?: return false
                episode = Json.gson.fromJson(epJson, Episode::class.java)
                streamUrl = ContentRepository(this).episodeStreamUrl(episode!!)
                subs = emptyMap()
                streamUrl.isNotEmpty()
            }
            else -> { // MODE_FILE — raw URL string; may be .m3u8 (timeshift) → HLS.
                streamUrl = intent.getStringExtra(PlayerContract.EXTRA_FILE_URI).orEmpty()
                subs = emptyMap()
                streamUrl.isNotEmpty()
            }
        }
    }

    private fun buildPlayer() {
        releasePlayer()
        val store = PortalStore(this)

        val renderers = DefaultRenderersFactory(this).apply {
            if (store.decoderMode == "software") {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            }
        }
        val buf = store.bufferMs.coerceAtLeast(5000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(buf, buf * 2, 1500, 3000)
            .build()

        trackSelector = DefaultTrackSelector(this)
        val dataSource = DefaultHttpDataSource.Factory().setUserAgent("VibeIPTV/1.0")

        val item = buildMediaItem()

        val source = if (streamUrl.endsWith(".m3u8", ignoreCase = true))
            HlsMediaSource.Factory(dataSource).createMediaSource(item)
        else
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(listener)
        binding.playerView.player = p
        p.setMediaSource(source)
        p.prepare()
        p.play()
        player = p
        updatePlayPauseIcon()
    }

    /** Builds the media item from the stream URL plus provider and user-loaded subtitles. */
    private fun buildMediaItem(): MediaItem {
        val subConfigs = subs.map { (label, url) ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(
                    if (url.endsWith(".vtt", ignoreCase = true)) MimeTypes.TEXT_VTT
                    else MimeTypes.APPLICATION_SUBRIP
                )
                .setLabel(label)
                .setLanguage(label)
                .build()
        } + externalSubs
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(subConfigs)
            .build()
    }

    /** Re-prepares the player with the current subtitle set, keeping position. */
    private fun reloadWithSubtitles() {
        val p = player ?: return
        val pos = p.currentPosition
        val wasPlaying = p.isPlaying
        val dataSource = DefaultHttpDataSource.Factory().setUserAgent("VibeIPTV/1.0")
        val item = buildMediaItem()
        val source = if (streamUrl.endsWith(".m3u8", ignoreCase = true))
            HlsMediaSource.Factory(dataSource).createMediaSource(item)
        else
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)
        p.setMediaSource(source)
        p.prepare()
        p.seekTo(pos)
        if (wasPlaying) p.play()
    }

    private fun releasePlayer() {
        player?.removeListener(listener)
        player?.release()
        player = null
        if (::binding.isInitialized) binding.playerView.player = null
    }

    // ------------------------------------------------------------------ OSD

    private fun showOsd() {
        if (!osdVisible) {
            osdVisible = true
            binding.osdBar.visibility = View.VISIBLE
            binding.btnPlayPause.requestFocus()
        }
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 4000)
    }

    private fun hideOsdNow() {
        osdVisible = false
        binding.osdBar.visibility = View.GONE
        osdHandler.removeCallbacks(hideOsdRunnable)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                return true
            }
            // Every key press reveals the OSD and resets the auto-hide timer.
            // The first press only reveals; navigation/clicks are handled by super.
            showOsd()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        binding.btnPlayPause.text = if (player?.isPlaying == true) "⏸" else "▶"
    }

    private fun toggleBanner() {
        bannerVisible = !bannerVisible
        binding.topBanner.visibility = if (bannerVisible) View.VISIBLE else View.GONE
    }

    private fun updateBanner() {
        binding.tvTitle.text = title
        when (mode) {
            PlayerContract.MODE_LIVE -> {
                val ch = channel
                if (ch == null) {
                    binding.tvMeta.text = ""
                } else {
                    binding.tvMeta.text = "CH ${ch.number} • loading EPG…"
                    lifecycleScope.launch(Dispatchers.IO) {
                        val line = try {
                            val (now, next) = EpgRepository(this@PlayerActivity).nowNextForChannel(ch)
                            "CH ${ch.number} • " + nowNextLine(now, next)
                        } catch (_: Exception) {
                            "CH ${ch.number}"
                        }
                        withContext(Dispatchers.Main) { binding.tvMeta.text = line }
                    }
                }
            }
            PlayerContract.MODE_VOD -> {
                binding.tvMeta.text = vod?.let {
                    listOfNotNull(it.year, it.genre, it.duration).joinToString(" • ")
                } ?: ""
            }
            PlayerContract.MODE_EPISODE -> {
                binding.tvMeta.text = episode?.let {
                    "Season ${it.season} • Episode ${it.episodeNum}" +
                        (it.durationSecs?.let { s -> " • ${Format.durationHm(s, true)}" } ?: "")
                } ?: ""
            }
            else -> binding.tvMeta.text = ""
        }
        binding.topBanner.visibility = if (bannerVisible) View.VISIBLE else View.GONE
    }

    private fun nowNextLine(now: EpgProgramme?, next: EpgProgramme?): String {
        if (now == null && next == null) return "No EPG info"
        val sb = StringBuilder()
        if (now != null) sb.append("Now: ${Format.timeHm(now.startUtc)} ${now.title}")
        if (next != null) {
            if (sb.isNotEmpty()) sb.append("  •  ")
            sb.append("Next: ${Format.timeHm(next.startUtc)} ${next.title}")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ live CH+/-

    private fun changeChannel(delta: Int) {
        if (mode != PlayerContract.MODE_LIVE || channels.isEmpty()) return
        channelIndex = (channelIndex + delta).mod(channels.size)
        channel = channels[channelIndex]
        title = channel!!.name
        streamUrl = ContentRepository(this).liveStreamUrl(channel!!)
        seekDone = false
        externalSubs.clear()
        pendingSubLabel = null
        buildPlayer()
        updateBanner()
        showOsd()
    }

    // ------------------------------------------------------------------ dialogs

    private fun showAudioDialog() {
        val groups = player?.currentTracks?.groups
        if (groups == null) {
            Toast.makeText(this, "No audio tracks", Toast.LENGTH_SHORT).show(); return
        }
        val items = mutableListOf<TrackSel>()
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = f.label?.toString()
                    ?: f.language
                    ?: f.sampleMimeType
                    ?: "Track ${i + 1}"
                items.add(TrackSel(label, g.mediaTrackGroup, i))
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "No audio tracks", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Audio track")
            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                val t = items[which]
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setOverrideForType(TrackSelectionOverride(t.group, t.track))
                )
            }
            .show()
    }

    private fun showSubsDialog() {
        val groups = player?.currentTracks?.groups
        if (groups == null) {
            Toast.makeText(this, "No subtitles", Toast.LENGTH_SHORT).show(); return
        }
        val items = mutableListOf<TrackSel>()
        for (g in groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = f.label?.toString() ?: f.language ?: "Subtitle ${i + 1}"
                items.add(TrackSel(label, g.mediaTrackGroup, i))
            }
        }
        val labels = mutableListOf("Load from device…", "Off")
        items.forEach { labels.add(it.label) }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openSubtitlePicker()
                    1 -> {
                        trackSelector.setParameters(
                            trackSelector.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        )
                    }
                    else -> {
                        val t = items[which - 2]
                        trackSelector.setParameters(
                            trackSelector.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(t.group, t.track))
                        )
                    }
                }
            }
            .show()
    }

    /** System file picker for .srt / .vtt / .ass subtitle files. */
    private fun openSubtitlePicker() {
        try {
            subtitlePicker.launch(
                arrayOf(
                    "text/plain", "text/vtt", "text/x-ssa",
                    "application/x-subrip", "application/x-ssa",
                    "application/octet-stream"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Loads a user-picked subtitle file (.srt/.vtt/.ass/.ssa) via the system
     * picker. The content URI is used directly (with a persistable read grant);
     * server-provided subtitles are left untouched.
     */
    private fun loadSubtitleFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { /* best-effort */ }

                val name = queryDisplayName(uri) ?: "subtitle.srt"
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = when (ext) {
                    "vtt" -> MimeTypes.TEXT_VTT
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    else -> null
                }
                if (mime == null) {
                    toast("Unsupported subtitle format" + if (ext.isNotEmpty()) " (.$ext)" else "")
                    return@launch
                }
                val label = name.substringBeforeLast('.').ifBlank { "External" }
                val config = MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime)
                    .setLabel(label)
                    .setLanguage("und")
                    .build()
                withContext(Dispatchers.Main) {
                    externalSubs.add(config)
                    pendingSubLabel = label
                    reloadWithSubtitles()
                    Toast.makeText(
                        this@PlayerActivity,
                        "Subtitle loaded: $label",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                toast("Could not load subtitle: ${e.message}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun toast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspects.size
        binding.playerView.resizeMode = aspects[aspectIdx]
        Toast.makeText(this, "Aspect: ${aspectNames[aspectIdx]}", Toast.LENGTH_SHORT).show()
    }

    private fun showSleepDialog() {
        val options = arrayOf("Off", "15 min", "30 min", "60 min", "90 min")
        val mins = intArrayOf(0, 15, 30, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(options) { _, which ->
                sleepRunnable?.let { osdHandler.removeCallbacks(it) }
                sleepRunnable = null
                if (mins[which] > 0) {
                    val r = Runnable {
                        player?.stop()
                        Toast.makeText(this, "Sleep timer ended playback", Toast.LENGTH_SHORT).show()
                        showOsd()
                    }
                    sleepRunnable = r
                    osdHandler.postDelayed(r, mins[which] * 60_000L)
                    Toast.makeText(this, "Sleep timer: ${options[which]}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ recording

    private fun startRecording() {
        val ch = channel ?: return
        if (streamUrl.endsWith(".m3u8", ignoreCase = true)) {
            Toast.makeText(this, "Recording not supported for HLS streams", Toast.LENGTH_SHORT).show()
            return
        }
        val safe = ch.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val i = Intent(this, RecordService::class.java)
        i.putExtra("url", streamUrl)
        i.putExtra("filename", "$safe.ts")
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ PiP / resume

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) {
            hideOsdNow()
            binding.topBanner.visibility = View.GONE
            bannerVisible = false
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    private fun saveResume() {
        val id = contentId ?: return
        if (mode != PlayerContract.MODE_VOD && mode != PlayerContract.MODE_EPISODE) return
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0)
        if (dur <= 0) return
        val pos = p.currentPosition
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppDatabase.get(this@PlayerActivity).resumeDao().upsert(
                    ResumeEntity(
                        contentId = id,
                        positionMs = pos,
                        durationMs = dur,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) player?.pause()
        saveResume()
    }

    override fun onStop() {
        super.onStop()
        saveResume()
    }

    override fun onDestroy() {
        saveResume()
        osdHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }
}
