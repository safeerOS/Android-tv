package si.safeer.tv

/**
 * Kdaj brskalnik, ki ni na zaslonu, vrne pomnilnik (zavihki in izrisovalnik WebView). Brez Androida, preizkus v
 * tests/SpanjePravilaTest.kt.
 *
 * Izmerjeno na TV 3. 11. 2026 (Android 11, 2 GB RAM-a): brskalnik po odhodu na Domov ali po izhodu z Nazaj drzi
 * 200-270 MB PSS in izrisovalnik (sandboxed_process0) se 65 MB, tudi po 56 minutah. Aktivni zavihek je prej zaspal sele
 * po 10 minutah, izrisovalnik pa ni koncal nikoli (Chromium ga drzi do konca procesa, naloga 64).
 */
object SpanjePravila {
    /** Televizor: uporabnik se v pol minute vrne brez ponovnega nalaganja; dlje ga ne drzimo, ker steje vsak MB. */
    const val TV_MS = 30_000L
    const val TV_MALO_RAMA_MS = 5_000L
    /** Tablica in telefon imata vec pomnilnika in uporabnik pogosto preklaplja med aplikacijami. */
    const val DOTIK_MS = 10 * 60_000L
    const val DOTIK_MALO_RAMA_MS = 20_000L
    /** Neoddan obrazec odlozi spanje najvec za toliko; prej je ob iskalnem polju odlagal v nedogled. */
    const val OBRAZEC_TV_MS = 10 * 60_000L
    const val OBRAZEC_DOTIK_MS = 30 * 60_000L

    /** Cez koliko ms po odhodu z zaslona zaspi tudi aktivni zavihek; null = ne zaspi (namerni zvok v ozadju). */
    fun zamikAktivnega(naDotik: Boolean, maloRama: Boolean, zvokVOzadju: Boolean): Long? = when {
        zvokVOzadju -> null
        naDotik -> if (maloRama) DOTIK_MALO_RAMA_MS else DOTIK_MS
        else -> if (maloRama) TV_MALO_RAMA_MS else TV_MS
    }

    /** Ali zavihek zdaj zaspi: ki predvaja, nikoli; neoddan obrazec spanje odlozi, a najvec do roka. */
    fun zaspi(predvaja: Boolean, umazanObrazec: Boolean, odlozenoMs: Long, naDotik: Boolean): Boolean =
        !predvaja && (!umazanObrazec || odlozenoMs >= if (naDotik) OBRAZEC_DOTIK_MS else OBRAZEC_TV_MS)

    /**
     * Izrisovalnik koncamo, ko brskalnika ni na zaslonu (ali je zaprt) in nima nobenega zivega zavihka. Ali ga rabi se
     * kak drug WebView procesa (okno Linka, lahki predvajalnik), preveri LahkiWebViewPool.
     */
    fun koncajIzrisovalnik(naZaslonu: Boolean, zivihZavihkov: Int): Boolean = !naZaslonu && zivihZavihkov == 0
}
