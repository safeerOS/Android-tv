package si.safeer.tv.os

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Od Androida 15 (targetSdk 35+) sistem aplikacijo vedno rise do robov zaslona, tudi pod vrstico
 * stanja, navigacijsko vrstico in tipkovnico. Na televizorju teh vrstic ni (vse ostane enako), na
 * telefonu in tablici pa bi bili gumbi pod njimi. Zato vsebino odmaknemo za toliko, kolikor jo
 * vrstice, izrez kamere in odprta tipkovnica pokrivajo; skrite vrstice (celozaslonski nacin)
 * ne odmaknejo nicesar.
 */
object Robovi {
    fun uporabi(dejavnost: Activity) {
        if (Build.VERSION.SDK_INT < 35) return
        val vsebina = dejavnost.findViewById<View>(android.R.id.content) ?: return
        vsebina.setOnApplyWindowInsetsListener { pogled, robovi ->
            val r = robovi.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            pogled.setPadding(r.left, r.top, r.right, r.bottom)
            robovi
        }
        vsebina.requestApplyInsets()
    }
}
