package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Context

/**
 * Identiteta in shramba Safeer OS v Linku.
 *
 * Id je "sorodnik" sredisca na tem televizorju (`<id sredisca>-os`), kot je Safeer Control sorodnik
 * brskalnika na racunalniku. Poverilnice (zeton, odtis) so v zasebnih nastavitvah aplikacije.
 */
object Identiteta {
    private const val PREFS = "safeer_os_link"

    fun id(context: Context): String =
        "tv-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase() + "-os"

    fun beri(context: Context): Sorodnik.Poverilnice? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hub = p.getString("hub_url", null) ?: return null
        val zeton = p.getString("token", null) ?: return null
        val odtis = p.getString("fp", null) ?: return null
        return Sorodnik.Poverilnice(hub, zeton, odtis, p.getString("hub_id", "") ?: "")
    }

    fun shrani(context: Context, p: Sorodnik.Poverilnice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("hub_url", p.hubUrl).putString("token", p.zeton).putString("fp", p.odtis).putString("hub_id", p.hubId)
            .apply()
    }

    fun pozabi(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
