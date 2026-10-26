package si.safeer.tv.os

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Webtor rezerva (pot A, 25. 10. 2026; pravila: [WebtorPravila]): sonda toka pred predvajanjem in torrent v TorBox v
 * ozadju. Naslov toka v aplikaciji je nadomestek brez zetona; pravi naslov ([SkrivniDodatki.dejanski]) se uporabi samo za
 * zahtevo in se nikoli ne zapise. Koncni naslov po preusmeritvi (vezan na IP te naprave) je samo v pomnilniku te naprave
 * ([zaPredvajalnik]) - v zgodovino, Link in dnevnik gre vedno `/resolve/` (nadomestek).
 *
 * Izmerjeno 25. 10. 2026 (OnePlus 9, 0 od 3 predvaja): sonda je preverila samo kodo 206 - /resolve je vrnil naslovnico
 * (image/jpeg) in AVI s kodekom, ki ga Media3 izpusti. Zdaj sonda prebere prvih [WebtorPravila.GLAVA_BAJTOV] bajtov in
 * [WebtorPravila.pregled] pove, ali je to glavni video, ki ga naprava predvaja; predvajalnik dobi PREVERJENI koncni naslov
 * (pregled U4: brez nove verige preusmeritev med sondo in predvajanjem).
 */
object Webtor {
    private const val TAG = "SafeerWebtor"
    /** Oznaka dnevnika diagnostike [meri] (vklop: [meritev]). */
    private const val MERI = "SafeerWebtorMeri"

    /**
     * Izid sonde: [koda] HTTP (0 = ni odgovora), [prviBajtMs] (null = ga ni bilo), [potekel] rok, [gostitelj] toka,
     * [vrstaVsebine] (Content-Type), [skupaj] velikost datoteke (-1 = neznana), [glava] prvi bajti, [preusmeritev] stevilo
     * preusmeritev, [preusmeritevMs] cas do prvega odgovora 3xx (null = ga ni bilo) - loci pocasen /resolve od pocasnega
     * roja, [razlog] zavrnitve brez naslova (ROK, PREKLIC, NI_HTTPS, ZASEBNO, DNS, BREZ_LOCATION, PREVEC_PREUSMERITEV, KODA,
     * NAPAKA). [koncni] = naslov po preusmeritvah (vsebuje zeton ponudnika - nikoli v dnevnik ali na drugo napravo) in
     * [naslovi] njegovega gostitelja, ki jih je sonda preverila (predvajalnik se poveze samo nanje, pregled 26 T1); [naucen] =
     * gostitelj za Scit ([WebtorPravila.naucenGostitelj]) - uci ga klicatelj sele po preverjeni vsebini ([uci], pregled 26 T8).
     */
    class Sonda(val koda: Int, val prviBajtMs: Long?, val potekel: Boolean, val gostitelj: String,
                val vrstaVsebine: String = "", val skupaj: Long = -1L, val glava: ByteArray = ByteArray(0),
                val preusmeritev: Int = 0, internal val koncni: String = "", val preusmeritevMs: Long? = null,
                val razlog: String = "", internal val naslovi: List<InetAddress> = emptyList(), val naucen: String? = null)

    /** Preklic sonde od zunaj: prekine tekoci klic OkHttp (DNS, povezava, branje). */
    class Preklic {
        @Volatile var preklican = false
            private set
        @Volatile internal var klic: okhttp3.Call? = null
        fun preklici() { preklican = true; try { klic?.cancel() } catch (_: Throwable) { } }
    }

    /** Izid kroga sond: [izbran] kandidat (-1 = noben), po kandidatih sonda, pregled vsebine in izid (null = brez odgovora). */
    class Krog(val izbran: Int, val sonde: List<Sonda?>, val pregledi: List<WebtorPravila.Pregled?>, val izidi: List<WebtorPravila.Izid?>)

    /**
     * Krog sond (izmerjeno 25. 10. 2026: 4 od 16 kandidatov napacna datoteka, prenos do 14 s): [kandidati] (naslov toka,
     * pricakovana velikost v GB) hkrati, vsak z rokom [rokMs]; izbira [WebtorPravila.izberiSondo] - najboljsi pripravljen
     * glavni video. Ostale sonde se zaprejo. Klic iz delovne niti; prekinitev niti konca krog brez izbire.
     */
    fun krog(c: Context, kandidati: List<Pair<String, Double>>, rokMs: Long): Krog {
        val n = kandidati.size
        val sonde = arrayOfNulls<Sonda>(n)
        val pregledi = arrayOfNulls<WebtorPravila.Pregled>(n)
        val izidi = arrayOfNulls<WebtorPravila.Izid>(n)
        val preklici = List(n) { Preklic() }
        // Vsaka sonda po koncu sporoci svoj indeks; vrsta da tudi vidnost zapisov v tabelah (happens-before).
        val koncane = java.util.concurrent.LinkedBlockingQueue<Int>()
        val zacetek = SystemClock.elapsedRealtime()
        kandidati.forEachIndexed { i, (url, gb) ->
            Thread(Runnable {
                try {
                    val s = try { sonda(c, url, rokMs, preklic = preklici[i]) } catch (_: Exception) { Sonda(0, null, false, "") }
                    val p = if (s.koda in 200..299 && s.prviBajtMs != null) pregled(s, gb) else null
                    sonde[i] = s; pregledi[i] = p
                    izidi[i] = WebtorPravila.odlocitev(s.koda, s.prviBajtMs, s.potekel, p?.vsebina)
                    // Pregled 26, T8: Scit se gostitelja nauci samo pri preverjenem glavnem videu.
                    if (izidi[i] == WebtorPravila.Izid.PREDVAJAJ) uci(c, s)
                } finally { koncane.add(i) }
            }, "safeer-webtor-sonda").apply { isDaemon = true }.start()
        }
        val pripravljen = arrayOfNulls<Boolean>(n)
        var prviPripravljen: Long? = null
        var izbran = -1
        try {
            while (true) {
                val zdaj = SystemClock.elapsedRealtime()
                val r = WebtorPravila.izberiSondo(pripravljen.toList(), prviPripravljen?.let { zdaj - it }, zdaj - zacetek >= rokMs)
                if (r >= 0) { izbran = r; break }
                if (r == WebtorPravila.NOBENA) break
                val doRoka = rokMs - (zdaj - zacetek)
                val doBoljsega = prviPripravljen?.let { WebtorPravila.POCAKAJ_BOLJSEGA_MS - (zdaj - it) } ?: Long.MAX_VALUE
                val i = koncane.poll(minOf(doRoka, doBoljsega).coerceIn(1L, 1_000L), TimeUnit.MILLISECONDS) ?: continue
                pripravljen[i] = izidi[i] == WebtorPravila.Izid.PREDVAJAJ
                if (pripravljen[i] == true && prviPripravljen == null) prviPripravljen = SystemClock.elapsedRealtime()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // Preostale sonde zapremo - sicer bi do roka drzale sejo pri Webtorju (omejitev socasnih zahtev na sejo).
            for ((i, p) in preklici.withIndex()) if (i != izbran) p.preklici()
        }
        // Izid samo za sonde, ki so ze sporocile konec (ostale so se v teku ali preklicane).
        val znani = HashSet<Int>()
        while (true) { znani += koncane.poll() ?: break }
        for (i in 0 until n) if (pripravljen[i] != null) znani += i
        return Krog(izbran, (0 until n).map { if (it in znani) sonde[it] else null },
            (0 until n).map { if (it in znani) pregledi[it] else null }, (0 until n).map { if (it in znani) izidi[it] else null })
    }

    /** Ima naprava dekoder za [mime] (MediaCodecList, enkrat na MIME). */
    private val dekoderji = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun kodekPodprt(mime: String): Boolean = dekoderji.getOrPut(mime) {
        try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        } catch (_: Throwable) { true }
    }

    /** Pregled vsebine sonde ([WebtorPravila.pregled]); [pricakovanoGb] = velikost iz opisa toka (0 = ni znana). */
    fun pregled(s: Sonda, pricakovanoGb: Double): WebtorPravila.Pregled =
        WebtorPravila.pregled(s.vrstaVsebine, s.glava, s.skupaj, pricakovanoGb, ::kodekPodprt)

    /** Opis sonde za dnevnik - brez naslova (samo gostitelj). */
    fun opis(s: Sonda, p: WebtorPravila.Pregled?): String =
        "koda=${s.koda}, 302=${s.preusmeritevMs ?: "-"} ms, prvi bajt=${s.prviBajtMs ?: "-"} ms, rok=${s.potekel}, " +
            "gostitelj=${s.gostitelj.ifBlank { "-" }}, preusmeritev=${s.preusmeritev}" +
            (if (s.razlog.isNotEmpty()) ", razlog=${s.razlog}" else "") + ", vrsta=${s.vrstaVsebine.substringBefore(';').ifBlank { "-" }}, " +
            "MB=${if (s.skupaj >= 0) s.skupaj / 1_000_000 else "-"}" + (p?.let { ", posoda=${it.posoda}" +
            (if (it.kodek.isNotEmpty()) ", kodek=${it.kodek}" else "") + ", vsebina=${it.vsebina}" } ?: "")

    // ------------------------------------------------------------------ preverjeni koncni naslovi (pregled U4)

    /** Koncni naslov, ki ga je sonda preverila, in naslovi IP njegovega gostitelja (predvajalnik se poveze samo nanje). */
    class Preverjen internal constructor(internal val koncni: String, internal val naslovi: List<InetAddress>, val cas: Long)
    /** Kljuc: naslov toka v aplikaciji (nadomestek `/resolve/`); vrednost: koncni naslov, ki ga je sonda preverila. */
    private val preverjeni = ConcurrentHashMap<String, Preverjen>()
    /** Koncni naslov Webtorja/TorBoxa velja ure; po pol ure ga za novo odprtje (previjanje po premoru) preverimo znova. */
    private const val VELJA_PREVERJEN_MS = 30 * 60_000L
    /** Rok sonde, ko predvajalnik odpre tok brez sveze preverbe (Nadaljuj, tok z druge naprave). */
    private const val ROK_PREDVAJALNIKA_MS = 20_000L
    /** Pregled 26, T4: neuspela preverba velja toliko - ponovitve nalaganja Media3 ne sprozijo vsakic nove sonde do 20 s. */
    private const val NEUSPEH_VELJA_MS = 60_000L
    private val neuspehi = ConcurrentHashMap<String, Long>()

    /** Pregled 26, T8: Scit se gostitelja toka nauci sele, ko je vsebina preverjena (glavni video). */
    fun uci(c: Context, s: Sonda) {
        s.naucen?.let { g -> try { si.safeer.tv.scit.Scit.dovoliGostitelja(c, g) } catch (_: Throwable) { } }
    }

    /** Sonda je tok [url] preverila (glavni video): predvajalnik dobi njen koncni naslov in preverjene naslove IP. */
    fun zapomni(url: String, s: Sonda) {
        if (!s.koncni.startsWith("https://") || s.naslovi.isEmpty()) return
        // Pregled 26, T8: ob polnem seznamu gre najstarejsi vnos, ne vsi (tok, ki se pravkar odpira, ostane).
        if (preverjeni.size >= 20) preverjeni.entries.minByOrNull { it.value.cas }?.key?.let { preverjeni.remove(it) }
        preverjeni[url.trim()] = Preverjen(s.koncni, s.naslovi, SystemClock.elapsedRealtime())
        neuspehi.remove(url.trim())
    }

    /**
     * Za vir podatkov predvajalnika ([IzbirniVir], nit nalaganja): tok Webtor `/resolve/` -> preverjeni koncni naslov in
     * njegovi naslovi IP (sonda pred predvajanjem ali nova sonda tu); null = ni tok Webtor (navadna pot). Pregled 26, T1:
     * predvajalnik ga odpre z [WebtorVir] - brez preusmeritev, samo na preverjene naslove. Tok, ki ni preverjen glavni video,
     * in tok na webtor.io, ki ni preverljiv ([WebtorPravila.potPredvajalnika]), se ne odpreta (IOException, fail-closed).
     */
    fun zaPredvajalnik(c: Context, naslov: String, preklic: Preklic? = null): Preverjen? {
        val k = naslov.trim()
        val nadomestek = SkrivnostiPravila.jeNadomestek(k)
        val pravi = SkrivniDodatki.dejanski(c, k)
        when (WebtorPravila.potPredvajalnika(k, pravi, nadomestek && !SkrivniDodatki.pozna(c, k))) {
            WebtorPravila.Pot.NAVADNA -> return null
            WebtorPravila.Pot.ZAVRNI -> throw java.io.IOException("Webtor: tok ni preverljiv")
            WebtorPravila.Pot.WEBTOR -> { }
        }
        val zdaj = SystemClock.elapsedRealtime()
        preverjeni[k]?.let { if (zdaj - it.cas < VELJA_PREVERJEN_MS) return it }
        neuspehi[k]?.let { if (zdaj - it < NEUSPEH_VELJA_MS) throw java.io.IOException("Webtor: tok ni preverjen (nedavna sonda)") }
        val s = sonda(c, k, ROK_PREDVAJALNIKA_MS, preklic)
        val p = pregled(s, 0.0)
        Log.i(TAG, "predvajalnik: ${opis(s, p)}")
        if (s.koda !in 200..299 || s.prviBajtMs == null || p.vsebina != WebtorPravila.Vsebina.VIDEO || !s.koncni.startsWith("https://") ||
            s.naslovi.isEmpty()) {
            if (preklic?.preklican != true) {
                if (neuspehi.size >= 50) neuspehi.clear()
                neuspehi[k] = SystemClock.elapsedRealtime()
            }
            throw java.io.IOException("Webtor: tok ni preverjen (koda ${s.koda}, ${p.vsebina})")
        }
        uci(c, s)
        zapomni(k, s)
        return preverjeni[k] ?: Preverjen(s.koncni, s.naslovi, SystemClock.elapsedRealtime())
    }

    // ------------------------------------------------------------------ meritev (diagnostika na zahtevo razvijalca)

    /**
     * Pregled 26, T3: diagnostika samo prek lastnosti `debug.safeer.webtor_meri` in `debug.safeer.webtor_samo` z vrednostjo
     * = cas izteka v sekundah od 1970 (najvec [WebtorPravila.DIAGNOSTIKA_NAJVEC_S] naprej). `debug.*` nastavi samo lupina
     * adb, ne ostane po ponovnem zagonu (ni persist) in je ne vklopi globalna raven dnevnika (prej log.tag.* - tudi
     * persist in globalno). Primer: `adb shell setprop debug.safeer.webtor_samo $(( $(date +%s) + 3600 ))`.
     */
    internal fun diagnostika(c: Context, lastnost: String): Boolean = try {
        // Pregled Grok 32, G4/G6 (isto pravilo kot DiagnostikaPravila na veji z3-sejanje - ob zdruzitvi eno): getprop samo
        // pri vklopljenem razvijalskem adb, z rokom in destroy(); prej nov proces ob vsaki izbiri toka pri vseh uporabnikih.
        WebtorPravila.diagnostikaVklopljena(lastnost, System.currentTimeMillis() / 1000, { adbVklopljen(c) }, ::getprop)
    } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false } catch (_: Throwable) { false }

    /**
     * Razvijalski adb: razhroscevanje USB (Settings.Global.ADB_ENABLED == 1) ali brezzicno razhroscevanje (adb_wifi_enabled,
     * Android 11+; ce ga sistem ne da prebrati, ne velja). Brez tega lastnosti `debug.*` ne more nastaviti nihce razen sistema.
     */
    private fun adbVklopljen(c: Context): Boolean {
        val cr = c.applicationContext.contentResolver
        if (android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0) == 1) return true
        return try { android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1 } catch (_: Exception) { false }
    }

    /** getprop brez lupine (tabela argumentov), vhod zaprt; po koncu ali roku se proces konca (destroy). null = ni odgovora. */
    private fun getprop(lastnost: String): String? {
        val p = ProcessBuilder("getprop", lastnost).redirectErrorStream(true).start()
        return try {
            p.outputStream.close()
            if (!p.waitFor(WebtorPravila.DIAGNOSTIKA_ROK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) null
            else p.inputStream.bufferedReader().use { it.readLine() }
        } finally { p.destroy() }
    }

    fun meritev(c: Context): Boolean = diagnostika(c, "debug.safeer.webtor_meri")

    /** Izbira toka gre po poti rezerve Webtor (meritev, glej [diagnostika]). */
    fun samoWebtor(c: Context): Boolean = diagnostika(c, "debug.safeer.webtor_samo")

    /**
     * Meritev za odlocitve po podatkih (rok sonde, izbira datoteke): vsak kandidat ([oznaka] brez naslova, nadomestek
     * [url], pricakovana velikost) v svoji niti z rokom [rokMs]; izpis v dnevnik [MERI]. Ne predvaja in ne uci Scita.
     */
    fun meri(c: Context, kandidati: List<Triple<String, String, Double>>, rokMs: Long = 90_000L) {
        val app = c.applicationContext
        // Pregled 26, T3: najvec 2 sondi hkrati (Webtor omejuje socasne zahteve na sejo - prave sonde ne smejo dobiti 429).
        for ((i, k) in kandidati.withIndex()) {
            try {
                merilnik.execute {
                    val s = try { sonda(app, k.second, rokMs) } catch (e: Exception) { Sonda(0, null, false, "") }
                    Log.d(MERI, "meritev ${i + 1}/${kandidati.size} ${k.first}: ${opis(s, pregled(s, k.third))}")
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { }
        }
    }

    private val merilnik = Executors.newFixedThreadPool(2) { r -> Thread(r, "safeer-webtor-meri").apply { isDaemon = true } }

    /**
     * Prvi bajti toka [url] (Range 0-65535, [WebtorPravila.GLAVA_BAJTOV]) v najvec [rokMs] skupaj (vsi skoki, tudi DNS).
     * Preusmeritvam sledimo sami (najvec 5), samo po https. Pregled 26, T1: vsako ime se razresi enkrat in povezava gre samo
     * na preverjene javne naslove ([WebtorHttp.Preverjeni] - zasebni ali lokalni naslov = ZASEBNO, brez povezave), zato DNS
     * rebinding med preverbo in povezavo ni mogoc; koncni naslov in njegovi naslovi gredo v [Sonda] za predvajalnik.
     * Gostitelja za Scit ([Sonda.naucen]) uci klicatelj po preverjeni vsebini ([uci]). Blokira - z delovne niti; [preklic] ali
     * prekinitev niti jo konca.
     */
    fun sonda(c: Context, url: String, rokMs: Long = WebtorPravila.PRVI_BAJT_MS, preklic: Preklic? = null): Sonda {
        val zacetek = SystemClock.elapsedRealtime()
        fun ostane() = rokMs - (SystemClock.elapsedRealtime() - zacetek)
        val zacetni = SkrivniDodatki.dejanski(c, url)
        var naslov = zacetni
        var gostitelj = Zasebno.gostitelj(naslov)
        var preusmeritev = 0
        var preusmeritevMs: Long? = null
        val dns = WebtorHttp.Preverjeni()
        val klient = WebtorHttp.klient(dns, rokMs)
        // Pregled 26, T9: vsaka zavrnitev nosi stevilo in cas preusmeritev ter razlog (brez naslova).
        fun konec(koda: Int, potekel: Boolean, razlog: String) =
            Sonda(koda, null, potekel, gostitelj, preusmeritev = preusmeritev, preusmeritevMs = preusmeritevMs, razlog = razlog)
        // Isti UA kot predvajalnik ([SpletniVir.agent]) - streznik toka vidi isto napravo.
        val ua = try { SpletniVir.agent(c) } catch (_: Throwable) { "" }
        while (true) {
            if (Thread.currentThread().isInterrupted || preklic?.preklican == true) return konec(0, false, "PREKLIC")
            val rok = ostane()
            if (rok <= 0) return konec(0, true, "ROK")
            if (!naslov.startsWith("https://")) return konec(0, false, "NI_HTTPS")
            val zahteva = try {
                val g = okhttp3.Request.Builder().url(naslov)
                    .header("Range", "bytes=0-${WebtorPravila.GLAVA_BAJTOV - 1}")
                    .header("Accept-Encoding", "identity")
                if (ua.isNotBlank()) g.header("User-Agent", ua)
                g.build()
            } catch (_: IllegalArgumentException) { return konec(0, false, "NAPAKA") }
            // Gostitelj kot naslov IP gre mimo DNS: preveri ga enako (zaseben = brez povezave).
            try { dns.preveriIp(zahteva.url.host) } catch (_: WebtorHttp.ZasebenNaslov) {
                Log.w(TAG, "sonda: cilj v zasebnem omrezju zavrnjen ($gostitelj)")
                return konec(0, false, "ZASEBNO")
            }
            val klic = klient.newCall(zahteva)
            // Skupni rok vseh skokov: klic dobi preostanek (DNS, povezava, glave in prvi bajti).
            klic.timeout().timeout(rok, TimeUnit.MILLISECONDS)
            preklic?.klic = klic
            if (preklic?.preklican == true) { klic.cancel(); return konec(0, false, "PREKLIC") }
            var odgovor: okhttp3.Response? = null
            try {
                val r = klic.execute()
                odgovor = r
                val koda = r.code
                if (koda in 300..399) {
                    if (preusmeritevMs == null) preusmeritevMs = SystemClock.elapsedRealtime() - zacetek
                    val kam = r.header("Location") ?: return konec(koda, false, "BREZ_LOCATION")
                    if (++preusmeritev > 5) return konec(koda, false, "PREVEC_PREUSMERITEV")
                    naslov = r.request.url.resolve(kam)?.toString() ?: return konec(koda, false, "BREZ_LOCATION")
                    gostitelj = Zasebno.gostitelj(naslov)
                    continue
                }
                if (koda !in 200..299) return konec(koda, false, "KODA")
                val telo = r.body ?: return konec(koda, false, "NAPAKA")
                val vrsta = r.header("Content-Type").orEmpty()
                val skupaj = WebtorPravila.skupnaVelikost(koda, r.header("Content-Range"), telo.contentLength())
                // Prvi bajti (do GLAVA_BAJTOV): cas do PRVEGA bajta, nato preostanek glave (posoda, glava AVI, sledi MKV).
                val glava = java.io.ByteArrayOutputStream(WebtorPravila.GLAVA_BAJTOV)
                var ms: Long? = null
                try {
                    val vhod = telo.byteStream()
                    val buf = ByteArray(WebtorPravila.GLAVA_BAJTOV)
                    while (glava.size() < WebtorPravila.GLAVA_BAJTOV) {
                        val n = vhod.read(buf, 0, WebtorPravila.GLAVA_BAJTOV - glava.size())
                        if (n < 0) break
                        if (n > 0 && ms == null) ms = SystemClock.elapsedRealtime() - zacetek
                        glava.write(buf, 0, n)
                    }
                } catch (e: java.io.IOException) {
                    // Rok ali prekinitev po prvih bajtih: velja, kar je prislo (posoda je v prvih bajtih).
                    if (glava.size() == 0) throw e
                }
                val naucen = if (ms != null) WebtorPravila.naucenGostitelj(zacetni, naslov, koda) else null
                return Sonda(koda, ms, false, gostitelj, vrsta, skupaj, glava.toByteArray(), preusmeritev, naslov, preusmeritevMs,
                    naslovi = dns.za(r.request.url.host), naucen = naucen)
            } catch (_: WebtorHttp.ZasebenNaslov) {
                Log.w(TAG, "sonda: cilj v zasebnem omrezju zavrnjen ($gostitelj)")
                return konec(0, false, "ZASEBNO")
            } catch (_: java.net.UnknownHostException) {
                return konec(0, false, "DNS")
            } catch (e: java.io.IOException) {
                // Preklic kroga, iztek roka klica (InterruptedIOException) ali napaka omrezja.
                if (preklic?.preklican == true) return konec(0, false, "PREKLIC")
                if (e is java.io.InterruptedIOException || ostane() <= 0) return konec(0, true, "ROK")
                Log.w(TAG, "sonda: ${e.javaClass.simpleName}")
                return konec(0, false, "NAPAKA")
            } finally {
                try { odgovor?.close() } catch (_: Throwable) { }
            }
        }
    }

    /** Nit za TorBox v ozadju: ne prekine je nova izbira toka (namerno predpomnjenje za naslednjic). */
    private val ozadje = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-webtor-torbox").apply { isDaemon = true } }

    /** Torrent [magnet] v TorBox (createtorrent, brez cakanja) - po [WebtorPravila.dodajVTorBox]. */
    fun vTorBoxVOzadju(c: Context, magnet: String) {
        val app = c.applicationContext
        try { ozadje.execute { try { TorBox.dodajZaPozneje(app, magnet) } catch (e: Exception) { Log.w(TAG, "TorBox v ozadju: ${e.javaClass.simpleName}") } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
