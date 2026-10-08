package si.safeer.tv.os

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Majhen HTTP streznik za zvocnik v omrezju: datoteko s te naprave (content://, file://) ponudi zvocniku
 * na domacem naslovu - samo EN vir naenkrat, samo pod nakljucnim zetonom, samo branje, z Range (premik).
 * Tece le, dokler zvocnik igra nase; drugi naslovi vrnejo 404.
 */
class ZvocnikStreznik(ctx: Context) {
    private val app = ctx.applicationContext
    private val streznik = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
    @Volatile private var pot = ""
    @Volatile private var vir: String = ""
    @Volatile private var mime = "audio/mpeg"
    @Volatile private var odprt = true
    private val odprtih = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        Thread({ sprejemaj() }, "safeer-zvocnik-streznik").apply { isDaemon = true }.start()
    }

    /** Pripravi vir in vrne naslov, ki ga dobi zvocnik (lokalni naslov, s katerim ta naprava doseze zvocnik). */
    fun ponudi(url: String, znanMime: String, zvocnikIp: String): String {
        val zeton = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val ime = Uri.parse(url).lastPathSegment?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() } ?: "zvok"
        vir = url
        mime = if (znanMime.startsWith("audio/")) znanMime else (app.contentResolver.getType(Uri.parse(url))
            ?.takeIf { it.startsWith("audio/") } ?: DlnaPravila.mime(ime))
        pot = "/$zeton/$ime"
        return "http://${lokalniNaslov(zvocnikIp)}:${streznik.localPort}$pot"
    }

    fun mime(): String = mime

    /** Vir ni vec v ponudbi (zvocnik zdaj igra kaj drugega): stari naslov vrne 404. */
    fun pozabi() { pot = ""; vir = "" }

    fun zapri() {
        odprt = false
        try { streznik.close() } catch (_: Exception) {}
    }

    private fun lokalniNaslov(cilj: String): String = DatagramSocket().use { s ->
        s.connect(InetAddress.getByName(cilj), 9)      // brez posiljanja: le izbira poti
        s.localAddress.hostAddress ?: "127.0.0.1"
    }

    private fun sprejemaj() {
        while (odprt) {
            val s = try { streznik.accept() } catch (_: Exception) { if (!odprt) return else continue }
            // Zvocnik odpre nekaj povezav (glava, kosi z Range); vec hkratnih ne strezemo - naprava v omrezju ne sme zasesti niti.
            if (odprtih.incrementAndGet() > NAJVEC_POVEZAV) { odprtih.decrementAndGet(); try { s.close() } catch (_: Exception) {}; continue }
            Thread({ try { obdelaj(s) } catch (e: Exception) { Log.d("SafeerZvocniki", "Streznik: ${e.message}") } finally { odprtih.decrementAndGet(); try { s.close() } catch (_: Exception) {} } },
                "safeer-zvocnik-odjemalec").apply { isDaemon = true }.start()
        }
    }

    private fun vrstica(vhod: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val c = vhod.read(); if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun obdelaj(s: Socket) {
        s.soTimeout = 15000
        val vhod = BufferedInputStream(s.getInputStream())
        val zahteva = vrstica(vhod).split(" ")
        val glave = HashMap<String, String>()
        for (stevilo in 0 until NAJVEC_GLAV) {
            val v = vrstica(vhod); if (v.isEmpty()) break
            val i = v.indexOf(':'); if (i > 0) glave[v.substring(0, i).trim().lowercase()] = v.substring(i + 1).trim()
        }
        val izhod = s.getOutputStream()
        fun odgovor(koda: String, dodatno: List<String>) {
            izhod.write((listOf("HTTP/1.1 $koda", "Connection: close", "Accept-Ranges: bytes",
                "transferMode.dlna.org: Streaming",
                "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000") +
                dodatno).joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.US_ASCII))
        }
        val metoda = zahteva.getOrNull(0).orEmpty()
        val zahtevanaPot = zahteva.getOrNull(1).orEmpty().substringBefore('?')
        if ((metoda != "GET" && metoda != "HEAD") || pot.isEmpty() || zahtevanaPot != pot) { odgovor("404 Not Found", listOf("Content-Length: 0")); return }
        val uri = Uri.parse(vir)
        val velikost = velikost(uri)
        val obseg = DlnaPravila.obseg(glave["range"], velikost)
        val tip = "Content-Type: $mime"
        val (od, dolzina) = when {
            obseg != null -> obseg.first to (obseg.last - obseg.first + 1)
            else -> 0L to velikost
        }
        val glavaDolzine = if (dolzina >= 0) listOf("Content-Length: $dolzina") else emptyList()
        if (obseg != null) odgovor("206 Partial Content", listOf(tip, "Content-Range: bytes ${obseg.first}-${obseg.last}/$velikost") + glavaDolzine)
        else odgovor("200 OK", listOf(tip) + glavaDolzine)
        if (metoda == "HEAD") { izhod.flush(); return }
        odpri(uri).use { tok ->
            var preskoci = od
            while (preskoci > 0) { val n = tok.skip(preskoci); if (n <= 0) break; preskoci -= n }
            val kos = ByteArray(64 * 1024)
            var ostane = if (dolzina >= 0) dolzina else Long.MAX_VALUE
            while (ostane > 0) {
                val n = tok.read(kos, 0, minOf(kos.size.toLong(), ostane).toInt()); if (n < 0) break
                izhod.write(kos, 0, n); ostane -= n
            }
        }
        izhod.flush()
    }

    private fun odpri(uri: Uri): InputStream = when (uri.scheme?.lowercase()) {
        "content" -> app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("ni vira")
        "file" -> datoteka(uri.path ?: "").inputStream()
        else -> datoteka(vir).inputStream()
    }

    /** Datoteka iz skupne shrambe naprave; zasebnih podatkov aplikacije streznik ne ponuja, tudi ce jih vrsta navede. */
    private fun datoteka(pot: String): File {
        val f = File(pot).canonicalFile
        val zasebno = File(app.applicationInfo.dataDir).canonicalPath
        if (f.path == zasebno || f.path.startsWith("$zasebno/")) throw SecurityException("zasebna datoteka")
        return f
    }

    private companion object {
        const val NAJVEC_POVEZAV = 8
        const val NAJVEC_GLAV = 64
    }

    private fun velikost(uri: Uri): Long = try {
        when (uri.scheme?.lowercase()) {
            "content" -> app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            "file" -> File(uri.path ?: "").length()
            else -> File(vir).length()
        }
    } catch (_: Exception) { -1L }
}
