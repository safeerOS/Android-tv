package si.safeer.tv.os

import android.content.Context
import java.util.concurrent.Executors

/**
 * Predgretje ob zagonu Safeer OS: nastavitvene datoteke, ki jih Medijski center in stranska vrstica
 * berejo ob odprtju, se naložijo v ozadju že na domačem zaslonu. Prej je prvi dostop do vsake
 * (SharedPreferences: branje in razčlenitev XML) na televizorju trajal 240-310 ms NA GLAVNI NITI
 * (StrictMode), skupaj skoraj sekundo pred prvim izrisom Medijskega centra.
 */
object Predgretje {
    private val DATOTEKE = listOf("safeer_os_vrstica", "safeer_mediji", "safeer_media_pogled", "safeer_os", "tv_v_zivo_ikone")
    @Volatile private var zagnano = false

    fun zazeni(c: Context) {
        if (zagnano) return
        zagnano = true
        val app = c.applicationContext
        Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-os-predgretje").also { it.isDaemon = true } }.execute {
            for (ime in DATOTEKE) try { app.getSharedPreferences(ime, Context.MODE_PRIVATE).all } catch (_: Throwable) {}
            // Podlaga zaslona se sestavi enkrat in je nato pripravljena za vse zaslone.
            try { Ozadje.sestavi(app, Ozadje.izbrana(app), Ozadje.zatemnitev(app)) } catch (_: Throwable) {}
        }
    }
}
