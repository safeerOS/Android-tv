package si.safeer.tv.scit

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
 * Stanje in upravljanje Safeer Scita za sorodne aplikacije (Safeer OS), zascita z dovoljenjem istega
 * podpisa. Sporocilo STANJE vrne Bundle (vklopljen, pripravljen, stanje, blokiranih, poizvedb, domen);
 * IZKLOPI izklopi takoj; VKLOPI vklopi, ce je dovoljenje ze dano, sicer vrne `potrebuje_okno` = true in
 * klicatelj odpre ScitActivity (sistemsko okno z dovoljenjem mora odpreti ta aplikacija).
 */
class ScitSorodnikStoritev : Service() {

    private val odzivnik = Messenger(Handler(Looper.getMainLooper()) { sporocilo ->
        val komu = sporocilo.replyTo
        val odgovor = Message.obtain(null, ODGOVOR)
        val b = Bundle()
        try {
            when (sporocilo.what) {
                VKLOPI -> if (!Scit.vklopi(applicationContext)) b.putBoolean("potrebuje_okno", true)
                IZKLOPI -> Scit.izklopi(applicationContext)
            }
            val s = Scit.statistika(applicationContext)
            b.putBoolean("vklopljen", Scit.jeVklopljen(applicationContext))
            b.putBoolean("pripravljen", Scit.jePripravljen(applicationContext))
            b.putBoolean("ima_nabor", Scit.imaNabor(applicationContext))
            b.putString("stanje", s.stanje)
            b.putLong("blokiranih", s.blokiranih); b.putLong("poizvedb", s.poizvedb); b.putInt("domen", s.domen)
        } catch (e: Throwable) { Log.w(TAG, "Stanja ni mogoce pripraviti: ${e.message}") }
        odgovor.data = b
        try { komu?.send(odgovor) } catch (e: Throwable) { Log.w(TAG, "Odgovora ni mogoce poslati: ${e.message}") }
        true
    })

    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == DEJANJE) odzivnik.binder else null

    companion object {
        const val DEJANJE = "si.safeer.tv.SCIT_SORODNIK"
        const val STANJE = 1
        const val VKLOPI = 2
        const val IZKLOPI = 3
        const val ODGOVOR = 10
        private const val TAG = "SafeerScitSorodnik"
    }
}
