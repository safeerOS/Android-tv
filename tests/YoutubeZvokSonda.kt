package si.safeer.tv.os

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * Tedenska sonda (CI .github/workflows/youtube-tedensko.yml): ali YouTube se da zvocni tok po poti, ki jo uporablja
 * »Predvajaj na zvocniku« ([YoutubeZvokPravila]: visitor_id -> player VISIONOS -> itag 140 -> obseg Range na sredini toka).
 * Brez kljucev, samo dva javna posnetka; naslovov ne izpisuje.
 *
 * Izhod 1: YouTube je spremenil odjemalca ali obliko odgovora - posodobi YoutubeZvokPravila (vzor: yt-dlp, Unlicense; ne
 * NewPipe, GPL). Izhod 0 z opozorilom: YouTube je IP poganjalca oznacil za robota (pri podatkovnih centrih pogosto) - sonda
 * takrat nic ne pove; isto sondo pozenemo na prenosniku ob tedenskem pregledu (sreda).
 */
private fun klic(url: String, telo: String?, glave: Map<String, String>, obseg: String? = null): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = false
    c.connectTimeout = 10_000; c.readTimeout = 15_000
    glave.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (obseg != null) c.setRequestProperty("Range", obseg)
    if (telo != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(telo.toByteArray()) } }
    try {
        val koda = c.responseCode
        val out = ByteArrayOutputStream()
        (if (koda in 200..299) c.inputStream else c.errorStream)?.use { vhod ->
            val kos = ByteArray(16 * 1024)
            while (out.size() < YoutubeZvokPravila.NAJVEC_ODGOVORA) { val n = vhod.read(kos); if (n < 0) break; out.write(kos, 0, n) }
        }
        return koda to out.toString("UTF-8")
    } finally { c.disconnect() }
}

fun main() {
    val posnetki = listOf("jNQXAC9IVRw", "dQw4w9WgXcQ")
    val (kv, ov) = klic(YoutubeZvokPravila.VISITOR_ID, YoutubeZvokPravila.telesoVisitorId(), YoutubeZvokPravila.glaveVisitorId())
    val obisk = YoutubeZvokPravila.visitorIzOdgovora(ov)
    if (obisk == null) { println("::error::visitor_id (HTTP $kv) ne vrne visitorData - YouTube je spremenil vmesnik"); exitProcess(1) }
    var bot = 0
    var napak = 0
    for (id in posnetki) {
        val (k, odg) = klic(YoutubeZvokPravila.PLAYER, YoutubeZvokPravila.telesoPlayer(id, obisk), YoutubeZvokPravila.glavePlayer(obisk))
        val izid = YoutubeZvokPravila.izberi(odg, System.currentTimeMillis() / 1000)
        val t = izid.tok
        when {
            t != null -> {
                val sredina = maxOf(0L, t.bajtov / 2)
                val (kr, _) = klic(t.url, null, mapOf("User-Agent" to YoutubeZvokPravila.UA), "bytes=$sredina-${sredina + 65_535}")
                if (kr == 206) println("$id: OK - itag ${t.itag} ${t.mime}, obseg na sredini toka 206")
                else { println("::error::$id: obseg na sredini toka vrne HTTP $kr (YouTube zahteva PO token?)"); napak++ }
            }
            izid.razlog == YoutubeZvokPravila.Razlog.BOT -> { println("::warning::$id: YouTube zahteva preverbo »not a bot« (IP poganjalca)"); bot++ }
            else -> { println("::error::$id: player HTTP $k, ${izid.razlog} ${izid.opis}"); napak++ }
        }
    }
    if (napak > 0) exitProcess(1)
    if (bot == posnetki.size) println("::warning::Sonda ni nicesar preverila (IP poganjalca) - pozeni jo na prenosniku.")
}
