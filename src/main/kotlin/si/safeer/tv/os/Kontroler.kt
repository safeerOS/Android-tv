package si.safeer.tv.os

import android.app.Activity
import android.content.Context
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

    private const val NASTAVITVE = "safeer_os"
    private const val KLJUC_PLOSEK = "plosek_naprava"

    /** Zapomnjen zapis, da za vsako izrisano vrstico ne beremo nastavitev znova. */
    private var znanaNaprava: String? = null

    private fun zapis(context: Context): String {
        znanaNaprava?.let { return it }
        val v = try {
            context.applicationContext
                .getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
                .getString(KLJUC_PLOSEK, "").orEmpty()
        } catch (_: Throwable) { "" }
        znanaNaprava = v
        return v
    }

    /**
     * Ali uporabnik na tej napravi res ima igralni plosek?
     *
     * Po seznamu naprav tega ni mogoce povedati: televizijski daljinci se javijo kot
     * "Android Gamepad" in jedro jim pripise tudi tipke A, B, X in Y, ceprav jih na daljincu ni.
     * Vrstica pomoci nasteje prav te gumbe, zato zahtevamo dvoje: da je nekdo s ploscka res
     * pritisnil gumb, ki ga daljinec nima, in da je prav tisti plosek se zdaj prikljucen. Ko ga
     * uporabnik izklopi, vrstica spet izgine - obljubimo samo tisto, kar drzi.
     */
    fun jePriklopljen(context: Context): Boolean {
        val zapomnjen = zapis(context)
        if (zapomnjen.isEmpty()) return false
        return try {
            InputDevice.getDeviceIds().any { id ->
                val d = InputDevice.getDevice(id)
                d != null && !d.isVirtual && d.descriptor == zapomnjen
            }
        } catch (_: Throwable) { false }
    }

    /**
     * Zabelezi plosek, ki je poslal dokazni dogodek. Vrne true samo ob spremembi - takrat zaslon
     * osvezi vrstico pomoci. Navideznih naprav ne stejemo: vbrizgan dogodek ni dokaz, da plosek je.
     */
    fun zabelezi(context: Context, naprava: InputDevice?): Boolean {
        if (naprava == null || naprava.isVirtual) return false
        val opis = naprava.descriptor.orEmpty()
        if (opis.isEmpty() || zapis(context) == opis) return false
        znanaNaprava = opis
        try {
            context.applicationContext
                .getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
                .edit().putString(KLJUC_PLOSEK, opis).apply()
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Gumbi, ki jih televizijski daljinec nima: sele ti so dokaz, da je na napravi pravi plosek.
     * A in B sta izpuscena namenoma - nekateri daljinci OK in Nazaj posljeta prav pod tema kodama.
     */
    private val DOKAZ = setOf(
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)

    /** Je ta pritisk dokaz, da je plosek priklopljen? */
    fun jeDokazPloska(dogodek: KeyEvent): Boolean =
        dogodek.action == KeyEvent.ACTION_DOWN && jePlosek(dogodek) && dogodek.keyCode in DOKAZ

    /**
     * Je palica res odklonjena? Dogodek pri mirovanju ni dokaz - poslje ga lahko tudi naprava,
     * ki se le javi kot plosek.
     */
    fun jeOdklon(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        return try {
            kotlin.math.abs(e.getAxisValue(MotionEvent.AXIS_X)) >= MRTVI_KOT ||
                kotlin.math.abs(e.getAxisValue(MotionEvent.AXIS_Y)) >= MRTVI_KOT
        } catch (_: Throwable) { false }
    }

    /**
     * Gumb plosecka v dejanje. Vrne true, kadar smo dogodek porabili - takrat ga zaslon ne dobi se
     * enkrat. [dodatno] dobi prvo besedo: zaslon, ki hoce gumb zase (predvajalnik), ga obdrzi.
     */
    fun tipka(a: Activity, dogodek: KeyEvent, dodatno: (Int) -> Boolean = { false }): Boolean {
        if (!jePlosek(dogodek)) return false
        val koda = dogodek.keyCode
        if (dogodek.action != KeyEvent.ACTION_DOWN) return koda in GUMBI
        // Drzanje gumba ne sme sprozati dejanja znova in znova: A bi tako odprl aplikacijo desetkrat.
        if (dogodek.repeatCount > 0) return koda in GUMBI
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
            // Samo prava palica: smerni krizec (HAT) Android sam prevede v smerne tipke, zato bi
            // ga tu steli dvakrat in bi izbira preskakovala po dve kartici.
            val x = os(e, MotionEvent.AXIS_X)
            val y = os(e, MotionEvent.AXIS_Y)
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

        private fun os(e: MotionEvent, glavnaOs: Int): Float {
            val v = e.getAxisValue(glavnaOs)
            return if (kotlin.math.abs(v) >= MRTVI_KOT) v else 0f
        }
    }
}
