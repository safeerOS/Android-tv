package si.safeer.tv.os

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Od Androida 15 (targetSdk 35+) sistem aplikacijo vedno rise do robov zaslona, tudi pod vrstico
 * stanja, navigacijsko vrstico in tipkovnico. Na televizorju teh vrstic ni (vse ostane enako), na
 * telefonu in tablici pa bi bili gumbi pod njimi. Zato vsebino odmaknemo za toliko, kolikor jo
 * vrstice, izrez kamere in odprta tipkovnica pokrivajo; skrite vrstice (celozaslonski nacin)
 * ne odmaknejo nicesar.
 */
object Robovi {
    fun uporabi(dejavnost: Activity) {
        celozaslonsko(dejavnost)
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

    /**
     * Zasloni za sliko (predvajalnik, slika, zaslon racunalnika) imajo v temi windowFullscreen, ki
     * skrije samo vrstico stanja. Na tablici je navigacijska vrstica ostala in jemala prostor, zato
     * je bil okoli namizja crn rob. Tu skrijemo obe; poteg od roba ju za trenutek pokaze (Nazaj
     * deluje kot prej). Na televizorju vrstic ni, zato se tam nic ne spremeni.
     */
    private fun celozaslonsko(dejavnost: Activity) {
        val okno = dejavnost.window
        if (okno.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0) return
        if (Build.VERSION.SDK_INT >= 30) {
            val upravljalnik = okno.insetsController ?: return
            upravljalnik.hide(WindowInsets.Type.systemBars())
            upravljalnik.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            okno.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
}
