package si.safeer.tv.media

/** Majhen, omejen razčlenjevalnik M3U; ne izvaja ukazov in ne sledi ugnezdenim seznamom. */
object M3uParser {
    data class Entry(val title: String, val url: String, val group: String?, val logo: String?)

    fun parse(text: String, baseUrl: String? = null, limit: Int = 500): List<Entry> {
        val result = mutableListOf<Entry>()
        var title: String? = null
        var group: String? = null
        var logo: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                title = line.substringAfter(',', "").trim().ifBlank { null }
                group = attribute(line, "group-title")
                logo = attribute(line, "tvg-logo")
            } else if (line.isNotBlank() && !line.startsWith('#')) {
                val resolved = resolve(baseUrl, line)
                if (resolved == null) {
                    title = null; group = null; logo = null
                    continue
                }
                result += Entry(title ?: resolved.substringAfterLast('/').substringBefore('?').ifBlank { "Kanal" }, resolved, group, logo)
                title = null; group = null; logo = null
                if (result.size >= limit) break
            }
        }
        return result.distinctBy { it.url }
    }

    private fun attribute(line: String, name: String): String? =
        Regex("(?:^|\\s)${Regex.escape(name)}=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    private fun resolve(base: String?, value: String): String? = try {
        val uri = java.net.URI(value)
        when {
            uri.isAbsolute -> value
            base != null -> java.net.URI(base).resolve(uri).toString()
            else -> null
        }
    } catch (_: Exception) { null }
}
