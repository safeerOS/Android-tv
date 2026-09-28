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
        return SpletMostPravila.DOMACA + if (tv) "?tv=1" else ""
    }
}
