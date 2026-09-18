package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Igralni plosek kot polnopraven daljinec za Safeer OS.
 *
 * Kdor ima plosek priklopljen na televizor (na PlayStationu z njim upravljas vso napravo), ga hoce
 * uporabljati povsod, ne le pri sliki racunalnika. Android sam plosecka ne prevede v premikanje po
 * zaslonu: leva palica ne premika izbire, B ni Nazaj, X in Y ne pomenita nicesar. To naredimo tu,
 * enako na vseh zaslonih Safeer OS:
 *
 *  - **leva palica in smerni krizec**: premikanje izbire (palica se ponavlja, dokler jo drzis),
 *  - **A**: odpri (isto kot OK), **B**: nazaj, **X**: uredi (dolg OK), **Y**: domaci zaslon,
 *  - **L1/R1**: skok med vrstami, **L2/R2**: stran gor in dol po dolgem seznamu,
 *  - **Start**: nastavitve.
 *
 * Zaslon, ki plosek potrebuje zase (slika racunalnika), tega prevoda ne uporablja - tam gre plosek
 * naravnost v racunalnik.
 */
object Kontroler {

    /** Pod tem odklonom palice ne stejemo; palice v mirovanju nikoli ne kazejo natanko nic. */
    const val MRTVI_KOT = 0.35f
    /** Prvi premik je takojsen, nato pocaka, da uporabnik ne preskoci cele vrste naenkrat. */
    private const val PRVI_ZAMIK = 400L
    private const val PONOVITEV = 140L
    /** Koliko korakov naredi L2/R2 (stran gor, stran dol). */
    private const val STRAN = 5

    fun jePriklopljen(): Boolean = try {
        InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id)
            d != null && !d.isVirtual && (
                d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }
    } catch (_: Throwable) { false }

    /**
     * Gumb plosecka v dejanje. Vrne true, kadar smo dogodek porabili - takrat ga zaslon ne dobi se
     * enkrat. [dodatno] dobi prvo besedo: zaslon, ki hoce gumb zase (predvajalnik), ga obdrzi.
     */
    fun tipka(a: Activity, dogodek: KeyEvent, dodatno: (Int) -> Boolean = { false }): Boolean {
        if (!jePlosek(dogodek)) return false
        val koda = dogodek.keyCode
        if (dogodek.action != KeyEvent.ACTION_DOWN) return koda in GUMBI
        if (dodatno(koda)) return true
        val fokus: View? = a.currentFocus
        when (koda) {
            KeyEvent.KEYCODE_BUTTON_A -> { fokus?.performClick(); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { a.onBackPressed(); return true }
            KeyEvent.KEYCODE_BUTTON_X -> { fokus?.performLongClick(); return true }
            KeyEvent.KEYCODE_BUTTON_Y -> { domov(a); return true }
            KeyEvent.KEYCODE_BUTTON_START -> { nastavitve(a); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { premakni(a, View.FOCUS_UP, 1); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { premakni(a, View.FOCUS_DOWN, 1); return true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { premakni(a, View.FOCUS_UP, STRAN); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { premakni(a, View.FOCUS_DOWN, STRAN); return true }
        }
        return false
    }

    private val GUMBI = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)

    private fun jePlosek(dogodek: KeyEvent): Boolean {
        val v = dogodek.source
        return v and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            v and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun premakni(a: Activity, smer: Int, kolikokrat: Int) {
        var v = a.currentFocus ?: return
        repeat(kolikokrat) {
            val naslednji = v.focusSearch(smer) ?: return
            if (!naslednji.requestFocus()) return
            v = naslednji
        }
    }

    /** Y pelje na domaci zaslon Safeer OS, kjerkoli si - kot gumb PS na ploscku. */
    private fun domov(a: Activity) {
        if (a is DomovActivity) return
        a.startActivity(Intent(a, DomovActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun nastavitve(a: Activity) {
        if (a is NastavitveActivity) return
        a.startActivity(Intent(a, NastavitveActivity::class.java))
    }

    /**
     * Plosek tudi v pogovornih oknih. Okno ima svoje okno in dogodkov ne dobi zaslon, zato gumba
     * prevedemo tu: A je OK, B zapre okno. Brez tega bi moral uporabnik sredi izbire vzeti daljinec.
     */
    fun pokazi(okno: android.app.AlertDialog): android.app.AlertDialog {
        okno.setOnKeyListener { d, koda, e ->
            when (koda) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    // A je za okno isto kot OK: dogodek posljemo naprej pod drugo oznako.
                    okno.window?.decorView?.dispatchKeyEvent(KeyEvent(e.action, KeyEvent.KEYCODE_DPAD_CENTER))
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (e.action == KeyEvent.ACTION_UP) d.cancel()
                    true
                }
                else -> false
            }
        }
        return okno
    }

    /**
     * Leva palica premika izbiro. Palica ne poslje dogodka, dokler se premika, zato si odklon
     * zapomnimo in premikamo sami, dokler je odklonjena.
     */
    class Palica(private val dejavnost: Activity) {
        private val glavna = Handler(Looper.getMainLooper())
        private var smer = 0
        private var tece = false

        fun dogodek(e: MotionEvent): Boolean {
            if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
            val x = os(e, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X)
            val y = os(e, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y)
            val nova = when {
                kotlin.math.abs(x) >= kotlin.math.abs(y) && x <= -MRTVI_KOT -> View.FOCUS_LEFT
                kotlin.math.abs(x) >= kotlin.math.abs(y) && x >= MRTVI_KOT -> View.FOCUS_RIGHT
                y <= -MRTVI_KOT -> View.FOCUS_UP
                y >= MRTVI_KOT -> View.FOCUS_DOWN
                else -> 0
            }
            if (nova == smer) return true
            smer = nova
            if (smer != 0) zacni()
            return true
        }

        fun ustavi() { smer = 0 }

        private fun zacni() {
            korak()
            if (tece) return
            tece = true
            glavna.postDelayed(object : Runnable {
                override fun run() {
                    if (smer == 0 || dejavnost.isFinishing) { tece = false; return }
                    korak()
                    glavna.postDelayed(this, PONOVITEV)
                }
            }, PRVI_ZAMIK)
        }

        private fun korak() {
            val s = smer
            if (s == 0) return
            val v = dejavnost.currentFocus ?: return
            v.focusSearch(s)?.requestFocus()
        }

        private fun os(e: MotionEvent, glavnaOs: Int, nadomestna: Int): Float {
            val v = e.getAxisValue(glavnaOs)
            if (kotlin.math.abs(v) >= MRTVI_KOT) return v
            val n = e.getAxisValue(nadomestna)
            return if (kotlin.math.abs(n) >= MRTVI_KOT) n else 0f
        }
    }
}
