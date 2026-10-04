package si.safeer.tv.os

/**
 * Pravila Safeer OS brez Androida - ciste funkcije, ki jih preizkusimo v navadnem JVM
 * (tests/OsPravilaTest.kt, tests/run_os_tests.sh) in tako vsaka sprememba pade v CI,
 * ne sele na televizorju.
 */
object OsPravila {

    // ------------------------------------------------------------------ slike neznanega izvora

    /** Vecje slike sploh ne dekodiramo: nobena ikona ni tako velika. */
    const val NAJVEC_STRANICA = 8192

    /**
     * inSampleSize za sliko neznanega izvora (ikona z racunalnika): potenca dvojke, pri kateri
     * daljsa stranica ne preseze [najvec]. 0 pomeni: slike ne dekodiraj.
     *
     * Brez tega bi majhna datoteka z ogromnimi merami (PNG 20000 x 20000 ima lahko le nekaj
     * kilobajtov) na televizorju porabila stotine MB pomnilnika ali sesula aplikacijo.
     */
    fun vzorec(sirina: Int, visina: Int, najvec: Int): Int {
        if (sirina <= 0 || visina <= 0 || najvec <= 0) return 0
        if (sirina > NAJVEC_STRANICA || visina > NAJVEC_STRANICA) return 0
        val daljsa = maxOf(sirina, visina)
        var v = 1
        while (daljsa / v > najvec) v *= 2
        return v
    }

    /** Najvecja shranjena ikona (PNG, ki ga poslje racunalnik); vecje ne shranimo. */
    const val NAJVEC_IKONA_BAJTOV = 512 * 1024

    /** Ali ikono s temi bajti shranimo na disk televizorja. */
    fun shraniIkono(bajtov: Int): Boolean = bajtov in 1..NAJVEC_IKONA_BAJTOV

    // ------------------------------------------------------------------ pas z gumbi med videom

    /**
     * Ali se pas z naslovom in gumbi med videom skrije, ko uporabnik nekaj sekund nicesar ne naredi.
     * Daljinec: odprta vrsta kartic pas drzi (fokus je v njej; zapre jo uporabnik).
     * Dotik: vrsta kartic je del pasu in se skrije z njim, ko video tece - dotik pas vrne. Med pavzo in na koncu
     * posnetka pas ostane (uporabnik vidi, kje je obstal; gumbi in predlogi so pri roki).
     * [tece]: uporabnik hoce predvajanje (ni pavze), posnetek ni koncan in ni v napaki.
     */
    fun pasSeSkrije(dotik: Boolean, vrstaOdprta: Boolean, tece: Boolean): Boolean = if (dotik) tece else !vrstaOdprta

    /** Po koliko ms brez dotika ali tipke se pas skrije. */
    const val PAS_SKRIJ_MS = 4_000L

    /** Po nadaljevanju iz pavze se skrije prej: uporabnik hoce gledati, ne gumbov. */
    const val PAS_SKRIJ_PO_PAVZI_MS = 1_500L

    /** Zamik skrivanja ob zacetku predvajanja oziroma nadaljevanju ([poPavzi]). */
    fun pasZamik(poPavzi: Boolean): Long = if (poPavzi) PAS_SKRIJ_PO_PAVZI_MS else PAS_SKRIJ_MS

    // ------------------------------------------------------------------ vrstica Nadaljuj

    /** Nov vnos gre na vrh; isti (po kljucu) se ne podvoji, ampak premakne; najvec [najvec] vnosov. */
    fun <T> naVrh(nov: T, stari: List<T>, najvec: Int, kljuc: (T) -> String): List<T> {
        val izid = ArrayList<T>()
        izid.add(nov)
        for (s in stari) {
            if (izid.size >= najvec) break
            if (kljuc(s) == kljuc(nov)) continue
            izid.add(s)
        }
        return izid
    }

    /** Ime datoteke ikone za vrstico s tem kljucem. */
    fun imeIkone(kljuc: String): String = Integer.toHexString(kljuc.hashCode()) + ".png"

    /** Datoteke ikon, ki ne pripadajo nobeni vrstici vec (te se izbrisejo). */
    fun odvecneIkone(datoteke: List<String>, kljuci: List<String>): List<String> {
        val ostanejo = kljuci.map { imeIkone(it) }.toSet()
        return datoteke.filter { it !in ostanejo }
    }

    /**
     * Kartica "Zaslon racunalnika" sodi v Nadaljuj samo za celo namizje. Program, zagnan s
     * televizorja (locen zaslon), ima svojo kartico; zaslon bi odprl namizje, ne programa.
     */
    fun zapisiZaslon(naLocenemZaslonu: Boolean): Boolean = !naLocenemZaslonu

    /**
     * Okno "ni vec na voljo" ponudi odstranitev samo, kadar je kartica res v vrstici Nadaljuj
     * (pripeta kartica na domacem zaslonu ima svojo odstranitev).
     */
    fun ponudiOdstranitev(kljuc: String, vNadaljuj: List<String>): Boolean = kljuc in vNadaljuj
}
