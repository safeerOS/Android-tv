package si.safeer.tv.cast

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Sorodne aplikacije na istem televizorju (Safeer OS) vstopijo v Safeer Link brez kode.
 *
 * Storitev je zascitena z dovoljenjem istega podpisa (si.safeer.tv.permission.LINK): odgovor dobi
 * samo aplikacija, podpisana z istim kljucem kot ta brskalnik. Vrne naslov sredisca na tem
 * televizorju, zeton sorodnika (`<id sredisca>-<pripona>`) in odtis potrdila - enako, kot to za
 * Safeer Control naredi koncna tocka /cast/pair/sibling, le v procesu in brez omrezja.
 * Ce Safeer Link ni prizgan, ga prizge: sorodna aplikacija ga potrebuje, uporabnik pa je to zelel,
 * ko jo je odprl.
 */
class LinkSorodnikStoritev : Service() {

    private val odzivnik = Messenger(Handler(Looper.getMainLooper()) { sporocilo ->
        if (sporocilo.what == ZAHTEVA) {
            val komu = sporocilo.replyTo
            val podatki = sporocilo.data ?: Bundle()
            val odgovor = Message.obtain(null, ODGOVOR)
            odgovor.data = pripraviOdgovor(podatki.getString("device_name") ?: "Safeer OS", podatki.getString("app") ?: "")
            try { komu?.send(odgovor) } catch (e: Throwable) { Log.w(TAG, "Odgovora ni bilo mogoce poslati: ${e.message}") }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == DEJANJE) odzivnik.binder else null

    private fun pripraviOdgovor(imeNaprave: String, paket: String): Bundle {
        val app = applicationContext
        val b = Bundle()
        try {
            if (!HubKrmilnik.tece()) {
                HubKrmilnik.zazeni(app, zapomni = true)
                HubStoritev.zagotovi(app)
            }
            val u = HubKrmilnik.usmerjevalnik
            val vrata = HubKrmilnik.vrata()
            if (u == null || vrata == 0) {
                Log.w(TAG, "Sredisce ne tece; sorodnik ($paket) ostane brez zetona.")
                return b
            }
            val pripona = if (paket.endsWith(".os")) "os" else paket.substringAfterLast('.').ifBlank { "app" }
            val id = HubKrmilnik.lastniId() + "-" + pripona
            val ime = "$imeNaprave (" + android.os.Build.MODEL + ")"
            val zeton = u.zagotoviLastniZeton(id, ime)
            b.putString("hub_url", "wss://127.0.0.1:$vrata/cast/ws")
            b.putString("token", zeton)
            b.putString("fp", HubTls.lastniOdtis())
            b.putString("hub_id", HubUsmerjevalnik.IDENTITETA_HUBA)
            b.putString("device_id", id)
            Log.i(TAG, "Sorodna aplikacija $paket je dobila zeton ($id).")
        } catch (e: Throwable) {
            Log.w(TAG, "Zetona za sorodnika ni bilo mogoce pripraviti: ${e.message}")
        }
        return b
    }

    companion object {
        const val DEJANJE = "si.safeer.tv.LINK_SORODNIK"
        const val ZAHTEVA = 1
        const val ODGOVOR = 2
        private const val TAG = "SafeerLinkSorodnik"
    }
}
