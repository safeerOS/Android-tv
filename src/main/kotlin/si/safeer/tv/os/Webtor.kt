package si.safeer.tv.os

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

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

    /**
     * Prvi bajt toka [url] (Range 0-0) v najvec [WebtorPravila.PRVI_BAJT_MS]. Preusmeritvam sledimo sami (najvec 5), da
     * gostitelja toka dodamo med izjeme Scita ([si.safeer.tv.scit.Scit.dovoliGostitelja]); ce je bil dodan na novo in
     * ime se ne razresi, poskusimo enkrat znova. Blokira - z delovne niti; preklic (prekinitev niti) jo konca.
     */
    fun sonda(c: Context, url: String): Sonda {
        val zacetek = SystemClock.elapsedRealtime()
        fun ostane() = (WebtorPravila.PRVI_BAJT_MS - (SystemClock.elapsedRealtime() - zacetek)).toInt()
        var naslov = SkrivniDodatki.dejanski(c, url)
        var gostitelj = Zasebno.gostitelj(naslov)
        var preusmeritev = 0
        var ponovljeno = false
        var novaIzjema = false
        while (true) {
            if (Thread.currentThread().isInterrupted) return Sonda(0, null, false, gostitelj)
            val rok = ostane()
            if (rok <= 0) return Sonda(0, null, true, gostitelj)
            if (!naslov.startsWith("https://")) return Sonda(0, null, false, gostitelj)
            val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return Sonda(0, null, false, gostitelj) }
            try {
                p.instanceFollowRedirects = false
                p.connectTimeout = rok; p.readTimeout = rok
                p.setRequestProperty("Range", "bytes=0-0")
                p.setRequestProperty("Accept-Encoding", "identity")
                val koda = p.responseCode
                if (koda in 300..399) {
                    val kam = p.getHeaderField("Location") ?: return Sonda(koda, null, false, gostitelj)
                    if (++preusmeritev > 5) return Sonda(koda, null, false, gostitelj)
                    naslov = URL(URL(naslov), kam).toString()
                    gostitelj = Zasebno.gostitelj(naslov)
                    novaIzjema = try { si.safeer.tv.scit.Scit.dovoliGostitelja(c, gostitelj) } catch (_: Throwable) { false }
                    continue
                }
                if (koda !in 200..299) return Sonda(koda, null, false, gostitelj)
                val b = p.inputStream.use { it.read() }
                val ms = SystemClock.elapsedRealtime() - zacetek
                return Sonda(koda, if (b >= 0) ms else null, false, gostitelj)
            } catch (_: java.net.SocketTimeoutException) {
                return Sonda(0, null, true, gostitelj)
            } catch (_: java.net.UnknownHostException) {
                // Nova izjema Scita: proces Scita jo prebere v trenutku - en ponoven poskus.
                if (novaIzjema && !ponovljeno && ostane() > 1_000) { ponovljeno = true; try { Thread.sleep(700) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }; continue }
                return Sonda(0, null, false, gostitelj)
            } catch (e: Exception) {
                Log.w(TAG, "sonda: ${e.javaClass.simpleName}")
                return Sonda(0, null, false, gostitelj)
            } finally { p.disconnect() }
        }
    }

    /** Niti za TorBox v ozadju: ne prekine jih nova izbira toka (namerno predpomnjenje za naslednjic). */
    private val ozadje = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-webtor-torbox").apply { isDaemon = true } }

    /** Torrent [magnet] v TorBox (createtorrent, brez cakanja) - po [WebtorPravila.dodajVTorBox]. */
    fun vTorBoxVOzadju(c: Context, magnet: String) {
        val app = c.applicationContext
        try { ozadje.execute { try { TorBox.dodajZaPozneje(app, magnet) } catch (e: Exception) { Log.w(TAG, "TorBox v ozadju: ${e.javaClass.simpleName}") } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
