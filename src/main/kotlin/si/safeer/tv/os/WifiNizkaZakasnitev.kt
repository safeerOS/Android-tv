package si.safeer.tv.os

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Wi-Fi brez varcevanja, dokler uporabnik gleda in upravlja zaslon druge naprave.
 *
 * Telefon radio Wi-Fi uspava za desetine milisekund, kadar je prometa malo (mirna slika je nekaj sto kb/s). Izmerjeno
 * 6. 10. 2026 na telefonu z Wi-Fi 6 in odlicnim signalom (odziv na 20 ms med sejo): mediana 9,4 ms, petina odgovorov
 * nad 30 ms, najvec 65 ms - slike so prihajale v sunkih, s presledki nad 50 ms v petini sekund. Drug telefon na istem
 * usmerjevalniku: mediana 3,4 ms, najvec 9 ms. Zaklep WIFI_MODE_FULL_LOW_LATENCY varcevanje izklopi; sistem ga
 * uposteva samo, dokler je aplikacija v ospredju in zaslon prizgan, zato baterije v ozadju ne trosi.
 */
class WifiNizkaZakasnitev(context: Context, private val oznaka: String) {
    private val app = context.applicationContext
    private var zaklep: WifiManager.WifiLock? = null

    /** Vklopi (ce se ni). Napaka - naprava brez Wi-Fi, zavrnjen zaklep - ne sme motiti seje. */
    fun vklopi() {
        if (zaklep?.isHeld == true) return
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val nacin = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            zaklep = wm.createWifiLock(nacin, oznaka).apply { setReferenceCounted(false); acquire() }
            Log.i(TAG, "Wi-Fi brez varcevanja: vklopljeno ($oznaka)")
        } catch (e: Throwable) {
            Log.i(TAG, "Wi-Fi brez varcevanja ni na voljo: ${e.javaClass.simpleName}")
        }
    }

    fun izklopi() {
        try { zaklep?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        zaklep = null
    }

    private companion object { const val TAG = "SafeerWifi" }
}
