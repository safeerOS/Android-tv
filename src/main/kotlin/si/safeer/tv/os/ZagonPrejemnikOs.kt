package si.safeer.tv.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Po vklopu televizorja (in po posodobitvi aplikacije) odpre Safeer OS - ce je uporabnik to
 * izbral v nastavitvah. Privzeto ne naredi nicesar.
 */
class ZagonPrejemnikOs : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val akcija = intent?.action ?: return
        if (akcija != Intent.ACTION_BOOT_COMPLETED &&
            akcija != "android.intent.action.QUICKBOOT_POWERON" &&
            akcija != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            akcija != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context?.applicationContext ?: return
        if (!ZagonOb.jeVklopljen(app)) return
        Log.i("SafeerOsZagon", "Televizor se je prizgal ($akcija): odpiram Safeer OS.")
        try { ZagonOb.pokaziDomov(app) } catch (e: Throwable) {
            Log.w("SafeerOsZagon", "Safeer OS se ni odprl: ${e.message}")
        }
    }
}
