package si.safeer.tv.media

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class UserMediaRepository(context: Context) {
    private val dao = UserMediaDatabase.get(context).userMediaDao()
    private val executor = Executors.newSingleThreadExecutor()

    fun filter(type: MediaType?, genre: String?, done: (List<UserMediaItem>) -> Unit) =
        executor.execute { done(dao.filter(type, genre)) }

    fun insert(item: UserMediaItem, done: (List<UserMediaItem>) -> Unit = {}) = executor.execute {
        dao.insert(item); done(listOf(item))
    }

    fun delete(item: UserMediaItem, done: () -> Unit = {}) = executor.execute { dao.delete(item); done() }

    /** Neposreden tok shrani kot en element; M3U razširi v posamezne kanale. */
    fun importUrl(item: UserMediaItem, done: (Result<List<UserMediaItem>>) -> Unit) = executor.execute {
        done(runCatching {
            val isPlaylist = item.streamUrl.substringBefore('?').lowercase().let { it.endsWith(".m3u") || it.endsWith(".m3u8") }
            if (!isPlaylist || item.streamUrl.lowercase().contains(".m3u8")) {
                dao.insert(item); listOf(item)
            } else {
                val text = downloadPlaylist(item.streamUrl)
                val entries = M3uParser.parse(text, item.streamUrl)
                require(entries.isNotEmpty()) { "Prazen seznam M3U" }
                val imported = entries.map { entry ->
                    item.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        title = entry.title,
                        streamUrl = entry.url,
                        sourceName = item.sourceName ?: item.title,
                        posterUrl = entry.logo ?: item.posterUrl,
                        genres = (item.genreKeys() + listOfNotNull(entry.group?.let { Genre.customKey(it) }?.ifBlank { null }))
                            .distinct().joinToString(","),
                        addedAt = System.currentTimeMillis()
                    )
                }
                dao.insertAll(imported); imported
            }
        })
    }

    fun importM3uText(template: UserMediaItem, text: String, baseUrl: String?, done: (Result<List<UserMediaItem>>) -> Unit) = executor.execute {
        done(runCatching {
            val entries = M3uParser.parse(text, baseUrl)
            require(entries.isNotEmpty()) { "Prazen seznam M3U" }
            val imported = entries.map { entry -> template.copy(
                id = java.util.UUID.randomUUID().toString(), title = entry.title, streamUrl = entry.url,
                sourceName = template.sourceName ?: template.title, posterUrl = entry.logo ?: template.posterUrl,
                genres = (template.genreKeys() + listOfNotNull(entry.group?.let { Genre.customKey(it) }?.ifBlank { null }))
                    .distinct().joinToString(","),
                addedAt = System.currentTimeMillis()) }
            dao.insertAll(imported); imported
        })
    }

    private fun downloadPlaylist(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "SafeerOS/Media")
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            require((connection.contentLengthLong.takeIf { it >= 0 } ?: 0L) <= MAX_PLAYLIST_BYTES) { "Seznam je prevelik" }
            return connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                    require(out.length <= MAX_PLAYLIST_BYTES) { "Seznam je prevelik" }
                }
                out.toString()
            }
        } finally { connection.disconnect() }
    }

    fun close() = executor.shutdownNow()

    companion object { private const val MAX_PLAYLIST_BYTES = 1_000_000L }
}
