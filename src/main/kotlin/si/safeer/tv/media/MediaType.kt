package si.safeer.tv.media

import android.content.Context
import si.safeer.tv.R

enum class MediaType {
    MOVIE, SERIES, LIVE, RADIO, LOCAL, OTHER;

    fun label(context: Context): String = context.getString(when (this) {
        MOVIE -> R.string.os_user_media_movies
        SERIES -> R.string.os_user_media_series
        LIVE -> R.string.os_user_media_live
        RADIO -> R.string.os_user_media_radio
        LOCAL -> R.string.os_user_media_local
        OTHER -> R.string.os_user_media_other
    })
}
