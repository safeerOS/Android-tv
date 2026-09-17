package si.safeer.tv.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Ali Safeer OS prevzame domaci zaslon televizorja.
 *
 * Uporabnik ima dve poti in izbere sam:
 *  - **aplikacija** (privzeto): Safeer OS je navadna aplikacija v zaganjalniku televizorja;
 *    tipka Domov dela kot prej, Safeer OS pa odpres, kadar ga hoces - spletne aplikacije,
 *    datoteke, Link in Scit so v njem.
 *  - **domaci zaslon**: Safeer OS prevzame tipko Domov in je prvo, kar televizor pokaze.
 *
 * Tehnicno je to `activity-alias` s kategorijo HOME, ki je v manifestu **onemogocen**. Dokler ga
 * uporabnik ne vklopi, Android sploh ne ve, da bi Safeer OS lahko bil zaganjalnik, zato ga tudi
 * ne ponuja in nicesar ne vprasa. Ob izklopu ga spet onemogocimo in televizor se vrne na svoj
 * zaganjalnik - pot nazaj je zato vedno tu, tudi ce sistemskih nastavitev ni mogoce odpreti.
 */
object Zaganjalnik {
    private const val TAG = "SafeerOsZaganjalnik"
    private const val ALIAS = "si.safeer.tv.os.ZaganjalnikAlias"

    private fun komponenta(context: Context) = ComponentName(context.packageName, ALIAS)

    /** Ali je Safeer OS ponujen kot domaci zaslon (alias omogocen). */
    fun jePonujen(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(komponenta(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Throwable) {
        Log.w(TAG, "Stanja ni bilo mogoce prebrati: ${e.message}"); false
    }

    /** Ali je Safeer OS res izbran domaci zaslon tega televizorja. */
    fun jeIzbran(context: Context): Boolean = try {
        val namera = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = context.packageManager.resolveActivity(namera, PackageManager.MATCH_DEFAULT_ONLY)
        r?.activityInfo?.packageName == context.packageName
    } catch (e: Throwable) {
        Log.w(TAG, "Domacega zaslona ni bilo mogoce ugotoviti: ${e.message}"); false
    }

    /**
     * Ponudi Safeer OS kot domaci zaslon. Izbira ostane sistemska: mi samo omogocimo moznost,
     * uporabnik pa jo potrdi v sistemskem oknu. Vrne true, ce je bilo okno odprto.
     */
    fun ponudi(context: Context): Boolean {
        nastavi(context, true)
        return odpriSistemskoIzbiro(context)
    }

    /** Vrni domaci zaslon televizorju: alias onemogocimo in sistem vzame svoj zaganjalnik. */
    fun opusti(context: Context) {
        nastavi(context, false)
    }

    private fun nastavi(context: Context, omogocen: Boolean) {
        val stanje = if (omogocen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(komponenta(context), stanje, PackageManager.DONT_KILL_APP)
            Log.i(TAG, "Safeer OS kot domaci zaslon: ${if (omogocen) "ponujen" else "opuscen"}")
        } catch (e: Throwable) {
            Log.w(TAG, "Nastavitve ni bilo mogoce spremeniti: ${e.message}")
        }
    }

    /** Sistemsko okno za izbiro domacega zaslona; nekateri televizorji ga nimajo. */
    fun odpriSistemskoIzbiro(context: Context): Boolean {
        for (dejanje in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(dejanje).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Throwable) { }
        }
        Log.w(TAG, "Sistemskih nastavitev za domaci zaslon ni mogoce odpreti")
        return false
    }
}
