package si.safeer.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Safeer Scit (filter DNS za ves televizor) zivi v Safeer Browserju; Safeer OS ga kaze in preklaplja
 * prek storitve `si.safeer.tv.SCIT_SORODNIK` (dovoljenje istega podpisa). Sistemsko okno z
 * dovoljenjem za VPN sme odpreti samo brskalnik, zato vklop, ki ga se ni potrdil uporabnik,
 * odpre brskalnikovo dejavnost ScitActivity.
 */
object Scit {
    private const val DEJANJE = "si.safeer.tv.SCIT_SORODNIK"
    private const val STANJE = 1
    private const val VKLOPI = 2
    private const val IZKLOPI = 3
    private const val ODGOVOR = 10
    private const val TAG = "SafeerOsScit"

    class Stanje(val naVoljo: Boolean, val vklopljen: Boolean, val pripravljen: Boolean, val tece: Boolean,
                 val blokiranih: Long, val domen: Int, val potrebujeOkno: Boolean)

    fun stanje(context: Context, naprej: (Stanje) -> Unit) = poslji(context, STANJE, naprej)
    fun vklopi(context: Context, naprej: (Stanje) -> Unit) = poslji(context, VKLOPI, naprej)
    fun izklopi(context: Context, naprej: (Stanje) -> Unit) = poslji(context, IZKLOPI, naprej)

    /** Brskalnikovo okno za vklop (sistemsko dovoljenje); po vrnitvi klicatelj znova prebere stanje. */
    fun odpriVklop(context: Context) {
        val namera = Intent().setComponent(ComponentName(Sorodnik.PAKET_BRSKALNIKA, "si.safeer.tv.scit.ScitActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Okna za vklop ni mogoce odpreti: ${e.message}") }
    }

    private fun poslji(context: Context, kaj: Int, naprej: (Stanje) -> Unit) {
        val app = context.applicationContext
        val ni = Stanje(false, false, false, false, 0, 0, false)
        if (!Sorodnik.jeBrskalnikNamescen(app)) { naprej(ni); return }
        val glavna = Handler(Looper.getMainLooper())
        var koncano = false
        var vez: ServiceConnection? = null
        fun koncaj(s: Stanje) {
            if (koncano) return
            koncano = true
            try { vez?.let { app.unbindService(it) } } catch (_: Throwable) { }
            naprej(s)
        }
        val odzivnik = Messenger(Handler(Looper.getMainLooper()) { m ->
            if (m.what == ODGOVOR) {
                val b: Bundle = m.data
                koncaj(Stanje(true, b.getBoolean("vklopljen"), b.getBoolean("pripravljen"), b.getString("stanje") == "tece",
                    b.getLong("blokiranih"), b.getInt("domen"), b.getBoolean("potrebuje_okno")))
            }
            true
        })
        vez = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, kaj); m.replyTo = odzivnik
                    Messenger(binder).send(m)
                } catch (e: Throwable) { Log.w(TAG, "Zahteve ni mogoce poslati: ${e.message}"); koncaj(ni) }
            }
            override fun onServiceDisconnected(name: ComponentName?) { }
            override fun onBindingDied(name: ComponentName?) { koncaj(ni) }
            override fun onNullBinding(name: ComponentName?) { koncaj(ni) }  // star brskalnik brez Scita
        }
        val ok = try { app.bindService(Intent(DEJANJE).setPackage(Sorodnik.PAKET_BRSKALNIKA), vez, Context.BIND_AUTO_CREATE) } catch (_: Throwable) { false }
        if (!ok) { koncaj(ni); return }
        glavna.postDelayed({ if (!koncano) koncaj(ni) }, 6000)
    }
}
