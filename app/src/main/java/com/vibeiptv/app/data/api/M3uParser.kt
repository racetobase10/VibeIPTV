package com.vibeiptv.app.data.api

data class M3uEntry(
    val name: String,
    val tvgId: String?,
    val tvgLogo: String?,
    val groupTitle: String?,
    val url: String
)

object M3uParser {

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    /**
     * Parses an extended M3U playlist. Each entry looks like:
     *   #EXTINF:-1 tvg-id="x" tvg-logo="y" group-title="Sports",Channel Name
     *   http://example.com/stream.ts
     * The display name is the text after the last comma; the URL is the next
     * non-empty line that does not start with '#'.
     */
    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = attrRegex.findAll(line)
                    .associate { it.groupValues[1] to it.groupValues[2] }
                val name = line.substringAfterLast(",").trim().ifEmpty { "Unknown" }

                var url: String? = null
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j]
                    if (!next.startsWith("#")) {
                        url = next
                        break
                    }
                    j++
                }
                if (url != null) {
                    entries.add(
                        M3uEntry(
                            name = name,
                            tvgId = attrs["tvg-id"]?.ifBlank { null },
                            tvgLogo = attrs["tvg-logo"]?.ifBlank { null },
                            groupTitle = attrs["group-title"]?.ifBlank { null },
                            url = url
                        )
                    )
                    i = j + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return entries
    }
}
