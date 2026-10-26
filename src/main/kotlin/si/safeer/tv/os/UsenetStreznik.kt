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
 * - [close] ob koncu predvajanja ali sam po [nedejavnostMs] brez zahtev in odprtih povezav; zapre tudi bralnik.
 */
class UsenetStreznik(
    private val bralnik: UsenetBralnik.Bralnik,
    ime: String? = bralnik.vir.ime,
    private val nedejavnostMs: Long = NEDEJAVNOST_MS,
    private val obKoncu: (() -> Unit)? = null,
) : Closeable {
    companion object {
        const val GOSTITELJ = "127.0.0.1"
        const val NEDEJAVNOST_MS = 300_000L
        const val KOS = 256 * 1024
        const val SNDBUF = 256 * 1024
        private const val NAJVEC_GLAVE = 8192
        private val VRSTE = mapOf(".mkv" to "video/x-matroska", ".mp4" to "video/mp4", ".m4v" to "video/mp4",
            ".webm" to "video/webm", ".avi" to "video/x-msvideo", ".ts" to "video/mp2t", ".m2ts" to "video/mp2t",
            ".mov" to "video/quicktime", ".wmv" to "video/x-ms-wmv", ".mpg" to "video/mpeg", ".mpeg" to "video/mpeg")
        private val RANGE = Regex("^bytes=(\\d{0,19})-(\\d{0,19})$")

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

    val velikost: Long = bralnik.vir.velikost
    val zeton: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val pot: String = imePoti(ime)
    private val vrsta = VRSTE["." + pot.substringAfterLast('.')] ?: "application/octet-stream"
    private val posluh = ServerSocket().apply {
        reuseAddress = false
        bind(java.net.InetSocketAddress(InetAddress.getByName(GOSTITELJ), 0), 50)
    }
    val vrata: Int = posluh.localPort
    val url: String = "http://$GOSTITELJ:$vrata/$zeton/$pot"
    private val odprtih = AtomicInteger()
    @Volatile private var zadnja = System.nanoTime()
    @Volatile var zaprt = false; private set
    private val vticnice = java.util.Collections.synchronizedSet(HashSet<Socket>())

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
            vticnice += s
            Thread({ strezi(s) }, "safeer-usenet-http-zahteva").apply { isDaemon = true; start() }
        }
    }

    override fun close() {
        synchronized(this) {
            if (zaprt) return
            zaprt = true
        }
        try { posluh.close() } catch (_: IOException) { }
        synchronized(vticnice) { for (s in vticnice) try { s.close() } catch (_: IOException) { } }
        bralnik.close()
        try { obKoncu?.invoke() } catch (_: Exception) { }
    }

    private fun vrstica(vhod: BufferedInputStream, meja: IntArray): String? {
        val sb = StringBuilder()
        while (true) {
            val c = vhod.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (--meja[0] < 0) throw IOException("glava prevelika")
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

    private fun dovoljeno(cilj: String, gost: String?): Boolean {
        val deli = cilj.substringBefore('?').split('/')
        val pravi = deli.size == 3 && deli[0].isEmpty() &&
            MessageDigest.isEqual(deli[1].toByteArray(Charsets.ISO_8859_1), zeton.toByteArray(Charsets.ISO_8859_1)) && deli[2] == pot
        val g = (gost ?: "").trim().lowercase()
        return pravi && (g == "$GOSTITELJ:$vrata" || g == "localhost:$vrata")
    }

    private fun strezi(s: Socket) {
        odprtih.incrementAndGet()
        zadnja = System.nanoTime()
        try {
            s.sendBufferSize = SNDBUF
            s.soTimeout = 60_000
            val vhod = BufferedInputStream(s.getInputStream())
            val izhod = s.getOutputStream()
            while (!zaprt) {
                val meja = intArrayOf(NAJVEC_GLAVE)
                val zahteva = vrstica(vhod, meja) ?: return
                if (zahteva.isEmpty()) continue
                val glave = HashMap<String, String>()
                while (true) {
                    val v = vrstica(vhod, meja) ?: return
                    if (v.isEmpty()) break
                    val i = v.indexOf(':')
                    if (i > 0) glave.putIfAbsent(v.substring(0, i).trim().lowercase(), v.substring(i + 1).trim())
                }
                zadnja = System.nanoTime()
                val deli = zahteva.split(' ')
                if (deli.size != 3) return
                val metoda = deli[0]
                val prazno = listOf("Content-Length" to "0")
                if (metoda != "GET" && metoda != "HEAD") {
                    pisi(izhod, 405, "Method Not Allowed", listOf("Allow" to "GET, HEAD") + prazno); izhod.flush(); continue
                }
                if (zaprt || !dovoljeno(deli[1], glave["host"])) {
                    pisi(izhod, 404, "Not Found", prazno); izhod.flush(); continue
                }
                val o = obseg(glave["range"], velikost)
                if (o != null && o.first < 0) {
                    pisi(izhod, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$velikost", "Accept-Ranges" to "bytes") + prazno)
                    izhod.flush(); continue
                }
                val (zacetek, konec) = o ?: (0L to velikost - 1)
                val g = arrayListOf("Content-Type" to vrsta, "Accept-Ranges" to "bytes", "Cache-Control" to "no-store",
                    "Content-Length" to maxOf(0L, konec - zacetek + 1).toString())
                if (o != null) g += "Content-Range" to "bytes $zacetek-$konec/$velikost"
                pisi(izhod, if (o == null) 200 else 206, if (o == null) "OK" else "Partial Content", g)
                if (metoda == "HEAD") { izhod.flush(); continue }
                var poz = zacetek
                try {
                    while (poz <= konec) {
                        val kos = bralnik.beri(poz, minOf(KOS.toLong(), konec - poz + 1).toInt())
                        if (kos.isEmpty()) break
                        izhod.write(kos)
                        poz += kos.size
                        zadnja = System.nanoTime()
                    }
                    izhod.flush()
                } catch (_: UsenetTok.NapakaToka) {
                    return  // obljubljene dolzine ni bilo: povezavo zapremo, predvajalnik odpre znova
                }
                if (poz <= konec) return
            }
        } catch (_: SocketException) {
            // predvajalnik je zaprl povezavo (skok, konec)
        } catch (_: IOException) {
        } finally {
            vticnice -= s
            try { s.close() } catch (_: IOException) { }
            odprtih.decrementAndGet()
            zadnja = System.nanoTime()
        }
    }
}
