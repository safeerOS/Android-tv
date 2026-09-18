package si.safeer.tv.os

import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import si.safeer.tv.R

/**
 * Preizkusi tipke: kaj televizor zares dobi od igralnega plosecka in kaj bi od tega poslal
 * racunalniku.
 *
 * Zakaj obstaja: kadar gumb v igri ne naredi nicesar, je vprasanje vedno isto - ali ga televizor
 * sploh dobi, ali ga znamo poslati naprej, ali ga racunalnik zavrne. Tu se to vidi na prvi pogled,
 * brez ugibanja in brez razvijalskih orodij. Ime naprave beremo od Androida; znamke ne predpostavimo.
 *
 * Nicesar ne posilja racunalniku - to je merilnik, ne upravljanje.
 */
class PlosekActivity : OsActivity(), InputManager.InputDeviceListener {

    private lateinit var naprave: TextView
    private lateinit var zadnja: TextView
    private lateinit var tipke: TextView
    private lateinit var osi: TextView
    private val drzane = LinkedHashSet<String>()
    private val zadnjeOsi = LinkedHashMap<String, Float>()
    private val upravitelj by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun besedilo(velikost: Float, krepko: Boolean = false): TextView {
            val t = TextView(this)
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, velikost)
            t.setTextColor(Color.WHITE)
            if (krepko) t.setTypeface(Typeface.DEFAULT_BOLD)
            t.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            return t
        }

        val stolpec = LinearLayout(this)
        stolpec.orientation = LinearLayout.VERTICAL
        val rob = (48 * d).toInt()
        stolpec.setPadding(rob, (32 * d).toInt(), rob, rob)

        val naslov = besedilo(28f, true)
        naslov.text = getString(R.string.os_plosek_preizkus)
        naslov.gravity = Gravity.START
        stolpec.addView(naslov)

        val opis = besedilo(15f)
        opis.text = getString(R.string.os_plosek_opis)
        opis.alpha = 0.75f
        stolpec.addView(opis)

        naprave = besedilo(16f)
        zadnja = besedilo(20f, true)
        tipke = besedilo(16f)
        osi = besedilo(16f)
        osi.setTypeface(Typeface.MONOSPACE)
        for (v in listOf(naprave, zadnja, tipke, osi)) stolpec.addView(v)

        val drsnik = ScrollView(this)
        drsnik.addView(stolpec, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        drsnik.setBackgroundColor(Color.parseColor("#0b1a14"))
        setContentView(drsnik)
        // Zaslon mora slisati tipke, zato fokus ostane tu.
        drsnik.isFocusableInTouchMode = true
        drsnik.requestFocus()
        narisi()
    }

    override fun onStart() {
        super.onStart()
        upravitelj.registerInputDeviceListener(this, null)
        narisi()
    }

    override fun onStop() {
        upravitelj.unregisterInputDeviceListener(this)
        super.onStop()
    }

    /** Ob izgubi fokusa ali odklopu plosecka pozabimo, kaj je bilo drzano - drugace ostane "prilepljeno". */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            drzane.clear()
            zadnjeOsi.clear()
            narisi()
        }
    }

    // ------------------------------------------------------------------ naprave

    private fun ploscki(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull {
        InputDevice.getDevice(it)
    }.filter {
        it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    override fun onInputDeviceAdded(deviceId: Int) = narisi()
    override fun onInputDeviceRemoved(deviceId: Int) { drzane.clear(); zadnjeOsi.clear(); narisi() }
    override fun onInputDeviceChanged(deviceId: Int) = narisi()

    private fun narisi() {
        val seznam = ploscki()
        naprave.text = if (seznam.isEmpty()) getString(R.string.os_plosek_ni)
        else seznam.joinToString("\n") { n ->
            val osi = n.motionRanges.joinToString(", ") { MotionEvent.axisToString(it.axis) }
            getString(R.string.os_plosek_naprava, n.name, osi.ifBlank { "-" })
        }
        tipke.text = getString(R.string.os_plosek_drzane,
            if (drzane.isEmpty()) "-" else drzane.joinToString(", "))
        osi.text = if (zadnjeOsi.isEmpty()) getString(R.string.os_plosek_osi, "-")
        else getString(R.string.os_plosek_osi, zadnjeOsi.entries.joinToString("\n   ") {
            "%-12s %6.2f".format(it.key, it.value)
        })
    }

    // ------------------------------------------------------------------ dogodki

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        pokaziTipko(keyCode, event, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        pokaziTipko(keyCode, event, false)
        return true
    }

    private fun pokaziTipko(koda: Int, dogodek: KeyEvent?, dol: Boolean) {
        val ime = KeyEvent.keyCodeToString(koda).removePrefix("KEYCODE_")
        val nase = ZaslonVnos.plosekTipka(koda, dol, dogodek)
        val kam = when {
            nase == null -> getString(R.string.os_plosek_ne_poslje)
            nase.optString("vrsta") == "plosek_gumb" ->
                getString(R.string.os_plosek_kot_gumb, nase.optString("gumb"))
            else -> getString(R.string.os_plosek_kot_os, nase.optString("os"))
        }
        zadnja.text = getString(R.string.os_plosek_zadnja, ime, koda, kam)
        if (dol) drzane.add(ime) else drzane.remove(ime)
        narisi()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val odkloni = ZaslonVnos.plosekOdkloni(event)
        if (odkloni.isNotEmpty()) {
            zadnjeOsi.putAll(odkloni)
            narisi()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
