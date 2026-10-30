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

    /** Dovoljenje imajo samo okusi z zaganjalnikom (os, telefon, tablica); brskalnik in predvajalnik ga nimata. */
    private fun smemo(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Zapre aplikacije v ozadju. Vrne stevilo poskusov; meritev pred in po da [stanje]. Klic iz ozadja. */
    @android.annotation.SuppressLint("MissingPermission")
    fun sprosti(c: Context): Int {
        if (!smemo(c)) return 0
        val am = c.getSystemService(ActivityManager::class.java)
        val seznam = kandidati(c)
        for (p in seznam) try { am.killBackgroundProcesses(p) } catch (_: Throwable) { }
        Log.i(TAG, "sprosceno: ${seznam.size} aplikacij v ozadju")
        return seznam.size
    }

    /** Ena aplikacija (telefonska po vrnitvi domov), samo ce je pomnilnika malo. */
    @android.annotation.SuppressLint("MissingPermission")
    fun zapriCeMalo(c: Context, paket: String) {
        if (!smemo(c) || !stanje(c).malo) return
        if (!OsPravila.smemoZapreti(paket, c.packageName, null, false)) return
        try { c.getSystemService(ActivityManager::class.java).killBackgroundProcesses(paket) } catch (_: Throwable) { }
        Log.i(TAG, "telefonska aplikacija zaprta po vrnitvi domov (malo pomnilnika): $paket")
    }
}
