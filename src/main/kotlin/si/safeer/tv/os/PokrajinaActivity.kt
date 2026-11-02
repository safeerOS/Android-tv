package si.safeer.tv.os
import android.content.ComponentName
import android.widget.Toast

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
import android.widget.ImageButton
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
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val linkPoslusalec = object : LinkOdjemalec.Poslusalec {
        override fun naStanje(povezan: Boolean, sporocilo: String) { osveziLinkPiko() }
        override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) { osveziLinkPiko() }
        override fun naNaslov(url: String, naslov: String, od: String) {}
        override fun naBesedilo(besedilo: String, od: String) {}
        override fun naZavrnitev() {}
    }
    private fun osveziLinkPiko() {
        val pika = dok.findViewWithTag<View>("link_pika") ?: return
        val vidna = link.povezan && link.naprave.any {
            !link.jeTaNaprava(it) && (OzjiKrog.vKrogu(this@PokrajinaActivity, it.id) || it.zmoznosti.contains(si.safeer.tv.link.Daljinec.ZMOZNOST_DELI_ZASLON))
        }
        pika.visibility = if (vidna) View.VISIBLE else View.GONE
    }
    private lateinit var koren: FrameLayout
    private lateinit var zatemnitevOzadja: View
    private lateinit var ura: TextView
    private lateinit var datum: TextView
    private lateinit var vreme: TextView
    /** Tedenska napoved vodoravno v glavi, levo od temperature (lastnik 30. 10. 2026). */
    private lateinit var teden: HorizontalScrollView
    private lateinit var tedenVrsta: LinearLayout
    private lateinit var vrstaNadaljuj: LinearLayout
    private lateinit var okvirNadaljuj: View
    private lateinit var vrstaAplikacij: LinearLayout
    private lateinit var dok: LinearLayout
    private lateinit var preliv: View
    private lateinit var zadetki: LinearLayout
    /** Drsnik zadetkov: vidnost lupe je na njem (zadetki sami so vedno vidni v njem). */
    private lateinit var zadetkiDrsnik: View
    private lateinit var vsebina: LinearLayout
    private lateinit var poljeLupe: EditText

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
            ura.text = PokrajinaPravila.ura(zdaj, jezik(), TimeZone.getDefault(), android.text.format.DateFormat.is24HourFormat(this@PokrajinaActivity))
            datum.text = PokrajinaPravila.datum(zdaj, jezik(), TimeZone.getDefault())
            // Lastnik 27. 10. 2026: vreme vsaj priblizno sveze tudi, ko je domaci zaslon ves vecer na TV - prej samo ob
            // odprtju zaslona. Klic gre na splet najvec na VremePravila.OSVEZI_MS (30 min), sicer nic.
            osveziVreme()
            glavna.postDelayed(this, PokrajinaPravila.doNaslednjeMinute(zdaj))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        koren = FrameLayout(this)
        zatemnitevOzadja = View(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
        }
        koren.addView(zatemnitevOzadja, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        vsebina = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        uporabiVarenRob()
        vsebina.addView(glava(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        vsebina.addView(lupa(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val rob = (resources.displayMetrics.widthPixels * 0.2f).toInt()
            marginStart = rob; marginEnd = rob; topMargin = dp(10)
        })
        val tipkovnica = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "lupaTipkovnica"
        }
        vsebina.addView(tipkovnica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val rob = (resources.displayMetrics.widthPixels * 0.2f).toInt()
            marginStart = rob; marginEnd = rob; topMargin = dp(4)
        })

        val okvirVsebine = FrameLayout(this)
        val spodnjiDel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Naloga 49: Nadaljuj vrsta nad aplikacijami
        okvirNadaljuj = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val naslovNadaljuj = naslovSkupine(R.string.pk_nadaljuj).apply {
            setPadding(dp(14), dp(4), 0, dp(4))
        }
        (okvirNadaljuj as LinearLayout).addView(naslovNadaljuj)
        vrstaNadaljuj = object : LinearLayout(this) {
            override fun focusSearch(direction: Int): View? {
                if (direction == View.FOCUS_DOWN) {
                    if (::vrstaAplikacij.isInitialized && vrstaAplikacij.childCount > 0) {
                        najblizjiPogledX(vrstaAplikacij, findFocus())?.let { return it }
                    }
                }
                return super.focusSearch(direction)
            }
        }.apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        val drsnikNadaljuj = object : HorizontalScrollView(this) {
            override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean {
                val rob = dp(48 + 12)
                rectangle.left -= rob
                rectangle.right += rob
                return super.requestChildRectangleOnScreen(child, rectangle, immediate)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(48))
            addView(vrstaNadaljuj)
        }
        (okvirNadaljuj as LinearLayout).addView(drsnikNadaljuj, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        
        // Vstavimo ga pred vrsto aplikacij (okvirDrsnika)
        spodnjiDel.addView(okvirNadaljuj, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // Lastnik 27. 10. 2026: ENA vrstica - priljubljene spletne in namescene aplikacije in »+ Dodaj«, plosce po zgledu
        // (zaobljene, brez obrobe; obroba samo s fokusom). Vse aplikacije so za gumbom Aplikacije v spodnji vrstici.
        vrstaAplikacij = object : LinearLayout(this) {
            override fun focusSearch(direction: Int): View? {
                if (direction == View.FOCUS_DOWN) {
                    val mini = dock?.aktivniPogledZaFokus
                    if (mini != null && mini.isShown && mini.isFocusable) return mini
                    if (::dok.isInitialized) {
                        najblizjiPogledX(dok, findFocus())?.let { return it }
                    }
                }
                return super.focusSearch(direction)
            }
        }.apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
        val drsnik = object : HorizontalScrollView(this) {
            override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean {
                val rob = dp(48 + 12)
                rectangle.left -= rob
                rectangle.right += rob
                return super.requestChildRectangleOnScreen(child, rectangle, immediate)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(48))
            addView(vrstaAplikacij)
        }
        val okvirDrsnika = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        okvirDrsnika.addView(drsnik, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val puscica = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(osBarva(R.color.os_besedilo))
            contentDescription = getString(R.string.pk_vec)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        okvirDrsnika.addView(puscica, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        
        val osveziPuscico = {
            val vidno = drsnik.width > 0 && drsnik.scrollX < drsnik.getChildAt(0).width - drsnik.width - dp(10)
            val zadnjaSFokusom = vrstaAplikacij.childCount > 0 && vrstaAplikacij.getChildAt(vrstaAplikacij.childCount - 1).hasFocus()
            puscica.visibility = if (vidno && !zadnjaSFokusom) View.VISIBLE else View.GONE
        }
        drsnik.setOnScrollChangeListener { _, _, _, _, _ -> osveziPuscico() }
        vrstaAplikacij.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> osveziPuscico() }
        vrstaAplikacij.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> osveziPuscico() }

        // Lastnik 28. 10. 2026: vrstica pod iskalno lupo - mini predvajalnik (nad spodnjo vrstico) je ne prekriva vec.
        spodnjiDel.addView(okvirDrsnika, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        spodnjiDel.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        dok = plavajocaVrstica()
        dok.addOnLayoutChangeListener { _, _, t, _, b, _, st, _, sb -> if (t != st || b != sb) { robDocka(); prilagodiVisinoPlosc() } }
        spodnjiDel.addView(dok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        okvirVsebine.addView(spodnjiDel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Naloga 42 E: zadetki lupe kot na racunalniku (programi, datoteke, nastavitve, mediji, splet) - samo med iskanjem.
        zadetki = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = steklo(20, fokusMogoc = false)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        zadetkiDrsnik = android.widget.ScrollView(this).apply {
            visibility = View.GONE; tag = "zadetkiDrsnik"
            addView(zadetki, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        okvirVsebine.addView(zadetkiDrsnik, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val rob = (resources.displayMetrics.widthPixels * 0.2f).toInt()
            marginStart = rob; marginEnd = rob; topMargin = dp(8); bottomMargin = dp(14) // prostor do spodnje vrstice
        })
        vsebina.addView(okvirVsebine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        koren.setOnLongClickListener { menijOzadja(); true }

        preliv = View(this)
        koren.addView(preliv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        koren.addView(vsebina, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(koren)
        if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true) Zaganjalnik.zabeleziZagonDomov(this)
        if (Zaganjalnik.vrnjenPoPadcu(this)) pokaziVrnjenPoPadcu()
    }

    /** Naloga 49 I: dolg pritisk na ozadje (miska ali dotik) - Videz in, ce sme, vrnitev prejsnjega zaganjalnika. */
    private fun menijOzadja() {
        val dejanja = mutableListOf(
            getString(R.string.os_videz) to { odpri(Intent(this, VidezActivity::class.java)) }
        )
        if (Zaganjalnik.smeOpustiti(this)) {
            dejanja.add(getString(R.string.pk_zaganjalnik_opusti) to { Zaganjalnik.vrniTovarniskega(this) })
        }
        val okno = android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    /** Varen rob (Videz, privzeto 40 dp kot prej); ob vsakem onStart, da sprememba v Videzu velja brez ponovnega zagona. */
    private fun uporabiVarenRob() {
        val rob = PokrajinaPravila.varenRob(shramba().getInt(PokrajinaPravila.KLJUC_VAREN_ROB, PokrajinaPravila.VAREN_ROB_DP))
        vsebina.setPadding(dp(rob), dp(24), dp(rob), dp(18))
    }

    private fun pokaziVrnjenPoPadcu() {
        android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setTitle(R.string.zg_padec_naslov)
            .setMessage(R.string.nz_padec_opis)
            .setPositiveButton(R.string.zg_padec_znova) { _, _ -> Zaganjalnik.zahtevajVlogo(this) }
            .setNegativeButton(android.R.string.ok, null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /**
     * Lastnik 1. 11. 2026: Claude je ostal v pomnilniku (170 MB), ceprav ga ze ure ni uporabljal - zapiranje ob menjavi
     * zajame samo aplikacijo, ki jo uporabnik ravnokar zapusti. Ob vrnitvi na domaci zaslon zato pocistimo vse
     * aplikacije v ozadju (kot DomovActivity); glasba, ki igra, ostane (Pomnilnik.pocistiOzadje meri zvok).
     */
    override fun onResume() {
        super.onResume()
        // Naloga 45 G: ponudba »Dodam na dom?« sele ob vrnitvi domov - zagon aplikacije ni nikoli cakal na okno.
        // Ponudba se ob prikazu pobrise, zato je najvec enkrat.
        glavna.post { if (!isFinishing && !isDestroyed) Priljubljene.ponudiCakajoco(this) { narisiAplikacije() } }
    }

    private val usbSprejemnik = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            narisiAplikacije()
        }
    }


    private var zadnjiFokusPogled: View? = null
    private val posodobiZnacke = { osveziDokZnacke() }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(usbSprejemnik, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbSprejemnik, filter)
        link.dodaj(linkPoslusalec)
        osveziLinkPiko()
        uporabiVarenRob()
        Ozadje.uporabi(this, koren)
        // Po zgledu tv-simple-home (Scrim): ura zgoraj in spodnja vrstica ostaneta berljivi tudi na svetli sliki.
        val alfa = PokrajinaPravila.alfaPreliva(Ozadje.zatemnitev(this))
        preliv.background = if (alfa == 0) null else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(alfa shl 24, 0, 0, alfa shl 24))
        glavna.post(tikUre)
        
        narisiNadaljuj()
        narisiAplikacije()
        // Naloga 49 D: zadnji fokus (ploscica, spodnji gumb ali lupa), ce pogled se obstaja; sicer ista ploscica po oznaki.
        val f = zadnjiFokusPogled
        if (f != null && f.isAttachedToWindow && f.isShown) {
            f.post { f.requestFocus() }
        } else {
            val izbrana = zadnjaPloscica?.let { koren.findViewWithTag<View>(it) }
                ?: (0 until vrstaAplikacij.childCount).map { vrstaAplikacij.getChildAt(it) }.firstOrNull { it.isFocusable }
            izbrana?.let { v -> v.post { v.requestFocus() } }
        }
        zbudi(animiraj = false)
        prikaziVreme()
        osveziVreme()
        // Naloga 42 B: mini predvajalnik kot na racunalniku - kaj igra, premor/naprej/nazaj/glasnost, odpri - brez
        // zapuscanja domacega zaslona (isti dock kot na starem domacem zaslonu, MiniPredvajalnik.naOknu).
        if (dock == null) dock = MiniPredvajalnik.naOknu(this) { odpri(GlasbaStoritev.namenKartice(this)) }
        // Vodja 27. 10. 2026 (TV beta 14): dock je bil cez spodnjo vrstico (med »Safeer Media« in »Settings« ikone niso bile
        // vidne) - pusti ji prostor; ob vsaki postavitvi vrstice znova (zasuk, velikost pisave).
        dok.post { robDocka() }
        GlasbaStoritev.poslusalci.add(medijiPoslusalec)
        OddaljenoPredvajanje.poslusalci.add(medijiPoslusalec)
        ZnackeObvestil.poslusalci.add(posodobiZnacke)
        Posodobitve.preveri(this) { _, _ -> if (!isFinishing) osveziDokZnacke() }
        dock?.osvezi()
    }


    override fun onStop() {
        ZnackeObvestil.poslusalci.remove(posodobiZnacke)
        zadnjiFokusPogled = currentFocus
        try { unregisterReceiver(usbSprejemnik) } catch (_: Throwable) { }
        link.odstrani(linkPoslusalec)
        glavna.removeCallbacks(preveriMirovanje)
        zbudi(animiraj = false)
        glavna.removeCallbacks(preveriMirovanje)
        GlasbaStoritev.poslusalci.remove(medijiPoslusalec)
        OddaljenoPredvajanje.poslusalci.remove(medijiPoslusalec)
        dock?.ustavi()
        glavna.removeCallbacks(tikUre)
        // Medijski center ali drug program iz lupe: ob vrnitvi domaci zaslon brez starega iskanja (vodja 27. 10. 2026).
        zapriLupo()
        super.onStop()
    }

    private var dock: MiniPredvajalnik? = null

    /** Prostor pod dockom: od vrha spodnje vrstice do roba zaslona in se malo razmika. */
    private fun robDocka() {
        if (!::koren.isInitialized || dok.height == 0) return
        dock?.nastaviRob(koren.height - dok.top + dp(8))
    }
    private val medijiPoslusalec: () -> Unit = { runOnUiThread { dock?.osvezi() } }

    /**
     * Preverba TV 28. 10. 2026 (beta 30): po tipki Domov je tipkovnica ostala odprta iz prejsnjega okna, fokus pa je bil na
     * iskalni lupi. Domaci zaslon tipkovnice nikoli ne pokaze sam (manifest stateAlwaysHidden); odpre jo samo OK na lupi.
     */
    override fun onWindowFocusChanged(imaFokus: Boolean) {
        super.onWindowFocusChanged(imaFokus)
        if (imaFokus && ::poljeLupe.isInitialized && !poljeLupe.showSoftInputOnFocus)
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Tipka Domov na domacem zaslonu (vodja 27. 10. 2026): razprta lupa se zapre in pokaze se domaci zaslon - prej je
        // Domov fokus namenoma vrnil v lupo in lupe z daljincem ni bilo mogoce zapreti.
        // Lastnik 1. 11. 2026: tipko Domov sistem ne poslje v dispatchKeyEvent, ampak kot namero HOME - tudi ta mora
        // zbuditi zaslon iz mirovanja (prvi pritisk samo zbudi, kot pri drugih tipkah).
        setIntent(intent)
        // Pregled Grok 2. 11. 2026: Domov konca tudi premikanje ploscice (prej je levo/desno po Domov se premikalo).
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) { koncajPremikanje(); koncajPremikanjeDok() }
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true && mirujem) { zbudi(); return }
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) zapriLupo()
    }

    /** Nazaj, Domov, odhod z zaslona: besedilo, predlogi in tipkovnica stran, fokus s polja navzdol na ploscice. */
    private fun zapriLupo(): Boolean {
        if (!::poljeLupe.isInitialized) return false
        // Preverba TV 28. 10. 2026 (beta 29): po OK v lupi je ostalo showSoftInputOnFocus = true, tudi ko lupa ni bila odprta -
        // ob tipki Domov je polje dobilo fokus in tipkovnica se je odprla sama. Fokus spet ne odpre tipkovnice, OK jo odpre.
        poljeLupe.showSoftInputOnFocus = false
        val tipkovnica = DomacaLupa.tipkovnicaOdprta(this)
        val vLupi = poljeLupe.hasFocus() || zadetkiDrsnik.hasFocus() || tipkovnica
        if (!PokrajinaPravila.zapriLupo(poljeLupe.text, zadetkiDrsnik.visibility == View.VISIBLE || tipkovnica, vLupi)) return false
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(poljeLupe.windowToken, 0)
        DomacaLupa.zapriTipkovnico(this)
        // Pregled 2. 11. 2026: setText("") sprozi TextWatcher - zamik odstranimo PO njem, sicer se je 250 ms pozneje
        // zgodovina iskanja spet pokazala cez ploscice.
        poljeLupe.setText("")
        glavna.removeCallbacks(iskanjeZamik)
        iskanjeSt++
        iskanjePoizvedba = ""
        zadetki.removeAllViews(); zadetkiDrsnik.visibility = View.GONE
        if (vLupi) { val dol = poljeLupe.focusSearch(View.FOCUS_DOWN); if (dol != null && dol !== poljeLupe) dol.requestFocus() else poljeLupe.clearFocus() }
        return true
    }

    /** Domaci zaslon nima kam nazaj (kot pri zaganjalniku televizorja); razprta lupa se zapre. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Nazaj zapira plasti od zgoraj: najprej vrstica znakov (fokus nazaj v polje), nato lupa.
        if (DomacaLupa.zapriTipkovnico(this)) { poljeLupe.requestFocus(); return }
        if (zapriLupo()) return
        if (DomacaLupa.nazaj(this)) return
        if (dock?.skrijCeOdprt() == true) return
        if (Zaganjalnik.jeIzbran(this)) return
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        val izvirnaKoda = dogodek.keyCode
        // Plosck A/B kot OK/Nazaj; ponovitev in cas ostaneta (dolg pritisk A premakne ploscico kot dolg OK).
        fun kot(koda: Int) = KeyEvent(dogodek.downTime, dogodek.eventTime, dogodek.action, koda, dogodek.repeatCount,
            dogodek.metaState, dogodek.deviceId, dogodek.scanCode, dogodek.flags, dogodek.source)
        val pretvorjen = if (izvirnaKoda == KeyEvent.KEYCODE_BUTTON_A) kot(KeyEvent.KEYCODE_DPAD_CENTER)
            else if (izvirnaKoda == KeyEvent.KEYCODE_BUTTON_B) kot(KeyEvent.KEYCODE_BACK)
            else dogodek
        
        // Pregled Grok 2. 11. 2026: v mirovanju prvi Nazaj najprej zbudi zaslon, tedenska napoved se skrije sele z naslednjim.
        if (!mirujem && pretvorjen.keyCode == KeyEvent.KEYCODE_BACK && ::teden.isInitialized && teden.visibility == View.VISIBLE) {
            zadnjiVnos = android.os.SystemClock.uptimeMillis()
            if (pretvorjen.action == KeyEvent.ACTION_UP) skrijTeden()
            return true
        }
        // Mirovanje: prvi pritisk samo zbudi vmesnik (tudi njegov ACTION_UP ne naredi nicesar).
        if (pretvorjen.action == KeyEvent.ACTION_DOWN) {
            zadnjiVnos = android.os.SystemClock.uptimeMillis()
            if (mirujem) { zbudi(); pogoltnjene.add(pretvorjen.keyCode); return true }
            if (pretvorjen.repeatCount > 0 && pretvorjen.keyCode in pogoltnjene) return true
        } else if (pretvorjen.action == KeyEvent.ACTION_UP && pogoltnjene.remove(pretvorjen.keyCode)) return true
        // Nacin premikanja plosce (lastnik 28. 10. 2026): levo/desno premika, OK/Nazaj konca.
        premikam?.let { id ->
            val t = PokrajinaPravila.tipkaPremikanja(pretvorjen.keyCode)
            if (t != null) {
                // Ponovitve drzanega OK (dolg pritisk, ki je premikanje zacel) premikanja ne koncajo.
                if (pretvorjen.action == KeyEvent.ACTION_DOWN && !(t == 0 && pretvorjen.repeatCount > 0)) {
                    if (t == 0) koncajPremikanje() else premakniPloscico(id, t)
                }
                return true
            }
        }
        premikamDok?.let { id ->
            val t = PokrajinaPravila.tipkaPremikanja(pretvorjen.keyCode)
            if (t != null) {
                if (pretvorjen.action == KeyEvent.ACTION_DOWN && !(t == 0 && pretvorjen.repeatCount > 0)) {
                    if (t == 0) koncajPremikanjeDok() else premakniDok(id, t)
                }
                return true
            }
        }
        
        val koda = pretvorjen.keyCode
        if (PokrajinaPravila.jeMedijska(koda)) {
            val igra = try { GlasbaStoritev.trenutna() != null || GlasbaStoritev.tece() } catch (_: Throwable) { false }
            if (igra) {
                if (pretvorjen.action == KeyEvent.ACTION_DOWN) {
                    try {
                        when (koda) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> GlasbaStoritev.nadaljujTu()
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> GlasbaStoritev.premorTu()
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> GlasbaStoritev.preklopiTu()
                            KeyEvent.KEYCODE_MEDIA_NEXT -> GlasbaStoritev.naslednjaTu()
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> GlasbaStoritev.prejsnjaTu()
                        }
                    } catch (_: Throwable) {}
                }
                return true
            }
        }
        if (koda == KeyEvent.KEYCODE_VOLUME_UP || koda == KeyEvent.KEYCODE_VOLUME_DOWN || koda == KeyEvent.KEYCODE_VOLUME_MUTE) {
            val naZvocniku = try { GlasbaStoritev.naZvocniku() && GlasbaStoritev.trenutna() != null } catch (_: Throwable) { false }
            if (naZvocniku) {
                if (pretvorjen.action == KeyEvent.ACTION_DOWN) {
                    val smer = if (koda == KeyEvent.KEYCODE_VOLUME_UP) 1 else if (koda == KeyEvent.KEYCODE_VOLUME_DOWN) -1 else 0
                    if (smer != 0) try {
                        si.safeer.tv.os.Zvocniki.glasnostZa(smer * si.safeer.tv.os.ZvocnikPravila.korakGlasnosti(si.safeer.tv.os.Zvocniki.najGlasnost, si.safeer.tv.os.Zvocniki.lestvicaZnana))
                    } catch (_: Throwable) {}
                }
                return true
            }
        }

        val tipkovnicaOdprta = ::poljeLupe.isInitialized && poljeLupe.hasFocus() && poljeLupe.showSoftInputOnFocus
        val lupaImaFokus = ::poljeLupe.isInitialized && poljeLupe.hasFocus()
        val lupaImaBesedilo = ::poljeLupe.isInitialized && poljeLupe.text?.isNotEmpty() == true

        if (pretvorjen.action == KeyEvent.ACTION_DOWN && !tipkovnicaOdprta && pretvorjen.repeatCount == 0) {
            val indeks = PokrajinaPravila.indeksPloscice(koda)
            if (indeks != null && !lupaImaBesedilo && !lupaImaFokus) {
                var vidne = 0
                val drsnikX = (vrstaAplikacij.parent as? android.widget.HorizontalScrollView)?.scrollX ?: 0
                for (ind in 0 until vrstaAplikacij.childCount) {
                    val v = vrstaAplikacij.getChildAt(ind)
                    if (!v.isFocusable || !v.isShown) continue      // besedilo prazne vrstice ni ploscica
                    if (v.right > drsnikX) {
                        if (vidne == indeks) { v.performClick(); return true }
                        vidne++
                    }
                }
                return true
            }
            PokrajinaPravila.smerStran(koda)?.let { smer ->
                (vrstaAplikacij.parent as? android.widget.HorizontalScrollView)?.let { drsnik ->
                    val premik = if (smer > 0) drsnik.width else -drsnik.width
                    drsnik.smoothScrollBy(premik, 0)
                }
                return true
            }
        }

        if (pretvorjen.action == KeyEvent.ACTION_DOWN && currentFocus !is android.widget.EditText) {
            if (DomacaLupa.znakTipke(pretvorjen) || pretvorjen.keyCode == KeyEvent.KEYCODE_DEL) {
                if (::poljeLupe.isInitialized) {
                    poljeLupe.requestFocus()
                    if (pretvorjen.keyCode != KeyEvent.KEYCODE_DEL) {
                        poljeLupe.append(String(Character.toChars(pretvorjen.unicodeChar)))
                    } else if (poljeLupe.text.isNotEmpty()) {
                        poljeLupe.text.delete(poljeLupe.text.length - 1, poljeLupe.text.length)
                    }
                    return true
                }
            }
        }

        // Tipka Meni / Nastavitve na daljincu (Menu, Settings, rdeči gumb na Philips TV): odpre možnosti nastavitev
        if (PokrajinaPravila.jeTipkaMenija(pretvorjen.keyCode)) {
            val tag = currentFocus?.tag as? String
            val naPloscici = tag != null && (tag.startsWith("splet:") || tag.startsWith("razdelek:") || tag.startsWith("dok:") || tag.contains("."))
            if (naPloscici && super.dispatchKeyEvent(pretvorjen)) return true
            
            // Pregled Grok 2. 11. 2026: drzana tipka poslje vec ACTION_DOWN - nastavitve odpremo samo enkrat.
            if (pretvorjen.action == KeyEvent.ACTION_DOWN && pretvorjen.repeatCount == 0) {
                odpriNastavitveTv()
            }
            return true
        }

        // Možnost 1 (daljinec TV): navigacija med vrstico aplikacij, mini predvajalnikom in spodnjo vrstico
        if (pretvorjen.action == KeyEvent.ACTION_DOWN) {
            val mini = dock?.aktivniPogledZaFokus?.takeIf { it.isShown && it.isFocusable }
            val dockImaFokus = dock?.imaFokus == true || (mini != null && mini.hasFocus())
            if (pretvorjen.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (::vrstaAplikacij.isInitialized && vrstaAplikacij.hasFocus()) {
                    if (mini != null) {
                        mini.requestFocus()
                        return true
                    } else if (::dok.isInitialized) {
                        val cilj = najblizjiPogledX(dok, vrstaAplikacij.findFocus())
                        if (cilj != null && cilj.requestFocus()) return true
                    }
                } else if (dockImaFokus) {
                    if (::dok.isInitialized) {
                        val cilj = najblizjiPogledX(dok, mini ?: currentFocus)
                        if (cilj != null && cilj.requestFocus()) return true
                    }
                }
            } else if (pretvorjen.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (::dok.isInitialized && dok.hasFocus()) {
                    if (dock != null && (dock?.jeOdprt == true || mini != null)) {
                        if (dock?.fokusiraj() == true || (mini != null && mini.requestFocus())) return true
                    }
                    if (::vrstaAplikacij.isInitialized) {
                        val cilj = najblizjiPogledX(vrstaAplikacij, dok.findFocus())
                            ?: (zadnjaPloscica?.let { vrstaAplikacij.findViewWithTag<View>(it) })
                            ?: vrstaAplikacij.getChildAt(0)
                        if (cilj != null && cilj.requestFocus()) return true
                    }
                } else if (dockImaFokus) {
                    if (::vrstaAplikacij.isInitialized) {
                        val cilj = najblizjiPogledX(vrstaAplikacij, mini ?: currentFocus)
                            ?: (zadnjaPloscica?.let { vrstaAplikacij.findViewWithTag<View>(it) })
                            ?: vrstaAplikacij.getChildAt(0)
                        if (cilj != null && cilj.requestFocus()) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(pretvorjen)
    }

    /** Poišče otroka v vsebniku, ki je vodoravno (X) najbližje sredini referenčnega pogleda. */
    private fun najblizjiPogledX(vsebnik: ViewGroup, vir: View?): View? {
        if (vsebnik.childCount == 0) return null
        val dejanskiVir = if (vir != null && vir !== vsebnik) (vir.findFocus() ?: vir) else null
        if (dejanskiVir == null || !dejanskiVir.isShown) {
            return (0 until vsebnik.childCount).map { vsebnik.getChildAt(it) }.firstOrNull { it.isFocusable && it.isShown }
        }
        val virLokacija = IntArray(2).also { dejanskiVir.getLocationOnScreen(it) }
        val virSredinaX = virLokacija[0] + dejanskiVir.width / 2

        var najboljsi: View? = null
        var najmanjsaRazdalja = Int.MAX_VALUE
        val lokacija = IntArray(2)

        for (i in 0 until vsebnik.childCount) {
            val otrok = vsebnik.getChildAt(i)
            if (!otrok.isShown || !otrok.isFocusable) continue
            otrok.getLocationOnScreen(lokacija)
            val otrokSredinaX = lokacija[0] + otrok.width / 2
            val razdalja = kotlin.math.abs(virSredinaX - otrokSredinaX)
            if (razdalja < najmanjsaRazdalja) {
                najmanjsaRazdalja = razdalja
                najboljsi = otrok
            }
        }
        return najboljsi ?: (0 until vsebnik.childCount).map { vsebnik.getChildAt(it) }.firstOrNull { it.isFocusable && it.isShown }
    }

    // ------------------------------------------------------------------ videz (barve iz teme)

    /** Steklena plosca nad ozadjem; s fokusom obroba v barvi poudarka (na TV mora biti fokus viden z dveh metrov). */
    private fun steklo(polmerDp: Int, fokusMogoc: Boolean = true): StateListDrawable {
        fun plosca(obroba: Int, debelina: Int, ozadje: Int) = GradientDrawable().apply {
            setColor(osBarva(ozadje)); cornerRadius = dp(polmerDp).toFloat(); setStroke(dp(debelina), osBarva(obroba))
        }
        fun ploscaFokus(polmerDp: Int): android.graphics.drawable.Drawable {
            val spodaj = GradientDrawable().apply {
                setColor(osBarva(R.color.os_kartica_steklo_fokus))
                cornerRadius = dp(polmerDp).toFloat()
                setStroke(dp(5), osBarva(R.color.os_mint))
            }
            val zgoraj = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(polmerDp).toFloat()
                setStroke(dp(2), osBarva(R.color.os_ozadje))
            }
            return android.graphics.drawable.LayerDrawable(arrayOf(spodaj, zgoraj))
        }
        return StateListDrawable().apply {
            if (fokusMogoc) addState(intArrayOf(android.R.attr.state_focused), ploscaFokus(polmerDp))
            addState(intArrayOf(), plosca(R.color.os_kartica_obroba, 1, R.color.os_kartica_steklo))
        }
    }

    /** Povecava ob fokusu (brez nje pri zmanjsanem gibanju); obroba je v [steklo]. */
    private fun poudariFokus(v: View) {
        val prej = v.onFocusChangeListener
        v.setOnFocusChangeListener { p, ima ->
            prej?.onFocusChange(p, ima)
            val cilj = if (ima) PokrajinaPravila.povecava(zmanjsano()) else 1f
            val cas = PokrajinaPravila.trajanje(150, Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f), zmanjsano())
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
        // Lastnik 27. 10. 2026: brez oznake »Safeer OS« levo zgoraj - glava ima samo vreme in uro.
        g.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Vreme: brez izbranega kraja ga ni (ne nadomestka). Lastnik 27. 10. 2026: kraj se izbere v Nastavitvah, ne s klikom
        // na uro (VremeKraj) - ura je samo prikaz.
        teden = object : HorizontalScrollView(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    skrijTeden()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
        }
        tedenVrsta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        teden.addView(tedenVrsta, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        g.addView(teden, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        // Lastnik 30. 10. 2026: klik na temperaturo pokaze tedensko napoved v zgornjem pasu (ne cez vsebino).
        vreme = besedilo("", 26f).apply {
            visibility = View.INVISIBLE
            id = View.generateViewId()
            isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_meni_postavka)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { preklopiTeden() }
        }
        g.addView(vreme, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(22) })
        val cas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            // Zgled lastnika 27. 10. 2026: ura v stekleni tabletki desno zgoraj (fokus doda obrobo iz [steklo]).
            // Lastnik 27. 10. 2026: ura brez okvirja in ni gumb (ni fokusa ne klika).
            setPadding(dp(18), dp(6), dp(18), dp(6))
            isFocusable = false; isClickable = false
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
                val ok = koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER
                // Pregled Grok 2. 11. 2026: na TV je OK skoraj vedno DPAD_CENTER - z vpisanim besedilom isce v izbranem obsegu
                // (prej je OK odprl samo tipkovnico, iskanje pa je sprozil le gumb tipkovnice).
                val niz = (v as EditText).text?.toString()?.trim().orEmpty()
                if (d.action == KeyEvent.ACTION_DOWN && d.repeatCount == 0 && ok && LupaPravila.okIsce(niz, DomacaLupa.obseg(this@PokrajinaActivity))) {
                    DomacaLupa.izvedi(this@PokrajinaActivity, niz)
                    true
                } else if (d.action == KeyEvent.ACTION_DOWN && ok && !v.showSoftInputOnFocus) {
                    if (DomacaLupa.jeFizicnaTipkovnica(this@PokrajinaActivity)) {
                        v.showSoftInputOnFocus = true
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, 0)
                    } else {
                        DomacaLupa.odpriTipkovnico(this@PokrajinaActivity, v)
                    }
                    true
                } else false
            }
        }
        okvir.addView(polje, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        poljeLupe = polje
        polje.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                glavna.removeCallbacks(iskanjeZamik)
                glavna.postDelayed(iskanjeZamik, 250)
            }
        })
        polje.setOnFocusChangeListener { _, ima ->
            if (ima && polje.text.isNullOrBlank()) narisiZadetke("", vsili = true)
            // Zgodovina (prazno polje) ne ostane cez ploscice, ko gre fokus z lupe drugam.
            else if (!ima && polje.text.isNullOrBlank()) polje.post {
                if (!polje.hasFocus() && !zadetkiDrsnik.hasFocus()) { zadetkiDrsnik.visibility = View.GONE; iskanjeSt++ }
            }
        }
        // Ista lupa kot na starem domacem zaslonu: »… na TV«, spletni naslov, iskanje v Medijih; brez mikrofona (lastnik 29. 10. 2026).
        DomacaLupa.prevzemi(this, polje, okvir, zMikrofonom = false) { }
        return okvir
    }

    // ------------------------------------------------------------------ zadetki lupe (naloga 42 E)

    private val iskanjeZamik = Runnable { if (!isFinishing && !isDestroyed) narisiZadetke(poljeLupe.text?.toString().orEmpty()) }
    /** Stevec iskanja: odgovor iskalnika za staro poizvedbo ne prepise novejsih zadetkov. */
    private var iskanjeSt = 0

    /** Sprotni predlogi iz spleta (iskalnik uporabnika, isti kot v brskalniku); izklop v Nastavitvah novega zaganjalnika. */
    private fun spletniPredlogi(): Boolean = shramba().getBoolean(KLJUC_SPLET_PREDLOGI, false)

    private fun vrsticaZadetka(ikona: Int, napis: String, klik: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true
        background = steklo(12).apply { alpha = 0 }
        setOnFocusChangeListener { v, ima -> v.background.alpha = if (ima) 255 else 0 }
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnClickListener { klik(); zapriLupo() }
        addView(ImageView(this@PokrajinaActivity).apply { setImageResource(ikona); setColorFilter(osBarva(R.color.os_mint)) },
            LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(besedilo(napis, 16f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    /**
     * »Isci v: Splet | Video vsebine | YouTube« nad zadetki (lastnik 2. 11. 2026): uporabnik izbere, kje lupa isce ob Enter;
     * izbira se zapomni na tej napravi. Izbran gumb je poudarjen; OK na gumbu izbere, besedilo ostane.
     */
    private fun izbiraObsega(): View {
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), 0, dp(6)) }
        vrsta.addView(besedilo(getString(R.string.pk_lupa_obseg), 14f, R.color.os_umirjeno))
        val izbran = DomacaLupa.obseg(this)
        for (o in LupaPravila.OBSEGI) {
            val napis = getString(when (o) {
                LupaPravila.OBSEG_VIDEO -> R.string.pk_lupa_obseg_video
                LupaPravila.OBSEG_YOUTUBE -> R.string.pk_lupa_obseg_youtube
                else -> R.string.pk_lupa_obseg_splet
            })
            vrsta.addView(besedilo(napis, 15f, if (o == izbran) R.color.os_mint else R.color.os_besedilo, krepko = o == izbran).apply {
                isFocusable = true; isClickable = true
                background = steklo(14)
                setPadding(dp(14), dp(5), dp(14), dp(5))
                contentDescription = getString(R.string.pk_lupa_obseg) + " " + napis
                setOnClickListener {
                    DomacaLupa.nastaviObseg(this@PokrajinaActivity, o)
                    // Z daljincem je pot nazaj na polje dolga: OK na obsegu ga izbere in takoj isce v njem (ce je kaj vpisano).
                    val niz = poljeLupe.text?.toString()?.trim().orEmpty()
                    if (LupaPravila.okIsce(niz, o)) { DomacaLupa.izvedi(this@PokrajinaActivity, niz); return@setOnClickListener }
                    // Ista poizvedba: brez vsili bi narisiZadetke takoj koncal in izbira obsega ne bi bila vidna.
                    narisiZadetke(poljeLupe.text?.toString().orEmpty(), vsili = true)
                    // Fokus ostane na izbranem gumbu (daljinec) - po ponovnem izrisu ga poiscemo po oznaki.
                    zadetki.post { zadetki.findViewWithTag<View>("obseg_$o")?.requestFocus() }
                }
                tag = "obseg_$o"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        return vrsta
    }

    private fun naslovSkupine(napis: Int) = besedilo(getString(napis), 13f, R.color.os_umirjeno).apply { setPadding(dp(4), dp(6), 0, dp(2)) }

    /**
     * Zadetki med tipkanjem: programi televizorja, nedavne datoteke, nastavitve, iskanje v Medijih, »Isci v spletu« in
     * sprotni predlogi iskalnika. Enter ali OK na polju izvede ukaz (DomacaLupa.izvedi: »… na TV«, vreme, naslov).
     */
    private var iskanjePoizvedba = ""

    /** Je [v] ([v] sam ali njegov prednik) v [vsebnik]? */
    private fun jePotomec(v: View?, vsebnik: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === vsebnik) return true; p = p.parent }
        return false
    }

    private fun narisiZadetke(poizvedba: String, vsili: Boolean = false) {
        val q = poizvedba.trim()
        // Naloga 49 C: ista poizvedba se ne rise znova (fokus v seznamu ostane).
        if (!vsili && q == iskanjePoizvedba) return
        iskanjePoizvedba = q
        val st = ++iskanjeSt

        val stariFokus = currentFocus
        // Pregled 2. 11. 2026: gumbi obsega so vnuki seznama (vrsta »Isci v«) - prej se fokus z njih ni vrnil.
        val imelFokus = jePotomec(stariFokus, zadetki)
        val fokusTag = stariFokus?.tag ?: (stariFokus as? TextView)?.text?.toString()

        zadetki.removeAllViews()
        val drsnik = zadetkiDrsnik
        if (q.isEmpty()) { 
            val zgodovina = DomacaLupa.beriZgodovino(this)
            if (zgodovina.isEmpty()) {
                drsnik.visibility = View.GONE
            } else {
                zadetki.addView(naslovSkupine(R.string.menu_history))
                for (z in zgodovina) {
                    zadetki.addView(vrsticaZadetka(R.drawable.os_ikona_isci, z) { DomacaLupa.izvedi(this, z) }.apply { tag = z })
                }
                drsnik.visibility = View.VISIBLE
            }
        } else {
            zadetki.addView(izbiraObsega())
            val vsi = Aplikacije.zadnjiZnani(this) ?: emptyList()
            val programi = LupaPravila.najboljsi(vsi, q, 4) { it.ime }
            if (programi.isNotEmpty()) {
                zadetki.addView(naslovSkupine(R.string.pk_lupa_programi))
                for (a in programi) zadetki.addView(vrsticaZadetka(R.drawable.os_ikona_aplikacije, a.ime) {
                    Priljubljene.preveriZagonInZazeni(this, a.paket, a.ime) {
                        odpri(Intent(a.namera).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.apply { tag = a.ime })
            }
            val datoteke = LupaPravila.najboljsi(Nadaljuj.seznam(this).filter { it.vrsta in ZaganjalnikPravila.VRSTE_DATOTEK }, q, 3) { it.ime }
            if (datoteke.isNotEmpty()) {
                zadetki.addView(naslovSkupine(R.string.os_meni_datoteke))
                val link = LinkUpravitelj.pridobi(this)
                for (n in datoteke) zadetki.addView(vrsticaZadetka(R.drawable.os_ikona_datoteka, n.ime) {
                    NadaljujOdpri.datoteka(this, link, n) { _ -> android.widget.Toast.makeText(this, getString(R.string.os_nadaljuj_ni_vec, n.ime),
                        android.widget.Toast.LENGTH_LONG).show() }
                }.apply { tag = n.ime })
            }
            val nastavitve = listOf(
                getString(R.string.os_meni_nastavitve) to Intent(this, NastavitveActivity::class.java),
                getString(R.string.os_videz) to Intent(this, VidezActivity::class.java),
                getString(R.string.dov_naslov) to Intent(this, DovoljenjaActivity::class.java),
                getString(R.string.os_meni_naprave) to Intent(this, NapraveActivity::class.java),
            )
            val zadetkiNastavitev = LupaPravila.najboljsi(nastavitve, q, 3) { it.first }
            if (zadetkiNastavitev.isNotEmpty()) {
                zadetki.addView(naslovSkupine(R.string.os_meni_nastavitve))
                for ((ime, namera) in zadetkiNastavitev) zadetki.addView(vrsticaZadetka(R.drawable.os_ikona_nastavitve, ime) { odpri(namera) }.apply { tag = ime })
            }
            // Lastnik 2. 11. 2026: obseg (Splet, Video, YouTube) je ena vrstica pod lupo (izbiraObsega na vrhu zadetkov), ne
            // trije dodatni zadetki; Enter in OK zazeneta samo izbranega.
            drsnik.visibility = View.VISIBLE
            val predloge = si.safeer.tv.SmartOmnibox.iskalnik(this).predlogi
            if (predloge != null && LupaPravila.predlogeIskati(q, spletniPredlogi())) {
                Thread({
                    val odgovor = beri(predloge + java.net.URLEncoder.encode(q, "UTF-8"))
                    val seznam = odgovor?.let { LupaPravila.predlogi(it, q) }.orEmpty()
                    glavna.post {
                        if (isFinishing || isDestroyed || st != iskanjeSt || seznam.isEmpty()) return@post
                        zadetki.addView(naslovSkupine(R.string.pk_lupa_predlogi))
                        for (p in seznam) zadetki.addView(vrsticaZadetka(R.drawable.os_ikona_isci, p) { DomacaLupa.izvedi(this, p) }.apply { tag = p })
                        if (imelFokus) zadetki.post { zadetki.findViewWithTag<View>(fokusTag)?.requestFocus() }
                    }
                }, "safeer-lupa-predlogi").start()
            }
        }
        
        if (imelFokus) {
            zadetki.post { zadetki.findViewWithTag<View>(fokusTag)?.requestFocus() }
        } else if (stariFokus != null && stariFokus != poljeLupe) {
            stariFokus.requestFocus()
        }
    }

    // ------------------------------------------------------------------ priljubljene in spletne aplikacije

    /**
     * Plosca aplikacije v mrezi: zaobljena, velika ikona na sredini, kratko ime spodaj. Lastnik 27. 10. 2026: brez obrobe -
     * obroba (in povecava) samo na plosci s fokusom, da je na TV z daljincem vidno, kje si.
     */
    private fun kartica(ikona: android.graphics.drawable.Drawable?, ikonaRes: Int, ime: String, klik: (View) -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacij, false)
        val slika = v.findViewById<ImageView>(R.id.ikona)
        if (ikona != null) slika.setImageDrawable(ikona) else slika.setImageResource(ikonaRes)
        slika.layoutParams = slika.layoutParams.apply { width = dp(64); height = dp(64) }
        v.findViewById<TextView>(R.id.ime).apply { text = ime; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        v.background = StateListDrawable().apply {
            val spodaj = GradientDrawable().apply {
                setColor(osBarva(R.color.os_kartica_steklo_fokus))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(5), osBarva(R.color.os_mint))
            }
            val zgoraj = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), osBarva(R.color.os_ozadje))
            }
            addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.LayerDrawable(arrayOf(spodaj, zgoraj)))
            addState(intArrayOf(), GradientDrawable().apply { setColor(osBarva(R.color.os_kartica_steklo)); cornerRadius = dp(22).toFloat() })
        }
        v.contentDescription = ime
        v.setOnClickListener { klik(v) }
        poudariFokus(v)
        return v
    }

    /** Sirina plosce: sest v vrsti cez vsebino zaslona; vec plosc je za drsenjem desno. */
    /** Visina plosc po prostoru na zaslonu ([PokrajinaPravila.visinaPlosce]); na TV 1080p ostane 118 dp. */
    private var visinaPlosceDp = PokrajinaPravila.VISINA_PLOSCE_DP

    /** Po postavitvi: prostor od vrha vrste do spodnje vrstice; ce plosce ne gredo, jih znizamo (in nazaj, ko gredo). */
    private fun prilagodiVisinoPlosc() {
        val drsnik = vrstaAplikacij.parent as? View ?: return
        if (dok.height == 0 || drsnik.height == 0) return
        val gostota = resources.displayMetrics.density
        // Prostor za plosco: od vrha drsnika do vrha spodnje vrstice brez njenega odmika (14) in notranjega roba vrste (2 x 10).
        val prostorPx = dok.top - dp(14) - drsnik.top - vrstaAplikacij.paddingTop - vrstaAplikacij.paddingBottom
        // Spodnja vrstica je pod robom zaslona (vsebina ne gre): manjka toliko, kolikor je je zunaj.
        val zunajPx = (dok.bottom - (koren.height - dp(18))).coerceAtLeast(0)
        val nova = PokrajinaPravila.visinaPlosce(((prostorPx - zunajPx) / gostota).toInt())
        if (nova == visinaPlosceDp) return
        visinaPlosceDp = nova
        for (i in 0 until vrstaAplikacij.childCount) {
            val v = vrstaAplikacij.getChildAt(i)
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = dp(nova); v.layoutParams = it }
        }
    }

    private fun sirinaPlosce(): Int = ((resources.displayMetrics.widthPixels - dp(88)) / PokrajinaPravila.STOLPCEV - dp(20)).coerceAtLeast(dp(110))

    private fun dodajVVrsto(v: View, sirina: Int = sirinaPlosce()) {
        vrstaAplikacij.addView(v, LinearLayout.LayoutParams(sirina, dp(visinaPlosceDp)).apply { marginStart = dp(10); marginEnd = dp(10) })
    }

    private fun narisiNadaljuj() {
        val vnosi = Nadaljuj.seznam(this)
        vrstaNadaljuj.removeAllViews()
        if (vnosi.isEmpty()) {
            okvirNadaljuj.visibility = View.GONE
            return
        }
        okvirNadaljuj.visibility = View.VISIBLE
        for (n in vnosi) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaNadaljuj, false)
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            val risba = ikonaPrograma(n)
            if (risba != null) ikona.setImageDrawable(risba) else ikona.setImageResource(ikonaZa(n.vrsta))
            v.findViewById<TextView>(R.id.ime).text = n.ime
            v.contentDescription = getString(R.string.pk_nadaljuj) + ": " + n.ime
            v.setOnClickListener {
                if (premikam != null) { koncajPremikanje(); return@setOnClickListener }
                zadnjaPloscica = "nadaljuj:" + n.kljuc()
                odpriNadaljuj(n)
            }
            v.tag = "nadaljuj:" + n.kljuc()
            poudariFokus(v)
            vrstaNadaljuj.addView(v, LinearLayout.LayoutParams(sirinaPlosce(), dp(visinaPlosceDp)).apply { marginStart = dp(10); marginEnd = dp(10) })
        }
    }
    
    private fun odpriNadaljuj(n: Nadaljuj.Vnos) {
        when (n.vrsta) {
            Nadaljuj.SPLETNA -> {
                val a = SpletneAplikacije.seznam(this).firstOrNull { it.url == n.url }
                if (a != null) {
                    preveriInOdpri(a.url, Brskalnik.namera(this).putExtra("spletna_aplikacija", a.url).putExtra("aplikacija_ime", a.ime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    odpri(Brskalnik.namera(this).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(n.url)))
                }
            }
            Nadaljuj.ZASLON -> odpri(Intent(this, ZaslonActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, n.racunalnik))
            // Pregled 2. 11. 2026: PROGRAM je program na drugi napravi v Linku (app:...), ne paket tega televizorja -
            // ComponentName(program, program) se ni nikoli odprl. Ista pot kot stari domaci zaslon (ozji krog).
            Nadaljuj.PROGRAM -> {
                val r = link.naprave.firstOrNull { it.id == n.racunalnik }
                if (r != null && OzjiKrog.zavrni(this, r.id, r.ime)) return
                ZagonPrograma.zazeni(this, link, n.racunalnik, n.program, n.ime, n.igra)
            }
            else -> NadaljujOdpri.datoteka(this, link, n) { rac ->
                Toast.makeText(this, if (rac) getString(R.string.os_nadaljuj_ni_racunalnika) else getString(R.string.os_nadaljuj_ni_vec, n.ime),
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun ikonaPrograma(n: Nadaljuj.Vnos): android.graphics.drawable.Drawable? {
        if (n.vrsta == Nadaljuj.SPLETNA) {
            val a = SpletneAplikacije.seznam(this).firstOrNull { it.url == n.url }
            if (a != null) return SpletneAplikacije.ikona(this, a)
        }
        return Nadaljuj.ikona(this, n)
    }

    private fun ikonaZa(vrsta: String): Int = when (vrsta) {
        Nadaljuj.VIDEO -> R.drawable.os_ikona_video
        Nadaljuj.GLASBA -> R.drawable.os_ikona_glasba
        Nadaljuj.SLIKA -> R.drawable.os_ikona_slika
        Nadaljuj.SPLETNA -> R.drawable.os_ikona_splet
        Nadaljuj.ZASLON -> R.drawable.os_ikona_zaslon
        Nadaljuj.PROGRAM -> R.drawable.os_ikona_aplikacije
        else -> R.drawable.os_ikona_datoteka
    }

    private fun narisiAplikacije(): Boolean {
        val vsi = Aplikacije.zadnjiZnani(this) ?: Aplikacije.seznam(this)
        val poPaketu = vsi.associateBy { it.paket }
        val spletne = SpletneAplikacije.seznam(this).associateBy { "splet:" + it.url }
        val zacetnaVrsta = PokrajinaPravila.vrstaDomov(Priljubljene.redDomov(this), Priljubljene.seznam(this) + spletne.keys,
            poPaketu.keys + spletne.keys)
        var vrsta = PokrajinaPravila.deduplicirajSpletne(zacetnaVrsta)
        
        var nicPripetega = false
        if (vrsta.isEmpty()) {
            nicPripetega = true
            vrsta = vsi.take(6).map { it.paket }
        }
        
        // Naloga 49 D: brez sprememb se vrstica ne rise znova (fokus ostane). Podpis zajame tudi USB, imena in
        // zatemnitev spletnih - prej priklop USB ali novo ime spletne aplikacije nista bila vidna do spremembe vrste.
        val usbs = usbNosilci()
        val podpis = vrsta + usbs.map { "usb:" + (it.uuid ?: it.getDescription(this)) } +
            spletne.values.map { "s:" + it.url + "|" + it.ime + "|" + it.zadnjaNapaka }
        if (podpis == podpisVrste && vrstaAplikacij.childCount > 0) return false
        podpisVrste = podpis

        val stare = vrstaDomov.toSet()
        vrstaAplikacij.removeAllViews()
        vrstaDomov = vrsta
        
        val nove = vrsta.filter { it !in stare }
        if (stare.isNotEmpty() && nove.isNotEmpty()) {
            zadnjaPloscica = nove.first()
        }
        
        if (nicPripetega) {
            val opozorilo = besedilo(getString(R.string.pk_dodaj_gledas), 16f, R.color.os_umirjeno).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            vrstaAplikacij.addView(opozorilo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        for (id in vrsta) {
            val a = poPaketu[id]
            if (a != null) {
                val v = kartica(Aplikacije.ikonaIzPomnilnika(a.paket), R.drawable.os_ikona_mreza, a.ime) { k ->
                    if (premikam != null) { koncajPremikanje(); return@kartica }
                    zadnjaPloscica = id
                    preveriInOdpri(id, Intent(a.namera).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), izPloscice(k))
                }
                if (Aplikacije.ikonaIzPomnilnika(a.paket) == null)
                    Aplikacije.ikonaLeno(this, a) { Aplikacije.ikonaIzPomnilnika(a.paket)?.let { v.findViewById<ImageView>(R.id.ikona).setImageDrawable(it) } }
                v.tag = id
                v.setOnLongClickListener { zacniPremikanje(id); true }
                v.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (keyCode == 165 || PokrajinaPravila.jeTipkaMenija(keyCode))) {
                        moznostiPloscice(id, a.ime, splet = false); true
                    } else false
                }
                dodajVVrsto(v)
                continue
            }
            val r = id.removePrefix("razdelek:")
            if (r != id) {
                val (ikona, napis) = when (r) {
                    PokrajinaPravila.MEDIJI -> R.drawable.os_ikona_predvajaj to R.string.pk_mediji
                    PokrajinaPravila.SPLET -> R.drawable.os_ikona_splet to R.string.os_meni_splet
                    PokrajinaPravila.LINK -> R.drawable.os_ikona_link to R.string.os_meni_naprave
                    PokrajinaPravila.SPOROCILA -> R.drawable.os_ikona_sporocila to R.string.os_meni_sporocila
                    PokrajinaPravila.APLIKACIJE -> R.drawable.os_ikona_aplikacije to R.string.pk_aplikacije
                    PokrajinaPravila.DATOTEKE -> R.drawable.os_ikona_datoteke to R.string.os_meni_datoteke
                    else -> R.drawable.os_ikona_nastavitve to R.string.os_meni_nastavitve
                }
                val ime = getString(napis)
                val v = kartica(null, ikona, ime) { _ ->
                    if (premikam != null) { koncajPremikanje(); return@kartica }
                    zadnjaPloscica = id
                    odpriRazdelek(r)
                }
                v.tag = id
                v.setOnLongClickListener { zacniPremikanje(id); true }
                v.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (keyCode == 165 || PokrajinaPravila.jeTipkaMenija(keyCode))) {
                        moznostiPloscice(id, ime, splet = false); true
                    } else false
                }
                dodajVVrsto(v)
                continue
            }
            val s = spletne[id] ?: continue
            val ime = s.ime.ifBlank { SpletneAplikacije.gostitelj(s.url) }
            val v = kartica(SpletneAplikacije.ikona(this, s), R.drawable.os_ikona_splet, ime) { _ ->
                if (premikam != null) { koncajPremikanje(); return@kartica }
                zadnjaPloscica = id
                Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.SPLETNA, ime = ime, url = s.url))
                preveriInOdpri(id, Brskalnik.namera(this).putExtra("spletna_aplikacija", s.url).putExtra("aplikacija_ime", ime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (s.zadnjaNapaka) {
                v.alpha = 0.5f
            }
            v.tag = id
            v.setOnLongClickListener { zacniPremikanje(id); true }
            v.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == 165 || PokrajinaPravila.jeTipkaMenija(keyCode))) {
                    moznostiPloscice(id, ime, splet = true); true
                } else false
            }
            dodajVVrsto(v)
        }
        
        // Naloga 48 E: zacasna ploscica USB - ni v vrstaDomov, zato se ne premika in ne shrani v vrstni red.
        try {
            for (usb in usbs) {
                val ime = usb.getDescription(this) ?: "USB"
                val v = kartica(null, R.drawable.os_ikona_datoteke, ime) { _ ->
                    if (premikam != null) { koncajPremikanje(); return@kartica }
                    // createOpenDocumentTreeIntent je od Androida 10 (API 29); na Androidu 9 bi bil NoSuchMethodError
                    // (Error, catch Exception ga ne ujame) - tam in ob napaki odpremo nas brskalnik datotek (Lint NewApi,
                    // CI 41458435776, 2. 11. 2026).
                    val namera = if (android.os.Build.VERSION.SDK_INT >= 29) usb.createOpenDocumentTreeIntent() else null
                    try {
                        if (namera != null) startActivity(namera) else odpriKrajevneDatoteke()
                    } catch (e: Exception) {
                        odpriKrajevneDatoteke()
                    }
                }
                v.tag = "usb:" + (usb.uuid ?: ime)
                dodajVVrsto(v)
            }
        } catch (_: Throwable) { }

        // Zadnja plosca: uporabnik si sam doda aplikacijo s televizorja ali spletno aplikacijo.
        val gumbDodaj = kartica(null, R.drawable.os_ikona_plus, getString(R.string.os_spletne_dodaj)) { dodajAplikacijo() }
        gumbDodaj.findViewById<TextView>(R.id.ime).visibility = View.GONE
        dodajVVrsto(gumbDodaj, dp(visinaPlosceDp))
        return true
    }

    private fun usbNosilci(): List<android.os.storage.StorageVolume> = try {
        getSystemService(android.os.storage.StorageManager::class.java)?.storageVolumes
            ?.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }.orEmpty()
    } catch (_: Throwable) { emptyList() }

    /** Vrstni red, kot je trenutno narisan (za premik in shranjevanje). */
    private var vrstaDomov: List<String> = emptyList()
    private var podpisVrste: List<String> = emptyList()
    // ------------------------------------------------------------------ mirovanje (po zgledu tv-simple-home)
    private var zadnjiVnos = 0L
    private var mirujem = false
    private val pogoltnjene = HashSet<Int>()
    private var zaslonZatemnjen = false
    private val preveriMirovanje = object : Runnable {
        override fun run() {
            val zdaj = android.os.SystemClock.uptimeMillis()
            // Pregled 2. 11. 2026: vidnost je na drsniku - zadetki so bili vedno VISIBLE in zaslon ni nikoli miroval.
            val lupa = ::poljeLupe.isInitialized && (poljeLupe.hasFocus() || zadetkiDrsnik.visibility == View.VISIBLE ||
                DomacaLupa.tipkovnicaOdprta(this@PokrajinaActivity))
            // Mini predvajalnik (tudi predvajanje na drugi napravi): ne ciscenja ozadja ne zatemnitve.
            val predvaja = try {
                GlasbaStoritev.trenutna() != null || GlasbaStoritev.tece() ||
                    (OddaljenoPredvajanje.cilj != null && OddaljenoPredvajanje.stanje.kajIgra)
            } catch (_: Throwable) { false }
            
            if (!mirujem && hasWindowFocus() && !lupa && premikam == null && premikamDok == null && PokrajinaPravila.mirujePo(zadnjiVnos, zdaj)) {
                mirujem = true
                val cas = PokrajinaPravila.trajanje(600, android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f), zmanjsano())
                for (i in 0 until koren.childCount) koren.getChildAt(i).let { 
                    if (it !== zatemnitevOzadja) {
                        if (cas == 0L) it.alpha = 0f else it.animate().alpha(0f).setDuration(cas).start() 
                    }
                }
                dock?.let { glavna.post { it.skrijCeOdprt() } }
                if (!predvaja) Pomnilnik.pocistiOzadje(this@PokrajinaActivity, false, 0L)
            }
            if (mirujem && !zaslonZatemnjen && hasWindowFocus() && !predvaja && PokrajinaPravila.mirujePo(zadnjiVnos, zdaj, PokrajinaPravila.MIROVANJE_MS * 2)) {
                zaslonZatemnjen = true
                val cas = PokrajinaPravila.trajanje(1000, android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f), zmanjsano())
                if (cas == 0L) zatemnitevOzadja.alpha = 0.85f else zatemnitevOzadja.animate().alpha(0.85f).setDuration(cas).start()
            }
            if (!zaslonZatemnjen) {
                val meja = if (!mirujem) PokrajinaPravila.MIROVANJE_MS else PokrajinaPravila.MIROVANJE_MS * 2
                glavna.postDelayed(this, maxOf(1_000L, zadnjiVnos + meja - zdaj))
            }
        }
    }

    private fun zbudi(animiraj: Boolean = true) {
        zadnjiVnos = android.os.SystemClock.uptimeMillis()
        if (mirujem) {
            mirujem = false
            zaslonZatemnjen = false
            for (i in 0 until koren.childCount) koren.getChildAt(i).let { 
                if (it === zatemnitevOzadja) {
                    it.animate().cancel()
                    it.alpha = 0f 
                } else {
                    if (animiraj) it.animate().alpha(1f).setDuration(300).start() else it.alpha = 1f 
                }
            }
        }
        glavna.removeCallbacks(preveriMirovanje)
        glavna.postDelayed(preveriMirovanje, PokrajinaPravila.MIROVANJE_MS)
    }

    /** Dotik ali klik miske, ki je zbudil zaslon (njegov ACTION_UP ne sme odpreti ploscice). */
    private var dotikZbudil = false
    private var dotikZacetek = 0L

    override fun dispatchTouchEvent(dogodek: android.view.MotionEvent): Boolean {
        when (dogodek.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dotikZbudil = mirujem
                dotikZacetek = dogodek.eventTime
                zbudi()
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                // Pregled 2. 11. 2026: prej je ACTION_UP po dotiku, ki je zbudil, vseeno poslal OK (odprl ploscico).
                if (dotikZbudil) { dotikZbudil = false; return true }
                // Naloga 49 I: dolg pritisk (miska, dotik) odpre meni ozadja; kratek je OK na fokusu (bcec519e).
                if (dogodek.eventTime - dotikZacetek >= android.view.ViewConfiguration.getLongPressTimeout()) menijOzadja()
                else {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
                return true
            }
        }
        return super.dispatchTouchEvent(dogodek)
    }

    private var zadnjiXMis = 0f

    override fun dispatchGenericMotionEvent(dogodek: android.view.MotionEvent): Boolean {
        // Premik miske ali kolescek: v mirovanju prvi dogodek samo zbudi (kot prva tipka), ne premakne fokusa.
        if (mirujem) { zbudi(); zadnjiXMis = dogodek.x; return true }
        zbudi()
        if (dogodek.action == android.view.MotionEvent.ACTION_SCROLL) {
            val os = dogodek.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL).takeIf { it != 0f }
                ?: dogodek.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
            if (os < 0f) {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            } else if (os > 0f) {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
            }
            return true
        }
        if (dogodek.action == android.view.MotionEvent.ACTION_HOVER_MOVE) {
            val dx = dogodek.x - zadnjiXMis
            if (Math.abs(dx) > 40f) {
                val koda = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, koda))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, koda))
                zadnjiXMis = dogodek.x
            }
            return true
        }
        return super.dispatchGenericMotionEvent(dogodek)
    }

    /** Plosca aplikacije, ki jo je uporabnik nazadnje odprl (fokus ob vrnitvi). */
    private var zadnjaPloscica: String? = null
    /** Plosca, ki jo uporabnik premika z levo/desno (OK, Nazaj ali Domov konca); null = ne premika. */
    private var premikam: String? = null

    /**
     * Lastnik 28. 10. 2026: dolg pritisk (OK na daljincu, desni klik ali dolg dotik) na plosci - premakni (levo/desno z
     * daljincem), premik za eno mesto, odstrani iz priljubljenih. Spletna aplikacija je samo v tej vrstici: odstranitev
     * jo odstrani.
     */
    private fun moznostiPloscice(id: String, ime: String, splet: Boolean) {
        val dejanja = mutableListOf(
            getString(R.string.pk_premakni) to { zacniPremikanje(id) },
            getString(R.string.os_spletne_levo) to { premakniPloscico(id, -1) },
            getString(R.string.os_spletne_desno) to { premakniPloscico(id, 1) },
            getString(R.string.os_priljubljen_odstrani) to { odstraniPloscico(id, splet) }
        )
        if (splet) {
            val url = id.removePrefix("splet:")
            val s = SpletneAplikacije.seznam(this).find { it.url == url }
            if (s != null) {
                dejanja.add(getString(R.string.pk_uredi_ime) to { SpletnaDodaj.urediIme(this, s) { narisiAplikacije() } })
            }
        } else {
            val vsi = Aplikacije.zadnjiZnani(this) ?: Aplikacije.seznam(this)
            val a = vsi.find { it.paket == id }
            if (a != null) {
                dejanja.add(getString(R.string.zg_podatki_aplikacije) to {
                    odpri(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$id")))
                })
                val jeSistemska = (a.info?.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                val posodobitevSistemske = (a.info?.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                if (!jeSistemska && !posodobitevSistemske && id != packageName) {
                    dejanja.add(getString(R.string.pk_odstrani_z_naprave) to {
                        odpri(Intent(Intent.ACTION_DELETE).setData(android.net.Uri.parse("package:$id")))
                    })
                }
            }
        }
        dejanja.add(getString(R.string.os_meni_nastavitve) to { odpri(Intent(this, NastavitveActivity::class.java)) })
        dejanja.add(getString(R.string.nast_sk_tv) to { odpriNastavitveTv() })

        val okno = android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setTitle(ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun premakniPloscico(id: String, korak: Int): Boolean {
        val pogled = vrstaAplikacij.findViewWithTag<View>(id) ?: return false
        val i = vrstaAplikacij.indexOfChild(pogled)
        if (i < 0) return false
        val nova = PokrajinaPravila.premakni(vrstaDomov, id, korak) ?: return false
        val j = nova.indexOf(id)
        if (j < 0 || j == i) return false

        vrstaDomov = nova
        Priljubljene.shraniRedDomov(this, nova)

        vrstaAplikacij.removeViewAt(i)
        vrstaAplikacij.addView(pogled, j)
        pogled.requestFocus()
        return true
    }

    private fun zacniPremikanje(id: String) {
        premikam = id
        osveziStanjePremikanja(vrstaAplikacij, id, dp(10))
        vrstaAplikacij.findViewWithTag<View>(id)?.requestFocus()
    }

    private fun koncajPremikanje() {
        val id = premikam ?: return
        premikam = null
        osveziStanjePremikanja(vrstaAplikacij, null, dp(10))
        vrstaAplikacij.findViewWithTag<View>(id)?.requestFocus()
    }

    private fun odstraniPloscico(id: String, splet: Boolean) {
        if (splet) SpletneAplikacije.odstrani(this, id.removePrefix("splet:")) else Priljubljene.odstrani(this, id)
        Priljubljene.shraniRedDomov(this, vrstaDomov.filterNot { it == id })
        narisiAplikacije()
        vrstaAplikacij.getChildAt(0)?.requestFocus()
    }

    /** Nas brskalnik krajevnih datotek (tudi USB) - deluje na vseh podprtih Androidih. */
    private fun odpriKrajevneDatoteke() {
        odpri(Intent(this, DatotekeActivity::class.java).putExtra(DatotekeActivity.EXTRA_KRAJEVNO, true))
    }

    /** »+ Dodaj«: aplikacija s televizorja (seznam vseh, dolg pritisk doda) ali spletna aplikacija (kot na starem zaslonu). */
    private fun dodajAplikacijo() {
        val okno = android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setTitle(R.string.pk_dodaj_naslov)
            .setItems(arrayOf(getString(R.string.pk_dodaj_tv), getString(R.string.pk_dodaj_splet), getString(R.string.pk_dodaj_razdelek))) { _, i ->
                when (i) {
                    0 -> odpri(Intent(this, AplikacijeTvActivity::class.java).putExtra(AplikacijeTvActivity.IZBIRA_ZA_DOMOV, true))
                    1 -> SpletnaDodaj.izberi(this) { narisiAplikacije() }
                    2 -> dodajRazdelek()
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun dodajRazdelek() {
        val r = PokrajinaPravila.DOK
        val imena = r.map { getString(when (it) {
                PokrajinaPravila.MEDIJI -> R.string.pk_mediji
                PokrajinaPravila.SPLET -> R.string.os_meni_splet
                PokrajinaPravila.LINK -> R.string.os_meni_naprave
                PokrajinaPravila.SPOROCILA -> R.string.os_meni_sporocila
                PokrajinaPravila.APLIKACIJE -> R.string.pk_aplikacije
                PokrajinaPravila.DATOTEKE -> R.string.os_meni_datoteke
                else -> R.string.os_meni_nastavitve
            }) }
        val okno = android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setTitle(R.string.pk_dodaj_razdelek)
            .setItems(imena.toTypedArray()) { _, i ->
                val id = "razdelek:${r[i]}"
                val red = Priljubljene.redDomov(this).toMutableList()
                if (id !in red) { red.add(id); Priljubljene.shraniRedDomov(this, red) }
                narisiAplikacije()
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    // ------------------------------------------------------------------ plavajoca vrstica

    private var vrstaDok: List<String> = emptyList()

    private fun plavajocaVrstica(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = steklo(26, fokusMogoc = false)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        narisiDok(v)
        return v
    }

    private fun narisiDok(v: LinearLayout) {
        v.removeAllViews()
        vrstaDok = PokrajinaPravila.dokVrstniRed(shramba().getString("pk_dok", ""))
        for (r in vrstaDok) {
            val (ikona, napis) = when (r) {
                PokrajinaPravila.MEDIJI -> R.drawable.os_ikona_predvajaj to R.string.pk_mediji
                PokrajinaPravila.SPLET -> R.drawable.os_ikona_splet to R.string.os_meni_splet
                PokrajinaPravila.LINK -> R.drawable.os_ikona_link to R.string.os_meni_naprave
                PokrajinaPravila.SPOROCILA -> R.drawable.os_ikona_sporocila to R.string.os_meni_sporocila
                PokrajinaPravila.APLIKACIJE -> R.drawable.os_ikona_aplikacije to R.string.pk_aplikacije
                PokrajinaPravila.DATOTEKE -> R.drawable.os_ikona_datoteke to R.string.os_meni_datoteke
                else -> R.drawable.os_ikona_nastavitve to R.string.os_meni_nastavitve
            }
            val bes = besedilo(getString(napis), 15f).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
            val gumb = object : LinearLayout(this) {
                override fun focusSearch(direction: Int): View? {
                    if (direction == View.FOCUS_UP) {
                        val mini = dock?.aktivniPogledZaFokus?.takeIf { it.isShown && it.isFocusable }
                        if (dock?.jeOdprt == true && mini != null) return mini
                        if (mini != null) return mini
                        if (::vrstaAplikacij.isInitialized) {
                            najblizjiPogledX(vrstaAplikacij, this)?.let { return it }
                                ?: zadnjaPloscica?.let { vrstaAplikacij.findViewWithTag<View>(it) }?.let { return it }
                                ?: vrstaAplikacij.getChildAt(0)?.let { return it }
                        }
                    }
                    return super.focusSearch(direction)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                background = steklo(18).apply { alpha = 0 }
                setPadding(dp(8), dp(8), dp(8), dp(6))
                tag = "dok:$r"
                contentDescription = getString(napis)
                setOnFocusChangeListener { p, ima ->
                    p.background.alpha = if (ima) 255 else 0
                    bes.visibility = if (ima) View.VISIBLE else View.GONE
                }
                setOnClickListener { odpriRazdelek(r) }
                setOnLongClickListener { zacniPremikanjeDok(r); true }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (keyCode == 165 || PokrajinaPravila.jeTipkaMenija(keyCode))) {
                        moznostiDokGumba(r, getString(napis)); true
                    } else false
                }
                val ikonaOkvir = FrameLayout(this@PokrajinaActivity)
                ikonaOkvir.addView(ImageView(this@PokrajinaActivity).apply { setImageResource(ikona); setColorFilter(osBarva(R.color.os_mint)) },
                    FrameLayout.LayoutParams(dp(34), dp(34)))
                if (r == PokrajinaPravila.LINK) {
                    val pika = View(this@PokrajinaActivity).apply {
                        setBackgroundResource(R.drawable.os_pika)
                        visibility = View.GONE
                        tag = "link_pika"
                    }
                    ikonaOkvir.addView(pika, FrameLayout.LayoutParams(dp(8), dp(8)).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(2); marginEnd = dp(2) })
                }
                if (r == PokrajinaPravila.SPOROCILA) {
                    val znacka = TextView(this@PokrajinaActivity).apply {
                        gravity = Gravity.CENTER
                        setTextColor(osBarva(R.color.os_ozadje)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        typeface = Typeface.DEFAULT_BOLD
                        minWidth = dp(18); setPadding(dp(4), 0, dp(4), 0)
                        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(osBarva(R.color.os_mint)) }
                        visibility = View.GONE
                        tag = "znacka_sporocila"
                    }
                    ikonaOkvir.addView(znacka, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END).apply { topMargin = 0; marginEnd = 0 })
                }
                if (r == PokrajinaPravila.NASTAVITVE) {
                    val pika = View(this@PokrajinaActivity).apply {
                        setBackgroundResource(R.drawable.os_pika)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(osBarva(R.color.os_mint))
                        visibility = View.GONE
                        tag = "nastavitve_pika"
                    }
                    ikonaOkvir.addView(pika, FrameLayout.LayoutParams(dp(8), dp(8)).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(2); marginEnd = dp(2) })
                }
                addView(ikonaOkvir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(bes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            poudariFokus(gumb)
            v.addView(gumb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
    }

    private fun osveziDokZnacke() {
        val gumb = dok.findViewWithTag<View>("dok:" + PokrajinaPravila.SPOROCILA)
        if (gumb != null) {
            val znacka = gumb.findViewWithTag<TextView>("znacka_sporocila")
            if (znacka != null) {
                // Naloga 49: samo obvestila, ki cakajo na cloveka
                val koliko = ZnackeObvestil.stevci.values.sum()
                val b = ZaganjalnikPravila.besediloZnacke(koliko)
                znacka.text = b
                znacka.visibility = if (b.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
        val nastavitve = dok.findViewWithTag<View>("dok:" + PokrajinaPravila.NASTAVITVE)
        if (nastavitve != null) {
            val pika = nastavitve.findViewWithTag<View>("nastavitve_pika")
            if (pika != null) {
                pika.visibility = if (Posodobitve.shranjena(this) != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun moznostiDokGumba(id: String, ime: String) {
        val dejanja = listOf(
            getString(R.string.pk_premakni) to { zacniPremikanjeDok(id) },
            getString(R.string.os_spletne_levo) to { premakniDok(id, -1) },
            getString(R.string.os_spletne_desno) to { premakniDok(id, 1) }
        )
        val okno = android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setTitle(ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    private var premikamDok: String? = null

    private fun zacniPremikanjeDok(id: String) {
        premikamDok = id
        osveziStanjePremikanja(dok, "dok:$id", dp(4))
        dok.findViewWithTag<View>("dok:$id")?.requestFocus()
    }

    private fun koncajPremikanjeDok() {
        val id = premikamDok ?: return
        premikamDok = null
        osveziStanjePremikanja(dok, null, dp(4))
        dok.findViewWithTag<View>("dok:$id")?.requestFocus()
    }

    private fun osveziStanjePremikanja(vrsta: LinearLayout, aktivenTag: String?, osnovniRob: Int) {
        val zmanjsano = zmanjsano()
        for (i in 0 until vrsta.childCount) {
            val v = vrsta.getChildAt(i)
            val params = v.layoutParams as ViewGroup.MarginLayoutParams
            if (aktivenTag != null && v.tag == aktivenTag) {
                v.scaleX = if (zmanjsano) 1f else 1.1f
                v.scaleY = if (zmanjsano) 1f else 1.1f
                v.elevation = dp(8).toFloat()
                params.marginStart = osnovniRob + dp(14)
                params.marginEnd = osnovniRob + dp(14)
            } else {
                v.scaleX = 1f
                v.scaleY = 1f
                v.elevation = 0f
                params.marginStart = osnovniRob
                params.marginEnd = osnovniRob
            }
            v.layoutParams = params
        }
    }

    private fun premakniDok(id: String, korak: Int): Boolean {
        val nova = PokrajinaPravila.premakni(vrstaDok, id, korak) ?: return false
        vrstaDok = nova
        shramba().edit().putString("pk_dok", nova.joinToString(",")).apply()
        narisiDok(dok)
        dok.findViewWithTag<View>("dok:$id")?.requestFocus()
        return true
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

    /**
     * Naloga 42 D: Safeer Link -> povezane naprave ozjega kroga (druge naprave zunaj kroga se ne pokazejo). Klik na
     * napravo: gledanje zaslona in upravljanje z daljincem po isti poti kot kartica Zaslon na starem domacem zaslonu.
     * Zadnja vrstica odpre celoten Safeer Link (naprave, krog, nastavitve).
     */

    /** Odpiranje, ki ne utihne: napaka se pokaze (kot na starem domacem zaslonu), ne izgine. */
    private fun odpri(namera: Intent, moznosti: Bundle? = null): Boolean {
        return try {
            startActivity(namera, moznosti)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Odpiranje ni uspelo: ${e.javaClass.simpleName}")
            android.widget.Toast.makeText(this, getString(R.string.os_odpri_napaka, getString(
                if (e is android.content.ActivityNotFoundException) R.string.os_odpri_ni_aplikacije else R.string.os_odpri_ni_dovoljenja)),
                android.widget.Toast.LENGTH_LONG).show()
            false
        }
    }

    private val zadnjiZagoni = mutableMapOf<String, Long>()

    private fun preveriInOdpri(id: String, namera: Intent, moznosti: Bundle? = null) {
        val zdaj = android.os.SystemClock.uptimeMillis()
        val zadnji = zadnjiZagoni[id] ?: 0L
        if (!PokrajinaPravila.dovoljenZagon(zadnji, zdaj)) return
        
        if (odpri(namera, moznosti)) {
            zadnjiZagoni[id] = zdaj
            android.widget.Toast.makeText(this, R.string.pk_odpiram, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tipka Menu na daljincu (ali rdeči gumb / Settings): odpre možnosti nastavitev televizorja.
     * Na Philips TV odpre »Pogoste nastavitve« (Quick Settings stranski meni); če teh ni, sistemske
     * nastavitve Androida ali nastavitve Safeer OS.
     */
    private fun odpriNastavitveTv() {
        // 1. Philips Quick Settings (Pogoste nastavitve)
        try {
            val quickIntent = Intent().setClassName("org.droidtv.settings", "org.droidtv.settings.quicksettings.QuickSettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(quickIntent)
            return
        } catch (_: Throwable) { }

        // 2. Sistemske nastavitve Android TV
        try {
            val sysIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(sysIntent)
            return
        } catch (_: Throwable) { }

        // 3. Nastavitve Safeer OS
        odpri(Intent(this, NastavitveActivity::class.java))
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

    private var vremeNapaka = false
    /** Neuspeh zadnjega branja vremena je bil povezava (true) ali streznik vremena (false). */
    private var vremeBrezPovezave = true

    private fun prikaziVreme() {
        val s = shramba()
        val k = kraj()
        val cas = s.getLong(KLJUC_VREME_CAS, 0L)
        if (k == null || cas == 0L) { vreme.visibility = View.INVISIBLE; return }
        val v = VremePravila.Vreme(s.getInt(KLJUC_VREME_STOPINJ, 0), s.getInt(KLJUC_VREME_KODA, 0))
        val osnovno = ikona(VremePravila.vrsta(v.koda)) + "  " + VremePravila.napis(v)
        
        val podnaslov = if (vremeNapaka || !VremePravila.svezo(cas, System.currentTimeMillis())) {
            getString(if (vremeNapaka && !vremeBrezPovezave) si.safeer.tv.R.string.pk_vreme_ni_na_voljo
                      else si.safeer.tv.R.string.pk_ni_povezave)
        } else {
            k.ime
        }

        val izpis = android.text.SpannableString("$osnovno\n$podnaslov").apply {
            setSpan(android.text.style.RelativeSizeSpan(0.6f), osnovno.length + 1, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(osBarva(R.color.os_umirjeno)), osnovno.length + 1, length, 0)
        }
        vreme.text = izpis
        vreme.contentDescription = getString(R.string.pk_vreme_opis, k.ime, VremePravila.napis(v))
        vreme.visibility = View.VISIBLE
        if (::poljeLupe.isInitialized) {
            if (poljeLupe.id == View.NO_ID) poljeLupe.id = View.generateViewId()
            poljeLupe.nextFocusUpId = vreme.id
            vreme.nextFocusDownId = poljeLupe.id
        }
    }

    /** Skrije tedensko napoved; fokus, ki je bil v njej, se vrne na temperaturo (sicer bi ostal na skritem pogledu). */
    private fun skrijTeden() {
        val imel = teden.hasFocus()
        teden.visibility = View.GONE
        if (imel) vreme.requestFocus()
    }

    private fun preklopiTeden() {
        if (teden.visibility == View.VISIBLE) { skrijTeden(); return }
        val k = kraj() ?: return
        Thread({
            val dni = beri(VremePravila.naslovTedna(k))?.let { VremePravila.razcleniTeden(it) }.orEmpty()
            glavna.post {
                if (isFinishing || isDestroyed || dni.isEmpty()) return@post
                narisiTeden(dni)
            }
        }, "safeer-vreme-teden").start()
    }

    /**
     * Naloga 49 E: najvec [PokrajinaPravila.DNI_TEDNA] dni, vodoravno drsno, prvi dan v fokusu, levo/desno premika, Nazaj
     * zapre. Pregled 2. 11. 2026: prej so sli stolpci neposredno v HorizontalScrollView, ki sprejme samo enega otroka -
     * drugi dan je sesul domaci zaslon (IllegalStateException).
     */
    private fun narisiTeden(dni: List<VremePravila.Dan>) {
        tedenVrsta.removeAllViews()
        val vhod = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        val izhod = java.text.SimpleDateFormat("EEE", jezik())   // jezik vmesnika, kot ura in datum (pravilo 27. 10. 2026)
        for (d in dni.take(PokrajinaPravila.DNI_TEDNA)) {
            val dan = try { vhod.parse(d.datum)?.let { izhod.format(it) } } catch (_: Exception) { null } ?: d.datum.takeLast(5)
            val stolpec = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isFocusable = true
                background = steklo(12).apply { alpha = 0 }
                setOnFocusChangeListener { v, ima -> v.background.alpha = if (ima) 255 else 0 }
                setOnClickListener { skrijTeden() }
            }
            stolpec.addView(besedilo(dan, 13f, R.color.os_umirjeno).apply { gravity = Gravity.CENTER })
            stolpec.addView(besedilo(ikona(VremePravila.vrsta(d.koda)), 18f).apply { gravity = Gravity.CENTER })
            stolpec.addView(besedilo(VremePravila.napisDneva(d), 13f).apply { gravity = Gravity.CENTER })
            stolpec.contentDescription = dan + " " + VremePravila.napisDneva(d)
            tedenVrsta.addView(stolpec)
        }
        teden.visibility = View.VISIBLE
        teden.scrollTo(0, 0)
        tedenVrsta.getChildAt(0)?.let { prvi -> prvi.post { prvi.requestFocus() } }
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
            val json = beri(VremePravila.naslovNapovedi(k))
            val v = json?.let { VremePravila.razcleniVreme(it) }
            if (v != null) {
                shramba().edit().putInt(KLJUC_VREME_STOPINJ, v.stopinj).putInt(KLJUC_VREME_KODA, v.koda)
                    .putLong(KLJUC_VREME_CAS, System.currentTimeMillis()).apply()
                vremeNapaka = false
            } else {
                vremeBrezPovezave = VremeKraj.zadnjaNapakaPovezave
                vremeNapaka = true
            }
            glavna.post { if (!isFinishing && !isDestroyed) prikaziVreme() }
        }, "safeer-vreme").start()
    }

    /** GET za vreme in predloge lupe (VremeKraj.beri: rok, brez preusmeritev, najvec 64 KiB). */
    private fun beri(naslov: String): String? = VremeKraj.beri(naslov)

    companion object {
        private const val TAG = "SafeerPokrajina"
        const val KLJUC_ZMANJSANO = "pokrajina_zmanjsano_gibanje"
        const val KLJUC_SPLET_PREDLOGI = "pokrajina_splet_predlogi"
        private const val KLJUC_KRAJ_IME = VremeKraj.KLJUC_IME
        private const val KLJUC_KRAJ_SIRINA = VremeKraj.KLJUC_SIRINA
        private const val KLJUC_KRAJ_DOLZINA = VremeKraj.KLJUC_DOLZINA
        private const val KLJUC_VREME_CAS = VremeKraj.KLJUC_CAS
        private const val KLJUC_VREME_STOPINJ = "vreme_stopinj"
        private const val KLJUC_VREME_KODA = "vreme_koda"
    }
}
