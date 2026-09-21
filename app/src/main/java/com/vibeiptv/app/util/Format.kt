package com.vibeiptv.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    /** "14:30" in the device's locale/timezone. */
    fun timeHm(utc: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(utc))

    /** "21 Sep 14:30" in the device's locale/timezone. */
    fun dateHm(utc: Long): String =
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(utc))

    /** 83 -> "1h 23m" (mins), 45 -> "45m"; pass isSecs=true when the input is seconds. */
    fun durationHm(minsOrSecs: Long, isSecs: Boolean = false): String {
        val totalMins = if (isSecs) minsOrSecs / 60 else minsOrSecs
        val h = totalMins / 60
        val m = totalMins % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** 1234567890 -> "1.2 GB". */
    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }
}
