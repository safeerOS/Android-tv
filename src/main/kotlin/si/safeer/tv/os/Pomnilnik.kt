package si.safeer.tv.os

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Lastnik 30. 10. 2026 (TV: 2,7 GB RAM, prosto 0,55 GB, swap poln): sprostitev pomnilnika brez roota in brez ADB.
 * Android dovoli zapreti samo procese v OZADJU (KILL_BACKGROUND_PROCESSES): aplikacija v ospredju in tista, ki predvaja
 * (storitev v ospredju), ostaneta. Nikoli ne zapremo Safeerja, tipkovnice ali sistemskih storitev televizorja.
 */
object Pomnilnik {
    private const val TAG = "SafeerPomnilnik"

    /**
     * Stevec odhodov iz nasih dejavnosti (onPause katerekoli). Lastnik 2. 11. 2026: TV Browser, odprt 3 s po vrnitvi
     * domov, je zaprlo ciscenje ozadja, ki se je ravno izvajalo. Ciscenje se ustavi, ko se stevec spremeni.
     */
    @Volatile var odhodi: Long = 0L
        private set
    @Volatile private var spremljam = false

    private fun spremljajOdhode(c: Context) {
        if (spremljam) return
        val app = c.applicationContext as? android.app.Application ?: return
        synchronized(this) {
            if (spremljam) return
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityPaused(a: android.app.Activity) { odhodi++ }
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityResumed(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            })
            spremljam = true
        }
    }

    data class Stanje(val prostoMb: Int, val skupajMb: Int, val malo: Boolean)

    fun stanje(c: Context): Stanje {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        try { am.getMemoryInfo(mi) } catch (_: Throwable) { }
        val prosto = (mi.availMem / 1_048_576).toInt()
        val skupaj = (mi.totalMem / 1_048_576).toInt()
        return Stanje(prosto, skupaj, OsPravila.maloPomnilnika(prosto, skupaj) || mi.lowMemory)
    }

    /** Aplikacije z zaganjalnikom, ki jih smemo zapreti (ce tecejo v ozadju). */
    private fun kandidati(c: Context): List<String> {
        val pm = c.packageManager
        val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")
            ?.substringBefore('/') } catch (_: Throwable) { null }
        val paketi = LinkedHashSet<String>()
        for (k in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(k), 0) } catch (_: Throwable) { emptyList() }
                .mapNotNullTo(paketi) { it.activityInfo?.packageName }
        }
        return paketi.filter { p ->
            val ai = try { pm.getApplicationInfo(p, 0) } catch (_: Throwable) { null } ?: return@filter false
            OsPravila.smemoZapreti(p, c.packageName, ime, (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0)
        }
    }

    /**
     * Zapre [paketi]. Lastnik 1. 11. 2026: na TV (Android 11) killBackgroundProcesses tujih aplikacij ne zapre - z
     * uporabnikovim soglasjem gre zapiranje prek lastnega adb ([AdbLokalno], am force-stop), sicer kot doslej.
     * Nikoli na glavni niti (omrezje).
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun zapriPakete(c: Context, paketi: List<String>, vOspredju: Set<String>, ime: String?, nadaljuj: () -> Boolean = { true }): List<String> {
        if (paketi.isEmpty()) return emptyList()
        if (AdbLokalno.soglasje(c)) {
            // Sistemskih aplikacij televizorja ne zapiramo na silo (killBackgroundProcesses jih prej tudi ni).
            val pm = c.packageManager
            // Aplikacije z vklopljeno storitvijo (dostopnost, obvestila): force-stop bi jim storitev izklopil.
            val sStoritvijo = listOf("enabled_accessibility_services", "enabled_notification_listeners").flatMap { k ->
                AdbLokalnoPravila.paketiStoritev(try { android.provider.Settings.Secure.getString(c.contentResolver, k) } catch (_: Throwable) { null })
            }.toSet()
            val dovoljeni = paketi.filter { p ->
                p !in sStoritvijo && AdbLokalnoPravila.smemoZapreti(p, c.packageName, vOspredju, ime) &&
                    (try { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0 } catch (_: Throwable) { false })
            }
            return AdbLokalno.zapri(c, dovoljeni, nadaljuj = nadaljuj)
        }
        val am = c.getSystemService(ActivityManager::class.java)
        val zaprti = ArrayList<String>()
        for (p in paketi) try { am.killBackgroundProcesses(p); zaprti.add(p) } catch (_: Throwable) { }
        return zaprti
    }

    private fun tipkovnica(c: Context): String? = try {
        android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/')
    } catch (_: Throwable) { null }

    /** Dovoljenje imajo samo okusi z zaganjalnikom (os, telefon, tablica); brskalnik in predvajalnik ga nimata. */
    private fun smemo(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Zapre aplikacije v ozadju. Vrne stevilo poskusov; meritev pred in po da [stanje]. Klic iz ozadja. */
    @android.annotation.SuppressLint("MissingPermission")
    fun sprosti(c: Context): Int {
        if (!smemo(c)) return 0
        val seznam = kandidati(c)
        val zaprti = zapriPakete(c, seznam, emptySet(), tipkovnica(c))
        Log.i(TAG, "sprosceno: ${zaprti.size} od ${seznam.size} aplikacij v ozadju")
        return zaprti.size
    }

    /** Ena aplikacija (telefonska po vrnitvi domov), samo ce je pomnilnika malo. */
    @android.annotation.SuppressLint("MissingPermission")
    fun zapriCeMalo(c: Context, paket: String) {
        if (!smemo(c) || !stanje(c).malo) return
        if (!OsPravila.smemoZapreti(paket, c.packageName, null, false)) return
        Thread { zapriPakete(c, listOf(paket), emptySet(), tipkovnica(c))
            Log.i(TAG, "telefonska aplikacija zaprta po vrnitvi domov (malo pomnilnika): $paket") }.start()
    }

    /** Zvok ob trenutku menjave (pred zamikom): po 3 s ze igra NOVA aplikacija (film) in prejsnja se ne bi nikoli zaprla. */
    private const val PREFS = "safeer_os"

    fun zvokAktiven(c: Context): Boolean = try {
        (c.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
    } catch (_: Exception) { false }

    @android.annotation.SuppressLint("MissingPermission")   // dovoljenje preveri smemo(); brskalnik ga nima
    fun zapriObMenjavi(c: Context, prejsnja: String, nova: String?, casMs: Long, predvaja: Boolean) {
        if (!smemo(c)) return
        val vklopljeno = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("zapri_neuporabljeno", true)
        val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/') } catch (_: Throwable) { null }
        val pm = c.packageManager
        val trajen = try { (pm.getApplicationInfo(prejsnja, 0).flags and ApplicationInfo.FLAG_PERSISTENT) != 0 } catch (_: Exception) { false }
        
        if (OsPravila.zapriObMenjavi(prejsnja, nova, casMs, c.packageName, ime, trajen, vklopljeno, predvaja)) {
            Thread {
                val pred = stanje(c)
                val zaprti = zapriPakete(c, listOf(prejsnja), setOfNotNull(nova), ime)
                val po = stanje(c)
                Log.i(TAG, "zapiranje ob menjavi ($prejsnja -> $nova, ${casMs / 1000} s, zaprto=${zaprti.isNotEmpty()}), RAM pred: ${pred.prostoMb}MB, po: ${po.prostoMb}MB")
            }.start()
        }
    }

    /**
     * [nasePredvajanje]: klic ob zagonu NASEGA predvajanja (film, glasba v Safeer OS). Zvok, ki tece takrat, je nas, zato
     * ne sme ustaviti zapiranja (pregled 1. 11. 2026: po 3 s je isMusicActive vedno true in se ni zaprlo nic).
     * Zvok merimo PRED zamikom: ob vrnitvi domov pomeni, da igra ena od aplikacij v ozadju - takrat ne zapremo nicesar.
     */
    @android.annotation.SuppressLint("MissingPermission")   // dovoljenje preveri smemo(); brskalnik ga nima
    fun pocistiOzadje(c: Context, nasePredvajanje: Boolean = false) {
        if (!smemo(c)) return
        spremljajOdhode(c)
        val odhodiObZacetku = odhodi
        val nadaljuj = { AdbLokalnoPravila.smemoNadaljevati(odhodiObZacetku, odhodi) }
        val predvaja = !nasePredvajanje && try {
            (c.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
        } catch (_: Exception) { false }
        Thread {
            try { Thread.sleep(3000) } catch (_: Exception) {}
            if (!nadaljuj()) { Log.i(TAG, "ciscenje ozadja preskoceno - uporabnik je odprl aplikacijo"); return@Thread }
            val amgr = c.getSystemService(ActivityManager::class.java)
            val pred = stanje(c)
            val k = kandidati(c).toMutableList()
            // Lastnik 1. 11. 2026: tudi nase aplikacije (TV Browser, Play Store, Predvajalnik), ko jih ne uporabljamo vec.
            for (nas in listOf("si.safeer.tv", "si.safeer.trgovina", "si.safeer.player")) if (!k.contains(nas)) k.add(nas)
            val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/') } catch (_: Throwable) { null }
            val pm = c.packageManager
            val zdaj = System.currentTimeMillis()
            val vklopljeno = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("zapri_neuporabljeno", true)
            val runningAppProcesses = amgr.runningAppProcesses
            val foregroundPackages = runningAppProcesses?.filter { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.flatMap { it.pkgList.toList() } ?: emptyList()
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val stats = usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, zdaj - 1000 * 60 * 60 * 24, zdaj)
            val zaZapreti = mutableListOf<String>()
            for (p in k) {
                if (foregroundPackages.contains(p)) continue
                val lastUsed = stats?.find { it.packageName == p }?.lastTimeUsed ?: 0L
                val trajen = try { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_PERSISTENT) != 0 } catch (_: Exception) { false }
                if (OsPravila.zapriObVrnitvi(p, c.packageName, ime, trajen, vklopljeno, predvaja, lastUsed, zdaj)) zaZapreti.add(p)
            }
            val pocisceno = zapriPakete(c, zaZapreti, foregroundPackages.toSet(), ime, nadaljuj)
            val po = stanje(c)
            if (pocisceno.isNotEmpty()) Log.i(TAG, "zaprto v ozadju: ${pocisceno.joinToString(", ")}, RAM pred: ${pred.prostoMb}MB, po: ${po.prostoMb}MB")
        }.start()
    }
}
