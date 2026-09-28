package si.safeer.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ikone bliznjic, prenesene neposredno z njihovih strani in shranjene kot lokalni data URL-i. */
class SpletIkone(context: Context, private val obSpremembi: () -> Unit) {
    private val app = context.applicationContext
    private val mapa = File(app.filesDir, "ikone")
    private val opravila = Executors.newFixedThreadPool(2) { r -> Thread(r, "safeer-splet-ikone").also { it.isDaemon = true } }
    private val zacete = ConcurrentHashMap.newKeySet<String>()
    private val odjemalec = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()

    fun preberi(url: String): String = try { datoteka(url).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty() } catch (_: Throwable) { "" }

    fun zagotovi(url: String) {
        if (preberi(url).isNotEmpty() || !zacete.add(url)) return
        opravila.execute {
            val ikona = try { prenesiIkono(url) } catch (e: Throwable) {
                Log.d("SafeerSpletIkone", "Ikone za ${url.take(80)} ni bilo mogoce dobiti: ${e.message}"); ""
            }
            if (ikona.isNotEmpty()) {
                try {
                    mapa.mkdirs()
                    val cilj = datoteka(url)
                    val zacasna = File(mapa, cilj.name + ".tmp")
                    zacasna.writeText(ikona, Charsets.UTF_8)
                    if (!zacasna.renameTo(cilj)) { cilj.delete(); zacasna.renameTo(cilj) }
                    obSpremembi()
                } catch (e: Throwable) { Log.w("SafeerSpletIkone", "Predpomnilnik ikone: ${e.message}") }
            }
        }
    }

    private fun prenesiIkono(url: String): String {
        val stran = URI(url.trim())
        if (stran.scheme?.lowercase() !in setOf("http", "https") || stran.host.isNullOrBlank() || jeBlokirano(url)) return ""
        val koren = URI(stran.scheme, stran.userInfo, stran.host, stran.port, "/", null, null)
        val kandidati = ArrayList<String>()
        prenesi(url, 400_000)?.first?.toString(Charsets.UTF_8)?.let { html ->
            for (k in SpletIkonePravila.kandidati(html)) {
                try { kandidati += stran.resolve(k.href).toString() } catch (_: Throwable) { }
            }
        }
        kandidati += koren.resolve("apple-touch-icon.png").toString()
        kandidati += koren.resolve("favicon.ico").toString()
        for (kandidat in kandidati.distinct()) {
            if (jeBlokirano(kandidat)) continue
            val (podatki, prijavljenaVrsta) = prenesi(kandidat, 300_000) ?: continue
            val vrsta = vrstaSlike(podatki, prijavljenaVrsta) ?: continue
            return "data:$vrsta;base64," + Base64.encodeToString(podatki, Base64.NO_WRAP)
        }
        return ""
    }

    private fun prenesi(url: String, meja: Int): Pair<ByteArray, String>? {
        val zahteva = Request.Builder().url(url).header("User-Agent", UPORABNISKI_AGENT).header("Accept", "*/*").build()
        odjemalec.newCall(zahteva).execute().use { odgovor ->
            if (!odgovor.isSuccessful) return null
            val telo = odgovor.body ?: return null
            if (telo.contentLength() > meja) return null
            val tok = telo.byteStream()
            val ven = ByteArrayOutputStream(minOf(meja, 32_768))
            val kos = ByteArray(8192)
            var skupaj = 0
            while (true) {
                val n = tok.read(kos); if (n < 0) break
                skupaj += n; if (skupaj > meja) return null
                ven.write(kos, 0, n)
            }
            val vrsta = odgovor.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            return ven.toByteArray() to vrsta
        }
    }

    private fun vrstaSlike(p: ByteArray, prijavljena: String): String? {
        if (prijavljena.startsWith("image/")) return prijavljena
        if (p.size >= 4 && p[0] == 0x89.toByte() && p[1] == 'P'.code.toByte() && p[2] == 'N'.code.toByte() && p[3] == 'G'.code.toByte()) return "image/png"
        if (p.size >= 4 && p[0] == 0.toByte() && p[1] == 0.toByte() && p[2] == 1.toByte() && p[3] == 0.toByte()) return "image/x-icon"
        if (p.copyOfRange(0, minOf(p.size, 400)).toString(Charsets.UTF_8).lowercase().contains("<svg")) return "image/svg+xml"
        return null
    }

    private fun jeBlokirano(url: String): Boolean = try {
        val host = URI(url).host?.lowercase()?.trimEnd('.') ?: return true
        val oglas = AdBlockEngine.vgrajeneOglasneDomene().any { host == it || host.endsWith(".$it") } ||
            AdBlockEngine.shouldBlockUrl("https://$host/")
        oglas || ThreatBlockEngine.isThreat("https://$host/")
    } catch (_: Throwable) { true }

    private fun datoteka(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val ime = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(mapa, "$ime.txt")
    }

    companion object {
        private const val UPORABNISKI_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
