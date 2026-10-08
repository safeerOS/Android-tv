package si.safeer.tv.os

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Vrsta naprave za postavitve in upravljanje: televizor (daljinec) ali dotik. */
object Naprava {
    /**
     * Televizor: upravljanje z daljincem, brez dotika. Preizkusna gradnja »TV nacin«
     * ([si.safeer.tv.BuildConfig.TV_NACIN_PREIZKUS]) se tako vede tudi na telefonu, da postavitve za televizor izmerimo
     * brez televizorja; v izdajah je zastavica vedno false.
     */
    fun jeTelevizor(c: Context): Boolean =
        si.safeer.tv.BuildConfig.TV_NACIN_PREIZKUS || c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Krajsa stranica zaslona televizorja v dp (1080p pri gostoti 2 in 720p pri gostoti 1,33: 960 x 540 dp). */
    private const val TV_VISINA_DP = 540

    /**
     * Preizkusna gradnja »TV nacin«: zaslon telefona se zaslonom predstavi z merami televizorja (krajsa stranica 540 dp),
     * da so postavitve in viri (values-sw480dp) taki kot na televizorju - telefon lezece ima le 384 dp visine in vrsta
     * kartic na zaslonu predvajanja se brez tega ne vidi cela. V izdajah vrne [c] nespremenjen.
     */
    fun zaslonTelevizorja(c: Context): Context {
        if (!si.safeer.tv.BuildConfig.TV_NACIN_PREIZKUS) return c
        val prava = c.resources.configuration
        val najmanjsa = prava.smallestScreenWidthDp
        if (najmanjsa <= 0 || najmanjsa >= TV_VISINA_DP) return c
        val f = TV_VISINA_DP.toFloat() / najmanjsa
        val k = Configuration(prava)
        k.densityDpi = (prava.densityDpi / f).toInt()
        k.smallestScreenWidthDp = TV_VISINA_DP
        k.screenWidthDp = (maxOf(prava.screenWidthDp, prava.screenHeightDp) * f).toInt()
        k.screenHeightDp = (minOf(prava.screenWidthDp, prava.screenHeightDp) * f).toInt()
        k.orientation = Configuration.ORIENTATION_LANDSCAPE
        return c.createConfigurationContext(k)
    }
}
