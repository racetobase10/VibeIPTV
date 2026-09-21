package com.vibeiptv.app.ui.recordings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vibeiptv.app.data.api.Http
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Foreground service that records a live stream to a local .ts file.
 *
 * NOTE (honest limitation): HLS (.m3u8) recording is NOT supported here — an HLS
 * stream is a playlist of short segments, not a single byte stream, so it cannot
 * be captured by simply downloading one URL. The player hides the record button
 * for HLS streams, so this service only ever receives plain progressive
 * (e.g. MPEG-TS) URLs and can stream the response body straight to disk.
 *
 * POST_NOTIFICATIONS is requested by RecordingsActivity, not inside this service.
 */
class RecordService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_STOP = "stop"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIF_ID = 1001
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val rawName = intent.getStringExtra(EXTRA_FILENAME).takeIf { !it.isNullOrBlank() } ?: "recording"
        val filename = sanitize(rawName) + ".ts"

        createChannel()
        val ongoing = baseNotification("Recording", filename).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, ongoing)
        }

        worker = Thread { runDownload(url, filename) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, filename: String) {
        val nm = getSystemService(NotificationManager::class.java)
        try {
            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings")
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, filename)
                resp.body!!.byteStream().use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
            nm.notify(NOTIF_ID, baseNotification("Recording saved", filename).setOngoing(false).build())
        } catch (e: Exception) {
            nm.notify(NOTIF_ID, baseNotification("Recording failed", filename).setOngoing(false).build())
        } finally {
            stopSelf()
        }
    }

    private fun baseNotification(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "recording" }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }
}
