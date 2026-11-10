package si.safeer.tv.link

/**
 * Meja socasnih povezav streznika datotek te naprave (DatotekeStreznik) - cista odlocitev brez Androida
 * (tests/StreznikMejaTest.kt).
 *
 * Pregled 10. 11. 2026 (3.4): streznik je za VSAKO sprejeto povezavo ustvaril nit, se preden je preveril zeton, in na
 * vsako vrstico zahteve cakal 15 s brez skupnega roka. Kdorkoli v omrezju je z odprtimi povezavami (brez zetona) porabil
 * niti in pomnilnik aplikacije. Zdaj: najvec [skupaj] povezav hkrati in [naNaslov] z enega naslova, presezek zapremo
 * takoj (brez niti), zahteva (vrstica in glave) mora priti v [ROK_ZAHTEVE_MS].
 */
class StreznikMeja(private val skupaj: Int = NAJVEC_POVEZAV, private val naNaslov: Int = NAJVEC_NA_NASLOV) {
    private val poNaslovu = HashMap<String, Int>()
    private var vseh = 0

    /** Stevilo trenutno odprtih povezav. */
    val odprtih: Int @Synchronized get() = vseh

    /** Ali novo povezavo z [naslov] sprejmemo; true jo ze steje - klicatelj jo ob koncu sprosti s [sprosti]. */
    @Synchronized
    fun sprejmi(naslov: String): Boolean {
        if (vseh >= skupaj) return false
        val njegovih = poNaslovu[naslov] ?: 0
        if (njegovih >= naNaslov) return false
        poNaslovu[naslov] = njegovih + 1
        vseh++
        return true
    }

    /** Povezava z [naslov], ki jo je [sprejmi] stel, je koncana. */
    @Synchronized
    fun sprosti(naslov: String) {
        val njegovih = poNaslovu[naslov] ?: return
        if (njegovih <= 1) poNaslovu.remove(naslov) else poNaslovu[naslov] = njegovih - 1
        if (vseh > 0) vseh--
    }

    companion object {
        /** Predvajalnik odpre nekaj povezav na video (obsegi), galerija slicice eno za drugo; 8 na napravo je dovolj. */
        const val NAJVEC_POVEZAV = 32
        const val NAJVEC_NA_NASLOV = 8
        /** Toliko casa ima odjemalec za rokovanje TLS, vrstico zahteve in glave skupaj. */
        const val ROK_ZAHTEVE_MS = 20_000L
        /** Najdaljse cakanje na en kos podatkov (kot prej). */
        const val ROK_BRANJA_MS = 15_000
        /** Glave zahteve: predvajalniki jih posljejo do deset; vec kot toliko ni zahteva nasega odjemalca. */
        const val NAJVEC_GLAV = 64

        /**
         * Koliko ms sme trajati naslednje branje zahteve, ki se je zacela ob [zacetekMs]: najvec [ROK_BRANJA_MS] in
         * najvec do skupnega roka. 0 = rok zahteve je potekel (povezavo zapremo).
         */
        fun preostanek(zacetekMs: Long, zdajMs: Long, rokMs: Long = ROK_ZAHTEVE_MS): Int {
            val ostane = rokMs - (zdajMs - zacetekMs)
            if (ostane <= 0L || zdajMs < zacetekMs) return 0
            return minOf(ostane, ROK_BRANJA_MS.toLong()).toInt()
        }
    }
}
