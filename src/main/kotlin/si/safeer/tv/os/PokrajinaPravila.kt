package si.safeer.tv.os

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Naloga 42 A (27. 10. 2026, lastnik: »TV zaganjalnik po zgledu«): tema Pokrajina novega TV zaganjalnika (beta). Ozadje
 * pokrajina, levo zgoraj Safeer OS, desno vreme + ura + datum, na sredini velika lupa z mikrofonom, vrstica priljubljenih in
 * spletnih aplikacij, spodaj plavajoca vrstica razdelkov. Ciste odlocitve brez Androida (tests/PokrajinaPravilaTest.kt).
 *
 * Ura in datum sta v JEZIKU VMESNIKA (JezikVmesnika -> resources.configuration), ne v jeziku sistema: na zgledu je bil
 * vmesnik angleski, datum pa slovenski (Locale.getDefault() je jezik televizorja, ne izbira v Safeer OS).
 */
object PokrajinaPravila {
    const val TEMA = "pokrajina"
    /** Tema, ki jo je imel kdor ni izbral nobene, pred novim zaganjalnikom (Tema.PRIVZETA). */
    const val TEMA_STARA = "namizje"

    /** Razdelki plavajoce vrstice v tem vrstnem redu (zgled lastnika). */
    const val MEDIJI = "mediji"
    const val SPLET = "splet"
    const val LINK = "link"
    const val SPOROCILA = "sporocila"
    const val APLIKACIJE = "aplikacije"
    const val DATOTEKE = "datoteke"
    const val NASTAVITVE = "nastavitve"
    val DOK = listOf(MEDIJI, SPLET, LINK, SPOROCILA, APLIKACIJE, DATOTEKE, NASTAVITVE)

    /**
     * Tema za uporabnika, ki je ni izbral sam: z novim zaganjalnikom Pokrajina, s starim prejsnja privzeta. Izbrana tema
     * (tudi Pokrajina pri starem zaganjalniku) ostane - vrnitev na stari zaganjalnik ne spremeni izbire.
     */
    fun tema(izbrana: String?, novZaganjalnik: Boolean): String =
        izbrana?.takeIf { it.isNotBlank() } ?: if (novZaganjalnik) TEMA else TEMA_STARA

    fun ura(ms: Long, jezik: Locale, zona: TimeZone): String =
        SimpleDateFormat("HH:mm", jezik).apply { timeZone = zona }.format(Date(ms))

    /**
     * Kratek datum v jeziku vmesnika: »Tor., 27. okt.« (sl), »Tue, 27 Oct« (en), »Di., 27. Okt.« (de), »mar, 27 oct« ->
     * »Mar, 27 oct« (es), »Mar. 27 oct.« (fr), »Mar 27 ott« (it). Prva crka velika (na zacetku vrstice pod uro).
     */
    fun datum(ms: Long, jezik: Locale, zona: TimeZone): String {
        val vzorec = when (jezik.language) {
            "sl", "de" -> "EEE, d. MMM"
            "fr", "it" -> "EEE d MMM"
            else -> "EEE, d MMM"
        }
        val besedilo = SimpleDateFormat(vzorec, jezik).apply { timeZone = zona }.format(Date(ms))
        return besedilo.replaceFirstChar { if (it.isLowerCase()) it.titlecase(jezik) else it.toString() }
    }

    /** Ura se osvezi ob naslednji polni minuti (ne vsakih 30 s s preskokom do pol minute). */
    fun doNaslednjeMinute(ms: Long): Long = 60_000L - Math.floorMod(ms, 60_000L)

    /**
     * Zmanjsano gibanje: Android »odstrani animacije« (lestvica animatorja 0) ali izbira v Nastavitvah - prehodi in
     * povecava ob fokusu brez animacije (fokus ostane viden z obrobo).
     */
    fun trajanje(osnovaMs: Long, lestvicaAnimatorja: Float, zmanjsano: Boolean): Long =
        if (zmanjsano || lestvicaAnimatorja <= 0f) 0L else (osnovaMs * lestvicaAnimatorja).toLong()

    /** Povecava kartice s fokusom; pri zmanjsanem gibanju je ni (obroba zadostuje). */
    fun povecava(zmanjsano: Boolean): Float = if (zmanjsano) 1f else 1.08f

    /** Relativna svetlost barve ARGB po WCAG 2.1 (prosojnost se ne uposteva). */
    fun svetlost(argb: Int): Double {
        fun kanal(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * kanal((argb shr 16) and 0xFF) + 0.7152 * kanal((argb shr 8) and 0xFF) + 0.0722 * kanal(argb and 0xFF)
    }

    /** Kontrastno razmerje dveh barv (1..21). Besedilo na ozadju: vsaj 4.5, velik napis vsaj 3 (WCAG AA). */
    fun kontrast(a: Int, b: Int): Double {
        val la = svetlost(a); val lb = svetlost(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * Barva cez prosojno plast: [spredaj] (ARGB s prosojnostjo) nad neprosojnim [zadaj] - za kontrast besedila na
     * steklenih ploscah nad ozadjem.
     */
    fun cez(spredaj: Int, zadaj: Int): Int {
        val a = ((spredaj ushr 24) and 0xFF) / 255.0
        fun k(s: Int) = (((spredaj shr s) and 0xFF) * a + ((zadaj shr s) and 0xFF) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (k(16) shl 16) or (k(8) shl 8) or k(0)
    }

    /** Fokus po vrstici z daljincem: levo/desno ostane v vrstici, na robu se ustavi (ne skoci v drugo vrstico). */
    fun naslednji(indeks: Int, korak: Int, stevilo: Int): Int =
        if (stevilo <= 0) -1 else (indeks + korak).coerceIn(0, stevilo - 1)
}
