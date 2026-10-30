package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import si.safeer.tv.R

/**
 * Naloga 118: mini predvajalnik na domacem zaslonu ([ZaganjalnikActivity]) kot »dock« ob robu, ki ga izbere uporabnik
 * ([MiniPredvajalnikPravila]). Skrit je le rocica ob robu; poteg proti sredini ali dotik (OK na daljincu) ga odpre, poteg
 * proti robu ali gumb »Skrij« (Nazaj) ga skrije, sam se skrije po 8 s brez dotika. Dolg pritisk odpre izbiro mesta.
 * Gumbi gredo prek [GlasbaStoritev]: igrajo na tej napravi, na zvocniku ali na drugi napravi (naloga 117) - kot drugje.
 *
 * [okvir] je FrameLayout domacega zaslona (nad seznamom odsekov); ob vsaki prerisbi zaslona se ustvari znova.
 */
class MiniPredvajalnik(
    private val a: Activity,
    private val okvir: FrameLayout,
    private val nastavitve: SharedPreferences,
    private val daljinec: Boolean,
    private val barva: (Int) -> Int,
    private val odpriMedije: () -> Unit,
    /** Mesto se je spremenilo (domaci zaslon pokaze ali skrije kompakten predvajalnik v seznamu). */
    private val obSpremembiMesta: () -> Unit,
    /** Naloga 127: zaslon brez kompaktnega predvajalnika v seznamu (DomovActivity, DomovTabletActivity) - »v seznamu« ni izbira. */
    private val dovoliSeznam: Boolean = true,
) {
    companion object {
        const val KLJUC_MESTO = "mini_predvajalnik_mesto"

        /**
         * Naloga 127: dock na korenu okna (`android.R.id.content`, vedno FrameLayout) - za domaca zaslona brez lastnega
         * okvira (DomovActivity na TV, DomovTabletActivity na telefonu in tablici). Prezivi tudi nov setContentView.
         */
        fun naOknu(a: Activity, odpriMedije: () -> Unit): MiniPredvajalnik? {
            val okvir = a.findViewById<FrameLayout>(android.R.id.content) ?: return null
            var dock: MiniPredvajalnik? = null
            dock = MiniPredvajalnik(a, okvir, a.getSharedPreferences("safeer_os", android.content.Context.MODE_PRIVATE),
                Naprava.jeTelevizor(a), { a.osBarva(it) }, odpriMedije, { dock?.osvezi() }, dovoliSeznam = false)
            return dock
        }
        /** Odprt ali skrit ostane cez prerisbo zaslona (zasuk, razdeljen zaslon). */
        private var odprt = false
    }

    private val glavna = Handler(Looper.getMainLooper())
    private var koren: FrameLayout? = null
    private var rocica: View? = null
    private var kartica: LinearLayout? = null
    private var zadnjiDotik = 0L
    // Pregled C4: dock se zgradi enkrat (in ob novem mestu); osvezitev spremeni samo te poglede.
    private var zgrajenoMesto: String? = null
    private var naslovPogled: TextView? = null
    private var izvajalecPogled: TextView? = null
    private var gumbPredvajaj: ImageButton? = null
    private var radioIkona = false
    // Lastnik 27. 10. 2026: drsnik casa in zvocnik v docku (kot v celozaslonskem predvajalniku).
    private var drsnik: SeekBar? = null
    private var gumbZvocnik: ImageButton? = null
    /** Lastnik 28. 10. 2026: »Zatemni zaslon« - glasba igra naprej, katerakoli tipka daljinca zaslon zbudi. */
    private var gumbZatemni: ImageButton? = null
    private var vlece = false
    // Vodja 27. 10. 2026: zaslon z vrstico ob robu (Pokrajina) pove, koliko prostora mora dock pustiti (px).
    private var robSpodaj = 0

    /** Prostor za spodnjo vrstico domacega zaslona: dock (rocica in kartica) je nad njo, ne cez njo. */
    fun nastaviRob(spodaj: Int) {
        if (spodaj == robSpodaj) return
        robSpodaj = spodaj
        zgrajenoMesto = null      // naslednja osvezitev zgradi dock z novim odmikom
        osvezi()
    }

    private fun dodatno(m: String) = MiniPredvajalnikPravila.dodatniRob(m, robSpodaj)

    private fun odmakni(lp: FrameLayout.LayoutParams, m: String): FrameLayout.LayoutParams {
        val d = dodatno(m)
        when (m) {
            MiniPredvajalnikPravila.ZGORAJ -> Unit
            MiniPredvajalnikPravila.LEVO -> lp.leftMargin += d
            MiniPredvajalnikPravila.DESNO -> lp.rightMargin += d
            else -> lp.bottomMargin += d
        }
        return lp
    }

    /** Video na tej napravi (film, epizoda) - ne druga naprava v Linku. */
    private fun videoTu(): Boolean = !oddaljeno() && try { GlasbaStoritev.trenutna()?.video == true } catch (_: Throwable) { false }

    /**
     * Lastnik 27. 10. 2026: »Nadaljuj« pri filmu odpre celozaslonski predvajalnik na ISTEM mestu - predvajalnik se pripne na
     * isti GlasbaStoritev.predvajalnik (isti tok, podnapisi, zvocna sled), nic se ne nalozi znova.
     */
    /** Zatemnjen zaslon predvajanja (samo zvok): ura in naslov na crnem, glasba igra naprej ([PredvajanjeActivity.ZATEMNI]). */
    private fun zatemni() {
        try {
            a.startActivity(android.content.Intent(a, PredvajanjeActivity::class.java).putExtra(PredvajanjeActivity.ZATEMNI, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Throwable) { }
    }

    private fun odpriPredvajalnik() {
        try { GlasbaStoritev.nadaljujTu() } catch (_: Throwable) { }
        try {
            a.startActivity(android.content.Intent(a, PredvajanjeActivity::class.java)
                .putExtra(PredvajanjeActivity.NADALJUJ, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Throwable) { GlasbaStoritev.preklopi() }
    }

    val mesto: String get() = MiniPredvajalnikPravila.mesto(nastavitve.getString(KLJUC_MESTO, null))
        .let { if (!dovoliSeznam && it == MiniPredvajalnikPravila.V_SEZNAMU) MiniPredvajalnikPravila.PRIVZETO else it }
    val jeOdprt: Boolean get() = odprt && koren?.visibility == View.VISIBLE

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    /** Prerise dock po trenutnem predvajanju; ce nic ne igra ali je izbran »v seznamu«, ga ni. */
    fun osvezi() {
        val m = mesto
        val oddaljeno = try { GlasbaStoritev.naDrugiNapravi() } catch (_: Throwable) { false }
        val tu = try { GlasbaStoritev.trenutna() } catch (_: Throwable) { null }
        val daljinsko = OddaljenoPredvajanje.stanje
        val viden = MiniPredvajalnikPravila.jeDock(m) &&
            MiniPredvajalnikPravila.viden(tu != null, OddaljenoPredvajanje.cilj != null && daljinsko.kajIgra)
        if (!viden) { koren?.visibility = View.GONE; return }
        val naslov = if (oddaljeno || tu == null) daljinsko.naslov else tu.naslov
        val izvajalec = if (oddaljeno || tu == null) daljinsko.izvajalec else tu.izvajalec
        val igra = if (oddaljeno || tu == null) daljinsko.igra else try { GlasbaStoritev.igra() } catch (_: Throwable) { false }
        val radio = !oddaljeno && tu?.radio == true
        // Dock kaze drugo napravo: njeno stanje naj bo sveze (v ozadju se sicer ne sprasuje, pregled C2).
        if (oddaljeno || tu == null) OddaljenoPredvajanje.prikazano()
        val k = koren
        if (k == null || MiniPredvajalnikPravila.zgradiZnova(zgrajenoMesto, m, k.parent === okvir)) zgradi(m, naslov, izvajalec, igra, radio)
        else {
            k.visibility = View.VISIBLE
            prikazi(naslov, izvajalec, igra, radio)
        }
    }

    /**
     * Pregled C4: osvezitev (vsaka sprememba predvajanja, odgovor druge naprave) spremeni samo besedilo in ikone - fokus
     * daljinca ostane, kjer je, in cas samodejnega skrivanja tece naprej (prej je vsaka osvezitev dock zgradila znova,
     * fokus je skocil na »Predvajaj« in dock se med sejo daljinca ni nikoli skril).
     */
    private fun prikazi(naslov: String, izvajalec: String, igra: Boolean, radio: Boolean) {
        naslovPogled?.let { if (it.text?.toString() != naslov) it.text = naslov }
        izvajalecPogled?.let {
            if (it.text?.toString() != izvajalec) it.text = izvajalec
            it.visibility = if (izvajalec.isNotBlank()) View.VISIBLE else View.GONE
        }
        gumbPredvajaj?.let {
            it.setImageResource(if (igra) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
            it.contentDescription = a.getString(if (igra) R.string.zg_premor else R.string.zg_predvajaj)
        }
        osveziCas(radio)
        (rocica as? ImageButton)?.let {
            if (radio != radioIkona) { radioIkona = radio; it.setImageResource(if (radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba) }
            it.contentDescription = a.getString(R.string.zg_dock_pokazi) + ": " + naslov
        }
    }

    /** Nazaj (domaci zaslon): odprt dock se skrije. Vrne true, ce je bil odprt. Fokus gre na rocico (pregled C9). */
    fun skrijCeOdprt(): Boolean {
        if (!jeOdprt) return false
        nastaviOdprt(false, fokus = kartica?.findFocus() != null); return true
    }

    val rocicaPogled: View? get() = if (koren?.visibility == View.VISIBLE && rocica?.visibility == View.VISIBLE) rocica else null
    val imaFokus: Boolean get() = koren?.hasFocus() == true
    val aktivniPogledZaFokus: View? get() = if (koren?.visibility == View.VISIBLE) {
        if (odprt) gumbPredvajaj ?: kartica?.findViewWithTag<View>("dock:predvajaj") ?: rocica else rocica
    } else null

    fun fokusiraj(): Boolean {
        if (koren?.visibility != View.VISIBLE) return false
        val cilj = aktivniPogledZaFokus ?: return false
        return cilj.requestFocus()
    }

    fun ustavi() { glavna.removeCallbacks(samodejno); glavna.removeCallbacks(casovnik) }

    private fun oddaljeno(): Boolean = try { GlasbaStoritev.naDrugiNapravi() } catch (_: Throwable) { false }

    /** Drsnik in gumb zvocnika po stanju predvajanja (skrita, kadar nimata smisla - radio, druga naprava). */
    private fun osveziCas(radio: Boolean) {
        val odd = oddaljeno()
        val tu = try { GlasbaStoritev.trenutna() } catch (_: Throwable) { null }
        val trajanje = if (odd) 0L else try { GlasbaStoritev.trajanjeMs() } catch (_: Throwable) { 0L }
        drsnik?.let { d ->
            val viden = MiniPredvajalnikPravila.drsnik(trajanje, radio, odd)
            d.visibility = if (viden) View.VISIBLE else View.GONE
            if (viden && !vlece) d.progress = MiniPredvajalnikPravila.korakDrsnika(
                try { GlasbaStoritev.polozajMs() } catch (_: Throwable) { 0L }, trajanje)
        }
        gumbZvocnik?.let { g ->
            g.visibility = if (MiniPredvajalnikPravila.gumbZvocnik(tu != null, odd)) View.VISIBLE else View.GONE
            val na = try { GlasbaStoritev.naZvocniku() } catch (_: Throwable) { false }
            g.imageTintList = ColorStateList.valueOf(barva(if (na) R.color.os_mint else R.color.os_besedilo))
            g.contentDescription = if (na) a.getString(R.string.zvocnik_na, Zvocniki.aktivni?.ime.orEmpty()) else a.getString(R.string.zvocnik_na_napravi)
        }
        // Zatemnitev ima smisel samo za zvok na tej napravi (video bi zakrila, druga naprava ima svoj zaslon).
        gumbZatemni?.visibility = if (MiniPredvajalnikPravila.gumbZatemni(tu != null, tu?.video == true, odd)) View.VISIBLE else View.GONE
        glavna.removeCallbacks(casovnik)
        if (drsnik?.visibility == View.VISIBLE && odprt && koren?.visibility == View.VISIBLE) glavna.postDelayed(casovnik, 1_000L)
    }

    /** Cas na drsniku tece, dokler je dock odprt in na zaslonu. */
    private val casovnik = Runnable { if (koren?.parent === okvir) osveziCas(radioIkona) }

    private fun zvocnik() {
        GlasbaStoritev.trenutna()?.let { sk -> ZvocnikIzbira.predvajajNaNapravi(a, sk) { osvezi() } }
    }

    private val samodejno = object : Runnable {
        override fun run() {
            val k = kartica ?: return
            val fokus = k.findFocus() != null
            if (MiniPredvajalnikPravila.samodejnoSkrij(odprt, fokus, zadnjiDotik, SystemClock.uptimeMillis())) nastaviOdprt(false)
            else if (odprt) glavna.postDelayed(this, 1_000L)
        }
    }

    /** Uporabnik odpre ali skrije dock (dotik, poteg, OK, Nazaj): cas samodejnega skrivanja zacne znova. */
    private fun nastaviOdprt(da: Boolean, fokus: Boolean = false) {
        odprt = da
        zadnjiDotik = SystemClock.uptimeMillis()
        uveljaviOdprt(da, fokus)
    }

    private fun uveljaviOdprt(da: Boolean, fokus: Boolean) {
        rocica?.visibility = if (da) View.GONE else View.VISIBLE
        kartica?.visibility = if (da) View.VISIBLE else View.GONE
        glavna.removeCallbacks(samodejno)
        if (da) {
            glavna.postDelayed(samodejno, 1_000L)
            osveziCas(radioIkona)
            if (fokus) kartica?.findViewWithTag<View>("dock:predvajaj")?.requestFocus()
        } else if (fokus) rocica?.requestFocus()
    }

    private fun gravitacija(m: String): Int = when (m) {
        MiniPredvajalnikPravila.ZGORAJ -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        MiniPredvajalnikPravila.LEVO -> Gravity.START or Gravity.CENTER_VERTICAL
        MiniPredvajalnikPravila.DESNO -> Gravity.END or Gravity.CENTER_VERTICAL
        else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    /**
     * Poteg na rocici ali kartici: odpre ali zapre po [MiniPredvajalnikPravila.poPotegu]. Poslusalec dotika ne porabi
     * (dotik, dolg pritisk in gumbi delajo kot obicajno) - porabi samo konec potega, ki je dock odprl ali zaprl.
     */
    private fun potegi(v: View, m: String) {
        var x0 = 0f; var y0 = 0f
        val prag = dp(24).toFloat()
        v.setOnTouchListener { pogled, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; zadnjiDotik = SystemClock.uptimeMillis(); false }
                MotionEvent.ACTION_UP -> when (MiniPredvajalnikPravila.poPotegu(m, e.rawX - x0, e.rawY - y0, prag)) {
                    MiniPredvajalnikPravila.ODPRI -> { pogled.isPressed = false; pogled.cancelLongPress(); nastaviOdprt(true); true }
                    MiniPredvajalnikPravila.ZAPRI -> { pogled.isPressed = false; pogled.cancelLongPress(); nastaviOdprt(false); true }
                    else -> false
                }
                else -> false
            }
        }
    }

    /**
     * Neprosojna zaobljena plosca v barvah teme (dvignjena kartica, obroba kartice); s fokusom daljinca debela obroba v
     * barvi mint - na TV se sicer ne vidi, da je fokus na rocici (24. 10.).
     */
    private fun neprosojno(): android.graphics.drawable.Drawable {
        fun plosca(obroba: Int, debelina: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(barva(R.color.os_kartica_dvignjena))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(debelina), barva(obroba))
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), plosca(R.color.os_mint, 3))
            addState(intArrayOf(), plosca(R.color.os_kartica_obroba, 1))
        }
    }

    private fun zgradi(m: String, naslov: String, izvajalec: String, igra: Boolean, radio: Boolean) {
        val fokusVDocku = koren?.findFocus() != null
        koren?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val pokoncen = MiniPredvajalnikPravila.pokoncen(m)
        val k = FrameLayout(a)
        val rob = dp(8)

        // Rocica: kratka plosca z ikono glasbe ob robu.
        val r = ImageButton(a).apply {
            setImageResource(if (radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
            imageTintList = ColorStateList.valueOf(barva(R.color.os_mint))
            background = neprosojno()
            contentDescription = a.getString(R.string.zg_dock_pokazi) + ": " + naslov
            isFocusable = true
            setOnClickListener { nastaviOdprt(true, fokus = daljinec) }
            setOnLongClickListener { izberiMesto(); true }
        }
        potegi(r, m)
        k.addView(r, odmakni(if (pokoncen) FrameLayout.LayoutParams(dp(30), dp(96), gravitacija(m)) else FrameLayout.LayoutParams(dp(96), dp(30), gravitacija(m)), m))

        // Kartica: kaj igra in gumbi.
        val c = LinearLayout(a).apply {
            orientation = if (pokoncen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Neprosojno ozadje: ploscica aplikacije je prosojna in skozi dock se je videla vsebina pod njim (telefon, 24. 10.).
            background = neprosojno()
            setPadding(dp(12), dp(8), dp(8), dp(8))
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setOnLongClickListener { izberiMesto(); true }
        }
        potegi(c, m)
        val besedila = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            isFocusable = true
            setOnClickListener { if (MiniPredvajalnikPravila.klikNaslova(videoTu()) == MiniPredvajalnikPravila.ODPRI_PREDVAJALNIK) odpriPredvajalnik() else odpriMedije() }
        }
        val nPogled = TextView(a).apply {
            text = naslov; maxLines = if (pokoncen) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(barva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        besedila.addView(nPogled)
        // Izvajalec je vedno v docku (skrit, ce ga ni) - osvezitev ga le pokaze ali skrije.
        val iPogled = TextView(a).apply {
            text = izvajalec; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(barva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = if (izvajalec.isNotBlank()) View.VISIBLE else View.GONE
        }
        besedila.addView(iPogled)
        // Drsnik casa: premik naprej ali nazaj po skladbi (tudi na zvocniku - GlasbaStoritev.skoci).
        val d = SeekBar(a).apply {
            max = MiniPredvajalnikPravila.DRSNIK_KORAKOV
            tag = "dock:cas"
            contentDescription = a.getString(R.string.zg_dock_cas)
            progressTintList = ColorStateList.valueOf(barva(R.color.os_mint))
            thumbTintList = ColorStateList.valueOf(barva(R.color.os_mint))
            setPadding(0, dp(4), 0, dp(2))
            visibility = View.GONE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, korak: Int, odUporabnika: Boolean) {
                    if (odUporabnika) zadnjiDotik = SystemClock.uptimeMillis()
                    // Daljinec (levo/desno) nima »spusti«: premik takoj.
                    if (odUporabnika && !vlece) skociNa(korak)
                }
                override fun onStartTrackingTouch(s: SeekBar) { vlece = true }
                override fun onStopTrackingTouch(s: SeekBar) { vlece = false; skociNa(s.progress) }
            })
        }
        besedila.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (pokoncen) c.addView(besedila, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
        else c.addView(besedila, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val g = dp(if (daljinec) 48 else 44)
        fun gumb(ikona: Int, opis: String, oznaka: String, dejanje: () -> Unit) = ImageButton(a).apply {
            setImageResource(ikona); contentDescription = opis; tag = oznaka
            imageTintList = ColorStateList.valueOf(barva(R.color.os_besedilo))
            setBackgroundResource(R.drawable.os_meni_postavka)
            isFocusable = true
            setOnClickListener {
                zadnjiDotik = SystemClock.uptimeMillis()
                try { dejanje() } catch (_: Throwable) { }
                glavna.postDelayed({ osvezi() }, 300)
            }
        }
        val gumbi = LinearLayout(a).apply { orientation = if (pokoncen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        gumbi.addView(gumb(R.drawable.os_ikona_prejsnja, a.getString(R.string.zg_prejsnja), "dock:prejsnja") { GlasbaStoritev.prejsnja() }, LinearLayout.LayoutParams(g, g))
        val gPredvajaj = gumb(if (igra) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj,
            a.getString(if (igra) R.string.zg_premor else R.string.zg_predvajaj), "dock:predvajaj") {
            val igraZdaj = try { GlasbaStoritev.igra() } catch (_: Throwable) { false }
            if (MiniPredvajalnikPravila.dejanjePredvajaj(videoTu(), igraZdaj) == MiniPredvajalnikPravila.ODPRI_PREDVAJALNIK) odpriPredvajalnik()
            else GlasbaStoritev.preklopi()
        }
        gumbi.addView(gPredvajaj, LinearLayout.LayoutParams(g, g))
        gumbi.addView(gumb(R.drawable.os_ikona_naslednja, a.getString(R.string.zg_naslednja), "dock:naslednja") { GlasbaStoritev.naslednja() }, LinearLayout.LayoutParams(g, g))
        val gZvocnik = gumb(R.drawable.os_ikona_predvajaj_na, a.getString(R.string.zvocnik_na_napravi), "dock:zvocnik") { zvocnik() }
        gumbi.addView(gZvocnik, LinearLayout.LayoutParams(g, g))
        // Naloga 42 B: glasnost brez zapuscanja domacega zaslona (kot na racunalniku) - tiste naprave, ki igra.
        gumbi.addView(gumb(R.drawable.os_ikona_tisje, a.getString(R.string.zvocnik_tisje), "dock:tisje") { glasnost(-1) }, LinearLayout.LayoutParams(g, g))
        gumbi.addView(gumb(R.drawable.os_ikona_glasneje, a.getString(R.string.zvocnik_glasneje), "dock:glasneje") { glasnost(1) }, LinearLayout.LayoutParams(g, g))
        val gZatemni = gumb(R.drawable.os_ikona_luna, a.getString(R.string.os_media_zatemni), "dock:zatemni") { zatemni() }
        gumbi.addView(gZatemni, LinearLayout.LayoutParams(g, g))
        gumbi.addView(gumb(R.drawable.os_ikona_zapri, a.getString(R.string.zg_dock_skrij), "dock:skrij") { nastaviOdprt(false, fokus = daljinec) }, LinearLayout.LayoutParams(g, g))
        c.addView(gumbi)
        val lp = if (pokoncen) FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravitacija(m))
            else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravitacija(m)).apply {
                // Na sirokem zaslonu (tablica, TV) dock ni cez ves rob.
                width = minOf(a.resources.displayMetrics.widthPixels - 2 * rob, dp(560))
            }
        lp.setMargins(rob, rob, rob, rob)
        k.addView(c, odmakni(lp, m))

        okvir.addView(k, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Prazen del docka ne lovi dotikov - pod njim je domaci zaslon.
        k.isClickable = false
        koren = k; rocica = r; kartica = c
        zgrajenoMesto = m; naslovPogled = nPogled; izvajalecPogled = iPogled; gumbPredvajaj = gPredvajaj; radioIkona = radio
        drsnik = d; gumbZvocnik = gZvocnik; gumbZatemni = gZatemni
        // Nov dock (prvic ali novo mesto): odprt dock dobi poln cas do samodejnega skrivanja, osvezitve pa ga ne podaljsajo.
        if (zadnjiDotik == 0L) zadnjiDotik = SystemClock.uptimeMillis()
        uveljaviOdprt(odprt, fokus = fokusVDocku)
    }

    private fun skociNa(korak: Int) {
        val trajanje = try { GlasbaStoritev.trajanjeMs() } catch (_: Throwable) { 0L }
        if (trajanje > 0) try { GlasbaStoritev.skoci(MiniPredvajalnikPravila.casIzDrsnika(korak, trajanje)) } catch (_: Throwable) { }
    }

    /**
     * Glasneje ([smer] 1) ali tisje (-1) za tisto, kar igra ([MiniPredvajalnikPravila.ciljGlasnosti]). Ta naprava: kot tipka
     * za glasnost na daljincu (sistem pokaze svoj drsnik); zvocnik in druga naprava: isti koraki kot v izbiri zvocnika.
     */
    private fun glasnost(smer: Int) {
        val naDrugi = try { GlasbaStoritev.naDrugiNapravi() } catch (_: Throwable) { false }
        val naZvocniku = try { GlasbaStoritev.naZvocniku() } catch (_: Throwable) { false }
        when (MiniPredvajalnikPravila.ciljGlasnosti(naDrugi, naZvocniku)) {
            MiniPredvajalnikPravila.GLASNOST_DRUGA -> OddaljenoPredvajanje.glasnostZa(smer * MiniPredvajalnikPravila.KORAK_DRUGA)
            MiniPredvajalnikPravila.GLASNOST_ZVOCNIK -> Zvocniki.glasnostZa(smer * ZvocnikPravila.korakGlasnosti(Zvocniki.najGlasnost, Zvocniki.lestvicaZnana))
            else -> a.getSystemService(android.media.AudioManager::class.java)?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                if (smer > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
        }
    }

    /** Izbira mesta (dolg pritisk na rocico ali kartico): zgoraj, spodaj, levo, desno ali v seznamu. */
    private fun izberiMesto() {
        val mesta = MiniPredvajalnikPravila.MESTA.filter { dovoliSeznam || it != MiniPredvajalnikPravila.V_SEZNAMU }
        val imena = mesta.map {
            a.getString(when (it) {
                MiniPredvajalnikPravila.ZGORAJ -> R.string.zg_dock_zgoraj
                MiniPredvajalnikPravila.LEVO -> R.string.zg_dock_levo
                MiniPredvajalnikPravila.DESNO -> R.string.zg_dock_desno
                MiniPredvajalnikPravila.V_SEZNAMU -> R.string.zg_dock_seznam
                else -> R.string.zg_dock_spodaj
            })
        }.toTypedArray()
        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.zg_dock_mesto)
            .setSingleChoiceItems(imena, mesta.indexOf(mesto)) { d, i ->
                nastavitve.edit().putString(KLJUC_MESTO, mesta[i]).apply()
                d.dismiss()
                obSpremembiMesta()
            }
            .setNegativeButton(a.getString(R.string.os_preklici), null).create()
        Kontroler.pokazi(okno)
        okno.show()
    }
}
