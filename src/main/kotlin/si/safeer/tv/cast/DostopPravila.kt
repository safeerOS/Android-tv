package si.safeer.tv.cast

/**
 * Dostop drugih naprav v Safeer Linku do vsebin TE naprave. Cista pravila brez Androida (tests/DostopPravilaTest.kt).
 *
 * 6. 10. 2026: seznanitev v Safeer Link ni vec dovoljenje za vsebine. Safeer Link je sirsi krog (»EU«): vsaka
 * naprava v njem pomaga pri povezavi (sredisce, posredovanje, pot prek mobilnega omrezja) in sprejme, kar ji kdo
 * izrecno poslje. Dostop do vsebin je ozji krog (»Schengen«): na VSAKI napravi posebej in za VSAKO drugo napravo
 * posebej uporabnik odloci, kaj ji odpre - datoteke, programe, predvajalnik, zaslon in upravljanje. Brez tega
 * naprava zahtevo zavrne sama (ne le gumb na zaslonu druge naprave).
 *
 * Prej je bila vsaka seznanjena naprava enakovredna: telefon druge osebe, dodan v Link, je takoj dobil sezname in
 * vire Medijskega centra, seznam programov, datoteke in »Nadaljuj z druge naprave« (izmerjeno 6. 10. 2026).
 */
object DostopPravila {

    /**
     * 6. 10. 2026 00:00:00 UTC. Naprave, ki so bile s to napravo v krogu ze pred tem dnem, ob uvedbi dovoljenj obdrzijo
     * dostop (uporabnikove dosedanje naprave delajo naprej brez enega dotika); vsaka pozneje dodana zacne brez dostopa.
     */
    const val MEJA_PODEDOVANJA = 1_791_244_800.0

    /** Kaj uporabnik drugi napravi na tej napravi odpre. [crka] je zapis v shrambi. */
    enum class Zmoznost(val crka: Char) { DATOTEKE('d'), PROGRAMI('p'), PREDVAJALNIK('v'), ZASLON('z') }

    /** Kaj zahteva dejanje: PROSTO sme vsaka naprava v Linku; VSE samo naprava, ki ji je odprto vse (ozji krog). */
    enum class Zahteva { PROSTO, DATOTEKE, PROGRAMI, PREDVAJALNIK, ZASLON, VSE }

    val VSE_ZMOZNOSTI: Set<Zmoznost> = Zmoznost.values().toSet()

    fun izNiza(s: String?): Set<Zmoznost> = Zmoznost.values().filter { s != null && s.indexOf(it.crka) >= 0 }.toSet()

    fun vNiz(z: Set<Zmoznost>): String = Zmoznost.values().filter { it in z }.joinToString("") { it.crka.toString() }

    private val DATOTEKE = setOf("files.list", "files.search", "files.open", "storage.put", "storage.status")
    private val PROGRAMI = setOf("apps.list", "apps.launch", "apps.close", "apps.running", "apps", "launch_app", "open_in_app")
    private val PREDVAJALNIK = setOf(
        // Kaj naprava predvaja, nadaljevanje in ustavitev; seznami in viri Medijskega centra.
        "play.state", "play.stop", "lists.get",
        // Odpiranje vsebine na tej napravi brez vprasanja in zvok druge naprave na njenih zvocnikih.
        "open_url", "magnet.open", "audio.play", "audio.stop",
        // Pomoc pri predvajanju vidi, kaj druga naprava gleda, in trosi to napravo (prenos, pretvorba, dodatki).
        "magnet.stream", "magnet.list", "magnet.remove", "magnet.keep",
        "video.transcode", "video.status", "video.stream", "video.stream_stop", "video.stream_status",
        "avail.get", "host.info",
    )
    private val ZASLON = setOf("screen.start", "screen.stop", "screen.status", "key", "scroll", "screenshot",
        "volume", "restart", "clear_cache", "status")

    /**
     * Kaj zahteva ukaz `control.command`. Ponudba »Poslji na napravo« (`play.offer`) je prosta: nic se ne zacne,
     * dokler uporabnik na tej napravi ne sprejme. Neznano dejanje zahteva vse - novo dejanje ni odprto po pomoti.
     */
    fun zahtevaDejanja(dejanje: String): Zahteva {
        val d = dejanje.trim().lowercase()
        return when {
            d == "play.offer" -> Zahteva.PROSTO
            d in DATOTEKE -> Zahteva.DATOTEKE
            d in PROGRAMI -> Zahteva.PROGRAMI
            d in PREDVAJALNIK -> Zahteva.PREDVAJALNIK
            d in ZASLON || d.startsWith("input.") || d.startsWith("gamepad.") -> Zahteva.ZASLON
            else -> Zahteva.VSE
        }
    }

    /**
     * Kaj zahteva sporocilo, ki ni ukaz. Besedilo, datoteka in klepet so izrecno poslani tej napravi (prosto; pristanejo
     * v Sporocilih oziroma Prejetem). Stran ali predvajanje, ki se odpre samo, zahteva predvajalnik; usklajevanje stanja
     * (odprte strani, iskanja, mesta v filmih, delovni prostor) samo ozji krog - naprava, ki ji je odprto vse; zaslon
     * druge naprave, ki se tu odpre sam, zahteva zaslon. Konec deljenja je vedno dovoljen.
     */
    fun zahtevaSporocila(tip: String, dejanje: String = ""): Zahteva = when (tip) {
        "cast.url", "cast.control", "handoff.request" -> Zahteva.PREDVAJALNIK
        "sync.data" -> Zahteva.VSE
        "share.screen" -> if (dejanje == "stop") Zahteva.PROSTO else Zahteva.ZASLON
        else -> Zahteva.PROSTO
    }

    fun sme(dano: Set<Zmoznost>, zahteva: Zahteva): Boolean = when (zahteva) {
        Zahteva.PROSTO -> true
        Zahteva.DATOTEKE -> Zmoznost.DATOTEKE in dano
        Zahteva.PROGRAMI -> Zmoznost.PROGRAMI in dano
        Zahteva.PREDVAJALNIK -> Zmoznost.PREDVAJALNIK in dano
        Zahteva.ZASLON -> Zmoznost.ZASLON in dano
        Zahteva.VSE -> dano.containsAll(VSE_ZMOZNOSTI)
    }

    /** Clan kroga, kolikor ga pravila potrebujejo. */
    class Clan(val id: String, val kljuc: String, val dodano: Double)

    /**
     * Stalna oznaka naprave ne glede na to, pod katerim id-jem se javi. Odloca KLJUC, pod katerim je naprava v krogu:
     * - id iz kljuca (`n-<16 hex>`, po zelji s pripono sorodnika `-os`, `-control` ...) da svoje jedro - RAZEN ce je pod
     *   tem id-jem v krogu vpisan drug kljuc. Tak vnos je videti kot druga naprava, a to ni (izmerjeno 7. 10. 2026 na
     *   racunalniku: krog ga je sprejel in preverba dostopa mu je dala dostop posnemane naprave); dobi prazno jedro,
     *   torej nic;
     * - star id (npr. po modelu naprave) se prevede prek kljuca v krogu; naprava, ki je krog ne pozna, ostane pri
     *   svojem id-ju (zanjo ni zapisa, torej nima dostopa). Pokvarjen kljuc ne odpre nicesar (prazno jedro).
     */
    fun jedro(id: String, kljucClana: (String) -> String?, idIzKljuca: (String) -> String): String {
        val jedroKljuca = try {
            kljucClana(id)?.takeIf { it.isNotBlank() }?.let { idIzKljuca(it) } ?: ""
        } catch (_: Throwable) { return "" }
        if (jeIdIzKljuca(id)) {
            if (jedroKljuca.isNotEmpty() && jedroKljuca != id.take(DOLZINA_JEDRA)) return ""
            return id.take(DOLZINA_JEDRA)
        }
        return jedroKljuca.ifEmpty { id }
    }

    /** Natanko jedro iz kljuca (`n-<16 hex>`, brez pripone) - oblika zapisa naprav, ki so kljuc dokazale. */
    fun jeJedro(s: String): Boolean = s.length == DOLZINA_JEDRA && jeIdIzKljuca(s)

    /** Predpona kljuca shrambe za oznako brez jedra (glej [kljucShrambe]). */
    const val BREZ_JEDRA = "brez-jedra:"

    /**
     * Kljuc, pod katerim shrambe (izrecno poslane datoteke, seznami prejemnikov oddaj) vodijo napravo: njeno [jedro].
     * Oznaka, pod katero je v krogu DRUG kljuc, jedra nima. Dobi kljuc, ki ne more biti enak jedru ali oznaki nobene
     * druge naprave - tudi kadar je taka oznaka kar golo jedro prave naprave (`n-<16 hex>`): kar je shranjeno za pravo
     * napravo, zanjo ne velja.
     */
    fun kljucShrambe(id: String, jedro: String): String = jedro.ifEmpty { BREZ_JEDRA + id }

    /**
     * Javni kljuc, s katerim preverimo podpis naprave v dogovoru zascite (cast/E2e). Oznaka iz kljuca je vezana na kljuc:
     * velja samo kljuc, ki da njeno jedro - vzamemo ga, kjerkoli v krogu je ([vsiKljuci]: vnos naprave same, sorodnika
     * ali stare oznake). Vnos s to oznako in DRUGIM kljucem ne steje: tujega kljuca tako ne more podtakniti niti
     * sredisce, od katerega dobivamo krog, in s takim vnosom pravi napravi zascite ne more onemogociti. Stara oznaka
     * (ni iz kljuca) ima kljuc svojega vnosa ([kljucVnosa]); njeno jedro je potem jedro TEGA kljuca.
     */
    fun kljucZaZascito(
        id: String, kljucVnosa: (String) -> String?, vsiKljuci: () -> List<String>, idIzKljuca: (String) -> String
    ): String? {
        if (jeIdIzKljuca(id)) {
            val jedro = id.take(DOLZINA_JEDRA)
            return vsiKljuci().firstOrNull { k -> k.isNotBlank() && try { idIzKljuca(k) == jedro } catch (_: Throwable) { false } }
        }
        return kljucVnosa(id)?.takeIf { it.isNotBlank() }
    }

    /**
     * Ali sme posiljatelj to, kar [zahteva] zahteva. [zascita] je jedro iz PREVERJENEGA kljuca, kadar je sporocilo prislo
     * zasciteno (cast/E2e): takrat odloca kljuc ([zmoznostiJedra]), ne oznaka. Brez zascite velja oznaka, ki jo je
     * vpisalo sredisce ([zmoznostiOznake]) - a samo za napravo, ki zascite (se) ne zna: od naprave, ki jo zna
     * ([zahtevaZascito]), nezascitenega ne sprejmemo, kadar gre za sporocilo, ki ga naprave z zascito posiljajo
     * zasciteno ([zascitljivo]).
     */
    fun smePosiljatelj(
        zahteva: Zahteva, zascita: String, zascitljivo: Boolean, zahtevaZascito: () -> Boolean,
        zmoznostiJedra: (String) -> Set<Zmoznost>, zmoznostiOznake: () -> Set<Zmoznost>,
    ): Boolean {
        if (zahteva == Zahteva.PROSTO) return true
        if (zascita.isNotEmpty()) return sme(zmoznostiJedra(zascita), zahteva)
        if (zascitljivo && zahtevaZascito()) return false
        return sme(zmoznostiOznake(), zahteva)
    }

    const val DOLZINA_JEDRA = 18

    /** Isto pravilo kot KrogZaupanja.jeIdIzKljuca (tu brez odvisnosti, da pravila tecejo sama). */
    fun jeIdIzKljuca(id: String): Boolean {
        if (id.length < DOLZINA_JEDRA || !id.startsWith("n-")) return false
        if (!id.substring(2, DOLZINA_JEDRA).all { it in '0'..'9' || it in 'a'..'f' }) return false
        return id.length == DOLZINA_JEDRA || id[DOLZINA_JEDRA] == '-'
    }

    /**
     * Naprave, ki ob uvedbi dovoljenj obdrzijo dostop na tej napravi (jedra): tiste, ki so v krogu ze od prej kot
     * [meja] - a le, ce je bila v krogu ze prej tudi TA naprava. Naprava, dodana po meji (telefon druge osebe, nova
     * namestitev), ne podeduje nikogar: tudi ona svojih vsebin ne odpre napravam, ki jih je spoznala sele zdaj.
     * Steje najstarejsi veljavni vnos istega kljuca (naprava ima lahko vec id-jev).
     */
    fun podedovani(clani: List<Clan>, lastniKljuc: String?, idIzKljuca: (String) -> String, meja: Double = MEJA_PODEDOVANJA): Set<String> {
        if (lastniKljuc.isNullOrBlank()) return emptySet()
        val najstarejsi = HashMap<String, Double>()
        for (c in clani) {
            if (c.kljuc.isBlank()) continue
            val prej = najstarejsi[c.kljuc]
            if (prej == null || c.dodano < prej) najstarejsi[c.kljuc] = c.dodano
        }
        val nas = najstarejsi[lastniKljuc] ?: return emptySet()
        if (nas >= meja) return emptySet()
        val izid = HashSet<String>()
        for ((kljuc, dodano) in najstarejsi) {
            if (kljuc == lastniKljuc || dodano >= meja) continue
            try { izid.add(idIzKljuca(kljuc)) } catch (_: Throwable) { }
        }
        return izid
    }

    /** Posiljatelj, pod katerim sredisce posreduje dotik gledalca deljenega zaslona (HubKrmilnik.naVnosGledalca). */
    const val GLEDALEC = "gledalec"

    /** Parameter ukaza gledalca, v katerem sredisce poslje znak deljenja ([znakGledalca]). */
    const val PARAM_ZNAK_GLEDALCA = "_znak"

    /**
     * Znak deljenja zaslona: zgoscena vrednost id-ja in kljuca deljenja. Poznajo ju naprava, ki zaslon deli, sredisce,
     * ki deljenje gosti, in gledalec, ki mu je bil zaslon poslan. Sredisce z znakom oznaci dotik gledalca; naprava, ki
     * bi se v Link le prijavila pod imenom »gledalec«, ga ne pozna. Kljuca deljenja se iz znaka ne da dobiti.
     */
    fun znakGledalca(idDeljenja: String, kljucDeljenja: String): String {
        if (idDeljenja.isBlank() || kljucDeljenja.isBlank()) return ""
        val zgostitev = java.security.MessageDigest.getInstance("SHA-256")
            .digest("safeer-gledalec-v1\n$idDeljenja\n$kljucDeljenja".toByteArray(Charsets.UTF_8))
        return zgostitev.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }

    /**
     * Dotik gledalca deljenega zaslona. Gledalec ni naprava v Linku (zaslon gleda s kljucem deljenja), zato zanj ni
     * zapisa. Upravljati sme (input.*) samo, dokler TA naprava sama deli zaslon ([deliZaslon]), in samo, ce je napravi,
     * s katero ga deli, tu odprt zaslon in upravljanje ([zmoznostiCilja]). Ogled je uporabnik izrecno poslal;
     * upravljanje naprave je dostop - gostu, ki mu zaslon samo pokazes, s tem ne das naprave v roke.
     * [znakDeljenja] je znak deljenja, ki tece na tej napravi, [znakUkaza] tisti, ki ga je prinesel ukaz: biti morata
     * enaka (prazen znak ne velja nikoli - dokler sredisce deljenja ne odpre, dotika ni).
     */
    fun smeGledalec(
        dejanje: String, deliZaslon: Boolean, zmoznostiCilja: Set<Zmoznost>, znakDeljenja: String, znakUkaza: String
    ): Boolean =
        deliZaslon && dejanje.trim().lowercase() in VNOS_GLEDALCA && Zmoznost.ZASLON in zmoznostiCilja &&
            znakDeljenja.isNotBlank() &&
            java.security.MessageDigest.isEqual(znakDeljenja.toByteArray(Charsets.UTF_8), znakUkaza.toByteArray(Charsets.UTF_8))

    /** Kar stran gledalca poslje (HubTokovi.vnosVZaslon): dotik, poteg, tipka. Nic drugega. */
    private val VNOS_GLEDALCA = setOf("input.tap", "input.swipe", "input.key")

    /** Kratek opis za vrstico naprave: katere zmoznosti so odprte, v vrstnem redu stikal. */
    fun povzetek(dano: Set<Zmoznost>): List<Zmoznost> = Zmoznost.values().filter { it in dano }
}
