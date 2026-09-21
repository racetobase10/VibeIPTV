package com.vibeiptv.app.data.api

import android.util.Xml
import com.vibeiptv.app.data.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class EpgChannel(val id: String, val name: String, val logo: String?)

object EpgParser {

    data class Result(
        val channels: List<EpgChannel>,
        val programmes: List<EpgProgramme>
    )

    private val fullFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val noZoneFmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val v = s.trim()
        return try {
            fullFmt.parse(v)?.time ?: 0L
        } catch (_: Exception) {
            try {
                noZoneFmt.parse(v)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    /**
     * Parses an XMLTV document. Programme channelKey = the programme's raw
     * `channel` attribute.
     */
    fun parse(input: InputStream): Result {
        val channels = mutableListOf<EpgChannel>()
        val programmes = mutableListOf<EpgProgramme>()

        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }

        var channelId: String? = null
        var channelName: String? = null
        var channelLogo: String? = null

        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var progTitle: String? = null
        var progDesc: String? = null

        var textTarget: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        channelName = null
                        channelLogo = null
                    }
                    "display-name" -> textTarget = "name"
                    "icon" -> if (channelId != null) {
                        channelLogo = parser.getAttributeValue(null, "src")
                    }
                    "programme" -> {
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = parseTime(parser.getAttributeValue(null, "start"))
                        progStop = parseTime(parser.getAttributeValue(null, "stop"))
                        progTitle = null
                        progDesc = null
                    }
                    "title" -> textTarget = "title"
                    "desc" -> textTarget = "desc"
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text ?: ""
                    if (t.isNotBlank()) when (textTarget) {
                        "name" -> channelName = (channelName ?: "") + t
                        "title" -> progTitle = (progTitle ?: "") + t
                        "desc" -> progDesc = (progDesc ?: "") + t
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        val id = channelId
                        if (id != null) {
                            channels.add(
                                EpgChannel(
                                    id = id,
                                    name = channelName?.trim().orEmpty().ifBlank { id },
                                    logo = channelLogo
                                )
                            )
                        }
                        channelId = null
                        textTarget = null
                    }
                    "programme" -> {
                        val key = progChannel
                        if (key != null) {
                            programmes.add(
                                EpgProgramme(
                                    channelKey = key,
                                    startUtc = progStart,
                                    stopUtc = progStop,
                                    title = progTitle?.trim().orEmpty(),
                                    desc = progDesc?.trim()?.ifBlank { null }
                                )
                            )
                        }
                        progChannel = null
                        textTarget = null
                    }
                    "display-name", "title", "desc" -> textTarget = null
                }
            }
            event = parser.next()
        }
        return Result(channels, programmes)
    }
}
