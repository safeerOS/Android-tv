package si.safeer.tv.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Po vklopu televizorja odpre Safeer OS - ce je uporabnik to izbral v nastavitvah. Po posodobitvi aplikacije samo
 * uskladi Safeer Vnos, zaslona ne vzame aplikaciji v ospredju. Privzeto ne naredi nicesar.
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
        // Tipka Viri na daljincu (lastnik 21. 11. 2026): alias zaslona virov se uskladi tudi ob vklopu in posodobitvi, ne samo ob
        // odprtju domaceg zaslona - Googlov zaganjalnik se lahko izklopi, ko Safeer OS ze tece (alias je ostal izklopljen).
        ViriActivity.uskladi(app)
        // Po posodobitvi in vklopu: Safeer Vnos sledi ozjemu krogu (link/VnosVklop), tudi ce se Safeer OS ne odpre. Samo
        // ob zascitenih sistemskih oddajah - QUICKBOOT_POWERON lahko poslje katerakoli aplikacija (pregled tv-vnos, 4).
        if (si.safeer.tv.link.VnosVklopPravila.uskladiObOddaji(akcija)) si.safeer.tv.link.VnosVklop.uskladi(app, akcija)
        if (!ZaganjalnikPravila.odpriDomObOddaji(akcija, android.os.SystemClock.elapsedRealtime()) || !ZagonOb.jeVklopljen(app)) return
        Log.i("SafeerOsZagon", "Televizor se je prizgal ($akcija): odpiram Safeer OS.")
        try { ZagonOb.pokaziDomov(app) } catch (e: Throwable) {
            Log.w("SafeerOsZagon", "Safeer OS se ni odprl: ${e.message}")
        }
    }
}
