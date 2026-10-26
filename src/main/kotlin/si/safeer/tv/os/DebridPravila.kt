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

    // Magnet povezave (MagnetActivity). Lastnik 23. 10. 2026: magnet prek TorBoxa (IP uporabnika ni v roju); brez
    // storitve opozorilo, da bo IP viden, PREDEN se naprava poveze z rojem (tudi branje podatkov torrenta je ze roj).
    /** Torrent prenese debrid storitev, naprava dobi samo tok - IP uporabnika ni v roju. */
    const val MAGNET_DEBRID = "debrid"
    /** Neposredno v roj, uporabnik ni potrdil opozorila: najprej opozorilo. */
    const val MAGNET_OPOZORI = "opozori"
    /** Neposredno v roj: uporabnik je opozorilo za ta torrent potrdil. */
    const val MAGNET_P2P = "p2p"

    /**
     * Kako odpreti magnet: [aktivna] = storitev, ki bi predvajala ([Debrid.aktivna]; null = ni kljuca ali je izklopljena),
     * [neposredno] = uporabnik je izrecno izbral »Odpri neposredno (P2P)«, [potrjeno] = opozorilo o vidnem IP je za ta
     * torrent ze potrdil. Neposredne povezave brez potrjenega opozorila ni - tudi ce jo zahteva druga naprava v Linku.
     */
    fun potMagneta(aktivna: String?, neposredno: Boolean, potrjeno: Boolean): String = when {
        aktivna != null && !neposredno -> MAGNET_DEBRID
        potrjeno -> MAGNET_P2P
        else -> MAGNET_OPOZORI
    }

    /** Ime torrenta iz povezave (dn=), brez omrezja in roja; prazno, ce ga povezava nima. */
    fun imeMagneta(magnet: String): String = magnet.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.let { runCatching { java.net.URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }
        ?.trim().orEmpty().take(200)

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
        val red = listOf(TorBoxPravila.NAPAKA_ZAKLENJEN, TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.NAPAKA_MEJA, TorBoxPravila.NAPAKA_PAKET,
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

    /** Skupno pravilo (tests/skupni-primeri, tudi namizje): vse storitve so rekle »ni predpomnjeno«. Android: [cakalnaStoritev]. */
    fun poskusiSCakanjem(samoPredpomnjeno: Boolean, napake: List<String>): Boolean =
        !samoPredpomnjeno && napake.isNotEmpty() && napake.all { it == NAPAKA_NI_PREDPOMNJENO }

    /**
     * Pregled Grok 2 (in neodvisni pregled 2): razlog za uporabnika iz (storitev, napaka) po vrstnem redu poskusov (prednostna
     * prva). Ce je katera storitev rekla »ni predpomnjeno«, je razlog to: ob »Pocakaj na prenos« bo ta storitev res prenesla
     * ([cakalnaStoritev]); zavrnjen kljuc, meja ali rok drugih tega ne preglasi (zavrnjen kljuc okno pove v dodatnem besedilu).
     * Sicer [razlogZaUporabnika].
     */
    fun razlogPoStoritvah(napake: List<Pair<String, String>>): String =
        if (napake.any { it.second == NAPAKA_NI_PREDPOMNJENO }) NAPAKA_NI_PREDPOMNJENO
        else razlogZaUporabnika(napake.map { it.second })

    /**
     * Neodvisni pregled 2: storitev, ki torrent prenese, ko uporabnik caka (2. krog) - prva po vrstnem redu (prednostna prva),
     * ki je rekla »ni predpomnjeno«. Prej je cakala samo prednostna: ce je ta padla na roku ali kljucu, je okno ponudilo
     * »Pocakaj«, ki ni cakal (zanka). null = nihce ne caka (filter »samo takoj« ali nobena storitev ni rekla »ni predpomnjeno«).
     */
    fun cakalnaStoritev(samoPredpomnjeno: Boolean, napake: List<Pair<String, String>>): String? =
        if (samoPredpomnjeno) null else napake.firstOrNull { it.second == NAPAKA_NI_PREDPOMNJENO }?.first

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
        // Varovalo racunov (lastnik 26. 10. 2026): 9 permission denied, 14 account locked, 22 IP not allowed = racun zaklenjen
        // (prej 9 in 14 »paket« = kljuc velja, 22 streznik - zaklenjen racun smo klicali naprej).
        koda in setOf(9, 14, 22) -> TorBoxPravila.NAPAKA_ZAKLENJEN
        koda == 8 -> TorBoxPravila.NAPAKA_KLJUC
        koda in setOf(20, 29) -> TorBoxPravila.NAPAKA_PAKET
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
            // Varovalo racunov: nova lokacija ali naprava (potrditev po e-posti) in prepoved racuna - vsak nov klic bi poslal novo
            // e-posto oziroma ponavljal prijavo (prej »kljuc ne velja«).
            k == "AUTH_BLOCKED" || k == "AUTH_USER_BANNED" -> TorBoxPravila.NAPAKA_ZAKLENJEN
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
        VSI.mapNotNull { p -> vnos[p]?.let { kljucIzVnosa(it, p) }?.let { p to it } }.toMap()

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

    // ------------------------------------------------------------------ neodvisni pregled 5: brisemo samo svoje

    /**
     * Drugi pregled N3: rezerva za locljivost ur streznika (glava `Date` je na sekundo): torrent, dodan najvec toliko pred
     * odgovorom na nase dodajanje, se steje za nasega. Primerjamo samo ure streznika - ura naprave ne odloca.
     */
    const val DODANO_REZERVA_MS = 10_000L

    private val ISO_CAS = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$")

    /**
     * Real-Debrid »added« (torrents/info, ISO 8601, npr. 2026-10-25T01:02:03.000Z ali z zamikom +01:00) v ms; brez oznake
     * pasu = UTC. null = ga ni ali ni berljiv.
     */
    fun rdDodanoMs(added: String?): Long? {
        val m = ISO_CAS.find(added?.trim() ?: return null) ?: return null
        val pas = m.groupValues[2].let { if (it.isEmpty() || it == "Z") "UTC" else "GMT" + it }
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone(pas); isLenient = false }.parse(m.groupValues[1])?.time
        } catch (_: Exception) { null }
    }

    /** AllDebrid »uploadDate« (magnet/status, sekunde Unix) v ms; null = ga ni. */
    fun adDodanoMs(uploadDate: Long?): Long? = uploadDate?.takeIf { it > 0 }?.times(1000)

    /**
     * Ali je torrent dodal ta poskus (smemo ga izbrisati): cas dodajanja po uri streznika ([dodanoMs]: RD »added«, AD
     * »uploadDate«) ni starejsi od glave `Date` odgovora na nase dodajanje ([odgovorDodajanjaMs], ura istega streznika) z
     * rezervo [DODANO_REZERVA_MS]. Torrent, ki ga je minuto prej dodala druga naprava, ni nas. Neznan cas ali brez glave
     * `Date` = ne - torrenta, ki je bil v racunu ze prej, ne izbrisemo.
     */
    fun dodalSmoMi(dodanoMs: Long?, odgovorDodajanjaMs: Long?): Boolean =
        dodanoMs != null && odgovorDodajanjaMs != null && odgovorDodajanjaMs > 0 && dodanoMs >= odgovorDodajanjaMs - DODANO_REZERVA_MS

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

    // ------------------------------------------------------------------ preverba kljuca (lastnik 25. 10. 2026)
    // Na eni napravi je bil v polje TorBox vpisan vzorec »XXXXXXXX-XXXX-…«: Safeer ga je shranil kot »kljuc vpisan ✓«, samodejno
    // usklajevanje pa ga je raznesel po ozjem krogu (tudi na telefon, kjer je bil veljaven). Zdaj: vzorec se zavrne takoj,
    // vsak kljuc preveri storitev (TorBox user/me, Real-Debrid user, AllDebrid user - nic ne doda v racun), zavrnjen se ne
    // shrani, med naprave gre samo preverjen veljaven, prejeti pa se pred uporabo preveri tudi na prejemni napravi.

    /** Izid preverbe kljuca pri storitvi. */
    enum class Preverba { VELJA, NE_VELJA, NI_ODGOVORA, MEJA, OBLIKA, ZAKLENJEN }

    /** Stanje shranjenega kljuca (vezano na kljuc - [odtisStanja]). */
    enum class StanjeKljuca { VELJA, NE_VELJA, NEPREVERJEN, ZAKLENJEN }

    /**
     * Varovalo racunov K1 (lastnik 26. 10. 2026): s kljucem, ki ne velja, ali z zaklenjenim racunom Safeer storitve NE klice
     * (ne ⚡, ne predvajanje, ne ozadje, ne samodejna preverba) - prej je bilo stanje samo za prikaz in vsako odprtje vsebine
     * je klicalo storitev z zavrnjenim kljucem (RD: »bruteforcing will leave you blocked«). Nova preverba samo ob »Preveri«.
     */
    fun smeKlicati(s: StanjeKljuca?): Boolean = s != StanjeKljuca.NE_VELJA && s != StanjeKljuca.ZAKLENJEN

    /**
     * Varovalo racunov (lastnik 26. 10. 2026: »ob ne velja in zaklenjen Safeer storitve ne uporablja vec na NOBENI napravi«):
     * dokoncno stanje racuna gre napravam ozjega kroga (polje `racuni` v secrets.sync - brez kljuca, samo odtis kljuca).
     * Nepreverjen ne gre (prejemniki bi ga sli preverjat - vec klicev z vec naslovov IP).
     */
    fun stanjeVKrog(s: StanjeKljuca?): Boolean = s == StanjeKljuca.VELJA || s == StanjeKljuca.NE_VELJA || s == StanjeKljuca.ZAKLENJEN

    /**
     * Prejeto stanje racuna z druge naprave ([prejetoOb] = cas, ko ga je tam dolocila storitev): uporabi se samo dokoncno
     * stanje, novejse od tukajsnjega ([tukajOb], null = tu se ni stanja) in najvec 10 min iz prihodnosti (napacna ura).
     * Isti kljuc (odtis) preveri klicatelj. Prejeto stanje obdrzi svoj cas - zato se ne vraca (brez ping-ponga).
     */
    fun sprejmiStanje(tukajOb: Long?, prejeto: StanjeKljuca, prejetoOb: Long, zdaj: Long): Boolean =
        stanjeVKrog(prejeto) && prejetoOb > 0 && prejetoOb <= zdaj + 10 * 60_000L && (tukajOb == null || prejetoOb > tukajOb)

    /** Ime stanja v sporocilu (kot skupni primeri): »velja«, »ne_velja«, »zaklenjen«; null = neznano. */
    fun stanjeIzImena(ime: String): StanjeKljuca? = StanjeKljuca.values().firstOrNull { it.name.equals(ime, ignoreCase = true) }

    /** »Preveri« uporabnika najvec enkrat na minuto (vsaka zavrnjena zahteva steje v mejo storitve). */
    const val ROCNA_PREVERBA_MS = 60_000L

    fun rocnaPreverbaDovoljena(zadnjic: Long, zdaj: Long): Boolean = zdaj - zadnjic !in 0 until ROCNA_PREVERBA_MS

    /** Stanje po »Preveri«: brez odgovora ali meja stanja ne spremeni (null - ostane ustavljen, brez samodejnih ponovitev). */
    fun stanjePoRocniPreverbi(p: Preverba): StanjeKljuca? = when (p) {
        Preverba.VELJA -> StanjeKljuca.VELJA
        Preverba.ZAKLENJEN -> StanjeKljuca.ZAKLENJEN
        Preverba.NE_VELJA, Preverba.OBLIKA -> StanjeKljuca.NE_VELJA
        Preverba.NI_ODGOVORA, Preverba.MEJA -> null
    }

    const val TORBOX_UPORABNIK = "https://api.torbox.app/v1/api/user/me"
    const val RD_UPORABNIK = "$RD_OSNOVA/user"
    const val AD_UPORABNIK = "$AD_OSNOVA/user"
    /** Polje »plan« TorBoxa: 0 Free, 1 Essential, 2 Pro, 3 Standard (Usenet samo Pro). */
    val TORBOX_PAKETI = mapOf(0 to "Free", 1 to "Essential", 2 to "Pro", 3 to "Standard")
    /** Nepreverjen kljuc (brez povezave ob vpisu) preverimo znova najvec tako pogosto. */
    const val PREVERI_ZNOVA_MS = 10 * 60_000L

    private val BESEDE_VZORCA = listOf("xxxxxx", "your_", "your-", "yourapi", "yourkey", "apikey", "api_key", "api-key", "kljuc", "example", "placeholder")

    /**
     * Vzorec iz navodil ali prazen nadomestek, ne pravi kljuc: same iste crke (»XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX«),
     * najvec 3 razlicni znaki, en znak v vsaj polovici ali besede vzorca (»your_api_key«). Nakljucni kljuci storitev (UUID,
     * 20-52 znakov) imajo 10+ razlicnih znakov.
     */
    fun jeVzorecKljuca(kljuc: String): Boolean {
        val z = kljuc.filter { it.isLetterOrDigit() }.lowercase()
        if (z.isEmpty()) return true
        val pogostost = z.groupingBy { it }.eachCount()
        return pogostost.size <= 3 || (pogostost.values.maxOrNull() ?: 0) * 2 >= z.length ||
            BESEDE_VZORCA.any { kljuc.lowercase().contains(it) }
    }

    /** Vnos uporabnika ali prejet kljuc: oblika kljuca in ne vzorec; null = to ni kljuc ([Preverba.OBLIKA]). */
    fun kljucIzVnosa(vnos: String, ponudnik: String): String? =
        (if (ponudnik == TORBOX) TorBoxPravila.ocistiKljuc(vnos) else ocistiKljuc(vnos))?.takeUnless { jeVzorecKljuca(it) }

    /** Napaka storitve (TorBoxPravila.NAPAKA_*) -> izid preverbe: zavrnjen kljuc ne velja, meja in paket kljuca ne zavrneta. */
    fun preverbaIzNapake(vrsta: String): Preverba = when (vrsta) {
        TorBoxPravila.NAPAKA_KLJUC -> Preverba.NE_VELJA
        TorBoxPravila.NAPAKA_ZAKLENJEN -> Preverba.ZAKLENJEN
        TorBoxPravila.NAPAKA_MEJA -> Preverba.MEJA
        TorBoxPravila.NAPAKA_PAKET -> Preverba.VELJA
        else -> Preverba.NI_ODGOVORA
    }

    /** TorBox user/me: [http] 0 = brez povezave; [uspeh] = `success: true` z `data`; [napaka] = polje `error`. */
    fun preverbaTorBox(http: Int, uspeh: Boolean, napaka: String?): Preverba = when {
        http in 200..299 && uspeh -> Preverba.VELJA
        http == 0 -> Preverba.NI_ODGOVORA
        else -> preverbaIzNapake(TorBoxPravila.vrstaNapake(http, napaka))
    }

    /**
     * Real-Debrid /user: velja samo s pozitivnim dokazom - 2xx z objektom uporabnika ([dokaz] = `id` ali `username`) ali
     * koda paketa (20, 29); 401 ali koda 8 = ne velja; 9, 14, 22 = racun zaklenjen (varovalo racunov). Pregled Grok 34, E3 (in
     * 33, F7): 403 ali 2xx BREZ kode RD (stran CDN, blokada IP podatkovnega centra ali VPN) o kljucu ne pove nicesar - ni
     * odgovora (prej velja: napacen kljuc bi sel v krog, naprave za istim IP pa bi ga »potrdile«). Kot indeksator (403 brez
     * napake Newznab = ni odgovora).
     */
    fun preverbaRd(http: Int, koda: Int?, dokaz: Boolean): Preverba = when {
        http == 0 -> Preverba.NI_ODGOVORA
        http in 200..299 && koda == null -> if (dokaz) Preverba.VELJA else Preverba.NI_ODGOVORA
        koda == null && http != 401 && http != 429 -> Preverba.NI_ODGOVORA
        else -> preverbaIzNapake(rdVrstaNapake(http, koda))
    }

    /** AllDebrid /user: `status: success` = velja; `AUTH_*` ali 401 = ne velja (AllDebrid napako vrne tudi s HTTP 200). */
    fun preverbaAd(http: Int, status: String?, koda: String?): Preverba = when {
        http in 200..299 && status == "success" -> Preverba.VELJA
        http == 0 -> Preverba.NI_ODGOVORA
        else -> preverbaIzNapake(adVrstaNapake(http, koda))
    }

    // Polja odgovora, kot jih da bralnik JSON (org.json v aplikaciji, bralnik v skupnih primerih - isti primeri kot namizje,
    // tests/skupni-primeri/kljuci-preverba.json). Strogo kot namizje: `success` mora biti true (ne niz »true«), paket in
    // koda Real-Debrid samo celo stevilo, status in koda AllDebrid samo niz; JSON null ni vrednost.

    private fun celo(x: Any?): Int? = when (x) {
        is Int -> x
        is Long -> x.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    /** TorBox user/me: (izid, paket). [imaData] = polje `data` je objekt; paket je `plan` (celo stevilo >= 0) samo pri veljavnem. */
    fun preverbaTorBoxOdgovor(http: Int, success: Any?, error: Any?, imaData: Boolean, plan: Any?): Pair<Preverba, Int?> {
        val izid = preverbaTorBox(http, success == true && imaData, error?.toString()?.takeIf { it.isNotBlank() && it != "null" })
        return izid to (if (izid == Preverba.VELJA) celo(plan)?.takeIf { it >= 0 } else null)
    }

    /** Real-Debrid /user: [errorCode] = polje `error_code`, [id] in [username] = polji uporabnika (pozitiven dokaz, E3). */
    fun preverbaRdOdgovor(http: Int, errorCode: Any?, id: Any?, username: Any?): Preverba =
        preverbaRd(http, celo(errorCode), celo(id) != null || (username as? String)?.isNotBlank() == true)

    /** AllDebrid /user: [status] = polje `status`, [koda] = `error.code`. */
    fun preverbaAdOdgovor(http: Int, status: Any?, koda: Any?): Preverba = preverbaAd(http, status as? String, koda as? String)

    /** Ali kljuc shranimo in s katerim stanjem: zavrnjen ali vzorec ne (null); izpad storitve kljuca ne zavrne (nepreverjen). */
    fun stanjePoPreverbi(p: Preverba): StanjeKljuca? = when (p) {
        Preverba.VELJA -> StanjeKljuca.VELJA
        Preverba.MEJA, Preverba.NI_ODGOVORA -> StanjeKljuca.NEPREVERJEN
        Preverba.NE_VELJA, Preverba.OBLIKA -> null
        // Varovalo racunov: kljuc je pravi, racun pa zaklenjen - shrani se (vrstica pove, kaj narediti), a se ne klice.
        Preverba.ZAKLENJEN -> StanjeKljuca.ZAKLENJEN
    }

    /**
     * Pregled Grok 33, F5: izid zahteve (401 ali povezava) velja za shranjen kljuc samo, ce je to kljuc, s katerim je sla
     * zahteva ([kljucZahteve]), in ce je odgovorila ta storitev ([ponudnikIzida] prazno = [ponudnik]).
     */
    fun izidZaKljuc(kljucZdaj: String?, kljucZahteve: String, ponudnik: String, ponudnikIzida: String): Boolean =
        kljucZdaj != null && kljucZdaj == kljucZahteve && (ponudnikIzida.isEmpty() || ponudnikIzida == ponudnik)

    /** Kam gre vpisan kljuc po preverbi (pregled Grok 33, F3). */
    enum class Vpis { ZAMENJAJ, OB_STRANI, OHRANI, ZAVRNI }

    /**
     * Pregled Grok 33, F3: vpisan kljuc s stanjem [novo] (po preverbi; zavrnjen se sploh ne shrani). Potrjen zamenja
     * shranjenega. Nepreverjen (brez povezave, 429, 5xx) NE prepise veljavnega - caka ob strani, dokler ga storitev ne potrdi
     * (prej je veljaven kljuc ob izpadu izginil; ce se je novi izkazal za napacnega, debrid na tej napravi ni delal); enak
     * veljavnemu ostane, kot je. Brez veljavnega shranjenega nepreverjen zamenja (nic veljavnega se ne izgubi).
     */
    fun obVpisu(novo: StanjeKljuca, shranjenVelja: Boolean, enak: Boolean): Vpis = when {
        novo == StanjeKljuca.VELJA -> Vpis.ZAMENJAJ
        // Varovalo racunov: nov kljuc zaklenjenega racuna ne zamenja veljavnega (enak kljuc dobi stanje »zaklenjen«).
        novo == StanjeKljuca.ZAKLENJEN -> if (shranjenVelja && !enak) Vpis.ZAVRNI else Vpis.ZAMENJAJ
        shranjenVelja && enak -> Vpis.OHRANI
        shranjenVelja -> Vpis.OB_STRANI
        else -> Vpis.ZAMENJAJ
    }

    /** Cakajoci kljuc po ponovni preverbi: potrjen zamenja veljavnega, zavrnjen ali vzorec se zavrze, sicer caka naprej. */
    enum class Cakajoc { ZAMENJAJ, ZAVRZI, CAKA }

    fun poPreverbiCakajocega(p: Preverba): Cakajoc = when (p) {
        Preverba.VELJA -> Cakajoc.ZAMENJAJ
        // Zaklenjen racun: brez samodejnih ponovitev (varovalo racunov) - zavrze se.
        Preverba.NE_VELJA, Preverba.OBLIKA, Preverba.ZAKLENJEN -> Cakajoc.ZAVRZI
        Preverba.NI_ODGOVORA, Preverba.MEJA -> Cakajoc.CAKA
    }

    /** Med naprave v ozjem krogu gre samo kljuc, ki ga je storitev potrdila. */
    fun zaUskladitev(s: StanjeKljuca?): Boolean = s == StanjeKljuca.VELJA

    /**
     * Prejet kljuc (usklajevanje ali »Poslji kljuce«) se tu uporabi samo, ce ga storitev potrdi tudi s te naprave. Zavrnjen
     * nikoli ne prepise veljavnega; brez povezave pocaka (posiljatelj ga poslje znova).
     */
    fun sprejmiPrejetega(p: Preverba): Boolean = p == Preverba.VELJA

    fun imePaketa(paket: Int?): String = paket?.let { TORBOX_PAKETI[it] ?: "plan $it" }.orEmpty()

    /** Stanje velja za natanko ta kljuc (zamenjan kljuc nima stanja prejsnjega). Kljuc ostane skrit (SHA-256, 16 znakov). */
    fun odtisStanja(kljuc: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("safeer-debrid-stanje|$kljuc".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    fun zapisStanja(s: StanjeKljuca, odtis: String, paket: Int?, ob: Long): String = listOf(s.name, odtis, paket?.toString().orEmpty(), ob.toString()).joinToString("|")

    /** (stanje, paket, cas) iz [zapis] za kljuc z [odtis]; null = ni zapisa ali je za drug kljuc. */
    fun stanjeIzZapisa(zapis: String?, odtis: String): Triple<StanjeKljuca, Int?, Long>? {
        val d = zapis?.split('|') ?: return null
        if (d.size < 4 || d[1] != odtis) return null
        val s = StanjeKljuca.values().firstOrNull { it.name == d[0] } ?: return null
        return Triple(s, d[2].toIntOrNull(), d[3].toLongOrNull() ?: 0L)
    }

    /** Ali kljuc (brez stanja ali nepreverjen, zadnja preverba [zadnjic]) preverimo zdaj. */
    fun preveriZdaj(s: StanjeKljuca?, zadnjic: Long, zdaj: Long): Boolean =
        s == null || (s == StanjeKljuca.NEPREVERJEN && zdaj - zadnjic !in 0 until PREVERI_ZNOVA_MS)
}
