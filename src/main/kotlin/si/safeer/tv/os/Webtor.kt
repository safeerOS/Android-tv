package si.safeer.tv.os

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
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
    /** Diagnostika: `adb shell setprop log.tag.SafeerWebtorMeri DEBUG` - meritev vseh kandidatov (brez predvajanja). */
    private const val MERI = "SafeerWebtorMeri"

    /**
     * Izid sonde: [koda] HTTP (0 = ni odgovora), [prviBajtMs] (null = ga ni bilo), [potekel] rok, [gostitelj] toka,
     * [vrstaVsebine] (Content-Type), [skupaj] velikost datoteke (-1 = neznana), [glava] prvi bajti, [preusmeritev] stevilo
     * preusmeritev; [koncni] = naslov po preusmeritvah (vsebuje zeton ponudnika - nikoli v dnevnik ali na drugo napravo).
     */
    class Sonda(val koda: Int, val prviBajtMs: Long?, val potekel: Boolean, val gostitelj: String,
                val vrstaVsebine: String = "", val skupaj: Long = -1L, val glava: ByteArray = ByteArray(0),
                val preusmeritev: Int = 0, internal val koncni: String = "")

    /** Preklic sonde od zunaj: zapre trenutno povezavo (branja HttpURLConnection prekinitev niti ne ustavi). */
    class Preklic {
        @Volatile var preklican = false
            private set
        @Volatile internal var povezava: HttpURLConnection? = null
        fun preklici() { preklican = true; try { povezava?.disconnect() } catch (_: Throwable) { } }
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
        "koda=${s.koda}, prvi bajt=${s.prviBajtMs ?: "-"} ms, rok=${s.potekel}, gostitelj=${s.gostitelj.ifBlank { "-" }}, " +
            "preusmeritev=${s.preusmeritev}, vrsta=${s.vrstaVsebine.substringBefore(';').ifBlank { "-" }}, " +
            "MB=${if (s.skupaj >= 0) s.skupaj / 1_000_000 else "-"}" + (p?.let { ", posoda=${it.posoda}" +
            (if (it.kodek.isNotEmpty()) ", kodek=${it.kodek}" else "") + ", vsebina=${it.vsebina}" } ?: "")

    // ------------------------------------------------------------------ preverjeni koncni naslovi (pregled U4)

    private class Preverjen(val koncni: String, val cas: Long)
    /** Kljuc: naslov toka v aplikaciji (nadomestek `/resolve/`); vrednost: koncni naslov, ki ga je sonda preverila. */
    private val preverjeni = java.util.concurrent.ConcurrentHashMap<String, Preverjen>()
    /** Koncni naslov Webtorja/TorBoxa velja ure; po pol ure ga za novo odprtje (previjanje po premoru) preverimo znova. */
    private const val VELJA_PREVERJEN_MS = 30 * 60_000L
    /** Rok sonde, ko predvajalnik odpre tok brez sveze preverbe (Nadaljuj, tok z druge naprave). */
    private const val ROK_PREDVAJALNIKA_MS = 20_000L

    /** Sonda je tok [url] preverila (glavni video): predvajalnik dobi njen koncni naslov. */
    fun zapomni(url: String, s: Sonda) {
        if (!s.koncni.startsWith("https://")) return
        if (preverjeni.size >= 20) preverjeni.clear()
        preverjeni[url.trim()] = Preverjen(s.koncni, SystemClock.elapsedRealtime())
    }

    /**
     * Za vir podatkov predvajalnika ([SpletniVir.virPodatkov], nit nalaganja): tok Webtor `/resolve/` -> preverjeni koncni
     * naslov (sonda pred predvajanjem ali nova sonda tu); null = ni tok Webtor. Tok, ki ni preverjen glavni video, se ne
     * odpre (IOException) - predvajalnik ne sledi preusmeritvam, ki jih nismo preverili.
     */
    fun zaPredvajalnik(c: Context, naslov: String): String? {
        val k = naslov.trim()
        // Naslov toka je nadomestek (skrivni dodatek) - pravi pove, ali je to Webtor /resolve/ (nadomestek lahko skrije pot).
        if (!k.startsWith("https://")) return null
        preverjeni[k]?.let { if (SystemClock.elapsedRealtime() - it.cas < VELJA_PREVERJEN_MS) return it.koncni }
        if (!WebtorPravila.jeResolveNaslov(k) && !(SkrivnostiPravila.jeNadomestek(k) && WebtorPravila.jeResolveNaslov(SkrivniDodatki.dejanski(c, k))))
            return null
        val s = sonda(c, k, ROK_PREDVAJALNIKA_MS)
        val p = pregled(s, 0.0)
        Log.i(TAG, "predvajalnik: ${opis(s, p)}")
        if (s.koda !in 200..299 || s.prviBajtMs == null || p.vsebina != WebtorPravila.Vsebina.VIDEO || !s.koncni.startsWith("https://"))
            throw java.io.IOException("Webtor: tok ni preverjen (koda ${s.koda}, ${p.vsebina})")
        zapomni(k, s)
        return s.koncni
    }

    // ------------------------------------------------------------------ meritev (diagnostika na zahtevo razvijalca)

    fun meritev(): Boolean = try { Log.isLoggable(MERI, Log.DEBUG) } catch (_: Throwable) { false }

    /**
     * Meritev za odlocitve po podatkih (rok sonde, izbira datoteke): vsak kandidat ([oznaka] brez naslova, nadomestek
     * [url], pricakovana velikost) v svoji niti z rokom [rokMs]; izpis v dnevnik [MERI]. Ne predvaja in ne uci Scita.
     */
    fun meri(c: Context, kandidati: List<Triple<String, String, Double>>, rokMs: Long = 90_000L) {
        val app = c.applicationContext
        for ((i, k) in kandidati.withIndex()) {
            Thread(Runnable {
                val s = try { sonda(app, k.second, rokMs, uciScit = false) } catch (e: Exception) { Sonda(0, null, false, "") }
                Log.d(MERI, "meritev ${i + 1}/${kandidati.size} ${k.first}: ${opis(s, pregled(s, k.third))}")
            }, "safeer-webtor-meri").apply { isDaemon = true }.start()
        }
    }

    /** Pregled N3: straza prekine zahtevo ob izteku skupnega roka (connect + read sta sicer vsak svoj rok). */
    private val straza = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-webtor-straza").apply { isDaemon = true } }

    /**
     * Prvi bajti toka [url] (Range 0-65535, [WebtorPravila.GLAVA_BAJTOV]) v najvec [rokMs] skupaj. Preusmeritvam sledimo
     * sami (najvec 5), samo po https in ne v domace omrezje. Pregled V1: gostitelja toka Scit dobi med izjeme samo, ce se je
     * zahteva zacela na `https://webtor.io/.../resolve/`, in samo KONCNEGA gostitelja po odgovoru 2xx
     * ([WebtorPravila.naucenGostitelj], [si.safeer.tv.scit.Scit.dovoliGostitelja]). Blokira - z delovne niti; preklic
     * (prekinitev niti) jo konca.
     */
    fun sonda(c: Context, url: String, rokMs: Long = WebtorPravila.PRVI_BAJT_MS, uciScit: Boolean = true, preklic: Preklic? = null): Sonda {
        val zacetek = SystemClock.elapsedRealtime()
        fun ostane() = rokMs - (SystemClock.elapsedRealtime() - zacetek)
        val zacetni = SkrivniDodatki.dejanski(c, url)
        var naslov = zacetni
        var gostitelj = Zasebno.gostitelj(naslov)
        var preusmeritev = 0
        while (true) {
            if (Thread.currentThread().isInterrupted || preklic?.preklican == true) return Sonda(0, null, false, gostitelj)
            val rok = ostane()
            if (rok <= 0) return Sonda(0, null, true, gostitelj)
            if (!naslov.startsWith("https://")) return Sonda(0, null, false, gostitelj)
            // Pregled U4: cilj (tudi po preusmeritvi) ne sme biti v domacem omrezju ali na tej napravi - po razresitvi DNS.
            val naslovi = try { java.net.InetAddress.getAllByName(URL(naslov).host) } catch (_: Exception) { return Sonda(0, null, false, gostitelj) }
            if (naslovi.isEmpty() || naslovi.any { WebtorPravila.zasebenNaslov(it.address) }) {
                Log.w(TAG, "sonda: cilj v zasebnem omrezju zavrnjen ($gostitelj)")
                return Sonda(0, null, false, gostitelj)
            }
            val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return Sonda(0, null, false, gostitelj) }
            preklic?.povezava = p
            if (preklic?.preklican == true) { p.disconnect(); return Sonda(0, null, false, gostitelj) }
            val prekini = try { straza.schedule({ try { p.disconnect() } catch (_: Throwable) { } }, rok, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            try {
                p.instanceFollowRedirects = false
                p.connectTimeout = rok.toInt().coerceAtLeast(1); p.readTimeout = rok.toInt().coerceAtLeast(1)
                p.setRequestProperty("Range", "bytes=0-${WebtorPravila.GLAVA_BAJTOV - 1}")
                p.setRequestProperty("Accept-Encoding", "identity")
                // Isti UA kot predvajalnik ([SpletniVir.agent]) - streznik toka vidi isto napravo.
                SpletniVir.agent(c).takeIf { it.isNotBlank() }?.let { p.setRequestProperty("User-Agent", it) }
                val koda = p.responseCode
                if (koda in 300..399) {
                    val kam = p.getHeaderField("Location") ?: return Sonda(koda, null, false, gostitelj, preusmeritev = preusmeritev)
                    if (++preusmeritev > 5) return Sonda(koda, null, false, gostitelj, preusmeritev = preusmeritev)
                    naslov = URL(URL(naslov), kam).toString()
                    gostitelj = Zasebno.gostitelj(naslov)
                    continue
                }
                if (koda !in 200..299) return Sonda(koda, null, false, gostitelj, preusmeritev = preusmeritev)
                val vrsta = p.contentType.orEmpty()
                val skupaj = WebtorPravila.skupnaVelikost(koda, p.getHeaderField("Content-Range"), p.contentLengthLong)
                // Prvi bajti (do GLAVA_BAJTOV): cas do PRVEGA bajta, nato preostanek glave (posoda, glava AVI, sledi MKV).
                val glava = java.io.ByteArrayOutputStream(WebtorPravila.GLAVA_BAJTOV)
                var ms: Long? = null
                try {
                    p.inputStream.use { vhod ->
                        val buf = ByteArray(WebtorPravila.GLAVA_BAJTOV)
                        while (glava.size() < WebtorPravila.GLAVA_BAJTOV) {
                            val n = vhod.read(buf, 0, WebtorPravila.GLAVA_BAJTOV - glava.size())
                            if (n < 0) break
                            if (n > 0 && ms == null) ms = SystemClock.elapsedRealtime() - zacetek
                            glava.write(buf, 0, n)
                        }
                    }
                } catch (e: java.io.IOException) {
                    // Rok ali prekinitev po prvih bajtih: velja, kar je prislo (posoda je v prvih bajtih).
                    if (glava.size() == 0) throw e
                }
                if (ms != null && uciScit) WebtorPravila.naucenGostitelj(zacetni, naslov, koda)?.let { g ->
                    try { si.safeer.tv.scit.Scit.dovoliGostitelja(c, g) } catch (_: Throwable) { }
                }
                return Sonda(koda, ms, false, gostitelj, vrsta, skupaj, glava.toByteArray(), preusmeritev, naslov)
            } catch (_: java.net.SocketTimeoutException) {
                return Sonda(0, null, true, gostitelj)
            } catch (e: Exception) {
                // Straza je prekinila zahtevo (iztek skupnega roka), krog jo je preklical ali napaka omrezja.
                if (preklic?.preklican == true) return Sonda(0, null, false, gostitelj, preusmeritev = preusmeritev)
                if (ostane() <= 0) return Sonda(0, null, true, gostitelj)
                Log.w(TAG, "sonda: ${e.javaClass.simpleName}")
                return Sonda(0, null, false, gostitelj)
            } finally {
                prekini?.cancel(false)
                p.disconnect()
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
