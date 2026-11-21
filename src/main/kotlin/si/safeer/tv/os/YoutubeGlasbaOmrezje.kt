package si.safeer.tv.os

import org.json.JSONObject

/** Omrezni del kataloga YouTube Music (klic z delovne niti); razclenjevanje: [YoutubeGlasba]. */
object YoutubeGlasbaOmrezje {
    private fun objekt(s: String?): JSONObject? = s?.let { try { JSONObject(it) } catch (_: Exception) { null } }

    fun isci(q: String): List<YoutubeGlasba.Element> {
        if (q.isBlank()) return emptyList()
        return objekt(UvozSeznama.beri("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", youtube = true, telo = YoutubeGlasba.zahtevaIskanja(q)))
            ?.let { YoutubeGlasba.razclenIskanje(it) } ?: emptyList()
    }

    fun album(id: String): YoutubeGlasba.Album? {
        if (!YoutubeGlasba.jeIdAlbuma(id)) return null
        return objekt(UvozSeznama.beri("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", youtube = true, telo = YoutubeGlasba.zahtevaAlbuma(id)))
            ?.let { YoutubeGlasba.razclenAlbum(it, id) }
    }
}
