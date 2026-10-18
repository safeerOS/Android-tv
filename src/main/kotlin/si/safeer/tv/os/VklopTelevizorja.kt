package si.safeer.tv.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Televizor se prizge iz pripravljenosti (tipka za vklop): pokaze naj se Safeer OS, ne zadnja
 * aplikacija, ki je bila odprta - tako kot po ponovnem zagonu ([ZagonPrejemnikOs]).
 *
 * Vklop iz pripravljenosti ni zagon sistema: BOOT_COMPLETED ne pride, Android samo prizge zaslon
 * (ACTION_SCREEN_ON), in ta dogodek dobi le prejemnik, ki ga tekoca aplikacija prijavi sama. Na
 * televizorju tece Safeer Link v brskalniku (HubStoritev, CastReceiverService), zato ga prijavi
 * tisti proces. Brskalnik sme dejavnost zagnati iz ozadja (dovoljenje za prekrivanje), Safeer OS iz
 * sprejemnika ne (Android zagon zavrne), zato Safeer OS odpre brskalnik - a samo, ce Safeer OS pove,
 * da je »Zazeni ob vklopu televizorja« vklopljeno.
 */
object VklopTelevizorja {
    private const val TAG = "SafeerOsZagon"
    @Volatile private var prijavljen = false
    /** Zaslon televizorja je ugasnjen (pripravljenost). Na napravi brez daljinca vedno false. */
    @Volatile private var ugasnjen = false

    /**
     * Sredisce Safeer Linka (HubKrmilnik) ob vklopu in izklopu zaslona drugim napravam takoj javi novo stanje
     * (zmoznost »standby«). Klice se na loceni niti - posiljanje gre po omrezju.
     */
    @Volatile var naSpremembo: (() -> Unit)? = null

    /** Televizor je v pripravljenosti (zaslon ugasnjen): racunalnik ga v Linku ne steje za prizganega. */
    fun vPripravljenosti(): Boolean = ugasnjen

    private fun javiLinku(app: Context, prizgan: Boolean) {
        if (ugasnjen == !prizgan) return
        ugasnjen = !prizgan
        Log.i(TAG, if (prizgan) "Televizor se je prizgal: javljam Safeer Linku." else "Televizor v pripravljenosti: javljam Safeer Linku.")
        naSpremembo?.let { f -> Thread { try { f() } catch (e: Throwable) { Log.w(TAG, "Linku ni bilo mogoce javiti: ${e.message}") } }.start() }
    }

    /** Enkrat na proces; klice se iz storitev, ki na televizorju tecejo ves cas. */
    fun namesti(context: Context) {
        if (prijavljen) return
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature("android.software.leanback")) return
        synchronized(this) {
            if (prijavljen) return
            try {
                // Zacetno stanje: ce se storitev zazene pri ugasnjenem zaslonu (vklop iz pripravljenosti se ni bil).
                ugasnjen = try {
                    !(app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                } catch (_: Throwable) { false }
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        when (i?.action) {
                            Intent.ACTION_SCREEN_ON -> { javiLinku(app, prizgan = true); obVklopu(app) }
                            Intent.ACTION_SCREEN_OFF -> javiLinku(app, prizgan = false)
                        }
                    }
                }, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
                prijavljen = true
            } catch (e: Throwable) {
                Log.w(TAG, "Prejemnika za vklop ni bilo mogoce prijaviti: ${e.message}")
            }
        }
    }

    private fun obVklopu(app: Context) {
        // Klic v Safeer OS gre prek ponudnika; ne na glavni niti, da vklop ne caka nanj.
        Thread {
            try {
                if (Sosed.smoOs(app)) {
                    if (ZagonOb.jeVklopljen(app)) ZagonOb.pokaziDomov(app)
                    return@Thread
                }
                val paket = Sosed.os(app) ?: return@Thread
                val vklopljen = SpletnePonudnik.klic(app, SpletnePonudnik.ZAGON_OB_VKLOPU, "")?.getBoolean("je") ?: false
                if (!vklopljen) return@Thread
                val namera = Intent().setClassName(paket, "si.safeer.tv.os.DomovActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                Handler(Looper.getMainLooper()).post {
                    try {
                        app.startActivity(namera)
                        Log.i(TAG, "Televizor se je prizgal: odpiram Safeer OS.")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Safeer OS se ob vklopu ni odprl: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Vklop televizorja ni bil obdelan: ${e.message}")
            }
        }.start()
    }
}
