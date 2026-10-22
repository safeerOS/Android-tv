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
    fun vVidnem(y: Int, h: Int, zgoraj: Int, spodaj: Int, rezerva: Int): Boolean =
        y + h >= zgoraj - rezerva && y <= spodaj + rezerva

    /**
     * Odzivnost dodatka (naloga 57d): cas zadnjih izpadov (brez odgovora ali 5xx) in do kdaj ga preskakujemo.
     * Lastnik: »Streznike, ki ne odgovarjajo, bi morali zavracati« - a vira ne brisemo sami (odlocitev je uporabnikova).
     */
    data class Odziv(val izpadi: List<Long> = emptyList(), val preskociDo: Long = 0L)

    /** Toliko izpadov v [IZPADI_OKNO_MS] pomeni »ne odgovarja«: dodatek preskocimo za [PRESKOK_MS]. */
    const val IZPADOV_ZA_PRESKOK = 3
    const val IZPADI_OKNO_MS = 10 * 60_000L
    const val PRESKOK_MS = 30 * 60_000L

    /** Nov odziv po poizvedbi: [uspeh] (odgovoril je, tudi »tega nimam«) vse pozabi; izpad se steje. */
    fun poOdzivu(o: Odziv?, uspeh: Boolean, zdaj: Long): Odziv {
        if (uspeh) return Odziv()
        val izpadi = o?.izpadi.orEmpty().filter { zdaj - it in 0 until IZPADI_OKNO_MS } + zdaj
        return if (izpadi.size >= IZPADOV_ZA_PRESKOK) Odziv(emptyList(), zdaj + PRESKOK_MS) else Odziv(izpadi, o?.preskociDo ?: 0L)
    }

    /** Ali dodatek zdaj preskocimo (v ozadju ga ne sprasujemo, oznaka »Ne odgovarja«). */
    fun neOdgovarja(o: Odziv?, zdaj: Long): Boolean = o != null && o.preskociDo > zdaj

    /** Izpad pri preverjanju ob vnosu (0 = brez odgovora ali 5xx): spec streznik (npr. brezplacno gostovanje) se zbuja - poskusimo se enkrat. */
    fun vnosZnova(koda: Int): Boolean = koda == 0 || koda >= 500

    /**
     * Ali ima katalog dodatka za stranjo [velikost] vnosov se naslednjo stran (naloga 57c). Katalog, ki stranjenje oglasi
     * (extra »skip«, standard Stremio), jo ima, dokler stran ni prazna - dodatki imajo razlicno velike strani (do 57 je
     * strani dajala skoraj samo Cinemeta s 50+ vnosi, uporabnikovi dodatki pogosto manj kot [najmanj]). Katalog brez
     * oznake: kot doslej, polna stran pomeni, da je morda se kaj. Stran -1 = dodatek ni odgovoril (izpad): to ni stran.
     */
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
        val v = vnos.trim().lowercase(java.util.Locale.ROOT)
        if (v.isEmpty() || v.contains("{q}") || v.contains(' ')) return null
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
}
