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

    /**
     * Ali je Safeer OS res domaci zaslon tega televizorja.
     *
     * Zanesljiv je samo dokaz iz prakse: ce nas je sistem kdaj zagnal z namero domacega zaslona
     * (CATEGORY_HOME), potem tipka Domov pride k nam. Razresevanje namere povemo samo kot drugo
     * moznost - Android 11+ aplikaciji pokaze predvsem njo samo, zato bi sama po sebi lahko
     * trdila, da je domaci zaslon, ceprav ga televizor pelje drugam.
     */
    fun jeIzbran(context: Context): Boolean {
        if (nasZagonDomov(context)) return true
        val kdo = domaciZaslon(context)
        return kdo != null && kdo == context.packageName
    }

    /** Sistem nas je zagnal kot domaci zaslon: to si zapomnimo, ker je edini trden dokaz. */
    fun zabeleziZagonDomov(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KLJUC_DOMOV, true).apply()
    }

    private fun nasZagonDomov(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KLJUC_DOMOV, false)

    private const val PREFS = "safeer_os"
    private const val KLJUC_DOMOV = "zaganjalnik_zagon_domov"

    /**
     * Kdo dejansko dobi tipko Domov. Nekateri televizorji (Philips: org.droidtv.homeintentresolver,
     * Google TV: com.google.android.tvlauncher) imajo svoj sistemski prestreznik domacega zaslona
     * in ga tuji aplikaciji ne dajo, tudi ce je vloga HOME nasa. To moramo uporabniku povedati,
     * ne pa pustiti, da ugiba, zakaj se ob tipki Domov odpre televizorjev zaslon.
     */
    fun domaciZaslon(context: Context): String? = try {
        val namera = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(namera, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (e: Throwable) {
        Log.w(TAG, "Domacega zaslona ni bilo mogoce ugotoviti: ${e.message}"); null
    }

    /** Stanje za nastavitve: IZKLOPLJEN, TELEVIZOR_OBDRZI (vklopljeno, a televizor ne da) ali IZBRAN. */
    fun stanje(context: Context): String = when {
        jeIzbran(context) -> IZBRAN
        jePonujen(context) -> TELEVIZOR_OBDRZI
        else -> IZKLOPLJEN
    }

    const val IZKLOPLJEN = "izklopljen"
    const val TELEVIZOR_OBDRZI = "televizor_obdrzi"
    const val IZBRAN = "izbran"

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
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KLJUC_DOMOV).apply()
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

    /**
     * Izhod iz lupine: pokazi domaci zaslon televizorja (Android), ne da bi karkoli spreminjali.
     * Uporabnik, ki hoce navaden Android, ga dobi takoj - tudi kadar je Safeer OS izbran domaci
     * zaslon. Trajno se odloci z izklopom zgoraj. Vrne true, ce je zaganjalnik odprt.
     */
    fun izhodVAndroid(context: Context): Boolean {
        val namera = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val najdeni = try {
            context.packageManager.queryIntentActivities(namera, 0)
        } catch (e: Throwable) {
            Log.w(TAG, "Zaganjalnikov ni bilo mogoce presteti: ${e.message}"); emptyList()
        }
        for (r in najdeni) {
            val info = r.activityInfo ?: continue
            if (info.packageName == context.packageName) continue
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(info.packageName, info.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "Zaganjalnika ${info.packageName} ni bilo mogoce odpreti: ${e.message}")
            }
        }
        return false
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
