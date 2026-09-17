package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Aplikacije na televizorju, kot jih vidi zaganjalnik (LEANBACK_LAUNCHER), brez te aplikacije (brskalnik + Safeer OS). */
object Aplikacije {
    data class Vnos(val paket: String, val ime: String, val ikona: Drawable?, val namera: Intent)

    /**
     * Kot jih nasteje sistem - v tem vrstnem redu jih ima televizor sam (namescanje). To vzamemo
     * za privzeti vrstni red priljubljenih, da domaci zaslon Safeer OS zacne tam, kjer je
     * uporabnik ze doma; naprej si jih razvrsti sam.
     */
    fun sistemskiVrstniRed(context: Context): List<Vnos> = najdi(context)

    fun seznam(context: Context): List<Vnos> = najdi(context).sortedBy { it.ime.lowercase() }

    private fun najdi(context: Context): List<Vnos> {
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
        // Safeer Browser je ista aplikacija kot Safeer OS in ima svojo kartico Splet, zato ga ni.
        return vnosi
    }

    fun jeNamescena(context: Context, paket: String): Boolean = try {
        context.packageManager.getPackageInfo(paket, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
