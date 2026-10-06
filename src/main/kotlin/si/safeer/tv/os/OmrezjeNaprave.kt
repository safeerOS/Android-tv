package si.safeer.tv.os

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Omrezje, po katerem je ta naprava zdaj povezana ([OmrezjePravila]): Wi-Fi, kabel ali mobilno omrezje z rodom.
 * Racunalnik po njem izbere kakovost slike, kadar naprava gostuje v drugem omrezju (Global Link).
 *
 * Vse je branje in vse je varovano: naprava brez telefonije, brez dovoljenja ali s starejsim Androidom vrne
 * »celicno« (rod neznan) oziroma prazno - to nikoli ni razlog, da zaslona ne bi pokazali.
 */
object OmrezjeNaprave {
    private const val TAG = "SafeerOmrezje"

    /** Kaj omrezje kaze uporabniku (TelephonyDisplayInfo.getOverrideNetworkType); -1, dokler tega ne vemo. */
    @Volatile private var prikaz = -1
    @Volatile private var prijavljen = false
    private var poslusalec: Any? = null

    /**
     * Prijavi poslusalca prikaza omrezja (Android 12+): samo tako izvemo, da je LTE v resnici 5G (NSA). Enkrat na
     * proces; odgovor pride tik po prijavi. Brez dovoljenja ali telefonije se ne zgodi nic.
     */
    fun pripravi(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        if (prijavljen) return
        prijavljen = true
        try {
            val app = context.applicationContext
            val tm = telefonija(app) ?: return
            val p = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    prikaz = try { info.overrideNetworkType } catch (_: Throwable) { 0 }
                }
            }
            tm.registerTelephonyCallback(app.mainExecutor, p)
            poslusalec = p
        } catch (e: Throwable) {
            Log.i(TAG, "Prikaza omrezja ni mogoce brati: ${e.javaClass.simpleName}")
        }
    }

    /** Ali je smiselno trenutek pocakati na prikaz omrezja: mobilno omrezje LTE, prikaz pa se ni prisel. */
    fun cakaNaPrikaz(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && prijavljen && poslusalec != null && prikaz < 0 &&
            mobilno(context) && tip(context).let { it == 13 || it == 19 }

    /** Vrsta omrezja te naprave: wifi, ethernet, 5g, 4g, 3g, 2g, celicno ali prazno. */
    fun vrsta(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val zmoznosti = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            zmoznosti == null -> OmrezjePravila.NEZNANO
            zmoznosti.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> OmrezjePravila.WIFI
            zmoznosti.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> OmrezjePravila.ETHERNET
            zmoznosti.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                OmrezjePravila.rod(tip(context), maxOf(prikaz, 0))
            else -> OmrezjePravila.NEZNANO
        }
    } catch (e: Throwable) {
        OmrezjePravila.NEZNANO
    }

    /** Za dnevnik: vrsta podatkovnega omrezja in prikaz, kot ju vrne sistem (brez osebnih podatkov). */
    fun opis(context: Context): String = "tip=${tip(context)} prikaz=$prikaz"

    private fun mobilno(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    } catch (_: Throwable) { false }

    private fun telefonija(context: Context): TelephonyManager? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val podatkovna = SubscriptionManager.getDefaultDataSubscriptionId()
        if (tm != null && podatkovna != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(podatkovna)
        else tm
    } catch (_: Throwable) { null }

    /** TelephonyManager.NETWORK_TYPE_* podatkovne povezave; 0, ce je ne moremo prebrati. */
    @Suppress("DEPRECATION")
    private fun tip(context: Context): Int {
        // Android 13+: obicajno dovoljenje READ_BASIC_PHONE_STATE (brez vprasanja uporabniku).
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val t = telefonija(context)?.dataNetworkType ?: 0
                if (t != 0) return t
            } catch (_: SecurityException) {
                // Dovoljenja ni (ali ga je sistem odvzel): ostane rezerva spodaj.
            } catch (_: Throwable) { }
        }
        // Starejsi Android (in rezerva): vrsta, ki jo o dejavni povezavi vodi ConnectivityManager.
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val info = cm?.activeNetworkInfo
            if (info != null && info.type == ConnectivityManager.TYPE_MOBILE) info.subtype else 0
        } catch (_: Throwable) { 0 }
    }
}
