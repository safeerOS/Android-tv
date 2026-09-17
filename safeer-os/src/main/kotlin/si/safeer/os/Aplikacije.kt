package si.safeer.os

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Aplikacije na televizorju, kot jih vidi zaganjalnik (LEANBACK_LAUNCHER), brez Safeer OS samega. */
object Aplikacije {
    data class Vnos(val paket: String, val ime: String, val ikona: Drawable?, val namera: Intent)

    fun seznam(context: Context): List<Vnos> {
        val pm = context.packageManager
        val poizvedba = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val zadetki = try { pm.queryIntentActivities(poizvedba, 0) } catch (_: Throwable) { emptyList() }
        val vnosi = ArrayList<Vnos>()
        for (z in zadetki) {
            val info = z.activityInfo ?: continue
            if (info.packageName == context.packageName) continue
            val namera = pm.getLeanbackLaunchIntentForPackage(info.packageName)
                ?: pm.getLaunchIntentForPackage(info.packageName) ?: continue
            val ikona = try { info.loadBanner(pm) ?: info.loadIcon(pm) } catch (_: Throwable) { null }
            vnosi.add(Vnos(info.packageName, z.loadLabel(pm)?.toString() ?: info.packageName, ikona, namera))
        }
        // Safeer Browser na vrh, potem po imenu.
        return vnosi.sortedWith(compareBy({ it.paket != Sorodnik.PAKET_BRSKALNIKA }, { it.ime.lowercase() }))
    }

    fun jeNamescena(context: Context, paket: String): Boolean = try {
        context.packageManager.getPackageInfo(paket, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
