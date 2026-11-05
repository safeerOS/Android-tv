package si.safeer.tv

/**
 * Zavihki brskalnika na TV in RAM (pravilo lastnika 2. 11. 2026: brskalnik se ne sme odpreti s starimi zavihki). Ciste
 * odlocitve brez Androida, preizkus tests/ZavihkiPravilaTest.kt.
 */
object ZavihkiPravila {
    /** Vrsta namere, s katero pride uporabnik v brskalnik. */
    enum class Namera { ZAGANJALNIK, POGLED, APLIKACIJA, OBNOVA, DRUGO }

    /**
     * Lastnik 4. 11. 2026 23:43: brskalnik, odprt z zaganjalnika, je bil na 3. zavihku (proces je ostal ziv, vmes sta
     * zunanji nameri odprli strani). Zagon z zaganjalnika ali iz Safeer OS (Splet, domaca stran), ko je bil brskalnik v
     * ozadju: ostane en zavihek (domaca stran), ostali se unicijo. Zavihek, ki predvaja, ostane.
     *
     * Pregled lupine 5. 11. 2026: pravilo lastnika je zaradi RAM vedno en zavihek. Zunanji naslov (POGLED), spletna
     * aplikacija (APLIKACIJA) in obnova aktivnosti po smrti procesa (OBNOVA) zato prav tako pustijo samo enega - prej se
     * je ob vsaki zunanji nameri in ob obnovi spet nabralo vec zavihkov (obnova je vrnila tudi 3. zavihek kot aktivnega).
     */
    fun zapriOstale(namera: Namera, zavihkov: Int, bilVOzadju: Boolean): Boolean = zavihkov > 1 && when (namera) {
        Namera.ZAGANJALNIK -> bilVOzadju
        Namera.POGLED, Namera.APLIKACIJA, Namera.OBNOVA -> true
        Namera.DRUGO -> false
    }

    /**
     * Vodja 4. 11. 2026 23:43 (TV: RTV SLO je ostal med odprtimi zavihki): ob izhodu iz spletne aplikacije (Nazaj na
     * zacetku, Domov, druga aplikacija) se njen zavihek unici - mesto je zapomnjeno (SpletneAplikacije.zapomniMesto),
     * ponoven vstop nadaljuje tam. Ostane samo, ce igra zvok v ozadju, ce caka izbira datotek (sistemski izbirnik je
     * druga dejavnost) ali ob menjavi nastavitev (zasuk).
     */
    fun unici(zvokVOzadju: Boolean, cakaIzbiroDatotek: Boolean, menjavaNastavitev: Boolean, predvaja: Boolean = false): Boolean =
        !zvokVOzadju && !cakaIzbiroDatotek && !menjavaNastavitev && !predvaja

    /**
     * Drugi krog pregleda 5. 11. 2026: OBNOVA (en zavihek po obnovi aktivnosti) samo, ce zagon ni vstop v spletno
     * aplikacijo v lupini - tam je odpriAplikacijoVLupini ze pustil en zavihek in si zapomnil prejsnjega, OBNOVA pa ga
     * je zaprla (ob izhodu iz aplikacije se je v ozadju naredil nov WebView).
     */
    fun obnovaPustiEnega(obnovljeno: Boolean, vstopVLupino: Boolean): Boolean = obnovljeno && !vstopVLupino

    /**
     * Lastnik 4. 11. 2026 23:47 (uiautomator): v »Odprti zavihki« smerne tipke niso premaknile nicesar - fokus sta imela
     * GridView in vozlisce v WebViewu pod seznamom. Ob odprtju dobi fokus aktivna kartica (sicer prva); brez zavihkov -1
     * (fokus na »+ Nov zavihek«).
     */
    fun zacetnaKartica(aktivni: Int, stevilo: Int): Int = when {
        stevilo <= 0 -> -1
        aktivni in 0 until stevilo -> aktivni
        else -> 0
    }

    /** Okvir gumba na zaslonu (px): levo, zgoraj, sirina, visina. */
    data class Okvir(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Pregled lupine 5. 11. 2026: LEVO/DESNO v vrstici z naslovom je sel po stalnem seznamu (Nazaj, Naprej, Osvezi,
     * Domov ...), na zaslonu pa je bil vrstni red drug (TV: Nazaj, Domov, Osvezi; Safeer OS pokonci spodaj: Nazaj, Naprej,
     * Domov, Osvezi) - fokus je preskakoval in OK je osvezil namesto Domov. Sosed je zdaj, kar je na zaslonu desno (levo)
     * v isti vrstici: okvirja se navpicno prekrivata vsaj za pol nizjega od obeh. Vrne indeks v [okvirji] ali -1, ce v
     * tisti smeri v vrstici ni nicesar.
     */
    fun sosedVVrstici(okvirji: List<Okvir>, trenutni: Int, desno: Boolean): Int {
        val t = okvirji.getOrNull(trenutni) ?: return -1
        val vrstica = okvirji.indices.filter { i ->
            val o = okvirji[i]
            // Pregled fokusa Gemini 5. 11. 2026 (9): prej je morala biti sredina soseda znotraj trenutnega gumba - z nizkega
            // gumba visjega soseda (polje, poravnano zgoraj) ni bilo, z njega nazaj pa je bil. Prekrivanje je simetricno;
            // vrstici, ki se le dotikata (ali prekrivata za rob), nista ena vrstica.
            val prekrivanje = minOf(o.y + o.h, t.y + t.h) - maxOf(o.y, t.y)
            o.w > 0 && o.h > 0 && prekrivanje * 2 >= minOf(o.h, t.h)
        }.sortedWith(compareBy<Int>({ okvirji[it].x + okvirji[it].w / 2 }, { it }))
        val mesto = vrstica.indexOf(trenutni)
        if (mesto < 0) return -1
        return vrstica.getOrNull(mesto + if (desno) 1 else -1) ?: -1
    }

    /** Dodatki namere za spletno aplikacijo (MainActivity, os/Brskalnik). */
    const val DODATEK_CEZ_ZASLON = "spletna_aplikacija"
    const val DODATEK_V_LUPINI = "aplikacija_v_lupini"
    const val DODATEK_IME = "aplikacija_ime"

    /** Namera brez Androida: ACTION_VIEW z naslovom ([pogled]) in dodatki. */
    data class NameraAplikacije(val pogled: Boolean, val naslov: String?, val dodatki: Map<String, Any>)

    /**
     * Spletna aplikacija iz Safeer OS (Brskalnik.spletnaAplikacija). Odlocitev lastnika 28. 9. 2026 (3a65e877): odpre se
     * v razdelku Splet, lupina Safeer OS (stranska vrstica, vrstica z naslovom) ostane vidna - zato VIEW in ne
     * [DODATEK_CEZ_ZASLON]. Pregled lupine 5. 11. 2026: [DODATEK_V_LUPINI] ji da zivljenjski cikel aplikacije (svoj zavihek,
     * unicen ob izhodu) - prej je bil navaden VIEW, ki je prepisal aktivni zavihek in ostal odprt (RTV SLO).
     */
    fun spletnaAplikacijaIzOs(url: String, ime: String): NameraAplikacije =
        NameraAplikacije(pogled = true, naslov = url, dodatki = mapOf(DODATEK_IME to ime, DODATEK_V_LUPINI to true))

    /** Kako tece spletna stran, ki pride z namero. */
    enum class VstopAplikacije { NAVADEN, V_LUPINI, CEZ_ZASLON }

    /**
     * Zivljenjski cikel aplikacije je neodvisen od videza: [DODATEK_CEZ_ZASLON] (Medij, zvok v ozadju) = cez cel zaslon
     * brez lupine, [DODATEK_V_LUPINI] = v lupini. Oboje samo, ce namero dovoli SpletVarnostPravila (od Safeerja ali naslov
     * s seznama) - sicer navaden zavihek.
     */
    fun vstopAplikacije(celZaslon: Boolean, vLupini: Boolean, dovoljeno: Boolean): VstopAplikacije = when {
        !dovoljeno -> VstopAplikacije.NAVADEN
        celZaslon -> VstopAplikacije.CEZ_ZASLON
        vLupini -> VstopAplikacije.V_LUPINI
        else -> VstopAplikacije.NAVADEN
    }

    /** Stranska vrstica Safeer OS: vstop iz Safeer OS, ki ni cez cel zaslon in ni stran Link (ta ima svoj videz). */
    fun stranskaVrstica(izOs: Boolean, celZaslon: Boolean, odpriLink: Boolean): Boolean = izOs && !celZaslon && !odpriLink

    /** Kaj naredi tipka Domov z daljinca Safeer Control, ko je brskalnik spredaj. */
    enum class Domov { SISTEM, DOMACA_STRAN, NI_MOGOCE }

    /**
     * Pregled lupine 5. 11. 2026: »home« je nalozil zacetno stran brskalnika in vedno javil uspeh, v nacinu spletne
     * aplikacije pa ni naredil nicesar (showBrowserStartPage se tam vrne takoj). Domov je kot na pravem daljincu
     * sistemski Domov (Safeer Vnos, GLOBAL_ACTION_HOME); brez Vnosa zacetna stran brskalnika, v spletni aplikaciji pa
     * neuspeh - uspeh se javi samo, ce se je res kaj zgodilo.
     */
    fun domovDaljinca(sistemskiUspel: Boolean, nacinAplikacije: Boolean): Domov = when {
        sistemskiUspel -> Domov.SISTEM
        !nacinAplikacije -> Domov.DOMACA_STRAN
        else -> Domov.NI_MOGOCE
    }

    /**
     * Pregled lupine 5. 11. 2026: branje stanja predvajanja za Safeer Cast (vsakih 5 s) se ni nikoli ustavilo - tudi brez
     * predvajanja in po koncu je drzalo aktivnost. Naprej samo, dokler kaj predvaja: ob idle/stopped konec; medij, ki se
     * nalaga, dobi 6 praznih branj (30 s), medij, ki je izginil s strani, nobenega.
     * Drugi krog pregleda 5. 11. 2026: »playing« dobi 2 prazni branji (10 s) - takoj po »play« medij na strani pogosto se
     * ni prebran (zamenjava elementa video) in prvo prazno branje je branje ustavilo.
     */
    fun castBranjeNaprej(stanje: String, praznihZapored: Int): Boolean = when (stanje) {
        "idle", "stopped" -> false
        "buffering" -> praznihZapored < 6
        "playing" -> praznihZapored < 2
        else -> praznihZapored < 1
    }
}
