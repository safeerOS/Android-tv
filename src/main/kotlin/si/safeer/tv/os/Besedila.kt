package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Naloga 89 (zamisel iz BitChord): besedila pesmi iz LRCLIB (lrclib.net, odprt vmesnik brez kljuca), sinhronizirana s
 * posnetkom. Zahteva gre SAMO, ce je uporabnik LRCLIB dodal kot vir ([MedijskiViri.imaBesedila]) - naslov in izvajalec
 * gresta tujemu strezniku; brez vira Safeer ne vprasa nikogar.
 *
 * Najprej /api/get (izvajalec, naslov, dolzina), ce ga ni, /api/search (prvi z besedilom). Zapis (tudi »ni besedila«)
 * gre v predpomnilnik ([MedijskiPredpomnilnik.besedilo]); »ni besedila« velja [BesedilaPravila.NI_BESEDILA_MS].
 * Klic iz ozadja.
 */
object Besedila {
    private const val OSNOVA = "https://lrclib.net/api"
    private const val ROK_MS = 6_000
    private const val NAJVEC_BAJTOV = 512 * 1024

    /** Besedilo pesmi: [vrstice] sinhronizirano (lahko prazno), [navadno] brez casov, [instrumentalno] = pesem brez besedila. */
    class Besedilo(val vrstice: List<BesedilaPravila.Vrstica>, val navadno: String, val instrumentalno: Boolean)

    /** Izid iskanja: besedilo, ali null in razlog ("ni" = LRCLIB ga nima, "napaka" = ni odgovora, "ni_vira" = vir ni dodan). */
    class Izid(val besedilo: Besedilo?, val razlog: String)

    fun poisci(c: Context, naslov: String, izvajalec: String, trajanjeMs: Long): Izid {
        val app = c.applicationContext
        if (!MedijskiViri.imaBesedila(app)) return Izid(null, "ni_vira")
        val (i, n) = BesedilaPravila.iskalniPar(naslov, izvajalec)
        if (n.isBlank()) return Izid(null, "ni")
        val sekund = (trajanjeMs / 1000).coerceAtLeast(0)
        val kljuc = BesedilaPravila.kljuc(i, n, sekund)
        val zdaj = System.currentTimeMillis()
        MedijskiPredpomnilnik.besedilo(app, kljuc)?.let { (cas, zapis) ->
            val o = try { JSONObject(zapis) } catch (_: Exception) { null }
            if (o != null) {
                if (!o.optBoolean("ni")) return Izid(izZapisa(o), "")
                if (BesedilaPravila.niBesedilaVelja(cas, zdaj)) return Izid(null, "ni")
            }
        }
        val od = android.os.SystemClock.uptimeMillis()
        // Najprej natancno (z dolzino), nato iskanje - drug posnetek iste pesmi ima lahko drugo dolzino.
        val zadetek = beri("$OSNOVA/get?" + BesedilaPravila.poizvedba(i, n, sekund))?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            ?: beri("$OSNOVA/search?" + BesedilaPravila.poizvedba(i, n, 0))?.let { t ->
                val a = try { JSONArray(t) } catch (_: Exception) { null } ?: return@let null
                (0 until minOf(a.length(), 10)).mapNotNull { a.optJSONObject(it) }
                    .firstOrNull { it.optString("syncedLyrics").isNotBlank() || it.optString("plainLyrics").isNotBlank() || it.optBoolean("instrumental") }
            }
        val koda = zadnjaKoda.get() ?: 0
        if (zadetek == null) {
            android.util.Log.i("SafeerOsMedia", "besedilo: ni najdeno (koda=$koda, ${android.os.SystemClock.uptimeMillis() - od} ms)")
            // »Ni besedila« si zapomnimo samo, ce je streznik odgovoril (404 ali prazno) - izpad omrezja ni odgovor.
            if (koda == 404 || koda in 200..299) { MedijskiPredpomnilnik.shraniBesedilo(app, kljuc, JSONObject().put("ni", true).toString()); return Izid(null, "ni") }
            return Izid(null, "napaka")
        }
        val zapis = JSONObject().put("sync", zadetek.optString("syncedLyrics")).put("plain", zadetek.optString("plainLyrics"))
            .put("instr", zadetek.optBoolean("instrumental"))
        MedijskiPredpomnilnik.shraniBesedilo(app, kljuc, zapis.toString())
        val b = izZapisa(zapis)
        android.util.Log.i("SafeerOsMedia", "besedilo: najdeno (sinhronizirano=${b.vrstice.isNotEmpty()}, ${android.os.SystemClock.uptimeMillis() - od} ms)")
        return Izid(b, "")
    }

    private fun izZapisa(o: JSONObject) = Besedilo(BesedilaPravila.razcleni(o.optString("sync")), o.optString("plain"), o.optBoolean("instr"))

    private val zadnjaKoda = ThreadLocal<Int>()

    /** GET z rokom; 200 -> telo (najvec [NAJVEC_BAJTOV]), sicer null (koda v [zadnjaKoda]). Brez preusmeritev drugam. */
    private fun beri(naslov: String): String? {
        zadnjaKoda.set(0)
        return try {
            val p = URL(naslov).openConnection() as HttpURLConnection
            p.connectTimeout = ROK_MS; p.readTimeout = ROK_MS
            p.instanceFollowRedirects = false
            // LRCLIB prosi za prepoznavno ime odjemalca (brez podatkov o napravi ali uporabniku).
            p.setRequestProperty("User-Agent", "Safeer OS (https://safeer.si)")
            p.setRequestProperty("Accept", "application/json")
            try {
                val koda = p.responseCode
                zadnjaKoda.set(koda)
                if (koda != 200) null else p.inputStream.use { vhod ->
                    val buf = java.io.ByteArrayOutputStream()
                    val kos = ByteArray(16 * 1024)
                    while (true) {
                        val n = vhod.read(kos)
                        if (n < 0) break
                        buf.write(kos, 0, n)
                        if (buf.size() > NAJVEC_BAJTOV) return null
                    }
                    buf.toString("UTF-8")
                }
            } finally { p.disconnect() }
        } catch (_: Exception) { null }
    }
}
