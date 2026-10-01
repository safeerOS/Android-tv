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

    /** Zavihki spodnje vrstice (Safeer Predvajalnik na dotik, v slogu VLC). */
    enum class Zavihek { DOMOV, VIDEO, GLASBA, V_ZIVO, BRSKAJ }

    private val meni = LinearLayout(dejavnost)
    /** Meni se da podrsati: na telefonu lezece (nizek zaslon) sicer spodnje postavke niso dosegljive. */
    private val drsnik = android.widget.ScrollView(dejavnost).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val besedila = ArrayList<View>()
    private val postavke = LinkedHashMap<Razdelek, View>()
    private var zadnjiFokusVsebine: View? = null

    /** Naprava z dotikom: vrstico skrije poteg proti levemu robu, prikaze poteg z roba ali rocaj.
     *  TV (daljinec): kot stranske vrstice brskalnikov - v vsebini skrcena na ikone, v meniju razsirjena. */
    private val dotik = !dejavnost.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    private val shramba = dejavnost.getSharedPreferences("safeer_os_vrstica", android.content.Context.MODE_PRIVATE)
    private var skrita = dotik && shramba.getBoolean("skrita", false)
    private var skrcena = false
    private val rocaj = View(dejavnost)
    private var zacetekX = 0f
    private var zacetekY = 0f
    private var poteg = false
    private var prevzet = false

    /**
     * Safeer Predvajalnik na telefonu/tablici: namesto leve vrstice spodnja vrstica zavihkov, kot jo
     * poznajo uporabniki VLC in drugih predvajalnikov (lastnik, 1. 10. 2026: "uporabniku bolj domace").
     */
    val spodnja = dotik && si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
    private val zavihki = LinkedHashMap<Zavihek, LinearLayout>()
    /** Zaslon, ki zna zavihek pokazati sam (GlasbaActivity), vrne true; sicer odpremo GlasbaActivity. */
    var naZavihek: ((Zavihek) -> Boolean)? = null

    val aktivnaPostavka: View get() = (postavke[aktivna] ?: postavke.values.firstOrNull() ?: zavihki.values.first())

    init {
        orientation = HORIZONTAL
        setBackgroundColor(dejavnost.osBarva(R.color.os_ozadje))
        if (spodnja) zgradiSpodnjo() else zgradiNormalno()
    }

    private fun zgradiSpodnjo() {
        orientation = VERTICAL
        (vsebina.parent as? ViewGroup)?.removeView(vsebina)
        addView(vsebina, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(View(dejavnost).apply { setBackgroundColor(dejavnost.osBarva(R.color.os_crta)) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val vrsta = LinearLayout(dejavnost).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(dejavnost.osBarva(R.color.os_meni_ozadje))
        }
        fun zavihek(z: Zavihek, slika: Int, niz: Int) {
            val pogled = LinearLayout(dejavnost).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                id = View.generateViewId(); isFocusable = true; isClickable = true
                setPadding(0, dp(6), 0, dp(6))
                contentDescription = dejavnost.getString(niz)
                // Izbrani zavihek: kapsula za ikono (kot pri sodobnih predvajalnikih), ne cel blok.
                addView(android.widget.FrameLayout(dejavnost).apply {
                    addView(ikona(slika, 24, R.color.os_umirjeno), android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }, LayoutParams(dp(60), dp(30)))
                addView(besedilo(12f, R.color.os_umirjeno).apply {
                    text = dejavnost.getString(niz); maxLines = 1; gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2), dp(3), dp(2), 0)
                }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener { izberiZavihek(z) }
            }
            zavihki[z] = pogled
            vrsta.addView(pogled, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        zavihek(Zavihek.DOMOV, R.drawable.os_ikona_domov, R.string.os_zavihek_domov)
        zavihek(Zavihek.VIDEO, R.drawable.os_ikona_video, R.string.os_zavihek_video)
        zavihek(Zavihek.GLASBA, R.drawable.os_ikona_glasba, R.string.os_zavihek_glasba)
        zavihek(Zavihek.V_ZIVO, R.drawable.os_ikona_radio, R.string.os_zavihek_v_zivo)
        zavihek(Zavihek.BRSKAJ, R.drawable.os_ikona_mapa, R.string.os_zavihek_brskaj)
        addView(vrsta, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        oznaci(if (aktivna == Razdelek.DATOTEKE) Zavihek.BRSKAJ else null)
        nastaviPrehode(dejavnost)
    }

    /** Oznaci aktivni zavihek (barva Safeer, krepko); null = noben (npr. Nastavitve, Iskanje). */
    fun oznaci(z: Zavihek?) {
        zavihki.forEach { (k, v) ->
            val da = k == z
            val barva = dejavnost.getColor(if (da) R.color.os_mint else R.color.os_umirjeno)
            v.isSelected = da
            (v.getChildAt(0) as? android.widget.FrameLayout)?.let { okvir ->
                okvir.background = if (!da) null else android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat(); setColor((dejavnost.getColor(R.color.os_mint) and 0x00FFFFFF) or 0x33000000) }
                (okvir.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(barva)
            }
            (v.getChildAt(1) as? TextView)?.apply {
                setTextColor(barva)
                typeface = Typeface.create(if (da) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            }
        }
    }

    private fun izberiZavihek(z: Zavihek) {
        if (naZavihek?.invoke(z) == true) { oznaci(z); return }
        try {
            dejavnost.startActivity(Intent(dejavnost, GlasbaActivity::class.java)
                .putExtra(GlasbaActivity.ZAVIHEK, z.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            if (dejavnost !is GlasbaActivity) dejavnost.finish()
        } catch (_: Throwable) {
            Toast.makeText(dejavnost, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show()
        }
    }

    private fun zgradiNormalno() {
        zgradiMeni()
        // Rocaj na levem robu, ko je vrstica skrita: tanek zelen jezicek (dotik ali poteg ga odpre).
        rocaj.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0x9957D6AD.toInt()) }
        )).apply { setLayerInset(0, dp(6), 0, dp(6), 0); setLayerGravity(0, Gravity.CENTER); setLayerSize(0, dp(6), dp(72)) }
        rocaj.contentDescription = dejavnost.getString(R.string.os_vrstica_pokazi)
        rocaj.setOnClickListener { nastaviSkrito(false) }
        addView(rocaj, LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
        drsnik.addView(meni, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(drsnik, LayoutParams(dejavnost.resources.getDimensionPixelSize(R.dimen.os_meni_sirina),
            ViewGroup.LayoutParams.MATCH_PARENT))
        (vsebina.parent as? ViewGroup)?.removeView(vsebina)
        addView(vsebina, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        prilagodiSirino()
        uveljaviSkrito()
        nastaviPrehode(dejavnost)
    }

    private fun uveljaviSkrito() {
        if (spodnja) return
        drsnik.visibility = if (skrita) View.GONE else View.VISIBLE
        rocaj.visibility = if (skrita) View.VISIBLE else View.GONE
    }

    /** Skrij ali pokazi vrstico (na dotik); izbira ostane zapomnjena za vse zaslone Safeer OS. */
    fun nastaviSkrito(da: Boolean) {
        if (!dotik || spodnja || skrita == da) return
        skrita = da
        shramba.edit().putBoolean("skrita", da).apply()
        android.transition.TransitionManager.beginDelayedTransition(this)
        uveljaviSkrito()
    }

    /** Poteg prestrezemo ze v dispatchTouchEvent: otroci (seznami, WebView) z
     *  requestDisallowInterceptTouchEvent sicer onInterceptTouchEvent izklopijo. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!dotik || spodnja) return super.dispatchTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                zacetekX = e.x; zacetekY = e.y; prevzet = false
                // Skrij: poteg se zacne na vrstici. Pokazi: poteg z levega roba zaslona (ali dotik rocaja).
                poteg = if (skrita) e.x < dp(28) else e.x < drsnik.right
            }
            android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP -> if (poteg && !prevzet) {
                val dx = e.x - zacetekX; val dy = e.y - zacetekY
                if (kotline(dx, dy)) {
                    prevzet = true; poteg = false
                    val preklic = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(preklic); preklic.recycle()
                    nastaviSkrito(dx < 0)
                    return true
                }
            }
        }
        if (prevzet) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) prevzet = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    private fun kotline(dx: Float, dy: Float): Boolean =
        kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(dx) > 1.5f * kotlin.math.abs(dy) && ((dx < 0) != skrita)

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
        // TV: v vsebini skrcena na ikone (vec prostora), ob vstopu v meni razsirjena z imeni.
        if (!dotik && novi != null) {
            val vMeniju = jePotomec(meni, novi)
            if (skrcena == vMeniju) { skrcena = !vMeniju; prilagodiSirino() }
        }
    }

    /** Poklice jo Activity ob spremembi velikosti, kadar manifest zaslona ne ustvari znova. */
    fun prilagodiSirino() {
        if (spodnja) return
        val ozek = dejavnost.resources.configuration.screenWidthDp < 600 || skrcena
        drsnik.layoutParams = (drsnik.layoutParams as? LayoutParams ?: LayoutParams(0, -1)).apply {
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
        meni.setBackgroundColor(dejavnost.osBarva(R.color.os_meni_ozadje))

        val znak = LinearLayout(dejavnost).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(14))
        }
        znak.addView(ikona(R.drawable.os_znak, 32))
        val ime = LinearLayout(dejavnost).apply { orientation = VERTICAL; setPadding(dp(10), 0, 0, 0) }
        // Samostojni Safeer Predvajalnik se predstavi s svojim imenom, ne kot Safeer OS.
        val jePredvajalnik = si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
        ime.addView(besedilo(18f, R.color.os_besedilo, true).apply {
            text = dejavnost.getString(if (jePredvajalnik) R.string.os_ime_predvajalnik else R.string.os_app_name)
        })
        ime.addView(besedilo(11f, R.color.os_umirjeno).apply {
            text = dejavnost.getString(if (jePredvajalnik) R.string.os_podnaslov_predvajalnik else R.string.os_podnaslov_app); maxLines = 2
        })
        znak.addView(ime)
        besedila.add(ime)
        meni.addView(znak)

        // Safeer Predvajalnik (samostojna aplikacija): samo, kar spada k predvajalniku - mediji, naprave
        // (datoteke z racunalnikov prek Safeer Linka), datoteke in nastavitve; ostalo je Safeer OS.
        val samoPredvajalnik = si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
        if (!samoPredvajalnik) dodaj(Razdelek.DOMOV, R.drawable.os_ikona_domov, R.string.os_meni_domov)
        dodaj(Razdelek.MEDIJI, R.drawable.os_ikona_glasba, R.string.os_mediji_kartica)
        dodaj(Razdelek.NAPRAVE, R.drawable.os_ikona_link, R.string.os_meni_naprave)
        if (!samoPredvajalnik) dodaj(Razdelek.SPOROCILA, R.drawable.os_ikona_sporocila, R.string.os_meni_sporocila)
        if (!samoPredvajalnik) dodaj(Razdelek.PROGRAMI, R.drawable.os_ikona_aplikacije, R.string.os_meni_aplikacije)
        dodaj(Razdelek.DATOTEKE, R.drawable.os_ikona_datoteke, R.string.os_meni_datoteke)
        if (!samoPredvajalnik) dodaj(Razdelek.SPLET, R.drawable.os_ikona_splet, R.string.os_meni_splet)
        if (!samoPredvajalnik) dodaj(Razdelek.ZAPISKI, R.drawable.os_ikona_zapiski, R.string.os_meni_zapiski)
        dodaj(Razdelek.NASTAVITVE, R.drawable.os_ikona_nastavitve, R.string.os_meni_nastavitve)

        val seznam = postavke.values.toList()
        seznam.forEachIndexed { i, pogled ->
            pogled.nextFocusUpId = seznam[if (i == 0) 0 else i - 1].id
            pogled.nextFocusDownId = seznam[if (i == seznam.lastIndex) i else i + 1].id
            pogled.nextFocusLeftId = pogled.id
        }

        meni.addView(View(dejavnost), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (dotik) {
            val skrij = LinearLayout(dejavnost).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_meni_postavka)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = dejavnost.getString(R.string.os_vrstica_skrij)
                addView(ikona(R.drawable.os_ikona_skrij_vrstico, 22, R.color.os_umirjeno))
                addView(besedilo(13f, R.color.os_umirjeno).apply {
                    text = dejavnost.getString(R.string.os_vrstica_skrij); maxLines = 1; setPadding(dp(12), 0, 0, 0)
                    besedila.add(this)
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { nastaviSkrito(true) }
            }
            meni.addView(skrij, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
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
        odpriRazdelek(razdelek)
        // Za Android pod 14 (novejsi uporabi overrideActivityTransition iz nastaviPrehode).
        @Suppress("DEPRECATION") dejavnost.overridePendingTransition(R.anim.os_prehod_noter, R.anim.os_prehod_ostane)
    }

    private fun odpriRazdelek(razdelek: Razdelek) {
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
        /** Odpiranje in zapiranje zaslonov Safeer OS s kratkim prelivom namesto sistemske animacije okna. */
        fun nastaviPrehode(dejavnost: Activity) {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                dejavnost.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.os_prehod_noter, R.anim.os_prehod_ostane)
                dejavnost.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.os_prehod_noter, R.anim.os_prehod_ostane)
            }
        }

        fun ovij(dejavnost: Activity, vsebina: View, aktivna: Razdelek) =
            StranskaVrstica(dejavnost, vsebina, aktivna)

        fun ovij(dejavnost: Activity, postavitev: Int, aktivna: Razdelek): StranskaVrstica =
            ovij(dejavnost, dejavnost.layoutInflater.inflate(postavitev, null, false), aktivna)
    }
}
