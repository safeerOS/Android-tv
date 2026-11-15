package si.safeer.tv

/**
 * Lastnik 9. 11. 2026 (24ur.com): stran, ki ni narejena za TV, naj daljinec sam preklopi v kazalec (miska). Izbira lastnika:
 * privzeto kazalec, izjeme so strani za TV (nase strani, YouTube za TV, pregledovalnik PDF). Rocni preklop (rdeca/INFO,
 * gumb kazalca, drzan OK) si zapomni brskalnik in spletna aplikacija za gostitelja (en vir) - naslednjic stran odpre tako, kot jo je uporabnik zelel.
 */
object KazalecPravila {
    /** Gostitelj http(s) naslova (malo, brez pike na koncu) ali null. */
    fun gostitelj(naslov: String?): String? = try {
        val u = java.net.URI((naslov ?: "").trim())
        if (!u.scheme.equals("https", true) && !u.scheme.equals("http", true)) null
        else u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /**
     * Ali naj bo kazalec ob odprtju [naslov] vklopljen. [zapomnjeno] = uporabnikova izbira za gostitelja (null = ni je).
     * [zaTv] = stran je narejena za daljinec (nasa stran, YouTube za TV, pregledovalnik PDF). [miska] = stran brez daljinca
     * ne gre (Facebook ..., [si.safeer.tv.os.OsPravila.miskaZaAplikacijo]).
     * Lastnik 15. 11. 2026 (Ai Lina tv browser): »kazalec je ze zasnovan v tem brskalniku, samo izboljsati ga je treba; naj se
     * uporabi na strani, ki ni prilagojena za TV«. Privzeto je zato kazalec povsod, kjer stran NI narejena za TV; nase strani,
     * YouTube za TV in pregledovalnik PDF ostanejo na puscicah. Uporabnikova zapomnjena izbira za gostitelja ima se vedno
     * prednost (v obe smeri). Prej (9. 11. 2026 15:xx, RTV SLO »kot aplikacija«) so bile puscice privzete, ker je kazalec
     * do roba rabil ~40 pritiskov; to odpravi gibanje po slicicah s pospeskom in drsenjem ob robu ([KazalecGibanje]).
     * [miska] je ohranjen zaradi klicev: stran, ki jo je izbrala miska (Facebook ...), ni za TV, zato je ze zajeta.
     */
    @Suppress("UNUSED_PARAMETER")
    fun privzetoKazalec(jeTelevizor: Boolean, naslov: String, zaTv: Boolean, zapomnjeno: Boolean?, miska: Boolean = false): Boolean {
        if (!jeTelevizor) return false
        if (gostitelj(naslov) == null) return false   // nasa domaca stran (file://, about:), brez gostitelja
        zapomnjeno?.let { return it }
        return !zaTv
    }

    /**
     * Lastnik 11. 11. 2026: brskalnik se pri kazalcu vede kot spletna aplikacija - kazalec se NIKOLI ne skrije sam (prej je
     * rocno vklopljen kazalec v brskalniku po 30 s izginil, ceprav si ga je brskalnik zapomnil kot vklopljenega).
     * Skriva ga samo uporabnik (rdeci gumb, INFO, gumb kazalca).
     */
    fun samSkrij(): Boolean = false

    /** Premiki kazalca sporocajo strani lebdenje (mouseover/mousemove) v brskalniku in v spletni aplikaciji enako. */
    fun lebdenje(): Boolean = true

    /** Lebdenje najvec vsakih toliko ms med gibanjem in enkrat ob ustavitvi (Ai Lina nacrt B8, 2 GB TV). */
    const val LEBDENJE_MS = 100L

    /** Nadomestni faktor drsenja (tock na zarezo kolesca), ce ga sistem ne pove (ViewConfiguration.scaledVerticalScrollFactor). */
    const val PX_NA_ZAREZO = 64f

    /** Kljuc, ob katerem se privzeti kazalec preracuna: gostitelj, stran za ogled istega gostitelja loceno. */
    fun kljucStrani(gostitelj: String?, ogled: Boolean): String? = gostitelj?.let { if (ogled) "$it#ogled" else it }

    /**
     * Lastnik 9. 11. 2026 (24ur.com na TV »prevelika, tezko se premikas gor in dol«): pri 960 dp televizorja je vecina strani
     * v postavitvi za tablico (ogromne slike, glava in spodnja vrstica pokrijeta pol zaslona). Na TV dobi vsaka stran sirino
     * [PRIVZETA_SIRINA] kot na racunalniku in se pomanjsa na zaslon; na napravi z dotikom ne. Izrecna sirina (vir Medijskega
     * centra) ostane.
     */
    const val PRIVZETA_SIRINA = 1280

    fun sirinaStrani(izrecna: Int, jeTelevizor: Boolean): Int = when {
        izrecna > 0 -> izrecna
        jeTelevizor -> PRIVZETA_SIRINA
        else -> 0
    }

    /** Strani s svojo postavitvijo za TV ali mobilno identiteto (YouTube za TV, Googlova prijava, PDF) ostanejo kot so. */
    fun brezSirine(dotik: Boolean, youtubeZaTv: Boolean, androidIdentiteta: Boolean, pregledovalnik: Boolean, http: Boolean): Boolean =
        dotik || youtubeZaTv || androidIdentiteta || pregledovalnik || !http

    /** Zacetna povecava: namizna samo za navadne strani; Google (prijava) in izjeme 100, da ne ostane povecava prejsnje strani. */
    fun zacetnaPovecava(namiznaSirina: Int, brezSirine: Boolean, google: Boolean, namizna: Int): Int =
        if (namiznaSirina > 0 && !brezSirine && !google) namizna else 100

    /** Zarez kolesca miske na pritisk ob robu (lastnik 9. 11. 2026, 24ur.com); izmeri na TV po gradnji. */
    const val KORAK_KOLESCA = 3f

    /** Tipka gor gre v naslovno vrstico samo, ko je kazalec na samem vrhu (konica je lahko na robu 0, KazalecOblika.omejiKonico) in stran navzgor ne more. */
    fun vNaslovnoVrstico(kazalecY: Float, stranLahkoGor: Boolean): Boolean = kazalecY <= 10.5f && !stranLahkoGor
}
