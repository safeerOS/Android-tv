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
     * Vnosi za pakete, ki jih razlicice.json ponuja. Oblika je gnezdena kot v Posodobitve.preveri:
     * {"android": {"si.safeer.tv": {url, sha256, ...}}}. Tedenski pregled 4. 11. 2026 (S2): prej je pravilo iskalo ploski
     * kljuc "android.si.safeer.tv", ki ga ni, in komplet se ni namestil nikoli; preizkus je tekel na izmisljeni obliki.
     * Paketa, ki ga v razlicice.json ni (si.safeer.trgovina 4. 11. 2026), ne ponudimo. null, ce je kateri ponujeni vnos
     * pokvarjen (ne https, sha256 ni 64 znakov) -> takrat NE namescamo nic.
     */
    fun vnosi(manifest: JSONObject, paketi: List<String>): Map<String, Vnos>? {
        val rezultat = mutableMapOf<String, Vnos>()
        val android = manifest.optJSONObject("android") ?: return emptyMap()
        for (paket in paketi) {
            val vnosJson = android.optJSONObject(paket) ?: continue
            val u = vnosJson.optString("url")
            val s = vnosJson.optString("sha256")
            if (!u.startsWith("https://") || s.length != 64) return null
            rezultat[paket] = Vnos(u, s.lowercase())
        }
        return rezultat
    }
}
