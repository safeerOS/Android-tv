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

    /**
     * Pogoji ene izdaje (vsak posebej, za preizkuse in dnevnik). Kodek in zvok sta prebrana iz imena izdaje (TokIzbira.opisi,
     * isto pravilo kot izbira toka ob predvajanju): ime brez oznake kodeka je H.264/AAC, ki ga predvaja vsaka naprava.
     * Slika diska (ISO, BDMV, celoten Blu-ray) ni datoteka za predvajalnik.
     */
    fun pogojiIzdaje(r: Izdaja, p: Pogoji, z: TokIzbira.Zmoznosti): Map<Pogoj, Izid> {
        val o = TokIzbira.opisi(r.naslov)
        val t = r.naslov.lowercase()
        val m = LinkedHashMap<Pogoj, Izid>()
        m[Pogoj.GESLO] = if (r.geslo == true) Izid.NI else Izid.NA_VOLJO
        m[Pogoj.MANJKA] = if (r.manjka == true) Izid.NI else Izid.NA_VOLJO
        val slika = SLIKA_DISKA.containsMatchIn(t)
        val video = !(o.hevc && !z.hevc) && !(o.desetbitni && !z.hevc10) && !(o.av1 && !z.av1)
        val zvok = when (o.zvok) { "truehd" -> z.truehd; "dts" -> z.dts; "eac3" -> z.eac3; "ac3" -> z.ac3; else -> true }
        m[Pogoj.PREDVAJALNIK] = if (!slika && video && zvok) Izid.NA_VOLJO else Izid.NI
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
    fun ustrezne(izdaje: List<Izdaja>, p: Pogoji, z: TokIzbira.Zmoznosti): List<Izdaja> =
        izdaje.filter { r -> pogojiIzdaje(r, p, z).values.all { it == Izid.NA_VOLJO } }

    /**
     * Izid preverbe vira za naslov: [Izid.NA_VOLJO] - vsaj ena izdaja izpolni vse pogoje; [Izid.NI] - vir je odgovoril
     * in nima take izdaje (tudi 0 zadetkov); [Izid.NE_VEMO] - vir ni odgovoril ali racun ni potrjen.
     */
    fun izid(izidi: Izidi, p: Pogoji, z: TokIzbira.Zmoznosti): Izid {
        if (izidi.racun != Izid.NA_VOLJO) return Izid.NE_VEMO
        val izdaje = izidi.odgovor?.izdaje ?: return Izid.NE_VEMO
        return if (ustrezne(izdaje, p, z).isNotEmpty()) Izid.NA_VOLJO else Izid.NI
    }

    /** Ali naslov sodi v filter: samo, ce je pot do predvajanja s tem virom izpolnjena v celoti (AND vseh pogojev). */
    fun lahkoGleda(p: Pogoji, izidi: Izidi, z: TokIzbira.Zmoznosti): Boolean = izid(izidi, p, z) == Izid.NA_VOLJO

    /** Prikaz v mrezi iz zapisa v predpomnilniku: neznan ali zastarel naslov se ne pokaze, dokler ni potrjen. */
    fun pokazi(zapis: Zapis?, zdaj: Long): Boolean = zapis != null && zapis.izid == Izid.NA_VOLJO && svez(zapis, zdaj)

    /**
     * Znacka kartice v filtru vira: najboljsa kakovost ustreznih izdaj tega vira, nikoli iz drugih virov ali kataloga
     * (prej »★ Full HD« iz torrentov pod filtrom Usenet). null = brez znacke.
     */
    fun oznaka(izdaje: List<Izdaja>, p: Pogoji, z: TokIzbira.Zmoznosti): KakovostPravila.Oznaka? =
        KakovostPravila.najboljsa(ustrezne(izdaje, p, z).map { it.naslov })

    /**
     * Katero epizodo serije vprasamo: naslednjo za gledanje (Nadaljuj), sicer prvo (S1E1) - kartica v mrezi odpre seznam
     * sezon od zacetka, nov gledalec zacne s prvo. Ena poizvedba na serijo. null = serije ne moremo preveriti.
     */
    fun epizodaZaPreverbo(naslednja: String?, prva: String?): String? = naslednja?.takeIf { it.isNotBlank() } ?: prva?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ predpomnilnik (kljuc: vir + racun + naslov)

    private const val MINUTA = 60_000L
    private const val URA = 60 * MINUTA
    /** Rok potrjenega in negativnega izida (lastnik: npr. 6 h). */
    const val NA_VOLJO_VELJA = 6 * URA
    const val NI_VELJA = 6 * URA
    /** Ponovne preverbe po »ne vemo«: najvec [NAJVEC_PONOVITEV], zamik 2, 4, 8 min; nato sele po [NI_VELJA]. */
    const val NAJVEC_PONOVITEV = 3
    const val PRVI_ZAMIK_MS = 2 * MINUTA

    /** Zapis: [izid] NA_VOLJO/NI s casom [cas]; NE_VEMO hrani samo stevilo neuspehov ([poskusov]) in cas zadnjega. */
    data class Zapis(val izid: Izid, val cas: Long, val oznaka: KakovostPravila.Oznaka? = null, val poskusov: Int = 0)

    fun kljuc(vir: String, odtisRacuna: String, kljucNaslova: String): String = "$vir|$odtisRacuna|$kljucNaslova"

    /** Ura naprave se lahko premakne nazaj: zapis »iz prihodnosti« ni svez. */
    fun svez(z: Zapis, zdaj: Long): Boolean {
        val s = zdaj - z.cas
        return when (z.izid) { Izid.NA_VOLJO -> s in 0..NA_VOLJO_VELJA; Izid.NI -> s in 0..NI_VELJA; Izid.NE_VEMO -> false }
    }

    /** Nov izid preverbe v zapis. Pravi odgovor (NA_VOLJO, NI) nadomesti vse; »ne vemo« ne povozi svezega odgovora. */
    fun zapisi(prej: Zapis?, izid: Izid, oznaka: KakovostPravila.Oznaka?, zdaj: Long): Zapis = when (izid) {
        Izid.NA_VOLJO, Izid.NI -> Zapis(izid, zdaj, if (izid == Izid.NA_VOLJO) oznaka else null)
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

    /** »izid;cas;oznaka;poskusov« za shrambo (oznaka = KakovostPravila.Oznaka.napis ali prazno). */
    fun vNiz(z: Zapis): String = z.izid.name + ";" + z.cas + ";" + (z.oznaka?.napis ?: "") + ";" + z.poskusov

    fun izNiza(s: String?): Zapis? {
        val d = s?.split(';') ?: return null
        if (d.size != 4) return null
        val izid = Izid.values().firstOrNull { it.name == d[0] } ?: return null
        val cas = d[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val poskusov = d[3].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val oznaka = if (d[2].isEmpty()) null else KakovostPravila.Oznaka.izNapisa(d[2]) ?: return null
        return Zapis(izid, cas, oznaka, poskusov)
    }

    // ------------------------------------------------------------------ kvota indeksatorja

    /**
     * Najvec [naMinuto] zahtev v drsecem oknu ene minute (vse mreze skupaj). Ura je podana ([zdaj], ms), zato je
     * omejevalnik preizkusljiv. Meja ali napaka indeksatorja (429, Newznab 500/501) ustavi vse za [premorMs].
     */
    class Omejevalnik(private val naMinuto: Int, private val premorMs: Long = 15 * MINUTA) {
        private val casi = ArrayDeque<Long>()
        private var premorDo = 0L

        @Synchronized fun smem(zdaj: Long): Boolean {
            if (zdaj < premorDo) return false
            while (casi.isNotEmpty() && zdaj - casi.first() >= MINUTA) casi.removeFirst()
            if (casi.size >= naMinuto) return false
            casi.addLast(zdaj)
            return true
        }

        @Synchronized fun meja(zdaj: Long) { premorDo = zdaj + premorMs }
        @Synchronized fun vPremoru(zdaj: Long): Boolean = zdaj < premorDo
    }

    /** Zahtev na minuto in hkratnih preverb (NZBFinder: dnevna kvota racuna; vidni plakati najprej). */
    const val NA_MINUTO = 20
    const val HKRATI = 2
    /** Najvec novih naslovov, ki jih ena odprta mreza vprasa, dokler uporabnik ne naredi nicesar (Nalozi vec, drsenje). */
    const val PRORACUN_MREZE = 40
    /** Rok enega iskanja (enak kot ob predvajanju, UsenetPravila.ISKANJE_ROK_MS). */
    const val ROK_MS = 8_000

    /** Napis prazne mreze filtra: se iscemo / indeksator zdaj ne odgovarja / za ta filter ni naslovov. */
    enum class Prazno { ISCEM, NE_ODGOVARJA, NI_NASLOVOV }

    fun prazno(iscemo: Boolean, neOdgovarja: Boolean): Prazno = when {
        iscemo -> Prazno.ISCEM
        neOdgovarja -> Prazno.NE_ODGOVARJA
        else -> Prazno.NI_NASLOVOV
    }

    private val SLIKA_DISKA = Regex("(^|[^a-z0-9])(iso|bdmv|complete[ ._-]?(uhd[ ._-]?)?blu-?ray|bd(25|50|66|100))([^a-z0-9]|$)")
}
