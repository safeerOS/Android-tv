package si.safeer.tv.os

/**
 * Cista pravila za zahtevo po kljucnem okvirju (IDR) pri prenosu zaslona na TV.
 * Preprecuje preplavo kljucnih okvirjev z eksponentnim zamikom ob zaporednih napakah
 * in tisino po uspesnem prikazu novega kljucnega okvirja.
 * Brez odvisnosti od Androida, da se da deterministicno preizkusiti na JVM.
 */
object ZaslonKljucniOkvir {
    /** Najkrajsi dovoljeni razmik med dvema samodejnima zahtevama v milisekundah. */
    const val NAJMANJ_RAZMIK_MS = 500L

    /** Najdaljsi razmik ob eksponentnem zamiku v milisekundah. */
    const val NAJVEC_RAZMIK_MS = 4000L

    /** Zacetni razmik med zahtevama v milisekundah. */
    const val ZACETNI_RAZMIK_MS = 500L

    /** Cas tisine po uspesnem kljucnem okvirju v milisekundah (za samodejne zahteve). */
    const val TISINA_PO_USPEHU_MS = 2000L

    /** Najkrajsi razmik za rocno zahtevo, da se prepreci podvajanje klikov. */
    private const val ROCNO_NAJMANJ_RAZMIK_MS = 100L

    /** Razlog za zahtevo po novem kljucnem okvirju. */
    enum class Razlog {
        NAPAKA_DEKODIRNIKA,
        IZGUBA_PAKETA,
        ZASTOJ_SLIKE,
        ROCNO,
    }

    /** Izid presoje: ali naj se zahteva poslje takoj in koliko milisekund je treba sicer cakati. */
    data class Odlocitev(val poslji: Boolean, val cakajMs: Long)

    /**
     * Stanjski presojevalec zahtev po kljucnem okvirju.
     * Cas [zdajMs] je vedno podan kot parameter zaradi deterministicnosti.
     */
    class Presojevalec {
        private var zadnjaZahtevaMs: Long? = null
        private var zadnjiUspehMs: Long? = null
        private var zadnjiZnaniCasMs: Long? = null
        private var trenutniRazmikMs: Long = ZACETNI_RAZMIK_MS
        private var stevecZaporednih: Int = 0

        /** Stevilo zaporednih zahtev od zadnjega uspeha oziroma zacetka. */
        val zaporednihNeuspelih: Int
            get() = stevecZaporednih

        /**
         * Presodi, ali je dovoljeno poslati zahtevo za kljucni okvir ob casu [zdajMs].
         * Ce gre [zdajMs] nazaj v casu, se obravnava kot zadnji znani cas.
         */
        fun presodi(razlog: Razlog, zdajMs: Long): Odlocitev {
            val cas = uskladiCas(zdajMs)
            val zadnja = zadnjaZahtevaMs

            if (razlog == Razlog.ROCNO) {
                val preostalo = preostaliCas(cas, zadnja, ROCNO_NAJMANJ_RAZMIK_MS)
                if (preostalo > 0L) {
                    return Odlocitev(poslji = false, cakajMs = preostalo)
                }
                zabeleziPoslanoZahtevo(cas)
                return Odlocitev(poslji = true, cakajMs = 0L)
            }

            val preostaloOdZahteve = preostaliCas(cas, zadnja, trenutniRazmikMs)
            val preostaloOdUspeha = preostaliCas(cas, zadnjiUspehMs, TISINA_PO_USPEHU_MS)
            val preostalo = maxOf(preostaloOdZahteve, preostaloOdUspeha)
            if (preostalo > 0L) {
                return Odlocitev(poslji = false, cakajMs = preostalo)
            }

            zabeleziPoslanoZahtevo(cas)
            return Odlocitev(poslji = true, cakajMs = 0L)
        }

        /**
         * Javi, da je prispel in bil prikazan nov kljucni okvir ob casu [zdajMs].
         * Ponastavi razmik na [ZACETNI_RAZMIK_MS], stevec zaporednih na 0 in sprozi
         * obdobje tisine [TISINA_PO_USPEHU_MS] za samodejne zahteve.
         */
        fun uspeh(zdajMs: Long) {
            val cas = uskladiCas(zdajMs)
            trenutniRazmikMs = ZACETNI_RAZMIK_MS.coerceIn(NAJMANJ_RAZMIK_MS, NAJVEC_RAZMIK_MS)
            stevecZaporednih = 0
            zadnjiUspehMs = cas
        }

        /** Ponastavi celotno notranje stanje v zacetno stanje. */
        fun ponastavi() {
            zadnjaZahtevaMs = null
            zadnjiUspehMs = null
            zadnjiZnaniCasMs = null
            trenutniRazmikMs = ZACETNI_RAZMIK_MS.coerceIn(NAJMANJ_RAZMIK_MS, NAJVEC_RAZMIK_MS)
            stevecZaporednih = 0
        }

        private fun uskladiCas(zdajMs: Long): Long {
            val prejsnji = zadnjiZnaniCasMs
            val usklajen = if (prejsnji != null && zdajMs < prejsnji) prejsnji else zdajMs
            zadnjiZnaniCasMs = usklajen
            return usklajen
        }

        private fun preostaliCas(cas: Long, zacetek: Long?, razmikMs: Long): Long {
            if (zacetek == null) return 0L
            val minilo = cas - zacetek
            return if (minilo >= razmikMs) 0L else razmikMs - minilo
        }

        private fun zabeleziPoslanoZahtevo(cas: Long) {
            // Ce je bila prej ze poslana zahteva brez vmesnega uspeha, podvojimo razmik.
            if (stevecZaporednih > 0) {
                trenutniRazmikMs = (trenutniRazmikMs * 2L).coerceIn(NAJMANJ_RAZMIK_MS, NAJVEC_RAZMIK_MS)
            }
            stevecZaporednih++
            zadnjaZahtevaMs = cas
        }
    }
}
