package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.os.VrsticaPravila.Nacin

/**
 * Enotna leva navigacija Safeer OS. Pogled ovije ze zgrajeno vsebino zaslona, zato dejavnostim ni
 * treba podvajati menija ali spreminjati njihovih obstojecih postavitev.
 *
 * Vrstica vsebini ne jemlje prostora, a je vedno pri roki (lastnik, 19. 10. 2026; pravila v [VrsticaPravila]):
 *  - telefon pokonci (ozko okno): skrita; gumb menija v glavi zaslona ali poteg z levega roba jo odpre cez vsebino,
 *    dotik vsebine, poteg v levo ali Nazaj jo zapre;
 *  - tablica in telefon lezece: tirnica z ikonami, gumb menija na vrhu pokaze imena (izbira je zapomnjena);
 *  - televizor: v vsebini skrcena na ikone, ob fokusu v meniju razsirjena; Nazaj iz vsebine gre v vrstico.
 */
class StranskaVrstica private constructor(
    private val dejavnost: Activity,
    val vsebina: View,
    val aktivna: Razdelek
) : LinearLayout(kontekstTeme(dejavnost)), ViewTreeObserver.OnGlobalFocusChangeListener {

    enum class Razdelek { DOMOV, MEDIJI, NAPRAVE, SPOROCILA, PROGRAMI, DATOTEKE, SPLET, ZAPISKI, NASTAVITVE }

    /** Zavihki spodnje vrstice (Safeer Predvajalnik na dotik, v slogu VLC). */
    enum class Zavihek { DOMOV, VIDEO, GLASBA, V_ZIVO, BRSKAJ }

    private val meni = LinearLayout(context)
    /** Meni se da podrsati: na telefonu lezece (nizek zaslon) sicer spodnje postavke niso dosegljive. */
    private val drsnik = android.widget.ScrollView(context).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val besedila = ArrayList<View>()
    private val postavke = LinkedHashMap<Razdelek, View>()
    private var zadnjiFokusVsebine: View? = null
    /** Zadnji fokus je bil v vsebini (ne v meniju): ce pogled s fokusom izgine, gre fokus spet v vsebino. */
    private var fokusVVsebini = false

    /** Naprava z dotikom (telefon, tablica); sicer televizor z daljincem (tudi preizkusna gradnja »TV nacin«). */
    private val dotik = !Naprava.jeTelevizor(dejavnost)
    private val shramba = dejavnost.getSharedPreferences(SHRAMBA, android.content.Context.MODE_PRIVATE)
    private var nacin = trenutniNacin()
    /** Tirnica: uporabnik je z gumbom menija izbral imena ob ikonah (velja za vse zaslone Safeer OS). */
    private var izbranaImena = shramba.getBoolean(KLJUC_IMENA, false)
    /** Naloga 127: tirnica je na dotik lezece popolnoma skrita (poteg z levega roba jo pokaze); velja za vse zaslone. */
    private var izbranaSkrita = shramba.getBoolean(KLJUC_SKRITA, false)
    private fun lezece(): Boolean =
        dejavnost.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** Tirnica je zdaj skrita (samo na dotik, lezece, v nacinu tirnice). */
    private fun tirnicaSkrita(): Boolean = nacin == Nacin.TIRNICA && dotik && lezece() && izbranaSkrita
    private var predalOdprt = false
    /** Televizor: fokus je v vsebini, vrstica je skrcena na ikone. */
    private var skrcena = false

    /** Vsebina in zastor cez njo (ko je predal odprt; dotik zastora predal zapre). */
    private val okvir = FrameLayout(context)
    private val zastor = View(context)
    /** Gumb menija na vrhu vrstice (dotik): v tirnici pokaze ali skrije imena, v predalu ga zapre. */
    private lateinit var gumbVrstice: ImageView
    /** Gumb menija v glavi vsebine (samo predal): vrstico odpre. Vedno viden, ko je vrstica skrita. */
    private val gumbVsebine = ImageView(context)
    /** Zaslon je sam povedal, pred kateri pogled glave sodi gumb menija ([gumbPred]). */
    private var sidroPred: View? = null
    /** Okrasna ikona glave, ki jo gumb menija nadomesti, dokler je vrstica predal. */
    private var skritoSidro: View? = null
    /** Nazaj je zaprl predal: spust iste tipke ne sme narediti se cesa. */
    private var pogoltniNazaj = false

    private var zacetekX = 0f
    private var zacetekY = 0f
    private var sledim = false
    private var prevzet = false

    /**
     * Safeer Predvajalnik na telefonu/tablici: namesto leve vrstice spodnja vrstica zavihkov, kot jo
     * poznajo uporabniki VLC in drugih predvajalnikov (lastnik, 1. 10. 2026: "uporabniku bolj domace").
     */
    val spodnja = dotik && si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
    private val zavihki = LinkedHashMap<Zavihek, LinearLayout>()
    /** Zaslon, ki zna zavihek pokazati sam (GlasbaActivity), vrne true; sicer odpremo GlasbaActivity. */
    var naZavihek: ((Zavihek) -> Boolean)? = null
    /** Dotik ze odprtega razdelka (npr. Domov na domacem zaslonu): zaslon se vrne na vrh; privzeto fokus v vsebino. */
    var naAktivno: (() -> Unit)? = null

    val aktivnaPostavka: View get() = (postavke[aktivna] ?: postavke.values.firstOrNull() ?: zavihki.values.first())

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.osBarva(R.color.os_ozadje))
        if (spodnja) zgradiSpodnjo() else zgradiNormalno()
    }

    private fun trenutniNacin(): Nacin =
        VrsticaPravila.nacin(dejavnost.resources.configuration.screenWidthDp, daljinec = !dotik)

    private fun zgradiSpodnjo() {
        orientation = VERTICAL
        (vsebina.parent as? ViewGroup)?.removeView(vsebina)
        addView(vsebina, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(View(context).apply { setBackgroundColor(context.osBarva(R.color.os_crta)) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val vrsta = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(context.osBarva(R.color.os_meni_ozadje))
        }
        fun zavihek(z: Zavihek, slika: Int, niz: Int) {
            val pogled = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                id = View.generateViewId(); isFocusable = true; isClickable = true
                setPadding(0, dp(6), 0, dp(6))
                contentDescription = dejavnost.getString(niz)
                // Valovanje ob dotiku (odziv, da je tap zaznan), brez stalnega ozadja.
                TypedValue().also { tv ->
                    if (dejavnost.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
                }
                // Izbrani zavihek: kapsula za ikono (kot pri sodobnih predvajalnikih), ne cel blok.
                addView(FrameLayout(context).apply {
                    addView(ikona(slika, 24, R.color.os_umirjeno), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
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
            val barva = context.osBarva(if (da) R.color.os_mint else R.color.os_umirjeno)
            v.isSelected = da
            (v.getChildAt(0) as? FrameLayout)?.let { okvir ->
                okvir.background = if (!da) null else android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat(); setColor((context.osBarva(R.color.os_mint) and 0x00FFFFFF) or 0x33000000) }
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
        drsnik.setBackgroundColor(context.osBarva(R.color.os_meni_ozadje))
        drsnik.addView(meni, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(drsnik, LayoutParams(dejavnost.resources.getDimensionPixelSize(R.dimen.os_meni_sirina),
            ViewGroup.LayoutParams.MATCH_PARENT))
        // Lastnik 27. 10. 2026: z novim zaganjalnikom (beta) na TV brez stranske vrstice - vsebina dobi celo sirino.
        if (!ZaganjalnikBetaPravila.stranskaVrstica(!dotik, ZaganjalnikBeta.vklopljen(dejavnost))) drsnik.visibility = View.GONE
        (vsebina.parent as? ViewGroup)?.removeView(vsebina)
        okvir.addView(vsebina, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        zastor.setBackgroundColor(0x8C000000.toInt())
        zastor.visibility = View.GONE
        zastor.isClickable = true
        zastor.contentDescription = dejavnost.getString(R.string.os_vrstica_skrij)
        zastor.setOnClickListener { zapriPredal() }
        okvir.addView(zastor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(okvir, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        gumbVsebine.apply {
            setImageResource(R.drawable.os_ikona_meni)
            imageTintList = ColorStateList.valueOf(context.osBarva(R.color.os_besedilo))
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = true; isClickable = true
            contentDescription = dejavnost.getString(R.string.os_vrstica_pokazi)
            tag = OZNAKA_GUMB
            TypedValue().also { tv ->
                if (dejavnost.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
            }
            setOnClickListener { if (tirnicaSkrita()) nastaviTirnico(VrsticaPravila.TIRNICA_IKONE) else odpriPredal() }
        }
        prilagodiSirino()
        nastaviPrehode(dejavnost)
    }

    /** Poteg prestrezemo ze v dispatchTouchEvent: otroci (seznami, WebView) z
     *  requestDisallowInterceptTouchEvent sicer onInterceptTouchEvent izklopijo. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!dotik || spodnja) return super.dispatchTouchEvent(e)
        val g = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zacetekX = e.x; zacetekY = e.y; prevzet = false
                // Predal: odpre ga poteg z levega roba, zapre poteg v levo kjerkoli. Tirnica: poteg po njej pokaze ali skrije imena.
                sledim = when (nacin) {
                    Nacin.PREDAL -> predalOdprt || e.x / g <= VrsticaPravila.ROB_POTEGA_DP
                    // Naloga 127: skrito tirnico pokaze poteg z levega roba.
                    Nacin.TIRNICA -> VrsticaPravila.potegZRobaTirnice(tirnicaSkrita(), e.x / g, drsnik.right / g)
                    Nacin.DALJINEC -> false
                }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (sledim && !prevzet) {
                val dx = (e.x - zacetekX) / g; val dy = (e.y - zacetekY) / g
                val dejanje: (() -> Unit)? = when {
                    nacin == Nacin.PREDAL && !predalOdprt && VrsticaPravila.potegOdpre(zacetekX / g, dx, dy) -> { { odpriPredal() } }
                    nacin == Nacin.PREDAL && predalOdprt && VrsticaPravila.potegZapre(dx, dy) -> { { zapriPredal() } }
                    // Naloga 127: proti sredini za eno stanje vec (skrita -> ikone -> imena), proti robu za eno manj.
                    nacin == Nacin.TIRNICA && VrsticaPravila.potegOdpre(if (tirnicaSkrita()) zacetekX / g else 0f, dx, dy) &&
                        stanjeTirnice() != VrsticaPravila.TIRNICA_IMENA -> { { nastaviTirnico(VrsticaPravila.tirnicaPoPotegu(stanjeTirnice(), true, lezece())) } }
                    nacin == Nacin.TIRNICA && VrsticaPravila.potegZapre(dx, dy) &&
                        VrsticaPravila.tirnicaPoPotegu(stanjeTirnice(), false, lezece()) != stanjeTirnice() -> { { nastaviTirnico(VrsticaPravila.tirnicaPoPotegu(stanjeTirnice(), false, lezece())) } }
                    else -> null
                }
                if (dejanje != null) {
                    prevzet = true; sledim = false
                    val preklic = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(preklic); preklic.recycle()
                    dejanje()
                    return true
                }
            }
        }
        if (prevzet) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) prevzet = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    /** Odpre predal cez vsebino (samo ozko okno na dotik). */
    fun odpriPredal() {
        if (spodnja || nacin != Nacin.PREDAL || predalOdprt) return
        predalOdprt = true
        prilagodiSirino()
        val sirina = drsnik.layoutParams.width.toFloat()
        drsnik.translationX = -sirina
        drsnik.animate().translationX(0f).setDuration(TRAJANJE_MS).start()
        zastor.alpha = 0f
        zastor.animate().alpha(1f).setDuration(TRAJANJE_MS).start()
        // Bralnik zaslona: povemo, da je meni odprt, in ga postavimo na trenutni razdelek.
        aktivnaPostavka.post { aktivnaPostavka.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
        gumbVsebine.contentDescription = dejavnost.getString(R.string.os_vrstica_skrij)
    }

    /** Zapre odprt predal; vrne true, ce je bil odprt (Nazaj je s tem porabljen). */
    fun zapriPredal(animacija: Boolean = true): Boolean {
        if (!predalOdprt) return false
        predalOdprt = false
        gumbVsebine.contentDescription = dejavnost.getString(R.string.os_vrstica_pokazi)
        val koncaj = { if (!predalOdprt) { drsnik.translationX = 0f; prilagodiSirino() } }
        if (!animacija) { drsnik.animate().cancel(); koncaj(); return true }
        drsnik.animate().translationX(-drsnik.width.toFloat()).setDuration(TRAJANJE_MS).withEndAction { koncaj() }.start()
        zastor.animate().alpha(0f).setDuration(TRAJANJE_MS).start()
        return true
    }

    fun jePredalOdprt(): Boolean = predalOdprt

    /** Vrstica je skrit predal (ozko okno na dotik): v glavi zaslona je njen gumb menija. */
    fun jePredal(): Boolean = !spodnja && nacin == Nacin.PREDAL

    private fun stanjeTirnice(): Int = VrsticaPravila.stanjeTirnice(izbranaSkrita, izbranaImena, lezece())

    /** Naloga 127: tirnica skrita / ikone / imena (poteg ali gumb menija); izbira velja za vse zaslone Safeer OS. */
    fun nastaviTirnico(stanje: Int) {
        val skrita = stanje == VrsticaPravila.TIRNICA_SKRITA
        val imena = stanje == VrsticaPravila.TIRNICA_IMENA
        if (skrita == izbranaSkrita && (skrita || imena == izbranaImena)) return
        izbranaSkrita = skrita
        if (!skrita) izbranaImena = imena
        shramba.edit().putBoolean(KLJUC_SKRITA, izbranaSkrita).putBoolean(KLJUC_IMENA, izbranaImena).apply()
        android.transition.TransitionManager.beginDelayedTransition(this)
        prilagodiSirino()
    }

    /** Tirnica: imena ob ikonah (gumb menija ali poteg); izbira velja za vse zaslone Safeer OS. */
    fun nastaviImena(da: Boolean) {
        if (izbranaImena == da) return
        izbranaImena = da
        shramba.edit().putBoolean(KLJUC_IMENA, da).apply()
        android.transition.TransitionManager.beginDelayedTransition(this)
        prilagodiSirino()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(this)
        // Fokus, ki ga zaslon dobi ze v onCreate (pred pripetjem okna), poslusalec ne vidi: meni je ostal razsirjen,
        // ceprav je bil fokus v vsebini, do prvega pritiska na daljinec (TV, Domov in Programi, 18. 10. 2026).
        post { dejavnost.currentFocus?.let { onGlobalFocusChanged(null, it) } }
    }

    /**
     * Izbira imen je skupna vsem zaslonom: ce jo je uporabnik spremenil drugje (Nastavitve, drug zaslon), jo ta
     * zaslon uveljavi, ko spet pride v ospredje - prej je ostal pri stari, dokler se ni na novo odprl.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || spodnja) return
        val imena = shramba.getBoolean(KLJUC_IMENA, false)
        val skrita = shramba.getBoolean(KLJUC_SKRITA, false)
        if (imena != izbranaImena || skrita != izbranaSkrita) { izbranaImena = imena; izbranaSkrita = skrita; prilagodiSirino() }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onGlobalFocusChanged(stari: View?, novi: View?) {
        if (novi != null && jePotomec(vsebina, novi)) zadnjiFokusVsebine = novi
        if (novi != null) fokusVVsebini = jePotomec(vsebina, novi)
        // TV: v vsebini skrcena na ikone (vec prostora), ob vstopu v meni razsirjena z imeni.
        if (!dotik && novi != null) {
            val vMeniju = jePotomec(meni, novi)
            if (skrcena == vMeniju) {
                skrcena = !vMeniju; prilagodiSirino()
                if (android.util.Log.isLoggable(DNEVNIK_MREZE, android.util.Log.DEBUG)) android.util.Log.d(DNEVNIK_MREZE, "stranska vrstica: " + if (skrcena) "skrcena" else "razsirjena")
            }
        }
    }

    /** Zasuk, razdeljen zaslon, zlozljiv telefon: vrstica se prilagodi sama, tudi v dejavnosti, ki tega ne klice. */
    override fun onConfigurationChanged(nova: android.content.res.Configuration?) {
        super.onConfigurationChanged(nova)
        prilagodiSirino()
    }

    /** Poklice jo Activity ob spremembi velikosti, kadar manifest zaslona ne ustvari znova. */
    fun prilagodiSirino() {
        if (spodnja) return
        nacin = trenutniNacin()
        if (nacin != Nacin.PREDAL) predalOdprt = false
        val imena = VrsticaPravila.zImeni(nacin, predalOdprt, izbranaImena, fokusVMeniju = !skrcena)
        val lp = drsnik.layoutParams as? LayoutParams ?: LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        when {
            nacin == Nacin.PREDAL && !predalOdprt -> {
                drsnik.visibility = View.GONE; lp.marginEnd = 0
            }
            nacin == Nacin.PREDAL -> {
                // Predal lebdi cez vsebino: negativen rob mu vzame prostor v vrsti, visja os Z ga narise cez vsebino.
                drsnik.visibility = View.VISIBLE
                lp.width = dp(VrsticaPravila.sirinaPredalaDp(dejavnost.resources.configuration.screenWidthDp))
                lp.marginEnd = -lp.width
            }
            // Naloga 127: popolnoma skrita tirnica vsebini ne vzame nic.
            tirnicaSkrita() -> { drsnik.visibility = View.GONE; lp.marginEnd = 0 }
            else -> {
                drsnik.visibility = View.VISIBLE
                lp.width = if (imena) dejavnost.resources.getDimensionPixelSize(R.dimen.os_meni_sirina) else dp(68)
                lp.marginEnd = 0
            }
        }
        drsnik.layoutParams = lp
        drsnik.elevation = if (nacin == Nacin.PREDAL) dp(16).toFloat() else 0f
        izvzemiRobOdKretenj()
        zastor.visibility = if (nacin == Nacin.PREDAL && predalOdprt) View.VISIBLE else View.GONE
        if (zastor.visibility == View.GONE) zastor.alpha = 1f
        // Bralnik zaslona: dokler je predal odprt, vsebina pod zastorom ni dosegljiva (kot pri sistemskih predalih).
        vsebina.importantForAccessibility = if (zastor.visibility == View.VISIBLE)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        meni.setPadding(dp(if (imena) 16 else 10), dp(if (dotik) 12 else 24), dp(if (imena) 12 else 10), dp(16))
        besedila.forEach { it.visibility = if (imena) View.VISIBLE else View.GONE }
        if (::gumbVrstice.isInitialized) {
            gumbVrstice.contentDescription = dejavnost.getString(when {
                nacin == Nacin.PREDAL -> R.string.os_vrstica_skrij
                imena -> R.string.os_vrstica_imena_skrij
                else -> R.string.os_vrstica_imena_pokazi
            })
        }
        uveljaviGumbVsebine()
    }

    /**
     * Gumb menija v glavi zaslona, kadar je vrstica predal: ob pogledu, ki ga je zaslon izbral ([gumbPred], oznaka
     * [OZNAKA_PRED]), ali namesto okrasne ikone glave (oznaka [OZNAKA_SIDRO]). Tako je vedno na istem mestu (zgoraj
     * levo) in ne prekrije nicesar. Zaslon brez oznacene glave dobi lebdec gumb zgoraj levo.
     */
    private fun uveljaviGumbVsebine() {
        (gumbVsebine.parent as? ViewGroup)?.removeView(gumbVsebine)
        skritoSidro?.visibility = View.VISIBLE
        skritoSidro = null
        // Naloga 127: tudi popolnoma skrita tirnica ima gumb menija v glavi (ne samo poteg z roba).
        if (spodnja || (nacin != Nacin.PREDAL && !tirnicaSkrita())) return
        val pred: View? = sidroPred?.takeIf { it.parent != null } ?: vsebina.findViewWithTag<View>(OZNAKA_PRED)
        val zamenjaj = if (pred == null) vsebina.findViewWithTag<View>(OZNAKA_SIDRO) else null
        val cilj = pred ?: zamenjaj
        val stars = cilj?.parent as? ViewGroup
        if (cilj == null || stars == null) {
            okvir.addView(gumbVsebine, 1, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(4), dp(4), 0, 0) })
            return
        }
        val lp = if (stars is LinearLayout) LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_VERTICAL
            // Ikona (24 dp) je v gumbu odmaknjena 12 dp: negativni rob jo poravna z robom vsebine.
            marginStart = -dp(10)
            if (pred != null) marginEnd = dp(4)
        } else ViewGroup.MarginLayoutParams(dp(48), dp(48))
        stars.addView(gumbVsebine, stars.indexOfChild(cilj), lp)
        if (zamenjaj != null) { zamenjaj.visibility = View.GONE; skritoSidro = zamenjaj }
    }

    /**
     * Naloga 127: pri sistemski navigaciji s kretnjami je poteg z levega roba »Nazaj«. Dokler je tirnica skrita (ali je
     * vrstica predal), del levega roba sredi zaslona izvzamemo, da ga dobi nas poteg (Android dovoli najvec 200 dp visine).
     */
    private fun izvzemiRobOdKretenj() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        post {
            val h = height
            systemGestureExclusionRects = if (h > 0 && (tirnicaSkrita() || (nacin == Nacin.PREDAL && !predalOdprt))) {
                val visina = minOf(dp(200), h)
                listOf(android.graphics.Rect(0, (h - visina) / 2, dp(VrsticaPravila.ROB_POTEGA_DP.toInt()), (h + visina) / 2))
            } else emptyList()
        }
    }

    /** Zaslon pove, pred kateri pogled glave sodi gumb menija (npr. pred naslovno vrstico Spleta). */
    fun gumbPred(pogled: View?) {
        sidroPred = pogled
        uveljaviGumbVsebine()
    }

    /** Vsebino vzame iz vrstice (Splet brez vrstice za spletne aplikacije). */
    fun odvij() {
        (vsebina.parent as? ViewGroup)?.removeView(vsebina)
    }

    /**
     * Tipke za vrstico (klici iz dispatchKeyEvent dejavnosti):
     *  - Nazaj zapre odprt predal (dotik);
     *  - LEVO na levem robu vsebine odpre aktivno postavko; DESNO iz menija se vrne na nazadnje
     *    uporabljeni element vsebine. Premikanje znotraj vrstic in mrez ostane nespremenjeno.
     */
    fun prestrezi(dogodek: KeyEvent): Boolean {
        if (dogodek.keyCode == KeyEvent.KEYCODE_BACK || dogodek.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (dogodek.action == KeyEvent.ACTION_DOWN && dogodek.repeatCount == 0 && zapriPredal()) { pogoltniNazaj = true; return true }
            if (dogodek.action == KeyEvent.ACTION_UP && pogoltniNazaj) { pogoltniNazaj = false; return true }
            return false
        }
        if (dogodek.action != KeyEvent.ACTION_DOWN) return false
        val fokus = dejavnost.currentFocus ?: return false
        // Fokus na pogledu, ki ga ni videti (skrit stars, velikost 0): daljinec ni nic premaknil in okvirja ni bilo nikjer
        // (TV, 18. 10. 2026 - skrita vrsta Zacni na Domov). Puscica ga vrne na viden cilj, kot DESNO iz menija.
        if (!dotik && jeSmerna(dogodek.keyCode) && !fokus.isShown) {
            if (jePotomec(meni, fokus)) fokusMenija() else fokusVsebine()
            if (dejavnost.currentFocus?.isShown == true) return true
        }
        if (dogodek.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && jePotomec(meni, fokus)) {
            fokusVsebine()
            return true
        }
        if (dogodek.keyCode != KeyEvent.KEYCODE_DPAD_LEFT || !jePotomec(vsebina, fokus)) return false
        // Mreza (GridView) je za iskanje fokusa en sam pogled: levo od vsake ploscice je nasla meni, zato je LEVO sredi
        // mreze odprlo meni namesto premika na sosednjo ploscico (lastnik, 18. 10. 2026). Dokler izbrana ploscica ni v
        // prvem stolpcu, LEVO premakne izbiro po mrezi - to naredi mreza sama.
        if (fokus is android.widget.GridView && !naLevemRobu(fokus)) return false
        val levo = fokus.focusSearch(View.FOCUS_LEFT)
        if (levo == null || levo === fokus || jePotomec(meni, levo)) {
            // Predal je skrit: tipkovnica ali daljinec na telefonu ga odpre, sicer bi LEVO z roba ne naredil nicesar.
            if (nacin == Nacin.PREDAL && !predalOdprt) odpriPredal()
            aktivnaPostavka.requestFocus()
            return true
        }
        return false
    }

    /**
     * Nazaj na televizorju: iz vsebine najprej v vrstico (kot pri sistemskem zaganjalniku), sele iz vrstice naprej.
     * Vrne true, ce je fokus sel v vrstico. Na dotik nic (predal zapre [prestrezi]).
     */
    fun nazajVMeni(): Boolean {
        if (dotik || spodnja || imaFokus()) return false
        fokusMenija()
        return imaFokus()
    }

    private fun jeSmerna(koda: Int): Boolean = koda == KeyEvent.KEYCODE_DPAD_UP || koda == KeyEvent.KEYCODE_DPAD_DOWN ||
        koda == KeyEvent.KEYCODE_DPAD_LEFT || koda == KeyEvent.KEYCODE_DPAD_RIGHT || koda == KeyEvent.KEYCODE_DPAD_CENTER

    /** Izbrana ploscica mreze je v prvem stolpcu (ali izbire ni): LEVO od tod vodi v meni. */
    private fun naLevemRobu(mreza: android.widget.GridView): Boolean {
        val stolpci = mreza.numColumns
        val izbrana = mreza.selectedItemPosition
        return stolpci <= 1 || izbrana < 0 || izbrana % stolpci == 0
    }

    /**
     * GOR, DOL in DESNO iz vsebine ostanejo v vsebini. Iskanje fokusa Androida gleda le razdalje, mreza pa zanj steje
     * samo svojo zadnjo izbrano ploscico (lahko cisto desno ali nizje): z vrstice cipov je DOL zato nasel meni
     * (Programi) namesto mreze tik pod cipom, GOR s prve vrstice cipov pa Domov - meni se je razsiril in vsebina je
     * skocila vstran (lastnik, 18. 10. 2026). V meni vodi samo LEVO z levega roba vsebine ([prestrezi]); kadar v
     * vsebini v tisti smeri ni nicesar, fokus ostane, kjer je.
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val koren = vsebina as? ViewGroup
        if (!dotik && !spodnja && koren != null && focused != null && focused !== koren && jePotomec(koren, focused) &&
            (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN || direction == View.FOCUS_RIGHT)) {
            return android.view.FocusFinder.getInstance().findNextFocus(koren, focused, direction)
        }
        return super.focusSearch(focused, direction)
    }

    /**
     * Pogled s fokusom je izginil (vsebina se je na novo izrisala): Android fokus podeli prvemu pogledu na zaslonu, to
     * je meni na levi - meni se je razsiril in vsebina poskocila, cez hip pa se je vse vrnilo. Kadar je bil fokus
     * nazadnje v vsebini, ga zdaj dobi spet vsebina (isti cilj kot DESNO iz menija); ob odprtju zaslona ostane, kot je bilo.
     */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean {
        if (!dotik && !spodnja && fokusVVsebini && vsebina.visibility == View.VISIBLE) {
            fokusVsebine()
            if (vsebina.hasFocus()) return true
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    fun fokusVsebine() {
        // Daljinec: prvi cilj ni iskalno polje (fokus nanj bi odprl tipkovnico cez pol zaslona), ce je v vsebini kaj drugega.
        val cilj = zadnjiFokusVsebine?.takeIf { it.isShown && it.isFocusable }
            ?: (if (!dotik) prviFokus(vsebina, brezPolj = true) else null) ?: prviFokus(vsebina)
        cilj?.requestFocus()
    }

    /** Fokus je v meniju (ne v vsebini). */
    fun imaFokus(): Boolean = dejavnost.currentFocus?.let { jePotomec(meni, it) } == true

    /** Fokus v meni, na aktivno postavko (na televizorju se meni ob tem razsiri z imeni). */
    fun fokusMenija() { aktivnaPostavka.requestFocus() }

    private fun zgradiMeni() {
        meni.orientation = VERTICAL
        meni.setBackgroundColor(context.osBarva(R.color.os_meni_ozadje))

        val znak = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(if (dotik) 0 else dp(4), 0, 0, dp(if (dotik) 10 else 14))
        }
        if (dotik) {
            // Na dotik je na vrhu gumb menija (tri crte) na mestu znaka: isti gumb kot v glavi zaslona, ko je vrstica skrita.
            gumbVrstice = ImageView(context).apply {
                setImageResource(R.drawable.os_ikona_meni)
                imageTintList = ColorStateList.valueOf(context.osBarva(R.color.os_besedilo))
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_meni_postavka)
                setOnClickListener {
                    if (nacin == Nacin.PREDAL) zapriPredal() else nastaviImena(!izbranaImena)
                }
            }
            znak.addView(gumbVrstice, LayoutParams(dp(48), dp(48)))
        } else {
            znak.addView(ikona(R.drawable.os_znak, 32))
        }
        val ime = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(10), 0, 0, 0) }
        // Samostojni Safeer Predvajalnik se predstavi s svojim imenom, ne kot Safeer OS.
        val jePredvajalnik = si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
        ime.addView(besedilo(18f, R.color.os_besedilo, true).apply {
            text = dejavnost.getString(if (jePredvajalnik) R.string.os_ime_predvajalnik else R.string.os_app_name)
            maxLines = 1
        })
        ime.addView(besedilo(11f, R.color.os_umirjeno).apply {
            text = dejavnost.getString(if (jePredvajalnik) R.string.os_podnaslov_predvajalnik else R.string.os_podnaslov_app); maxLines = 2
        })
        znak.addView(ime, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        besedila.add(ime)
        meni.addView(znak)

        // Safeer Predvajalnik (samostojna aplikacija): samo, kar spada k predvajalniku - mediji, naprave
        // (datoteke z racunalnikov prek Safeer Linka), datoteke in nastavitve; ostalo je Safeer OS.
        val samoPredvajalnik = si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
        if (!samoPredvajalnik) dodaj(Razdelek.DOMOV, R.drawable.os_ikona_domov, R.string.os_meni_domov)
        dodaj(Razdelek.MEDIJI, R.drawable.os_ikona_glasba, R.string.os_mediji_kartica)
        dodaj(Razdelek.NAPRAVE, R.drawable.os_ikona_link, R.string.os_meni_naprave)
        // Sporocila: postavka vrstice IN kuverta v glavi (lastnik, 22. 10. 2026; od 12. 10. je bila le kuverta).
        if (!samoPredvajalnik && si.safeer.tv.BuildConfig.FLAVOR != "brskalnik") {
            dodaj(Razdelek.SPOROCILA, R.drawable.os_ikona_sporocila, R.string.os_meni_sporocila)
            dodajZnackoSporocil()
        }
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

        meni.addView(View(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        meni.addView(besedilo(11f, R.color.os_umirjeno).apply {
            text = dejavnost.getString(R.string.os_poganja); setPadding(dp(4), 0, 0, 0); besedila.add(this)
        })
        val link = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), 0, 0)
        }
        link.addView(ikona(R.drawable.os_ikona_link, 16, R.color.os_mint))
        link.addView(besedilo(13f, R.color.os_mint, true).apply {
            text = dejavnost.getString(R.string.os_link); setPadding(dp(6), 0, 0, 0); besedila.add(this)
        })
        meni.addView(link)
    }

    /** Stevilo neprebranih Sporocil se vpise v znacko postavke (prebere se v ozadju, ob gradnji vrstice). */
    private fun dodajZnackoSporocil() {
        val postavka = postavke[Razdelek.SPOROCILA] as? LinearLayout ?: return
        val znacka = besedilo(10f, R.color.os_ozadje, true).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; minWidth = dp(17); setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(dejavnost.osBarva(R.color.os_mint)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        postavka.addView(znacka, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(17)))
        Thread({
            val koliko = try { SporocilaShramba(dejavnost).use { s -> s.pogovori().sumOf { it.neprebrano } } } catch (_: Throwable) { 0 }
            val b = ZaganjalnikPravila.besediloZnacke(koliko)
            dejavnost.runOnUiThread {
                if (dejavnost.isFinishing) return@runOnUiThread
                znacka.text = b; znacka.visibility = if (b.isEmpty()) View.GONE else View.VISIBLE
                postavka.contentDescription = if (koliko > 0) dejavnost.getString(R.string.zg_znacka_opis, dejavnost.getString(R.string.os_meni_sporocila), koliko)
                    else dejavnost.getString(R.string.os_meni_sporocila)
            }
        }, "SafeerSporocilaVrstica").start()
    }

    private fun dodaj(razdelek: Razdelek, slika: Int, niz: Int) {
        val pogled = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            id = View.generateViewId(); isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_meni_postavka)
            // Na dotik je postavka visoka vsaj 48 dp (smernice za dostopnost); na televizorju ostane gosta.
            setPadding(dp(12), dp(if (dotik) 13 else 6), dp(12), dp(if (dotik) 13 else 6))
            // Tirnica z ikonami skrije imena: bralnik zaslona (in avtomatski preizkusi) mora postavko se vedno poimenovati.
            contentDescription = dejavnost.getString(niz)
            addView(ikona(slika, 22, R.color.os_mint))
            addView(besedilo(14f, R.color.os_besedilo, true).apply {
                text = dejavnost.getString(niz); maxLines = 1; setPadding(dp(12), 0, 0, 0)
                setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                besedila.add(this)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isActivated = razdelek == aktivna
            isSelected = razdelek == aktivna
            setOnClickListener {
                if (razdelek == aktivna) {
                    zapriPredal()
                    naAktivno?.invoke() ?: fokusVsebine()
                } else odpri(razdelek)
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
        val domaci = ZaganjalnikActivity.jeDomaci(dejavnost)
        val namera = when (razdelek) {
            // Telefon, tablica: ce je Safeer zaganjalnik domaci zaslon, Domov vodi tja (namera domacega zaslona - ena sama
            // instanca v opravilu domacega zaslona, kot tipka Domov); sicer kot doslej. Televizor nespremenjen.
            Razdelek.DOMOV -> (if (Naprava.jeTelevizor(dejavnost)) null else Zaganjalnik.nameraDomov(dejavnost)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ?: Intent(dejavnost, ZaganjalnikActivity.domov(dejavnost))
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
            if (dejavnost !is OsActivity && !domaci) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            dejavnost.startActivity(namera)
            // Domaci zaslon ostane odprt pod razdelkom: ob vrnitvi naj predal ne caka odprt.
            if (!domaci) dejavnost.finish() else zapriPredal(animacija = false)
        } catch (_: Throwable) {
            Toast.makeText(dejavnost, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ikona(vir: Int, velikost: Int, barva: Int? = null) = ImageView(context).apply {
        setImageResource(vir); scaleType = ImageView.ScaleType.FIT_CENTER
        if (barva != null) imageTintList = ColorStateList.valueOf(context.osBarva(barva))
        layoutParams = LayoutParams(dp(velikost), dp(velikost))
    }

    private fun besedilo(velikost: Float, barva: Int, krepko: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, velikost)
        setTextColor(context.osBarva(barva))
        if (krepko) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
        dejavnost.resources.displayMetrics).toInt()

    private fun prviFokus(pogled: View, brezPolj: Boolean = false): View? {
        if (pogled.visibility != View.VISIBLE) return null
        // Drsnik (ScrollView) je le posoda: fokus bi predal prvemu otroku, tudi iskalnemu polju.
        val posoda = pogled is android.widget.ScrollView || pogled is android.widget.HorizontalScrollView
        if (!posoda && pogled.isFocusable && pogled.isEnabled && !(brezPolj && pogled is android.widget.EditText)) return pogled
        val skupina = pogled as? ViewGroup ?: return null
        for (i in 0 until skupina.childCount) prviFokus(skupina.getChildAt(i), brezPolj)?.let { return it }
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
        /** Oznaka dnevnika za meritve prikaza (polna risanja, dopolnjevanja mreze, stranska vrstica) - samo na zahtevo (setprop). */
        const val DNEVNIK_MREZE = "SafeerOsMreza"

        /** Skupna shramba vrstice in lupe (vsi zasloni Safeer OS). */
        const val SHRAMBA = "safeer_os_vrstica"
        const val KLJUC_IMENA = "imena"
        /** Naloga 127: tirnica popolnoma skrita (lezece na dotik). */
        const val KLJUC_SKRITA = "skrita"

        /** Okrasna ikona glave zaslona: na ozkem zaslonu jo nadomesti gumb menija. */
        const val OZNAKA_SIDRO = "k:meni_sidro"
        /** Pogled glave, pred katerega gre gumb menija (brez okrasne ikone). */
        const val OZNAKA_PRED = "k:meni_pred"
        /** Gumb menija v glavi (za preizkuse in iskanje po pogledih). */
        const val OZNAKA_GUMB = "k:meni_gumb"

        private const val TRAJANJE_MS = 200L

        /**
         * Splet (MainActivity) ima temo brskalnika, ki barv Safeer OS (?attr/osMint ...) nima: ikone menija, ki
         * barvo berejo iz teme, bi se tam narisale prozorne (prazna vrstica). Vrstica zato nastane v kontekstu
         * izbrane teme Safeer OS, kadar je gostujoca dejavnost nima.
         */
        private fun kontekstTeme(a: Activity): android.content.Context =
            if (a.theme.resolveAttribute(R.attr.osMint, TypedValue(), true)) a
            else android.view.ContextThemeWrapper(a, Tema.izbrana(a).stil)

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

        /** Vrstica na zaslonu dejavnosti (ali null). */
        fun na(dejavnost: Activity): StranskaVrstica? {
            fun poisci(v: View): StranskaVrstica? {
                if (v is StranskaVrstica) return v
                val skupina = v as? ViewGroup ?: return null
                for (i in 0 until skupina.childCount) poisci(skupina.getChildAt(i))?.let { return it }
                return null
            }
            return poisci(dejavnost.window.decorView)
        }
    }
}
