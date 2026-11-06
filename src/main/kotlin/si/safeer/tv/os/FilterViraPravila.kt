package si.safeer.tv.os

/**
 * Filter vira v mrezi Filmi/Serije (izbira »⚡ Usenet«) - cista logika brez Androida (preizkus tests/FilterViraPravilaTest.kt).
 *
 * Lastnik 5. 11. 2026 19:34 (Abbott Elementary z znacko »★ Full HD« pod filtrom Usenet, predvajanje se je preskocilo):
 * »Prikazemo samo takrat, ko so izpolnjeni VSI pogoji, da si ga uporabnik lahko ogleda.« Do tedaj je mreza uporabila
 * en zapis razpolozljivosti za vse vire (kljuc tip|id, RazpolozljivostPravila.zUsenetom: Usenet je bil le dodaten »je«),
 * zato je pod Usenetom ostal vsak naslov, ki ga je imel kateri koli dodatek (torrent, TorBox). Znacka kakovosti je bila
 * iz tokov vseh virov (KakovostZnane) ali iz metapodatkov kataloga.
 *
 * Odlocitev je ena funkcija [lahkoGleda]: AND vseh pogojev; pogoj, ki je neznan ali ni izpolnjen, naslov skrije.
 * Izid preverbe ima dve vrsti »nicesar« (lastnik 19:44): [Izid.NI] - vir je odgovoril in nima ustreznega toka
 * (negativno v predpomnilnik za ure); [Izid.NE_VEMO] - napaka, rok, ni povezave, meja indeksatorja: naslov se ne
 * pokaze, a se NE zapise kot negativen - ponovna preverba z omejenim stevilom poskusov in narascajocim zamikom.
 *
 * Kodek (lastnik 5. 11. 2026, zakon solidarnosti): pogoj se glasi »kodek zna predvajati TV SAM ALI katerakoli naprava v
 * Safeer Linku, ki tok pretvori in ga poslje TV«. Kaj ima indeksator, je podatek za ure; ali je pomocnik ZDAJ v Linku, pa
 * ne. Zato zapis hrani oboje loceno: [Zapis.izid] za TV samo in [Zapis.pomoc] (izdaja ustreza, ce jo pretvori pomocnik),
 * prisotnost pomocnika pa se uposteva sele ob prikazu ([pokazi]) - ko pomocnik pride ali odide, se mreza osvezi brez nove
 * zahteve indeksatorju in brez zapisa »NI za 6 h«, ki bi naslov skril tudi, ko pomocnik je.
 */
object FilterViraPravila {
    enum class Izid { NA_VOLJO, NI, NE_VEMO }

    /** Pogoji, ki jih je postavil uporabnik; null = pogoja ni. [vir] = kljuc vira filtra (npr. [VIR_USENET]). */
    data class Pogoji(
        val vir: String,
        /** Jezik zvoka (ISO 639-1), ce ga uporabnik zahteva. */
        val jezikZvoka: String? = null,
        /** Podnapisi v jeziku (ISO 639-1), ce jih uporabnik zahteva. */
        val jezikPodnapisov: String? = null,
        /** Najnizja locljivost (npr. 1080), ce jo uporabnik zahteva. */
        val najmanjVisina: Int? = null,
        /** Najvecja velikost izdaje v GB (samodejna izbira Useneta vzame do [UsenetPravila.NAJVEC_GB_SAMODEJNO]). */
        val najvecGb: Double? = null,
    )

    /**
     * Ena izdaja vira, kot jo pove indeksator. [geslo] / [manjka]: indeksator jo je oznacil kot zasciteno z geslom ali
     * z manjkajocimi clanki (true), kot cisto (false); null = indeksator tega ne pove - takrat je pogoj izpolnjen
     * (lastnik: »clanek ni oznacen kot manjkajoc, ce to indeksirnik pove«).
     */
    data class Izdaja(val naslov: String, val gb: Double, val geslo: Boolean? = null, val manjka: Boolean? = null)

    /** Odgovor vira za en naslov: [izdaje] == null pomeni, da vir ni odgovoril (napaka, rok, meja, ni povezave). */
    data class Odgovor(val izdaje: List<Izdaja>?)

    /** Izidi preverb za en naslov: [racun] = ponudnik ali kljuc vira je nastavljen in ga smemo klicati. */
    data class Izidi(val racun: Izid, val odgovor: Odgovor?)

    enum class Pogoj { GESLO, MANJKA, PREDVAJALNIK, JEZIK_ZVOKA, PODNAPISI, LOCLJIVOST, VELIKOST }

    const val VIR_USENET = "usenet"

    /** Kdo izdajo predvaja: TV sam, samo pomocnik v Linku (pretvori v H.264/AAC) ali nihce (slika diska). */
    enum class Pot { TV, POMOCNIK, NE }

    /**
     * Kodek in zvok iz imena izdaje (TokIzbira.opisi, isto pravilo kot izbira toka ob predvajanju): ime brez oznake kodeka
     * je H.264/AAC, ki ga predvaja vsaka naprava. Slike diska (ISO, BDMV) ne predvaja nihce - tudi pomocnik je ne pretvori.
     */
    fun pot(r: Izdaja, z: TokIzbira.Zmoznosti): Pot {
        if (SLIKA_DISKA.containsMatchIn(r.naslov.lowercase())) return Pot.NE
        val o = TokIzbira.opisi(r.naslov)
        val video = !(o.hevc && !z.hevc) && !(o.desetbitni && !z.hevc10) && !(o.av1 && !z.av1)
        val zvok = when (o.zvok) { "truehd" -> z.truehd; "dts" -> z.dts; "eac3" -> z.eac3; "ac3" -> z.ac3; else -> true }
        return if (video && zvok) Pot.TV else Pot.POMOCNIK
    }

    /**
     * Pogoji ene izdaje (vsak posebej, za preizkuse in dnevnik). Kodek in zvok sta prebrana iz imena izdaje (TokIzbira.opisi,
     * isto pravilo kot izbira toka ob predvajanju): ime brez oznake kodeka je H.264/AAC, ki ga predvaja vsaka naprava.
     * Slika diska (ISO, BDMV, celoten Blu-ray) ni datoteka za predvajalnik.
     */
    fun pogojiIzdaje(r: Izdaja, p: Pogoji, z: TokIzbira.Zmoznosti, pomoc: Boolean = false): Map<Pogoj, Izid> {
        val o = TokIzbira.opisi(r.naslov)
        val t = r.naslov.lowercase()
        val m = LinkedHashMap<Pogoj, Izid>()
        m[Pogoj.GESLO] = if (r.geslo == true) Izid.NI else Izid.NA_VOLJO
        m[Pogoj.MANJKA] = if (r.manjka == true) Izid.NI else Izid.NA_VOLJO
        // [pomoc]: stejemo, da je v Linku pomocnik, ki izdajo pretvori (TV kodeka ne zna) - glej [pokazi].
        m[Pogoj.PREDVAJALNIK] = when (pot(r, z)) { Pot.TV -> Izid.NA_VOLJO; Pot.POMOCNIK -> if (pomoc) Izid.NA_VOLJO else Izid.NI; Pot.NE -> Izid.NI }
        p.jezikZvoka?.let { j ->
            val jezik = TokIzbira.jezikZvoka(r.naslov)
            // »vec«, »izvirnik«, »sinhronizacija« ali neoznaceno: jezika ne vemo.
            m[Pogoj.JEZIK_ZVOKA] = when { jezik == j -> Izid.NA_VOLJO; jezik.length == 2 -> Izid.NI; else -> Izid.NE_VEMO }
        }
        p.jezikPodnapisov?.let { j ->
            // Ime izdaje podnapise navede le izjemoma (»SLSUBS«, »Subs.SL«): brez navedbe ne vemo.
            m[Pogoj.PODNAPISI] = if (Regex("(^|[^a-z])(${j}subs?|subs?[ ._-]?$j)([^a-z]|$)").containsMatchIn(t)) Izid.NA_VOLJO else Izid.NE_VEMO
        }
        p.najmanjVisina?.let { v -> m[Pogoj.LOCLJIVOST] = when { o.visina == 0 -> Izid.NE_VEMO; o.visina >= v -> Izid.NA_VOLJO; else -> Izid.NI } }
        p.najvecGb?.let { g -> m[Pogoj.VELIKOST] = when { r.gb <= 0.0 -> Izid.NE_VEMO; r.gb <= g -> Izid.NA_VOLJO; else -> Izid.NI } }
        return m
    }

    /** Izdaje, ki izpolnijo VSE pogoje (vrstni red indeksatorja ostane). */
    fun ustrezne(izdaje: List<Izdaja>, p: Pogoji, z: TokIzbira.Zmoznosti, pomoc: Boolean = false): List<Izdaja> =
        izdaje.filter { r -> pogojiIzdaje(r, p, z, pomoc).values.all { it == Izid.NA_VOLJO } }

    /**
     * Izid preverbe vira za naslov: [Izid.NA_VOLJO] - vsaj ena izdaja izpolni vse pogoje; [Izid.NI] - vir je odgovoril
     * in nima take izdaje (tudi 0 zadetkov); [Izid.NE_VEMO] - vir ni odgovoril ali racun ni potrjen.
     */
    fun izid(izidi: Izidi, p: Pogoji, z: TokIzbira.Zmoznosti, pomoc: Boolean = false): Izid {
        if (izidi.racun != Izid.NA_VOLJO) return Izid.NE_VEMO
        val izdaje = izidi.odgovor?.izdaje ?: return Izid.NE_VEMO
        return if (ustrezne(izdaje, p, z, pomoc).isNotEmpty()) Izid.NA_VOLJO else Izid.NI
    }

    /**
     * Ali naslov sodi v filter: samo, ce je pot do predvajanja s tem virom izpolnjena v celoti (AND vseh pogojev).
     * [pomocnik]: v Linku je ZDAJ naprava, ki sme pomagati in ima Usenet z istim indeksatorjem (UsenetPomocniki).
     */
    fun lahkoGleda(p: Pogoji, izidi: Izidi, z: TokIzbira.Zmoznosti, pomocnik: Boolean = false): Boolean =
        izid(izidi, p, z, pomocnik) == Izid.NA_VOLJO

    /** Izid ene preverbe za zapis: TV sam, ali ustreza s pomocnikom (samo ce TV sam ne), znacka vidne mnozice. */
    data class Preverba(val izid: Izid, val pomoc: Boolean, val oznaka: KakovostPravila.Oznaka?)

    fun preverba(izidi: Izidi, p: Pogoji, z: TokIzbira.Zmoznosti): Preverba {
        val sam = izid(izidi, p, z)
        val izdaje = izidi.odgovor?.izdaje.orEmpty()
        if (sam == Izid.NA_VOLJO) return Preverba(sam, false, oznaka(izdaje, p, z))
        val pomoc = sam == Izid.NI && izid(izidi, p, z, pomoc = true) == Izid.NA_VOLJO
        return Preverba(sam, pomoc, if (pomoc) oznaka(izdaje, p, z, pomoc = true) else null)
    }

    /**
     * Prikaz v mrezi iz zapisa v predpomnilniku: neznan ali zastarel naslov se ne pokaze, dokler ni potrjen. Izdaja, ki jo
     * predvaja samo pomocnik ([Zapis.pomoc]), se pokaze le, dokler je [pomocnik] v Linku - odide, naslov izgine takoj.
     */
    fun pokazi(zapis: Zapis?, zdaj: Long, pomocnik: Boolean = false): Boolean = zapis != null && svez(zapis, zdaj) &&
        (zapis.izid == Izid.NA_VOLJO || (zapis.izid == Izid.NI && zapis.pomoc && pomocnik))

    /**
     * Znacka kartice v filtru vira: najboljsa kakovost ustreznih izdaj tega vira, nikoli iz drugih virov ali kataloga
     * (prej »★ Full HD« iz torrentov pod filtrom Usenet). null = brez znacke.
     */
    fun oznaka(izdaje: List<Izdaja>, p: Pogoji, z: TokIzbira.Zmoznosti, pomoc: Boolean = false): KakovostPravila.Oznaka? =
        KakovostPravila.najboljsa(ustrezne(izdaje, p, z, pomoc).map { it.naslov })

    /**
     * Katero epizodo serije vprasamo: naslednjo za gledanje (Nadaljuj), sicer prvo (S1E1) - kartica v mrezi odpre seznam
     * sezon od zacetka, nov gledalec zacne s prvo. Ena poizvedba na serijo. null = serije ne moremo preveriti.
     */
    fun epizodaZaPreverbo(naslednja: String?, prva: String?): String? = naslednja?.takeIf { it.isNotBlank() } ?: prva?.takeIf { it.isNotBlank() }

    /**
     * Zadnji ogled serije na tej napravi (Nazadnje predvajano, Nadaljuj): epizoda in ali je odgledana (>= 95 %, MediaNapredek).
     * Lastnik 6. 11. 2026 04:19 (Abbott Elementary): filter je vedno preveril S1E1, predvajanje pa epizodo po napredku.
     */
    data class Ogled(val sezona: Int, val epizoda: Int, val odgledano: Boolean)

    /** Epizoda serije za seznam epizod (id dodatka, sezona, epizoda). */
    data class Epizoda(val id: String, val sezona: Int, val epizoda: Int)

    /**
     * Epizoda, ki jo bo uporabnik zares predvajal - po istem pravilu kot »Nadaljuj« (GlasbaActivity.nadaljujEpizodo):
     * neodgledana zadnja epizoda ostane, odgledana -> naslednja (OsPravila.naslednjaEpizoda); brez ogleda ali po zadnji
     * epizodi serije prva (S1E1, kartica odpre seznam sezon od zacetka). null = seznam epizod je prazen.
     */
    fun epizodaZaOgled(epizode: List<Epizoda>, ogled: Ogled?): Epizoda? {
        val prva = epizode.firstOrNull { it.sezona >= 1 } ?: epizode.firstOrNull()
        if (ogled == null) return prva
        val zadnja = epizode.firstOrNull { it.sezona == ogled.sezona && it.epizoda == ogled.epizoda }
        if (!ogled.odgledano) return zadnja ?: prva
        val n = OsPravila.naslednjaEpizoda(epizode.map { it.sezona to it.epizoda }, ogled.sezona, ogled.epizoda) ?: return prva
        return epizode.firstOrNull { it.sezona == n.first && it.epizoda == n.second } ?: prva
    }

    /**
     * Kljuc zapisa filtra za serijo vkljucuje zadnji ogled: ko uporabnik gleda naprej, star izid (za drugo epizodo) ne velja
     * vec in naslov se preveri za epizodo, ki se bo predvajala. Film in serija brez ogleda: kljuc kot doslej.
     */
    fun kljucOgleda(kljuc: String, ogled: Ogled?): String =
        if (ogled == null) kljuc else kljuc + "|S" + ogled.sezona + "E" + ogled.epizoda + (if (ogled.odgledano) "+" else "")

    /**
     * Id iskanja Usenet - ENA funkcija za filter in predvajanje (GlasbaActivity.tokoviVzporedno): prvi id, ki ga indeksator
     * zna iskati (`tt…` ali `tt…:S:E`, UsenetPravila.iscemo). Predvajanje vprasa [idDodatka] in id po IMDb; filter je prej
     * vprasal samo id dodatka (npr. `tmdb:…`) - brez zahteve in »ne vemo«, predvajanje pa je iskalo po IMDb.
     */
    fun idIskanja(idji: List<String?>): String? = idji.firstOrNull { it != null && UsenetPravila.iscemo(it) }

    /** Id iskanja za epizodo: id dodatka, nato `imdb:S:E` (SerijePravila.idEpizodePoImdb) - kot pri predvajanju epizode. */
    fun idIskanjaEpizode(e: Epizoda, imdbSerije: String?): String? =
        idIskanja(listOf(e.id, SerijePravila.idEpizodePoImdb(imdbSerije, e.id, e.sezona, e.epizoda)))

    /**
     * Razlog izida NE_VEMO za dnevnik (tocka d, lastnik 6. 11. 2026): kratek niz brez kljuca in naslova. null = izid ni
     * NE_VEMO zaradi poizvedbe (indeksator je odgovoril). [odgovor] = razlog iz Usenet.iskanje (http_NNN, meja, rok, ...).
     */
    fun razlogNeVemo(racun: Izid, iden: String?, iscemo: Boolean, poslano: Boolean, odgovor: String?): String? = when {
        racun != Izid.NA_VOLJO -> "ni_racuna"
        iden == null -> "ni_iden"
        !iscemo -> "ni_imdb"
        !poslano -> "kvota"
        else -> odgovor
    }

    /**
     * Proracun preverb mreze filtra (lastnik 6. 11. 2026 04:22: »Nalozi vec« s filtrom ni sprozil nobene preverbe). Prej se
     * je proracun obnovil samo, ce je zraslo okno mreze (o.zelja) - nestroga mreza (strogaMreza = false) okna ob »Nalozi
     * vec« ne poveca (o.vec(0)), zato je po prvem krogu ostal 0. Zdaj: vsaka zahteva uporabnika ([zahteva], stevec
     * OdprtKatalog.vec) da nov [PRORACUN_MREZE]; ko ga porabi, enkrat se [VNAPREJ_MREZE] za naslednjo stran v ozadju.
     */
    data class Proracun(val zahteva: Int, val ostalo: Int, val vnaprej: Boolean = false)

    fun proracun(prej: Proracun?, istaMreza: Boolean, zahteva: Int): Proracun = when {
        prej == null || !istaMreza || zahteva != prej.zahteva -> Proracun(zahteva, PRORACUN_MREZE)
        prej.ostalo <= 0 && !prej.vnaprej -> prej.copy(ostalo = VNAPREJ_MREZE, vnaprej = true)
        else -> prej
    }

    // ------------------------------------------------------------------ predpomnilnik (kljuc: vir + racun + naslov)

    private const val MINUTA = 60_000L
    private const val URA = 60 * MINUTA
    /** Rok potrjenega in negativnega izida (lastnik: npr. 6 h). */
    const val NA_VOLJO_VELJA = 6 * URA
    const val NI_VELJA = 6 * URA
    /** Ponovne preverbe po »ne vemo«: najvec [NAJVEC_PONOVITEV], zamik 2, 4, 8 min; nato sele po [NI_VELJA]. */
    const val NAJVEC_PONOVITEV = 3
    const val PRVI_ZAMIK_MS = 2 * MINUTA

    /**
     * Zapis: [izid] NA_VOLJO/NI s casom [cas]; NE_VEMO hrani samo stevilo neuspehov ([poskusov]) in cas zadnjega.
     * [pomoc]: pri NI - ustrezna izdaja obstaja, a jo predvaja samo pomocnik v Linku ([oznaka] je tedaj njena znacka).
     */
    data class Zapis(val izid: Izid, val cas: Long, val oznaka: KakovostPravila.Oznaka? = null, val poskusov: Int = 0,
                     val pomoc: Boolean = false)

    fun kljuc(vir: String, odtisRacuna: String, kljucNaslova: String): String = "$vir|$odtisRacuna|$kljucNaslova"

    /** Ura naprave se lahko premakne nazaj: zapis »iz prihodnosti« ni svez. */
    fun svez(z: Zapis, zdaj: Long): Boolean {
        val s = zdaj - z.cas
        return when (z.izid) { Izid.NA_VOLJO -> s in 0..NA_VOLJO_VELJA; Izid.NI -> s in 0..NI_VELJA; Izid.NE_VEMO -> false }
    }

    /** Nov izid preverbe v zapis. Pravi odgovor (NA_VOLJO, NI) nadomesti vse; »ne vemo« ne povozi svezega odgovora. */
    fun zapisi(prej: Zapis?, izid: Izid, oznaka: KakovostPravila.Oznaka?, zdaj: Long, pomoc: Boolean = false): Zapis = when (izid) {
        Izid.NA_VOLJO -> Zapis(izid, zdaj, oznaka)
        Izid.NI -> Zapis(izid, zdaj, if (pomoc) oznaka else null, 0, pomoc)
        Izid.NE_VEMO -> if (prej != null && prej.izid != Izid.NE_VEMO && svez(prej, zdaj)) prej
            else Zapis(Izid.NE_VEMO, zdaj, null, ((prej?.takeIf { it.izid == Izid.NE_VEMO }?.poskusov) ?: 0) + 1)
    }

    /**
     * Ali naslov zdaj vprasamo (zanka brez meje ni dovoljena): brez zapisa ali po izteku roka da; po »ne vemo« sele po
     * zamiku 2^(n-1) * [PRVI_ZAMIK_MS] in najvec [NAJVEC_PONOVITEV]-krat; potem nic vec, dokler ne mine [NI_VELJA].
     */
    fun vprasati(z: Zapis?, zdaj: Long): Boolean {
        if (z == null) return true
        if (z.izid != Izid.NE_VEMO) return !svez(z, zdaj)
        val s = zdaj - z.cas
        if (s < 0) return false
        if (z.poskusov >= NAJVEC_PONOVITEV) return s > NI_VELJA
        return s >= PRVI_ZAMIK_MS * (1L shl (z.poskusov - 1).coerceIn(0, 10))
    }

    /** Se cakamo na odgovor (napis »Iscem …«): ni svezega odgovora in naslov bomo se vprasali. */
    fun seIscemo(z: Zapis?, zdaj: Long): Boolean = when {
        z == null -> true
        z.izid != Izid.NE_VEMO -> !svez(z, zdaj)
        else -> z.poskusov < NAJVEC_PONOVITEV
    }

    /** »izid;cas;oznaka;poskusov;pomoc« za shrambo (oznaka = KakovostPravila.Oznaka.napis ali prazno; pomoc 1/0). */
    fun vNiz(z: Zapis): String = z.izid.name + ";" + z.cas + ";" + (z.oznaka?.napis ?: "") + ";" + z.poskusov + ";" + (if (z.pomoc) "1" else "0")

    /** Tudi zapis prejsnje razlicice (stiri polja, brez [Zapis.pomoc]). */
    fun izNiza(s: String?): Zapis? {
        val d = s?.split(';') ?: return null
        if (d.size != 4 && d.size != 5) return null
        val pomoc = when (d.getOrNull(4)) { null, "0" -> false; "1" -> true; else -> return null }
        val izid = Izid.values().firstOrNull { it.name == d[0] } ?: return null
        val cas = d[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val poskusov = d[3].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val oznaka = if (d[2].isEmpty()) null else KakovostPravila.Oznaka.izNapisa(d[2]) ?: return null
        return Zapis(izid, cas, oznaka, poskusov, pomoc && izid == Izid.NI)
    }

    // ------------------------------------------------------------------ kvota indeksatorja

    /**
     * Najvec [naMinuto] zahtev v drsecem oknu ene minute (vse mreze skupaj). Ura je podana ([zdaj], ms), zato je
     * omejevalnik preizkusljiv. Meja ali napaka indeksatorja (429, Newznab 500/501) ustavi vse za [premorMs].
     */
    class Omejevalnik(private val naMinuto: Int, private val prviPremorMs: Long = PRVI_PREMOR_MS,
                      private val najvecPremorMs: Long = 15 * MINUTA, private val razmikMs: Long = RAZMIK_MS) {
        private val casi = ArrayDeque<Long>()
        private var premorDo = 0L
        // Lastnik 6. 11. 2026: indeksator ob sunku omeji za nekaj sekund - premor raste (5 s, 10 s, 20 s ...) do 15 min,
        // uspesen odgovor ga ponastavi (prej vedno 15 min brez obvestila: »Nalozi vec« ni naredil nic).
        private var zaporednihMej = 0

        @Synchronized fun smem(zdaj: Long): Boolean {
            if (zdaj < premorDo) return false
            while (casi.isNotEmpty() && zdaj - casi.first() >= MINUTA) casi.removeFirst()
            if (casi.size >= naMinuto) return false
            casi.addLast(zdaj)
            return true
        }

        /** Koliko ms mora zahteva se pocakati, da je od prejsnje minil [razmikMs] (lastnik: razmik manj kot 1 s). */
        @Synchronized fun razmik(zdaj: Long): Long = casi.lastOrNull()?.let { (it + razmikMs - zdaj).coerceAtLeast(0L) } ?: 0L

        @Synchronized fun meja(zdaj: Long) {
            val korak = (prviPremorMs shl zaporednihMej.coerceAtMost(20)).coerceAtMost(najvecPremorMs)
            zaporednihMej++
            premorDo = zdaj + korak
        }
        @Synchronized fun uspeh() { zaporednihMej = 0 }
        @Synchronized fun vPremoru(zdaj: Long): Boolean = zdaj < premorDo
        @Synchronized fun premorSe(zdaj: Long): Long = (premorDo - zdaj).coerceAtLeast(0L)

        /** Ali bi [smem] zdaj dovolil - brez porabe (pregled 6. 11. 2026, U1: kvoto porabi samo poslana zahteva). */
        @Synchronized fun lahko(zdaj: Long): Boolean {
            if (zdaj < premorDo) return false
            while (casi.isNotEmpty() && zdaj - casi.first() >= MINUTA) casi.removeFirst()
            return casi.size < naMinuto
        }

        /**
         * Zahteva, ki gre indeksatorju ne glede na kvoto (zagon naslednje epizode - kot klik uporabnika), se steje: preverbe
         * filtra v isti minuti pocakajo (pregled 6. 11. 2026, U2).
         */
        @Synchronized fun zabelezi(zdaj: Long) {
            while (casi.isNotEmpty() && zdaj - casi.first() >= MINUTA) casi.removeFirst()
            casi.addLast(zdaj)
        }
    }
    }

    /**
     * Pregled 6. 11. 2026 (U1): preverba filtra poslje zahtevo indeksatorju samo z nastavljenim racunom, znanim id in
     * naslovom, ki ga iscemo - prej je kvoto porabila tudi takrat, ko zahteve sploh ni poslala.
     */
    fun poslji(racun: Izid, iden: String?, iscemo: Boolean): Boolean = racun == Izid.NA_VOLJO && iden != null && iscemo

    /**
     * Zahtev na minuto in hkratnih preverb (lastnik 6. 11. 2026 04:26: indeksator dovoli 20 000 na dan, porabimo ~40). Ena
     * preverba na TV traja 0,25-0,3 s (izmerjeno 6. 11. 04:16): 6 hkrati in meja 100 na minuto dovolita takojsen sunek
     * cele mreze in naslednje strani. Ob 429 / Newznab 500/501 ostane premor 15 min ([Omejevalnik.meja]) - drugi uporabniki
     * imajo lahko manjso kvoto.
     */
    // Lastnik 6. 11. 2026 06:29-06:35: indeksator (NZBFinder) ni ponudnik NNTP - najvec 2 zahtevi hkrati, razmik pod 1 s,
    // nizka stopnja (100/min in 6 hkrati je ob 06:14 sprozilo omejitev indeksatorja po 60 zahtevah).
    const val NA_MINUTO = 30
    const val HKRATI = 2
    const val RAZMIK_MS = 500L
    const val PRVI_PREMOR_MS = 5_000L
    /** Novih naslovov na zahtevo uporabnika (odprtje mreze, Nalozi vec): zaslon in naslednja stran. */
    const val PRORACUN_MREZE = 40
    /** Prednalaganje: ko je [PRORACUN_MREZE] porabljen, se toliko naslovov naprej v ozadju (enkrat na zahtevo). */
    const val VNAPREJ_MREZE = 20
    /** Rok enega iskanja - isti kot ob predvajanju. */
    const val ROK_MS = UsenetPravila.ISKANJE_ROK_MS

    /** Napis prazne mreze filtra: se iscemo / indeksator zdaj ne odgovarja / za ta filter ni naslovov. */
    enum class Prazno { ISCEM, NE_ODGOVARJA, NI_NASLOVOV }

    fun prazno(iscemo: Boolean, neOdgovarja: Boolean): Prazno = when {
        iscemo -> Prazno.ISCEM
        neOdgovarja -> Prazno.NE_ODGOVARJA
        else -> Prazno.NI_NASLOVOV
    }

    private val SLIKA_DISKA = Regex("(^|[^a-z0-9])(iso|bdmv|complete[ ._-]?(uhd[ ._-]?)?blu-?ray|bd(25|50|66|100))([^a-z0-9]|$)")
}
