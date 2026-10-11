package si.safeer.tv

import android.content.Context
import android.content.pm.PackageManager

object SpletDomaca {
    const val STARA = "file:///android_asset/brave_home.html"

    fun jeSafeerOs(context: Context): Boolean = context.packageName in setOf(
        "si.safeer.os", "si.safeer.tablet", "si.safeer.phone"
    )

    fun naslov(context: Context): String {
        if (!jeSafeerOs(context)) return STARA
        val tv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        // Barve izbrane teme Safeer OS (poudarek, ozadje, kartica, besedilo, umirjeno), da je zacetna stran enaka
        // kot domaci zaslon - ne zelena pri modri temi.
        val t = android.view.ContextThemeWrapper(context, si.safeer.tv.os.Tema.izbrana(context).stil)
        val barve = listOf(R.color.os_mint, R.color.os_ozadje, R.color.os_kartica, R.color.os_besedilo, R.color.os_umirjeno)
            .joinToString(",") { String.format("%06X", si.safeer.tv.os.Tema.barva(t, it) and 0xFFFFFF) }
        return SpletMostPravila.DOMACA + "?barve=" + barve + if (tv) "&tv=1" else ""
    }
}
