package si.safeer.tv.link

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Lastnik 30. 10. 2026: aplikacije, ki niso za TV, naj se odprejo kot na lezeci tablici - lezeca slika (ne pokoncna
 * ali obrnjena) in postavitev tablice namesto telefona. Dokler je taka aplikacija v ospredju:
 *  - nevidno prekrivno okno zahteva lezeco usmerjenost zaslona,
 *  - gostota zaslona se zacasno zmanjsa, da je najmanjsa sirina >= 600 dp (aplikacija izbere postavitev tablice).
 * Ob odhodu iz aplikacije se oboje vrne. Ce se je proces vmes koncal, [obnovi] ob naslednjem zagonu storitve
 * vrne privzeto gostoto (zapis v nastavitvah), da televizor nikoli ne ostane pri zmanjsani gostoti.
 */
class TablicniPrikaz(private val c: Context) {

    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var usmerjenost: View? = null
    private var aktiven = false

    fun vklopi() {
        if (aktiven) return
        aktiven = true
        if (usmerjenost == null) {
            val v = View(c)
            val lp = WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            try { wm.addView(v, lp); usmerjenost = v } catch (e: Throwable) { Log.w(TAG, "usmerjenost: ${e.message}") }
        }
        val m = c.resources.displayMetrics
        val dpi = si.safeer.tv.os.OsPravila.gostotaTablice(minOf(m.widthPixels, m.heightPixels), m.densityDpi)
        if (dpi > 0 && nastaviGostoto(dpi)) {
            c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putBoolean(KLJUC, true).apply()
        }
    }

    fun izklopi() {
        if (!aktiven) return
        aktiven = false
        usmerjenost?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        usmerjenost = null
        obnovi()
    }

    /** Vrne privzeto gostoto, ce smo jo mi spremenili (tudi po koncu procesa). */
    fun obnovi() {
        val p = c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        if (!p.getBoolean(KLJUC, false)) return
        if (pocistiGostoto()) p.edit().putBoolean(KLJUC, false).apply()
    }

    private fun upravitelj(): Any? = try {
        Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
    } catch (e: Throwable) { Log.w(TAG, "IWindowManager: ${e.javaClass.simpleName}"); null }

    private fun nastaviGostoto(dpi: Int): Boolean = try {
        val w = upravitelj() ?: throw IllegalStateException("ni upravitelja oken")
        w.javaClass.getMethod("setForcedDisplayDensityForUser", Int::class.java, Int::class.java, Int::class.java)
            .invoke(w, 0, dpi, UPORABNIK_TRENUTNI)
        Log.i(TAG, "gostota zaslona za tablicno postavitev: $dpi")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "gostote ni bilo mogoce spremeniti: ${e.javaClass.simpleName} ${e.cause?.javaClass?.simpleName}")
        false
    }

    private fun pocistiGostoto(): Boolean = try {
        val w = upravitelj() ?: throw IllegalStateException("ni upravitelja oken")
        w.javaClass.getMethod("clearForcedDisplayDensityForUser", Int::class.java, Int::class.java)
            .invoke(w, 0, UPORABNIK_TRENUTNI)
        Log.i(TAG, "gostota zaslona vrnjena")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "gostote ni bilo mogoce vrniti: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "SafeerTablica"
        const val NASTAVITVE = "safeer_tablicni_prikaz"
        const val KLJUC = "gostota_vsiljena"
        /** UserHandle.USER_CURRENT */
        const val UPORABNIK_TRENUTNI = -2
    }
}
