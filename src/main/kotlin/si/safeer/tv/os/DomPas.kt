package si.safeer.tv.os

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R

/**
 * Kaj pas na vrhu Doma kaze. [vrsta] je ena od [DomPasPravila.IGRA], [DomPasPravila.NADALJUJ], [DomPasPravila.IZBOR]
 * ali [DomPasPravila.MC_NIC] (pasa ni). Ostala polja so ze pripravljena besedila - [DomPas] jih ne sestavlja sam, ker
 * ve, kaj vsebina je, samo Dom Medijskega centra.
 */
data class DomPasStanje(
    val vrsta: String,            // DomPasPravila.IGRA, NADALJUJ, IZBOR (MC_NIC = pas skrit)
    val naslov: String,
    val podnaslov: String,        // npr. "S01E03 · 2024" ali izvajalec
    val opis: String,             // kratek opis (lahko prazen) - najvec 2 vrstici
    val slikaUrl: String,         // ozadje (backdrop) ali plakat; prazno = ikona
    val video: Boolean,           // vsebina je video
    val igra: Boolean,            // trenutno igra (gumb kaze premor)
    val polozajMs: Long, val trajanjeMs: Long   // 0/0 = brez crte napredka
)

/**
 * Pas cez celo sirino na vrhu Doma (po Kinemi `Hero`, MIT: slika cez ves pas, senca od spodaj in z leve, naslov,
 * podnaslov, opis, crta napredka in vrsti gumbov) - hkrati mali predvajalnik: kaze, kar igra (video v [povrsinaVidea]),
 * sicer zadnjo nedokoncano vsebino z gumbom Nadaljuj, sicer izbor dneva. Mere, fokus in napisi po StreamNexus HD
 * (`TvVideoPlayer.kt`): velike pisave za 50" TV, fokus na glavnem gumbu, berljiv cas.
 *
 * [prikazi] samo posodobi besedila, ikone in crto - pogledov ne gradi znova, zato fokus daljinca med osvezitvami
 * (vsako sekundo napredka) ostane, kjer je. Dom pripne predvajalnik na [povrsinaVidea] in jo pokaze s [pokaziVideo].
 */
class DomPas(context: Context) : FrameLayout(context) {

    /** OK / klik na pas: igra -> cel zaslon, nadaljuj -> nadaljuj, izbor -> odpri. */
    var naGlavni: (() -> Unit)? = null

    /** Glavni gumb (Predvajaj / Premor / Nadaljuj). */
    var naPredvajaj: (() -> Unit)? = null

    /** Drugi gumb (Podrobnosti / vsi nedokoncani); null = gumba ni. */
    var naPodrobnosti: (() -> Unit)? = null
        set(value) {
            field = value
            // Slepi pregled: gumb s fokusom, ki izgine, fokus preda glavnemu (sicer bi ga sistem prestavil kamor koli).
            if (value == null && gumbPodrobnosti.hasFocus()) gumbGlavni.requestFocus()
            gumbPodrobnosti.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    /** Glavni gumb (Dom nanj skoci z drzanim DOL - kot na stari kartici). */
    val glavniGumb: View get() = gumbGlavni

    /** Nalagalnik slik, ki ga poda Dom (obstojeci predpomnilnik slik); brez njega se pokaze ikona. */
    var naloziSliko: ((String, ImageView) -> Unit)? = null

    /** Povrsina, na katero Dom pripne predvajalnik; pas je samo postavi in skrije/prikaze. */
    val povrsinaVidea: SurfaceView = SurfaceView(context)

    private val sirinaZaslonaDp = sirinaDp()
    private val siroko = sirinaZaslonaDp >= 600

    private val podlaga = View(context)
    private val ikona = ImageView(context)
    private val slika = ImageView(context)
    private val sencaSpodaj = View(context)
    private val sencaLevo = View(context)
    private val vsebina = LinearLayout(context)
    private val naslov = TextView(context)
    private val vrstaIkona = ImageView(context)
    private val podnaslov = TextView(context)
    private val opis = TextView(context)
    private val napredek = LinearLayout(context)
    private val crta = LinearLayout(context)
    private val crtaNapolnjena = View(context)
    private val crtaPrazna = View(context)
    private val cas = TextView(context)
    private val gumbGlavni = LinearLayout(context)
    private val ikonaGlavnega = ImageView(context)
    private val napisGlavnega = TextView(context)
    private val gumbPodrobnosti = LinearLayout(context)
    private val napisPodrobnosti = TextView(context)

    // Krozenje izbora (deklarirano pred init, ki pike doda v pas).
    /** Vsebine, med katerimi pas krozi ([prikaziVec]); prazno = brez krozenja. */
    private var krog: List<DomPasStanje> = emptyList()
    private var krogIndeks = 0
    private val pike = LinearLayout(context)
    private val krozi = Runnable { naslednjiVKrogu() }

    /** Zadnja narocena slika: ob vsakem prikazu (napredek) je ne nalagamo znova. */
    private var zadnjaSlika = ""
    private var imamSliko = false

    init {
        isFocusable = true
        isFocusableInTouchMode = false   // CodeRabbit: na dotik prvi dotik klikne (ne samo fokusira)
        isClickable = true
        defaultFocusHighlightEnabled = false
        clipToOutline = true
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Zgled KefinTweaks: na TV je pas cez vso sirino brez okvirja; na ozkem zaslonu ostane zaobljen.
                outline.setRoundRect(0, 0, view.width, view.height, dp(if (siroko) 0 else 20).toFloat())
            }
        }
        // Obroba fokusa je foreground: vidi se tudi cez video povrsino in sliko.
        foreground = obrobaFokusa()
        setOnClickListener { naGlavni?.invoke() }
        // Slepi pregled: dolg OK in MENU (onKeyDown -> performLongClick) odpreta vse nedokoncane, kot na stari kartici.
        setOnLongClickListener { val p = naPodrobnosti; p?.invoke(); p != null }
        // Daljinec: DESNO/DOL s pasa gre na gumba (sicer bi fokus sel mimo njiju).
        setOnKeyListener { _, kod, e ->
            if (e.action == KeyEvent.ACTION_DOWN && (kod == KeyEvent.KEYCODE_DPAD_RIGHT || kod == KeyEvent.KEYCODE_DPAD_DOWN)) {
                gumbGlavni.requestFocus()
            } else false
        }

        podlaga.background = plosca(R.color.os_kartica_dvignjena, 0, 20)
        addView(podlaga, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Ikona je pod sliko: med nalaganjem slike (in brez nje) stoji namesto nje, slika jo prekrije sama.
        ikona.setImageResource(R.drawable.os_ikona_video)
        ikona.imageTintList = ColorStateList.valueOf(barva(R.color.os_umirjeno))
        ikona.scaleType = ImageView.ScaleType.CENTER_INSIDE
        ikona.alpha = 0.35f
        ikona.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ikona, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        slika.scaleType = ImageView.ScaleType.CENTER_CROP
        slika.visibility = View.GONE
        slika.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(slika, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        povrsinaVidea.visibility = View.GONE
        addView(povrsinaVidea, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Senca: prosojna crna je edina barva, ki ni iz os_colors.xml - natancno to dopusca naloga.
        // Zgled KefinTweaks: slika se levo in spodaj prelije v ozadje strani (os_ozadje), ne v crno - pas se zlije z vrsticami.
        val ozadje = barva(R.color.os_ozadje)
        fun prosojno(a: Int) = Color.argb(a, Color.red(ozadje), Color.green(ozadje), Color.blue(ozadje))
        sencaSpodaj.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(prosojno(255), prosojno(150), prosojno(0), prosojno(0)))
        addView(sencaSpodaj, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sencaLevo.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(prosojno(235), prosojno(170), prosojno(0), prosojno(0)))
        addView(sencaLevo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        vsebina.orientation = LinearLayout.VERTICAL
        vsebina.isFocusable = false
        // Povecava gumba ob fokusu (1.03) ne sme biti odrezana ob robu vsebine in vrstice.
        vsebina.clipChildren = false
        vsebina.clipToPadding = false
        // Odmiki so tesneje na ozkem zaslonu: 170 dp mora drzati naslov, podnaslov, crto in gumba brez odrezanega vrha.
        // Na TV je vsebina pasu odmaknjena od roba kot v zgledu (48 dp levo, 32 dp spodaj).
        vsebina.setPadding(dp(if (siroko) 48 else 24), dp(if (siroko) 10 else 6), dp(if (siroko) 48 else 24), dp(if (siroko) 32 else 10))
        addView(vsebina, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        naslov.setTextSize(TypedValue.COMPLEX_UNIT_SP, DomPasPravila.velikostNaslovaSp(sirinaZaslonaDp))
        naslov.setTextColor(barva(R.color.os_besedilo))
        naslov.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        naslov.maxLines = 2
        naslov.ellipsize = TextUtils.TruncateAt.END
        if (siroko) naslov.maxWidth = dp(680)
        vsebina.addView(naslov, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val vrsticaPodnaslova = LinearLayout(context)
        vrsticaPodnaslova.orientation = LinearLayout.HORIZONTAL
        vrsticaPodnaslova.gravity = Gravity.CENTER_VERTICAL
        vrsticaPodnaslova.isFocusable = false
        vrstaIkona.setImageResource(R.drawable.os_ikona_video)
        vrstaIkona.imageTintList = ColorStateList.valueOf(barva(R.color.os_umirjeno))
        vrstaIkona.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        vrsticaPodnaslova.addView(vrstaIkona, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) })
        podnaslov.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        podnaslov.setTextColor(barva(R.color.os_umirjeno))
        podnaslov.maxLines = 1
        podnaslov.ellipsize = TextUtils.TruncateAt.END
        vrsticaPodnaslova.addView(podnaslov, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        vsebina.addView(vrsticaPodnaslova, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        opis.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (siroko) 16f else 14f)
        opis.setTextColor(barva(R.color.os_umirjeno))
        // Na 170 dp se poleg dveh vrstic naslova, crte in gumbov opis ne izide - tam ga ni (kot v ozkem uvodu
        // priznanih centrov); sicer najvec 2 vrstici, ker je uvod pas in ne stran.
        opis.maxLines = 2
        opis.ellipsize = TextUtils.TruncateAt.END
        if (siroko) opis.maxWidth = dp(560)
        vsebina.addView(opis, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        napredek.orientation = LinearLayout.HORIZONTAL
        napredek.gravity = Gravity.CENTER_VERTICAL
        napredek.isFocusable = false
        crta.orientation = LinearLayout.HORIZONTAL
        crta.background = plosca(R.color.os_crta, 0, 2)
        crtaNapolnjena.background = plosca(R.color.os_mint, 0, 2)
        crta.addView(crtaNapolnjena, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        crta.addView(crtaPrazna, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f))
        napredek.addView(crta, LinearLayout.LayoutParams(0, dp(4), 1f))
        cas.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (siroko) 18f else 14f)
        cas.setTextColor(barva(R.color.os_umirjeno))
        cas.maxLines = 1
        napredek.addView(cas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        vsebina.addView(napredek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val gumbi = LinearLayout(context)
        gumbi.orientation = LinearLayout.HORIZONTAL
        gumbi.gravity = Gravity.CENTER_VERTICAL
        gumbi.isFocusable = false
        gumbi.clipChildren = false
        gumbi.clipToPadding = false
        sestaviGumb(gumbGlavni, ikonaGlavnega, napisGlavnega, glavni = true)
        gumbi.addView(gumbGlavni, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sestaviGumb(gumbPodrobnosti, null, napisPodrobnosti, glavni = false)
        napisPodrobnosti.setText(R.string.os_scit_podrobnosti)   // slepi pregled: brez tega je bil gumb prazen
        gumbPodrobnosti.visibility = if (naPodrobnosti != null) View.VISIBLE else View.GONE
        gumbi.addView(gumbPodrobnosti, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        vsebina.addView(gumbi, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (siroko) 16 else 10) })

        // Pike krozenja (zgled: desno spodaj); niso fokusabilne in jih bralnik zaslona ne bere.
        pike.orientation = LinearLayout.HORIZONTAL
        pike.gravity = Gravity.CENTER_VERTICAL
        pike.isFocusable = false
        pike.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        pike.visibility = View.GONE
        addView(pike, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8), Gravity.BOTTOM or Gravity.END)
            .apply { rightMargin = dp(if (siroko) 48 else 20); bottomMargin = dp(if (siroko) 40 else 16) })
        // Fokus na pasu ali gumbu ustavi krozenje (klik mora odpreti vsebino, ki jo uporabnik vidi).
        viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> narociKrozenje() }
    }

    // ------------------------------------------------------------------ krozenje izbora (zgled KefinTweaks / MediaBar)


    /** Indeks trenutne vsebine krozenja (ob vsaki menjavi) - Dom po njem ve, kaj odpre klik. */
    var naIzbranem: ((Int) -> Unit)? = null

    /**
     * Vec vsebin izbora: pas jih kaze po vrsti vsakih [DomPasPravila.SEKUND_KROZENJA] s s pikami. En element ali vrsta, ki ni
     * izbor, je navaden [prikazi]. Isti seznam kot prej ne zacne znova (Dom pas osvezuje pogosto).
     */
    fun prikaziVec(seznam: List<DomPasStanje>) {
        val s = seznam.take(DomPasPravila.NAJVEC_KROZENJA)
        if (s.size < 2 || s.first().vrsta != DomPasPravila.IZBOR) {
            krog = emptyList(); pike.visibility = View.GONE; removeCallbacks(krozi)
            s.firstOrNull()?.let { prikazi(it) }
            return
        }
        if (s != krog) {
            krog = s
            krogIndeks = 0
            sestaviPike()
            naIzbranem?.invoke(0)
        }
        prikazi(krog[krogIndeks.coerceIn(0, krog.size - 1)])
        osveziPike()
        narociKrozenje()
    }

    private fun sestaviPike() {
        pike.removeAllViews()
        krog.forEach { _ -> pike.addView(View(context), LinearLayout.LayoutParams(dp(6), dp(6)).apply { leftMargin = dp(6) }) }
        pike.visibility = if (krog.size >= 2) View.VISIBLE else View.GONE
    }

    private fun osveziPike() {
        for (i in 0 until pike.childCount) {
            val p = pike.getChildAt(i)
            val dejavna = i == krogIndeks
            p.background = plosca(if (dejavna) R.color.os_besedilo else R.color.os_umirjeno, 0, 3)
            p.alpha = if (dejavna) 1f else 0.6f
            (p.layoutParams as LinearLayout.LayoutParams).width = dp(if (dejavna) 16 else 6)
            p.requestLayout()
        }
    }

    private fun narociKrozenje() {
        removeCallbacks(krozi)
        val vrsta = krog.firstOrNull()?.vrsta ?: return
        if (isAttachedToWindow && DomPasPravila.smeKroziti(vrsta, hasFocus(), !animacijeSme(), krog.size))
            postDelayed(krozi, DomPasPravila.SEKUND_KROZENJA * 1000L)
    }

    private fun naslednjiVKrogu() {
        if (krog.size < 2 || hasFocus()) return
        krogIndeks = DomPasPravila.naslednji(krogIndeks, krog.size)
        naIzbranem?.invoke(krogIndeks)
        val s = krog[krogIndeks]
        if (animacijeSme()) {
            vsebina.animate().cancel()
            vsebina.alpha = 0f
            prikazi(s)
            vsebina.animate().alpha(1f).setDuration(300L).start()
        } else prikazi(s)
        osveziPike()
        narociKrozenje()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        narociKrozenje()
    }

    /** Pas zunaj okna ne krozi (brez puscanja postDelayed). */
    override fun onDetachedFromWindow() {
        removeCallbacks(krozi)
        super.onDetachedFromWindow()
    }

    /**
     * Visina po [DomPasPravila.visinaDp] glede na dejansko sirino (ne le glede na konfiguracijo): pas na razdeljenem
     * zaslonu ali v ozjem oknu mora biti nizek tudi na tablici.
     */
    override fun onMeasure(sirinaSpec: Int, visinaSpec: Int) {
        val sirina = MeasureSpec.getSize(sirinaSpec)
        val sirinaV = if (sirina > 0) (sirina / resources.displayMetrics.density).toInt() else sirinaZaslonaDp
        val visinaZaslona = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        super.onMeasure(sirinaSpec, MeasureSpec.makeMeasureSpec(dp(DomPasPravila.visinaDp(sirinaV, visinaZaslona)), MeasureSpec.EXACTLY))
    }

    /**
     * Osvezi pas po stanju. Pogledov ne gradi znova in fokusa ne premakne - Dom to klice ob vsaki sekundi napredka.
     * [DomPasPravila.MC_NIC] pas skrije (zunaj Doma, na podstrani).
     */
    fun prikazi(s: DomPasStanje) {
        if (s.vrsta == DomPasPravila.MC_NIC) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

        val naslovB = s.naslov.trim()
        if (naslov.text?.toString() != naslovB) naslov.text = naslovB
        naslov.visibility = if (naslovB.isEmpty()) View.GONE else View.VISIBLE

        val podnaslovB = s.podnaslov.trim()
        if (podnaslov.text?.toString() != podnaslovB) podnaslov.text = podnaslovB
        podnaslov.visibility = if (podnaslovB.isEmpty()) View.GONE else View.VISIBLE
        vrstaIkona.visibility = podnaslov.visibility
        vrstaIkona.setImageResource(if (s.video) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba)

        val opisB = s.opis.trim()
        opis.text = opisB
        // Ozek pas (170 dp) opisa ne drzi poleg naslova, crte in gumbov - tam ga ni, da nic ni odrezano.
        opis.visibility = if (opisB.isEmpty() || !siroko) View.GONE else View.VISIBLE

        // Crte in casa ni brez trajanja (tok v zivo, se nalaga) - prazen trak bi izgledal kot pokvarjen napredek.
        val casB = DomPasPravila.casNapredka(s.polozajMs, s.trajanjeMs)
        napredek.visibility = if (casB.isEmpty()) View.GONE else View.VISIBLE
        if (casB.isNotEmpty()) {
            if (cas.text?.toString() != casB) cas.text = casB
            val odstotek = DomPasPravila.odstotek(s.polozajMs, s.trajanjeMs)
            (crtaNapolnjena.layoutParams as LinearLayout.LayoutParams).weight = odstotek.toFloat()
            (crtaPrazna.layoutParams as LinearLayout.LayoutParams).weight = (100 - odstotek).toFloat()
            crta.requestLayout()
        }

        val kljuc = DomPasPravila.napisGlavnega(s.vrsta, s.igra)
        val napis = when (kljuc) {
            DomPasPravila.NAPIS_PREMOR -> R.string.zg_premor
            DomPasPravila.NAPIS_NADALJUJ -> R.string.os_media_nadaljuj
            else -> R.string.zg_predvajaj
        }
        napisGlavnega.setText(napis)
        ikonaGlavnega.setImageResource(if (kljuc == DomPasPravila.NAPIS_PREMOR) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
        gumbGlavni.contentDescription = context.getString(napis)
        gumbPodrobnosti.contentDescription = context.getString(R.string.os_scit_podrobnosti)
        gumbPodrobnosti.visibility = if (naPodrobnosti != null) View.VISIBLE else View.GONE

        contentDescription = listOf(naslovB, podnaslovB).filter { it.isNotEmpty() }.joinToString(" · ")

        val url = s.slikaUrl.trim()
        if (url != zadnjaSlika) {
            zadnjaSlika = url
            slika.setImageDrawable(null)
            slika.tag = null   // slepi pregled: nalagalnik po oznaki ve, ali je prispela slika se prava
            if (url.isNotEmpty()) naloziSliko?.invoke(url, slika)
        }
        imamSliko = url.isNotEmpty() && naloziSliko != null
        val video = povrsinaVidea.visibility == View.VISIBLE
        slika.visibility = if (imamSliko) View.VISIBLE else View.GONE
        ikona.visibility = if (video) View.GONE else View.VISIBLE
        ikona.setImageResource(if (s.video) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba)
        podlaga.visibility = if (imamSliko || video) View.GONE else View.VISIBLE
    }

    /**
     * Video v pasu: povrsina je vidna (predvajalnik nanjo pripne Dom) ali skrita - takrat pas kaze sliko oziroma ikono.
     * Povrsine ne dodaja in ne odvzema, da predvajalniku ne zamenja slike med predvajanjem.
     */
    fun pokaziVideo(da: Boolean) {
        povrsinaVidea.visibility = if (da) View.VISIBLE else View.GONE
        ikona.visibility = if (da) View.GONE else View.VISIBLE
        podlaga.visibility = if (da || imamSliko) View.GONE else View.VISIBLE
    }

    private fun sirinaDp(): Int {
        val izKonfiguracije = resources.configuration.screenWidthDp
        if (izKonfiguracije > 0) return izKonfiguracije
        return (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun barva(id: Int): Int = context.getColor(id)

    private fun plosca(polnilo: Int, obroba: Int, polmerDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(barva(polnilo))
        cornerRadius = dp(polmerDp).toFloat()
        if (obroba != 0) setStroke(dp(2), barva(obroba))
    }

    /**
     * Obroba fokusa pasa (2 dp mint). Na mint gumbu mint obroba ne bi bila vidna, zato ima glavni gumb temno mint
     * obrobo (os_mint_temna) - oblika in debelina sta enaki, barva je iz iste palete.
     */
    private fun obrobaFokusa(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(2), barva(R.color.os_mint))
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun gumbPlosca(glavni: Boolean): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            if (glavni) plosca(R.color.os_mint, R.color.os_mint_temna, 28)
            else plosca(R.color.os_kartica_steklo_fokus, R.color.os_mint, 28))
        // Zgled KefinTweaks: gumba sta »tabletki« (polmer = polovica visine 44-48 dp).
        addState(intArrayOf(),
            if (glavni) plosca(R.color.os_mint, 0, 28)
            else plosca(R.color.os_kartica_steklo, 0, 28))
    }

    private fun sestaviGumb(kontejner: LinearLayout, ikonaGumba: ImageView?, napis: TextView, glavni: Boolean) {
        kontejner.orientation = LinearLayout.HORIZONTAL
        kontejner.gravity = Gravity.CENTER_VERTICAL
        kontejner.setPadding(dp(if (glavni) 22 else 20), dp(9), dp(if (glavni) 26 else 22), dp(9))
        // Na telefonu je gumb cilj dotika (48 dp), na TV z daljincem zadosca 44 dp - v 170 dp se mora vse iziti.
        kontejner.minimumHeight = dp(if (siroko) 44 else 48)
        kontejner.minimumWidth = dp(if (glavni) 128 else 108)
        kontejner.isFocusable = true
        kontejner.isFocusableInTouchMode = false
        kontejner.isClickable = true
        kontejner.defaultFocusHighlightEnabled = false
        kontejner.background = gumbPlosca(glavni)
        if (ikonaGumba != null) {
            ikonaGumba.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ikonaGumba.imageTintList = ColorStateList.valueOf(barva(R.color.os_ozadje))
            kontejner.addView(ikonaGumba, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        }
        // Crna ikona na mintu: namesto trdo kodirane crne uporabimo os_ozadje (skoraj crna iz palete).
        napis.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (siroko) 18f else 16f)
        napis.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        napis.setTextColor(barva(if (glavni) R.color.os_ozadje else R.color.os_besedilo))
        napis.maxLines = 1
        kontejner.addView(napis, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        kontejner.setOnClickListener { if (glavni) naPredvajaj?.invoke() else naPodrobnosti?.invoke() }
        kontejner.setOnFocusChangeListener { _, ima -> poudari(kontejner, ima) }
        kontejner.setOnKeyListener { _, kod, e ->
            if (e.action != KeyEvent.ACTION_DOWN) {
                false
            } else if (glavni) {
                when (kod) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> this@DomPas.requestFocus()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> gumbPodrobnosti.visibility == View.VISIBLE && gumbPodrobnosti.requestFocus()
                    else -> false
                }
            } else {
                kod == KeyEvent.KEYCODE_DPAD_LEFT && gumbGlavni.requestFocus()
            }
        }
    }

    /** Fokus je viden tudi s povecavo 1.03; ob zmanjsanem gibanju sistema skoci takoj, brez animacije. */
    private fun poudari(v: View, ima: Boolean) {
        v.animate().cancel()
        val cilj = if (ima) 1.03f else 1f
        if (animacijeSme()) v.animate().scaleX(cilj).scaleY(cilj).setDuration(120L).start()
        else { v.scaleX = cilj; v.scaleY = cilj }
    }

    private fun animacijeSme(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Throwable) {
        true
    }
}
