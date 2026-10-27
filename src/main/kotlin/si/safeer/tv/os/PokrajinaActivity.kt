package si.safeer.tv.os

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone

/**
 * Naloga 42 A: nov TV zaganjalnik Safeer OS (beta, [ZaganjalnikBeta]) s temo Pokrajina po zgledu lastnika: ozadje
 * pokrajina (izbira v Nastavitvah › Videz, privzeto gorsko jezero), levo zgoraj Safeer OS, desno vreme + ura + datum, na
 * sredini velika lupa z mikrofonom ([DomacaLupa]), vrstica priljubljenih in spletnih aplikacij in spodaj plavajoca vrstica
 * razdelkov. Barve iz teme ([Tema], ?attr/os*), fokus z daljincem je debela obroba v barvi poudarka in povecava
 * ([PokrajinaPravila.povecava]; pri zmanjsanem gibanju samo obroba). Ura in datum sta v jeziku vmesnika.
 *
 * Stari domaci zaslon (DomovActivity) ostane nespremenjen; DomovActivity sem preusmeri, kadar je beta vklopljena.
 */
class PokrajinaActivity : OsActivity() {
    private val glavna = Handler(Looper.getMainLooper())
    private lateinit var koren: FrameLayout
    private lateinit var ura: TextView
    private lateinit var datum: TextView
    private lateinit var vreme: TextView
    private lateinit var vrstaAplikacij: LinearLayout
    private lateinit var naslovAplikacij: TextView
    private lateinit var dok: LinearLayout
    private lateinit var vrstaSporocil: LinearLayout

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (DomacaLupa.naRezultat(this, requestCode, resultCode, data)) return
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Jezik vmesnika (JezikVmesnika je v attachBaseContext nastavil konfiguracijo virov), ne jezik televizorja. */
    private fun jezik(): Locale = resources.configuration.locales[0] ?: Locale.ENGLISH

    /** Zmanjsano gibanje: Android brez animacij ali izbira v Nastavitvah ([KLJUC_ZMANJSANO]). */
    private fun zmanjsano(): Boolean =
        getSharedPreferences("safeer_os", Context.MODE_PRIVATE).getBoolean(KLJUC_ZMANJSANO, false) ||
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f

    private val tikUre = object : Runnable {
        override fun run() {
            val zdaj = System.currentTimeMillis()
            ura.text = PokrajinaPravila.ura(zdaj, jezik(), TimeZone.getDefault())
            datum.text = PokrajinaPravila.datum(zdaj, jezik(), TimeZone.getDefault())
            glavna.postDelayed(this, PokrajinaPravila.doNaslednjeMinute(zdaj))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        koren = FrameLayout(this)
        val vsebina = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(18))
        }
        vsebina.addView(glava(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        vsebina.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        vsebina.addView(lupa(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val rob = (resources.displayMetrics.widthPixels * 0.2f).toInt()
            marginStart = rob; marginEnd = rob
        })
        vsebina.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        naslovAplikacij = TextView(this).apply {
            setText(R.string.pk_priljubljene)
            setTextColor(osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }
        vsebina.addView(naslovAplikacij)
        vrstaAplikacij = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        val drsnik = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(48))
            addView(vrstaAplikacij)
        }
        vsebina.addView(drsnik, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Naloga 42 C: Sporocila na hitro - samo, ce kaksno je (brez prazne vrstice).
        vrstaSporocil = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        vsebina.addView(vrstaSporocil, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        dok = plavajocaVrstica()
        vsebina.addView(dok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        koren.addView(vsebina, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(koren)
        if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true) Zaganjalnik.zabeleziZagonDomov(this)
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        glavna.post(tikUre)
        narisiAplikacije()
        prikaziVreme()
        osveziVreme()
        // Naloga 42 B: mini predvajalnik kot na racunalniku - kaj igra, premor/naprej/nazaj/glasnost, odpri - brez
        // zapuscanja domacega zaslona (isti dock kot na starem domacem zaslonu, MiniPredvajalnik.naOknu).
        if (dock == null) dock = MiniPredvajalnik.naOknu(this) { odpri(GlasbaStoritev.namenKartice(this)) }
        GlasbaStoritev.poslusalci.add(medijiPoslusalec)
        OddaljenoPredvajanje.poslusalci.add(medijiPoslusalec)
        dock?.osvezi()
        KlepetLinka.poslusalci.add(sporocilaPoslusalec)
        narisiSporocila()
    }

    override fun onStop() {
        KlepetLinka.poslusalci.remove(sporocilaPoslusalec)
        GlasbaStoritev.poslusalci.remove(medijiPoslusalec)
        OddaljenoPredvajanje.poslusalci.remove(medijiPoslusalec)
        dock?.ustavi()
        glavna.removeCallbacks(tikUre)
        super.onStop()
    }

    private var dock: MiniPredvajalnik? = null
    private val medijiPoslusalec: () -> Unit = { runOnUiThread { dock?.osvezi() } }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Tipka Domov na domacem zaslonu: fokus nazaj na lupo, kot pri sistemskem zaganjalniku.
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) findViewById<View>(android.R.id.content)?.findViewWithTag<View>(DomacaLupa.OZNAKA_POLJA)?.requestFocus()
    }

    /** Domaci zaslon nima kam nazaj (kot pri zaganjalniku televizorja); razprta lupa se zapre. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (DomacaLupa.nazaj(this)) return
        if (dock?.skrijCeOdprt() == true) return
        if (Zaganjalnik.jeIzbran(this)) return
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        if (DomacaLupa.tipka(this, dogodek)) return true
        return super.dispatchKeyEvent(dogodek)
    }

    // ------------------------------------------------------------------ videz (barve iz teme)

    /** Steklena plosca nad ozadjem; s fokusom obroba v barvi poudarka (na TV mora biti fokus viden z dveh metrov). */
    private fun steklo(polmerDp: Int, fokusMogoc: Boolean = true): StateListDrawable {
        fun plosca(obroba: Int, debelina: Int, ozadje: Int) = GradientDrawable().apply {
            setColor(osBarva(ozadje)); cornerRadius = dp(polmerDp).toFloat(); setStroke(dp(debelina), osBarva(obroba))
        }
        return StateListDrawable().apply {
            if (fokusMogoc) addState(intArrayOf(android.R.attr.state_focused), plosca(R.color.os_mint, 3, R.color.os_kartica_steklo_fokus))
            addState(intArrayOf(), plosca(R.color.os_kartica_obroba, 1, R.color.os_kartica_steklo))
        }
    }

    /** Povecava ob fokusu (brez nje pri zmanjsanem gibanju); obroba je v [steklo]. */
    private fun poudariFokus(v: View) {
        val prej = v.onFocusChangeListener
        v.setOnFocusChangeListener { p, ima ->
            prej?.onFocusChange(p, ima)
            val cilj = if (ima) PokrajinaPravila.povecava(zmanjsano()) else 1f
            val cas = PokrajinaPravila.trajanje(150, ValueAnimator.getDurationScale(), zmanjsano())
            if (cas == 0L) { p.scaleX = cilj; p.scaleY = cilj } else p.animate().scaleX(cilj).scaleY(cilj).setDuration(cas).start()
        }
    }

    private fun besedilo(niz: String, sp: Float, barva: Int = R.color.os_besedilo, krepko: Boolean = false) = TextView(this).apply {
        text = niz; setTextColor(osBarva(barva)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (krepko) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
    }

    // ------------------------------------------------------------------ glava

    private fun glava(): View {
        val g = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val znak = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = steklo(28, fokusMogoc = false)
            setPadding(dp(14), dp(8), dp(20), dp(8))
            addView(ImageView(this@PokrajinaActivity).apply { setImageResource(R.drawable.os_ikona_scit); setColorFilter(osBarva(R.color.os_mint)) },
                LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(besedilo(getString(R.string.os_app_name), 20f, krepko = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        g.addView(znak)
        g.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Vreme: brez izbranega kraja ga ni (ne nadomestka); OK na uri odpre izbiro kraja.
        vreme = besedilo("", 26f).apply { visibility = View.GONE }
        g.addView(vreme, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(22) })
        val cas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isFocusable = true
            background = steklo(16).apply { alpha = 0 }
            contentDescription = getString(R.string.pk_vreme_kraj)
            setOnFocusChangeListener { v, ima -> v.background.alpha = if (ima) 255 else 0 }
            setOnClickListener { izberiKraj() }
        }
        ura = besedilo("", 34f, krepko = true).apply { gravity = Gravity.END }
        datum = besedilo("", 16f, R.color.os_umirjeno).apply { gravity = Gravity.END }
        cas.addView(ura); cas.addView(datum)
        g.addView(cas)
        return g
    }

    // ------------------------------------------------------------------ lupa

    private fun lupa(): View {
        val okvir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = steklo(36)
            setAddStatesFromChildren(true)
            setPadding(dp(22), dp(6), dp(10), dp(6))
            minimumHeight = dp(64)
        }
        okvir.addView(ImageView(this).apply { setImageResource(R.drawable.os_ikona_isci); setColorFilter(osBarva(R.color.os_besedilo)) },
            LinearLayout.LayoutParams(dp(30), dp(30)))
        val polje = EditText(this).apply {
            setSingleLine(); background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(osBarva(R.color.os_besedilo)); setHintTextColor(osBarva(R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            isFocusable = true; isFocusableInTouchMode = true
            // Daljinec: fokus tipkovnice ne odpre (sicer bi zakrila zaslon ob vsakem prehodu), OK jo odpre.
            showSoftInputOnFocus = false
            setOnKeyListener { v, koda, d ->
                if (d.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER) && !(v as EditText).showSoftInputOnFocus) {
                    v.showSoftInputOnFocus = true
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, 0)
                    true
                } else false
            }
        }
        okvir.addView(polje, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Ista lupa kot na starem domacem zaslonu: »… na TV«, spletni naslov, iskanje v Medijih; mikrofon doda DomacaLupa.
        DomacaLupa.prevzemi(this, polje, okvir) { }
        return okvir
    }

    // ------------------------------------------------------------------ priljubljene in spletne aplikacije

    private fun kartica(ikona: android.graphics.drawable.Drawable?, ikonaRes: Int, ime: String, klik: (View) -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacij, false)
        val slika = v.findViewById<ImageView>(R.id.ikona)
        if (ikona != null) slika.setImageDrawable(ikona) else slika.setImageResource(ikonaRes)
        v.findViewById<TextView>(R.id.ime).apply { text = ime; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        v.layoutParams = (v.layoutParams as ViewGroup.MarginLayoutParams).apply { width = dp(150); height = dp(128); marginEnd = dp(22) }
        v.background = steklo(18)
        v.setOnClickListener { klik(v) }
        poudariFokus(v)
        return v
    }

    private fun narisiAplikacije() {
        vrstaAplikacij.removeAllViews()
        val vsi = Aplikacije.zadnjiZnani(this) ?: Aplikacije.seznam(this)
        for (paket in Priljubljene.seznam(this)) {
            val a = vsi.firstOrNull { it.paket == paket } ?: continue
            val v = kartica(Aplikacije.ikonaIzPomnilnika(a.paket), R.drawable.os_ikona_mreza, a.ime) { k ->
                odpri(Intent(a.namera).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), izPloscice(k))
            }
            if (Aplikacije.ikonaIzPomnilnika(a.paket) == null)
                Aplikacije.ikonaLeno(this, a) { Aplikacije.ikonaIzPomnilnika(a.paket)?.let { v.findViewById<ImageView>(R.id.ikona).setImageDrawable(it) } }
            vrstaAplikacij.addView(v)
        }
        for (s in SpletneAplikacije.seznam(this)) {
            val ime = s.ime.ifBlank { SpletneAplikacije.gostitelj(s.url) }
            vrstaAplikacij.addView(kartica(SpletneAplikacije.ikona(this, s), R.drawable.os_ikona_splet, ime) { _ ->
                Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.SPLETNA, ime = ime, url = s.url))
                odpri(Brskalnik.namera(this).putExtra("spletna_aplikacija", s.url).putExtra("aplikacija_ime", ime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        // Prazna vrsta: kartica pove, kaj naredi (izbira aplikacij), ne prazen pas.
        if (vrstaAplikacij.childCount == 0) vrstaAplikacij.addView(kartica(null, R.drawable.os_ikona_mreza, getString(R.string.os_aplikacije_izberi)) {
            odpriAplikacije()
        })
    }

    // ------------------------------------------------------------------ sporocila na hitro (naloga 42 C)

    private val sporocilaPoslusalec: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed) narisiSporocila() } }

    /** Zadnji pogovori iz Sporocil (baza se bere v ozadju); zaslon jih pokaze sam, ce kaksen je. */
    private fun narisiSporocila() {
        Thread({
            val zadnja = try {
                SporocilaHitroPravila.zadnja(SporocilaShramba(this).pogovori().map {
                    SporocilaHitroPravila.Pogovor(it.id, it.kanalId, it.ime.ifBlank { it.oseba }, it.zadnje, it.neprebrano, it.cas)
                })
            } catch (e: Throwable) { Log.i(TAG, "Sporocila: ${e.javaClass.simpleName}"); emptyList() }
            glavna.post { if (!isFinishing && !isDestroyed) pokaziSporocila(zadnja) }
        }, "safeer-hitra-sporocila").start()
    }

    private fun odlozisce(): String? = try {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Throwable) { null }

    private fun gumbSporocila(napis: String, klik: () -> Unit) = besedilo(napis, 15f).apply {
        isFocusable = true; isClickable = true
        background = steklo(14)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { klik() }
    }

    private fun pokaziSporocila(zadnja: List<SporocilaHitroPravila.Pogovor>) {
        val fokusJeBil = vrstaSporocil.hasFocus()
        vrstaSporocil.removeAllViews()
        vrstaSporocil.visibility = if (zadnja.isEmpty()) View.GONE else View.VISIBLE
        val skupine = ArrayList<Pair<LinearLayout, LinearLayout>>()
        for (p in zadnja) {
            val skupina = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val dejanja = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
            val sporocilo = gumbSporocila(SporocilaHitroPravila.izsek(p.ime, 24) + ": " + SporocilaHitroPravila.izsek(p.zadnje, 48)) {
                odpri(Intent(this, SporocilaActivity::class.java))
            }
            sporocilo.maxLines = 1; sporocilo.ellipsize = TextUtils.TruncateAt.END; sporocilo.maxWidth = dp(420)
            if (p.neprebrano > 0) sporocilo.setTextColor(osBarva(R.color.os_mint))
            skupina.addView(sporocilo)
            skupina.addView(dejanja, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            // Dejanja se pokazejo, ko fokus pride na sporocilo; odlozisce se prebere takrat (kopirano se medtem spremeni).
            val pokazi = {
                dejanja.removeAllViews()
                for (d in SporocilaHitroPravila.dejanja(p.zadnje, odgovor = true, odlozisce = odlozisce())) {
                    val g = when (d) {
                        SporocilaHitroPravila.KOPIRAJ -> gumbSporocila(getString(R.string.pk_kopiraj)) { kopiraj(p.zadnje) }
                        SporocilaHitroPravila.ODGOVORI -> gumbSporocila(getString(R.string.pk_odgovori)) { odgovori(p) }
                        else -> gumbSporocila(getString(R.string.pk_poslji_kopirano)) { posljiKopirano(p) }
                    }
                    g.setOnFocusChangeListener { _, _ -> vrstaSporocil.post { uskladiDejanja(skupine) } }
                    dejanja.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                }
                dejanja.visibility = View.VISIBLE
            }
            sporocilo.setOnFocusChangeListener { _, ima ->
                if (ima && dejanja.visibility != View.VISIBLE) pokazi()
                vrstaSporocil.post { uskladiDejanja(skupine) }
            }
            skupine.add(skupina to dejanja)
            vrstaSporocil.addView(skupina, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        }
        // Fokus je bil v vrstici, ki se je prerisala (novo sporocilo): ostane na prvem sporocilu ali gre v spodnjo vrstico.
        if (fokusJeBil) ((vrstaSporocil.getChildAt(0) as? ViewGroup)?.getChildAt(0) ?: dok.getChildAt(0))?.requestFocus()
    }

    /** Dejanja ostanejo samo ob sporocilu, ki ima fokus (ali ga ima kateri od njegovih gumbov). */
    private fun uskladiDejanja(skupine: List<Pair<LinearLayout, LinearLayout>>) {
        for ((skupina, dejanja) in skupine) if (!skupina.hasFocus()) dejanja.visibility = View.GONE
    }

    private fun kopiraj(besedilo: String) {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.os_meni_sporocila), besedilo))
            android.widget.Toast.makeText(this, R.string.pk_kopirano, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
    }

    private fun odgovori(p: SporocilaHitroPravila.Pogovor) {
        val polje = EditText(this).apply {
            hint = getString(R.string.pk_odgovor_namig, p.ime)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(p.ime)
            .setMessage(SporocilaHitroPravila.izsek(p.zadnje))
            .setView(polje)
            .setPositiveButton(R.string.pk_poslji) { _, _ -> poslji(p, polje.text.toString()) }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    /** Kopirano gre sele po potrditvi: v odlozisu je lahko kaj, cesar uporabnik ne misli poslati. */
    private fun posljiKopirano(p: SporocilaHitroPravila.Pogovor) {
        val kopirano = SporocilaHitroPravila.zaPosiljanje(odlozisce()) ?: return
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.pk_poslji_kopirano_naslov, p.ime))
            .setMessage(SporocilaHitroPravila.izsek(kopirano, 300))
            .setPositiveButton(R.string.pk_poslji) { _, _ -> poslji(p, kopirano) }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun poslji(p: SporocilaHitroPravila.Pogovor, vnos: String) {
        val besedilo = SporocilaHitroPravila.zaPosiljanje(vnos) ?: return
        Thread({
            val napaka = try { SporocilaKanali.poslji(this, SporocilaShramba(this), p.kanalId, p.id, besedilo); null } catch (e: Throwable) { e }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                if (napaka == null) {
                    android.widget.Toast.makeText(this, R.string.pk_poslano, android.widget.Toast.LENGTH_SHORT).show()
                    narisiSporocila()
                } else {
                    Log.i(TAG, "Odgovor ni poslan: ${SporocilaKanali.razlog(napaka)}")
                    android.widget.Toast.makeText(this, R.string.pk_ni_poslano, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "safeer-hitri-odgovor").start()
    }

    // ------------------------------------------------------------------ plavajoca vrstica

    private fun plavajocaVrstica(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = steklo(26, fokusMogoc = false)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        for (r in PokrajinaPravila.DOK) {
            val (ikona, napis) = when (r) {
                PokrajinaPravila.MEDIJI -> R.drawable.os_ikona_predvajaj to R.string.pk_mediji
                PokrajinaPravila.SPLET -> R.drawable.os_ikona_splet to R.string.os_meni_splet
                PokrajinaPravila.LINK -> R.drawable.os_ikona_link to R.string.os_meni_naprave
                PokrajinaPravila.SPOROCILA -> R.drawable.os_ikona_sporocila to R.string.os_meni_sporocila
                PokrajinaPravila.APLIKACIJE -> R.drawable.os_ikona_aplikacije to R.string.pk_aplikacije
                PokrajinaPravila.DATOTEKE -> R.drawable.os_ikona_datoteke to R.string.os_meni_datoteke
                else -> R.drawable.os_ikona_nastavitve to R.string.os_meni_nastavitve
            }
            val gumb = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                background = steklo(18).apply { alpha = 0 }
                setPadding(dp(8), dp(8), dp(8), dp(6))
                tag = "dok:$r"
                setOnFocusChangeListener { p, ima -> p.background.alpha = if (ima) 255 else 0 }
                setOnClickListener { odpriRazdelek(r) }
                addView(ImageView(this@PokrajinaActivity).apply { setImageResource(ikona); setColorFilter(osBarva(R.color.os_mint)) },
                    LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(besedilo(getString(napis), 15f).apply {
                    gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            poudariFokus(gumb)
            v.addView(gumb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        return v
    }

    private fun odpriAplikacije() =
        odpri(Intent(this, AplikacijeHostaActivity::class.java).putExtra(AplikacijeHostaActivity.EXTRA_VIR, "vse"))

    private fun odpriRazdelek(r: String) {
        when (r) {
            PokrajinaPravila.MEDIJI -> odpri(Intent(this, GlasbaActivity::class.java))
            PokrajinaPravila.SPLET -> odpri(Brskalnik.namera(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            PokrajinaPravila.LINK -> odpri(Intent(this, NapraveActivity::class.java))
            PokrajinaPravila.SPOROCILA -> odpri(Intent(this, SporocilaActivity::class.java))
            PokrajinaPravila.APLIKACIJE -> odpriAplikacije()
            PokrajinaPravila.DATOTEKE -> odpri(Intent(this, DatotekeActivity::class.java))
            else -> odpri(Intent(this, NastavitveActivity::class.java))
        }
    }

    /** Odpiranje, ki ne utihne: napaka se pokaze (kot na starem domacem zaslonu), ne izgine. */
    private fun odpri(namera: Intent, moznosti: Bundle? = null) {
        try { startActivity(namera, moznosti) }
        catch (e: Throwable) {
            Log.w(TAG, "Odpiranje ni uspelo: ${e.javaClass.simpleName}")
            android.widget.Toast.makeText(this, getString(R.string.os_odpri_napaka, getString(
                if (e is android.content.ActivityNotFoundException) R.string.os_odpri_ni_aplikacije else R.string.os_odpri_ni_dovoljenja)),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ vreme (samo za kraj, ki ga izbere uporabnik)

    private fun shramba() = getSharedPreferences("safeer_os", Context.MODE_PRIVATE)

    private fun kraj(): VremePravila.Kraj? {
        val s = shramba()
        val ime = s.getString(KLJUC_KRAJ_IME, null)?.takeIf { it.isNotBlank() } ?: return null
        val sirina = s.getString(KLJUC_KRAJ_SIRINA, null)?.toDoubleOrNull() ?: return null
        val dolzina = s.getString(KLJUC_KRAJ_DOLZINA, null)?.toDoubleOrNull() ?: return null
        return VremePravila.Kraj(ime, "", sirina, dolzina)
    }

    private fun prikaziVreme() {
        val s = shramba()
        val cas = s.getLong(KLJUC_VREME_CAS, 0L)
        if (kraj() == null || !VremePravila.svezo(cas, System.currentTimeMillis())) { vreme.visibility = View.GONE; return }
        val v = VremePravila.Vreme(s.getInt(KLJUC_VREME_STOPINJ, 0), s.getInt(KLJUC_VREME_KODA, 0))
        vreme.text = ikona(VremePravila.vrsta(v.koda)) + "  " + VremePravila.napis(v)
        vreme.contentDescription = getString(R.string.pk_vreme_opis, kraj()?.ime.orEmpty(), VremePravila.napis(v))
        vreme.visibility = View.VISIBLE
    }

    private fun ikona(vrsta: String): String = when (vrsta) {
        VremePravila.JASNO -> "☀️"
        VremePravila.DELNO -> "⛅"
        VremePravila.MEGLA -> "🌫️"
        VremePravila.DEZ -> "🌧️"
        VremePravila.SNEG -> "❄️"
        VremePravila.NEVIHTA -> "⛈️"
        else -> "☁️"
    }

    private fun osveziVreme(prisilno: Boolean = false) {
        val k = kraj() ?: return
        if (!prisilno && !VremePravila.osvezi(shramba().getLong(KLJUC_VREME_CAS, 0L), System.currentTimeMillis())) return
        Thread({
            val v = beri(VremePravila.naslovNapovedi(k))?.let { VremePravila.razcleniVreme(it) } ?: return@Thread
            shramba().edit().putInt(KLJUC_VREME_STOPINJ, v.stopinj).putInt(KLJUC_VREME_KODA, v.koda)
                .putLong(KLJUC_VREME_CAS, System.currentTimeMillis()).apply()
            glavna.post { if (!isFinishing && !isDestroyed) prikaziVreme() }
        }, "safeer-vreme").start()
    }

    /** Izbira kraja za vreme; prazno izklopi vreme (in izbrise zadnje vreme). */
    private fun izberiKraj() {
        val polje = EditText(this).apply {
            setSingleLine(); setText(kraj()?.ime.orEmpty()); hint = getString(R.string.pk_vreme_kraj_namig)
        }
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.pk_vreme_kraj)
            .setMessage(R.string.pk_vreme_kraj_opis)
            .setView(polje)
            .setPositiveButton(android.R.string.ok) { _, _ -> shraniKraj(polje.text.toString()) }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun shraniKraj(vnos: String) {
        val naslov = VremePravila.naslovIskanja(vnos, jezik().language)
        if (naslov == null) {
            shramba().edit().remove(KLJUC_KRAJ_IME).remove(KLJUC_KRAJ_SIRINA).remove(KLJUC_KRAJ_DOLZINA).remove(KLJUC_VREME_CAS).apply()
            prikaziVreme(); return
        }
        Thread({
            val k = beri(naslov)?.let { VremePravila.razcleniKraj(it) }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                if (k == null) { android.widget.Toast.makeText(this, R.string.pk_vreme_ni_najden, android.widget.Toast.LENGTH_LONG).show(); return@post }
                shramba().edit().putString(KLJUC_KRAJ_IME, k.ime).putString(KLJUC_KRAJ_SIRINA, k.sirina.toString())
                    .putString(KLJUC_KRAJ_DOLZINA, k.dolzina.toString()).remove(KLJUC_VREME_CAS).apply()
                osveziVreme(prisilno = true)
            }
        }, "safeer-vreme-kraj").start()
    }

    /** GET z rokom, brez preusmeritev drugam, najvec 64 KiB; null ob napaki ali odgovoru, ki ni 200. */
    private fun beri(naslov: String): String? = try {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 6_000; p.readTimeout = 6_000
        p.instanceFollowRedirects = false
        p.setRequestProperty("User-Agent", "Safeer OS (https://safeer.si)")
        p.setRequestProperty("Accept", "application/json")
        try {
            if (p.responseCode != 200) null
            else p.inputStream.use { vhod ->
                val bajti = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var n = vhod.read(buf)
                while (n >= 0 && bajti.size() <= 64 * 1024) {
                    bajti.write(buf, 0, n)
                    n = vhod.read(buf)
                }
                if (bajti.size() > 64 * 1024) null else bajti.toString("UTF-8")
            }
        } finally { p.disconnect() }
    } catch (e: Throwable) { Log.i(TAG, "Vreme: ${e.javaClass.simpleName}"); null }

    companion object {
        private const val TAG = "SafeerPokrajina"
        const val KLJUC_ZMANJSANO = "pokrajina_zmanjsano_gibanje"
        private const val KLJUC_KRAJ_IME = "vreme_kraj_ime"
        private const val KLJUC_KRAJ_SIRINA = "vreme_kraj_sirina"
        private const val KLJUC_KRAJ_DOLZINA = "vreme_kraj_dolzina"
        private const val KLJUC_VREME_CAS = "vreme_cas"
        private const val KLJUC_VREME_STOPINJ = "vreme_stopinj"
        private const val KLJUC_VREME_KODA = "vreme_koda"
    }
}
