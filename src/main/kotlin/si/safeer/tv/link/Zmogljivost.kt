package si.safeer.tv.link

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zakon solidarnosti v Safeer Linku: vsaka naprava sproti pove, koliko proste moci ima (procesor,
 * pomnilnik, prostor, grafika, baterija), in ali sme ta trenutek pomagati drugim. Isti zapis kot
 * `host.info` na racunalniku (core/link_daljinec.py), da odjemalec bere vse naprave na en nacin.
 *
 * Samo stevilke o zmogljivosti - nic o vsebini naprave.
 *
 * Telefon in tablica pomagata, ce se polnita ALI imata baterijo vsaj [VKLOP_BATERIJA] %; nehata pod
 * [IZKLOP_BATERIJA] % (vmes ostane prejsnja odlocitev, da naprava pri meji ne preklaplja sem in tja).
 * Nikoli ne pomagata v nacinu varcevanja, pri pregrevanju ali kadar ji samo zmanjkuje pomnilnika.
 */
object Zmogljivost {
    private const val TAG = "SafeerZmogljivost"
    const val VKLOP_BATERIJA = 40
    const val IZKLOP_BATERIJA = 30
    private const val PREFS = "safeer_zmogljivost"

    fun porocilo(context: Context): JSONObject {
        val p = JSONObject()
        p.put("hostname", Build.MODEL)
        p.put("sistem", "Android " + Build.VERSION.RELEASE)
        p.put("vrsta", vrsta(context))
        val jedra = Runtime.getRuntime().availableProcessors()
        val cpu = JSONObject().put("jedra", jedra)
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }?.let { cpu.put("model", it) }
        p.put("cpu", cpu)
        var malo = false
        try {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            malo = info.lowMemory
            p.put("ram", JSONObject().put("skupaj", info.totalMem).put("prosto", info.availMem).put("nizko", info.lowMemory))
        } catch (e: Throwable) { Log.w(TAG, "RAM: ${e.message}") }
        try {
            val s = StatFs(Environment.getDataDirectory().path)
            p.put("disk", JSONObject().put("skupaj", s.blockCountLong * s.blockSizeLong).put("prosto", s.availableBlocksLong * s.blockSizeLong))
        } catch (e: Throwable) { Log.w(TAG, "Disk: ${e.message}") }
        p.put("gpu", grafika(context))
        val bat = baterija(context)
        if (bat != null) p.put("baterija", bat)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val varcuje = pm.isPowerSaveMode
        val vroce = Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val sorodnik = sorodnikZVecjoPrednostjo(context)
        if (sorodnik != null) {
            // Ista fizicna naprava, dve Safeer aplikaciji (npr. Safeer OS in brskalnik): pomaga samo ena,
            // da se moc naprave ne steje dvakrat in ne prevzameta dela hkrati.
            p.put("pomoc", JSONObject().put("lahko", false).put("razlog", "sorodnik").put("sorodnik", sorodnik))
        } else {
            p.put("pomoc", pomoc(context, bat, varcuje, vroce, malo))
        }
        return p
    }

    /** Prednost pri pomoci na eni napravi: Safeer OS, nato Predvajalnik, nato brskalnik. */
    private val PREDNOST = linkedMapOf("si.safeer.os" to 3, "si.safeer.tablet" to 3, "si.safeer.phone" to 3,
        "si.safeer.player" to 2)

    fun prednost(paket: String): Int = PREDNOST[paket] ?: 1

    /** Ime paketa Safeer aplikacije z vecjo prednostjo, ki je namescena na tej napravi, sicer null. */
    fun sorodnikZVecjoPrednostjo(context: Context): String? {
        val moja = prednost(context.packageName)
        return PREDNOST.keys.firstOrNull { paket ->
            paket != context.packageName && prednost(paket) > moja && try {
                context.packageManager.getPackageInfo(paket, 0); true
            } catch (_: Throwable) { false }
        }
    }

    private fun vrsta(context: Context): String = when {
        context.packageManager.hasSystemFeature("android.software.leanback") -> "tv"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "phone"
    }

    /** Baterija: raven in ali se polni; null pri napravi brez baterije (televizor). */
    private fun baterija(context: Context): JSONObject? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return null
        val raven = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val lestvica = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (raven < 0) return null
        // Na napajanju (polnilec, USB, brezzicno) - tudi ko je baterija polna in se ne polni vec.
        val polni = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return JSONObject().put("raven", raven * 100 / lestvica).put("polni", polni)
    }

    /**
     * Odlocitev o pomoci (z zapomnjenim prejsnjim stanjem za obmocje med izklopom in vklopom).
     * Vrne {lahko, razlog}; razlog: baterija, varcevanje, pregreto, malo_pomnilnika.
     */
    fun pomoc(context: Context, bat: JSONObject?, varcuje: Boolean, vroce: Boolean, malo: Boolean): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prej = prefs.getBoolean("pomaga", true)
        val (lahko, razlog) = odlocitev(bat?.optInt("raven", -1) ?: -1, bat?.optBoolean("polni") ?: true, prej, varcuje, vroce, malo)
        if (bat != null && !varcuje && !vroce && !malo) prefs.edit().putBoolean("pomaga", lahko).apply()
        return JSONObject().put("lahko", lahko).put("razlog", razlog)
    }

    /** Cista odlocitev (za teste): raven -1 = brez baterije (televizor, vedno na omrezju). */
    fun odlocitev(raven: Int, polni: Boolean, prej: Boolean, varcuje: Boolean, vroce: Boolean, malo: Boolean): Pair<Boolean, String> = when {
        varcuje -> false to "varcevanje"
        vroce -> false to "pregreto"
        malo -> false to "malo_pomnilnika"
        raven < 0 || polni -> true to ""
        raven >= VKLOP_BATERIJA -> true to ""
        raven < IZKLOP_BATERIJA -> false to "baterija"
        prej -> true to ""
        else -> false to "baterija"
    }

    /** Grafika: OpenGL ES, strojna osnova in strojni kodirniki videa (kdo zna pretvarjati video). */
    /** Grafika se med delovanjem ne spremeni: preberemo jo enkrat (nastevanje kodirnikov traja). */
    @Volatile private var grafikaPomnjena: String? = null

    private fun grafika(context: Context): JSONObject {
        grafikaPomnjena?.let { return JSONObject(it) }
        return grafikaZares(context).also { grafikaPomnjena = it.toString() }
    }

    private fun grafikaZares(context: Context): JSONObject {
        val g = JSONObject()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            g.put("model", listOf("OpenGL ES " + am.deviceConfigurationInfo.glEsVersion, Build.HARDWARE).filter { it.isNotBlank() }.joinToString(" · "))
        } catch (e: Throwable) { Log.w(TAG, "GPU: ${e.message}") }
        val kodirniki = JSONArray()
        try {
            for (c in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!c.isEncoder) continue
                if (Build.VERSION.SDK_INT >= 29 && !c.isHardwareAccelerated) continue
                if (Build.VERSION.SDK_INT < 29 && (c.name.startsWith("OMX.google.") || c.name.startsWith("c2.android."))) continue
                for (t in c.supportedTypes) {
                    if (!t.startsWith("video/")) continue
                    val v = try { c.getCapabilitiesForType(t).videoCapabilities } catch (_: Throwable) { null }
                    val o = JSONObject().put("vrsta", t.removePrefix("video/"))
                    if (v != null) o.put("sirina", v.supportedWidths.upper).put("visina", v.supportedHeights.upper)
                    kodirniki.put(o)
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "Kodirniki: ${e.message}") }
        g.put("kodirniki", kodirniki)
        g.put("strojno", kodirniki.length() > 0)
        return g
    }
}
