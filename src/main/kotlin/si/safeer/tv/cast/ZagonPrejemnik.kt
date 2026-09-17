package si.safeer.tv.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Po vklopu televizorja in po posodobitvi aplikacije znova zazene Safeer Link, ce ga je
 * uporabnik prizgal.
 *
 * Brez tega bi moral uporabnik po vsakem izklopu televizorja (ali po namestitvi nove razlicice,
 * ki proces ustavi) odpreti brskalnik, preden bi telefon spet nasel zaslon. Ce Link ni prizgan,
 * ta prejemnik ne naredi nicesar.
 */
class ZagonPrejemnik : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val akcija = intent?.action ?: return
        if (akcija != Intent.ACTION_BOOT_COMPLETED &&
            akcija != "android.intent.action.QUICKBOOT_POWERON" &&
            akcija != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            akcija != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context?.applicationContext ?: return
        try {
            HubStoritev.zagotovi(app)
        } catch (e: Throwable) {
            Log.w("SafeerHubZagon", "Zagona po vklopu ni bilo mogoce izvesti: ${e.message}")
        }
    }
}
