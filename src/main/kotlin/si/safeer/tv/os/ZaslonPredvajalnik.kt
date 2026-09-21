package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Predvajalnik z racunalnika (VLC, Celluloid, Rhythmbox ...) upravljan kot predvajalnik, ne kot namizje.
 *
 * Daljinec ima smerne tipke in OK, predvajalnik pa je narejen za misko in tipkovnico. Zato tu OK
 * pomeni predvajaj/pavza, levo in desno previjeta, gor in dol pokazeta pas - racunalnik ukaz izvede
 * prek MPRIS, ne glede na to, katere tipke program pricakuje.
 *
 * Pas za predvajanje (naslov, cas, napredek) narise televizor sam, iz stanja, ki ga sporoca racunalnik:
 * program da vsebino, Safeer pa upravljanje, prilagojeno daljincu. Pas je ostrejsi od slike in je
 * enak v vseh predvajalnikih.
 */
class ZaslonPredvajalnik(private val a: Activity, koren: FrameLayout, private val poslji: (JSONObject) -> Unit) {

    private val glavna = Handler(Looper.getMainLooper())
    private val gostota = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * gostota).toInt()

    private val naslov = besedilo(20f, a.getColor(R.color.os_besedilo), true)
    private val izvajalec = besedilo(14f, a.getColor(R.color.os_umirjeno))
    private val znak = besedilo(26f, a.getColor(R.color.os_besedilo), true)
    private val zdaj = besedilo(14f, a.getColor(R.color.os_besedilo))
    private val skupaj = besedilo(14f, a.getColor(R.color.os_umirjeno))
    private val vrstica = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
        progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
    }
    private val pas = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = 18 * gostota
            setColor(Color.parseColor("#E60B1220"))
        }
        addView(naslov)
        addView(izvajalec)
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(znak, LinearLayout.LayoutParams(dp(40), -2))
            addView(zdaj, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
            addView(vrstica, LinearLayout.LayoutParams(0, dp(6), 1f))
            addView(skupaj, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        })
        visibility = View.GONE
    }

    init {
        koren.addView(pas, FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(48), 0, dp(48), dp(32))
        })
    }

    private var polozaj = 0
    private var dolzina = 0
    private var predvaja = true
    private var imaStanje = false
    private val skrij = Runnable { pas.visibility = View.GONE }

    private fun besedilo(vel: Float, barva: Int, krepko: Boolean = false) = TextView(a).apply {
        textSize = vel
        setTextColor(barva)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (krepko) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    val jeViden: Boolean get() = pas.visibility == View.VISIBLE

    fun skrij() { glavna.removeCallbacks(skrij); pas.visibility = View.GONE }

    /** Pokaze pas; sam izgine, ce uporabnik nekaj casa ne pritisne nicesar. */
    fun pokazi(ms: Long = PAS_MS) {
        osvezi()
        pas.visibility = View.VISIBLE
        glavna.removeCallbacks(skrij)
        glavna.postDelayed(skrij, ms)
    }

    /** Stanje predvajalnika od racunalnika (null: predvajalnik ne pove nicesar - samo znak). */
    fun stanje(o: JSONObject?) {
        imaStanje = o != null
        if (o != null) {
            naslov.text = o.optString("naslov")
            izvajalec.text = o.optString("izvajalec")
            dolzina = o.optInt("dolzina")
            polozaj = o.optInt("polozaj")
            predvaja = o.optBoolean("predvaja", predvaja)
        }
        if (jeViden) osvezi()
    }

    private fun osvezi() {
        znak.text = if (predvaja) "❚❚" else "▶"
        naslov.visibility = if (naslov.text.isNullOrBlank()) View.GONE else View.VISIBLE
        izvajalec.visibility = if (izvajalec.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val znanCas = imaStanje && (dolzina > 0 || polozaj > 0)
        zdaj.visibility = if (znanCas) View.VISIBLE else View.GONE
        skupaj.visibility = if (dolzina > 0) View.VISIBLE else View.GONE
        vrstica.visibility = if (dolzina > 0) View.VISIBLE else View.INVISIBLE
        zdaj.text = cas(polozaj)
        skupaj.text = cas(dolzina)
        vrstica.progress = if (dolzina > 0) (polozaj * 1000L / dolzina).toInt().coerceIn(0, 1000) else 0
    }

    private fun ukaz(ukaz: String) = poslji(JSONObject().put("vrsta", "medij").put("ukaz", ukaz))

    /** Previjanje: takoj premaknemo tudi pas, da uporabnik vidi, kam gre, preden odgovori racunalnik. */
    private fun previj(s: Int) {
        poslji(JSONObject().put("vrsta", "medij").put("ukaz", "premik").put("s", s))
        polozaj = (polozaj + s).coerceIn(0, if (dolzina > 0) dolzina else Int.MAX_VALUE)
        pokazi()
    }

    /** Tipka daljinca ali plosecka v nacinu predvajalnika; vrne true, ce jo je porabil. */
    fun tipka(koda: Int, ponovitev: Int): Boolean {
        when (koda) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (ponovitev == 0) { ukaz("predvajaj_pavza"); predvaja = !predvaja; pokazi() }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { ukaz("predvajaj"); predvaja = true; pokazi() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { ukaz("pavza"); predvaja = false; pokazi() }
            KeyEvent.KEYCODE_MEDIA_STOP -> { ukaz("ustavi"); predvaja = false; pokazi() }
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (ponovitev == 0) { ukaz("naprej"); pokazi() }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (ponovitev == 0) { ukaz("nazaj"); pokazi() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                // Drzanje previja naprej: vsak cetrti ponovljeni pritisk, po nekaj sekundah v vecjih korakih.
                if (ponovitev == 0 || ponovitev % 4 == 0) {
                    val korak = if (ponovitev >= 40) PREVIJ_DOLGO_S else PREVIJ_S
                    previj(if (koda == KeyEvent.KEYCODE_DPAD_LEFT || koda == KeyEvent.KEYCODE_MEDIA_REWIND) -korak else korak)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> pokazi()
            else -> return false
        }
        return true
    }

    /** Tipke, ki jih nacin predvajalnika porabi (tudi njihov dvig ne gre naprej). */
    fun jeNjegova(koda: Int): Boolean = koda in NJEGOVE

    fun ustavi() = glavna.removeCallbacksAndMessages(null)

    companion object {
        const val PAS_MS = 4_000L
        const val PREVIJ_S = 10
        const val PREVIJ_DOLGO_S = 30
        private val NJEGOVE = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO)

        fun cas(sekund: Int): String {
            val s = sekund.coerceAtLeast(0)
            return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }
}
