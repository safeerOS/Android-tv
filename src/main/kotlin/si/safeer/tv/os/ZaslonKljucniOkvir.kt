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

    /**
     * Mirovanje brez poslanih zahtev, po katerem se razmik in stevec zaporednih ob naslednji presoji
     * ponastavita (KLJUC1-7). Meja je stroga: ponastavitev velja sele, ko je od zadnje poslane
     * zahteve minilo VEC kot ta cas (pri tocno 8000 ms se se ne ponastavi).
     */
    const val MIROVANJE_ZA_PONASTAVITEV_MS = 2L * NAJVEC_RAZMIK_MS

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
     *
     * POGODBA ZA KLICATELJA (neodvisni pregled 22. 11. 2026, KLJUC1-1, 3, 5, 6, 11, 12):
     * - Cas mora biti MONOTONA ura (SystemClock.elapsedRealtime), nikoli stenska ura: cas, ki gre nazaj, se
     *   steje kot zadnji znani cas, en sam napacen cas v prihodnosti pa bi zamrznil presojevalca do konca seje.
     * - [uspeh] klici samo, ko je po poslani zahtevi res prikazan nov kljucni okvir (ne ob vsakem prikazanem IDR).
     * - Ob `poslji = false` mora klicatelj, ce je slika se pokvarjena, presoditi znova cez `cakajMs`.
     * - ROCNO ima lasten najkrajsi razmik 100 ms: tipko ali ukaz mora klicatelj sam omejiti na dejanske pritiske.
     * - [ponastavi] samo ob novi seji prenosa, ne ob vsaki ponovni inicializaciji dekodirnika (takoj sprosti zahtevo).
     * - En presojevalec na sejo prenosa (ne deljen med sejami). Metode so sinhronizirane, ker jih lahko
     *   kliceta nit dekodirnika in nit uporabniskega vmesnika.
     *
     * DOLOCENA PRAVILA (utrditev po pregledu, KLJUC1-6, 7, 8, 9, 10):
     * - Meje so vkljucne: zahteva gre, ko je od zadnje zahteve minilo >= trenutni razmik in od uspeha
     *   >= [TISINA_PO_USPEHU_MS] (pri 499 ms se caka 1 ms, pri 500 ms gre).
     * - Ce veljata razmik in tisina hkrati, je `cakajMs` vecji od obeh preostankov; ob `poslji = true` je 0.
     * - Po mirovanju > [MIROVANJE_ZA_PONASTAVITEV_MS] od zadnje poslane zahteve se ob naslednji presoji
     *   (samodejni ali ROCNO) razmik vrne na [ZACETNI_RAZMIK_MS] in stevec na 0. Tisina po uspehu ostane.
     * - Razlike casov so saturirane na obseg Long, zato skrajni casi (Long.MIN_VALUE, Long.MAX_VALUE)
     *   ne povzrocijo preliva, obida razmika ali negativnega `cakajMs`.
     */
    class Presojevalec {
        private var zadnjaZahtevaMs: Long? = null
        private var zadnjiUspehMs: Long? = null
        private var zadnjiZnaniCasMs: Long? = null
        private var trenutniRazmikMs: Long = ZACETNI_RAZMIK_MS
        private var stevecZaporednih: Int = 0

        /**
         * Stevilo zaporednih poslanih zahtev (samodejnih in ROCNO) brez vmesnega uspeha (KLJUC1-6).
         * - Poveca se za 1 natanko ob vsaki presoji z izidom `poslji = true`, tudi pri ROCNO.
         * - Zadrzana presoja (`poslji = false`) ga ne spremeni.
         * - Na 0 ga postavijo [uspeh], [ponastavi] in presoja po mirovanju > [MIROVANJE_ZA_PONASTAVITEV_MS]
         *   (ta ga postavi na 0, ce je nato zahteva poslana, je 1).
         * - Mirovanje se uposteva sele ob naslednji presoji, branje stevca samo po sebi stanja ne spremeni.
         * Zahteva se steje kot neuspela, dokler ne pride [uspeh]; casovne omejitve za neuspeh ni.
         */
        val zaporednihNeuspelih: Int
            @Synchronized get() = stevecZaporednih

        /**
         * Presodi, ali je dovoljeno poslati zahtevo za kljucni okvir ob casu [zdajMs].
         * Ce gre [zdajMs] nazaj v casu, se obravnava kot zadnji znani cas.
         * Pred presojo se uposteva mirovanje (glej [MIROVANJE_ZA_PONASTAVITEV_MS]).
         */
        @Synchronized
        fun presodi(razlog: Razlog, zdajMs: Long): Odlocitev {
            val cas = uskladiCas(zdajMs)
            ponastaviPoMirovanju(cas)
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
        @Synchronized
        fun uspeh(zdajMs: Long) {
            val cas = uskladiCas(zdajMs)
            trenutniRazmikMs = ZACETNI_RAZMIK_MS.coerceIn(NAJMANJ_RAZMIK_MS, NAJVEC_RAZMIK_MS)
            stevecZaporednih = 0
            zadnjiUspehMs = cas
        }

        /** Ponastavi celotno notranje stanje v zacetno stanje. */
        @Synchronized
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

        /** Po mirovanju strogo > [MIROVANJE_ZA_PONASTAVITEV_MS] od zadnje poslane zahteve vrne razmik in stevec na zacetek. */
        private fun ponastaviPoMirovanju(cas: Long) {
            val zadnja = zadnjaZahtevaMs ?: return
            if (nasicenaRazlika(cas, zadnja) > MIROVANJE_ZA_PONASTAVITEV_MS) {
                trenutniRazmikMs = ZACETNI_RAZMIK_MS.coerceIn(NAJMANJ_RAZMIK_MS, NAJVEC_RAZMIK_MS)
                stevecZaporednih = 0
            }
        }

        private fun preostaliCas(cas: Long, zacetek: Long?, razmikMs: Long): Long {
            if (zacetek == null) return 0L
            val minilo = nasicenaRazlika(cas, zacetek)
            // Zaradi uskladiCas je minilo vedno >= 0; zascita, da razmikMs - minilo ne more preliti.
            if (minilo < 0L) return razmikMs
            return if (minilo >= razmikMs) 0L else razmikMs - minilo
        }

        /** Razlika [a] - [b], omejena na obseg Long (namesto preliva vrne Long.MIN_VALUE ali Long.MAX_VALUE). */
        private fun nasicenaRazlika(a: Long, b: Long): Long {
            val razlika = a - b
            // Preliv nastane samo, ce imata a in b razlicen predznak in ima razlika drugacen predznak kot a.
            if (((a xor b) and (a xor razlika)) < 0L) {
                return if (a < b) Long.MIN_VALUE else Long.MAX_VALUE
            }
            return razlika
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
