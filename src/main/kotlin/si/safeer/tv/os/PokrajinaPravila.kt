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
    /**
     * Okno menija je sirota (lastnik 8. 11. 2026): odprto je, fokus pa ima glavno okno dejavnosti - tipke gredo glavnemu
     * oknu, Nazaj okna ne zapre. Normalno ima fokus okno menija, dokler je odprto.
     */
    fun meniBrezFokusa(glavnoOknoImaFokus: Boolean, meniOdprt: Boolean): Boolean = glavnoOknoImaFokus && meniOdprt

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
     * Fokus-straza 5. 11. 2026 05:03 (ecb290b6): DESNO v spodnji vrstici je preskakoval gumbe (Medijski center -> Safeer
     * Link, Sporocila -> Datoteke) in z zadnjega pobegnil k vremenu zgoraj - gumb s fokusom se je razsiril, sosedi so se med
     * iskanjem fokusa premaknili. Zdaj sirina gumbov ostane enaka (napis sme cez rob gumba, brez vpliva na postavitev) in
     * pot je izrecna: [indeks] -> (levo, desno) po vrstnem redu; zadnji DESNO in prvi LEVO ostaneta na sebi.
     * Preverba TV 5. 11. 2026 (N3): prvi LEVO je bil -1 (privzeto) in je pobegnil GOR na ploscico - stranskega menija na
     * domu ni. [stevilo] gumbov v vrsti.
     */
    fun sosedaDoka(indeks: Int, stevilo: Int): Pair<Int, Int> =
        (if (indeks > 0) indeks - 1 else indeks) to (if (indeks < stevilo - 1) indeks + 1 else indeks)

    /**
     * Preverba TV 5. 11. 2026 (N4): DOL iz lupe se vrne na ploscico [zapomnjena], s katere je uporabnik prisel GOR, ce je
     * se vidna in sprejme fokus ([vidna]); sicer null (privzeto iskanje fokusa - prej geometrijsko najblizja SmartTube).
     */
    fun dolIzLupe(zapomnjena: String?, vidna: (String) -> Boolean): String? = zapomnjena?.takeIf { it.isNotEmpty() && vidna(it) }

    /**
     * Preverba TV 5. 11. 2026 (N1): okno, ki ga odpre dolg OK, dobi ponovitve in spust istega pritiska. Ti se pozrejo;
     * velja sele nov pritisk OK (ACTION_DOWN brez ponovitve) ali katerakoli druga tipka. [dol] = ACTION_DOWN.
     */
    class DolgiOk {
        private var pozri = true
        fun pozre(jeOk: Boolean, dol: Boolean, ponovitev: Int): Boolean {
            if (!pozri) return false
            if (!jeOk) { if (dol) pozri = false; return false }
            if (dol && ponovitev == 0) { pozri = false; return false }
            return true
        }
    }

    /** Preverba TV 5. 11. 2026 (N1): prvi fokus menija na prvi neskodljivi postavki; -1 = na »Preklici« (vse so nevarne). */
    fun prviFokusMenija(nevarne: List<Boolean>): Int = nevarne.indexOfFirst { !it }

    /** Vrstni red gumbov v spodnji vrstici (shranjen in manjkajoci na koncu). */
    /**
     * Preverba TV 4. 11. 2026: Nazaj iz Aplikacije/Datoteke (odprto z gumbom spodnje vrstice) je fokus dal zadnji ploscici
     * (facebook.com), ne gumbu, s katerega je uporabnik prisel. Fokus se vrne na pogled z oznako [oznaka] (gumb spodnje
     * vrstice »dok:...« ali ploscica), ce je se na zaslonu; sicer na [zadnjaPloscica]; sicer null (prva ploscica).
     */
    fun oznakaFokusaPoVrnitvi(oznaka: String?, naZaslonu: (String) -> Boolean, zadnjaPloscica: String?): String? = when {
        !oznaka.isNullOrEmpty() && naZaslonu(oznaka) -> oznaka
        !zadnjaPloscica.isNullOrEmpty() && naZaslonu(zadnjaPloscica) -> zadnjaPloscica
        else -> null
    }

    /**
     * Pregled lupe 5. 11. 2026 (11): ob odhodu z doma s fokusom v lupi (polje, zadetek, filter, vrstica znakov) se zapomni
     * zadnja ploscica - lupa se ob odhodu zapre in zadetki izginejo, zato je fokus po vrnitvi pristal v lupi.
     */
    fun oznakaObOdhodu(oznaka: String?, vLupi: Boolean, zadnjaPloscica: String?): String? =
        if (vLupi) zadnjaPloscica else oznaka

    /**
     * Pregled lupe 5. 11. 2026 (9): fokus po vrnitvi na dom se obnovi samo, dokler uporabnik od onStart ni pritisnil
     * nobene tipke - prej so trije poznejsi posti (onStart, onResume, okno dobi fokus) fokus vrnili za uporabnikom.
     */
    fun obnoviFokus(cakaVrnitev: Boolean, tipkaPoVrnitvi: Boolean): Boolean = cakaVrnitev && !tipkaPoVrnitvi

    /**
     * DOL s ploscice na mini predvajalnik, ko je viden in ga je mogoce fokusirati (odprt ali zaprta pilula); sicer na
     * spodnjo vrstico pod isto X. Lastnik 7. 11. 2026 20:15 (tocka 28, video): z daljincem na pilulo nad dokom ni bilo
     * mogoce; pregled 3. 11. je zaprto pilulo preskakoval. [miniOdprt] je ostal zaradi klicateljev, na odlocitev ne vpliva.
     */
    @Suppress("UNUSED_PARAMETER")
    fun dolNaMini(miniOdprt: Boolean, miniViden: Boolean): Boolean = miniViden

    fun dokVrstniRed(shranjeno: String?): List<String> {
        val s = shranjeno?.split(",")?.map { it.trim() }?.filter { it in DOK }.orEmpty()
        val izid = s.toMutableList()
        for (r in DOK) if (r !in izid) izid.add(r)
        return izid
    }

    /**
     * Tema za uporabnika, ki je ni izbral sam: z novim zaganjalnikom Pokrajina, s starim prejsnja privzeta. Izbrana tema
     * (tudi Pokrajina pri starem zaganjalniku) ostane - vrnitev na stari zaganjalnik ne spremeni izbire.
     */
    fun tema(izbrana: String?, novZaganjalnik: Boolean): String =
        izbrana?.takeIf { it.isNotBlank() } ?: if (novZaganjalnik) TEMA else TEMA_STARA

    fun ura(ms: Long, jezik: Locale, zona: TimeZone, urniNacin24: Boolean): String {
        val vzorec = if (urniNacin24) "HH:mm" else "h:mm a"
        return SimpleDateFormat(vzorec, jezik).apply { timeZone = zona }.format(Date(ms))
    }

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

    /** Najvecja ikona ploscice (pri polni visini 118 dp, kot prej); najmanjsa, da je se prepoznavna. */
    const val NAJVECJA_IKONA_DP = 64
    const val NAJMANJSA_IKONA_DP = 20

    /**
     * Pregled doma 5. 11. 2026 (15): ikona ploscice iz visine plosce, da ime ni obrezano - plosca (os_kartica_ikona.xml)
     * ima 2 x 10 dp roba in 6 dp med ikono in imenom; [visinaImenaDp] je izmerjena vrstica imena (0, ce imena ni).
     */
    fun velikostIkonePloscice(visinaPlosceDp: Int, visinaImenaDp: Int): Int =
        (visinaPlosceDp - 20 - 6 - visinaImenaDp).coerceIn(NAJMANJSA_IKONA_DP, NAJVECJA_IKONA_DP)

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
        // Lastnik 5. 11. 2026 05:24: ploscic »razdelek:« (Safeer OS razdelek, 1c828bcc) ni vec - vsi razdelki so v spodnji
        // vrstici. Shranjene enkrat pocisti Priljubljene.pocistiRazdelke; filter ostane za red, ki pride z drugih naprav.
        return brezRazdelkov(priljubljene.distinct()).filter { it in znane }
    }

    /** Predpona odstranjenih ploscic razdelkov Safeer OS (pregled doma 5. 11. 2026, 10). */
    const val PREDPONA_RAZDELKA = "razdelek:"

    /** Seznam brez ploscic »razdelek:...« (vrstni red ostane). */
    fun brezRazdelkov(seznam: List<String>): List<String> = seznam.filterNot { it.startsWith(PREDPONA_RAZDELKA) }

    /**
     * Pregled doma 5. 11. 2026 (7): po odstranitvi zadnje ploscice je prvi otrok vrstice napis »Dodaj, kar gledas« (ne
     * sprejme fokusa) - fokus je ostal nikjer. Indeks prvega pogleda, ki sprejme fokus in je viden, ali -1.
     */
    fun prviZaFokus(fokusabilenInViden: List<Boolean>): Int = fokusabilenInViden.indexOfFirst { it }

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

    /**
     * Pregled doma 5. 11. 2026 (12): med oknom, lupo ali premikom mirovanje ne sme nastopiti - prej se je preverba zato
     * ponavljala vsako sekundo (rok je bil ze mimo). [zadrzano] = okno/lupa/premik: null (ne ponavljaj; nova preverba ob
     * vrnitvi fokusa ali naslednji tipki). Sicer cas do roka (vsaj 1 s); ce je rok mimo (zatemnitev zadrzi predvajanje),
     * znova sele cez [MIROVANJE_MS].
     */
    fun zamikPreverbeMirovanja(zadnjiVnosMs: Long, zdajMs: Long, mejaMs: Long, zadrzano: Boolean): Long? {
        if (zadrzano) return null
        val d = zadnjiVnosMs + mejaMs - zdajMs
        return if (d > 0) maxOf(1_000L, d) else MIROVANJE_MS
    }

    /**
     * Tipka v nacinu premikanja: -1/+1 premik, 0 konec (OK, Nazaj, Domov, Meni), null = tipka ni za premikanje.
     * Pregled doma 5. 11. 2026 (5): Meni med premikom je odprl moznosti, premik pa je ostal vklopljen.
     */
    fun tipkaPremikanja(koda: Int): Int? = when {
        koda == 21 -> -1                         // KEYCODE_DPAD_LEFT
        koda == 22 -> 1                          // KEYCODE_DPAD_RIGHT
        koda == 23 || koda == 66 || koda == 160 || koda == 4 || koda == 111 -> 0   // DPAD_CENTER, ENTER, NUMPAD_ENTER, BACK, ESCAPE
        jeTipkaMenija(koda) || koda == 165 -> 0  // Meni, Nastavitve, rdeca, INFO (165) - kot moznosti ploscice
        else -> null
    }

    /** GOR/DOL med premikanjem: pogoltneta se, da fokus ne zapusti vrstice, medtem ko je premik se vklopljen. */
    fun pogoltneMedPremikanjem(koda: Int): Boolean = koda == 19 || koda == 20   // KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN

    /**
     * Zgornji rob napisa med premikom (v koordinatah okvirja): pod vrstico ali nad njo, z robom [rob], vedno cel v okvirju.
     * Preverba TV 4. 11. 2026: premik se je zacel brez vidnega napisa.
     */
    fun vrhNamigaPremika(vrhVrste: Int, dnoVrste: Int, visinaNamiga: Int, rob: Int, nadVrsto: Boolean, visinaOkvirja: Int): Int {
        val y = if (nadVrsto) vrhVrste - rob - visinaNamiga else dnoVrste + rob
        return y.coerceIn(0, maxOf(0, visinaOkvirja - visinaNamiga))
    }

    /** Fokus po vrstici z daljincem: levo/desno ostane v vrstici, na robu se ustavi (ne skoci v drugo vrstico). */
    fun naslednji(indeks: Int, korak: Int, stevilo: Int): Int =
        if (stevilo <= 0) -1 else (indeks + korak).coerceIn(0, stevilo - 1)

    /**
     * Tipke menija / nastavitev na daljincu ali krmilniku (Menu, Settings, rdeči gumb na Philips TV).
     * Philips TV daljinec pošilja KEYCODE_MENU (82), KEYCODE_SETTINGS (176), KEYCODE_PROG_RED (183) ali 183.
     */
    fun jeTipkaMenija(koda: Int): Boolean = when (koda) {
        82,                             // KEYCODE_MENU
        176,                            // KEYCODE_SETTINGS
        183,                            // KEYCODE_PROG_RED ali TV rdeči gumb (Philips)
        602,                            // KEYCODE_FAC_FAC (Philips TV daljinec MENU tipka)
        171,                            // KEYCODE_WINDOW (Quick settings na nekaterih TV)
        109                             // KEYCODE_BUTTON_SELECT (gamepad select)
            -> true
        else -> false
    }

    /** Indeks ploscice glede na tipko 1-6 (0..5), ali null. */
    fun indeksPloscice(koda: Int): Int? = when (koda) {
        8, 145 -> 0
        9, 146 -> 1
        10, 147 -> 2
        11, 148 -> 3
        12, 149 -> 4
        13, 150 -> 5
        else -> null
    }

    /** Smer premika za stran (kanal gor/dol), -1 ali 1. */
    fun smerStran(koda: Int): Int? = when (koda) {
        166, 92 -> -1
        167, 93 -> 1
        else -> null
    }

    /** Ali tipka pripada predvajalniku (predvajaj/premor/naprej/nazaj)? */
    fun jeMedijska(koda: Int): Boolean = when (koda) {
        85, 87, 88, 126, 127 -> true
        else -> false
    }

    /** Naloga 45 G: ponudba za dodajanje na domaci zaslon. */
    fun dodajNaDomDialog(zagonov: Int, zeVprasan: Boolean, naDomu: Boolean): Boolean =
        zagonov == 3 && !zeVprasan && !naDomu

    /** Dovoljen zagon aplikacije (preprečitev dvojnega klika): mora miniti 1,5 s od zadnjega poskusa. */
    fun dovoljenZagon(zadnjiZagonMs: Long, zdajMs: Long): Boolean =
        zadnjiZagonMs == 0L || zdajMs - zadnjiZagonMs > 1500L

    /** Splosni deli imen paketov in domen, ki ne povedo, katera storitev je (com.google.android..., google.com). */
    private val SPLOSNI_DELI = setOf("com", "org", "net", "www", "android", "google", "tv", "app", "apps", "mobile",
        "leanback", "player", "launcher", "safeer")

    /**
     * Odstrani spletno aplikacijo, ce je v vrsti ze prava aplikacija iste storitve (YouTube: youtube.com in
     * com.google.android.youtube.tv). Pregled 2. 11. 2026: prej je zadostoval podniz - news.google.com je izginil ob
     * katerikoli aplikaciji com.google.*; zdaj se mora glavni del domene ujemati s celim, ne splosnim delom imena paketa.
     */
    fun deduplicirajSpletne(vrsta: List<String>): List<String> {
        val deliPaketov = vrsta.filter { !it.startsWith("splet:") }
            .flatMap { it.lowercase().split('.') }.filter { it.length > 2 && it !in SPLOSNI_DELI }.toSet()
        return vrsta.filter { id ->
            if (id.startsWith("splet:")) {
                val host = id.removePrefix("splet:").substringAfter("://").substringBefore("/").substringBefore(":")
                val deli = host.split(".")
                val glavni = (if (deli.size >= 2) deli[deli.size - 2] else host).lowercase()
                !(glavni.length > 2 && glavni !in SPLOSNI_DELI && glavni in deliPaketov)
            } else true
        }
    }

    /** Varen rob zaslona (Videz): privzeto 40 dp kot pred drsnikom, drsnik 40-96 dp. */
    const val KLJUC_VAREN_ROB = "varen_rob_dp"
    const val VAREN_ROB_DP = 40
    const val VAREN_ROB_NAJVEC_DP = 96
    fun varenRob(shranjen: Int): Int = shranjen.coerceIn(VAREN_ROB_DP, VAREN_ROB_NAJVEC_DP)

    /** Tedenska napoved na domacem zaslonu: najvec toliko dni (naloga 49 E). */
    const val DNI_TEDNA = 5
}
