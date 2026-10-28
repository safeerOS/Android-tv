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

    /** Plosc v vrstici brez drsenja (zgled lastnika 27. 10. 2026: sest plosc v vrsti); vec jih je za drsenjem desno. */
    /**
     * Lastnik in vodja 27. 10. 2026 (TV beta): Nazaj, Domov in odhod z domacega zaslona zaprejo razprto lupo - besedilo,
     * predloge in fokus v njej - kot zaganjalnik televizorja. Prej lupa v Pokrajini ni imela krmila DomacaLupa in Nazaj ni
     * naredil nic (»sele ko sem pobrisal vse crke, je skrilo predloge«), Domov pa je fokus namenoma vrnil v lupo.
     */
    fun zapriLupo(besedilo: CharSequence?, zadetkiVidni: Boolean, fokusVLupi: Boolean): Boolean =
        !besedilo.isNullOrEmpty() || zadetkiVidni || fokusVLupi

    const val STOLPCEV = 6

    /**
     * Prileganje vrste ploscic (po zgledu tv-simple-home LayoutBudget, lastnik 28. 10. 2026): plosca je visoka
     * [VISINA_PLOSCE_DP], na nizjem zaslonu (telefon lezece, tablica, pisava povecana) pa toliko, kolikor je prostora med
     * vrsto in spodnjo vrstico - nikoli nizja od [NAJNIZJA_PLOSCA_DP]; spodnja vrstica tako ne zdrsne z zaslona.
     */
    const val VISINA_PLOSCE_DP = 118
    const val NAJNIZJA_PLOSCA_DP = 72

    fun visinaPlosce(prostorDp: Int): Int = prostorDp.coerceIn(NAJNIZJA_PLOSCA_DP, VISINA_PLOSCE_DP)

    /**
     * Zatemnitev za berljivost (po zgledu tv-simple-home Scrim): prelivi zgoraj (ura, vreme, lupa) in spodaj (spodnja
     * vrstica) - alfa 0-255. Ce je uporabnik ozadje ze zatemnil ([zatemnitev] 0-100), preliv doda samo, kar manjka do
     * [NAJMANJ_ZATEMNITVE_PRELIV]; svetla slika ozadja tako besedila nikoli ne pobeli.
     */
    const val NAJMANJ_ZATEMNITVE_PRELIV = 45

    fun alfaPreliva(zatemnitev: Int): Int =
        ((NAJMANJ_ZATEMNITVE_PRELIV - zatemnitev.coerceIn(0, 100)).coerceAtLeast(0) * 255 / 100)

    /**
     * Lastnik 27. 10. 2026: na domacem zaslonu ENA vrstica - priljubljene spletne in namescene aplikacije (vrstni red
     * uporabnika) in na koncu »+ Dodaj«; vse aplikacije (TV in drugih naprav) so za gumbom Aplikacije. Priljubljena, ki
     * je ni vec med [vse] (odstranjena aplikacija), se ne pokaze; vsaka samo enkrat.
     */
    fun vrstaPriljubljenih(priljubljene: List<String>, vse: Collection<String>): List<String> {
        val znane = vse.toSet()
        return priljubljene.distinct().filter { it in znane }
    }

    /**
     * Lastnik 28. 10. 2026: vrstni red vrstice doloci uporabnik - za namescene IN spletne aplikacije skupaj. [red] = shranjen
     * vrstni red (id: paket ali »splet:<naslov>«), [kandidati] = priljubljene in spletne aplikacije v privzetem redu,
     * [vse] = kar na napravi obstaja. Najprej shranjeni red (brez odstranjenih), nato nove na konec; vsaka enkrat.
     */
    fun vrstaDomov(red: List<String>, kandidati: List<String>, vse: Collection<String>): List<String> {
        val veljavni = vrstaPriljubljenih(kandidati, vse)
        val mnozica = veljavni.toSet()
        val izid = red.distinct().filter { it in mnozica }.toMutableList()
        for (id in veljavni) if (id !in izid) izid.add(id)
        return izid
    }

    /** Premik plosce [id] za [korak] mest (-1 levo, +1 desno); null, ce se nic ne spremeni (rob vrstice, ni v vrstici). */
    fun premakni(vrsta: List<String>, id: String, korak: Int): List<String>? {
        val i = vrsta.indexOf(id)
        if (i < 0 || korak == 0) return null
        val j = (i + korak).coerceIn(0, vrsta.size - 1)
        if (j == i) return null
        val s = vrsta.toMutableList()
        s.add(j, s.removeAt(i))
        return s
    }

    /**
     * Mirovanje (po zgledu tv-simple-home IdleTracker, lastnik 28. 10. 2026): po [MIROVANJE_MS] brez daljinca, miske ali
     * dotika vmesnik zbledi in ostane samo ozadje (manj vzganja zaslona OLED); prvi pritisk samo zbudi.
     */
    const val MIROVANJE_MS = 60_000L

    fun mirujePo(zadnjiVnosMs: Long, zdajMs: Long, mejaMs: Long = MIROVANJE_MS): Boolean = zdajMs - zadnjiVnosMs >= mejaMs

    /** Do naslednje preverbe mirovanja (vsaj 1 s). */
    fun doMirovanja(zadnjiVnosMs: Long, zdajMs: Long, mejaMs: Long = MIROVANJE_MS): Long = maxOf(1_000L, zadnjiVnosMs + mejaMs - zdajMs)

    /** Tipka v nacinu premikanja: -1/+1 premik, 0 konec (OK, Nazaj, Domov), null = tipka ni za premikanje. */
    fun tipkaPremikanja(koda: Int): Int? = when (koda) {
        21 -> -1                        // KEYCODE_DPAD_LEFT
        22 -> 1                         // KEYCODE_DPAD_RIGHT
        23, 66, 160, 4, 111 -> 0        // DPAD_CENTER, ENTER, NUMPAD_ENTER, BACK, ESCAPE
        else -> null
    }

    /** Fokus po vrstici z daljincem: levo/desno ostane v vrstici, na robu se ustavi (ne skoci v drugo vrstico). */
    fun naslednji(indeks: Int, korak: Int, stevilo: Int): Int =
        if (stevilo <= 0) -1 else (indeks + korak).coerceIn(0, stevilo - 1)
}
