package si.safeer.tv

/**
 * Oblika in tocka klika navideznega kazalca (Ai Lina tv browser, lastnik 15. 11. 2026): kazalec je PUSCICA kot miskin, ne krog.
 * Cista pravila brez Androida; risanje je v [VirtualPointerView], ki jih samo uporabi. Preizkus: tests/KazalecOblikaTest.kt.
 *
 * Tocka klika (hotspot) je KONICA puscice: koordinate dotika, lebdenje in izbira pogleda pod kazalcem so polozaj konice, ne sredina
 * slike (pri krogu je bila sredina). [slikaIzTocke] in [tockaKlika] sta si inverzni funkciji, zato risanje in dotik ne moreta
 * razhajati.
 */
object KazalecOblika {
    enum class Oblika { PUSCICA, KROG }
    enum class Stanje { NAVADEN, ZGRABLJEN, KLIK }

    /** Privzeta oblika (prej krog). */
    val PRIVZETA = Oblika.PUSCICA

    /**
     * Velikost puscice v tockah zaslona 1920x1080 (960 dp, gostota 2): visina 44 px = 22 dp, sirina ~28 px = 14 dp. Sedanji krog ima
     * zunanji premer 2 * 18 + obris 3 = 39 px (19,5 dp): puscica je visja (44 >= 39) in z obrisom berljiva z razdalje televizorja.
     * Meritev na TV (videz z razdalje) NEPREVERJENA.
     */
    const val VISINA_PX = 44f
    const val SIRINA_PX = 28f
    /** Zunanji premer sedanjega kroga (obroc 18 + 3 obris), ki ga puscica ne sme biti manjsa od. */
    const val PREMER_KROGA_PX = 39f

    /** Trajanje kratkega povratnega signala ob kliku (ms). */
    const val KLIK_MS = 140L

    /**
     * Konture puscice, konica v (0, 0), telo desno in navzdol (kot miskin kazalec 12x19, povecan na [VISINA_PX]): pari x, y.
     * Samo ena predpripravljena pot v onDraw, brez senc in zameglitve.
     */
    fun tocke(): FloatArray {
        val enota = VISINA_PX / 19f
        val osnova = floatArrayOf(0f, 0f, 0f, 16f, 4f, 12f, 7f, 19f, 10f, 18f, 7f, 11f, 12f, 11f)
        return FloatArray(osnova.size) { osnova[it] * enota }
    }

    /** Polozaj zgornjega levega kota slike za tocko klika; PUSCICA: konica je zgornji levi kot, KROG: sredina. */
    fun slikaIzTocke(klikX: Float, klikY: Float, oblika: Oblika): Pair<Float, Float> = when (oblika) {
        Oblika.PUSCICA -> Pair(klikX, klikY)
        Oblika.KROG -> Pair(klikX - PREMER_KROGA_PX / 2f, klikY - PREMER_KROGA_PX / 2f)
    }

    /** Tocka klika (koordinate dotika, lebdenja, izbire pogleda) iz polozaja zgornjega levega kota slike. */
    fun tockaKlika(slikaX: Float, slikaY: Float, oblika: Oblika): Pair<Float, Float> = when (oblika) {
        Oblika.PUSCICA -> Pair(slikaX, slikaY)
        Oblika.KROG -> Pair(slikaX + PREMER_KROGA_PX / 2f, slikaY + PREMER_KROGA_PX / 2f)
    }

    /** Konica je lahko natancno na robu zaslona: omejitev [0, velikost - 1], brez prejsnjih 10 px odmika. */
    fun omejiKonico(v: Float, velikost: Int): Float = if (velikost <= 0) 0f else v.coerceIn(0f, (velikost - 1).toFloat())

    /** Zrcaljenje telesa puscice, da ostane vidno ob desnem in spodnjem robu (konica ostane na mestu klika). */
    data class Obrat(val vodoravno: Boolean, val navpicno: Boolean)

    fun obrat(klikX: Float, klikY: Float, sirinaZaslona: Int, visinaZaslona: Int, merilo: Float = 1f): Obrat = Obrat(
        vodoravno = klikX + SIRINA_PX * merilo > sirinaZaslona,
        navpicno = klikY + VISINA_PX * merilo > visinaZaslona,
    )

    /** Stanje: med vlecenjem ZGRABLJEN, kratek cas po kliku KLIK, sicer NAVADEN. */
    fun stanje(vlecem: Boolean, klikDoMs: Long, zdajMs: Long): Stanje = when {
        vlecem -> Stanje.ZGRABLJEN
        zdajMs < klikDoMs -> Stanje.KLIK
        else -> Stanje.NAVADEN
    }

    /** Izgled stanja: merilo, polnitev in obris (ARGB). Navaden: bela polnitev + temen obris; zgrabljen: vecji in cian; klik: manjsi in rumen. */
    data class Izgled(val merilo: Float, val polnitev: Int, val obris: Int)

    private const val TEMEN_OBRIS = 0xFF101820.toInt()

    fun izgled(s: Stanje): Izgled = when (s) {
        Stanje.NAVADEN -> Izgled(1f, 0xFFFFFFFF.toInt(), TEMEN_OBRIS)
        Stanje.ZGRABLJEN -> Izgled(1.25f, 0xFF00E5FF.toInt(), TEMEN_OBRIS)
        Stanje.KLIK -> Izgled(0.8f, 0xFFFFD54F.toInt(), TEMEN_OBRIS)
    }

    /** Relativna svetlost barve (WCAG), 0..1. */
    fun svetlost(argb: Int): Double {
        fun kanal(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * kanal((argb shr 16) and 0xFF) + 0.7152 * kanal((argb shr 8) and 0xFF) + 0.0722 * kanal(argb and 0xFF)
    }

    /** Kontrast polnitve proti obrisu (WCAG), >= 3 je za grafiko dovolj. */
    fun kontrast(a: Int, b: Int): Double {
        val x = svetlost(a); val y = svetlost(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
