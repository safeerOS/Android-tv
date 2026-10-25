package si.safeer.tv.os

/**
 * Naloga 128: debrid storitve v Medijskem centru - TorBox (119), Real-Debrid in AllDebrid. Storitev torrent prenese
 * namesto naprave (njen IP ni v roju) in ga pretaka s svojega streznika. Ciste odlocitve brez Androida in omrezja
 * (preizkus: tests/DebridPravilaTest.kt); odjemalci so [TorBox], [RealDebrid], [AllDebrid], izbira v [Debrid].
 *
 * Iz dokumentacije (prebrano 24. 10. 2026, ne ugibano):
 * - Real-Debrid: `https://api.real-debrid.com/rest/1.0`, glava `Authorization: Bearer <zeton>`; addMagnet -> selectFiles
 *   -> torrents/info (status »downloaded«, links) -> unrestrict/link (download). Preverjanja predpomnjenja
 *   (instantAvailability) od novembra 2024 ni vec - predpomnjen je torrent, ki je takoj po dodajanju »downloaded«.
 * - AllDebrid: `https://api.alldebrid.com/v4`, glava `Authorization: Bearer <kljuc>` (agent ni vec potreben);
 *   magnet/upload (`ready`) -> magnet/files (drevo n/s/l/e) -> link/unlock (link ali delayed). Posebne tocke za
 *   predpomnjenje ni - predpomnjen je magnet z `ready = true`.
 * - TorBox: checkcached (prava preverba, do 100 naenkrat).
 */
object DebridPravila {
    const val TORBOX = "torbox"
    const val REAL_DEBRID = "realdebrid"
    const val ALLDEBRID = "alldebrid"

    /** Privzeti vrstni red (ce uporabnik ne izbere prednostne storitve). */
    val VSI = listOf(TORBOX, REAL_DEBRID, ALLDEBRID)

    fun ime(ponudnik: String): String = when (ponudnik) {
        TORBOX -> "TorBox"
        REAL_DEBRID -> "Real-Debrid"
        ALLDEBRID -> "AllDebrid"
        else -> ponudnik
    }

    /** Kje uporabnik dobi kljuc (prikaz v oknu za vnos). */
    fun kjeKljuc(ponudnik: String): String = when (ponudnik) {
        TORBOX -> "torbox.app → Settings → API"
        REAL_DEBRID -> "real-debrid.com/apitoken"
        ALLDEBRID -> "alldebrid.com/apikeys"
        else -> ""
    }

    /**
     * Kljuc, kot ga uporabnik prilepi: brez presledkov; 16-128 znakov, samo crke, stevke, '-' in '_' (Real-Debrid zeton je
     * 52 znakov, AllDebrid kljuc okoli 20, TorBox UUID). Prilepljena vrstica z naslovom ali presledki ni kljuc.
     */
    fun ocistiKljuc(vnos: String): String? {
        val k = vnos.trim()
        return k.takeIf { it.length in 16..128 && it.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '_' } }
    }

    fun glavaAvtorizacije(kljuc: String): String = "Bearer $kljuc"

    /**
     * Vrstni red storitev za predvajanje: samo tiste s kljucem; [prednostna] (ce ima kljuc) prva, ostale po [VSI].
     */
    fun vrstniRed(zKljucem: Set<String>, prednostna: String?): List<String> {
        val ima = VSI.filter { it in zKljucem }
        val prva = prednostna?.takeIf { it in ima }
        return if (prva == null) ima else listOf(prva) + ima.filter { it != prva }
    }

    /** Ena napaka vec poleg napak TorBoxa: vsebina pri tej storitvi ni predpomnjena (filter »samo takoj«). */
    const val NAPAKA_NI_PREDPOMNJENO = "ni_predpomnjeno"

    /**
     * Izid preizkusa vseh storitev: prva, ki je dala povezavo, zmaga. Ko nobena ni dala povezave, je razlog za uporabnika
     * najbolj pomenljiva napaka: zavrnjen kljuc > meja > paket > ni datoteke > ni predpomnjeno > streznik.
     */
    fun razlogZaUporabnika(napake: List<String>): String {
        val red = listOf(TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.NAPAKA_MEJA, TorBoxPravila.NAPAKA_PAKET,
            TorBoxPravila.NAPAKA_NI_DATOTEKE, NAPAKA_NI_PREDPOMNJENO, TorBoxPravila.NAPAKA_PRENASA, TorBoxPravila.NAPAKA_STREZNIK)
        return red.firstOrNull { it in napake } ?: TorBoxPravila.NAPAKA_STREZNIK
    }

    /**
     * Ali po neuspehu z vsemi predpomnjenimi poskusimo se s cakanjem (storitev torrent prenese): samo, ce filter
     * »samo takoj predvajljivo« ni vklopljen in je razlog »ni predpomnjeno« (ne kljuc ali meja).
     */
    /**
     * Pregled A4: storitev, ki jo sporocilo napake imenuje - prva (po vrstnem redu poskusov) z napako, ki jo uporabnik
     * vidi ([razlogZaUporabnika]). [napake]: (storitev, napaka). Prazno = ni poskusov.
     */
    fun ponudnikNapake(napake: List<Pair<String, String>>): String {
        if (napake.isEmpty()) return ""
        val razlog = razlogPoStoritvah(napake)
        return (napake.firstOrNull { it.second == razlog } ?: napake.first()).first
    }

    /** Skupno pravilo (tests/skupni-primeri, tudi namizje): vse storitve so rekle »ni predpomnjeno«. Android: [cakajPriPrednostni]. */
    fun poskusiSCakanjem(samoPredpomnjeno: Boolean, napake: List<String>): Boolean =
        !samoPredpomnjeno && napake.isNotEmpty() && napake.all { it == NAPAKA_NI_PREDPOMNJENO }

    /**
     * Pregled Grok 2: razlog za uporabnika iz (storitev, napaka) po vrstnem redu poskusov - prva je prednostna. Ce prednostna
     * vsebine nima pripravljene, je razlog »ni predpomnjeno«: zavrnjen kljuc, meja ali rok vzporednega klica DRUGE storitve
     * ga ne preglasi (sicer okno ni ponudilo »Pocakaj na prenos«, ceprav bi ga prednostna prenesla). Sicer [razlogZaUporabnika].
     */
    fun razlogPoStoritvah(napake: List<Pair<String, String>>): String =
        if (napake.firstOrNull()?.second == NAPAKA_NI_PREDPOMNJENO) NAPAKA_NI_PREDPOMNJENO
        else razlogZaUporabnika(napake.map { it.second })

    /**
     * Pregled Grok 2: cakanje na prenos (2. krog) velja za prednostno storitev - samo, ce filter »samo takoj« ni vklopljen in je
     * prednostna (prva v [napake]) rekla »ni predpomnjeno«; napake drugih storitev tega ne preprecijo.
     */
    fun cakajPriPrednostni(samoPredpomnjeno: Boolean, napake: List<Pair<String, String>>): Boolean =
        !samoPredpomnjeno && napake.firstOrNull()?.second == NAPAKA_NI_PREDPOMNJENO

    // ------------------------------------------------------------------ Real-Debrid

    const val RD_OSNOVA = "https://api.real-debrid.com/rest/1.0"

    /** Torrent je pri Real-Debrid na voljo za predvajanje. */
    fun rdPripravljen(status: String): Boolean = status.lowercase() == "downloaded"

    /** Torrent pri Real-Debrid ne bo nikoli pripravljen (ni smiselno cakati). */
    fun rdKoncnaNapaka(status: String): Boolean = status.lowercase() in setOf("error", "virus", "dead", "magnet_error")

    /**
     * Napaka Real-Debrid (HTTP koda in `error_code` iz tabele v dokumentaciji): 8 bad token -> kljuc; 9 permission denied,
     * 14 account locked, 20 ne za brezplacne, 29 torrent prevelik -> paket; 5 slow down, 21 prevec aktivnih, 23 promet
     * porabljen, 34 too many requests, 36 fair usage -> meja; 24 file unavailable, 28 file not allowed, 30 torrent
     * invalid, 35 infringing file -> ni datoteke.
     */
    fun rdVrstaNapake(http: Int, koda: Int?): String = when {
        // Koda Real-Debrid ima prednost pred HTTP (37 »disabled endpoint« pride tudi s 403, a ni stvar paketa).
        koda == 8 -> TorBoxPravila.NAPAKA_KLJUC
        koda in setOf(9, 14, 20, 29) -> TorBoxPravila.NAPAKA_PAKET
        koda in setOf(5, 21, 23, 34, 36) -> TorBoxPravila.NAPAKA_MEJA
        koda in setOf(24, 28, 30, 35) -> TorBoxPravila.NAPAKA_NI_DATOTEKE
        koda != null -> TorBoxPravila.NAPAKA_STREZNIK
        http == 401 -> TorBoxPravila.NAPAKA_KLJUC
        http == 403 -> TorBoxPravila.NAPAKA_PAKET
        http == 429 -> TorBoxPravila.NAPAKA_MEJA
        else -> TorBoxPravila.NAPAKA_STREZNIK
    }

    // ------------------------------------------------------------------ AllDebrid

    const val AD_OSNOVA = "https://api.alldebrid.com/v4"

    /** Napaka AllDebrid (`error.code`). */
    fun adVrstaNapake(http: Int, koda: String?): String {
        val k = koda.orEmpty()
        return when {
            k.startsWith("AUTH_") || http == 401 -> TorBoxPravila.NAPAKA_KLJUC
            k == "MUST_BE_PREMIUM" || k == "MAGNET_MUST_BE_PREMIUM" || k == "FREE_TRIAL_LIMIT_REACHED" -> TorBoxPravila.NAPAKA_PAKET
            k == "MAGNET_TOO_MANY_ACTIVE" || k == "MAGNET_TOO_MANY" || http == 429 || http == 503 -> TorBoxPravila.NAPAKA_MEJA
            k.startsWith("MAGNET_INVALID") || k == "MAGNET_NO_URI" || k == "LINK_DOWN" -> TorBoxPravila.NAPAKA_NI_DATOTEKE
            else -> TorBoxPravila.NAPAKA_STREZNIK
        }
    }

    /** Kljuc za magnet pri storitvah, ki ga sprejmejo tudi kot hash (AllDebrid): magnet ali 40-znakovni hash. */
    fun magnetAliHash(magnet: String): String = TorBoxPravila.hashIzMagneta(magnet) ?: magnet.trim()

    // ------------------------------------------------------------------ naloga 129: kljuc s telefona na TV (Link)

    /** Dejanje Linka (`control.command`): druga naprava poslje kljuce. Ni v nobenem naboru DostopPravila -> zahteva ozji krog. */
    const val DEJANJE_KLJUCI = "debrid.keys"

    /** Prejeti kljuci cakajo na potrditev na tej napravi toliko casa, potem jih pozabimo. */
    const val PREJETO_VELJA_MS = 10 * 60_000L

    /** Iz prejetega zemljevida (storitev -> kljuc) ostanejo samo znane storitve z veljavnim kljucem. */
    fun prejetiKljuci(vnos: Map<String, String>): Map<String, String> =
        VSI.mapNotNull { p -> vnos[p]?.let { ocistiKljuc(it) }?.let { p to it } }.toMap()

    fun prejetoVelja(prejetoMs: Long, zdajMs: Long): Boolean = zdajMs - prejetoMs in 0 until PREJETO_VELJA_MS

    /**
     * Ali smemo kljuc poslati napravi: samo po sifrirani poti od naprave do naprave ([znaZascito] - E2e v Linku), da ga ne
     * vidi niti naprava sredisca. TLS do sredisca sam ni dovolj za skrivnost.
     */
    fun smePoslatiKljuc(znaZascito: Boolean, povezan: Boolean): Boolean = znaZascito && povezan

    // ------------------------------------------------------------------ naloga 130: izbira ob zvrsteh, oznaka ⚡

    /**
     * Storitev, prek katere gredo torrenti (izbira »Debrid« ob zvrsteh v Video). Privzeto prva s kljucem po [vrstniRed]
     * (uporabnik ni izbral nic); [izklopljeno] = izbral je »Brez debrida«. null = torrenti ne gredo prek debrida.
     */
    fun aktivna(zKljucem: Set<String>, prednostna: String?, izklopljeno: Boolean): String? =
        if (izklopljeno) null else vrstniRed(zKljucem, prednostna).firstOrNull()

    /** Najvec hashev v eni preverbi predpomnjenja TorBox (checkcached). */
    const val NAJVEC_PREVERB = 100

    /** Hashi za preverbo predpomnjenja: samo veljavni (40 heks), mala crka, brez ponovitev, najvec [NAJVEC_PREVERB]. */
    fun hashiZaPreverbo(hashi: List<String?>): List<String> =
        hashi.mapNotNull { h -> h?.trim()?.lowercase()?.takeIf { it.length == 40 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } }
            .distinct().take(NAJVEC_PREVERB)

    /** Predpomnjeni iz odgovora checkcached (kljuci objekta `data`): samo tisti, ki smo jih vprasali. */
    fun predpomnjeni(kljuciOdgovora: Iterable<String>, vprasani: List<String>): Set<String> {
        val v = vprasani.toSet()
        return kljuciOdgovora.map { it.lowercase() }.filterTo(LinkedHashSet()) { it in v }
    }

    private fun jePredpomnjen(h: String?, predpomnjeni: Set<String>) = h != null && h.lowercase() in predpomnjeni

    /**
     * Vrstni red torrentov za seznam virov: predpomnjeni (takoj predvajljivi) najprej, sicer red ostane, kot ga je dala
     * TokIzbira (kakovost). Vrne indekse v [hashi].
     */
    fun predpomnjeniNaprej(hashi: List<String?>, predpomnjeni: Set<String>): List<Int> =
        hashi.indices.filter { jePredpomnjen(hashi[it], predpomnjeni) } + hashi.indices.filter { !jePredpomnjen(hashi[it], predpomnjeni) }

    /**
     * Samodejno predvajanje (OK na kartici): z aktivno storitvijo gre najboljsi pripravljen torrent pred neposredne povezave
     * (zasebno, hitro, obicajno boljsa kakovost). Vrne indeks v [hashi] ali -1 = obstojeca izbira (najprej povezave).
     */
    fun samodejniTorrent(aktivna: String?, hashi: List<String?>, predpomnjeni: Set<String>): Int =
        if (aktivna == null) -1 else hashi.indexOfFirst { jePredpomnjen(it, predpomnjeni) }

    /** Oznaka pred imenom vira, ki ga ima storitev pripravljenega. */
    const val OZNAKA_PREDPOMNJEN = "⚡"

    /**
     * Koliko casa damo dodatkom za tokove po prvem predvajljivem toku. Z aktivno storitvijo pocakamo dlje: dodatki s
     * torrenti (Torrentio) odgovorijo pocasneje od dodatkov s povezavami.
     */
    fun cakajPoPrvemMs(aktivna: Boolean): Long = if (aktivna) 3_000L else 1_500L

    /** Ali steje tok kot »prvi predvajljiv« za [cakajPoPrvemMs]: povezava vedno, torrent samo z aktivno storitvijo. */
    fun stejeKotPrvi(vrsta: String, aktivna: Boolean): Boolean = vrsta == "url" || (aktivna && vrsta == "torrent")

    // ------------------------------------------------------------------ pregled Grok 8: meja odgovora API

    /** Najvec bajtov odgovora API debrid storitve (JSON) - ista meja kot pri dodatkih (OsPravila.NAJVEC_ODGOVORA_DODATKA). */
    const val NAJVEC_ODGOVORA = 8 * 1024 * 1024

    /** Najvec [meja] bajtov iz [vhod]; null, ce jih je vec (beremo najvec meja + 1, da to vemo) - vecjega ne odrezemo, zavrnemo. */
    fun beriOmejeno(vhod: java.io.InputStream, meja: Int = NAJVEC_ODGOVORA): ByteArray? {
        val izhod = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (izhod.size() <= meja) {
            val n = vhod.read(buf, 0, minOf(buf.size, meja + 1 - izhod.size()))
            if (n < 0) break
            izhod.write(buf, 0, n)
        }
        return if (izhod.size() > meja) null else izhod.toByteArray()
    }

    // ------------------------------------------------------------------ naloga 130: najhitrejsa storitev

    /**
     * Meritev povezave storitve do te naprave: [pingMs] = cas do prvega odgovora streznika (povezava + prvi bajt),
     * [bajtov] v [casMs] po prvem odgovoru. Kakovost je pri vseh enaka (isti torrent, ista datoteka) - razlikujeta se
     * odziv in hitrost CDN storitve.
     */
    data class Meritev(val ponudnik: String, val pingMs: Long, val bajtov: Long, val casMs: Long) {
        val bitovNaSekundo: Long get() = if (casMs <= 0 || bajtov <= 0) 0 else bajtov * 8_000 / casMs
    }

    /** Meritev prenese najvec toliko bajtov in traja najvec toliko casa (storitve merimo zaporedno, ne hkrati). */
    const val MERITEV_BAJTOV = 3L * 1024 * 1024
    const val MERITEV_MS = 2_000L

    /** Rok, v katerem morajo vse storitve s kljucem dati povezavo (vzporedno); kar ne pride, ne steje. */
    const val VZPOREDNO_ROK_MS = 15_000L

    /** Hitrosti, ki se razlikujeta za manj kot 15 %, sta »enaki« - takrat odloci manjsi ping. */
    const val PODOBNA_HITROST = 0.85

    /** Storitev z najvisjo hitrostjo prenosa; pri podobni hitrosti tista z manjsim pingom. null = nobena meritev ni uspela. */
    fun najhitrejsa(meritve: List<Meritev>): String? {
        val ok = meritve.filter { it.bitovNaSekundo > 0 }
        val najvec = ok.maxOfOrNull { it.bitovNaSekundo } ?: return null
        return ok.filter { it.bitovNaSekundo >= najvec * PODOBNA_HITROST }.minByOrNull { it.pingMs }?.ponudnik
    }

    /** Opis meritve za uporabnika: »85 Mb/s · 40 ms« (pod 10 Mb/s z eno decimalko). */
    fun opisMeritve(m: Meritev): String {
        val mb = m.bitovNaSekundo / 1_000_000.0
        val hitrost = if (mb < 10) String.format(java.util.Locale.ROOT, "%.1f", mb) else mb.toLong().toString()
        return "$hitrost Mb/s · ${m.pingMs} ms"
    }

    /** Zapis meritve v nastavitvah (»bajtov,casMs,pingMs,kdajMs«) in nazaj; neveljaven zapis = null. */
    fun zapisMeritve(m: Meritev, kdajMs: Long): String = "${m.bajtov},${m.casMs},${m.pingMs},$kdajMs"

    fun meritevIzZapisa(ponudnik: String, zapis: String?): Pair<Meritev, Long>? {
        val d = zapis?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (d.size != 4) return null
        return Meritev(ponudnik, d[2], d[0], d[1]) to d[3]
    }

    /** Zadnja meritev se pokaze v oknu »Debrid storitve«, dokler ni starejsa od tedna. */
    const val MERITEV_VELJA_MS = 7L * 24 * 3_600_000

    fun meritevVelja(kdajMs: Long, zdajMs: Long): Boolean = zdajMs - kdajMs in 0 until MERITEV_VELJA_MS

    // ------------------------------------------------------------------ naloga 136: ⚡ za Real-Debrid in AllDebrid

    /**
     * Real-Debrid in AllDebrid preverbe predpomnjenja nimata (gl. zgoraj). Zato si zapomnimo torrente, ki jih je storitev
     * ob predvajanju ze vrnila kot pripravljene - brez dodatnih zahtev do storitve. Velja [ZAPOMNJEN_VELJA_MS]; ko storitev
     * pri predvajanju rece »ni predpomnjeno«, ga pozabimo. TorBox ima pravo preverbo (checkcached), zato ga ne hranimo.
     */
    data class Zapomnjen(val hash: String, val ponudnik: String, val doMs: Long)

    /** Storitve, za katere si pripravljene torrente zapomnimo. */
    val ZAPOMNI = setOf(REAL_DEBRID, ALLDEBRID)

    /**
     * Storitev pripravljen torrent obicajno hrani dlje (priljubljeni so pripravljeni stalno), a lahko ga tudi odstrani -
     * 3 dni je kompromis med koristjo ⚡ in napacno oznako; napacna se popravi ze ob prvem predvajanju (pozabi).
     */
    const val ZAPOMNJEN_VELJA_MS = 3L * 24 * 3_600_000

    /** Najvec zapomnjenih (vse storitve skupaj): ~70 bajtov na vnos v nastavitvah. */
    const val NAJVEC_ZAPOMNJENIH = 2_000

    private fun hashAliNull(h: String?): String? =
        h?.trim()?.lowercase()?.takeIf { it.length == 40 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    /**
     * Doda [novi] k [seznam]: isti hash pri isti storitvi ostane enkrat z daljsim rokom, poteceni odpadejo, pri vec kot
     * [NAJVEC_ZAPOMNJENIH] odpadejo tisti z najkrajsim rokom.
     */
    private fun dodaj(seznam: List<Zapomnjen>, novi: List<Zapomnjen>, zdajMs: Long): List<Zapomnjen> {
        val po = LinkedHashMap<Pair<String, String>, Zapomnjen>()
        for (z in seznam + novi) {
            if (z.doMs <= zdajMs) continue
            val k = z.hash to z.ponudnik
            val prej = po[k]
            if (prej == null || z.doMs > prej.doMs) { po.remove(k); po[k] = z }
        }
        val vsi = po.values.toList()
        if (vsi.size <= NAJVEC_ZAPOMNJENIH) return vsi
        val odvec = vsi.sortedBy { it.doMs }.take(vsi.size - NAJVEC_ZAPOMNJENIH).toSet()
        return vsi.filter { it !in odvec }
    }

    /** Storitev [ponudnik] je torrent [hash] vrnila kot pripravljen (povezava za predvajanje) ob [zdajMs]. */
    fun zapomniPripravljen(seznam: List<Zapomnjen>, hash: String?, ponudnik: String, zdajMs: Long): List<Zapomnjen> {
        val h = hashAliNull(hash) ?: return seznam
        if (ponudnik !in ZAPOMNI) return seznam
        return dodaj(seznam, listOf(Zapomnjen(h, ponudnik, zdajMs + ZAPOMNJEN_VELJA_MS)), zdajMs)
    }

    /** Storitev [ponudnik] je rekla, da [hash] ni pripravljen - oznaka ⚡ zanjo ni vec resnicna. */
    fun pozabiPripravljen(seznam: List<Zapomnjen>, hash: String?, ponudnik: String): List<Zapomnjen> {
        val h = hashAliNull(hash) ?: return seznam
        return seznam.filter { !(it.hash == h && it.ponudnik == ponudnik) }
    }

    /** Kateri od [hashi] (mala crka) so zapomnjeni kot pripravljeni pri kateri od [ponudniki] (storitve s kljucem). */
    fun zapomnjeniZa(seznam: List<Zapomnjen>, hashi: List<String?>, ponudniki: Set<String>, zdajMs: Long): Set<String> {
        val vprasani = hashi.mapNotNullTo(HashSet()) { hashAliNull(it) }
        return seznam.filterTo(ArrayList()) { it.ponudnik in ponudniki && it.doMs > zdajMs && it.hash in vprasani }
            .mapTo(LinkedHashSet()) { it.hash }
    }

    /** Zapis v nastavitvah: vrstica »hash,storitev,doMs«. */
    fun zapisZapomnjenih(seznam: List<Zapomnjen>): String = seznam.joinToString("") { "${it.hash},${it.ponudnik},${it.doMs}\n" }

    /** Iz zapisa; pokvarjena vrstica ali neznana storitev se preskoci. */
    fun zapomnjeniIzZapisa(zapis: String?): List<Zapomnjen> = zapis.orEmpty().split('\n').mapNotNull { v ->
        val d = v.split(',')
        if (d.size != 3) return@mapNotNull null
        val h = hashAliNull(d[0]) ?: return@mapNotNull null
        val doMs = d[2].toLongOrNull() ?: return@mapNotNull null
        if (d[1] !in ZAPOMNI) null else Zapomnjen(h, d[1], doMs)
    }

    /**
     * Dejanje Linka (`control.command`): druga naprava v ozjem krogu poslje pripravljene torrente. Ni v nobenem naboru
     * DostopPravila -> zahteva ozji krog; posiljatelj ga poslje samo po sifrirani poti od naprave do naprave
     * ([smePoslatiKljuc]) - seznam pove, kaj uporabnik gleda.
     */
    const val DEJANJE_PREDPOMNJENI = "debrid.cached"

    /** Najvec vnosov, ki jih sprejmemo v enem sporocilu. */
    const val NAJVEC_PREJETIH = 200

    /** Za posiljanje: preostali cas namesto roka - ure naprav se lahko razlikujejo. null = ze potekel. */
    fun zaPoslati(z: Zapomnjen, zdajMs: Long): Triple<String, String, Long>? =
        (z.doMs - zdajMs).takeIf { it > 0 }?.let { Triple(z.hash, z.ponudnik, it) }

    /**
     * Prejeto z druge naprave (hash, storitev, preostali ms): samo veljavni hashi in [ZAPOMNI], rok najvec nas
     * [ZAPOMNJEN_VELJA_MS], najvec [NAJVEC_PREJETIH]; obstojeci rok se ne skrajsa.
     */
    fun prejetiZapomnjeni(seznam: List<Zapomnjen>, prejeti: List<Triple<String, String, Long>>, zdajMs: Long): List<Zapomnjen> {
        val novi = prejeti.take(NAJVEC_PREJETIH).mapNotNull { (hash, ponudnik, ostane) ->
            val h = hashAliNull(hash)
            if (h == null || ponudnik !in ZAPOMNI || ostane <= 0) null
            else Zapomnjen(h, ponudnik, zdajMs + minOf(ostane, ZAPOMNJEN_VELJA_MS))
        }
        return dodaj(seznam, novi, zdajMs)
    }
}
