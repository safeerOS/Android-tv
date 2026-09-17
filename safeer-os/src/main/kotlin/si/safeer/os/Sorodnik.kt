package si.safeer.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Vstop v Safeer Link brez kode: Safeer Browser na istem televizorju gosti sredisce in Safeer OS
 * (podpisan z istim kljucem) od njega dobi svoj zeton. Uporabnik ne vidi nicesar - kot Safeer
 * Control ob seznanjenem brskalniku na racunalniku.
 *
 * Pogovor: vezava na storitev `si.safeer.tv.LINK_SORODNIK` (dovoljenje istega podpisa), sporocilo
 * ZAHTEVA z replyTo; odgovor nosi hub_url, token, fp in hub_id.
 */
object Sorodnik {
    const val PAKET_BRSKALNIKA = "si.safeer.tv"
    private const val DEJANJE = "si.safeer.tv.LINK_SORODNIK"
    private const val ZAHTEVA = 1
    private const val ODGOVOR = 2
    private const val TAG = "SafeerOsSorodnik"

    data class Poverilnice(val hubUrl: String, val zeton: String, val odtis: String, val hubId: String)

    fun jeBrskalnikNamescen(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PAKET_BRSKALNIKA, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Vrne poverilnice ali null (brskalnik ni namescen, je prestar ali ni odgovoril v 8 s). */
    fun zahtevaj(context: Context, naprej: (Poverilnice?) -> Unit) {
        if (!jeBrskalnikNamescen(context)) { naprej(null); return }
        val app = context.applicationContext
        val glavna = Handler(Looper.getMainLooper())
        var koncano = false
        var vez: ServiceConnection? = null
        fun koncaj(p: Poverilnice?) {
            if (koncano) return
            koncano = true
            try { vez?.let { app.unbindService(it) } } catch (_: Throwable) { }
            naprej(p)
        }
        val odzivnik = Messenger(Handler(Looper.getMainLooper()) { sporocilo ->
            if (sporocilo.what == ODGOVOR) {
                val b: Bundle = sporocilo.data
                val hub = b.getString("hub_url").orEmpty()
                val zeton = b.getString("token").orEmpty()
                val odtis = b.getString("fp").orEmpty()
                if (hub.startsWith("wss://") && zeton.isNotBlank() && odtis.isNotBlank()) {
                    koncaj(Poverilnice(hub, zeton, odtis, b.getString("hub_id").orEmpty()))
                } else {
                    Log.w(TAG, "Odgovor brskalnika je nepopoln.")
                    koncaj(null)
                }
            }
            true
        })
        vez = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, ZAHTEVA)
                    m.replyTo = odzivnik
                    m.data = Bundle().apply {
                        putString("device_name", "Safeer OS")
                        putString("app", app.packageName)
                    }
                    Messenger(binder).send(m)
                } catch (e: Throwable) {
                    Log.w(TAG, "Zahteve ni bilo mogoce poslati: ${e.message}")
                    koncaj(null)
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { }
            override fun onBindingDied(name: ComponentName?) { koncaj(null) }
            override fun onNullBinding(name: ComponentName?) { koncaj(null) }
        }
        val namera = Intent(DEJANJE).setPackage(PAKET_BRSKALNIKA)
        val uspelo = try { app.bindService(namera, vez, Context.BIND_AUTO_CREATE) } catch (e: Throwable) {
            Log.w(TAG, "Vezava ni uspela: ${e.message}"); false
        }
        if (!uspelo) { koncaj(null); return }
        glavna.postDelayed({ if (!koncano) { Log.w(TAG, "Brskalnik ni odgovoril."); koncaj(null) } }, 8000)
    }
}
