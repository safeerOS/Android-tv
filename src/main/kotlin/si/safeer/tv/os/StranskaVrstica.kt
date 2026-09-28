package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R

/**
 * Enotna leva navigacija Safeer OS. Pogled ovije ze zgrajeno vsebino zaslona, zato dejavnostim ni
 * treba podvajati menija ali spreminjati njihovih obstojecih postavitev.
 */
class StranskaVrstica private constructor(
    private val dejavnost: Activity,
    val vsebina: View,
    val aktivna: Razdelek
) : LinearLayout(dejavnost), ViewTreeObserver.OnGlobalFocusChangeListener {

    enum class Razdelek { DOMOV, MEDIJI, NAPRAVE, SPOROCILA, PROGRAMI, DATOTEKE, SPLET, ZAPISKI, NASTAVITVE }

    private val meni = LinearLayout(dejavnost)
    private val besedila = ArrayList<View>()
    private val postavke = LinkedHashMap<Razdelek, View>()
    private var zadnjiFokusVsebine: View? = null

    val aktivnaPostavka: View get() = postavke.getValue(aktivna)

    init {
        orientation = HORIZONTAL
        setBackgroundColor(dejavnost.getColor(R.color.os_ozadje))
        zgradiMeni()
        addView(meni, LayoutParams(dejavnost.resources.getDimensionPixelSize(R.dimen.os_meni_sirina),
            ViewGroup.LayoutParams.MATCH_PARENT))
        (vsebina.parent as? ViewGroup)?.removeView(vsebina)
        addView(vsebina, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        prilagodiSirino()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(this)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onGlobalFocusChanged(stari: View?, novi: View?) {
        if (novi != null && jePotomec(vsebina, novi)) zadnjiFokusVsebine = novi
    }

    /** Poklice jo Activity ob spremembi velikosti, kadar manifest zaslona ne ustvari znova. */
    fun prilagodiSirino() {
        val ozek = dejavnost.resources.configuration.screenWidthDp < 600
        meni.layoutParams = (meni.layoutParams as? LayoutParams ?: LayoutParams(0, -1)).apply {
            width = if (ozek) dp(68) else dejavnost.resources.getDimensionPixelSize(R.dimen.os_meni_sirina)
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        meni.setPadding(dp(if (ozek) 10 else 16), dp(24), dp(if (ozek) 10 else 12), dp(16))
        besedila.forEach { it.visibility = if (ozek) View.GONE else View.VISIBLE }
    }

    /**
     * LEVO na levem robu vsebine odpre aktivno postavko; DESNO iz menija se vrne na nazadnje
     * uporabljeni element vsebine. Premikanje znotraj vrstic in mrez ostane nespremenjeno.
     */
    fun prestrezi(dogodek: KeyEvent): Boolean {
        if (dogodek.action != KeyEvent.ACTION_DOWN) return false
        val fokus = dejavnost.currentFocus ?: return false
        if (dogodek.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && jePotomec(meni, fokus)) {
            fokusVsebine()
            return true
        }
        if (dogodek.keyCode != KeyEvent.KEYCODE_DPAD_LEFT || !jePotomec(vsebina, fokus)) return false
        val levo = fokus.focusSearch(View.FOCUS_LEFT)
        if (levo == null || levo === fokus || jePotomec(meni, levo)) {
            aktivnaPostavka.requestFocus()
            return true
        }
        return false
    }

    fun fokusVsebine() {
        val cilj = zadnjiFokusVsebine?.takeIf { it.isShown && it.isFocusable }
            ?: prviFokus(vsebina)
        cilj?.requestFocus()
    }

    private fun zgradiMeni() {
        meni.orientation = VERTICAL
        meni.setBackgroundColor(dejavnost.getColor(R.color.os_meni_ozadje))

        val znak = LinearLayout(dejavnost).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(14))
        }
        znak.addView(ikona(R.drawable.os_znak, 32))
        val ime = LinearLayout(dejavnost).apply { orientation = VERTICAL; setPadding(dp(10), 0, 0, 0) }
        ime.addView(besedilo(18f, R.color.os_besedilo, true).apply { text = dejavnost.getString(R.string.os_app_name) })
        ime.addView(besedilo(11f, R.color.os_umirjeno).apply {
            text = dejavnost.getString(R.string.os_podnaslov_app); maxLines = 2
        })
        znak.addView(ime)
        besedila.add(ime)
        meni.addView(znak)

        dodaj(Razdelek.DOMOV, R.drawable.os_ikona_domov, R.string.os_meni_domov)
        dodaj(Razdelek.MEDIJI, R.drawable.os_ikona_glasba, R.string.os_mediji_kartica)
        dodaj(Razdelek.NAPRAVE, R.drawable.os_ikona_link, R.string.os_meni_naprave)
        dodaj(Razdelek.SPOROCILA, R.drawable.os_ikona_sporocila, R.string.os_meni_sporocila)
        dodaj(Razdelek.PROGRAMI, R.drawable.os_ikona_aplikacije, R.string.os_meni_aplikacije)
        dodaj(Razdelek.DATOTEKE, R.drawable.os_ikona_datoteke, R.string.os_meni_datoteke)
        dodaj(Razdelek.SPLET, R.drawable.os_ikona_splet, R.string.os_meni_splet)
        dodaj(Razdelek.ZAPISKI, R.drawable.os_ikona_zapiski, R.string.os_meni_zapiski)
        dodaj(Razdelek.NASTAVITVE, R.drawable.os_ikona_nastavitve, R.string.os_meni_nastavitve)

        val seznam = postavke.values.toList()
        seznam.forEachIndexed { i, pogled ->
            pogled.nextFocusUpId = seznam[if (i == 0) 0 else i - 1].id
            pogled.nextFocusDownId = seznam[if (i == seznam.lastIndex) i else i + 1].id
            pogled.nextFocusLeftId = pogled.id
        }

        meni.addView(View(dejavnost), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        meni.addView(besedilo(11f, R.color.os_umirjeno).apply {
            text = dejavnost.getString(R.string.os_poganja); setPadding(dp(4), 0, 0, 0); besedila.add(this)
        })
        val link = LinearLayout(dejavnost).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), 0, 0)
        }
        link.addView(ikona(R.drawable.os_ikona_link, 16, R.color.os_mint))
        link.addView(besedilo(13f, R.color.os_mint, true).apply {
            text = dejavnost.getString(R.string.os_link); setPadding(dp(6), 0, 0, 0); besedila.add(this)
        })
        meni.addView(link)
    }

    private fun dodaj(razdelek: Razdelek, slika: Int, niz: Int) {
        val pogled = LinearLayout(dejavnost).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            id = View.generateViewId(); isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_meni_postavka)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(ikona(slika, 22, R.color.os_mint))
            addView(besedilo(14f, R.color.os_besedilo, true).apply {
                text = dejavnost.getString(niz); maxLines = 1; setPadding(dp(12), 0, 0, 0)
                setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                besedila.add(this)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isActivated = razdelek == aktivna
            isSelected = razdelek == aktivna
            setOnClickListener {
                if (razdelek == aktivna) fokusVsebine() else odpri(razdelek)
            }
        }
        postavke[razdelek] = pogled
        meni.addView(pogled, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        })
    }

    private fun odpri(razdelek: Razdelek) {
        val namera = when (razdelek) {
            Razdelek.DOMOV -> Intent(dejavnost, DomovActivity::class.java)
            Razdelek.MEDIJI -> GlasbaStoritev.namenKartice(dejavnost)
            Razdelek.NAPRAVE -> Intent(dejavnost, NapraveActivity::class.java)
            Razdelek.SPOROCILA -> Intent(dejavnost, SporocilaActivity::class.java)
            Razdelek.PROGRAMI -> Intent(dejavnost, AplikacijeHostaActivity::class.java)
                .putExtra(AplikacijeHostaActivity.EXTRA_VIR, "vse")
            Razdelek.DATOTEKE -> Intent(dejavnost, DatotekeActivity::class.java)
            Razdelek.SPLET -> Brskalnik.namera(dejavnost)
            Razdelek.ZAPISKI -> Intent(dejavnost, ZapiskiActivity::class.java)
            Razdelek.NASTAVITVE -> Intent(dejavnost, NastavitveActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).also {
            // Vgrajeni brskalnik je singleTask z drugo afiniteto; iz njega se vrnemo v obstojeco
            // nalogo Safeer OS, ne ustvarimo novega Domov ob brskalniku.
            if (dejavnost !is OsActivity) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            dejavnost.startActivity(namera)
            if (dejavnost !is DomovActivity) dejavnost.finish()
        } catch (_: Throwable) {
            Toast.makeText(dejavnost, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ikona(vir: Int, velikost: Int, barva: Int? = null) = ImageView(dejavnost).apply {
        setImageResource(vir); scaleType = ImageView.ScaleType.FIT_CENTER
        if (barva != null) imageTintList = ColorStateList.valueOf(dejavnost.getColor(barva))
        layoutParams = LayoutParams(dp(velikost), dp(velikost))
    }

    private fun besedilo(velikost: Float, barva: Int, krepko: Boolean = false) = TextView(dejavnost).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, velikost)
        setTextColor(dejavnost.getColor(barva))
        if (krepko) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
        dejavnost.resources.displayMetrics).toInt()

    private fun prviFokus(pogled: View): View? {
        if (pogled.visibility != View.VISIBLE) return null
        if (pogled.isFocusable && pogled.isEnabled) return pogled
        val skupina = pogled as? ViewGroup ?: return null
        for (i in 0 until skupina.childCount) prviFokus(skupina.getChildAt(i))?.let { return it }
        return null
    }

    private fun jePotomec(stars: View, otrok: View): Boolean {
        var trenutni: View? = otrok
        while (trenutni != null) {
            if (trenutni === stars) return true
            trenutni = trenutni.parent as? View
        }
        return false
    }

    companion object {
        fun ovij(dejavnost: Activity, vsebina: View, aktivna: Razdelek) =
            StranskaVrstica(dejavnost, vsebina, aktivna)

        fun ovij(dejavnost: Activity, postavitev: Int, aktivna: Razdelek): StranskaVrstica =
            ovij(dejavnost, dejavnost.layoutInflater.inflate(postavitev, null, false), aktivna)
    }
}
