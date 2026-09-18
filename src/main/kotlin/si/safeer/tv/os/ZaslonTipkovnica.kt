package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Navidezna tipkovnica za upravljanje racunalnika z daljincem. Daljinec nima crk, zato jih
 * ponudimo na zaslonu: smerne tipke izbirajo, OK vtipka, Nazaj zapre.
 *
 * Crke gredo v racunalnik kot **besedilo** (isti dogodek kot s prave tipkovnice), posebne tipke
 * pa po imenu (`vracalka`, `vnasalka`, `shrani` ...) - racunalnik jih prevede sam in kar ni na
 * njegovem seznamu dovoljenega, tiho izpade. Slovenske crke so tu, ker brez njih pisanje ni
 * pisanje.
 */
class ZaslonTipkovnica(
    private val context: Context,
    private val koren: LinearLayout,
    private val naBesedilo: (String) -> Unit,
    private val naTipko: (String) -> Unit,
) {

    private var velike = false
    private var simboli = false
    /** Tipkovnica je lahko spodaj ali zgoraj: ne sme zakriti mesta, kamor uporabnik pise. */
    private var zgoraj = false
    private val gostota = context.resources.displayMetrics.density

    /** Prva vrsta je ali stevilke ali simboli; crkovne vrste ostajajo enake. */
    private val STEVILKE = "1234567890"
    private val SIMBOLI = "!?:;\"'()/"
    private val VRSTA1 = "qwertzuiopš"
    private val VRSTA2 = "asdfghjklčž"
    private val VRSTA3 = "yxcvbnm,.-"
    private val SIMBOLI2 = "@#€%&*+=_~"
    private val SIMBOLI3 = "<>[]{}\\|§°"

    val jeOdprta: Boolean get() = koren.visibility == View.VISIBLE

    fun odpri() {
        narisi()
        premakni()
        koren.visibility = View.VISIBLE
        koren.post { koren.getChildAt(1)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() } }
    }

    fun zapri() {
        koren.visibility = View.GONE
    }

    /** Prestavi ploscico gor ali dol; besedilo, ki ga pises, mora ostati vidno. */
    private fun premakni() {
        val lp = koren.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        lp.gravity = (if (zgoraj) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        val rob = (20 * gostota).toInt()
        lp.topMargin = if (zgoraj) rob else 0
        lp.bottomMargin = if (zgoraj) 0 else rob
        koren.layoutParams = lp
    }

    private fun narisi() {
        koren.removeAllViews()
        koren.orientation = LinearLayout.VERTICAL
        vrsta(if (simboli) SIMBOLI else STEVILKE)
        vrsta(if (simboli) SIMBOLI2 else VRSTA1)
        vrsta(if (simboli) SIMBOLI3 else VRSTA2)
        vrsta(VRSTA3)
        smerne()
        ukazi()
    }

    private fun vrsta(znaki: String) {
        val v = LinearLayout(context)
        v.orientation = LinearLayout.HORIZONTAL
        v.gravity = Gravity.CENTER_HORIZONTAL
        for (z in znaki) {
            val znak = if (velike && !simboli) z.uppercaseChar() else z
            v.addView(tipka(znak.toString(), 54) { naBesedilo(znak.toString()) })
        }
        koren.addView(v)
    }

    /**
     * Vrsta za premikanje po besedilu. Smerne tipke daljinca med odprto tipkovnico izbirajo tipke,
     * zato mora biti premikanje kazalca v besedilu tu - sicer uporabnik ne more popraviti besede
     * dve vrstici visje.
     */
    private fun smerne() {
        val v = LinearLayout(context)
        v.orientation = LinearLayout.HORIZONTAL
        v.gravity = Gravity.CENTER_HORIZONTAL
        v.addView(tipka("←", 66) { naTipko("levo") })
        v.addView(tipka("↑", 66) { naTipko("gor") })
        v.addView(tipka("↓", 66) { naTipko("dol") })
        v.addView(tipka("→", 66) { naTipko("desno") })
        v.addView(tipka(context.getString(R.string.os_tipk_zacetek), 84) { naTipko("zacetek") })
        v.addView(tipka(context.getString(R.string.os_tipk_konec), 84) { naTipko("konec") })
        v.addView(tipka(context.getString(R.string.os_tipk_tabulator), 84) { naTipko("tabulator") })
        v.addView(tipka(context.getString(if (zgoraj) R.string.os_tipk_dol else R.string.os_tipk_gor), 104) {
            zgoraj = !zgoraj; premakni(); osveziZnake()
        })
        koren.addView(v)
    }

    /** Spodnja vrsta: velike crke, simboli, presledek, brisanje, nova vrstica in shranjevanje. */
    private fun ukazi() {
        val v = LinearLayout(context)
        v.orientation = LinearLayout.HORIZONTAL
        v.gravity = Gravity.CENTER_HORIZONTAL
        v.addView(tipka(context.getString(if (velike) R.string.os_tipk_male else R.string.os_tipk_velike), 96) {
            velike = !velike; osveziZnake()
        })
        v.addView(tipka(context.getString(if (simboli) R.string.os_tipk_crke else R.string.os_tipk_simboli), 96) {
            simboli = !simboli; osveziZnake()
        })
        v.addView(tipka(context.getString(R.string.os_tipk_presledek), 168) { naBesedilo(" ") })
        v.addView(tipka(context.getString(R.string.os_tipk_vracalka), 110) { naTipko("vracalka") })
        v.addView(tipka(context.getString(R.string.os_tipk_vnasalka), 110) { naTipko("vnasalka") })
        v.addView(tipka(context.getString(R.string.os_tipk_shrani), 110) { naTipko("shrani") })
        v.addView(tipka(context.getString(R.string.os_tipk_zapri), 96) { zapri() })
        koren.addView(v)
    }

    /**
     * Preris ob preklopu velikih crk ali simbolov: fokus mora ostati na istem mestu, sicer
     * uporabnik po vsakem preklopu isce, kje je.
     */
    private fun osveziZnake() {
        val mesto = mestoFokusa()
        narisi()
        koren.post { postaviFokus(mesto) }
    }

    private fun mestoFokusa(): Pair<Int, Int> {
        for (i in 0 until koren.childCount) {
            val v = koren.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until v.childCount) if (v.getChildAt(j).isFocused) return i to j
        }
        return 0 to 0
    }

    private fun postaviFokus(mesto: Pair<Int, Int>) {
        val v = koren.getChildAt(mesto.first) as? LinearLayout ?: return
        val t = v.getChildAt(mesto.second.coerceAtMost(v.childCount - 1)) ?: return
        t.requestFocus()
    }

    private fun tipka(napis: String, sirinaDp: Int, ob: () -> Unit): View {
        val t = TextView(context)
        t.text = napis
        t.gravity = Gravity.CENTER
        t.setTextColor(context.getColor(R.color.os_besedilo))
        t.textSize = if (napis.length > 2) 12f else 17f
        t.setBackgroundResource(R.drawable.os_tipka_velika)
        t.isFocusable = true
        t.isClickable = true
        t.setOnClickListener { ob() }
        // Visina tipke je odmerjena tako, da vseh pet vrst skupaj z robovi ostane na zaslonu
        // 540 dp - spodnja vrsta se je prej odrezala cez rob.
        val lp = LinearLayout.LayoutParams((sirinaDp * gostota).toInt(), (42 * gostota).toInt())
        lp.setMargins((2 * gostota).toInt(), (2 * gostota).toInt(), (2 * gostota).toInt(), (2 * gostota).toInt())
        t.layoutParams = lp
        return t
    }
}
