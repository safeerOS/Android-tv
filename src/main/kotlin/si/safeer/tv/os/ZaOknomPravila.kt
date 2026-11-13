package si.safeer.tv.os

/**
 * Prenos v zivo, ki ga predvajalnik prehiti (BEHIND_LIVE_WINDOW): vrnitev na zivo je pravilna, a brez meje se lahko vrti
 * v neskoncnost (pregled 5. 11. 2026: »brez stevca«). Najvec [MEJA] vrnitev v [OKNO_MS] za isti posnetek; nato velja
 * obicajna pot napake (rezerva ali sporocilo). Cista logika.
 */
object ZaOknomPravila {
    const val MEJA = 3
    const val OKNO_MS = 60_000L

    /** Novi casi vrnitev za posnetek [id]: stari casi drugega posnetka ali zunaj okna odpadejo. Null = meja dosezena. */
    fun dovoli(prejsnjiId: String, casi: List<Long>, id: String, zdaj: Long): List<Long>? {
        val veljavni = if (prejsnjiId == id) casi.filter { zdaj - it in 0 until OKNO_MS } else emptyList()
        if (veljavni.size >= MEJA) return null
        return veljavni + zdaj
    }
}
