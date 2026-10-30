package si.safeer.tv.os

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lokalni streznik HTTP Range za lasten pogon Usenet, korak 2 - enako kot core/usenet_streznik.py v Safeer-linux.
 *
 * Na Androidu ExoPlayer bere neposredno prek [UsenetMedia3Vir] (meritev 26. 10. 2026: 12-43 % manj CPU za prenos, brez
 * vrat, ki bi jih dosegla druga aplikacija); ta streznik je za predvajalnike, ki potrebujejo naslov (Cast na TV v ozjem
 * krogu je korak 3 - zato samo 127.0.0.1).
 * - http://127.0.0.1:<nakljucna vrata>/<nakljucen zeton 32 B>/video.<koncnica>: nikoli kljuc indeksatorja, geslo ali
 *   message-id v naslovu; zeton se primerja v konstantnem casu; glava Host mora biti 127.0.0.1 ali localhost z nasimi vrati.
 * - Brez dnevnika zahtev (pot nosi zeton). reuseAddress izklopljen. Medpomnilnik posiljanja 256 KiB.
 * - 206 z Content-Range, 200 brez ali z neveljavnim/vecdelnim Range, HEAD, 416 izven obsega (bytes * /N), 405, 404.
 * - [close] ob koncu predvajanja ali sam po [nedejavnostMs] brez zahtev z veljavnim zetonom; zapre tudi bralnik.
 * - Pregled Grok 61 W2 / 58 T7: najvec [NAJVEC_POVEZAV] hkratnih povezav (nad tem takoj zapre - prej nit na vsako
 *   povezavo brez meje, OutOfMemoryError: pthread_create je sesul aplikacijo), glave v [ROK_POVEZAVE_MS], nedejavnost
 *   steje samo zahteve z veljavnim zetonom, po 404 se povezava zapre.
 * - T12: prvi kos se prebere pred glavami: trajna napaka (luknja, pokvarjen ...) da 502, prehodna 503 - ne prirezanega
 *   200/206; enota Range ne glede na velike crke.
 */
class UsenetStreznik(
    private val izvor: Izvor,
    private val nedejavnostMs: Long = NEDEJAVNOST_MS,
    private val obKoncu: (() -> Unit)? = null,
) : Closeable {
    /**
     * Kaj streznik streze (lastnik 27. 10. 2026): ena datoteka (film - naslov [url]) ali skladbe albuma ([urlSkladbe],
     * vsaka svoj bralnik, pripravljen sele ob prvi zahtevi - [AlbumIzvor]).
     */
    interface Izvor : Closeable {
        val stevilo: Int
        fun ime(i: Int): String?
        /** Bralnik datoteke [i]; lahko jo pripravi (prvi segment) - [UsenetTok.NapakaToka] ob luknji. */
        fun bralnik(i: Int): UsenetBralnik.Bralnik
    }

    private class EnaDatoteka(private val b: UsenetBralnik.Bralnik, private val imeDatoteke: String?) : Izvor {
        override val stevilo = 1
        override fun ime(i: Int) = imeDatoteke
        override fun bralnik(i: Int) = b
        override fun close() = b.close()
    }

    constructor(bralnik: UsenetBralnik.Bralnik, ime: String? = bralnik.vir.ime, nedejavnostMs: Long = NEDEJAVNOST_MS,
                obKoncu: (() -> Unit)? = null) : this(EnaDatoteka(bralnik, ime), nedejavnostMs, obKoncu)

    companion object {
        const val GOSTITELJ = "127.0.0.1"
        const val NEDEJAVNOST_MS = 300_000L
        const val KOS = 256 * 1024
        const val SNDBUF = 256 * 1024
        private const val NAJVEC_GLAVE = 8192
        const val NAJVEC_POVEZAV = 16
        const val ROK_POVEZAVE_MS = 60_000L
        private val TRAJNE = setOf(UsenetTok.Izid.LUKNJA, UsenetTok.Izid.POKVARJEN, UsenetTok.Izid.NESKLADNO, UsenetTok.Izid.IZVEN)
        private val VRSTE = mapOf(".mkv" to "video/x-matroska", ".mp4" to "video/mp4", ".m4v" to "video/mp4",
            ".webm" to "video/webm", ".avi" to "video/x-msvideo", ".ts" to "video/mp2t", ".m2ts" to "video/mp2t",
            ".mov" to "video/quicktime", ".wmv" to "video/x-ms-wmv", ".mpg" to "video/mpeg", ".mpeg" to "video/mpeg",
            // Lastnik 27. 10. 2026: glasba z Useneta (UsenetGlasbaPravila.ZVOK).
            ".flac" to "audio/flac", ".mp3" to "audio/mpeg", ".m4a" to "audio/mp4", ".aac" to "audio/aac",
            ".ogg" to "audio/ogg", ".opus" to "audio/ogg", ".wav" to "audio/wav")
        private val RANGE = Regex("^bytes=(\\d{0,19})-(\\d{0,19})$", RegexOption.IGNORE_CASE)

        const val NAJVEC_DIAGNOSTIKE = 40

        /** Obseg iz glave Range: (zacetek, konec vkljucno), null = cela datoteka, (-1, -1) = 416. */
        fun obseg(glava: String?, velikost: Long): Pair<Long, Long>? {
            if (glava.isNullOrBlank()) return null
            val m = RANGE.matchEntire(glava.trim().replace(" ", "")) ?: return null
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.isEmpty() && b.isEmpty()) return null
            if (a.isEmpty()) {
                val n = b.toBigInteger()
                if (n.signum() == 0 || velikost == 0L) return -1L to -1L
                return maxOf(0L, velikost - minOf(n, velikost.toBigInteger()).toLong()) to velikost - 1
            }
            val zacetek = a.toBigInteger()
            val konec = if (b.isEmpty()) (velikost - 1).toBigInteger() else b.toBigInteger()
            if (b.isNotEmpty() && konec < zacetek) return null
            if (zacetek >= velikost.toBigInteger()) return -1L to -1L
            return zacetek.toLong() to minOf(konec, (velikost - 1).toBigInteger()).toLong()
        }

        /** 'video' + znana koncnica videa; nic drugega iz imena izdaje ne gre v naslov. */
        fun imePoti(ime: String?): String {
            val k = if (ime != null && '.' in ime) "." + ime.substringAfterLast('.').lowercase() else ""
            return "video" + (if (k in VRSTE) k else ".bin")
        }
    }

    /** Velikost datoteke (film); pri albumu -1 (vsaka skladba ima svojo). */
    val velikost: Long get() = if (izvor.stevilo == 1) izvor.bralnik(0).vir.velikost else -1L
    val zeton: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val pot: String = imePoti(izvor.ime(0))
    private fun vrsta(i: Int) = VRSTE["." + imePoti(izvor.ime(i)).substringAfterLast('.')] ?: "application/octet-stream"
    private val posluh = ServerSocket().apply {
        reuseAddress = false
        bind(java.net.InetSocketAddress(InetAddress.getByName(GOSTITELJ), 0), 50)
    }
    val vrata: Int = posluh.localPort
    val url: String = "http://$GOSTITELJ:$vrata/$zeton/$pot"

    /** Naslov skladbe [i] albuma: stevilka in koncnica, nic iz imena izdaje. */
    fun urlSkladbe(i: Int): String = "http://$GOSTITELJ:$vrata/$zeton/$i/${imePoti(izvor.ime(i))}"
    /** Zahteve z veljavnim zetonom v teku (nedejavnost); povezave so v [vticnice]. */
    private val odprtih = AtomicInteger()
    @Volatile private var zadnja = System.nanoTime()
    @Volatile var zaprt = false; private set
    private val vticnice = java.util.Collections.synchronizedSet(HashSet<Socket>())

    /**
     * Diagnostika (TV 28. 10. 2026: MatroskaExtractor NullPointerException pred prvo sliko - lastnik: prioriteta): prvih
     * [NAJVEC_DIAGNOSTIKE] zahtev predvajalnika z obsegom in prvimi bajti odgovora (za MKV: ali je na odmiku Cues res
     * element Cues 1C53BB6B). Brez naslova, zetona in imena izdaje.
     */
    private val diagnostik = AtomicInteger()
    private fun dnevnik(kaj: String) {
        // Prek odseva: ista datoteka se prevaja tudi v preizkusih JVM (tests/run_os_tests.sh), kjer android.util.Log ni.
        try {
            Class.forName("android.util.Log").getMethod("i", String::class.java, String::class.java).invoke(null, "SafeerUsenetHttp", kaj)
        } catch (_: Throwable) { }
    }

    init {
        Thread({ sprejemaj() }, "safeer-usenet-http").apply { isDaemon = true; start() }
        Thread({ pazi() }, "safeer-usenet-http-varuh").apply { isDaemon = true; start() }
    }

    override fun toString() = "UsenetStreznik(127.0.0.1:$vrata, ${if (zaprt) "zaprt" else "odprt"})"  // brez zetona

    private fun pazi() {
        while (!zaprt) {
            try { Thread.sleep(maxOf(50L, minOf(1000L, nedejavnostMs / 4))) } catch (_: InterruptedException) { return }
            if (odprtih.get() == 0 && (System.nanoTime() - zadnja) / 1_000_000 > nedejavnostMs) close()
        }
    }

    private fun sprejemaj() {
        while (!zaprt) {
            val s = try { posluh.accept() } catch (_: IOException) { return }
            val sprejeta = synchronized(vticnice) { (vticnice.size < NAJVEC_POVEZAV).also { if (it) vticnice += s } }
            if (!sprejeta) {
                try { s.close() } catch (_: IOException) { }  // nad mejo: takoj zapri, brez niti
                continue
            }
            try {
                Thread({ strezi(s) }, "safeer-usenet-http-zahteva").apply { isDaemon = true; start() }
            } catch (_: Throwable) {
                // OutOfMemoryError: pthread_create ali podobno - povezava proc, streznik in aplikacija ostaneta (W2)
                vticnice -= s
                try { s.close() } catch (_: IOException) { }
            }
        }
    }

    /** Za preizkus: odprte povezave. */
    internal val povezav: Int get() = vticnice.size

    override fun close() {
        synchronized(this) {
            if (zaprt) return
            zaprt = true
        }
        try { posluh.close() } catch (_: IOException) { }
        synchronized(vticnice) { for (s in vticnice) try { s.close() } catch (_: IOException) { } }
        try { izvor.close() } catch (_: Exception) { }
        try { obKoncu?.invoke() } catch (_: Exception) { }
    }

    private fun vrstica(vhod: BufferedInputStream, meja: IntArray, rok: Long): String? {
        val sb = StringBuilder()
        while (true) {
            val c = vhod.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (--meja[0] < 0) throw IOException("glava prevelika")
            if (System.nanoTime() > rok) throw IOException("glava prepocasna")  // kapljanje po bajt pod soTimeout
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
        }
    }

    private fun pisi(izhod: OutputStream, koda: Int, razlog: String, glave: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $koda $razlog\r\nServer: Safeer\r\n")
        for ((k, v) in glave) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        izhod.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** Indeks datoteke za veljavno zahtevo (zeton, Host, pot) ali -1. Film: /zeton/pot, album: /zeton/<i>/pot. */
    private fun indeks(cilj: String, gost: String?): Int {
        val deli = cilj.substringBefore('?').split('/')
        val g = (gost ?: "").trim().lowercase()
        if (g != "$GOSTITELJ:$vrata" && g != "localhost:$vrata") return -1
        if (deli.size < 3 || deli[0].isNotEmpty() ||
            !MessageDigest.isEqual(deli[1].toByteArray(Charsets.ISO_8859_1), zeton.toByteArray(Charsets.ISO_8859_1))) return -1
        if (deli.size == 3) return if (izvor.stevilo == 1 && deli[2] == pot) 0 else -1
        if (deli.size != 4) return -1
        val i = deli[2].toIntOrNull() ?: return -1
        return if (i in 0 until izvor.stevilo && deli[2] == i.toString() && deli[3] == imePoti(izvor.ime(i))) i else -1
    }

    private fun strezi(s: Socket) {
        try {
            s.sendBufferSize = SNDBUF
            s.soTimeout = ROK_POVEZAVE_MS.toInt()
            val vhod = BufferedInputStream(s.getInputStream())
            val izhod = s.getOutputStream()
            while (!zaprt) {
                val meja = intArrayOf(NAJVEC_GLAVE)
                val zahteva = vrstica(vhod, meja, Long.MAX_VALUE) ?: return  // med zahtevami velja soTimeout
                if (zahteva.isEmpty()) continue
                val rok = System.nanoTime() + ROK_POVEZAVE_MS * 1_000_000
                val glave = HashMap<String, String>()
                while (true) {
                    val v = vrstica(vhod, meja, rok) ?: return
                    if (v.isEmpty()) break
                    val i = v.indexOf(':')
                    if (i > 0) glave.putIfAbsent(v.substring(0, i).trim().lowercase(), v.substring(i + 1).trim())
                }
                val deli = zahteva.split(' ')
                if (deli.size != 3) return
                val metoda = deli[0]
                val prazno = listOf("Content-Length" to "0")
                if (metoda != "GET" && metoda != "HEAD") {
                    pisi(izhod, 405, "Method Not Allowed", listOf("Allow" to "GET, HEAD") + prazno); izhod.flush(); continue
                }
                val i = if (zaprt) -1 else indeks(deli[1], glave["host"])
                if (i < 0) {
                    // zeton pred vsem ostalim; brez njega povezava ne ostane in ne osvezi nedejavnosti (T7)
                    pisi(izhod, 404, "Not Found", listOf("Connection" to "close") + prazno); izhod.flush(); return
                }
                odprtih.incrementAndGet()
                zadnja = System.nanoTime()
                try {
                    if (!odgovori(s, izhod, metoda, glave["range"], i)) return
                } finally {
                    odprtih.decrementAndGet()
                    zadnja = System.nanoTime()
                }
            }
        } catch (_: SocketException) {
            // predvajalnik je zaprl povezavo (skok, konec)
        } catch (_: IOException) {
        } finally {
            vticnice -= s
            try { s.close() } catch (_: IOException) { }
        }
    }

    /** Odgovor na veljavno zahtevo; false = povezavo zapri. */
    private fun odgovori(s: Socket, izhod: OutputStream, metoda: String, range: String?, i: Int): Boolean {
        val prazno = listOf("Content-Length" to "0")

        // Napako lahko posljemo le pred zacetkom odgovora 200/206.
        fun napaka(koda: Int): Boolean {
            val glave = arrayListOf("Connection" to "close", "Content-Length" to "0")
            if (koda == 503) glave += "Retry-After" to "1"
            pisi(izhod, koda, if (koda == 502) "Bad Gateway" else "Service Unavailable", glave)
            izhod.flush()
            return false
        }

        // Album lahko pri pripravi bralnika potrebuje omrezni dostop.
        val bralnik = try {
            izvor.bralnik(i)
        } catch (e: UsenetTok.NapakaToka) {
            return napaka(if (e.izid in TRAJNE) 502 else 503)
        } catch (_: Exception) {
            return napaka(503)
        }
        val velikost = bralnik.vir.velikost
        if (velikost < 0) return napaka(502)
        val vrsta = vrsta(i)
        val o = obseg(range, velikost)
        if (o != null && o.first < 0) {
            pisi(izhod, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$velikost", "Accept-Ranges" to "bytes") + prazno)
            izhod.flush(); return true
        }
        val (zacetek, konec) = o ?: (0L to velikost - 1)
        val dolzina = if (konec >= zacetek) konec - zacetek + 1 else 0L
        var prvi = ByteArray(0)
        if (metoda == "GET" && dolzina > 0) {
            val zahtevano = minOf(KOS.toLong(), dolzina).toInt()
            prvi = try {
                bralnik.beri(zacetek, zahtevano)
            } catch (e: UsenetTok.NapakaToka) {
                val koda = if (e.izid in TRAJNE) 502 else 503
                dnevnik("zahteva $zacetek-$konec/$velikost: $koda (${e.izid})")
                return napaka(koda)
            } catch (_: Exception) {
                dnevnik("zahteva $zacetek-$konec/$velikost: napaka pri prvem branju")
                return napaka(503)
            }
            if (prvi.isEmpty()) {
                dnevnik("zahteva $zacetek-$konec/$velikost: prvi kos ni na voljo")
                return napaka(503)
            }
            // Bralnik ne sme poslati vec bajtov, kot smo jih zahtevali.
            if (prvi.size > zahtevano) {
                dnevnik("zahteva $zacetek-$konec/$velikost: prevelik prvi kos")
                return napaka(502)
            }
        }
        if (metoda == "GET" && diagnostik.incrementAndGet() <= NAJVEC_DIAGNOSTIKE)
            dnevnik("zahteva range=${range ?: "-"} -> ${if (o == null) 200 else 206} $zacetek-$konec/$velikost, prvih=" +
                prvi.take(8).joinToString("") { "%02X".format(it.toInt() and 0xFF) })
        val g = arrayListOf("Content-Type" to vrsta, "Accept-Ranges" to "bytes", "Cache-Control" to "no-store",
            "Content-Length" to dolzina.toString())
        if (o != null) g += "Content-Range" to "bytes $zacetek-$konec/$velikost"

        // HEAD nima telesa; prazna datoteka pri GET prav tako ne potrebuje prvega kosa.
        if (metoda == "HEAD" || dolzina == 0L) {
            pisi(izhod, if (o == null) 200 else 206, if (o == null) "OK" else "Partial Content", g)
            izhod.flush()
            return true
        }

        // RST pripravimo pred statusom, tudi za primer, da close() socasno zapre vticnico.
        // Po uspesnem prenosu nastavitev izklopimo, da je keep-alive spet varen.
        try {
            s.setSoLinger(true, 0)
        } catch (_: SocketException) {
            return napaka(503)
        }
        var poz = zacetek
        if (i > 0 && zacetek >= minOf(velikost / 2L, 64 * 1024L)) {
            (izvor as? AlbumIzvor)?.pocistiPrejsnje(i)
        }
        try {
            pisi(izhod, if (o == null) 200 else 206, if (o == null) "OK" else "Partial Content", g)
            while (poz <= konec) {
                val zahtevano = minOf(KOS.toLong(), konec - poz + 1).toInt()
                val kos = if (prvi.isNotEmpty()) prvi else bralnik.beri(poz, zahtevano)
                prvi = ByteArray(0)
                if (kos.isEmpty() || kos.size > zahtevano) {
                    dnevnik("tok prekinjen pri $poz od $zacetek-$konec: nevelik kos")
                    return false // linger ostane vklopljen; strezi v finally zapre vticnico z RST
                }
                izhod.write(kos)
                poz += kos.size
                zadnja = System.nanoTime()
                if (i > 0 && poz >= minOf(velikost / 2L, 64 * 1024L)) {
                    (izvor as? AlbumIzvor)?.pocistiPrejsnje(i)
                }
            }
            izhod.flush()
            s.setSoLinger(false, 0)
            return true
        } catch (e: Exception) {
            // Status je morda ze poslan: novega HTTP odgovora ne smemo vec pisati.
            dnevnik("tok prekinjen pri $poz od $zacetek-$konec: ${e.javaClass.simpleName}")
            return false // linger ostane vklopljen; strezi v finally zapre vticnico z RST
        }
    }
}

/**
 * Lastnik 27. 10. 2026: skladbe albuma z Useneta. Vsaka se pripravi (prvi segment, bralnik z nitmi in predpomnilnikom) sele
 * ob prvi zahtevi predvajalnika; odprtih je najvec [najvecOdprtih] - najdlje neuporabljena se zapre in se ob ponovni
 * zahtevi pripravi znova.
 * Lastnik 29. 10. 2026: ko se naslednja skladba predvaja (ze na manj kot polovici), se prejsnje skladbe sproti brisejo
 * iz pomnilnika ([pocistiPrejsnje]), zato sta odprti le se aktivna in naslednja vnaprej ([NAJVEC_ODPRTIH] = 2).
 */
class AlbumIzvor(
    private val skladbe: List<Pair<String?, () -> UsenetBralnik.Vir>>,
    private val telo: (String) -> ByteArray,
    private val profil: UsenetBralnik.Profil,
    private val najvecOdprtih: Int = NAJVEC_ODPRTIH,
) : UsenetStreznik.Izvor {
    companion object { const val NAJVEC_ODPRTIH = 1 }

    private val viri = arrayOfNulls<UsenetBralnik.Vir>(skladbe.size)
    private val odprti = LinkedHashMap<Int, UsenetBralnik.Bralnik>(8, 0.75f, true)
    private val priprava = Array(skladbe.size) { Any() }
    @Volatile private var zaprt = false
    private val vnaprej = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "safeer-usenet-album-vnaprej").apply { isDaemon = true }
    }

    /**
     * Lastnik 28. 10. 2026: vir skladbe [i] (prvi segment) pripravi v ozadju, se preden jo predvajalnik zahteva - brez
     * bralnika (niti in predpomnilnik nastanejo ob zahtevi). Prej je prva zahteva cakala na povezavo NNTP in prvi segment
     * in je predvajalnik po 8 s skladbo preskocil.
     */
    fun pripraviVnaprej(i: Int) {
        if (i !in skladbe.indices || zaprt) return
        try {
            vnaprej.execute {
                try { synchronized(priprava[i]) { if (viri[i] == null && !zaprt) viri[i] = skladbe[i].second() } } catch (_: Exception) { }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    override val stevilo: Int get() = skladbe.size
    override fun ime(i: Int): String? = skladbe[i].first

    /** Za preizkus: odprti bralniki. */
    val odprtih: Int @Synchronized get() = odprti.size

    /**
     * Lastnik 29. 10. 2026: ko se naslednja skladba [i] predvaja (ze na manj kot polovici ali takoj po zacetku),
     * se prejsnje skladbe (< i) takoj zaprejo in izbrisejo iz pomnilnika (RAM), da je ves prostor na voljo aktivni skladbi.
     */
    fun pocistiPrejsnje(i: Int) {
        val stari = ArrayList<UsenetBralnik.Bralnik>()
        synchronized(this) {
            if (zaprt) return
            val iter = odprti.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.key < i) {
                    stari += e.value
                    iter.remove()
                }
            }
        }
        stari.forEach { try { it.close() } catch (_: Exception) { } }
    }

    override fun bralnik(i: Int): UsenetBralnik.Bralnik {
        synchronized(this) { if (zaprt) throw IOException("album zaprt"); odprti[i]?.let { return it } }
        // Priprava (omrezje) brez skupnega zaklepa: trenutna skladba med pripravo naslednje ne caka.
        synchronized(priprava[i]) {
            synchronized(this) { odprti[i]?.let { return it } }
            val vir = viri[i] ?: skladbe[i].second().also { viri[i] = it }
            val b = UsenetBralnik.Bralnik(telo, vir, profil)
            val stari = ArrayList<UsenetBralnik.Bralnik>()
            synchronized(this) {
                if (zaprt) { b.close(); throw IOException("album zaprt") }
                odprti[i] = b
                val iter = odprti.entries.iterator()
                while (odprti.size > najvecOdprtih && iter.hasNext()) { val e = iter.next(); if (e.key != i) { stari += e.value; iter.remove() } }
            }
            stari.forEach { try { it.close() } catch (_: Exception) { } }
            pripraviVnaprej(i + 1)      // naslednja skladba je pripravljena, ko pride na vrsto
            return b
        }
    }

    override fun close() {
        vnaprej.shutdownNow()
        val vsi = synchronized(this) { zaprt = true; ArrayList(odprti.values).also { odprti.clear() } }
        vsi.forEach { try { it.close() } catch (_: Exception) { } }
    }
}
