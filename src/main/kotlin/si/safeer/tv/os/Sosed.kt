package si.safeer.tv.os

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
 * Safeer Browser TV in Safeer OS sta **loceni aplikaciji** (si.safeer.tv in si.safeer.os), zgrajeni
 * iz iste kode. Na televizorju sta lahko obe ali samo ena, zato vsak del, ki ga ima lahko soseda,
 * vpraša tukaj:
 *
 *  - Safeer OS z namescenim brskalnikom: Safeer Link, Scit in splet gredo prek brskalnika -
 *    ena povezava, en filter, en pogon. Nova povezava v Link ni potrebna.
 *  - Safeer OS brez brskalnika: vse dela sam (koda je v obeh aplikacijah).
 *  - Brskalnik z namescenim Safeer OS: spletna aplikacija, dodana iz menija, gre na domaci zaslon
 *    Safeer OS, ne v svojo shrambo.
 *
 * Pogovor med aplikacijama je vezava na storitev Messenger, zascitena z dovoljenjem istega podpisa
 * (si.safeer.tv.permission.LINK): odgovori samo aplikacija, podpisana z istim kljucem.
 */
object Sosed {
    const val BRSKALNIK = "si.safeer.tv"
    const val OS = "si.safeer.os"
    private const val TAG = "SafeerSosed"

    fun namescen(context: Context, paket: String): Boolean = try {
        context.packageManager.getPackageInfo(paket, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false
    } catch (e: Throwable) { Log.w(TAG, "Paketa $paket ni bilo mogoce preveriti: ${e.message}"); false }

    /** Paket brskalnika, kadar je to **druga**, namescena aplikacija; sicer null (smo brskalnik ali ga ni). */
    fun brskalnik(context: Context): String? {
        if (context.packageName == BRSKALNIK) return null
        return if (namescen(context, BRSKALNIK)) BRSKALNIK else null
    }

    /** Ali smo Safeer OS (aplikacija z domacim zaslonom televizorja). */
    fun smoOs(context: Context): Boolean = context.packageName == OS

    /** Paket Safeer OS, kadar je to **druga**, namescena aplikacija; sicer null. */
    fun os(context: Context): String? {
        if (context.packageName == OS) return null
        return if (namescen(context, OS)) OS else null
    }

    /**
     * Poslji sporocilo sosedovi storitvi Messenger in pockaj na odgovor. [naprej] dobi podatke
     * odgovora ali null (sosede ni, ne odgovori, je prestara). Vedno se poklice natanko enkrat,
     * na glavni niti.
     */
    fun poslji(
        context: Context, paket: String, dejanje: String, kaj: Int, odgovorKaj: Int,
        podatki: Bundle? = null, potekMs: Long = 8000, naprej: (Bundle?) -> Unit,
    ) {
        val app = context.applicationContext
        val glavna = Handler(Looper.getMainLooper())
        var koncano = false
        var vez: ServiceConnection? = null
        fun koncaj(b: Bundle?) {
            if (koncano) return
            koncano = true
            try { vez?.let { app.unbindService(it) } } catch (_: Throwable) { }
            naprej(b)
        }
        val odzivnik = Messenger(Handler(Looper.getMainLooper()) { m ->
            if (m.what == odgovorKaj) koncaj(m.data)
            true
        })
        vez = object : ServiceConnection {
            override fun onServiceConnected(ime: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, kaj)
                    m.replyTo = odzivnik
                    if (podatki != null) m.data = podatki
                    Messenger(binder).send(m)
                } catch (e: Throwable) {
                    Log.w(TAG, "Zahteve ($dejanje) ni bilo mogoce poslati: ${e.message}"); koncaj(null)
                }
            }
            override fun onServiceDisconnected(ime: ComponentName?) { }
            override fun onBindingDied(ime: ComponentName?) { koncaj(null) }
            override fun onNullBinding(ime: ComponentName?) { koncaj(null) }
        }
        val uspelo = try {
            app.bindService(Intent(dejanje).setPackage(paket), vez, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) { Log.w(TAG, "Vezava na $paket ni uspela: ${e.message}"); false }
        if (!uspelo) { koncaj(null); return }
        glavna.postDelayed({ if (!koncano) { Log.w(TAG, "$paket ni odgovoril ($dejanje)."); koncaj(null) } }, potekMs)
    }
}
