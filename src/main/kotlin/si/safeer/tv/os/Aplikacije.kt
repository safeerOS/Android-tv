package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

/** Aplikacije na televizorju, kot jih vidi zaganjalnik (LEANBACK_LAUNCHER), brez te aplikacije (brskalnik + Safeer OS). */
object Aplikacije {
    data class Vnos(val paket: String, val ime: String, val ikona: Drawable?, val namera: Intent,
                    val info: ActivityInfo? = null)

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
            // Ikona pred plakatom: plakat (banner) je sirok 320x180 in v majhni kvadratni
            // ploscici na domacem zaslonu postane neberljiv drobiz - prav to se je videlo pri
            // Safeer Browserju in SmartTubu. Kvadratna ikona aplikacije je vedno citljiva.
            val ikona = try { info.loadIcon(pm) ?: info.loadBanner(pm) } catch (_: Throwable) { null }
            vnosi.add(Vnos(info.packageName, z.loadLabel(pm).toString().ifBlank { info.packageName }, ikona, namera, info))
        }
        // Safeer Browser je ista aplikacija kot Safeer OS in ima svojo kartico Splet, zato ga ni.
        return vnosi
    }

    /**
     * Ikona aplikacije, oblikovana enako kot ikone spletnih aplikacij: zaobljena ploscica, temen
     * logotip na svetli podlagi (in obratno). Brez tega se temne ikone zlijejo s temnim ozadjem.
     * Rezultat si zapomnimo, ker se seznam aplikacij med brskanjem veckrat izrise.
     */
    private val oblikovane = HashMap<String, Drawable>()

    fun ikona(c: Context, v: Vnos): Drawable? {
        oblikovane[v.paket]?.let { return it }
        // Najprej ostra razlicica iz iste aplikacije; sele ce je ni, tista, ki jo da loadIcon.
        val ostra = ostraIkona(c, v)
        val vir = ostra ?: v.ikona ?: return null
        val slika = vBitmap(vir) ?: return v.ikona
        val d = try { SpletneAplikacije.ikonaIzSlike(c, slika) } catch (_: Throwable) { vir }
        oblikovane[v.paket] = d
        return d
    }

    /**
     * Ista ikona kot jo da loadIcon, a v najvisji gostoti, ki jo aplikacija nosi (xxxhdpi).
     * Televizor ima 320 dpi, zato loadIcon vrne 96 px staro ikono, ploscica pa je 184 px - ikona
     * se napihne in je mehka. Iz xxxhdpi dobimo 192 px (prilagodljiva: 432 px). Risbo porabimo
     * samo za oblikovanje in je ne hranimo, da velike slike ne ostanejo v pomnilniku televizorja.
     */
    private fun ostraIkona(c: Context, v: Vnos): Drawable? {
        val info = v.info ?: return null
        val res = info.iconResource
        if (res == 0) return null
        return try {
            c.packageManager.getResourcesForApplication(info.applicationInfo)
                .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
        } catch (_: Throwable) { null }
    }

    /** Risba (tudi prilagodljiva ikona) v sliko, v velikosti, ki jo risba sama ponudi. */
    private fun vBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable) d.bitmap?.let { return it }
        val sirina = d.intrinsicWidth.takeIf { it > 0 } ?: 192
        val visina = d.intrinsicHeight.takeIf { it > 0 } ?: 192
        return try {
            val b = Bitmap.createBitmap(sirina.coerceAtMost(512), visina.coerceAtMost(512), Bitmap.Config.ARGB_8888)
            val platno = Canvas(b)
            d.setBounds(0, 0, platno.width, platno.height)
            d.draw(platno)
            b
        } catch (_: Throwable) { null }
    }

    fun jeNamescena(context: Context, paket: String): Boolean = try {
        context.packageManager.getPackageInfo(paket, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
