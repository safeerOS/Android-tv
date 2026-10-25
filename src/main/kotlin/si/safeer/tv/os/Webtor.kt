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
 * zahtevo in se nikoli ne zapise. Koncnega naslova po preusmeritvi ne hranimo in ne posiljamo drugim napravam (vezan je na
 * IP te naprave) - predvajalnik dobi `/resolve/` in preusmeritvi sledi sam.
 */
object Webtor {
    private const val TAG = "SafeerWebtor"

    /** Izid sonde: [koda] HTTP (0 = ni odgovora), [prviBajtMs] (null = ga ni bilo), [potekel] rok, [gostitelj] toka. */
    class Sonda(val koda: Int, val prviBajtMs: Long?, val potekel: Boolean, val gostitelj: String)

    /** Pregled N3: straza prekine zahtevo ob izteku skupnega roka (connect + read sta sicer vsak svoj rok). */
    private val straza = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-webtor-straza").apply { isDaemon = true } }

    /**
     * Prvi bajt toka [url] (Range 0-0) v najvec [rokMs] skupaj. Preusmeritvam sledimo sami (najvec 5), samo po https.
     * Pregled V1: gostitelja toka Scit dobi med izjeme samo, ce se je zahteva zacela na `https://webtor.io/.../resolve/`, in
     * samo KONCNEGA gostitelja po odgovoru 2xx ([WebtorPravila.naucenGostitelj], [si.safeer.tv.scit.Scit.dovoliGostitelja]).
     * Blokira - z delovne niti; preklic (prekinitev niti) jo konca.
     */
    fun sonda(c: Context, url: String, rokMs: Long = WebtorPravila.PRVI_BAJT_MS): Sonda {
        val zacetek = SystemClock.elapsedRealtime()
        fun ostane() = rokMs - (SystemClock.elapsedRealtime() - zacetek)
        val zacetni = SkrivniDodatki.dejanski(c, url)
        var naslov = zacetni
        var gostitelj = Zasebno.gostitelj(naslov)
        var preusmeritev = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) return Sonda(0, null, false, gostitelj)
            val rok = ostane()
            if (rok <= 0) return Sonda(0, null, true, gostitelj)
            if (!naslov.startsWith("https://")) return Sonda(0, null, false, gostitelj)
            val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return Sonda(0, null, false, gostitelj) }
            val prekini = try { straza.schedule({ try { p.disconnect() } catch (_: Throwable) { } }, rok, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            try {
                p.instanceFollowRedirects = false
                p.connectTimeout = rok.toInt().coerceAtLeast(1); p.readTimeout = rok.toInt().coerceAtLeast(1)
                p.setRequestProperty("Range", "bytes=0-0")
                p.setRequestProperty("Accept-Encoding", "identity")
                val koda = p.responseCode
                if (koda in 300..399) {
                    val kam = p.getHeaderField("Location") ?: return Sonda(koda, null, false, gostitelj)
                    if (++preusmeritev > 5) return Sonda(koda, null, false, gostitelj)
                    naslov = URL(URL(naslov), kam).toString()
                    gostitelj = Zasebno.gostitelj(naslov)
                    continue
                }
                if (koda !in 200..299) return Sonda(koda, null, false, gostitelj)
                val b = p.inputStream.use { it.read() }
                val ms = SystemClock.elapsedRealtime() - zacetek
                if (b >= 0) WebtorPravila.naucenGostitelj(zacetni, naslov, koda)?.let { g ->
                    try { si.safeer.tv.scit.Scit.dovoliGostitelja(c, g) } catch (_: Throwable) { }
                }
                return Sonda(koda, if (b >= 0) ms else null, false, gostitelj)
            } catch (_: java.net.SocketTimeoutException) {
                return Sonda(0, null, true, gostitelj)
            } catch (e: Exception) {
                // Straza je prekinila zahtevo (iztek skupnega roka) ali napaka omrezja.
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
