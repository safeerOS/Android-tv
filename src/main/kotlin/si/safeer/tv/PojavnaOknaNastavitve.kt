package si.safeer.tv

import android.content.Context

/**
 * 🚫 Preprecevanje pojavnih oken.
 *
 * Privzeto vklopljeno: en klik na predvajalnik naj predvaja film, ne odpira zavihka z oglasom.
 * Kdor novo okno res potrebuje (redke strani ga uporabljajo za prijavo), to v meniju izklopi.
 * Ista nastavitev velja na televizorju, telefonu in racunalniku.
 */
object PojavnaOknaNastavitve {
    private const val PREFS = "safeer_ui_prefs"
    private const val KEY = "popup_block_enabled"

    fun jeVklopljeno(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun nastavi(context: Context, vklopljeno: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, vklopljeno).apply()
    }
}
