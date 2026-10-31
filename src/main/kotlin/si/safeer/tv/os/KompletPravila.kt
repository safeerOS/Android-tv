package si.safeer.tv.os

import org.json.JSONObject

object KompletPravila {
    data class Vnos(val url: String, val sha256: String)

    /** Samo okus os na TV vrne manjkajoce iz [Sosed.BRSKALNIK, Sosed.TRGOVINA] v tem vrstnem redu; drugje prazno. */
    fun manjkajoci(jeTv: Boolean, smoOs: Boolean, namesceni: Set<String>): List<String> {
        if (!jeTv || !smoOs) return emptyList()
        // Uporabimo fiksne nize, da se izognemo odvisnosti od Androidovega Sosed.kt v JVM preizkusu
        val potrebni = listOf("si.safeer.tv", "si.safeer.trgovina")
        return potrebni.filter { it !in namesceni }
    }

    /**
     * null, ce kateremu paketu manjka veljaven vnos -> takrat NE namescamo nic (komplet je cel ali nic).
     * Pravilo dela z navadnimi nizi in lastnim preverjanjem (https, sha256 dolzine 64), brez odvisnosti od Androida.
     */
    fun vnosi(manifest: JSONObject, paketi: List<String>): Map<String, Vnos>? {
        val rezultat = mutableMapOf<String, Vnos>()
        for (paket in paketi) {
            val vnosJson = manifest.optJSONObject("android.$paket") ?: return null
            val u = vnosJson.optString("url")
            val s = vnosJson.optString("sha256")
            if (!u.startsWith("https://") || s.length != 64) return null
            rezultat[paket] = Vnos(u, s.lowercase())
        }
        return rezultat
    }
}
