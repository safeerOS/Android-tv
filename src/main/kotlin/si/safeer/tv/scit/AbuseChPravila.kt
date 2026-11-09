package si.safeer.tv.scit

/** Ciste funkcije (preizkus v JVM). */
object AbuseChPravila {
    /** Kljuc abuse.ch: crke in stevke, brez presledkov - samo tako gre varno v pot naslova. */
    fun veljavnaOblika(k: String): Boolean = k.length in 20..128 && k.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /** Vnos z daljincem ali prilepljen: presledki, narekovaji in nevidni znaki stran (iz e-poste ali strani). */
    fun ocisti(vnos: String): String = vnos.filterNot { it.isWhitespace() || it == '"' || it == '\'' || it == '\u200B' }

    enum class Preverba { VELJA, NI_VELJAVEN, OBLIKA, NI_ODGOVORA }

    /** Izid preverbe po kodi HTTP odgovora na prenos seznama s kljucem. */
    fun izKode(koda: Int): Preverba = when (koda) {
        200, 304 -> Preverba.VELJA
        401, 403 -> Preverba.NI_VELJAVEN
        else -> Preverba.NI_ODGOVORA
    }
}
