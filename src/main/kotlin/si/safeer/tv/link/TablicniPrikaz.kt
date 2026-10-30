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
        val p = c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        val nasa = p.getBoolean(KLJUC, false)
        // Pregled 30. 10. 2026: izvorna gostota iz upravitelja oken (tovarniska), ne iz trenutnih metrik - te so ob hitrem
        // preklopu (telefonska A -> dom -> telefonska B) lahko se tablicne (240) in tablicni nacin bi izostal.
        val zacetna = celo("getInitialDisplayDensity")
        val osnovna = celo("getBaseDisplayDensity")
        // Uporabnik ima svojo gostoto (»Prikaz«, adb wm density): ne spreminjamo je in je ob izhodu ne pobrisemo.
        if (!nasa && zacetna != null && osnovna != null && zacetna != osnovna) {
            Log.i(TAG, "uporabnikova gostota $osnovna (tovarniska $zacetna): ostane, samo lezeca usmerjenost")
            return
        }
        val m = c.resources.displayMetrics
        val dpi = si.safeer.tv.os.OsPravila.gostotaTablice(minOf(m.widthPixels, m.heightPixels), zacetna ?: m.densityDpi)
        if (dpi <= 0) return
        // Zastavica PRED spremembo in sinhrono: ce proces umre takoj po spremembi, jo obnovi() ob naslednjem zagonu najde.
        p.edit().putBoolean(KLJUC, true).commit()
        if (!nastaviGostoto(dpi) && !nasa) p.edit().putBoolean(KLJUC, false).commit()
    }

    /** Celostevilska vrednost upravitelja oken za zaslon 0 (gostota); null = ne vemo. */
    private fun celo(metoda: String): Int? = try {
        val w = upravitelj() ?: throw IllegalStateException("ni upravitelja oken")
        Class.forName("android.view.IWindowManager").getMethod(metoda, Int::class.java).invoke(w, 0) as? Int
    } catch (_: Throwable) { null }

    fun izklopi() {
        // Tudi ce nismo aktivni: zastavica je lahko ostala (neuspel prejsnji poskus vracanja) - ponovni poskus je poceni.
        if (!aktiven) { obnovi(); return }
        aktiven = false
        usmerjenost?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        usmerjenost = null
        obnovi()
    }

    /** Vrne privzeto gostoto, ce smo jo mi spremenili (tudi po koncu procesa). */
    fun obnovi() {
        val p = c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        if (!p.getBoolean(KLJUC, false)) return
        if (pocistiGostoto()) p.edit().putBoolean(KLJUC, false).commit()
    }

    private fun upravitelj(): Any? = try {
        // Android 11+: skriti vmesnik brez izjeme vrne NoSuchMethodException (izmerjeno na TV 30. 10. 2026).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/IWindowManager", "Landroid/view/WindowManagerGlobal"
            )
        }
        Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
    } catch (e: Throwable) { Log.w(TAG, "IWindowManager: ${e.javaClass.simpleName}"); null }

    private fun nastaviGostoto(dpi: Int): Boolean = try {
        val w = upravitelj() ?: throw IllegalStateException("ni upravitelja oken")
        Class.forName("android.view.IWindowManager")
            .getMethod("setForcedDisplayDensityForUser", Int::class.java, Int::class.java, Int::class.java)
            .invoke(w, 0, dpi, uporabnik())
        Log.i(TAG, "gostota zaslona za tablicno postavitev: $dpi")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "gostote ni bilo mogoce spremeniti: ${e.javaClass.simpleName} ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
        false
    }

    private fun pocistiGostoto(): Boolean = try {
        val w = upravitelj() ?: throw IllegalStateException("ni upravitelja oken")
        Class.forName("android.view.IWindowManager")
            .getMethod("clearForcedDisplayDensityForUser", Int::class.java, Int::class.java)
            .invoke(w, 0, uporabnik())
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
        // Pravi ID uporabnika (0): psevdouporabnik -2 (trenutni) na Android 11 zahteva INTERACT_ACROSS_USERS_FULL.
        fun uporabnik(): Int = android.os.Process.myUid() / 100000
    }
}
