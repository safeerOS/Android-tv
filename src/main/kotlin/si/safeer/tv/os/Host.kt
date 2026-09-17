package si.safeer.tv.os

import android.content.Context
import android.util.Log
import java.net.URI

/**
 * Kje je **host** - racunalnik, ki televizorju daje datoteke in moc.
 *
 *  - **Domace omrezje** (privzeto): sredisce Safeer Linka je na tem televizorju, datoteke pa deli
 *    Safeer Control na racunalniku v isti hisi. Nic ne zapusti omrezja.
 *  - **Oddaljeni naslov**: sredisce tece na strezniku, ki ga ima uporabnik zunaj hise - najet
 *    strojni strezniku ali navidezni strezniku v oblaku. Televizor se nanj poveze po naslovu,
 *    seznanitev pa je enaka kot doma: koda s SPAKE2 in pripeto potrdilo (HubPairing), zato zeton
 *    nikoli ne potuje po omrezju in zamenjano potrdilo seznanitev zavrne.
 *
 * To ni "Safeer oblak": strezniku damo naslov mi, lastnik je uporabnik. V tem nacinu promet
 * **zapusti domace omrezje** - to mora v vmesniku pisati, ne sme se skriti za lepo besedo.
 */
object Host {
    private const val TAG = "SafeerOsHost"
    private const val PREFS = "safeer_os"
    private const val KLJUC_KJE = "host_kje"
    private const val KLJUC_URL = "host_url"
    private const val KLJUC_ZETON = "host_zeton"
    private const val KLJUC_ODTIS = "host_odtis"
    private const val KLJUC_ID = "host_hub_id"

    const val DOMA = "doma"
    const val ODDALJEN = "oddaljen"

    /** Vrata, na katerih Safeer Hub poslusa, kadar jih uporabnik ne pove (HubStreznik.PRIVZETA_VRATA). */
    private const val PRIVZETA_VRATA = 8990

    private fun p(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun kje(context: Context): String = p(context).getString(KLJUC_KJE, DOMA) ?: DOMA

    fun jeOddaljen(context: Context): Boolean = kje(context) == ODDALJEN && poverilnice(context) != null

    /** Naslov oddaljenega sredisca (wss://...), tudi ce seznanitev se ni koncana. */
    fun naslov(context: Context): String? = p(context).getString(KLJUC_URL, null)

    /** Gostitelj brez sheme in poti - za izpis v nastavitvah. */
    fun gostitelj(context: Context): String? = naslov(context)?.let {
        try { URI(it).host } catch (_: Throwable) { it }
    }

    fun poverilnice(context: Context): Sorodnik.Poverilnice? {
        val s = p(context)
        val url = s.getString(KLJUC_URL, null) ?: return null
        val zeton = s.getString(KLJUC_ZETON, null) ?: return null
        val odtis = s.getString(KLJUC_ODTIS, null) ?: return null
        return Sorodnik.Poverilnice(url, zeton, odtis, s.getString(KLJUC_ID, "") ?: "")
    }

    fun shrani(context: Context, url: String, zeton: String, odtis: String, hubId: String) {
        p(context).edit()
            .putString(KLJUC_KJE, ODDALJEN).putString(KLJUC_URL, url).putString(KLJUC_ZETON, zeton)
            .putString(KLJUC_ODTIS, odtis).putString(KLJUC_ID, hubId).apply()
        Log.i(TAG, "Host je oddaljen: ${try { URI(url).host } catch (_: Throwable) { "?" }}")
    }

    /** Nazaj na domace omrezje; poverilnice oddaljenega hosta pozabimo. */
    fun domov(context: Context) {
        p(context).edit().putString(KLJUC_KJE, DOMA)
            .remove(KLJUC_URL).remove(KLJUC_ZETON).remove(KLJUC_ODTIS).remove(KLJUC_ID).apply()
        Log.i(TAG, "Host je spet v domacem omrezju.")
    }

    /** Med seznanitvijo si naslov zapomnimo, ceprav zetona se ni. */
    fun zapomniNaslov(context: Context, url: String) {
        p(context).edit().putString(KLJUC_URL, url).apply()
    }

    /**
     * Iz tega, kar uporabnik vtipka ("moj.streznik.si", "moj.streznik.si:9000", "1.2.3.4"),
     * naredi naslov sredisca. Brez TLS ne gremo nikamor: zeton bi potoval v cistem besedilu.
     * Vrne null, ce vnos ni videti kot naslov.
     */
    fun izVnosa(vnos: String): String? {
        var t = vnos.trim()
        if (t.isEmpty()) return null
        t = t.removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")
        t = t.trimEnd('/')
        if (t.contains("/")) t = t.substringBefore("/")
        val gostitelj = if (t.startsWith("[")) t.substringAfter("[").substringBefore("]") else t.substringBefore(":")
        if (gostitelj.isEmpty()) return null
        // Ime domene ali naslov IP; brez pike (razen IPv6) ne gre nikamor.
        if (!gostitelj.contains(".") && !gostitelj.contains(":")) return null
        val vrata = t.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: PRIVZETA_VRATA
        val zaUrl = if (gostitelj.contains(":")) "[$gostitelj]" else gostitelj
        return "wss://$zaUrl:$vrata/cast/ws"
    }
}
