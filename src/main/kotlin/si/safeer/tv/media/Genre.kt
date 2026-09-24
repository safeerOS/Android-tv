package si.safeer.tv.media

import android.content.Context
import si.safeer.tv.R
import java.util.Locale

/** Prednastavljene zvrsti. V bazi lahko ostane tudi uporabnikova lastna oznaka. */
enum class Genre(val labelSl: String) {
    ACTION("Akcija"), COMEDY("Komedija"), DRAMA("Drama"), HORROR("Grozljivka"),
    THRILLER("Triler"), SCIFI("Znanstvena fantastika"), FANTASY("Fantazija"),
    DOCUMENTARY("Dokumentarec"), ANIMATION("Animacija"), CRIME("Kriminalka"),
    ROMANCE("Romantika"), FAMILY("Družinski"), OTHER("Drugo");

    companion object {
        fun label(key: String): String = entries.firstOrNull { it.name == key }?.labelSl
            ?: key.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

        fun customKey(value: String): String = value.trim().uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9ČŠŽ]+"), "_").trim('_')
    }

    fun label(context: Context): String = context.getString(when (this) {
        ACTION -> R.string.os_user_media_action
        COMEDY -> R.string.os_user_media_comedy
        DRAMA -> R.string.os_user_media_drama
        HORROR -> R.string.os_user_media_horror
        THRILLER -> R.string.os_user_media_thriller
        SCIFI -> R.string.os_user_media_scifi
        FANTASY -> R.string.os_user_media_fantasy
        DOCUMENTARY -> R.string.os_user_media_documentary
        ANIMATION -> R.string.os_user_media_animation
        CRIME -> R.string.os_user_media_crime
        ROMANCE -> R.string.os_user_media_romance
        FAMILY -> R.string.os_user_media_family
        OTHER -> R.string.os_user_media_other
    })
}
