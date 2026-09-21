package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Viri, ki jih doda uporabnik sam: streznik PeerTube (video), neposreden tok (radio, glasba,
 * video po naslovu) ali spletna stran, ki jo odpre brskalnik Safeer - jedro Safeer OS, ki predvaja vse. Kje vire najde, je njegova stvar; Safeer jih samo predvaja cisto - promet
 * gre skozi Scit kot ves ostali. Shranjeni so samo na tej napravi.
 */
object MedijskiViri {
    private const val NASTAVITVE = "safeer_mediji"
    private const val KLJUC = "viri"

    data class Vir(val tip: String, val ime: String, val naslov: String) {
        val jePeerTube get() = tip == PEERTUBE
        val jeSplet get() = tip == SPLET
    }

    const val PEERTUBE = "peertube"
    const val TOK = "tok"
    /** Spletna stran z glasbo ali videom: odpre jo brskalnik Safeer (jedro Safeer OS), ki predvaja vse. */
    const val SPLET = "splet"

    fun vsi(ctx: Context): List<Vir> {
        val a = try { JSONArray(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Vir(it.optString("tip"), it.optString("ime"), it.optString("naslov")) }
    }

    fun streznikiPeerTube(ctx: Context): List<String> =
        (PeerTube.VGRAJENI + vsi(ctx).filter { it.jePeerTube }.map { it.naslov }).distinct()

    fun odstrani(ctx: Context, vir: Vir) = shrani(ctx, vsi(ctx).filterNot { it == vir })

    /**
     * Preveri naslov in ga doda. Najprej PeerTube (vpisano ime streznika ali naslov strani), nato
     * neposreden tok: odgovor mora biti zvok ali video (ali seznam .m3u/.pls, iz katerega vzamemo
     * prvi naslov). Vrne dodani vir ali null, ce na naslovu ni nicesar, kar bi znali predvajati.
     */
    fun dodaj(ctx: Context, vnos: String): Vir? {
        val cisto = vnos.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val gostitelj = try { URL(cisto).host } catch (_: Exception) { return null }
        val jePot = try { URL(cisto).path.trim('/').isNotEmpty() } catch (_: Exception) { false }
        val vir = PeerTube.imeStreznika(gostitelj)?.takeIf { !jePot || cisto.contains("/videos") || cisto.contains("/c/") || cisto.contains("/a/") }
            ?.let { Vir(PEERTUBE, it, gostitelj) }
            ?: tok(cisto)?.let { (url, video) -> Vir(if (video) "$TOK-video" else TOK, gostitelj, url) }
            ?: if (jeStran(cisto)) Vir(SPLET, gostitelj.removePrefix("www."), cisto) else return null
        shrani(ctx, vsi(ctx).filterNot { it.naslov == vir.naslov } + vir)
        return vir
    }

    fun kotSkladba(v: Vir) = Jamendo.Skladba("vir:" + v.naslov, v.ime, v.naslov.removePrefix("https://").removePrefix("http://"),
        "", v.naslov, v.naslov, radio = !v.tip.endsWith("video"), video = v.tip.endsWith("video"))

    /** Naslov toka in ali je video; seznam .m3u/.pls razpakira v prvi naslov. */
    private fun tok(naslov: String, globina: Int = 0): Pair<String, Boolean>? {
        if (globina > 1) return null
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        p.connectTimeout = 8_000; p.readTimeout = 8_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        p.setRequestProperty("Icy-MetaData", "0")
        return try {
            if (p.responseCode !in 200..299) return null
            val vrsta = (p.contentType ?: "").lowercase()
            when {
                vrsta.contains("mpegurl") || vrsta.contains("scpls") || naslov.endsWith(".m3u") || naslov.endsWith(".pls") -> {
                    val telo = p.inputStream.bufferedReader().use { it.readText().take(20_000) }
                    val prvi = Regex("""(https?://\S+)""").find(telo)?.value?.trim() ?: return null
                    if (prvi.contains(".m3u8")) null else tok(prvi, globina + 1)
                }
                vrsta.startsWith("audio/") || vrsta == "application/ogg" -> naslov to false
                vrsta.startsWith("video/") -> naslov to true
                else -> null
            }
        } catch (_: Exception) { null } finally { p.disconnect() }
    }

    private fun jeStran(naslov: String): Boolean = try {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 8_000; p.readTimeout = 8_000; p.setRequestProperty("User-Agent", "SafeerOS")
        try { p.responseCode in 200..399 && (p.contentType ?: "").lowercase().contains("text/html") } finally { p.disconnect() }
    } catch (_: Exception) { false }

    private fun shrani(ctx: Context, viri: List<Vir>) {
        val a = JSONArray()
        viri.forEach { a.put(JSONObject().put("tip", it.tip).put("ime", it.ime).put("naslov", it.naslov)) }
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, a.toString()).apply()
    }
}
