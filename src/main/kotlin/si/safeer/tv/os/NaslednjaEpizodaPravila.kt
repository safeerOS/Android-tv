package si.safeer.tv.os

/**
 * Lastnik 5. 11. 2026: »naslednja epizoda iste serije naj se zacne sama, ko je konec epizode«. Ciste funkcije (preizkus
 * tests/NaslednjaEpizodaPravilaTest.kt). Kartica je v PredvajanjeActivity, izbira toka v GlasbaActivity po isti poti kot
 * »Nadaljuj« (ista pravila in filter, ki jih je izbral uporabnik). Dodatki Stremio ne povedo, kdaj se zacne odjavna
 * spica, zato se kartica pokaze v zadnjih [ODSTEVANJE_MS] posnetka oziroma ob koncu.
 */
object NaslednjaEpizodaPravila {
    /** Kartica odsteva toliko, preden se naslednja epizoda zacne sama. */
    const val ODSTEVANJE_MS = 10_000L

    /** Seznam epizod vprasamo dodatek minuto pred koncem: ob koncu je kartica takoj pripravljena. */
    const val ISCI_PRED_KONCEM_MS = 60_000L

    /** Krajsi posnetek (napovednik, povzetek) kartice pred koncem nima - samo ob koncu. */
    const val NAJMANJ_TRAJANJA_MS = 5 * 60_000L

    /** Po koncu epizode zaslon na seznam epizod caka najvec toliko, nato se zapre kot prej. */
    const val CAKAJ_SEZNAM_MS = 20_000L

    /**
     * Odlocitev 5. 11. 2026: po toliko samodejnih epizodah zapored brez tipke daljinca vprasamo »Se gledas?« in ne
     * odstevamo (TV ne predvaja celo noc; tako delajo tudi drugi predvajalniki).
     */
    const val NAJVEC_ZAPOREDNIH = 3

    /** Vrsta kartice. */
    const val NIC = 0
    const val ODSTEVAJ = 1
    const val VPRASAJ = 2

    /** Posnetek je epizoda serije (video, ne v zivo, ima stevilko epizode). */
    fun jeEpizoda(video: Boolean, vZivo: Boolean, epizoda: Int): Boolean = video && !vZivo && epizoda >= 1

    /** Cas, da dodatek vprasamo za seznam epizod (zadnja minuta dovolj dolgega posnetka). */
    fun iskati(trajanjeMs: Long, polozajMs: Long): Boolean =
        trajanjeMs >= NAJMANJ_TRAJANJA_MS && trajanjeMs - polozajMs in 0..ISCI_PRED_KONCEM_MS

    /** Kartica: ob koncu posnetka ali v njegovih zadnjih [ODSTEVANJE_MS]. */
    fun pokazi(trajanjeMs: Long, polozajMs: Long, koncano: Boolean): Boolean =
        koncano || (trajanjeMs >= NAJMANJ_TRAJANJA_MS && trajanjeMs - polozajMs <= ODSTEVANJE_MS)

    /** Odstevanje ali vprasanje »Se gledas?« po [NAJVEC_ZAPOREDNIH] samodejnih epizodah; brez naslednje epizode nic. */
    fun vrsta(zaporednih: Int, imaNaslednjo: Boolean): Int = when {
        !imaNaslednjo -> NIC
        zaporednih >= NAJVEC_ZAPOREDNIH -> VPRASAJ
        else -> ODSTEVAJ
    }

    /** Stevec samodejnih epizod: samodejna +1, izbira uporabnika (gumb, tipka) ga postavi na nic. */
    fun zaporednihPo(prej: Int, samodejno: Boolean): Int = if (samodejno) prej + 1 else 0

    /** Odsteto po enem koraku; med pavzo odstevanje stoji. */
    fun odsteto(prejMs: Long, korakMs: Long, pavza: Boolean): Long = if (pavza) prejMs else prejMs + korakMs

    /** Sekund do samodejnega zacetka (zaokrozeno navzgor, za napis »cez 10 s«). */
    fun preostaloSekund(odstetoMs: Long): Int = (((ODSTEVANJE_MS - odstetoMs).coerceIn(0L, ODSTEVANJE_MS) + 999L) / 1000L).toInt()

    fun zacniZdaj(odstetoMs: Long): Boolean = odstetoMs >= ODSTEVANJE_MS

    /** »S1E2 Ime« za kartico. */
    fun oznaka(sezona: Int, epizoda: Int, ime: String): String =
        "S${sezona}E$epizoda" + (if (ime.isNotBlank()) " " + ime.trim() else "")

    /** Naslov nove epizode v isti obliki kot seznam epizod in »Nadaljuj« (»Serija · S1E2 · ime«). */
    fun naslovEpizode(naslovZdaj: String, sezona: Int, epizoda: Int, ime: String): String =
        OsPravila.naslovBrezEpizode(naslovZdaj) + " · S${sezona}E$epizoda" + (if (ime.isNotBlank()) " · " + ime.trim() else "")
}
