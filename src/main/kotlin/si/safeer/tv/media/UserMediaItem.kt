package si.safeer.tv.media

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "user_media")
data class UserMediaItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: MediaType,
    val streamUrl: String,
    val sourceName: String? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    /** CSV ključi, npr. ACTION,THRILLER; podpira tudi uporabnikove oznake. */
    val genres: String = Genre.OTHER.name,
    val mimeType: String? = null,
    val licenseUrl: String? = null,
    val licenseHeadersJson: String? = null,
    val encrypted: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun genreKeys(): List<String> = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .distinct().ifEmpty { listOf(Genre.OTHER.name) }

    fun genreLabels(): String = genreKeys().joinToString(" · ") { Genre.label(it) }

    fun isVideo(): Boolean = when (type) {
        MediaType.RADIO -> false
        MediaType.MOVIE, MediaType.SERIES, MediaType.LIVE -> true
        MediaType.LOCAL, MediaType.OTHER -> mimeType?.startsWith("video/") == true ||
            Regex("(?i)\\.(mp4|mkv|webm|mov|m4v|mpd|m3u8)(?:[?#].*)?$").containsMatchIn(streamUrl)
    }
}
