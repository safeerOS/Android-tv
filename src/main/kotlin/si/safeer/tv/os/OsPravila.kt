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
    fun pasSeSkrije(dotik: Boolean, vrstaOdprta: Boolean, tece: Boolean, napaka: Boolean = false): Boolean =
        // Napaka na televizorju: slike ni, zato pas z razlago ostane (prej se je skril in ostal je crn zaslon).
        if (dotik) tece else !vrstaOdprta && !napaka

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

    /**
     * Ikona naprave v seznamu Safeer Linka: "telefon", "tablica", "racunalnik" ali "naprava" (televizor, neznano).
     * Doloci jo platforma, ki jo naprava pove sama. »Deli datoteke« pomeni racunalnik samo pri napravah brez
     * platforme (starejse): datoteke delita tudi tablica in telefon, ki sta zato dobila ikono racunalnika.
     */
    fun ikonaNaprave(platforma: String, deliDatoteke: Boolean): String = when (platforma) {
        "phone" -> "telefon"
        "tablet" -> "tablica"
        "linux", "windows", "macos" -> "racunalnik"
        "" -> if (deliDatoteke) "racunalnik" else "naprava"
        else -> "naprava"
    }

    /**
     * Podnapis naprave v seznamu Safeer Linka kot par (vrsta, program). Vrsta je "racunalnik", "tv", "tablica",
     * "telefon" ali "" (ne vemo - vrstica ostane brez podnapisa); zaslon jo prevede. Program je ime programa ali
     * "": kadar ga ne vemo ali kadar se z njim ze zacne [prikazanoIme] (»Safeer OS (model)« ne potrebuje se
     * podnapisa »Safeer OS«). Platformo pove naprava sama; starejse naprave prepoznamo po naslovu in id-ju.
     */
    fun opisNaprave(platforma: String, id: String, ime: String, prikazanoIme: String, naslov: String): Pair<String, String> {
        val programRacunalnika = if (id.endsWith("-control")) "Safeer Control" else "Safeer Browser"
        val (vrsta, program) = when {
            platforma == "linux" || platforma == "windows" || platforma == "macos" -> "racunalnik" to programRacunalnika
            platforma == "tv" -> "tv" to "Safeer Link"
            platforma == "tablet" -> "tablica" to "Safeer OS"
            platforma == "phone" -> "telefon" to programTelefona(ime)
            naslov == "127.0.0.1" || id.startsWith("tv-") -> "tv" to "Safeer Link"
            id.startsWith("pc-") -> "racunalnik" to programRacunalnika
            id.startsWith("phone-") -> "telefon" to "Safeer Browser"
            // Surove vloge (sender/receiver) uporabnik ne razume.
            else -> "" to ""
        }
        val zeVImenu = program.isNotEmpty() && prikazanoIme.trim().startsWith(program)
        return vrsta to (if (zeVImenu) "" else program)
    }

    /**
     * Program telefona. Platformo "phone" javita Safeer OS in Safeer Browser za Android; kateri je, pove samo
     * privzeto ime naprave (»Safeer OS (model)«, »Safeer (model)«). Preimenovan telefon tega ne pove: prazno,
     * brez ugibanja (prej je vsak telefon dobil napis »Safeer Browser · Android«, tudi tisti s Safeer OS).
     */
    fun programTelefona(ime: String): String {
        val i = ime.trim()
        return when {
            i.startsWith("Safeer OS") -> "Safeer OS"
            Regex("^Safeer(?: telefon)? \\(.+\\)$").matches(i) -> "Safeer Browser"
            else -> ""
        }
    }

    // ------------------------------------------------------------------ glasbeni dodatek

    private val RX_NAGLASI = Regex("\\p{M}+")
    private val GLASBENO_IME = Regex("(^|[^a-z])(music|musik|musica|musique|muzika|glasba|glasbeni|songs?|concerts?|koncerti?|" +
        "lastfm|last fm|vevo|karaoke|soundcloud|videospoti?)([^a-z]|$)")

    /**
     * Ali ime dodatka ali kataloga pove, da je vsebina glasba (»Top Music«, »... Music«, »Koncerti«). Tak katalog
     * sodi pod Glasbo, tudi ce ga dodatek oglasi kot filme ali drug video (lastnik, 5. 10. 2026: dodatek, ki predvaja
     * videospote z YouTuba ali podobno, spada pod zvok). »Musical« je filmska zvrst, ne glasba.
     */
    fun glasbenoIme(ime: String): Boolean =
        GLASBENO_IME.containsMatchIn(java.text.Normalizer.normalize(ime.lowercase(), java.text.Normalizer.Form.NFD).replace(RX_NAGLASI, ""))

    // ------------------------------------------------------------------ logotip kanala

    /**
     * Ali logotip potrebuje svetlo podlago: [piksli] so ARGB pomanjsane slike. Da, kadar je vsaj petina slike prozorna
     * (logotip brez svoje podlage) in so vidni piksli v povprecju temni - tak logotip se na temni ploscici ne vidi.
     * Logotip s svojo podlago (neprozoren) in svetel logotip ostaneta na temni.
     */
    fun svetlaPodlaga(piksli: IntArray): Boolean {
        if (piksli.isEmpty()) return false
        var prozornih = 0; var vidnih = 0; var svetlost = 0L
        for (p in piksli) {
            val a = p ushr 24
            if (a < 64) { prozornih++; continue }
            vidnih++
            svetlost += (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        if (vidnih == 0 || prozornih * 5 < piksli.size) return false
        return svetlost / vidnih < 96
    }

    // ------------------------------------------------------------------ kanal ali postaja, ki ne stece

    /** Kako dolgo kanala, ki pri viru ne dela, ne kazemo. */
    const val MRTEV_KANAL_MS = 24 * 3_600_000L
    /** Kako dolgo velja odgovor dodatka, da za kanal ima prenos (potem ga mreza kanalov vprasa znova). */
    const val ZIV_KANAL_MS = 12 * 3_600_000L

    /**
     * Ali napaka predvajalnika pomeni, da kanal ali postaja pri VIRU ne dela (ne pa, da je odpovedalo omrezje te
     * naprave ali da naprava oblike ne zna). [koda] je PlaybackException.errorCode, [http] odgovor vira (0 = ni).
     * 2004 slab odgovor HTTP: 4xx razen tistih, ki so zacasni ali zahtevajo prijavo; 2003 napacna vrsta vsebine;
     * 2005 datoteke ni; 3001-3004 seznama ali vsebnika ni mogoce prebrati. Casovne omejitve, izpad omrezja (2001,
     * 2002), napake dekodirnika (4xxx) in 5xx niso dokaz, da kanala ni.
     */
    fun mrtevKanal(koda: Int, http: Int): Boolean = when (koda) {
        2004 -> http in 400..499 && http !in setOf(401, 407, 408, 425, 429)
        2003, 2005 -> true
        in 3001..3004 -> true
        else -> false
    }

    /** Koliko casa najvec cakamo na odgovor imenika (DNS) o strezniku toka. */
    const val IMENIK_ROK_MS = 1_500L
    /** Kako dolgo velja odgovor imenika za istega gostitelja (ponovni dotik iste kartice). */
    const val IMENIK_VELJA_MS = 60_000L

    /**
     * Streznika toka ni v imeniku (DNS). To je dokaz, da kanala pri viru ni, sele, kadar je imenik sveze odgovoril, da
     * imena ni ([imeObstaja] == false), IN je isti hip odgovoril za kontrolno ime ([kontrola] == true): imenik torej
     * dela, omrezje te naprave tudi. Brez odgovora (null) je lahko odpovedalo omrezje - takrat kanal ostane.
     */
    fun mrtevGostitelj(imeObstaja: Boolean?, kontrola: Boolean?): Boolean = imeObstaja == false && kontrola == true

    /**
     * Izid sonde toka po odgovoru streznika [http]: true = tok je (2xx), false = toka pri viru ni (iste kode kot pri
     * predvajanju, [mrtevKanal]), null = ne vemo (omejitev, prijava, napaka streznika, preusmeritev brez cilja).
     */
    fun sondaPoOdgovoru(http: Int): Boolean? = when {
        http in 200..299 -> true
        mrtevKanal(2004, http) -> false
        else -> null
    }

    /** Rok za povezavo do streznika kanala ali postaje v zivo (privzeto v predvajalniku: 8 s). */
    const val ROK_POVEZAVE_V_ZIVO_MS = 5_000
    /** Kako dolgo velja odgovor imenika o delujocem omrezju za streznik, ki se ne odziva. */
    const val KONTROLA_VELJA_MS = 3_000L

    /**
     * Streznik toka se ne odziva (casovna omejitev, zavrnjena povezava, ni poti), kanal pa se se ni zacel. Mrtev je,
     * kadar je imenik isti hip odgovoril za kontrolno ime ([kontrola] == true): omrezje te naprave torej dela, ne
     * odziva se streznik. Med predvajanjem ([zacelo]) kratek izpad ni dokaz.
     */
    fun mrtevNeodziven(zacelo: Boolean, kontrola: Boolean?): Boolean = !zacelo && kontrola == true

    /** Ime gostitelja iz sporocila sistema »Unable to resolve host "ime": ...« ali "". */
    fun gostiteljIzNapake(sporocilo: String?): String =
        Regex("resolve host \"([^\"]+)\"").find(sporocilo.orEmpty())?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()

    /**
     * Id kartice kanala. Predvajana enota dodatka ima za id-jem kartice se »#<stevilka toka>« (izbrani tok); torrent
     * (»#t...«) in drugi id-ji ostanejo, kot so.
     */
    fun kljucKanala(id: String): String {
        val i = id.lastIndexOf('#')
        if (i <= 0) return id
        val rep = id.substring(i + 1).removePrefix("-")
        return if (rep.isNotEmpty() && rep.all { it.isDigit() }) id.substring(0, i) else id
    }

    /**
     * Vrstni red mreze, ki se dopolnjuje v ozadju: kar uporabnik ze vidi ([naZaslonu], id-ji po vrsti), ostane spredaj v
     * istem vrstnem redu, novo pride za tem v svojem vrstnem redu. Kartice se tako nikoli ne premescajo in ne vrivajo
     * pod izbiro (lastnik, 5. 10. 2026: »uporabnik ne sme cutiti osvezevanja v ozadju«). Cesar v [novi] ni vec, izpade.
     */
    fun <T> stabilenRed(naZaslonu: List<String>, novi: List<T>, id: (T) -> String): List<T> {
        if (naZaslonu.isEmpty()) return novi
        val poId = HashMap<String, T>(novi.size * 2)
        for (x in novi) poId.putIfAbsent(id(x), x)
        val spredaj = naZaslonu.mapNotNull { poId[it] }
        if (spredaj.isEmpty()) return novi
        val videni = naZaslonu.toHashSet()
        return spredaj + novi.filter { id(it) !in videni }
    }

    // ------------------------------------------------------------------ stranska vrstica ob vgrajenem brskalniku

    /** Kaj naredi tipka daljinca, ko je fokus v stranski vrstici Safeer OS in je vsebina vgrajeni brskalnik. */
    enum class TipkaMenija { MENIJU, NIC, V_VSEBINO, IZHOD, DRUGAM }

    /**
     * Fokus v stranski vrstici ob brskalniku. GOR, DOL in OK pripadajo meniju (prej jih je dobila stran: GOR je
     * skocil v naslovno vrstico in meni zaprl - po meniju se z daljincem ni dalo premikati). DESNO vrne v stran,
     * LEVO ne naredi nicesar, NAZAJ zapusti Splet (vsebina -> meni -> izhod, kot v televizijskih aplikacijah;
     * drzanje tipke ne steje, da en dolg pritisk ne naredi dveh korakov). Druge tipke (barvne, predvajanje) niso
     * za meni. [koda] je Androidova (KeyEvent.KEYCODE_*), [pritisk] = ACTION_DOWN.
     */
    fun tipkaVMeniju(koda: Int, pritisk: Boolean, ponovitev: Int): TipkaMenija = when (koda) {
        19, 20 -> TipkaMenija.MENIJU                 // DPAD_UP, DPAD_DOWN
        23, 66, 160, 96 -> TipkaMenija.MENIJU        // DPAD_CENTER, ENTER, NUMPAD_ENTER, BUTTON_A
        21 -> TipkaMenija.NIC                        // DPAD_LEFT
        22 -> if (pritisk) TipkaMenija.V_VSEBINO else TipkaMenija.NIC                       // DPAD_RIGHT
        4 -> if (pritisk && ponovitev == 0) TipkaMenija.IZHOD else TipkaMenija.NIC          // BACK
        else -> TipkaMenija.DRUGAM
    }

    /** Besedilo je ena sama spletna povezava: ob njem ponudimo »Odpri povezavo«. */
    fun jePovezava(besedilo: String): Boolean {
        val b = besedilo.trim()
        val zaShemo = when {
            b.startsWith("https://") -> b.substring(8)
            b.startsWith("http://") -> b.substring(7)
            else -> return false
        }
        return zaShemo.isNotEmpty() && b.none { it.isWhitespace() }
    }
}
