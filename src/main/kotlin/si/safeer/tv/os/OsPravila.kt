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

    /**
     * Zaslon predvajanja odhaja (onStop). Video sicer ustavimo ("video brez slike nima smisla"), razen kadar odhod NI
     * uporabnikov odhod od videa: medtem ko je zaslon izgubljal ospredje, se je namerno zacelo novo predvajanje
     * ([zagonovObOdhodu] je stevec ob onPause, [zagonovZdaj] ob onStop), ali pa video ze kaze drug zaslon predvajanja
     * ([drugihZaslonov] > 0). Brez tega je onStop starega zaslona, ki pride sele po zagonu novega posnetka, novi posnetek
     * ustavil na zacetku. [zagonovObOdhodu] < 0 pomeni, da onPause ni bil zabelezen - takrat velja staro vedenje.
     */
    fun novoPredvajanjeObOdhodu(zagonovObOdhodu: Int, zagonovZdaj: Int, drugihZaslonov: Int): Boolean =
        drugihZaslonov > 0 || (zagonovObOdhodu >= 0 && zagonovZdaj != zagonovObOdhodu)

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

    // ------------------------------------------------------------------ naloga 75: zanesljivost sonde in pregled dodatka

    /**
     * Naloga 75: izid sonde toka. [ZIV] streznik je odgovoril z vsebino; [MRTEV] dokaz, da toka ni (4xx kot pri
     * predvajanju, ni imena v imeniku, zavrnjena povezava); [NEGOTOVO] manj zanesljiv znak (casovna omejitev ob delujocem
     * omrezju, napaka streznika 5xx) - mrtev sele po [NEGOTOVIH_ZA_MRTEV] zaporednih; [NEZNANO] nic ne vemo (omrezje te
     * naprave, omejitev, prijava).
     */
    enum class IzidSonde { ZIV, MRTEV, NEGOTOVO, NEZNANO }

    /** Toliko zaporednih negotovih izidov (brez vmesnega zivega) pomeni mrtev kanal. */
    const val NEGOTOVIH_ZA_MRTEV = 2
    /** Negotov izid velja toliko casa; starejsi se ne steje (zacasen izpad pred dnevi ni dokaz). */
    const val NEGOTOV_VELJA_MS = 48 * 3_600_000L

    /** Izid sonde po odgovoru streznika [http]: 2xx ziv, 4xx kot [mrtevKanal] mrtev, 5xx negotovo, ostalo neznano. */
    fun izidPoOdgovoru(http: Int): IzidSonde = when {
        http in 200..299 -> IzidSonde.ZIV
        mrtevKanal(2004, http) -> IzidSonde.MRTEV
        http in 500..599 -> IzidSonde.NEGOTOVO
        else -> IzidSonde.NEZNANO
    }

    /**
     * Streznik toka se ni odzval v roku. Ob delujocem omrezju ([kontrola] == true: imenik je isti hip odgovoril za
     * kontrolno ime) je to negotovo - lahko je zacasno ali geografsko omejeno; brez tega dokaza ne vemo nic.
     */
    fun izidCasovneOmejitve(kontrola: Boolean?): IzidSonde = if (kontrola == true) IzidSonde.NEGOTOVO else IzidSonde.NEZNANO

    /** Ali je [zaporednih] negotovih izidov ze dokaz, da kanal ne dela. */
    fun mrtevPoNegotovih(zaporednih: Int): Boolean = zaporednih >= NEGOTOVIH_ZA_MRTEV

    /** Pregled kanalov dodatka v ozadju: najvec enkrat na toliko casa (poln pregled). */
    const val PREGLED_VSAKIH_MS = 24 * 3_600_000L
    /** Prekinjen pregled (predvajanje, proracun, pomnilnik) se nadaljuje najprej cez toliko casa. */
    const val PREGLED_PONOVI_MS = 3_600_000L

    /**
     * Ali je pregled kanalov dodatka na vrsti: zadnji poln pregled ([zadnjiPoln]) je star vsaj [PREGLED_VSAKIH_MS] in
     * zadnji poskus ([zadnjiPoskus]) vsaj [PREGLED_PONOVI_MS]. 0 = se ga ni bilo. Ura naprave nazaj: na vrsti.
     */
    fun pregledJeNaVrsti(zadnjiPoln: Long, zadnjiPoskus: Long, zdaj: Long): Boolean {
        fun starVsaj(cas: Long, ms: Long) = cas <= 0L || zdaj - cas !in 0 until ms
        return starVsaj(zadnjiPoln, PREGLED_VSAKIH_MS) && starVsaj(zadnjiPoskus, PREGLED_PONOVI_MS)
    }

    /**
     * Ali raven onTrimMemory pomeni pomnilniski pritisk, ob katerem delo v ozadju neha: med tekom RUNNING_LOW (10) in
     * RUNNING_CRITICAL (15), v ozadju MODERATE (60) in COMPLETE (80). UI_HIDDEN (20) in BACKGROUND (40) nista pritisk.
     */
    fun pritiskPomnilnika(raven: Int): Boolean = raven == 10 || raven == 15 || raven >= 60

    // ------------------------------------------------------------------ naloga 77: glasba z id-ji IMDb (koncerti)

    /** Id IMDb: »tt« in samo stevke. */
    fun jeImdb(id: String): Boolean = id.length > 2 && id.startsWith("tt") && id.drop(2).all { it.isDigit() }

    /**
     * Naloga 77: vnos glasbenega tipa ([glasbenTip], npr. »music« kataloga Music Concerts) z id-jem IMDb je posnetek
     * (koncert, glasbeni film) - video, ne zvok. Tokove zanj dajo filmski dodatki po IMDb.
     */
    fun glasbaJeVideo(glasbenTip: Boolean, id: String): Boolean = glasbenTip && jeImdb(id)

    /**
     * Naloga 77: s katerim tipom vprasamo dodatke po tokovih (pot /stream/{tip}/{id}.json). Ce noben uporabnikov dodatek ne
     * da toka za [tip] ([dodatekZaTip] = false) in je id IMDb, vprasamo kot »movie« (koncert je film); film in serija ostaneta.
     */
    fun tipToka(tip: String, id: String, dodatekZaTip: Boolean): String =
        if (!dodatekZaTip && jeImdb(id) && tip != "movie" && tip != "series") "movie" else tip

    /**
     * Naloga 77: dodatek ima samo kataloge za iskanje (vsak katalog zahteva »search«, npr. YouTubio): mreze nima, v Mojih
     * virih pise »samo iskanje«, dotik odpre iskanje. [katalogi]: (prikazen brez filtra, podpira iskanje) za vsak katalog.
     */
    fun samoIskanje(katalogi: List<Pair<Boolean, Boolean>>): Boolean =
        katalogi.isNotEmpty() && katalogi.none { it.first } && katalogi.any { it.second }

    // ------------------------------------------------------------------ naloga 78: mreza nikoli ni prazna

    /**
     * Naloga 78 (lastnik, 23. 10. 2026: »Prazni seznam ni mi vsec«): kaj pride na zaslon namesto prazne prve strani izbire.
     * [ZADNJA_ZNANA] zadnja znana mreza te izbire (disk); [SORODNO] sorodne police iz uporabnikovih virov; [POKAZI_VSE]
     * kartica, ki odstrani filter; [BREZ_FILTRA] kartice iste mreze brez filtra (ze v pomnilniku); [NIC] razlog in Dodaj vir.
     */
    enum class Nadomestek { ZADNJA_ZNANA, SORODNO, POKAZI_VSE, BREZ_FILTRA, NIC }

    /**
     * Po vrsti: zadnja znana mreza ([zadnjaZnana] kartic), sicer sorodne police ([sorodne]: stevilo kartic vsake), nato ob
     * izbranem filtru ([filter]) »Pokazi vse« in kartice brez filtra ([brezFiltra]); [NIC] samo, ce ni res nicesar.
     */
    fun nadomestnaVsebina(zadnjaZnana: Int, sorodne: List<Int>, filter: Boolean, brezFiltra: Int): List<Nadomestek> {
        val r = ArrayList<Nadomestek>()
        if (zadnjaZnana > 0) r += Nadomestek.ZADNJA_ZNANA
        else if (sorodne.any { it > 0 }) r += Nadomestek.SORODNO
        if (filter) {
            r += Nadomestek.POKAZI_VSE
            if (brezFiltra > 0) r += Nadomestek.BREZ_FILTRA
        }
        if (r.isEmpty()) r += Nadomestek.NIC
        return r
    }

    /**
     * Naloga 78: skupine v izbiri (npr. Glasba | Videospoti | Moja glasba): prva (privzeta) vedno; ostale samo, ce imajo
     * vir ([skupine]: kljuc, ima vir), po svojem vrstnem redu. Prazna skupina ostane samo, ce je izbrana - na koncu.
     */
    fun <T> skupineIzbire(skupine: List<Pair<T, Boolean>>, izbrana: T): List<T> {
        val prva = skupine.firstOrNull() ?: return emptyList()
        val ostale = skupine.drop(1)
        return listOf(prva.first) + ostale.filter { it.second }.map { it.first } +
            listOfNotNull(ostale.firstOrNull { it.first == izbrana && !it.second }?.first)
    }

    // ------------------------------------------------------------------ naloga 79: dodatek brez prostih tokov

    /**
     * Naloga 79: kar vemo o tem, ali dodatek kanalov da proste tokove. [zaporednih] = zaporedni odgovori za kanale, ki so
     * imeli samo obvestila (externalUrl »Click to Unlock Premium Access« ...) in nobenega predvajljivega toka;
     * [zaklenjenOb] = kdaj smo sklenili, da dodatek tokove daje samo z narocnino (0 = ne).
     */
    data class Zaklep(val zaporednih: Int = 0, val zaklenjenOb: Long = 0L) {
        fun vNiz() = "$zaporednih;$zaklenjenOb"
        companion object {
            fun izNiza(s: String?): Zaklep? {
                val d = s.orEmpty().split(";")
                if (d.size != 2) return null
                val n = d[0].toIntOrNull() ?: return null
                val ob = d[1].toLongOrNull() ?: return null
                return if (n < 0 || ob < 0) null else Zaklep(n, ob)
            }
        }
    }

    /** Po toliko zaporednih kanalih samo z obvestili je dodatek »brez prostih tokov«. */
    const val ZAKLENJEN_PO = 5
    /** Sklep velja toliko casa; potem prvi tak odgovor sklep obnovi, predvajljiv tok pa ga pobrise. */
    const val ZAKLENJEN_VELJA_MS = 24 * 3_600_000L

    /**
     * Zaklep po odgovoru dodatka za en kanal: [vnosov] vnosov v `streams`, od tega [predvajljivih] predvajljivih. Predvajljiv
     * tok pobrise vse; prazen odgovor (kanal nima toka) ne pove nic o dodatku; samo obvestila stejejo.
     */
    fun poOdgovoruTokov(z: Zaklep, vnosov: Int, predvajljivih: Int, zdaj: Long): Zaklep = when {
        predvajljivih > 0 -> Zaklep()
        vnosov <= 0 -> z
        else -> {
            val n = z.zaporednih + 1
            Zaklep(n, if (n >= ZAKLENJEN_PO && !jeZaklenjen(z, zdaj)) zdaj else z.zaklenjenOb)
        }
    }

    /** Naloga 90b: dodatki, ki jih ob odprtju Live TV poskusimo odkleniti: zaklenjeni, vsak dodatek (osnova) enkrat, v vrstnem redu seznama. */
    fun zaklenjeniZaPoskus(naslovi: List<String>, osnova: (String) -> String, zaklenjen: (String) -> Boolean): List<String> =
        naslovi.filter(zaklenjen).distinctBy(osnova)

    /** Ali sklep »samo z narocnino« zdaj velja (ura nazaj: ne velja, dokler ga nov odgovor ne obnovi). */
    fun jeZaklenjen(z: Zaklep?, zdaj: Long): Boolean = z != null && z.zaklenjenOb > 0L && zdaj - z.zaklenjenOb in 0 until ZAKLENJEN_VELJA_MS

    /**
     * Naloga 82: dodatek je za mrezo TV v zivo zaklenjen, ce sklep velja ALI je potekel brez vmesnega predvajljivega toka
     * (stevec je se na meji). Prej je po 24 h dodatek spet zasedel vire izbire (zvrst »News« -> samo »All IPTV News«),
     * prvo preverjanje je sklep obnovilo in vsi kanali so izginili - prazna mreza z »Nalagam«. Ponovno preverjanje po
     * izteku je delo pregleda dodatka (75) in klika, ne uporabnikove mreze.
     */
    fun zaklenjenZaMrezo(z: Zaklep?, zdaj: Long): Boolean = jeZaklenjen(z, zdaj) || (z != null && z.zaporednih >= ZAKLENJEN_PO)

    // ------------------------------------------------------------------ naloga 120: neodklenjena placljiva vsebina

    /**
     * Naloga 120: besedilo vnosa dodatka (ime, naslov, opis, povezava), ki NI tok, ampak placilni zid: »Unlock Premium
     * Access«, »Subscribe«, »VIP«, »Narocnina« ... Prosnja za donacijo, »No streams found« in vabilo v Discord niso placilo.
     */
    private val PLACILNI_ZID = Regex(
        "(?i)premium|unlock|subscri|paywall|purchase|\\bbuy\\b|\\bvip\\b|members?[ -]?only|upgrade|paid|" +
            "narocn|naročn|placl|plačl|abonn|suscrip|abbonament|abonnement|kaufen|bezahl|freischalt")

    fun jePlacljivoObvestilo(besedilo: String): Boolean = PLACILNI_ZID.containsMatchIn(besedilo)

    /** Toliko casa je vsebina, ki jo dodatek daje samo za placilo, skrita; potem se preveri znova (morda je odklenjena). */
    const val PLACLJIVO_VELJA_MS = 7L * 24 * 3_600_000L

    /**
     * Naloga 120: kdaj je vsebina (tip + id) zadnjic dala samo placilni zid po odgovoru vseh dodatkov: predvajljiv tok -> null
     * (vsebina je odklenjena ali prosta); vsaj eno placilno obvestilo brez toka -> [prej] ali zdaj; sicer (prazno, napaka)
     * ostane [prej] - odgovor brez tokov ne pove, da je placljiva.
     */
    fun placljivoPoOdgovoru(prej: Long?, placilnih: Int, predvajljivih: Int, zdaj: Long): Long? = when {
        predvajljivih > 0 -> null
        placilnih > 0 -> prej ?: zdaj
        else -> prej
    }

    /** Vsebina je skrita, dokler zapis velja (ura nazaj: ne velja). Uporabnik jo pokaze z »Pokazi zaklenjene«. */
    fun placljivoSkrito(ob: Long?, zdaj: Long, pokaziZaklenjene: Boolean): Boolean =
        !pokaziZaklenjene && ob != null && zdaj - ob in 0 until PLACLJIVO_VELJA_MS

    /** Naloga 79b: najdaljsa povezava za odklep pri dodatku, ki jo sprejmemo. */
    const val ODKLEP_NAJVEC = 2048

    /**
     * Naloga 79b: povezava »Click to Unlock Premium Access« (externalUrl dodatka), ki jo uporabnik lahko odpre v Safeer Splet:
     * samo http(s), z gostiteljem, brez kontrolnih znakov in presledkov, najvec [ODKLEP_NAJVEC] znakov. Vrne samo shemo,
     * gostitelja (z vrati) in pot - brez poizvedbe in sidra (tam je lahko kljuc ali sled); null = ni varna.
     */
    fun povezavaOdklepa(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u.length > ODKLEP_NAJVEC || u.any { it.isISOControl() || it.isWhitespace() }) return null
        val m = Regex("^(https?)://([^/?#@]+)(?=[/?#]|$)(/[^?#]*)?", RegexOption.IGNORE_CASE).find(u) ?: return null
        val gostitelj = m.groupValues[2]
        if (gostitelj.isEmpty() || gostitelj.startsWith(".") || gostitelj.startsWith(":")) return null
        return m.groupValues[1].lowercase() + "://" + gostitelj.lowercase() + m.groupValues[3].ifEmpty { "/" }
    }

    /**
     * Naloga 79b: isti dodatek dvakrat - nov naslov ([novaOsnova]) ima isti id manifesta ([idManifesta]) kot ze dodan
     * ([dodani]: osnova -> id manifesta). Vrne osnove starih naslovov (drugacne osnove, isti id; prazen id ne steje).
     */
    fun stariNasloviDodatka(novaOsnova: String, idManifesta: String, dodani: Map<String, String>): List<String> =
        if (idManifesta.isBlank()) emptyList() else dodani.filter { (o, id) -> o != novaOsnova && id == idManifesta }.keys.sorted()

    // ------------------------------------------------------------------ naloga 81: iskanje v dodatkih samo za iskanje (YouTubio)

    /** Skupni rok iskanja v Media Center (vsi viri) in daljsi rok za kataloge samo za iskanje (YouTubio isce na YouTubu). */
    const val ISKANJE_ROK_MS = 12_000L
    const val ISKANJE_ROK_SAMO_MS = 16_000L

    /**
     * Naloga 81: ali iskanje neha cakati. Vsi viri so odgovorili; ali je mimo [ISKANJE_ROK_MS] in je odgovoril tudi vir samo
     * za iskanje ([samoIskanjeGotovo]); ali je mimo [ISKANJE_ROK_SAMO_MS]. Prej je skupni rok 12 s preklical YouTubio, preden
     * je ta sploh lahko odgovoril.
     */
    fun iskanjeKoncano(vsiGotovi: Boolean, samoIskanjeGotovo: Boolean, casMs: Long): Boolean =
        vsiGotovi || (casMs >= ISKANJE_ROK_MS && (samoIskanjeGotovo || casMs >= ISKANJE_ROK_SAMO_MS))

    /**
     * Naloga 90c: iskanje, ki ga je sprozil uporabnik ([iskanje] ni prazno), gre mimo varovalke odzivnosti dodatka: YouTubio je
     * pocasen (iskanje na YouTubu), po treh izpadih bi ga varovalka 30 min sploh ne vprasala in bi iz rezultatov izginil.
     */
    fun iskanjeMimoVarovalke(iskanje: String): Boolean = iskanje.isNotBlank()

    /** Naloga 90c: kam gre Nazaj z iskanja: razdelek, iz katerega je bilo odprto; neznan ali isti razdelek = nadzorna plosca. */
    fun ciljNazajIzIskanja(iskanjeIz: Int, iskanje: Int, domov: Int): Int = if (iskanjeIz == iskanje || iskanjeIz < 0) domov else iskanjeIz

    /** Naloga 81: ime dodatka v vrstici izvorov zadetkov (»YouTubio | ElfHosted« -> »YouTubio«). */
    fun imeIzvora(imeDodatka: String): String = imeDodatka.substringBefore(" | ").substringBefore(" - ").trim().ifEmpty { imeDodatka.trim() }

    /**
     * Naloga 81: povezava toka (externalUrl), ki je posnetek na YouTubu - ni placilna ali reklamna stran, ampak vsebina sama
     * (dodatek YouTubio); predvajamo jo kot doslej posnetek YouTuba (splet). Samo https/http youtube.com, youtu.be.
     */
    fun jeYoutubePovezava(url: String): Boolean {
        val u = url.trim().lowercase(java.util.Locale.ROOT)
        val m = Regex("^https?://([^/?#@]+)(?=[/?#]|$)").find(u) ?: return false
        val g = m.groupValues[1].substringBefore(':')
        return g == "youtu.be" || g == "youtube.com" || g.endsWith(".youtube.com")
    }

    private val RX_ID_YOUTUBE = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")

    /**
     * Slika zadetka iz uporabnikovega spletnega vira (SpletniVir bere stran zadetkov). YouTube slicico nalozi sele, ko je na
     * zaslonu (lena slika): prej je bil namesto slike naslov strani zadetkov in kartica je ostala z ikono - izmerjeno
     * 25. 10. 2026 (Chrome na WP28 S, iskanje »Murderball«): 20 od 23 zadetkov. Posnetek YouTuba brez prave slike (ali z
     * naslovom strani youtube.com iz starega predpomnilnika) dobi standardno slicico i.ytimg.com/vi/<id>/hqdefault.jpg;
     * drugi viri ostanejo s sliko, kot jo je dala stran.
     */
    fun slikaSpletnegaZadetka(slika: String, povezava: String): String {
        // Grok 10, M6/M16: sliko da tuja stran (njen JS lahko vrne karkoli) - samo https na javno ime gostitelja, ne naslov
        // IP, localhost ali .local (zahteva v domace omrezje), ne http/file/content/data in ne povezava zadetka sama.
        val s = slika.trim().takeIf { varnaSlikaVira(it) && it != povezava.trim() }.orEmpty()
        val id = if (jeYoutubePovezava(povezava)) RX_ID_YOUTUBE.find(povezava)?.groupValues?.get(1) else null
        if (id == null || (s.isNotEmpty() && !jeYoutubePovezava(s))) return s
        return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    }

    private val RX_SLIKA_GOSTITELJ = Regex("^https://([^/?#@:\\[\\]]+)(?::\\d{1,5})?(?:[/?#]|$)", RegexOption.IGNORE_CASE)
    // Zadnja oznaka imena je vrhnja domena: crke (ali xn--), nikoli stevilke - tako ne gre skozi noben zapis naslova IPv4.
    private val RX_VRHNJA_DOMENA = Regex("^(?:[a-z][a-z0-9-]*|xn--[a-z0-9-]+)$")
    private val ZASEBNE_KONCNICE = listOf(".local", ".lan", ".home", ".internal", ".localhost", ".localdomain", ".arpa")

    /**
     * Grok 15, N6: preusmeritev https -> http je dovoljena samo za postajo ali kanal v zivo brez poverilnice - izmerjeno
     * 25. 10. 2026: 2 od 87 postaj iz kataloga (Radio Browser) preusmeri https na http in brez tega ne igrata. Posnetek
     * (povezava debrida z zetonom) in tok s poverilnico ostaneta brez.
     * Grok 23, R1: »brez poverilnice« ne sme sloneti samo na oznaki nadomestka ([sPoverilnico]) - Media3 iste glave poslje
     * tudi na naslednji skok. Dokler predvajalnik ne preverja ciljev preusmeritev (N5), velja samo za zahtevo [naslov] brez
     * lastnih glav razen [GLAVE_BREZ_POVERILNICE] ([glave]: piskotki, Referer, Authorization, glave toka ne gredo po http)
     * in brez skrivnosti v naslovu (uporabnik/geslo, zeton v poti ali poizvedbi, pot Xtream /live/<ime>/<geslo>/).
     */
    fun dovoliHttpPreusmeritev(vZivo: Boolean, sPoverilnico: Boolean, naslov: String, glave: Collection<String>): Boolean =
        vZivo && !sPoverilnico && glave.all { it.trim().lowercase(java.util.Locale.ROOT) in GLAVE_BREZ_POVERILNICE } &&
            !SkrivnostiPravila.jeSkrivenNaslov(naslov) && !RX_XTREAM.containsMatchIn(naslov.trim())

    /** Glave zahteve, ki niso poverilnica: UA brskalnika (doda ga Safeer) in Icy-MetaData (doda ga Media3 za postaje). */
    val GLAVE_BREZ_POVERILNICE = setOf("user-agent", "icy-metadata")
    private val RX_XTREAM = Regex("^https?://[^/?#]+/(?:live|movie|series|timeshift)/[^/?#]+/[^/?#]+/", RegexOption.IGNORE_CASE)

    /** Slika iz uporabnikovega spletnega vira sme le na javno ime gostitelja po https (Grok 10, M6). */
    fun varnaSlikaVira(url: String): Boolean {
        val g = RX_SLIKA_GOSTITELJ.find(url.trim())?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return false
        if (g == "localhost" || '.' !in g || ZASEBNE_KONCNICE.any { g.endsWith(it) }) return false
        return RX_VRHNJA_DOMENA.matches(g.substringAfterLast('.'))
    }

    /** Prebere tok do [meja] bajtov; vec = null (slika s tuje strani ne sme napolniti pomnilnika - Grok 10, M6). */
    /**
     * Najvec bajtov ene slike (naslovnica, slika kartice) iz omrezja. Dnevnik TV 28. 10. 2026 04:56: OutOfMemoryError v
     * Jamendo.bajti (64 MB naenkrat) - slika, ki jo je SpletniVir zaradi meje zavrnil, je sla v rezervo brez meje. Naslovnica
     * je pomanjsana na 280-600 px, zato je 4 MB dovolj; vec slik se nalaga hkrati na TV z 2 GB pomnilnika.
     */
    const val NAJVEC_SLIKA = 4L * 1024 * 1024

    const val TEMNO_NAPREJ = 0
    const val TEMNO_ZBUDI = 1
    const val TEMNO_POGOLTNI = 2

    /**
     * Tipka na zaslonu predvajanja (lastnik 28. 10. 2026): zatemnjen zaslon zbudi katerakoli tipka in nic drugega ne naredi
     * ([TEMNO_ZBUDI]); spust tipke, ki je zbudila, se pogoltne ([TEMNO_POGOLTNI]); glasnost vedno deluje ([TEMNO_NAPREJ]).
     */
    fun tipkaNaTemnem(budna: Boolean, glasnost: Boolean, pritisk: Boolean, zbudilaTaTipka: Boolean): Int = when {
        glasnost -> TEMNO_NAPREJ
        !budna && pritisk -> TEMNO_ZBUDI
        !pritisk && zbudilaTaTipka -> TEMNO_POGOLTNI
        else -> TEMNO_NAPREJ
    }

    /** Napaka dekodiranja videa, ko zaslona predvajanja ni (preklop vhoda na TV, Domov): ne menjamo vira (28. 10. 2026). */
    fun brezMenjaveVira(video: Boolean, zaslonOdprt: Boolean, napakaDekodiranja: Boolean): Boolean =
        video && !zaslonOdprt && napakaDekodiranja

    /** Ponudba »Nadaljuj od … / Od zacetka« velja, ce je mesto vsaj 10 s in ne v zadnjih 5 % (sicer je film koncan). */
    fun ponudiNadaljevanje(mestoMs: Long, trajanjeMs: Long): Boolean =
        mestoMs >= 10_000 && (trajanjeMs <= 0 || mestoMs < trajanjeMs * 95 / 100)

    /** Napovedana dolzina (Content-Length) presega mejo: slike sploh ne prenesemo. -1 = ni napovedana. */
    fun prevelikaNapoved(dolzina: Long, meja: Long = NAJVEC_SLIKA): Boolean = dolzina > meja

    /**
     * Kot [beriOmejeno], a z napovedano dolzino (Content-Length) bere naravnost v polje te velikosti - brez podvajanja
     * medpomnilnika in kopije v toByteArray (OOM na TV 28. 10. 2026 08:07: 7,7 MB slika je rabila se 2 kopiji). Ce je
     * podatkov vec ali manj od napovedi, vrne null oz. prebrano.
     */
    fun beriOmejeno(vhod: java.io.InputStream, meja: Long, napovedano: Long): ByteArray? {
        if (napovedano <= 0 || napovedano > meja) return beriOmejeno(vhod, meja)
        val polje = ByteArray(napovedano.toInt())
        var n = 0
        while (n < polje.size) {
            val r = vhod.read(polje, n, polje.size - n)
            if (r < 0) return polje.copyOf(n)
            n += r
        }
        return if (vhod.read() < 0) polje else null
    }

    fun beriOmejeno(vhod: java.io.InputStream, meja: Long): ByteArray? {
        val izhod = java.io.ByteArrayOutputStream()
        val b = ByteArray(16 * 1024)
        var skupaj = 0L
        while (true) {
            val n = vhod.read(b)
            if (n < 0) return izhod.toByteArray()
            skupaj += n
            if (skupaj > meja) return null
            izhod.write(b, 0, n)
        }
    }

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

    /**
     * Mere naslovnice, pomanjsane na najvec [najvec] pik po daljsi stranici z ohranjenim razmerjem (naloga 56); manjsa
     * ostane, kot je. null = neveljavne mere.
     */
    fun meraNaslovnice(sirina: Int, visina: Int, najvec: Int): Pair<Int, Int>? {
        if (sirina <= 0 || visina <= 0 || najvec <= 0) return null
        val daljsa = maxOf(sirina, visina)
        if (daljsa <= najvec) return sirina to visina
        val s = (sirina.toLong() * najvec / daljsa).toInt().coerceAtLeast(1)
        val v = (visina.toLong() * najvec / daljsa).toInt().coerceAtLeast(1)
        return s to v
    }

    /**
     * Ali je pogled (vrh [y], visina [h] v vsebini drsnika) v vidnem delu [zgoraj]..[spodaj] ali najvec [rezerva] pik od
     * njega - plakat zunaj tega sprosti bitno sliko (naloga 56).
     */
    /**
     * Naloga 144: koliko nad in pod vidnim delom mreze nalagamo naslovnice - en zaslon (prej ena kartica + 60 dp), da je
     * naslednja vrsta ob drsenju ze narisana (z USB ali iz pomnilnika), ne prazna. Najmanj kot prej.
     */
    fun rezervaNaslovnic(visinaKartice: Int, visinaDrsnika: Int, rob: Int): Int = maxOf(visinaKartice + rob, visinaDrsnika)

    /**
     * Naloga 145 (lastnik, 24. 10. 2026: »zakaj se nič ne naredi ko pridem do zadnjega kvadratka«): Nalozi vec caka na
     * preverjanje okna samo, ce preverjanje res tece. Ko je dodatek v premoru (omejuje poizvedbe, na TV 27 min), ostanejo
     * naslovi v [caka] do konca premora - Nalozi vec je kazal »Preverjam … se 12« in ni naredil nicesar. Takrat gremo
     * naprej (naslednji naslovi takoj, preverjanje v ozadju kot pri naslednjih straneh, naloga 55b).
     */
    fun cakajNaPreverjanje(caka: Int, premor: Boolean): Boolean = caka > 0 && !premor

    fun vVidnem(y: Int, h: Int, zgoraj: Int, spodaj: Int, rezerva: Int): Boolean =
        y + h >= zgoraj - rezerva && y <= spodaj + rezerva

    /**
     * Bajtov, ki jih naslovnica [sirina] x [visina] zaseda v pomnilniku (naloga 77): z alfa kanalom 4 B na piksel (ARGB_8888),
     * brez njega 2 B (RGB_565 - plakati JPEG in WebP brez prosojnosti nimajo kaj izgubiti). Enako velja za teksturo v GPU.
     */
    fun bajtovNaslovnice(sirina: Int, visina: Int, imaAlfo: Boolean): Int =
        if (sirina <= 0 || visina <= 0) 0 else (sirina.toLong() * visina * (if (imaAlfo) 4 else 2)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Koliko naslovnic te velikosti gre v pomnilniski proracun [proracun] (B); 0, ce mere niso veljavne. */
    fun naslovnicVProracunu(proracun: Int, sirina: Int, visina: Int, imaAlfo: Boolean): Int {
        val b = bajtovNaslovnice(sirina, visina, imaAlfo)
        return if (b <= 0) 0 else proracun / b
    }

    /**
     * Ali je od zadnjega zapisa minilo dovolj casa za nov zapis (naloga 77): napredek videa se zapise najvec na [razmikMs],
     * ne ob vsakem ticku. [zadnjiMs] < 0 = se nikoli; ura, ki gre nazaj (ponovni zagon), zapis dovoli.
     */
    fun jeCasZaZapis(zadnjiMs: Long, zdajMs: Long, razmikMs: Long): Boolean =
        zadnjiMs < 0 || zdajMs < zadnjiMs || zdajMs - zadnjiMs >= razmikMs

    /**
     * Vrednost, ki jo drago izracunamo in ki se spreminja redko (naloga 77): [daj] jo ponovno izracuna najprej po [veljaMs].
     * Samo za eno nit (glavno). [pozabi] jo zavrze takoj - ob dejanju, ki jo spremeni.
     */
    class Obcasna<T>(private val veljaMs: Long) {
        private var vrednost: T? = null
        private var izracunanoMs = 0L
        private var ima = false

        fun daj(zdajMs: Long, izracunaj: () -> T): T {
            if (!ima || zdajMs < izracunanoMs || zdajMs - izracunanoMs >= veljaMs) {
                vrednost = izracunaj(); izracunanoMs = zdajMs; ima = true
            }
            @Suppress("UNCHECKED_CAST")
            return vrednost as T
        }

        fun pozabi() { ima = false; vrednost = null }
    }

    /**
     * Odzivnost dodatka (naloga 57d): cas zadnjih izpadov (brez odgovora ali 5xx) in do kdaj ga preskakujemo.
     * Lastnik: »Streznike, ki ne odgovarjajo, bi morali zavracati« - a vira ne brisemo sami (odlocitev je uporabnikova).
     */
    /** [poskusOd]: naloga 73 - polodprto stanje (preskok je minil): kdaj je sel edini poskusni zahtevek (0 = se ni). */
    data class Odziv(val izpadi: List<Long> = emptyList(), val preskociDo: Long = 0L, val poskusOd: Long = 0L,
                     /** Naloga 113: od kdaj (prvi izpad tega niza) in zadnja koda HTTP (0 = brez odgovora) - za oznako v Mojih virih. */
                     val odKdaj: Long = 0L, val koda: Int = 0)

    /** Toliko izpadov v [IZPADI_OKNO_MS] pomeni »ne odgovarja«: dodatek preskocimo za [PRESKOK_MS]. */
    const val IZPADOV_ZA_PRESKOK = 3
    const val IZPADI_OKNO_MS = 10 * 60_000L
    const val PRESKOK_MS = 30 * 60_000L

    /** Nov odziv po poizvedbi: [uspeh] (odgovoril je, tudi »tega nimam«) vse pozabi; izpad se steje. */
    fun poOdzivu(o: Odziv?, uspeh: Boolean, zdaj: Long, koda: Int = 0): Odziv {
        if (uspeh) return Odziv()
        // Naloga 113: od kdaj ne odgovarja - prvi izpad niza ostane, koda je zadnja.
        val od = o?.odKdaj?.takeIf { it > 0L } ?: zdaj
        // Naloga 73: polodprto (preskok je minil, dodatek se ni odgovoril) - izpad poskusnega zahtevka takoj spet zapre.
        if (o != null && o.preskociDo != 0L && o.preskociDo <= zdaj) return Odziv(emptyList(), zdaj + PRESKOK_MS, odKdaj = od, koda = koda)
        val izpadi = o?.izpadi.orEmpty().filter { zdaj - it in 0 until IZPADI_OKNO_MS } + zdaj
        return if (izpadi.size >= IZPADOV_ZA_PRESKOK) Odziv(emptyList(), zdaj + PRESKOK_MS, odKdaj = od, koda = koda)
            else Odziv(izpadi, o?.preskociDo ?: 0L, odKdaj = od, koda = koda)
    }

    /** Ali dodatek zdaj preskocimo (v ozadju ga ne sprasujemo, oznaka »Ne odgovarja«). */
    fun neOdgovarja(o: Odziv?, zdaj: Long): Boolean =
        o != null && (o.preskociDo > zdaj || (o.preskociDo != 0L && o.poskusOd != 0L && zdaj - o.poskusOd < POSKUS_MS))

    /** Naloga 73: poskusni zahtevek polodprtega stanja velja toliko; ce se ne vrne (visi), gre po tem casu naslednji. */
    const val POSKUS_MS = 30_000L

    /**
     * Naloga 73 (»circuit breaker«): ali sme zahtevek v ozadju k dodatku s stanjem [o]. Zaprto (ni preskoka): da. Odprto
     * ([Odziv.preskociDo] v prihodnosti): ne. Polodprto (preskok je minil): samo en poskusni zahtevek naenkrat - vrne nov
     * odziv z [Odziv.poskusOd]; izid zapise [poOdzivu] (odgovor zapre stikalo, izpad ga spet odpre za [PRESKOK_MS]).
     * Vrne stanje po odlocitvi ali null, ce zahtevek ne sme.
     */
    fun dovoliZahtevo(o: Odziv?, zdaj: Long): Odziv? {
        if (o == null || o.preskociDo == 0L) return o ?: Odziv()
        if (o.preskociDo > zdaj) return null
        if (o.poskusOd != 0L && zdaj - o.poskusOd < POSKUS_MS) return null
        return o.copy(poskusOd = zdaj)
    }

    /** Naloga 73: politika zahtevka k dodatku po vrsti (protokol Stremio): casovna meja, veljavnost v predpomnilniku, prednost niti. */
    data class Politika(val rokMs: Int, val veljaMs: Long, val prednost: Int)

    /** android.os.Process.THREAD_PRIORITY_DEFAULT / THREAD_PRIORITY_BACKGROUND (brez Androida v preizkusih). */
    const val PREDNOST_OSPREDJE = 0
    const val PREDNOST_OZADJE = 10

    /**
     * Politika po vrsti zahtevka ([pot] = naslov): tok ob kliku najvisja prednost in najdaljsi rok (uporabnik caka, dodatek
     * je lahko pocasen), tok pri preverjanju v ozadju ([vOzadju]) krajsi rok in nizka prednost; metapodatki in podnapisi
     * srednje; katalogi in manifesti v ozadju z nizko prednostjo in kratkim rokom (pocasen dodatek ne zadrzuje mreze).
     * Veljavnost: tok 3 min (povezave potecejo), katalog 30 min, meta in podnapisi 1 h, manifest v pomnilniku ves zagon.
     */
    fun politikaZahteve(pot: String, vOzadju: Boolean): Politika = when {
        "/stream/" in pot -> if (vOzadju) Politika(10_000, 3 * 60_000L, PREDNOST_OZADJE) else Politika(15_000, 3 * 60_000L, PREDNOST_OSPREDJE)
        "/subtitles/" in pot -> Politika(10_000, 60 * 60_000L, PREDNOST_OSPREDJE)
        "/meta/" in pot -> Politika(12_000, 60 * 60_000L, if (vOzadju) PREDNOST_OZADJE else PREDNOST_OSPREDJE)
        "/catalog/" in pot -> Politika(10_000, 30 * 60_000L, PREDNOST_OZADJE)
        pot.endsWith("/manifest.json") -> Politika(10_000, 0L, PREDNOST_OZADJE)
        else -> Politika(15_000, 0L, PREDNOST_OSPREDJE)
    }

    /** Naloga 73: manifest z diska je dober takoj (zagon brez cakanja), cez [MANIFEST_OSVEZI_MS] ga osvezimo v ozadju. */
    const val MANIFEST_OSVEZI_MS = 6 * 3_600_000L
    const val MANIFEST_NAJVEC_MS = 7 * 24 * 3_600_000L

    /** Kaj storiti z manifestom na disku starosti [starostMs]: "uporabi", "uporabi+osvezi" ali "prenesi" (prestar ali ga ni, <0). */
    fun manifestZDiska(starostMs: Long): String = when {
        starostMs < 0 || starostMs >= MANIFEST_NAJVEC_MS -> "prenesi"
        starostMs >= MANIFEST_OSVEZI_MS -> "uporabi+osvezi"
        else -> "uporabi"
    }

    /** Izpad pri preverjanju ob vnosu (0 = brez odgovora ali 5xx): spec streznik (npr. brezplacno gostovanje) se zbuja - poskusimo se enkrat. */
    fun vnosZnova(koda: Int): Boolean = koda == 0 || koda >= 500

    /**
     * Ali ima katalog dodatka za stranjo [velikost] vnosov se naslednjo stran (naloga 57c). Katalog, ki stranjenje oglasi
     * (extra »skip«, standard Stremio), jo ima, dokler stran ni prazna - dodatki imajo razlicno velike strani (do 57 je
     * strani dajala skoraj samo Cinemeta s 50+ vnosi, uporabnikovi dodatki pogosto manj kot [najmanj]). Katalog brez
     * oznake: kot doslej, polna stran pomeni, da je morda se kaj. Stran -1 = dodatek ni odgovoril (izpad): to ni stran.
     */
    /**
     * Naloga 143: najvec [najvec] katalogov, a vsak dodatek dobi svoj delez - izmenicno po dodatkih (v vrstnem redu prvega
     * pojava), znotraj dodatka v njegovem vrstnem redu. Prej `take(12)`: dodatki z veliko katalogi so zapolnili vseh 12 in
     * Cinemeta (20.000+ naslovov, dodana zadnja) je izpadla - mreza Serije se je koncala pri ~550 naslovih (TV 24. 10. 2026).
     */
    fun <T> pravicnoPoDodatkih(seznam: List<T>, dodatek: (T) -> String, najvec: Int): List<T> {
        val poDodatkih = LinkedHashMap<String, ArrayDeque<T>>()
        seznam.forEach { poDodatkih.getOrPut(dodatek(it)) { ArrayDeque() }.addLast(it) }
        val izid = ArrayList<T>()
        while (izid.size < najvec && poDodatkih.values.any { it.isNotEmpty() }) {
            for (vrsta in poDodatkih.values) {
                if (izid.size >= najvec) break
                vrsta.removeFirstOrNull()?.let { izid += it }
            }
        }
        return izid
    }

    fun katalogImaVec(velikost: Int, oglasaStrani: Boolean, najmanj: Int): Boolean =
        if (oglasaStrani) velikost > 0 else velikost >= najmanj

    /**
     * Naloga 55b: ali naslov na mestu [i] okna stroge mreze pokazemo. Prva stran ([strogoDo] mest) samo preverjena;
     * naslednje strani in kar je ze na zaslonu takoj - preverijo se v ozadju.
     */
    fun vOknuViden(i: Int, strogoDo: Int, naZaslonu: Boolean, nepreverjen: Boolean): Boolean =
        i >= strogoDo || naZaslonu || !nepreverjen

    /**
     * Naloga 55b: kartica [izbran] (ima izbiro daljinca), ki je bila na zaslonu ([naZaslonu]) in je v [vidni] ni vec,
     * ostane na svojem mestu, dokler se izbira ne premakne. Vrne (seznam, ali je kartica obdrzana).
     */
    fun <T> obdrziIzbrano(vidni: List<T>, naZaslonu: List<String>, izbran: String?, id: (T) -> String, najdi: (String) -> T?): Pair<List<T>, Boolean> {
        if (izbran == null || vidni.any { id(it) == izbran }) return vidni to false
        val mesto = naZaslonu.indexOf(izbran)
        if (mesto < 0) return vidni to false
        val x = najdi(izbran) ?: return vidni to false
        val m = mesto.coerceAtMost(vidni.size)
        return (vidni.subList(0, m) + x + vidni.subList(m, vidni.size)) to true
    }

    /** Izid [prvih]: izbrani, koliko elementov od zacetka je bilo pregledanih in ali je za njimi se kaj nepregledanega. */
    class Prvih<T>(val izbrani: List<T>, val pregledanih: Int, val prirezano: Boolean)

    /**
     * Prvih [koliko] elementov [vsi], ki jih [izpusti] ne izloci, po vrsti. [izpusti] se klice samo, dokler jih ni
     * dovolj (naloga 55, 22. 10. 2026: mreza Serij je imela 5143 nalozenih naslovov in vsak izris je vsakega preveril na
     * glavni niti, na zaslon pa gre okno 30). Ostanek seznama se pregleda, ko pride na vrsto.
     */
    fun <T> prvih(vsi: List<T>, koliko: Int, izpusti: (T) -> Boolean): Prvih<T> {
        val izhod = ArrayList<T>(minOf(koliko.coerceAtLeast(0), vsi.size))
        var i = 0
        while (i < vsi.size && izhod.size < koliko) {
            val x = vsi[i++]
            if (!izpusti(x)) izhod += x
        }
        return Prvih(izhod, i, i < vsi.size)
    }

    /**
     * Odtis seznama id-jev (vrstni red steje): ali se je seznam spremenil, brez primerjave vsebine. String.hashCode je v
     * nizu shranjen, zato je to en prehod brez novih predmetov.
     */
    fun <T> odtis(vsi: List<T>, id: (T) -> String): Long {
        var h = vsi.size.toLong()
        for (x in vsi) h = h * 1_000_003L + id(x).hashCode()
        return h
    }

    /**
     * Ali mrezo, ki se dopolnjuje v ozadju, narisemo zdaj (naloga 55, lastnikova zasnova): prvi zaslon takoj; potem
     * dodane kartice pocakajo, da je okno preverjeno ([oknoCaka] = false) ali da od zadnjega risanja mine [najvecMs].
     * Kartica, ki bi izginila ali se premaknila (strogo: ne kazemo nepredvajljivega), gre z zaslona takoj.
     */
    fun risiMrezoZdaj(naZaslonu: List<String>, novi: List<String>, prviZaslon: Int, oknoCaka: Boolean,
                      odZadnjegaMs: Long, najvecMs: Long): Boolean {
        if (novi == naZaslonu) return false
        val samoDodane = novi.size > naZaslonu.size && novi.subList(0, naZaslonu.size) == naZaslonu
        if (!samoDodane) return true
        if (naZaslonu.size < prviZaslon) return true
        if (!oknoCaka) return true
        return odZadnjegaMs !in 0 until najvecMs
    }

    /**
     * Razlicice iste vsebine, urejene od najboljse ([poOceni]): razlicica, ki je ze na zaslonu kot kartica ([naZaslonu],
     * id-ji kartic), ostane prva - kartica se ne zamenja, ko je pozneje potrjena boljsa razlicica. Ostale ostanejo za
     * njo po kakovosti. Brez spremembe vrne [poOceni] (isti seznam).
     */
    fun <T> stalnaGlava(poOceni: List<T>, naZaslonu: Set<String>, id: (T) -> String): List<T> {
        if (naZaslonu.isEmpty() || poOceni.size < 2) return poOceni
        val i = poOceni.indexOfFirst { id(it) in naZaslonu }
        return if (i <= 0) poOceni else listOf(poOceni[i]) + poOceni.filterIndexed { j, _ -> j != i }
    }

    /** Kako dolgo polica, ki je vir tokrat ni dal, ostane iz zadnjega znanega pogleda razdelka. */
    const val POLICA_OSTANE_MS = 24 * 3_600_000L

    /**
     * Sveze police razdelka, dopolnjene s policami zadnjega znanega pogleda ([stare]), ki jih [sveze] tokrat nimajo (vir
     * obcasno ne odgovori - polica zato ne sme izginiti in se naslednjic vrniti). Manjkajoca polica pride za svojo
     * prejsnjo sosedo; vrstni red svezih ostane. Brez manjkajocih vrne [sveze] (isti seznam).
     */
    fun <T> dopolniPolice(stare: List<T>, sveze: List<T>, naslov: (T) -> String): List<T> {
        val imena = sveze.map(naslov).toHashSet()
        if (stare.all { naslov(it) in imena }) return sveze
        val izid = ArrayList(sveze)
        var za = -1
        for (p in stare) {
            val i = izid.indexOfFirst { naslov(it) == naslov(p) }
            if (i >= 0) { za = i; continue }
            izid.add(za + 1, p)
            za++
        }
        return izid
    }

    // ------------------------------------------------------------------ mreza namesto polic

    /** Najvec celih vrstic bloka kartic na strani z vec bloki (Domov, Moja glasba, Zate, zadetki iskanja). */
    const val VRSTIC_BLOKA = 2

    /**
     * Blok kartic na strani z vec bloki je mreza z najvec [vrstic] vrsticami po [naVrsto] kartic - brez police, po kateri
     * je treba drseti v desno (lastnik, 5. in 7. 10. 2026). Vrne, koliko kartic VSEBINE ostane v bloku: vse, ce gredo skupaj
     * z [dejanj] karticami dejanj (»Pocisti«, »Pokazi vse« kataloga) v blok; sicer toliko, da ostane prostor za dejanja
     * in - kadar blok nima svoje kartice za vse ([imaVse]) - za kartico »Pokazi vse«. Blok z vsebino ni nikoli prazen.
     */
    fun vsebineVBloku(vsebine: Int, dejanj: Int, naVrsto: Int, vrstic: Int, imaVse: Boolean): Int {
        if (vsebine <= 0) return 0
        val mest = naVrsto.coerceAtLeast(1) * vrstic.coerceAtLeast(1)
        if (vsebine + dejanj <= mest) return vsebine
        return (mest - dejanj - (if (imaVse) 0 else 1)).coerceIn(1, vsebine)
    }

    /**
     * Sirina kartice mreze (dp), s katero [naVrsto] kartic zapolni vrstico: [osnova] se raztegne do sirine vsebine
     * ([rob] dp roba na kartico), najvec za polovico - na zelo sirokem zaslonu raje ostane prazen rob kot ogromne kartice.
     * Prej je na telefonu na desni ostal prazen pas (dve kartici po 116 dp v 344 dp siroki vsebini).
     */
    fun sirinaKarticeMreze(sirinaVsebineDp: Int, naVrsto: Int, osnova: Int, rob: Int): Int =
        (sirinaVsebineDp / naVrsto.coerceAtLeast(1) - rob).coerceIn(osnova, osnova * 3 / 2)

    // ------------------------------------------------------------------ zadetki iskanja: dopolnitev na mestu (lastnik, 25. 10. 2026)

    /**
     * Najmanjsa visina vsebine med ponovnim risanjem strani, ki ostane na mestu [ostaneY] > 0 (zadetki iskanja, ki jih
     * dopolni vir, ki je odgovoril pozneje): stara visina. Risanje po korakih vsebino najprej izprazni in ScrollView bi
     * polozaj obrezal - izmerjeno 25. 10. 2026 na WP28 S: drsnik 145 -> 0 ob dopolnitvi, uporabnik ni mogel do zadetkov
     * spodaj. Stran z vrha visine ne drzi (0).
     */
    fun visinaMedRisanjem(staraVisina: Int, ostaneY: Int): Int = if (ostaneY > 0) staraVisina.coerceAtLeast(0) else 0

    /**
     * Ali se izbira po ponovnem risanju vrne tja, kjer je bila. Na dotik ne, kadar stran ostane na mestu [yNaMestu] > 0:
     * izbiro ima tam kvecjemu iskalno polje na vrhu in vrnitev nanj bi stran pomaknila na vrh. Z daljincem vedno - izbira
     * je uporabnikovo mesto na strani.
     */
    fun vrniIzbiroPoRisanju(naDotik: Boolean, yNaMestu: Int): Boolean = !(naDotik && yNaMestu > 0)

    /**
     * Podnaslov kartice zadetka iz spletnega vira med »Najboljsimi zadetki«: leto in avtor iz naslova posnetka (izvajalec),
     * da so zadetki z istim naslovom razlocljivi. Prej samo leto - posnetki YouTuba ga nimajo, zato so bile tri kartice
     * »Murderball« (»Murderball«, »Take us Home: Leeds United - Murderball« ...) brez razlike (lastnik 25. 10. 2026).
     * Ime vira ([imenaVirov]; izvajalec ga dobi, kadar naslov nima avtorja) se ne pokaze: uporabnik vir doda in nanj
     * pozabi (lastnik: »uporabnik ne rabi vedeti, iz kje je video«).
     */
    fun podnaslovSpletnegaZadetka(leto: Int, izvajalec: String, imenaVirov: Collection<String>): String {
        val avtor = izvajalec.trim().takeIf { a -> a.isNotEmpty() && imenaVirov.none { it.trim().equals(a, ignoreCase = true) } }
        return listOfNotNull(leto.takeIf { it > 0 }?.toString(), avtor).joinToString(" · ")
    }

    // Grok 10, M12: oklepaj na zacetku naslova je del naslova (»(500) Days of Summer«), ne oznaka izdaje.
    private val RX_OKLEPAJI_NASLOVA = Regex("(?<=\\S)\\s*(?:\\(.*?\\)|\\[.*?\\])")
    private val RX_LETNICA_NASLOVA = Regex("\\b(19|20)\\d{2}\\b")
    private val RX_NEALFA_NASLOVA = Regex("[^\\p{L}\\p{N}]+")
    // Grok 10, M12: crke brez razcepa NFD (kot ICU Latin-ASCII): »Đavolji« = »davolji«.
    private val CRKE_BREZ_RAZCEPA = mapOf('đ' to "d", 'ð' to "d", 'ł' to "l", 'ø' to "o", 'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'þ' to "th", 'ı' to "i")

    /** Naslov za primerjavo z iskanim (»Murderball (2005)« = »murderball«): male crke brez naglasov, oklepajev, letnic in locil. */
    fun kljucNaslova(t: String): String {
        val male = t.lowercase(java.util.Locale.ROOT).let { s -> if (s.none { it in CRKE_BREZ_RAZCEPA }) s else s.map { CRKE_BREZ_RAZCEPA[it] ?: it.toString() }.joinToString("") }
        val brez = java.text.Normalizer.normalize(male, java.text.Normalizer.Form.NFD).replace(RX_NAGLASI, "").replace(RX_OKLEPAJI_NASLOVA, " ")
        // Grok 10, M11: naslov, ki je samo letnica (»1917«, »2012«), letnico obdrzi - sicer je kljuc prazen in film ni prvi.
        return brez.replace(RX_LETNICA_NASLOVA, " ").replace(RX_NEALFA_NASLOVA, "").ifEmpty { brez.replace(RX_NEALFA_NASLOVA, "") }
    }

    /**
     * »Najboljsi zadetki« (lastnik 25. 10. 2026): film ali serija iz kataloga ([jeDelo]) s (skoraj) tocnim ujemanjem naslova
     * ([kljucNaslova]) je prvi, posnetki z istim imenom za njim. Pri »Murderball« so bili trije posnetki YouTuba (enaka ocena,
     * prej dodani) pred filmom 2005 s plakatom, ta pa sele spodaj v polici Filmi. Uporabnikove datoteke ([lastna]: ta naprava,
     * naprave v Linku) z enakim naslovom ostanejo spredaj. Brez takega dela vrstni red ostane, kot ga da ocena.
     */
    fun <T> najprejDela(zadetki: List<T>, beseda: String, naslov: (T) -> String, jeDelo: (T) -> Boolean, lastna: (T) -> Boolean,
                        /** Kar ostane pred delom, ce je ocenjeno vise: skladba, postaja - ne posnetek z istim imenom (Grok 10, M5). */
                        ostane: (T) -> Boolean = { false }): List<T> {
        val q = kljucNaslova(beseda)
        if (q.isEmpty()) return zadetki
        val enak = zadetki.indices.filter { i -> (jeDelo(zadetki[i]) || lastna(zadetki[i])) && kljucNaslova(naslov(zadetki[i])) == q }
        val prviDel = enak.firstOrNull { jeDelo(zadetki[it]) } ?: return zadetki
        // Grok 10, M5/M7: zadetki pred prvim delom, ki niso posnetki (skladba »Thriller« pred filmom »Thriller«) ali so
        // uporabnikovi (tudi z imenom izdaje, npr. »Murderball.2005.1080p.mkv«), ostanejo pred njim.
        val ostanejo = (0 until prviDel).filter { ostane(zadetki[it]) || lastna(zadetki[it]) }
        val spredaj = ostanejo + enak.filterNot { it in ostanejo }
        val prvi = spredaj.toHashSet()
        return (spredaj + zadetki.indices.filterNot { it in prvi }).map { zadetki[it] }
    }

    // ------------------------------------------------------------------ podstrani (»Pokazi vse«, seznam, epizode)

    /**
     * Podstrani medijskega centra: »Pokazi vse« bloka, seznam predvajanja, epizode podkasta, skladbe izvajalca. Vsaka si
     * zapomni, kje na strani pod njo je bil uporabnik, ko jo je odprl (polozaj drsnika in kartica z izbiro): Nazaj vrne na
     * stran, s katere je bila odprta, na isto mesto - tudi kadar je bila odprta s podstrani (»Pokazi vse« seznamov ->
     * seznam). Prej sta to hranila ena zastavica in en polozaj: Nazaj z vgnezdene podstrani je preskocil raven, izbira na
     * televizorju pa je pristala na prvi kartici strani.
     */
    class Podstrani<T> {
        companion object {
            /** Kartica mesta ni znana (ali ni narisana): velja polozaj drsnika. */
            const val NI_ODMIKA = Int.MIN_VALUE
        }

        /**
         * Kam Nazaj: [stran] je podstran, ki pride na zaslon (null = stran razdelka). Mesto na njej je kartica [kljuc], ki
         * naj bo [odmik] tock pod vrhom vidnega dela - tako stran pristane, kjer je bila, tudi ce se je nad kartico kaj
         * spremenilo (plosca predvajanja, nedavno). Brez odmika velja polozaj drsnika [y].
         */
        data class Vrnitev<T>(val stran: T?, val y: Int, val kljuc: String?, val odmik: Int = NI_ODMIKA)
        private class Raven<T>(val stran: T, val y: Int, val kljuc: String?, val odmik: Int)
        private val sklad = ArrayList<Raven<T>>()

        val odprta: Boolean get() = sklad.isNotEmpty()
        val globina: Int get() = sklad.size
        /** Podstran na zaslonu. */
        val vrh: T? get() = sklad.lastOrNull()?.stran

        /** Odpre [stran]; [y], [kljuc] in [odmik] so mesto na strani, ki je zdaj na zaslonu (tja se vrne Nazaj). */
        fun odpri(stran: T, y: Int, kljuc: String?, odmik: Int = NI_ODMIKA) { sklad.add(Raven(stran, y.coerceAtLeast(0), kljuc, odmik)) }

        /** Nazaj: zapre podstran na zaslonu; null, ce ni odprta nobena. */
        fun zapri(): Vrnitev<T>? {
            if (sklad.isEmpty()) return null
            val r = sklad.removeAt(sklad.size - 1)
            return Vrnitev(sklad.lastOrNull()?.stran, r.y, r.kljuc, r.odmik)
        }

        /** Drug razdelek, novo iskanje, stran razdelka cez podstran: podstrani ni vec. */
        fun pocisti() { sklad.clear() }
    }

    /**
     * Kljuc vnosa med nedavnimi: isti naslov iz dveh virov (dve razlicici filma ali skladbe) je en vnos - brez podvojenih
     * prikazov. Postaje in kanali v zivo ter datoteke naprave se locijo po oznaki: dve postaji z enakim imenom sta dve
     * postaji, dve datoteki »Track 01« dve datoteki. [cistNaslov] je ze preciscen naslov skupaj z izvajalcem.
     */
    fun kljucNedavnega(id: String, cistNaslov: String, vZivo: Boolean, krajevna: Boolean): String =
        if (vZivo || krajevna || cistNaslov.isBlank()) "id:$id" else "n:$cistNaslov"

    // ------------------------------------------------------------------ omejena vrsta kartic (zaslon predvajanja)

    /**
     * Koliko kartic gre v eno vrsto sirine [sirina], ce je kartica siroka [kartica] in je med karticami [razmik] (iste
     * enote). Zaokrozimo na najblizje celo stevilo - kartice si potem sirino razdelijo, zato so malo vecje ali malo
     * manjse od [kartica], vrsta pa je vedno polna do roba - in nikoli manj kot [najmanj].
     */
    fun karticVVrsti(sirina: Int, kartica: Int, razmik: Int, najmanj: Int = 3): Int {
        val korak = (kartica + razmik).coerceAtLeast(1)
        return maxOf(najmanj, (2 * (sirina + razmik) + korak) / (2 * korak))
    }

    /** Kaj kaze omejena vrsta: [stevilo] zaporednih elementov od [zacetek] in za njimi morda »Pokazi vse«. */
    data class OknoVrste(val zacetek: Int, val stevilo: Int, val pokaziVse: Boolean)

    /**
     * Omejena vrsta z [mest] mesti nad seznamom z [vseh] elementi, v katerem igra [tekoci] (-1: nobeden, npr. predlogi).
     * Ce gre ves seznam v vrsto, je v njej ves in gumba »Pokazi vse« ni. Sicer je zadnje mesto »Pokazi vse«, pred njim
     * pa mest-1 zaporednih elementov. Okno ostane, kjer je bilo ([prejZacetek]), dokler je element, ki igra, v njem -
     * kartice se uporabniku ne premikajo pod prstom ob vsaki skladbi -, sicer se zacne pri elementu, ki igra; ob koncu
     * seznama se poravna nazaj, da je vrsta polna.
     */
    fun oknoVrste(vseh: Int, tekoci: Int, mest: Int, prejZacetek: Int = -1): OknoVrste {
        if (vseh <= 0) return OknoVrste(0, 0, false)
        val m = mest.coerceAtLeast(2)
        if (vseh <= m) return OknoVrste(0, vseh, false)
        val k = m - 1
        val zadnjiZacetek = vseh - k
        val zacetek = when {
            tekoci !in 0 until vseh -> 0
            prejZacetek in 0..zadnjiZacetek && tekoci in prejZacetek until prejZacetek + k -> prejZacetek
            else -> minOf(tekoci, zadnjiZacetek)
        }
        return OknoVrste(zacetek, k, true)
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

    private val HTML_ENTITETA = Regex("&(amp|quot|apos|lt|gt|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});")

    /**
     * Ime iz kataloga (Jamendo) je HTML-kodirano (»John Dada &amp; the Weathermen«); na kartici je besedilo. Odkodiramo v
     * enem prehodu (»&amp;amp;« ostane »&amp;«) samo osnovne in stevilske entitete; neveljavne in neznane ostanejo.
     */
    fun htmlBesedilo(s: String): String = HTML_ENTITETA.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            else -> {
                val k = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (k == null || k == 0 || k > 0x10FFFF || k in 0xD800..0xDFFF) m.value else String(Character.toChars(k))
            }
        }
    }

    // ------------------------------------------------------------------ jezik kataloga iz naslovov (naloga 60)

    /**
     * Katalog m3u dodatka (lastnik, 22. 10. 2026: 5000 brazilskih filmov s portugalskimi naslovi) jezika ne pove, naslovi
     * nimajo IMDb - filter »angleščina« jih je zato pustil vidne. Jezik kataloga ocenimo iz vzorca naslovov: vsak naslov
     * dobi tocke po znacilnih crkah, koncnicah in pogostih besedah; katalog dobi jezik, ce je v njem vecina odlocenih
     * naslovov. Majhen in hiter (200 naslovov < 1 ms), brez slovarjev in brez omrezja.
     */
    const val VZOREC_JEZIKA = 200
    /** Delez odlocenih naslovov, ki mora biti v istem jeziku, da katalog dobi ta jezik. */
    const val VECINA_JEZIKA = 0.6
    /** Najmanj odlocenih naslovov (in najmanj desetina vzorca), preden o katalogu sodimo: sicer ostane neznan. */
    const val NAJMANJ_ODLOCENIH = 8
    /** Naslov je odlocen, ce ima jezik vsaj toliko tock in 1,5-krat vec od naslednjega jezika. */
    private const val PRAG_NASLOVA = 2.0
    /** Hrvascina, srbscina (latinica) in bosanscina: iz naslovov jih ne locimo zanesljivo (ena skupina). */
    const val JEZIK_HBS = "hbs"

    private val ZNAKI_JEZIKA: List<Triple<Char, String, Double>> = listOf(
        Triple('ã', "pt", 3.0), Triple('õ', "pt", 3.0), Triple('ç', "pt", 1.5), Triple('ç', "fr", 1.5),
        Triple('â', "pt", 1.0), Triple('â', "fr", 1.0), Triple('ê', "pt", 1.0), Triple('ê', "fr", 1.0),
        Triple('ô', "pt", 1.0), Triple('ô', "fr", 1.0), Triple('ñ', "es", 3.0), Triple('¿', "es", 3.0), Triple('¡', "es", 3.0),
        Triple('è', "fr", 1.5), Triple('è', "it", 1.0), Triple('à', "fr", 1.0), Triple('à', "it", 1.0), Triple('à', "pt", 1.0),
        Triple('ù', "fr", 1.0), Triple('ù', "it", 1.0), Triple('î', "fr", 2.0), Triple('û', "fr", 2.0), Triple('ë', "fr", 2.0),
        Triple('ï', "fr", 2.0), Triple('œ', "fr", 2.0), Triple('ì', "it", 2.0), Triple('ò', "it", 2.0),
        Triple('ä', "de", 2.5), Triple('ö', "de", 2.5), Triple('ü', "de", 2.5), Triple('ß', "de", 2.5),
        Triple('č', "sl", 1.5), Triple('č', JEZIK_HBS, 1.5), Triple('š', "sl", 1.5), Triple('š', JEZIK_HBS, 1.5),
        Triple('ž', "sl", 1.5), Triple('ž', JEZIK_HBS, 1.5), Triple('ć', JEZIK_HBS, 3.0), Triple('đ', JEZIK_HBS, 3.0),
    )

    private val KONCNICE_JEZIKA: List<Triple<String, String, Double>> = listOf(
        Triple("ção", "pt", 3.0), Triple("ções", "pt", 3.0), Triple("ões", "pt", 3.0), Triple("ción", "es", 3.0),
        Triple("ciones", "es", 3.0), Triple("zione", "it", 3.0), Triple("zioni", "it", 3.0), Triple("ung", "de", 2.0),
        Triple("heit", "de", 2.0), Triple("keit", "de", 2.0),
    )

    private val BESEDE_JEZIKA: Map<String, Map<String, Double>> = mapOf(
        "en" to mapOf("the" to 2.0, "of" to 1.5, "and" to 1.5, "with" to 1.5, "from" to 1.0, "my" to 1.0, "your" to 1.0, "to" to 1.0, "for" to 1.0, "is" to 1.0, "an" to 1.0, "no" to 0.5, "in" to 0.5, "on" to 0.5, "night" to 1.0, "story" to 1.0, "world" to 1.0, "dead" to 1.0, "house" to 1.0, "dark" to 1.0, "love" to 0.5, "man" to 0.5, "last" to 0.5, "life" to 0.5),
        "pt" to mapOf("de" to 0.5, "da" to 1.5, "do" to 1.5, "dos" to 2.0, "das" to 2.0, "o" to 1.0, "os" to 1.0, "um" to 1.5, "uma" to 2.0, "e" to 0.5, "em" to 1.5, "no" to 0.5, "na" to 0.5, "para" to 1.0, "com" to 1.5, "não" to 2.0, "meu" to 1.5, "minha" to 2.0, "homem" to 2.0, "mulher" to 2.0, "morte" to 1.0, "noite" to 1.5, "cidade" to 1.5, "ao" to 1.5, "pelo" to 2.0, "pela" to 2.0, "amor" to 0.5, "vida" to 0.5, "ela" to 1.5, "ele" to 1.5, "eu" to 1.5, "você" to 2.0, "seus" to 1.5, "mais" to 1.5, "dois" to 2.0, "estou" to 2.0),
        "es" to mapOf("de" to 0.5, "el" to 1.5, "la" to 0.5, "los" to 2.0, "las" to 2.0, "del" to 1.0, "y" to 1.5, "en" to 0.5, "una" to 1.0, "con" to 1.0, "por" to 1.0, "mi" to 1.0, "muerte" to 1.5, "noche" to 1.5, "hombre" to 2.0, "mujer" to 2.0, "ciudad" to 2.0, "al" to 1.0, "amor" to 0.5, "vida" to 0.5, "también" to 2.0, "todo" to 1.5, "sin" to 1.0),
        "fr" to mapOf("le" to 2.0, "la" to 0.5, "les" to 2.0, "des" to 2.0, "du" to 1.5, "de" to 0.5, "et" to 2.0, "une" to 2.0, "pour" to 1.5, "avec" to 2.0, "dans" to 2.0, "sur" to 1.5, "au" to 1.0, "aux" to 2.0, "mon" to 1.0, "ma" to 1.0, "amour" to 1.5, "nuit" to 2.0, "homme" to 2.0, "femme" to 2.0, "vie" to 1.0, "mort" to 1.0, "est" to 1.0),
        "de" to mapOf("der" to 2.0, "die" to 2.0, "das" to 1.0, "und" to 2.0, "ein" to 2.0, "eine" to 2.0, "mit" to 1.5, "für" to 2.0, "von" to 1.5, "im" to 1.0, "zu" to 1.0, "nicht" to 2.0, "ist" to 1.5, "auf" to 1.5, "dem" to 2.0, "den" to 1.5, "liebe" to 1.5, "nacht" to 2.0, "tod" to 1.5),
        "it" to mapOf("il" to 2.0, "lo" to 1.0, "gli" to 2.0, "della" to 2.0, "delle" to 2.0, "dei" to 1.5, "di" to 1.5, "e" to 0.5, "una" to 1.0, "per" to 1.0, "che" to 1.0, "nel" to 2.0, "nella" to 2.0, "alla" to 2.0, "del" to 1.0, "la" to 0.5, "è" to 1.5, "bella" to 1.0, "amore" to 2.0, "notte" to 2.0, "uomo" to 2.0, "donna" to 2.0, "vita" to 1.0),
        "sl" to mapOf("in" to 0.5, "na" to 0.5, "za" to 1.0, "je" to 0.5, "ki" to 2.0, "v" to 1.0, "z" to 1.0, "od" to 1.0, "pri" to 1.5, "po" to 1.0, "ljubezen" to 2.0, "noč" to 2.0, "smrt" to 1.0, "življenje" to 2.0, "človek" to 2.0, "ali" to 0.5),
        JEZIK_HBS to mapOf("i" to 1.5, "u" to 1.5, "je" to 0.5, "na" to 0.5, "za" to 0.5, "od" to 0.5, "se" to 0.5, "sa" to 1.0, "ljubav" to 2.0, "noć" to 2.0, "život" to 2.0, "čovjek" to 2.0, "čovek" to 2.0),
    )

    /** Francoska elizija (l'amour, d'Amélie, qu'il ...): del pred opuscajem. */
    private val ELIZIJA_FR = setOf("l", "d", "qu", "j", "n")
    private val LOCILA_NASLOVA = Regex("[^\\p{L}\\p{M}\\p{N}']+")
    /** Zlozenka s pomisljajem med crkama (Homem-Aranha): sibek znak portugalscine. */
    private val ZLOZENKA = Regex("\\p{L}-\\p{L}")

    /** Jezik enega naslova (en, pt, es, fr, de, it, sl, [JEZIK_HBS]) ali "" (neodlocen: imena, kratki naslovi). */
    fun jezikNaslova(naslov: String): String {
        val s = java.text.Normalizer.normalize(naslov, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
        val tocke = HashMap<String, Double>()
        fun dodaj(jezik: String, v: Double) { tocke[jezik] = (tocke[jezik] ?: 0.0) + v }
        for ((znak, jezik, v) in ZNAKI_JEZIKA) if (s.indexOf(znak) >= 0) dodaj(jezik, v)
        if (ZLOZENKA.containsMatchIn(s)) dodaj("pt", 0.5)
        for (surova in s.split(LOCILA_NASLOVA)) {
            val beseda = surova.trim('\'')
            if (beseda.isEmpty() || beseda.all { it.isDigit() }) continue
            val deli = if ('\'' in beseda) {
                val pred = beseda.substringBefore('\''); val za = beseda.substringAfter('\'')
                if (pred in ELIZIJA_FR) dodaj("fr", 1.5)
                if (za == "s" || za == "t") dodaj("en", 1.0)
                listOf(pred, za)
            } else listOf(beseda)
            for (d in deli) {
                for ((jezik, besede) in BESEDE_JEZIKA) besede[d]?.let { dodaj(jezik, it) }
                for ((koncnica, jezik, v) in KONCNICE_JEZIKA) if (d.length > koncnica.length + 1 && d.endsWith(koncnica)) dodaj(jezik, v)
            }
        }
        val urejeno = tocke.entries.sortedByDescending { it.value }
        val prvi = urejeno.firstOrNull() ?: return ""
        val drugi = urejeno.getOrNull(1)?.value ?: 0.0
        return if (prvi.value >= PRAG_NASLOVA && prvi.value >= drugi * 1.5 && prvi.value > drugi) prvi.key else ""
    }

    /**
     * Jezik kataloga iz vzorca naslovov (najvec [VZOREC_JEZIKA]): jezik, v katerem je vsaj [VECINA_JEZIKA] odlocenih
     * naslovov, ce je odlocenih vsaj [NAJMANJ_ODLOCENIH] in desetina vzorca; sicer "" (neznan - katalog ostane viden kot
     * prej). Klic iz delovne niti.
     */
    fun jezikKataloga(naslovi: Collection<String>): String {
        val vzorec = naslovi.asSequence().take(VZOREC_JEZIKA).toList()
        val odloceni = vzorec.map { jezikNaslova(it) }.filter { it.isNotEmpty() }
        if (odloceni.size < NAJMANJ_ODLOCENIH || odloceni.size * 10 < vzorec.size) return ""
        val (jezik, n) = odloceni.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair() ?: return ""
        return if (n >= VECINA_JEZIKA * odloceni.size) jezik else ""
    }

    /** Ali jezik kataloga ([jezikKataloga]) ustreza izbranemu jeziku (ISO 639-1); [JEZIK_HBS] ustreza hr, sr in bs. */
    fun jezikKatalogaUstreza(jezikKataloga: String, izbrani: String): Boolean =
        jezikKataloga == izbrani || (jezikKataloga == JEZIK_HBS && izbrani in setOf("hr", "sr", "bs"))

    // ------------------------------------------------------------------ film ali serija iz metapodatkov (naloga 63)

    /** Obdobje predvajanja serije v `releaseInfo` (»2016–2021«, »2016-«); film ima eno letnico. */
    private val OBDOBJE_SERIJE = Regex("^\\s*(19|20)\\d{2}\\s*[-\u2013\u2014]\\s*((19|20)\\d{2})?\\s*$")

    /**
     * Vrsta naslova iz metapodatkov dodatka (lastnik, 22. 10. 2026: »Lucifer« med Filmi - dodatek ga v katalogu filmov
     * navede kot film): "series", "movie" ali null (ne vemo). Serija: ostevilcena epizoda v `videos` (sezona >= 1 in
     * epizoda >= 1), `type` "series" ali obdobje v `releaseInfo`. Film: `type` "movie" brez ostevilcenih epizod in brez
     * obdobja. [epizode] = (sezona, epizoda) vsakega vnosa v `videos`.
     */
    fun vrstaIzMeta(tip: String, izid: String, epizode: List<Pair<Int, Int>>): String? {
        if (epizode.any { (sezona, epizoda) -> sezona >= 1 && epizoda >= 1 }) return "series"
        val t = tip.trim().lowercase(java.util.Locale.ROOT)
        if (t == "series" || OBDOBJE_SERIJE.matches(izid)) return "series"
        return if (t == "movie") "movie" else null
    }

    // ------------------------------------------------------------------ jezik iz drzave izvora (naloga 66)

    /**
     * Drzave izvora (angleska imena, kot jih da Cinemeta v `country`) -> jeziki iz izbire jezika ([IzvirniJezik.KODE]).
     * Iste drzave kot v [IzvirniJezik.JEZIKI] (Wikidata), z imeni namesto predmetov; drzava z vec jeziki ima vse.
     */
    private val JEZIKI_DRZAV: Map<String, Set<String>> = buildMap<String, Set<String>> {
        fun d(jezik: String, vararg drzave: String) { for (x in drzave) put(x, (get(x) ?: emptySet()) + jezik) }
        d("en", "united states", "united states of america", "usa", "us", "united kingdom", "uk", "england", "scotland", "wales",
            "northern ireland", "canada", "australia", "ireland", "new zealand")
        d("sl", "slovenia", "yugoslavia", "federal republic of yugoslavia")
        d("de", "germany", "west germany", "east germany", "austria", "switzerland", "liechtenstein", "luxembourg")
        d("fr", "france", "belgium", "switzerland", "canada", "luxembourg", "monaco")
        d("es", "spain", "mexico", "argentina", "colombia", "chile", "peru", "venezuela", "cuba", "uruguay", "bolivia", "ecuador",
            "paraguay", "guatemala", "costa rica", "panama", "dominican republic", "puerto rico", "el salvador", "honduras", "nicaragua")
        d("it", "italy", "switzerland", "san marino")
        d("pt", "portugal", "brazil")
        d("hr", "croatia", "yugoslavia", "federal republic of yugoslavia")
        d("sr", "serbia", "montenegro", "serbia and montenegro", "yugoslavia", "federal republic of yugoslavia")
        d("bs", "bosnia and herzegovina", "yugoslavia", "federal republic of yugoslavia")
        d("mk", "north macedonia", "macedonia", "yugoslavia", "federal republic of yugoslavia")
        d("ru", "russia", "soviet union", "ussr")
        d("pl", "poland"); d("cs", "czech republic", "czechia", "czechoslovakia"); d("sk", "slovakia", "czechoslovakia")
        d("hu", "hungary"); d("nl", "netherlands", "belgium"); d("sv", "sweden"); d("da", "denmark"); d("no", "norway")
        d("fi", "finland"); d("is", "iceland"); d("tr", "turkey", "türkiye"); d("el", "greece", "cyprus")
        d("ro", "romania", "moldova"); d("bg", "bulgaria"); d("sq", "albania", "kosovo"); d("uk", "ukraine")
        d("ja", "japan"); d("ko", "south korea", "korea", "republic of korea", "north korea")
        d("zh", "china", "hong kong", "taiwan"); d("hi", "india"); d("ta", "india"); d("te", "india"); d("th", "thailand")
        d("ar", "egypt", "saudi arabia", "lebanon", "morocco", "algeria", "tunisia", "jordan", "syria", "iraq", "united arab emirates",
            "qatar", "kuwait", "palestine")
        d("he", "israel"); d("fa", "iran")
    }

    /** Drzave iz polja `country` (»United States, United Kingdom«; tudi ; ali /), male crke, brez »the«. */
    fun drzaveNaslova(niz: String): List<String> = niz.split(',', ';', '/').map { it.trim().lowercase(java.util.Locale.ROOT).removePrefix("the ").trim() }
        .filter { it.isNotEmpty() }.distinct()

    /**
     * Naloga 66 (lastnik, 22. 10. 2026: korejski film »Hope« med »English«): ali je naslov z drzavami izvora [drzave] v
     * jeziku [jezik] (ISO 639-1). true = katera koli drzava ustreza; false = vse drzave poznamo in nobena ne ustreza
     * (naslov izpade); null = ne vemo (brez drzav ali katere ne poznamo) - naslov ostane viden kot do zdaj.
     */
    fun jezikPoDrzavah(drzave: String, jezik: String): Boolean? {
        val d = drzaveNaslova(drzave)
        if (d.isEmpty() || jezik.isBlank()) return null
        if (d.any { jezik in JEZIKI_DRZAV[it].orEmpty() }) return true
        return if (d.all { it in JEZIKI_DRZAV }) false else null
    }

    // ------------------------------------------------------------------ seznam postaj ali ena postaja (naloga 68)

    /**
     * Ali je seznam .m3u/.pls seznam vec vsebin (vir »seznam«) ali ena postaja z zrcali. [imena] = ime vsakega vnosa iz
     * #EXTINF oziroma TitleN ("" = brez imena), [datotek] = koliko vnosov ima koncnico datoteke. Seznam je, kadar ima vsaj
     * polovica vnosov koncnico (kot do zdaj) ali kadar imajo vnosi vsaj dve RAZLICNI imeni (lastnik, 22. 10. 2026: seznam
     * Radio Browser s 142 slovenskimi postajami, tokovi brez koncnice, je bil ena postaja). Zrcala iste postaje (isto ime
     * ali brez imen) ostanejo ena postaja.
     */
    fun jeSeznamPostaj(imena: List<String>, datotek: Int): Boolean {
        if (imena.size <= 1) return false
        if (datotek * 2 >= imena.size) return true
        val razlicna = imena.map { it.trim().lowercase(java.util.Locale.ROOT).replace(PRESLEDKI, " ") }.filter { it.isNotEmpty() }.toSet()
        return razlicna.size >= 2
    }

    private val PRESLEDKI = Regex("\\s+")
    /** Oznake seznama IPTV (tvg-id, tvg-logo, group-title): vnos je televizijski kanal, ne radio. */
    private val OZNAKE_IPTV = Regex("(?i)tvg-|group-title")

    /**
     * Ali je vnos seznama radijski tok: naslov brez koncnice datoteke (http://live.radio.si/Radio1) in brez oznak seznama
     * IPTV v vrstici #EXTINF ([extinf]; "" pri .pls). Kanal TV iz seznama IPTV in datoteka (.mp3, .m3u8 ...) nista radio.
     */
    fun jeRadijskiVnos(imaKoncnico: Boolean, extinf: String): Boolean = !imaKoncnico && !OZNAKE_IPTV.containsMatchIn(extinf)

    // ------------------------------------------------------------------ imenik radijskih postaj kot vir (naloga 69)

    /** Imenik postaj, ki ga uporabnik doda sam (naslov vira vrste »imenik«): Radio Browser ali TuneIn. */
    const val IMENIK_RADIO_BROWSER = "radio-browser"
    const val IMENIK_TUNEIN = "tunein"

    /**
     * Ali je vnos v »Dodaj vir« imenik radijskih postaj: radio-browser.info (tudi de1.api.radio-browser.info, naslov
     * njegovega API) ali tunein.com / radiotime.com (OPML). Seznam postaj (.m3u/.pls, pot /m3u/ ali /pls/ - naloga 68)
     * in tok ene postaje (Tune.ashx) nista imenik: dodata se kot doslej. Vrne [IMENIK_RADIO_BROWSER], [IMENIK_TUNEIN]
     * ali null.
     */
    fun imenikPostaj(vnos: String): String? {
        // Tipkovnica na TV (samopopravki) rada vrine presledek za piko ("radio-browser. info"): naslov presledkov nima.
        val v = vnos.trim().lowercase(java.util.Locale.ROOT).filter { it.code in 0x21..0x7E }
        if (v.isEmpty() || v.contains("{q}")) return null
        val brezSheme = v.removePrefix("https://").removePrefix("http://")
        val gostitelj = brezSheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').trimEnd('.')
        val pot = brezSheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        fun je(d: String) = gostitelj == d || gostitelj.endsWith(".$d")
        return when {
            je("radio-browser.info") ->
                if (pot.startsWith("m3u/") || pot.startsWith("pls/") || pot.endsWith(".m3u") || pot.endsWith(".pls")) null else IMENIK_RADIO_BROWSER
            je("tunein.com") || je("radiotime.com") -> if (pot.startsWith("tune.ashx")) null else IMENIK_TUNEIN
            else -> null
        }
    }

    // ------------------------------------------------------------------ ista postaja iz vec imenikov (naloga 71)

    private val FREKVENCA_V_OKLEPAJU = Regex("\\([^)]*\\d+([.,]\\d+)?\\s*(mhz|fm)[^)]*\\)")
    private val FREKVENCA = Regex("\\b\\d{2,3}([.,]\\d{1,2})\\s*(mhz|fm)?(?=\\W|$)|\\b(mhz|fm)\\b")
    private val NAGLASI = Regex("\\p{Mn}+")
    private val BESEDA_POSTAJE = Regex("[a-z0-9]+")
    private val FM_CELO = Regex("8[7-9]|9[0-9]|10[0-8]")
    private val ZVRST_V_OKLEPAJU = Regex("\\((rock|pop|news|talk|world music|classical|classic rock|jazz|dance|electronic|country|oldies|hits|top 40|" +
        "adult contemporary|adult hits|folk|sports|christian|alternative|metal|hip hop|r&b|reggae|latin|blues|soul|variety|public|ethnic|schlager|" +
        "easy listening|children|community)\\)\\s*$")
    private val CRKA = Regex("[a-z]")

    /**
     * Kljuc imena postaje (lastnik, 23. 10. 2026: Radio 1, Val 202, Rock Radio dvakrat - Radio Browser in TuneIn): male crke,
     * brez naglasov, brez frekvence (»105.2«, »(98.9MHz)«, »87.6 FM«), brez besede »radio« in brez locil in presledkov.
     * »Radio« ostane, ce bi brez nje ostale samo stevilke (»Radio 1« -> »radio1«, ne »1«). Prazno = imena ni.
     */
    fun kljucImenaPostaje(ime: String): String {
        var t = java.text.Normalizer.normalize(ime.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
        t = NAGLASI.replace(t, "")
        t = FREKVENCA_V_OKLEPAJU.replace(t, " ")
        // TuneIn doda zvrst v oklepaju na konec (»Radio 1 ROCK 87 (Rock)«, »Rock Radio Si 103.3 (Rock)«).
        t = ZVRST_V_OKLEPAJU.replace(t, " ")
        t = FREKVENCA.replace(t, " ")
        val besede = BESEDA_POSTAJE.findAll(t).map { it.value }.toList()
            // Celo stevilo v obmocju FM na koncu je frekvenca (»... 87«), »Val 202« in »Radio 1« ostaneta.
            .let { b -> if (b.size > 1 && FM_CELO.matches(b.last()) && b.dropLast(1).any { CRKA.containsMatchIn(it) }) b.dropLast(1) else b }
        val brez = besede.filter { it != "radio" }
        val k = if (brez.any { CRKA.containsMatchIn(it) }) brez else besede
        // Drzava na koncu imena (»Rock Radio Slovenia«, »Rock Radio SLO«, »ROCK RADIO SI« - po icy-name isti ROCK RADIO,
        // izmerjeno 23. 10. 2026) ne loci postaj; »Rock Radio Best Ballads SLO« ostane »rockbestballads«.
        val brezDrzave = if (k.size > 1 && k.last() in DRZAVNE_PRIPONE) k.dropLast(1) else k
        return (if (brezDrzave.any { CRKA.containsMatchIn(it) }) brezDrzave else k).joinToString("")
    }

    private val DRZAVNE_PRIPONE = setOf("si", "slo", "slovenia", "slovenija", "hr", "cro", "croatia", "hrvatska", "rs", "srb", "serbia",
        "srbija", "ba", "bih", "at", "aut", "austria", "de", "ger", "germany", "it", "ita", "italy", "italia", "uk", "usa", "us", "fr", "france")

    /** Kljuc naslova toka: gostitelj in pot (brez sheme, www., poizvedbe in koncne posevnice), male crke. Prazno = toka se ne vemo. */
    fun kljucTokaPostaje(naslov: String): String {
        var t = naslov.trim().lowercase(java.util.Locale.ROOT)
        t = t.substringAfter("://", t).substringBefore('?').substringBefore('#').removePrefix("www.")
        return t.trimEnd('/')
    }

    /**
     * Naloga 71: postaje iz Radio Browserja, TuneIna, dodatkov in seznamov .m3u (naloga 68) brez dvojnikov. Ista postaja je
     * ista po kljucu imena ([kljucImenaPostaje]) ali po kljucu toka ([kljucTokaPostaje]); razlicni imeni (»Rock Radio« in
     * »Rock Radio Best Ballads«) nista ista. Vrstni red ostane (prva pojavitev); ce je ohranjena postaja brez neposrednega
     * toka (TuneIn, tok se razresi ob kliku), dvojnik z neposrednim tokom ([neposreden]) stopi na njeno mesto.
     */
    fun <T> zdruziPostaje(postaje: List<T>, ime: (T) -> String, tok: (T) -> String, neposreden: (T) -> Boolean): List<T> {
        val izid = ArrayList<T>(postaje.size)
        val poImenu = HashMap<String, Int>()
        val poToku = HashMap<String, Int>()
        for (p in postaje) {
            val ki = kljucImenaPostaje(ime(p))
            val kt = kljucTokaPostaje(tok(p))
            val j = (if (ki.isNotEmpty()) poImenu[ki] else null) ?: (if (kt.isNotEmpty()) poToku[kt] else null)
            if (j != null) {
                if (!neposreden(izid[j]) && neposreden(p)) {
                    izid[j] = p
                    if (ki.isNotEmpty()) poImenu[ki] = j
                    if (kt.isNotEmpty()) poToku[kt] = j
                }
                continue
            }
            izid += p
            if (ki.isNotEmpty()) poImenu[ki] = izid.size - 1
            if (kt.isNotEmpty()) poToku[kt] = izid.size - 1
        }
        return izid
    }

    /**
     * Lastnik, 23. 10. 2026: od iste postaje obdrzimo samo najboljsi tok. Isto ime ([kljucImenaPostaje]) -> ostane vnos z
     * najvecjo [kakovost] (bitna hitrost); ob enaki kakovosti prvi (Radio Browser: najvec poslusan). Mesto v seznamu je
     * mesto prve pojavitve imena. Vnosi brez imena ostanejo vsi.
     */
    fun <T> najboljsiTokovi(postaje: List<T>, ime: (T) -> String, kakovost: (T) -> Int): List<T> {
        val izid = ArrayList<T>(postaje.size)
        val mesto = HashMap<String, Int>()
        for (p in postaje) {
            val k = kljucImenaPostaje(ime(p))
            val j = if (k.isEmpty()) null else mesto[k]
            if (j == null) { if (k.isNotEmpty()) mesto[k] = izid.size; izid += p }
            else if (kakovost(p) > kakovost(izid[j])) izid[j] = p
        }
        return izid
    }

    // ------------------------------------------------------------------ podnapisi iz dodatkov Stremio (naloga 72)

    /**
     * (tip, id) za /subtitles/{tip}/{id}.json po protokolu Stremio: film »movie« / tt..., epizoda »series« / tt...:S:E.
     * null = naslov brez IMDb (dodatki za podnapise, npr. OpenSubtitles v3, iscejo po IMDb).
     */
    fun idPodnapisov(imdb: String, sezona: Int, epizoda: Int): Pair<String, String>? {
        val i = imdb.trim()
        if (!(i.startsWith("tt") && i.length > 2 && i.drop(2).all { it.isDigit() })) return null
        return if (sezona > 0 && epizoda > 0) "series" to "$i:$sezona:$epizoda" else "movie" to i
    }

    /** Bibliografske in posebne kode ISO 639-2, ki jih dodatki dajejo, Java pa jih ne pozna (OpenSubtitles: pob, scc ...). */
    private val KODE_PODNAPISOV = mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "cze" to "cs", "slo" to "sk", "chi" to "zh",
        "zht" to "zh", "zhe" to "zh", "scc" to "sr", "scr" to "hr", "rum" to "ro", "gre" to "el", "per" to "fa", "alb" to "sq",
        "mac" to "mk", "ice" to "is", "pob" to "pt", "pom" to "pt", "spn" to "es", "ea" to "es", "ze" to "zh")

    /** Jezik podnapisa iz dodatka kot ISO 639-1, kadar ga poznamo (»eng« -> »en«, »slv« -> »sl«, »pob« -> »pt«). */
    fun jezikPodnapisa(koda: String): String {
        val k = koda.trim().lowercase(java.util.Locale.ROOT)
        return KODE_PODNAPISOV[k] ?: JezikiVsebine.oznaka(k)
    }

    data class PodnapisDodatka(val url: String, val jezik: String)

    /**
     * Vnosi odgovora dodatka {subtitles: [{id, url, lang}]} kot (url, lang): samo naslovi http(s) brez kontrolnih znakov in z
     * jezikom; jezik v ISO 639-1 ([jezikPodnapisa]); isti naslov enkrat. Vrstni red dodatka ostane (najboljsi prvi).
     */
    fun podnapisiDodatka(vnosi: List<Pair<String, String>>): List<PodnapisDodatka> = vnosi.mapNotNull { (url, lang) ->
        val u = url.trim()
        val j = jezikPodnapisa(lang)
        if ((u.startsWith("https://") || u.startsWith("http://")) && u.length <= 2048 && url.none { it < ' ' } && j.isNotEmpty() && j.length <= 3)
            PodnapisDodatka(u, j) else null
    }.distinctBy { it.url }

    /** Jeziki za izbiro: jezik vmesnika, nato anglescina, nato ostali po abecedi (kode); vsak enkrat. */
    fun jezikiPodnapisov(kode: List<String>, vmesnik: String): List<String> {
        val vse = kode.filter { it.isNotBlank() }.distinct()
        val prvi = listOf(vmesnik, "en").distinct().filter { it in vse }
        return prvi + vse.filter { it !in prvi }.sorted()
    }

    /**
     * Naloga 90a: najvec [n] katalogov, a tisti, ki imajo [prednost] (video koncerti Music Concerts), so prvi - prej jih je
     * rez na prvih desetih katalogih izpustil, ce je imel uporabnik vec glasbenih katalogov (YouTubio).
     */
    fun <T> katalogiZPrednostjo(vsi: List<T>, n: Int, prednost: (T) -> Boolean): List<T> {
        val prvi = vsi.filter(prednost).take(n)
        return prvi + vsi.filterNot(prednost).take((n - prvi.size).coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ naloga 83: Music (koncerti, seznami, takoj z diska)

    /** Vir uvozenega seznama predvajanja (UvozSeznama: »YouTube«, »Spotify«). */
    fun jeUvozenSeznam(vir: String): Boolean = vir.trim().lowercase(java.util.Locale.ROOT) in setOf("youtube", "spotify")

    /**
     * Naloga 83 (lastnik: »od Music ne prikaze nic, niti nasega Spotify in YouTube«): kateri seznami predvajanja ([vir], [cas])
     * so na polici na vrhu Music, po vrsti - najprej uvozeni (Spotify, YouTube), nato ostali, vsaka skupina od najnovejsega;
     * najvec [najvec]. Vrne indekse v vhodnem seznamu.
     */
    fun seznamiNaVrhuGlasbe(seznami: List<Pair<String, Long>>, najvec: Int): List<Int> =
        seznami.indices.sortedWith(compareBy<Int> { if (jeUvozenSeznam(seznami[it].first)) 0 else 1 }.thenByDescending { seznami[it].second })
            .take(najvec.coerceAtLeast(0))

    /** Ali gre seznam v Music: uvozen (tudi YouTube s samimi posnetki) ali ima vsaj eno skladbo, ki ni video. */
    fun seznamZaGlasbo(vir: String, vsiVideo: Boolean): Boolean = jeUvozenSeznam(vir) || !vsiVideo

    /**
     * Naloga 83: filter jezika mreze Music. [jezikVnosa] = oznaka ([JezikiVsebine.oznaka]) ali prazno. Vnos dodatka brez
     * oznake jezika (koncert iz Music Concerts) ostane - prej ga je izbran jezik vedno skril.
     */
    fun jezikGlasbeUstreza(jezikVnosa: String, izbran: String, izDodatka: Boolean): Boolean =
        izbran.isEmpty() || jezikVnosa == izbran || (izDodatka && jezikVnosa.isEmpty())

    // ------------------------------------------------------------------ naloga 84: Radio hitro

    /**
     * Naloga 84: koliko ms prva stran Radia caka na imenika (Radio Browser, TuneIn). Brez mreze na zaslonu kratko (kar
     * ne pride, se doda na konec mreze), z mrezo z diska ali iz pomnilnika na vse (sveza stran jo zamenja enkrat).
     */
    fun rokImenikaRadia(prva: Boolean, naZaslonu: Boolean): Long = if (prva && !naZaslonu) 3_000L else 25_000L

    /** Naloga 90b: najvec kartic mreze na disku (prej 60 = ena stran, zato je Radio po zagonu imel 60 postaj namesto vseh). */
    const val ZAPIS_MREZE_NAJVEC = 300

    /** Naloga 90b: kartice mreze za zapis na disk - cela mreza prve strani (z dodatki in pozni viri), ne samo prve strani. */
    fun <T> zapisMreze(vsi: List<T>): List<T> = vsi.take(ZAPIS_MREZE_NAJVEC)

    /**
     * Naloga 90b: ali prva stran mreze ze gre na disk. Ce so pozni viri se v teku in je mreza ze z diska ali iz pomnilnika,
     * zapis pocaka na polno mrezo (zapis le prve strani bi povozil vecjo).
     */
    fun shraniPrvoStranMreze(viriVTeku: Boolean, izPredpomnilnika: Boolean): Boolean = !(viriVTeku && izPredpomnilnika)

    /** Naloga 84: vrstni red streznikov imenika - zadnji, ki je odgovoril, najprej; ostali po vrsti (brez dvojnikov). */
    fun vrstniRedStreznikov(strezniki: List<String>, zadnjiDober: String?): List<String> =
        (listOfNotNull(zadnjiDober?.takeIf { it in strezniki }) + strezniki).distinct()

    // ------------------------------------------------------------------ naloga 86: popravki pregleda 85

    /**
     * Naloga 86 (M3): cakanje z rokom [rokMs] po kosih do [kosMs]: [poskusi] dobi dovoljeni cas (ms) in vrne izid ali null
     * (se ni). Pred vsakim kosom [prekinjen] (preklicana mreza) cakanje konca takoj z null - nit se sprosti v najvec [kosMs]
     * namesto ob roku (prej do 30 ali 90 s). [ura] = monotona ura v ms.
     */
    fun <T> cakajPoKoscih(rokMs: Long, kosMs: Long = 1_000L, ura: () -> Long, prekinjen: () -> Boolean, poskusi: (Long) -> T?): T? {
        val konec = ura() + rokMs
        while (true) {
            if (prekinjen()) return null
            val ostane = konec - ura()
            if (ostane <= 0) return null
            poskusi(minOf(kosMs, ostane).coerceAtLeast(1))?.let { return it }
        }
    }

    /** Naloga 86 (M4): najvec kartic v predpomnilniku strani katalogov kanalov (vse strani skupaj). */
    const val NAJVEC_KARTIC_STRANI_KANALOV = 10_000

    /** Naloga 86 (M4): »velikost« strani v predpomnilniku = stevilo kartic (prazna stran steje 1, da je meja koncna). */
    fun velikostStraniKanalov(kartic: Int): Int = kartic.coerceAtLeast(0) + 1

    /**
     * Naloga 86 (M6): opis zavrnjenega vnosa vira za dnevnik - samo dolzina in razredi znakov, NIKOLI vsebina (povezava IPTV
     * ima lahko uporabnisko ime in geslo, dodatek kljuc storitve). Neznani (ne-ASCII) znaki samo po vrsti Unicode (npr. FORMAT
     * za nevidni presledek ze tipkovnice), brez kode znaka.
     */
    fun opisZavrnjenegaVnosa(vnos: String): String {
        var crk = 0; var stevk = 0; var presledkov = 0; var locil = 0; var kontrolnih = 0; var neAscii = 0
        val vrste = java.util.TreeSet<String>()
        for (c in vnos) when {
            c.code < 0x20 || c.code == 0x7F -> kontrolnih++
            c.code >= 0x80 -> { neAscii++; vrste += vrstaZnaka(c) }
            c == ' ' -> presledkov++
            c in 'a'..'z' || c in 'A'..'Z' -> crk++
            c in '0'..'9' -> stevk++
            else -> locil++
        }
        return "dolzina=${vnos.length}, crk=$crk, stevk=$stevk, locil=$locil, presledkov=$presledkov, kontrolnih=$kontrolnih, " +
            "ne-ASCII=$neAscii" + (if (vrste.isEmpty()) "" else " ${vrste.joinToString(",", "(", ")")}")
    }

    private fun vrstaZnaka(c: Char): String = when (Character.getType(c).toByte()) {
        Character.FORMAT -> "FORMAT"
        Character.SPACE_SEPARATOR -> "PRESLEDEK"
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.OTHER_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER -> "CRKA"
        Character.DECIMAL_DIGIT_NUMBER, Character.OTHER_NUMBER, Character.LETTER_NUMBER -> "STEVKA"
        Character.SURROGATE -> "NADOMESTNI"
        Character.CONTROL -> "KONTROLNI"
        else -> "DRUGO"
    }

    // ------------------------------------------------------------------ naloga 88: Live TV, Music, kanal ni dosegljiv

    /** Najdlje dopolnitev mreze caka na prvo risanje (po korakih); potem dopolni brez cakanja (varovalka). */
    const val ODLOZI_RISANJE_MS = 2_000L

    /** Ali dopolnitev mreze se pocaka na prvo risanje, ki caka od [odMs] (uptime); ura nazaj = ne caka. */
    fun odloziDopolnitev(odMs: Long, zdajMs: Long): Boolean = zdajMs - odMs in 0 until ODLOZI_RISANJE_MS

    /**
     * Naloga 88: rok za tokove vseh dodatkov ob kliku. Kanal v zivo 7 s (uporabnik v 8 s izve, da ni dosegljiv - prej 12 s
     * in nato tiho); film, serija in drugo 12 s kot doslej (pocasni dodatki z vec viri).
     */
    fun rokTokovMs(vZivo: Boolean): Long = if (vZivo) 7_000L else 12_000L

    /**
     * Naloga 88: izid prve strani kataloga za dnevnik: [vnosov] (null = izpad), [koda] HTTP zadnjega zahtevka (0 = brez),
     * [varovalka] = dodatek je izklopljen, ker zapored ne odgovarja.
     */
    fun izidKataloga(vnosov: Int?, koda: Int, varovalka: Boolean): String = when {
        vnosov != null && vnosov > 0 -> "$vnosov vnosov"
        vnosov != null -> "prazno (dodatek je odgovoril brez vnosov${if (koda == 404) ", 404" else ""})"
        koda == 0 && varovalka -> "ni vprasan (varovalka: dodatek zapored ne odgovarja)"
        koda == 0 -> "ni odgovora (cas, omrezje)"
        koda == 429 || koda == 403 -> "omejitev dodatka (HTTP $koda)"
        else -> "napaka (HTTP $koda)"
    }

    /** Napis nad mrezo Glasbe ali Radia. */
    enum class OpisMreze { NIC, NALAGAM, JEZIK, BREZ_VIROV, SAMO_SEZNAMI, PRAZNO }

    /**
     * Naloga 88 (TV 08:50: »Nothing found« pod sestimi seznami predvajanja): prazna mreza s seznami predvajanja na zaslonu
     * (polica na vrhu Music, naloga 83) ni »nic najdeno« - napis pove, da so tu seznami, ostalo pride z viri.
     */
    fun opisPrazneMreze(imaKartice: Boolean, nalagam: Boolean, jezikIzbran: Boolean, brezVirov: Boolean, imaSezname: Boolean): OpisMreze = when {
        imaKartice -> OpisMreze.NIC
        nalagam -> OpisMreze.NALAGAM
        jezikIzbran -> OpisMreze.JEZIK
        imaSezname -> OpisMreze.SAMO_SEZNAMI
        brezVirov -> OpisMreze.BREZ_VIROV
        else -> OpisMreze.PRAZNO
    }

    // ------------------------------------------------------------------ naloga 89: posnetki uvozenih seznamov vnaprej

    /** Skladba, za katero iskanje posnetka ni uspelo, se isce znova sele cez toliko casa (ne ob vsakem odprtju Music). */
    const val ISKANJE_ZNOVA_MS = 7L * 24 * 3_600_000

    /** Ali skladbi znova iscemo posnetek: se nikoli ni odpovedalo, ali je minil [ISKANJE_ZNOVA_MS] (ura nazaj: da). */
    fun poskusiZnovaIskanje(neuspehOb: Long?, zdaj: Long): Boolean =
        neuspehOb == null || zdaj - neuspehOb !in 0 until ISKANJE_ZNOVA_MS

    /**
     * Naloga 89: vrstni red iskanja posnetkov za vec seznamov - izmenicno po polozaju (prva skladba vsakega seznama, nato
     * druga ...), da je vsak seznam takoj predvajljiv od zacetka; brez ponovitev, najvec [najvec].
     */
    fun vrstniRedIskanja(seznami: List<List<String>>, najvec: Int): List<String> {
        val izhod = LinkedHashSet<String>()
        val najdaljsi = seznami.maxOfOrNull { it.size } ?: 0
        for (i in 0 until najdaljsi) {
            for (s in seznami) {
                if (izhod.size >= najvec) return izhod.toList()
                s.getOrNull(i)?.let { izhod += it }
            }
        }
        return izhod.toList()
    }

    // ------------------------------------------------------------------ naloga 94: namestitev dodatka iz Spleta

    /** Spletni Stremio (web.stremio.com, app.strem.io): stran »#/addons?addon=<naslov manifesta>« namesti dodatek. */
    private val GOSTITELJI_SPLETNEGA_STREMIA = setOf("web.stremio.com", "app.strem.io", "web.strem.io")

    /**
     * Naloga 94: naslov manifesta dodatka Stremio iz navigacije v Spletu (gumb »Install« na straneh z dodatki, npr.
     * stremio-addons.net): »stremio://gostitelj/.../manifest.json« -> isti naslov s https; spletni Stremio
     * »https://web.stremio.com/#/addons?addon=<kodiran naslov>«; neposreden »http(s)://.../manifest.json«. Vrne naslov
     * manifesta (http ali https, brez dela za »#«) ali null, ce navigacija ni namestitev dodatka.
     */
    fun naslovDodatka(url: String): String? {
        val u = url.trim()
        val malo = u.lowercase(java.util.Locale.ROOT)
        if (malo.startsWith("stremio://")) return manifestDodatka("https://" + u.substring("stremio://".length))
        if (!malo.startsWith("http://") && !malo.startsWith("https://")) return null
        val gostitelj = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase(java.util.Locale.ROOT)
        if (gostitelj in GOSTITELJI_SPLETNEGA_STREMIA) {
            val del = u.substringAfter('#', "")
            if (!del.startsWith("/addons")) return null
            val vrednost = del.substringAfter('?', "").split('&').firstOrNull { it.startsWith("addon=") }?.removePrefix("addon=") ?: return null
            val naslov = try { java.net.URLDecoder.decode(vrednost, "UTF-8") } catch (_: Exception) { return null }
            // Spletni Stremio z naslovom, ki ni dodatek (ali spet spletni Stremio), ni namestitev.
            return naslovDodatka(naslov)?.takeIf { n -> GOSTITELJI_SPLETNEGA_STREMIA.none { n.lowercase(java.util.Locale.ROOT).contains("://$it") } }
        }
        return manifestDodatka(u)
    }

    /** http(s) naslov z gostiteljem, katerega pot (brez »?« in »#«) se konca z »/manifest.json«; brez dela za »#«. */
    private fun manifestDodatka(u: String): String? {
        val brezDela = u.substringBefore('#')
        val malo = brezDela.lowercase(java.util.Locale.ROOT)
        if (!malo.startsWith("http://") && !malo.startsWith("https://")) return null
        val poGostitelju = brezDela.substringAfter("://")
        val gostitelj = poGostitelju.substringBefore('/').substringBefore('?')
        if (gostitelj.isBlank() || gostitelj.any { it.isWhitespace() }) return null
        val pot = poGostitelju.removePrefix(gostitelj).substringBefore('?')
        return brezDela.takeIf { pot.lowercase(java.util.Locale.ROOT).endsWith("/manifest.json") }
    }

    // ------------------------------------------------------------------ naloga 95: kanal v zivo, ki streznik sproti zapira

    /** Najvec zaporednih ponovnih povezav kanala v zivo brez novih podatkov; potem je konec (kanal ne deluje). */
    const val PONOVITEV_V_ZIVO_NAJVEC = 5
    /** Naloga 97: najvec toliko ponovnih povezav istega vira v [PONOVITEV_V_ZIVO_OKNO_MS], ne glede na nove podatke. */
    const val PONOVITEV_V_ZIVO_OKNO_NAJVEC = 10
    const val PONOVITEV_V_ZIVO_OKNO_MS = 120_000L
    /** Naloga 97: nadomestna slika operaterja (prepoved, »ni na voljo«) - toliko zaporednih tokov, vsak krajsi od [NADOMESTEK_NAJDLJE_MS]. */
    const val NADOMESTEK_ZAPOREDNIH = 3
    const val NADOMESTEK_NAJDLJE_MS = 15_000L

    /**
     * Naloga 97 (meritev 23. 10. 14:31: »Zabranjen vam je pristup« - streznik vrti 5-sekundno sliko namesto programa): kanal v
     * zivo, kjer se tok vsakic konca po skoraj enakem casu, je SUMLJIV (naloga 100: dokaz da [nadomestekPoOdtisih]). Naloga 102:
     * v tej izdaji se ne klice - nadomestno sliko konca meja ponovitev ([PONOVITEV_V_ZIVO_OKNO_NAJVEC]). [trajanja] = koliko je predvajal vsak dosedanji
     * tok (ms, po vrsti). Nadomestek: zadnjih [NADOMESTEK_ZAPOREDNIH], vsak krajsi od [NADOMESTEK_NAJDLJE_MS] in razlika med
     * najdaljsim in najkrajsim manj kot 10 % najdaljsega.
     */
    fun nadomestnaSlikaVZivo(trajanja: List<Long>): Boolean {
        val zadnja = trajanja.takeLast(NADOMESTEK_ZAPOREDNIH)
        if (zadnja.size < NADOMESTEK_ZAPOREDNIH) return false
        val najdaljse = zadnja.maxOf { it }
        val najkrajse = zadnja.minOf { it }
        return najkrajse > 0 && najdaljse < NADOMESTEK_NAJDLJE_MS && (najdaljse - najkrajse) * 10 < najdaljse
    }

    /** Naloga 100: razmik med branjema zacetka toka in najmanj prebranih bajtov, da odtisa stejeta kot dokaz. */
    const val NADOMESTEK_RAZMIK_MS = 6_000L
    const val NADOMESTEK_NAJMANJ_BAJTOV = 16 * 1024
    /** Naloga 100: koliko bajtov zacetka toka preberemo za odtis in najvec preverb odtisa na vir. */
    const val NADOMESTEK_BAJTOV = 64 * 1024
    const val NADOMESTEK_PREVERB_NAJVEC = 2

    /**
     * Naloga 100 (pregled 231, S1): vsebinski dokaz nadomestne slike. Ob sumu ([nadomestnaSlikaVZivo] - samo trajanje ni
     * dovolj: pravi CBR MPEG-TS, ki mu streznik zapira povezavo, ima tudi enaka kratka trajanja) dvakrat preberemo zacetek toka
     * in primerjamo odtisa (SHA-256). Pravi prenos v zivo ima po [razmikMs] drugacen zacetek, nadomestna slika (datoteka, ki
     * se vrti) enakega. true = nadomestek, false = pravi prenos, null = ni dokaza (napaka branja, premalo podatkov, prekratek
     * razmik) - kanala takrat ne oznacimo. Naloga 102: v tej izdaji se ne klice (sonda je odstranjena iz poti predvajanja).
     */
    fun nadomestekPoOdtisih(prvi: String?, drugi: String?, bajtovPrvi: Int, bajtovDrugi: Int, razmikMs: Long): Boolean? {
        if (prvi.isNullOrEmpty() || drugi.isNullOrEmpty()) return null
        if (bajtovPrvi < NADOMESTEK_NAJMANJ_BAJTOV || bajtovDrugi < NADOMESTEK_NAJMANJ_BAJTOV || razmikMs < NADOMESTEK_RAZMIK_MS) return null
        return prvi == drugi
    }

    /**
     * Naloga 103 (pregled 102, tocka 1): ali se kanal v zivo ob meji ponovitev tiho konca. Samo, ce ni druge moznosti: pri
     * napaki vira ([napaka]) rezervni tok kanala dodatka ([imaRezervo]), sicer naslednji element vrste ([imaNaslednjega]) -
     * takrat gre obstojeca pot (rezerva, naslednji element), kot pred nalogo 102. Pregled 103, tocka 2: napaka vira brez
     * rezerve in naslednjega NI tih konec - obstojeca pot pokaze razlog (npr. HTTP 503), uporabnik ve, zakaj se je ustavilo.
     * Tih konec ostane samo za konec toka brez napake (nadomestna slika operaterja).
     */
    fun tihKonecPoMeji(napaka: Boolean, imaRezervo: Boolean, imaNaslednjega: Boolean): Boolean =
        !imaNaslednjega && !napaka

    /** Naloga 100: zamik ponovne povezave po izpadu omrezja (rok, zavrnjena povezava). */
    const val ZAMIK_PONOVITVE_OMREZJE_MS = 1_500L

    /**
     * Naloga 100 (pregled 231, N8) + 101 (pregled 100, S1): kdaj ponovimo kanal v zivo po napaki vira [koda] (Media3
     * PlaybackException; 0 = konec toka) z odgovorom [http] (pri 2004, 0 = ne vemo). null = ne ponovi, takoj obstojeca pot:
     * 2004 s trajnim odgovorom (401, 403, 404, 410 in drugi 4xx), 2005 ni datoteke, 2006 ni dovoljenja, 2007 cleartext.
     * Cez [ZAMIK_PONOVITVE_OMREZJE_MS]: 2004 z zacasnim odgovorom (5xx - tudi 509 »prevec povezav« -, 408, 429) in izpad
     * omrezja (2001 povezava ni uspela, 2002 rok). Sicer takoj (0).
     */
    fun zamikPonovitveVZivo(koda: Int, http: Int = 0): Long? = when (koda) {
        2004 -> if (http in 500..599 || http == 408 || http == 429) ZAMIK_PONOVITVE_OMREZJE_MS else null
        2005, 2006, 2007 -> null
        2001, 2002 -> ZAMIK_PONOVITVE_OMREZJE_MS
        else -> 0L
    }

    /** Ponovitev, pred katero je predvajalnik predvajal vsaj toliko, je prinesla nove podatke (stevec gre na zacetek). */
    const val NOVI_PODATKI_V_ZIVO_MS = 2_000L

    /**
     * Naloga 95 (meritev 23. 10. 14:12: surov MPEG-TS prek http, streznik povezavo zapre po ~1 MB, VLC se poveze znova):
     * ali kanal v zivo s progresivnim virom ([progresivno]: ne HLS ali DASH) ob koncu toka ali napaki vira med predvajanjem
     * ([zacelo]) znova pripravimo. [prejsnjih] = zaporedne ponovitve brez novih podatkov do zdaj, [predvajanoMs] = koliko je
     * predvajal od zadnje (ponovne) povezave. Vrne zaporedno stevilko te ponovitve brez novih podatkov (1 ..
     * [PONOVITEV_V_ZIVO_NAJVEC]) ali 0 = ne ponovi (film, HLS, se ni zacelo ali prevec ponovitev brez podatkov).
     */
    fun ponovnaPovezavaVZivo(vZivo: Boolean, progresivno: Boolean, zacelo: Boolean, prejsnjih: Int, predvajanoMs: Long,
                             /** Naloga 97: casi prejsnjih ponovitev tega vira (elapsedRealtime) in zdaj - meja [PONOVITEV_V_ZIVO_OKNO_NAJVEC]. */
                             casiPonovitev: List<Long> = emptyList(), zdaj: Long = 0L): Int {
        if (!vZivo || !progresivno || !zacelo) return 0
        // Naloga 97: tok, ki se vsakic konca po 5 s, je stevec brez podatkov vedno ponastavil - zanka ni nikoli koncala.
        if (casiPonovitev.count { zdaj - it in 0 until PONOVITEV_V_ZIVO_OKNO_MS } >= PONOVITEV_V_ZIVO_OKNO_NAJVEC) return 0
        val brez = if (predvajanoMs >= NOVI_PODATKI_V_ZIVO_MS) 0 else prejsnjih.coerceAtLeast(0)
        return if (brez >= PONOVITEV_V_ZIVO_NAJVEC) 0 else brez + 1
    }

    // ------------------------------------------------------------------ naloga 104: zaslon predvajanja v zivo

    /**
     * Naloga 104: zaslon predvajanja kaze »V zivo« namesto drsnika in casa (»0:10 / 0:59«) in skrije -10/+10 s, ce predvajalnik
     * pove, da je posnetek v zivo ([isLive]) ali ima dinamicno okno (HLS v zivo, [dinamicno]), ali je to radio ali kanal v zivo
     * ([radioAliKanal]: postaja, vgrajen kanal, kanal dodatka ali seznama IPTV).
     */
    fun prikazVZivo(isLive: Boolean, dinamicno: Boolean, radioAliKanal: Boolean): Boolean = isLive || dinamicno || radioAliKanal

    // ------------------------------------------------------------------ naloga 114: odlocitev kanala v zivo na enem mestu

    /** Naloga 115: najdaljsi zamik ponovne povezave (narascajoc zamik se tu ustavi). */
    const val ZAMIK_PONOVITVE_NAJVEC_MS = 12_000L

    /**
     * Naloga 115 (pregled 101, #6: stalni zamik je za ponudnika z omejitvijo »blago agresiven«): zamik se ob vsaki zaporedni
     * ponovitvi brez novih podatkov ([zaporedno] 1, 2, 3 ...) podvoji - 1,5 s, 3 s, 6 s, 12 s - najvec
     * [ZAMIK_PONOVITVE_NAJVEC_MS]. Takojsnja ponovitev (0) ostane takojsnja; nova data stevec (in zamik) vrnejo na zacetek.
     */
    fun narascajocZamik(osnova: Long, zaporedno: Int): Long {
        if (osnova <= 0L) return 0L
        val korak = (zaporedno - 1).coerceIn(0, 3)
        return minOf(osnova shl korak, ZAMIK_PONOVITVE_NAJVEC_MS)
    }

    /** Izid [odlocitevVZivo]: obstojeca pot (konec, napaka, rezerva), ponovna povezava (z zamikom) ali tih konec. */
    const val VZIVO_OBSTOJECA_POT = 0
    const val VZIVO_ZNOVA = 1
    const val VZIVO_TIH_KONEC = 2

    /** [vrsta] = VZIVO_*; pri VZIVO_ZNOVA [zamikMs] in zaporedna stevilka ponovitve brez novih podatkov [zaporedno]. */
    data class OdlocitevVZivo(val vrsta: Int, val zamikMs: Long = 0L, val zaporedno: Int = 0)

    /**
     * Naloga 114 (pregled: ponovna povezava se je spremenila v petih nalogah 95-103): vse odlocitve ob koncu toka ali napaki vira
     * kanala v zivo na enem mestu, v istem vrstnem redu kot GlasbaStoritev.ponoviVZivo: [koda]/[http] -> zamik ali obstojeca
     * pot ([zamikPonovitveVZivo]), meja ponovitev ([ponovnaPovezavaVZivo]), ob meji tih konec ali rezerva / naslednji element
     * ([tihKonecPoMeji]). [koda] 0 = konec toka.
     */
    fun odlocitevVZivo(kanal: Boolean, progresivno: Boolean, zacelo: Boolean, koda: Int, http: Int, prejsnjih: Int, predvajanoMs: Long,
                       casiPonovitev: List<Long>, zdaj: Long, imaRezervo: Boolean, imaNaslednjega: Boolean): OdlocitevVZivo {
        val zamik = zamikPonovitveVZivo(koda, http) ?: return OdlocitevVZivo(VZIVO_OBSTOJECA_POT)
        val n = ponovnaPovezavaVZivo(kanal, progresivno, zacelo, prejsnjih, predvajanoMs, casiPonovitev, zdaj)
        if (n > 0) return OdlocitevVZivo(VZIVO_ZNOVA, narascajocZamik(zamik, n), n)
        if (!(kanal && progresivno && zacelo)) return OdlocitevVZivo(VZIVO_OBSTOJECA_POT)
        return if (tihKonecPoMeji(koda != 0, imaRezervo, imaNaslednjega)) OdlocitevVZivo(VZIVO_TIH_KONEC) else OdlocitevVZivo(VZIVO_OBSTOJECA_POT)
    }

    // ------------------------------------------------------------------ pregled 25. 10. 2026 (B1, D7, B-S1, B2)

    /**
     * Pregled B1: placljivo se odloci ENKRAT po odgovorih vseh dodatkov za vsebino ([placljivoPoOdgovoru]). Ce kateri dodatek
     * v roku ni odgovoril, novega zapisa ni (morda bi dal prost tok - film ne sme izginiti za 7 dni); predvajljiv tok pa
     * zapis vedno pobrise.
     */
    fun placljivoPoVsehOdgovorih(prej: Long?, placilnih: Int, predvajljivih: Int, vsiOdgovorili: Boolean, zdaj: Long): Long? =
        if (predvajljivih <= 0 && !vsiOdgovorili) prej else placljivoPoOdgovoru(prej, placilnih, predvajljivih, zdaj)

    /** Pregled D7: najvec bajtov odgovora dodatka (JSON); vecjega zavrnemo, preden ga razclenimo. */
    const val NAJVEC_ODGOVORA_DODATKA = 8 * 1024 * 1024

    /** Pregled D7: napovedana dolzina odgovora ([dolzina] -1 = ni znana) je vecja od meje - odgovora sploh ne beremo. */
    fun odgovorPrevelik(dolzina: Long, meja: Int = NAJVEC_ODGOVORA_DODATKA): Boolean = dolzina > meja

    /**
     * Pregled B-S1: epizoda, ki sledi [sezona]/[epizoda] med [epizode] (sezona, epizoda). Posebne (sezona 0) gredo samo med
     * sabo; po zadnji epizodi sezone pride prva naslednje. null = zadnja epizoda ali je seznam ne pozna.
     */
    fun naslednjaEpizoda(epizode: List<Pair<Int, Int>>, sezona: Int, epizoda: Int): Pair<Int, Int>? =
        epizode.filter { it.second >= 1 && (if (sezona == 0) it.first == 0 else it.first >= 1) }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .firstOrNull { it.first > sezona || (it.first == sezona && it.second > epizoda) }

    private val EPIZODA_V_NASLOVU = Regex(" · S\\d+E\\d+( · .*)?$")

    /** Pregled B-S1: naslov serije brez dela »· S1E2 · ime« (tako ga sestavi seznam epizod); drug naslov ostane. */
    fun naslovBrezEpizode(naslov: String): String = naslov.replace(EPIZODA_V_NASLOVU, "").ifBlank { naslov }

    /** Pregled B-S1: ogled je prakticno koncan (>= 95 % trajanja) - MediaNapredek ga oznaci kot »odgledano«. */
    fun odgledano(polozajMs: Long, trajanjeMs: Long): Boolean = trajanjeMs > 0 && polozajMs >= trajanjeMs * 95 / 100

    /** Pregled B2: enkratni zeton samodejnega preizkusa (PreizkusActivity -> GlasbaActivity) velja tako dolgo. */
    const val PREIZKUS_ZETON_VELJA_MS = 60_000L

    /** Pregled B2: preizkus sprejmemo samo z zetonom, ki ga je ta proces izdal ([izdan]: zeton, cas) in se ni potekel. */
    fun zetonPreizkusaVelja(izdan: Pair<String, Long>?, prejet: String?, zdaj: Long): Boolean =
        izdan != null && !prejet.isNullOrEmpty() && prejet == izdan.first && zdaj - izdan.second in 0 until PREIZKUS_ZETON_VELJA_MS
}
