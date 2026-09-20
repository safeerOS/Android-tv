package si.safeer.tv

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.widget.*
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : android.app.Activity(), si.safeer.tv.cast.CastReceiverService.CastMediaController,
    si.safeer.tv.link.Daljinec.VOspredju {

    internal lateinit var mainRoot: RelativeLayout
    internal lateinit var mobileTopBar: LinearLayout
    internal lateinit var btnBack: Button
    internal lateinit var btnHome: Button
    internal lateinit var btnReload: Button
    internal lateinit var btnFavorite: Button
    private lateinit var omniboxContainer: LinearLayout
    internal lateinit var tvSecurityLock: TextView
    internal lateinit var editUrl: EditText
    private lateinit var btnClearUrl: TextView
    internal lateinit var btnSearchTrigger: TextView
    private lateinit var btnPointerToggle: Button
    private lateinit var btnAddTab: Button
    private lateinit var btnTabCount: Button
    private lateinit var btnMenu: Button
    /** Meni, odprt z daljinca s telefona: tipke daljinca gredo vanj, dokler je odprt. */
    private var meniDaljinca: Dialog? = null
    private lateinit var pageProgressBar: ProgressBar
    internal lateinit var webViewContainer: FrameLayout
    internal lateinit var virtualPointerView: VirtualPointerView

    // Overlays & Secondary Views
    internal lateinit var searchSuggestionsOverlay: LinearLayout
    internal lateinit var portalChipsContainer: LinearLayout
    internal lateinit var suggestionsListContainer: LinearLayout

    internal lateinit var tabSwitcherOverlay: RelativeLayout
    private lateinit var tabsGridView: GridView
    private lateinit var btnNewTabInSwitcher: Button
    private lateinit var btnCloseTabsSwitcher: Button
    private lateinit var btnCloseAllTabs: TextView

    internal lateinit var findInPageBar: LinearLayout
    private lateinit var editFindText: EditText
    private lateinit var tvFindMatches: TextView
    private lateinit var btnFindPrev: Button
    private lateinit var btnFindNext: Button
    private lateinit var btnFindClose: Button

    // Managers & Repositories
    internal lateinit var tabManager: TabManager
    private lateinit var repository: BrowserRepository
    private lateinit var downloadHandler: DownloadHandler

    internal lateinit var chrome: TvChrome
    internal lateinit var keyRouter: TvKeyRouter
    internal lateinit var playback: HostPlayback
    internal lateinit var channelPad: ChannelDigitPad

    internal var customVideoView: View? = null
    internal var customVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var isDarkModeActive: Boolean = true

    internal fun activeWebView(): ChromiumEngineView? = tabManager.getActiveTab()?.webView

    internal fun activeUrl(): String = tabManager.getActiveTab()?.url ?: ""

    internal fun isChromeFocused(): Boolean {
        return btnBack.hasFocus() || btnHome.hasFocus() || btnReload.hasFocus() ||
            btnFavorite.hasFocus() || btnPointerToggle.hasFocus() ||
            btnAddTab.hasFocus() || btnTabCount.hasFocus() || btnMenu.hasFocus() ||
            btnClearUrl.hasFocus() || btnSearchTrigger.hasFocus() ||
            editUrl.hasFocus() || mobileTopBar.hasFocus()
    }

    internal fun isTopBarFocused(): Boolean = isChromeFocused()

    internal fun isTelevisionDevice(): Boolean {
        val ui = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return ui == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun applySystemUi() {
        if (!isTelevisionDevice()) return
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    /** D-Pad strip on the omnibox row. Skip tiny 🔍/✕ inside the URL field — they trap focus into the WebView. */
    internal fun moveChromeFocus(right: Boolean): Boolean {
        val chain = listOf(
            btnBack, btnHome, btnReload, editUrl, btnFavorite, btnPointerToggle, btnAddTab, btnTabCount, btnMenu
        ).filter { it.visibility == View.VISIBLE }
        if (chain.isEmpty()) return false
        val focused = currentFocus
        val idx = when {
            focused == null -> -1
            focused === btnSearchTrigger || focused === btnClearUrl -> chain.indexOf(editUrl)
            else -> chain.indexOfFirst { it === focused }
        }
        if (idx < 0) return false
        val next = idx + if (right) 1 else -1
        if (next !in chain.indices) return true
        val target = chain[next]
        if (target !== editUrl) hideKeyboard()
        target.requestFocus()
        return true
    }

    internal fun superDispatchKey(event: KeyEvent): Boolean = super.dispatchKeyEvent(event)

    private fun handlePageScroll(@Suppress("UNUSED_PARAMETER") direction: Int, @Suppress("UNUSED_PARAMETER") scrollY: Int) {
        // Toolbar occupies its own row above the WebView. Sliding it away would leave a blank band.
        if (nacinAplikacije == null && mobileTopBar.translationY != 0f && mobileTopBar.visibility == View.VISIBLE) {
            mobileTopBar.animate().translationY(0f).setDuration(180).start()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        // Isti APK nosi vse prevode; privzeto odloca jezik televizorja, uporabnik pa
        // lahko v meniju izbere svojega.
        super.attachBaseContext(JezikVmesnika.vKontekstu(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aplikacijski kontekst v izbranem jeziku: nizi in glava Accept-Language
        // tako sledijo izbiri uporabnika, ne da bi zadrzali Activity v pomnilniku.
        UiText.init(JezikVmesnika.vKontekstu(applicationContext))
        setContentView(R.layout.activity_main)
        si.safeer.tv.os.Robovi.uporabi(this)

        window.statusBarColor = Color.parseColor("#06090F")
        window.navigationBarColor = Color.parseColor("#000000")
        applySystemUi()

        repository = BrowserRepository(this)
        downloadHandler = DownloadHandler(this)
        isDarkModeActive = getSharedPreferences("safeer_ui_prefs", MODE_PRIVATE).getBoolean("dark_mode", true)

        initViews()
        playback = HostPlayback(this)
        // Ujet pretok predamo domacemu predvajalniku, na katerikoli strani smo.
        DashPrevzem.listener = { session ->
            runOnUiThread { playback.playDash(session) }
        }
        DashPrevzem.onNeedPageLicense = {
            runOnUiThread {
                activeWebView()?.evaluateJavascript(
                    "try{window._safeer_medij_report_drm_cfg&&window._safeer_medij_report_drm_cfg()}catch(e){}",
                    null
                )
            }
        }
        chrome = TvChrome(this)
        chrome.poveziFokus()
        channelPad = ChannelDigitPad(this)
        keyRouter = TvKeyRouter(this)
        setupTabManager()
        setupOmnibox()
        setupSearchSuggestions()
        setupTopButtons()
        setupTouchGestures()
        setupFindInPage()

        // Safeer Cast: sprejemnik povezav s telefona in računalnika (vozlišče v domačem omrežju).
        startCastReceiver()

        // Safeer Hub v tem brskalniku. Ne zažene se sam od sebe: steče le, če ga je uporabnik
        // v Safeer Linku že prižgal. Takrat je televizor vozlišče za telefone v hiši tudi
        // brez računalnika.
        try { si.safeer.tv.cast.HubStoritev.zagotovi(this) } catch (_: Exception) {}

        // Agent za sezname groženj (Feodo, URLhaus, Phishing Army): shranjeni seznami takoj v ozadju,
        // preverjanje novih ~12 s po zagonu. Zagona in nalaganja strani ne upočasni.
        ThreatFeedsUpdater.start(this)
        // Dodatna plast: preverjen, podpisan seznam Safeer Threat Intelligence (izklopljen brez ključa)
        SignedThreatIntel.start(this)
        installServiceWorkerThreatShield()

        val targetUrl = incomingBrowseUrl(intent) ?: "file:///android_asset/brave_home.html"
        tabManager.createTab(this, targetUrl, true)

        if (BuildConfig.DEBUG) {
        debugJsReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "si.safeer.tv.EVAL_JS" -> {
                        val cmd = intent.getStringExtra("cmd") ?: return
                        tabManager.getActiveTab()?.webView?.evaluateJavascript(cmd, null)
                    }
                    "si.safeer.tv.EXO_SMOKE" -> {
                        SafeerDbg.log("H337", "MainActivity.kt:smoke", "broadcast smoke", JSONObject())
                        playback.playClearSmoke()
                    }
                    "si.safeer.tv.ACTION_OPEN_URL" -> {
                        val url = intent.getStringExtra("url") ?: return
                        val newTab = intent.getBooleanExtra("new_tab", false)
                        val activeTab = tabManager.getActiveTab()
                        if (newTab || activeTab == null) {
                            tabManager.createTab(this@MainActivity, url, true)
                        } else {
                            activeTab.webView.loadUrl(url)
                        }
                        showTvOsd("🌐 Povezava na TV", url.take(50))
                    }
                    "si.safeer.tv.ACTION_CHANNEL_TUNE" -> {
                        val ch = intent.getIntExtra("channel", -1)
                        if (ch > 0) {
                            playback.tuneLiveChannel(ch)
                            showTvOsd("📺 Preklop na kanal", "Kanal $ch")
                        }
                    }
                    "si.safeer.tv.ACTION_PLAY_PAUSE" -> {
                        playback.togglePlayPause()
                    }
                    "si.safeer.tv.ACTION_SEEK" -> {
                        val delta = intent.getIntExtra("seconds", 10)
                        playback.seekBy(delta)
                    }
                    "si.safeer.tv.ACTION_SEARCH" -> {
                        val query = intent.getStringExtra("query") ?: ""
                        val engine = intent.getStringExtra("engine") ?: "google"
                        if (query.isNotEmpty()) {
                            val searchUrl = when (engine.lowercase()) {
                                "youtube", "yt" -> "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
                                else -> "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
                            }
                            val activeTab = tabManager.getActiveTab()
                            if (activeTab != null) {
                                activeTab.webView.loadUrl(searchUrl)
                            } else {
                                tabManager.createTab(this@MainActivity, searchUrl, true)
                            }
                            showTvOsd("🔍 Iskanje ($engine)", query)
                        }
                    }
                    "si.safeer.tv.ACTION_SCREEN_OFF_AUDIO" -> {
                        val enable = intent.getBooleanExtra("enable", true)
                        toggleScreenOffAudio(enable)
                    }
                }
            }
        }
        val debugFilter = android.content.IntentFilter().apply {
            addAction("si.safeer.tv.EVAL_JS")
            addAction("si.safeer.tv.EXO_SMOKE")
            addAction("si.safeer.tv.ACTION_OPEN_URL")
            addAction("si.safeer.tv.ACTION_CHANNEL_TUNE")
            addAction("si.safeer.tv.ACTION_PLAY_PAUSE")
            addAction("si.safeer.tv.ACTION_SEEK")
            addAction("si.safeer.tv.ACTION_SEARCH")
            addAction("si.safeer.tv.ACTION_SCREEN_OFF_AUDIO")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugJsReceiver, debugFilter, Context.RECEIVER_EXPORTED)
            } else {
                // Samo razvojna gradnja (BuildConfig.DEBUG); pred Androidom 13 zastavice ni.
                @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
                registerReceiver(debugJsReceiver, debugFilter)
            }
        } catch (_: Exception) {}
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("exo_smoke", false) == true) {
            webViewContainer.post { playback.playClearSmoke() }
        }
        // Safeer OS (lupina) odpre stran Safeer Link neposredno: seznanitev, naprave, daljinec.
        if (intent?.getBooleanExtra(EXTRA_ODPRI_LINK, false) == true) {
            intent.removeExtra(EXTRA_ODPRI_LINK)
            zapomniLinkIzOs(intent)
            webViewContainer.post { odpriSafeerLink() }
        }
        intent?.getStringExtra(EXTRA_SPLETNA_APLIKACIJA)?.let { naslov ->
            val ime = intent.getStringExtra(EXTRA_APLIKACIJA_IME).orEmpty()
            intent.removeExtra(EXTRA_SPLETNA_APLIKACIJA)
            webViewContainer.post { vklopiNacinAplikacije(naslov, ime) }
        }
        zapomniIzvor(intent)
    }

    /**
     * Ce je brskalnik odprl Safeer OS, si to zapomnimo: ob izhodu iz brskalnika se vrnemo v
     * Safeer OS. Uporabnik, ki je v lupini, ne sme nepricakovano pristati na zaslonu Androida -
     * iz Safeer OS se gre ven samo takrat, kadar to sam izbere (nastavitve Safeer OS).
     */
    private fun zapomniIzvor(namera: Intent?) {
        val paket = namera?.getStringExtra(EXTRA_IZ_SAFEER_OS)
        if (!paket.isNullOrBlank()) izSafeerOs = paket
    }

    /** Safeer OS je odprl stran Link: zapomnimo si, da mora biti videti in se obnasati kot del sistema. */
    private fun zapomniLinkIzOs(namera: Intent) {
        linkIzOs = !namera.getStringExtra(EXTRA_IZ_SAFEER_OS).isNullOrBlank()
        linkOsOzadje = namera.getStringExtra(EXTRA_OS_OZADJE)
        linkOsVrni = namera.getStringExtra("os_vrni")
        linkOsZatemnitev = namera.getIntExtra(EXTRA_OS_ZATEMNITEV, si.safeer.tv.os.Ozadje.PRIVZETA_ZATEMNITEV)
    }

    /**
     * Zaprta stran Link, ki jo je odprl Safeer OS: nazaj v Safeer OS. Brskalnika ne koncamo, kadar
     * je Safeer OS drug paket - morda ima uporabnik v njem odprte zavihke; samo Safeer OS pride naprej.
     */
    private fun vrniVOsPoLinku() {
        val paket = izSafeerOs
        if (paket == null || paket == packageName) { finish(); return }
        // Samo NEW_TASK, brez CLEAR_TOP: Android prinese obstojeco nalogo Safeer OS v ospredje tocno
        // tam, kjer je uporabnik bil (Naprave ali Domov), namesto da bi jo pocistil.
        try {
            startActivity(Intent()
                .setComponent(android.content.ComponentName(paket, "si.safeer.tv.os.DomovActivity"))
                .putExtra("os_vrni", linkOsVrni)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) { android.util.Log.w("SafeerLink", "Nazaj v Safeer OS ni slo: ${e.message}") }
    }

    /** Konec brskanja: nazaj v Safeer OS, ce je brskalnik odprl on; sicer navaden konec. */
    private fun koncajVrniSe() {
        val paket = izSafeerOs
        if (paket != null && paket != packageName) {
            try {
                startActivity(Intent()
                    .setComponent(android.content.ComponentName(paket, "si.safeer.tv.os.DomovActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (e: Throwable) {
                android.util.Log.w("SafeerTV", "V Safeer OS se ni bilo mogoce vrniti: ${e.message}")
            }
        }
        finish()
    }

    // ------------------------------------------------------------------ nacin aplikacije (spletne aplikacije Safeer OS)

    /** Naslov spletne aplikacije, dokler tece v nacinu aplikacije; sicer null. */
    internal var nacinAplikacije: String? = null
        private set
    /** Zavihek, v katerem tece spletna aplikacija; ob izhodu ga zapremo. */
    private var zavihekAplikacije: String? = null

    /**
     * Spletna stran kot aplikacija: cez ves zaslon, brez vrstice z naslovom in brez zavihkov -
     * uporabnik vidi samo aplikacijo. Zascita (Scit, blokiranje oglasov in nevarnih strani,
     * preprecevanje pojavnih oken) ostane ista kot v brskalniku. Nazaj gre po zgodovini, na
     * zacetku pa zapre aplikacijo in vrne Safeer OS.
     */
    internal fun vklopiNacinAplikacije(naslov: String, ime: String) {
        nacinAplikacije = naslov
        mobileTopBar.visibility = View.GONE
        // Ob zagonu brskalnika se domaca stran nalozi z zamikom; aplikacijo nalozimo za njo,
        // da ne tekmujeta za isti zavihek.
        mainHandler.postDelayed({
            if (nacinAplikacije != naslov) return@postDelayed
            mobileTopBar.visibility = View.GONE
            // Aplikacija dobi svoj zavihek: njena zgodovina se zacne pri njej, zato Nazaj na
            // zacetku zapre aplikacijo in ne pripelje na domaco stran brskalnika.
            // Nadaljujemo, kjer je uporabnik koncal (ce je bil tam pred kratkim); sicer zacetna stran.
            val zacetni = try { si.safeer.tv.os.SpletneAplikacije.nadaljevanje(this, naslov) } catch (_: Throwable) { naslov }
            zavihekAplikacije = try { tabManager.createTab(this, zacetni, true).id } catch (_: Throwable) { null }
            if (zavihekAplikacije == null) odpriVZavihku(zacetni)
            activeWebView()?.requestFocus()
        }, 350)
        if (ime.isNotBlank()) Toast.makeText(this, UiText.get(R.string.ui_web_app_odprta, ime), Toast.LENGTH_LONG).show()
    }

    /**
     * Doda stran, ki je odprta, med spletne aplikacije Safeer OS. Ime in ikono poisce v ozadju
     * (manifest spletne aplikacije); domaci zaslon jo pokaze takoj, ikona pride za njo.
     */
    private fun dodajTrenutnoStranMedAplikacije() {
        val naslov = activeUrl()
        if (naslov.isBlank() || TvSite.isBrowserHome(naslov) || !naslov.startsWith("http")) {
            Toast.makeText(this, UiText.get(R.string.ui_aplikacija_ni_strani), Toast.LENGTH_LONG).show()
            return
        }
        val ime = (tabManager.getActiveTab()?.title ?: "").ifBlank { si.safeer.tv.os.SpletneAplikacije.gostitelj(naslov) }
        if (si.safeer.tv.os.SpletneAplikacije.jeDodana(this, naslov)) {
            Toast.makeText(this, UiText.get(R.string.ui_aplikacija_ze_dodana, ime), Toast.LENGTH_LONG).show()
            return
        }
        si.safeer.tv.os.SpletneAplikacije.dodaj(this, naslov, ime) {
            // Kartica se pojavi tudi v vrsti Safeer na domacem zaslonu televizorja.
            try { si.safeer.tv.os.DomacaVrsta.osvezi(this) } catch (_: Throwable) { }
        }
        Toast.makeText(this, UiText.get(R.string.ui_aplikacija_dodana, ime), Toast.LENGTH_LONG).show()
    }

    /** Kje je uporabnik v tej aplikaciji ostal; ob vrnitvi v pol ure ga postavimo nazaj tja. */
    private fun zapomniMestoAplikacije(kljuc: String) {
        try { si.safeer.tv.os.SpletneAplikacije.zapomniMesto(this, kljuc, activeUrl()) } catch (_: Throwable) { }
    }

    internal fun izklopiNacinAplikacije() {
        val bila = nacinAplikacije ?: return
        zapomniMestoAplikacije(bila)
        nacinAplikacije = null
        zavihekAplikacije?.let { id ->
            zavihekAplikacije = null
            try { tabManager.closeTab(this, id) } catch (_: Throwable) { }
        }
        mobileTopBar.visibility = View.VISIBLE
        mobileTopBar.translationY = 0f
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var debugJsReceiver: android.content.BroadcastReceiver? = null
    private var webViewsPaused = false
    /** Stevec utisanj: zakasnjeni onPause pogleda velja le, ce vmes ni bilo onResume. */
    private var generacijaUtisanja = 0
    private var globalOsdView: TextView? = null
    private var screenOffOverlay: View? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Pomnilnik v ozadju: televizor ima 2-3 GB pomnilnika in Safeer je z odprtimi stranmi
     * njegov najvecji porabnik tudi takrat, ko uporabnik gleda kaj drugega. Ko brskalnik
     * zapusti zaslon, zavihki v ozadju zaspijo takoj; aktivni zavihek zaspi, ce se brskalnik
     * v SPANJE_AKTIVNEGA_MS ne vrne. Ob vrnitvi se stran nalozi znova. Zavihek, ki predvaja,
     * ne zaspi nikoli; Safeer Link (sredisce) in daljinec delujeta ves cas.
     */
    /** Dodatek namere, s katerim Safeer OS odpre stran Safeer Link. */
    private val EXTRA_ODPRI_LINK = "odpri_link"
    /** Dodatek namere: brskalnik je odprl Safeer OS in ob izhodu se vrnemo vanj, ne na Android. */
    private val EXTRA_IZ_SAFEER_OS = "iz_safeer_os"
    /** Ozadje in zatemnitev Safeer OS, da stran Link lezi na istem ozadju kot sistem. */
    private val EXTRA_OS_OZADJE = "os_ozadje"
    private val EXTRA_OS_ZATEMNITEV = "os_zatemnitev"
    /** Paket Safeer OS, ce je brskalnik odprl on; sicer null. */
    private var izSafeerOs: String? = null
    /** Stran Safeer Link je odprl Safeer OS: ima videz sistema, ob zaprtju gremo nazaj v Safeer OS. */
    private var linkIzOs = false
    private var linkOsOzadje: String? = null
    private var linkOsZatemnitev = si.safeer.tv.os.Ozadje.PRIVZETA_ZATEMNITEV
    /** Uporabnik je s strani Link odprl spletno stran: takrat ostanemo v brskalniku. */
    private var linkOdprlZavihek = false
    /** Deljenje zaslona iz strani Link: komu (id, ime) in most, ki mu povemo izid dovoljenja. */
    private var linkZaslonCilj = ""
    private var linkZaslonIme = ""
    /**
     * Brskalnik je na zaslon prisel samo zato, ker je druga naprava zacela deliti zaslon. Ko deljenje
     * konca, se umakne in televizor pokaze tisto, kar je bilo prej (Safeer OS, program ...).
     */
    private var deljenjeOdprloBrskalnik = false
    private var linkMost: si.safeer.tv.link.LinkMost? = null
    /** Koda zahteve za sistemsko okno »Zacni zajem zaslona«. */
    private val ZAHTEVA_ZAJEM_ZASLONA = 4711
    /** Kam v Safeer OS se vrnemo po zaprtju strani Link (npr. "naprave"); prazno = domaci zaslon. */
    private var linkOsVrni: String? = null
    /** Dodatka, s katerima Safeer OS odpre spletno aplikacijo cez ves zaslon. */
    private val EXTRA_SPLETNA_APLIKACIJA = "spletna_aplikacija"
    private val EXTRA_APLIKACIJA_IME = "aplikacija_ime"
    private val SPANJE_AKTIVNEGA_MS = 10 * 60 * 1000L
    private var naZaslonu = true
    private val spanjeAktivnega = Runnable {
        if (naZaslonu || !::tabManager.isInitialized) return@Runnable
        try {
            tabManager.uspavajVOzadju(tudiAktivni = true)
            // Pogled je unicen, a Chromium in nas kup obdrzita svoje predpomnilnike, dokler ju
            // sistem ne opozori. Opozorilo sprozimo sami: WebView (registriran kot
            // ComponentCallbacks2) sprosti V8, slike in graficne vire, mi pa svoje.
            try { application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) } catch (_: Throwable) {}
            UserScriptManager.sprostiPredpomnilnik()
            System.gc()
            android.util.Log.i("SafeerPomnilnik", "Brskalnik je dolgo v ozadju; spi ${tabManager.steviloSpecih()} zavihkov, predpomnilniki sprosceni.")
        } catch (_: Exception) {}
    }

    private val hideGlobalOsdRunnable = Runnable {
        globalOsdView?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
            globalOsdView?.visibility = View.GONE
        }?.start()
    }

    fun showTvOsd(title: String, subtitle: String? = null, durationMs: Long = 3000L) {
        runOnUiThread {
            if (globalOsdView == null) {
                val tv = TextView(this)
                tv.setTextColor(Color.WHITE)
                tv.textSize = 22f
                tv.setPadding(44, 20, 44, 20)
                tv.gravity = Gravity.CENTER
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#E60B0F17"))
                    cornerRadius = 20f
                    setStroke(2, Color.parseColor("#3300D2FF"))
                }
                tv.background = bg
                tv.elevation = 60f
                val lp = RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                    bottomMargin = 70
                }
                mainRoot.addView(tv, lp)
                globalOsdView = tv
            }
            val text = if (!subtitle.isNullOrEmpty()) "$title\n$subtitle" else title
            globalOsdView?.text = text
            globalOsdView?.alpha = 1f
            globalOsdView?.visibility = View.VISIBLE
            globalOsdView?.bringToFront()
            mainHandler.removeCallbacks(hideGlobalOsdRunnable)
            if (durationMs > 0) {
                mainHandler.postDelayed(hideGlobalOsdRunnable, durationMs)
            }
        }
    }

    fun isScreenOffActive(): Boolean = screenOffOverlay != null

    fun toggleScreenOffAudio(enable: Boolean? = null) {
        runOnUiThread {
            val shouldEnable = enable ?: (screenOffOverlay == null)
            if (shouldEnable) {
                acquireWakeLock()
                if (screenOffOverlay == null) {
                    val overlay = View(this).apply {
                        setBackgroundColor(Color.BLACK)
                        isClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        elevation = 120f
                        setOnClickListener { toggleScreenOffAudio(false) }
                    }
                    mainRoot.addView(
                        overlay,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    screenOffOverlay = overlay
                    overlay.requestFocus()
                }
                showTvOsd(UiText.get(R.string.ui_background_audio), UiText.get(R.string.ui_press_key), 4000L)
            } else {
                screenOffOverlay?.let { mainRoot.removeView(it) }
                screenOffOverlay = null
                showTvOsd(UiText.get(R.string.ui_screen_on), durationMs = 2000L)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Safeer:BackgroundAudioWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun silenceBackgroundMedia(reason: String) {
        if (!::tabManager.isInitialized) return
        val tabs = tabManager.getAllTabs()
        // #region agent log
        SafeerDbg.log(
            "H210",
            "MainActivity.kt:$reason",
            "pauseAll",
            JSONObject()
                .put("tabs", tabs.size)
                .put("already", webViewsPaused)
                .put("wl", wakeLock?.isHeld == true)
        )
        // #endregion
        val js = """
            (function(){
                window._safeer_app_bg = true;
                try { sessionStorage.setItem('safeer_app_bg','1'); } catch (eS) {}
                try { if (window._safeerSiteAgent && window._safeerSiteAgent.clearWant) window._safeerSiteAgent.clearWant(); } catch (e) {}
                var n = 0;
                document.querySelectorAll('video,audio').forEach(function(m){
                    try { m.pause(); m.muted = true; m.volume = 0; n++; } catch (e2) {}
                });
                try { if (navigator.mediaSession) navigator.mediaSession.playbackState = 'paused'; } catch (e3) {}
                return n;
            })();
        """.trimIndent()
        val firstSilence = !webViewsPaused
        webViewsPaused = true
        // Utisanje tece po klicu JS, torej z zamikom. Ce se brskalnik vmes ze vrne na zaslon
        // (onResume), zakasneli onPause/pauseTimers NE smeta vec teci: pogled bi ostal ustavljen
        // in slika na televizorju bi zamrznila, ceprav se stran nalozi (npr. odprtje naslova
        // od zunaj, kratek dialog). Vsak onResume zato razveljavi cakajoce utisanje.
        val generacija = ++generacijaUtisanja
        // Domace predvajanje tece v nasem predvajalniku in ne v strani: takrat casovnikov ne
        // ustavljamo in budnost obdrzimo, sicer slika zamrzne. Velja za vsako stran enako.
        val predvajaDomace = playback.isNativeActive()
        for (tab in tabs) {
            try {
                if (firstSilence) {
                    tab.webView.evaluateJavascript(js) {
                        if (generacija != generacijaUtisanja) return@evaluateJavascript
                        try { tab.webView.onPause() } catch (_: Exception) {}
                        if (!predvajaDomace) {
                            try { tab.webView.pauseTimers() } catch (_: Exception) {}
                        }
                    }
                } else {
                    tab.webView.post {
                        if (generacija != generacijaUtisanja) return@post
                        try { tab.webView.onPause() } catch (_: Exception) {}
                        if (!predvajaDomace) {
                            try { tab.webView.pauseTimers() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
                try { tab.webView.onPause() } catch (_: Exception) {}
            }
        }
        if (!predvajaDomace) {
            releaseWakeLock()
            try {
                @Suppress("DEPRECATION")
                (getSystemService(AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocus(null)
            } catch (_: Exception) {}
        }
    }

    private fun resumeBackgroundMedia() {
        generacijaUtisanja++   // cakajoce zakasnjeno utisanje (glej silenceBackgroundMedia) ne velja vec
        if (!::tabManager.isInitialized) return
        val tabs = tabManager.getAllTabs()
        // #region agent log
        SafeerDbg.log(
            "H211",
            "MainActivity.kt:onResume",
            "resumeAll",
            JSONObject().put("tabs", tabs.size).put("paused", webViewsPaused)
        )
        // #endregion
        try { tabManager.getActiveTab()?.webView?.resumeTimers() } catch (_: Exception) {}
        val activeId = tabManager.getActiveTab()?.id
        for (tab in tabs) {
            try {
                if (tab.id == activeId) tab.webView.onResume()
                else tab.webView.onPause()
            } catch (_: Exception) {}
            if (tab.id == activeId) {
                try {
                    tab.webView.evaluateJavascript(
                        "try{window._safeer_app_bg=false;sessionStorage.removeItem('safeer_app_bg');}catch(e){}",
                        null
                    )
                } catch (_: Exception) {}
            }
        }
        webViewsPaused = false
    }

    internal fun stopPageMedia(reason: String) {
        if (!::tabManager.isInitialized) return
        val tabs = tabManager.getAllTabs()
        // #region agent log
        SafeerDbg.log(
            "H220",
            "MainActivity.kt:$reason",
            "stopPage",
            JSONObject().put("tabs", tabs.size)
        )
        // #endregion
        val js = """
            (function(){
                window._safeer_app_bg = true;
                try { sessionStorage.setItem('safeer_app_bg','1'); } catch (eS) {}
                try { if (window._safeerSiteAgent && window._safeerSiteAgent.clearWant) window._safeerSiteAgent.clearWant(); } catch (e) {}
                var n = 0;
                document.querySelectorAll('video,audio').forEach(function(m){
                    try { m.pause(); m.muted = true; m.volume = 0; n++; } catch (e2) {}
                });
                try { if (navigator.mediaSession) navigator.mediaSession.playbackState = 'paused'; } catch (e3) {}
                try { if (window._safeerDbg) window._safeerDbg('H220','site_agent.js','stopPage',{n:n,path:(location.pathname||'').slice(0,40)}); } catch (e4) {}
                return n;
            })();
        """.trimIndent()
        for (tab in tabs) {
            try { tab.webView.evaluateJavascript(js, null) } catch (_: Exception) {}
        }
        try {
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocus(null)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        nacinAplikacije?.let { zapomniMestoAplikacije(it) }
        si.safeer.tv.cast.CastReceiverService.krmilnikVOspredju = false
        si.safeer.tv.cast.HubKrmilnik.naPrijavoZaZaslon = null
        silenceBackgroundMedia("onPause")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Spletna aplikacija tece cez ves zaslon tudi po vrnitvi iz ozadja.
        if (nacinAplikacije != null && ::mobileTopBar.isInitialized) mobileTopBar.visibility = View.GONE
        si.safeer.tv.cast.CastReceiverService.krmilnikVOspredju = true
        si.safeer.tv.cast.HubKrmilnik.naPrijavoZaZaslon = { runOnUiThread { pokaziKodoZaSeznanitev() } }
        pokaziKodoZaSeznanitev()
        obravnavajCastNamero(intent)
        resumeBackgroundMedia()
    }

    override fun onStart() {
        super.onStart()
        naZaslonu = true
        mainHandler.removeCallbacks(spanjeAktivnega)
        if (::tabManager.isInitialized) {
            try { tabManager.zbudiAktivnega() } catch (_: Exception) {}
        }
    }

    override fun onStop() {
        silenceBackgroundMedia("onStop")
        naZaslonu = false
        if (::tabManager.isInitialized) {
            try {
                tabManager.uspavajVOzadju(tudiAktivni = false)
                mainHandler.removeCallbacks(spanjeAktivnega)
                mainHandler.postDelayed(spanjeAktivnega, SPANJE_AKTIVNEGA_MS)
            } catch (_: Exception) {}
        }
        super.onStop()
    }

    /**
     * Sistem javi, da mu zmanjkuje pomnilnika. To je edini trenutek, ko lahko kaj ukrenemo,
     * preden nas ubije: zavihki v ozadju gredo spat, stran pa se ob vrnitvi nalozi znova.
     * Aktivnega zavihka in tistega, ki predvaja, se ne dotaknemo.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level < TRIM_MEMORY_RUNNING_LOW) return
            val vse = level >= TRIM_MEMORY_RUNNING_CRITICAL
            if (::tabManager.isInitialized) tabManager.uspavajOzadje(vse)
            if (vse) {
                if (::tabManager.isInitialized) tabManager.sprostiPredpomnilnike()
                UserScriptManager.sprostiPredpomnilnik()
            }
            android.util.Log.i(
                "SafeerPomnilnik",
                "Sistem javlja pomanjkanje pomnilnika (stopnja $level); zavihki v ozadju gredo spat."
            )
        } catch (_: Exception) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            if (::tabManager.isInitialized) tabManager.uspavajOzadje(true)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        silenceBackgroundMedia("onDestroy")
        // Huba tu NE ustavljamo: od 2.1.89 zivi v HubStoritev, da je televizor dosegljiv
        // tudi, ko brskalnik ni odprt. Ugasne ga uporabnik v Safeer Linku.
        if (::tabManager.isInitialized) {
            for (tab in tabManager.getAllTabs()) {
                try { tab.webView.destroy() } catch (_: Exception) {}
            }
        }
        try { debugJsReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { playback.release() } catch (_: Exception) {}
        DashPrevzem.listener = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun incomingBrowseUrl(intent: Intent?): String? {
        val data = intent?.dataString
        if (!data.isNullOrEmpty()) return data
        val q = intent?.getStringExtra(SearchManager.QUERY) ?: intent?.getStringExtra("query")
        if (!q.isNullOrBlank()) {
            return "https://www.google.com/search?q=" + URLEncoder.encode(q.trim(), "UTF-8")
        }
        return null
    }

    /**
     * Namera, s katero nas prebudi sprejemnik Safeer Cast. Dodatek porabimo takoj, da se
     * naslov ob naslednjem onResume ne nalozi se enkrat.
     */
    private fun obravnavajCastNamero(namera: Intent?): Boolean {
        val url = namera?.getStringExtra(si.safeer.tv.cast.CastReceiverService.EXTRA_CAST_URL)
        if (url.isNullOrEmpty()) return false
        namera.removeExtra(si.safeer.tv.cast.CastReceiverService.EXTRA_CAST_URL)
        deljenjeOdprloBrskalnik = url.contains("/cast/screen/")
        val naslov = namera.getStringExtra(si.safeer.tv.cast.CastReceiverService.EXTRA_CAST_TITLE)
        val mesto = namera.getDoubleExtra(si.safeer.tv.cast.CastReceiverService.EXTRA_CAST_POSITION, 0.0)
        onCastUrlReceived(url, naslov, mesto)
        return true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        zapomniIzvor(intent)
        if (obravnavajCastNamero(intent)) return
        if (intent?.getBooleanExtra(EXTRA_ODPRI_LINK, false) == true) {
            intent.removeExtra(EXTRA_ODPRI_LINK)
            zapomniLinkIzOs(intent)
            webViewContainer.post { odpriSafeerLink() }
            return
        }
        val spletna = intent?.getStringExtra(EXTRA_SPLETNA_APLIKACIJA)
        if (spletna != null) {
            val ime = intent.getStringExtra(EXTRA_APLIKACIJA_IME).orEmpty()
            intent.removeExtra(EXTRA_SPLETNA_APLIKACIJA)
            webViewContainer.post { vklopiNacinAplikacije(spletna, ime) }
            return
        }
        // Navaden zagon brskalnika po tem, ko je tekla spletna aplikacija: vrni vrstico z naslovom.
        if (nacinAplikacije != null && intent?.action == Intent.ACTION_MAIN) izklopiNacinAplikacije()
        val url = incomingBrowseUrl(intent)
        if (intent?.getBooleanExtra("exo_smoke", false) == true) {
            playback.playClearSmoke()
            return
        }
        if (!url.isNullOrEmpty()) {
            val activeTab = tabManager.getActiveTab()
            if (activeTab != null) {
                activeTab.webView.loadUrl(url)
            } else {
                tabManager.createTab(this, url, true)
            }
        } else if (intent?.action == Intent.ACTION_MAIN) {
            // #region agent log
            SafeerDbg.log(
                "H110",
                "MainActivity.kt:onNewIntent",
                "launcher home",
                org.json.JSONObject().put("action", intent.action ?: "")
            )
            // #endregion
            showBrowserStartPage()
        }
    }

    // ------------------------------------------------------------------
    // Safeer Cast — sprejemnik povezav iz domačega omrežja
    // ------------------------------------------------------------------

    /** Zadnje znano stanje predvajanja; pošiljatelju ga javimo ob vsaki spremembi. */
    private var castState: String = "idle"
    private var castPosition: Double = 0.0
    private var castDuration: Double = 0.0
    private var castPollRunning = false

    private fun startCastReceiver() {
        try {
            si.safeer.tv.cast.CastReceiverService.mediaController = this
            val zazeni = {
                // Isto ime kot Safeer OS in hub (os_ime_vrste): ena naprava, eno ime v Linku.
                si.safeer.tv.cast.CastReceiverService.start(
                    this,
                    null,
                    getString(R.string.os_ime_vrste) + " (" + android.os.Build.MODEL + ")"
                )
            }
            // Ob zagonu NIKOLI ne sprozimo seznanjanja -- brskalnik je najprej brskalnik.
            // Sprejemnik zazenemo tiho SAMO, ce je televizor ze seznanjen; to je uporabnik
            // izbral sam prek menija Safeer Link. Ce ni seznanjen, se ne zgodi nic: nobene
            // kode, nobenega obvestila. Kodo za seznanitev pokaze le Safeer Link, ko jo
            // uporabnik izrecno zahteva.
            if (si.safeer.tv.cast.CastReceiverService.isConfigured(this)
                    && si.safeer.tv.cast.HubPairing.token(this) != null) {
                zazeni()
            }
        } catch (e: Exception) {
            android.util.Log.w("SafeerCast", "Sprejemnika ni bilo mogoce zagnati: " + e.message)
        }
    }

    /** JavaScript za vse predvajalnike v strani (video in audio). */
    /**
     * Daljinec Safeer Controla (prek Safeer Linka), ko je brskalnik v ospredju: tipke gredo
     * po isti poti kot s pravega daljinca, drsenje in posnetek zaslona po dejavnem zavihku.
     * Kar ni nasteto, izvede storitev sama (glasnost, aplikacije, ponovni zagon ...).
     */
    override fun izvediUkaz(dejanje: String, parametri: JSONObject): si.safeer.tv.link.Daljinec.Izid? {
        return when (dejanje) {
            "key" -> {
                val ime = parametri.optString("key", "").trim().lowercase()
                if (ime == "home") {
                    try { if (playback.isActive()) playback.exit() } catch (_: Exception) { }
                    showBrowserStartPage()
                    return si.safeer.tv.link.Daljinec.Izid(true, "Domov")
                }
                val meni = meniDaljinca?.takeIf { it.isShowing }
                if (ime == "menu") {
                    // Sistemske tipke MENU brskalnik ne pozna: odpre (ali zapre) se isti meni kot z gumbom v vrstici.
                    if (meni != null) meni.dismiss() else showMobileMenu()
                    return si.safeer.tv.link.Daljinec.Izid(true, "Meni")
                }
                val koda = si.safeer.tv.link.Daljinec.TIPKE[ime]
                    ?: return si.safeer.tv.link.Daljinec.Izid(false, "Neznana tipka: $ime", koda = "neznana_tipka")
                val zdaj = android.os.SystemClock.uptimeMillis()
                // Odprt meni je svoje okno: tipke mora dobiti on, sicer se premika stran pod njim.
                val cilj: (KeyEvent) -> Boolean = if (meni != null) meni::dispatchKeyEvent else this::dispatchKeyEvent
                cilj(KeyEvent(zdaj, zdaj, KeyEvent.ACTION_DOWN, koda, 0))
                cilj(KeyEvent(zdaj, zdaj + 40, KeyEvent.ACTION_UP, koda, 0))
                si.safeer.tv.link.Daljinec.Izid(true, "Tipka $ime")
            }
            "scroll" -> {
                val smer = parametri.optString("direction", "down").trim().lowercase()
                val js = when (smer) {
                    "up" -> "window.scrollBy({top:-Math.round(window.innerHeight*0.8),behavior:'smooth'})"
                    "down" -> "window.scrollBy({top:Math.round(window.innerHeight*0.8),behavior:'smooth'})"
                    "top" -> "window.scrollTo({top:0,behavior:'smooth'})"
                    "bottom" -> "window.scrollTo({top:document.documentElement.scrollHeight,behavior:'smooth'})"
                    else -> return si.safeer.tv.link.Daljinec.Izid(false, "Neznana smer: $smer")
                }
                val wv = activeWebView() ?: return si.safeer.tv.link.Daljinec.Izid(false, "Ni odprtega zavihka")
                try { wv.evaluateJavascript(js, null) } catch (_: Exception) { }
                si.safeer.tv.link.Daljinec.Izid(true, "Drsenje $smer")
            }
            "screenshot" -> posnetekZaslona()
            "status" -> si.safeer.tv.link.Daljinec.Izid(true, "Stanje", si.safeer.tv.link.Daljinec.stanje(this,
                JSONObject().put("url", si.safeer.tv.PdfPregledovalnik.javniNaslov(activeUrl()))
                    .put("title", tabManager.getActiveTab()?.webView?.title ?: "")
                    .put("playing", castState)))
            else -> null
        }
    }

    /** Posnetek dejavnega zavihka: pomanjsan JPEG (najvec 640 px), da gre skozi sredisce. */
    private fun posnetekZaslona(): si.safeer.tv.link.Daljinec.Izid {
        val pogled: View = customVideoView ?: activeWebView()
            ?: return si.safeer.tv.link.Daljinec.Izid(false, "Ni odprtega zavihka")
        val sirina = pogled.width
        val visina = pogled.height
        if (sirina <= 0 || visina <= 0) return si.safeer.tv.link.Daljinec.Izid(false, "Zaslon se ni pripravljen")
        val merilo = minOf(1f, 640f / sirina)
        val slika = android.graphics.Bitmap.createBitmap(
            (sirina * merilo).toInt().coerceAtLeast(1), (visina * merilo).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.RGB_565)
        val platno = android.graphics.Canvas(slika)
        platno.scale(merilo, merilo)
        try {
            pogled.draw(platno)
        } catch (e: Throwable) {
            slika.recycle()
            return si.safeer.tv.link.Daljinec.Izid(false, "Posnetka ni bilo mogoce narediti: ${e.message}")
        }
        val izhod = java.io.ByteArrayOutputStream()
        slika.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, izhod)
        val (w, h) = Pair(slika.width, slika.height)
        slika.recycle()
        val b64 = android.util.Base64.encodeToString(izhod.toByteArray(), android.util.Base64.NO_WRAP)
        return si.safeer.tv.link.Daljinec.Izid(true, "Posnetek zaslona",
            JSONObject().put("image", "data:image/jpeg;base64," + b64).put("width", w).put("height", h))
    }

    private fun castJs(telo: String) {
        val js = "(function(){try{var m=document.querySelectorAll('video,audio');" +
            "for(var i=0;i<m.length;i++){var v=m[i];" + telo + "}}catch(e){}})()"
        runOnUiThread {
            try { activeWebView()?.evaluateJavascript(js, null) } catch (_: Exception) {}
        }
    }

    /** Naslov s telefona: odpremo ga v dejavnem zavihku in povemo, da smo ga sprejeli. */
    override fun onCastUrlReceived(url: String, title: String?, startPosition: Double) {
        runOnUiThread {
            try {
                // Ce je nad brskalnikom se celozaslonski predvajalnik (npr. YouTube), ga umaknemo,
                // sicer bi nova stran tekla pod njim in je ne bi bilo videti.
                try { if (playback.isActive()) playback.exit() } catch (_: Exception) { }
                val activeTab = tabManager.getActiveTab()
                if (activeTab != null) {
                    activeTab.webView.loadUrl(url)
                } else {
                    tabManager.createTab(this, url, true)
                }
                showTvOsd("📲 " + UiText.get(R.string.ui_link_prejeto), (title ?: url).take(60))
                if (startPosition > 0.5) {
                    // Počakamo, da se predvajalnik postavi, nato skočimo na zapomnjeno mesto.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        castJs("v.currentTime=" + startPosition + ";")
                    }, 4000)
                }
                castState = "buffering"
                castPosition = startPosition
                startCastStatusPolling()
                sendCastStatus()
            } catch (e: Exception) {
                android.util.Log.w("SafeerCast", "Naslova ni bilo mogoce odpreti: " + e.message)
            }
        }
    }

    // ---- deljenje prek Safeer Linka: besedilo, zaslon, datoteka ----

    /** Besedilo z druge naprave: pokazemo ga v oknu, ki ga daljinec zapre z enim pritiskom. */
    override fun onShareText(od: String, besedilo: String) {
        runOnUiThread {
            try {
                val jePovezava = besedilo.trim().let { it.startsWith("http://") || it.startsWith("https://") } &&
                    !besedilo.trim().contains(Regex("\\s"))
                val okno = android.app.AlertDialog.Builder(this)
                    .setTitle("💬 " + od)
                    .setMessage(besedilo.take(4000))
                    .setNegativeButton(getString(android.R.string.ok), null)
                if (jePovezava) {
                    okno.setPositiveButton(getString(R.string.ui_share_open_link)) { _, _ ->
                        onCastUrlReceived(besedilo.trim(), null, 0.0)
                    }
                }
                val prikazano = okno.show()
                // Daljinec: fokus takoj na gumbu, da ga zapre en pritisk; po pol minute se
                // umakne samo, da ne prekrije filma, ce nihce ne pritisne nicesar.
                try { prikazano.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus() } catch (_: Exception) { }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { if (prikazano.isShowing) prikazano.dismiss() } catch (_: Exception) { }
                }, 30_000L)
            } catch (e: Exception) {
                showTvOsd("💬 " + od, besedilo.take(120))
            }
        }
    }

    /** Zaslon druge naprave: odpremo stran gledalca (slika ohrani razmerje, crno ozadje). */
    override fun onShareScreenStarted(url: String, od: String) {
        runOnUiThread {
            try {
                try { if (playback.isActive()) playback.exit() } catch (_: Exception) { }
                val activeTab = tabManager.getActiveTab()
                if (activeTab != null) activeTab.webView.loadUrl(url) else tabManager.createTab(this, url, true)
                // Tuj zaslon gledamo cez cel televizor: vrstica z naslovom bi le jemala prostor.
                mobileTopBar.visibility = View.GONE
                showTvOsd("📱 " + getString(R.string.ui_share_screen_from), od)
            } catch (e: Exception) {
                android.util.Log.w("SafeerCast", "Zaslona ni bilo mogoce odpreti: " + e.message)
            }
        }
    }

    /** Deljenje se je koncalo: ce se gledamo ta zaslon, se vrnemo na zacetno stran. */
    override fun onShareScreenStopped(id: String) {
        runOnUiThread {
            try {
                val trenutni = activeUrl()
                val gledamoTega = id.isNotBlank() && trenutni.contains("/cast/screen/" + id + "/")
                if (gledamoTega) {
                    showBrowserStartPage()
                }
                showTvOsd(getString(R.string.ui_share_screen_ended))
                if (gledamoTega && deljenjeOdprloBrskalnik) {
                    // Brskalnika ni odprl uporabnik: vrnemo ga tja, kjer je bil, sicer ostane na zaslonu
                    // (tudi po izklopu in vklopu televizorja) namesto Safeer OS.
                    deljenjeOdprloBrskalnik = false
                    moveTaskToBack(true)
                }
            } catch (e: Exception) {
                android.util.Log.w("SafeerCast", "Konca deljenja ni bilo mogoce obdelati: " + e.message)
            }
        }
    }

    /** Datoteka je v mapi prenosov: povemo, kje. */
    override fun onShareFileReceived(ime: String, pot: java.io.File, od: String) {
        runOnUiThread {
            val mapa = try { PrenosiMapa.opis(this) } catch (_: Exception) { "Download" }
            showTvOsd("📁 " + getString(R.string.ui_share_file_received), ime + "  ·  " + mapa + "  ·  " + od, 6000L)
        }
    }

    /** Ukazi predvajalnika s telefona. */
    override fun onCastControl(action: String, position: Double?, volume: Double?) {
        when (action) {
            "play" -> { castJs("v.play();"); castState = "playing" }
            "pause" -> { castJs("v.pause();"); castState = "paused" }
            "stop" -> { castJs("v.pause();v.currentTime=0;"); castState = "stopped" }
            "seek" -> position?.let { castJs("v.currentTime=" + it + ";"); castPosition = it }
            "volume" -> volume?.let { castJs("v.volume=" + it + ";v.muted=false;") }
            "mute" -> castJs("v.muted=true;")
            "unmute" -> castJs("v.muted=false;")
            else -> android.util.Log.w("SafeerCast", "Neznan ukaz: " + action)
        }
        sendCastStatus()
    }

    /** Trenutno stanje predvajanja (zadnje izmerjeno; osvežuje ga startCastStatusPolling). */
    override fun getCurrentPlaybackState(): Map<String, Any?> = mapOf(
        "state" to castState,
        "current_url" to activeUrl(),
        "title" to (tabManager.getActiveTab()?.webView?.title ?: ""),
        "position" to castPosition,
        "duration" to castDuration
    )

    /** Vsakih pet sekund preberemo, kje je predvajanje, in to javimo pošiljatelju. */
    private fun startCastStatusPolling() {
        if (castPollRunning) return
        castPollRunning = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val naloga = object : Runnable {
            override fun run() {
                try {
                    val js = "(function(){var v=document.querySelector('video,audio');" +
                        "if(!v)return '';return (v.paused?'paused':'playing')+'|'+v.currentTime+'|'+(v.duration||0);})()"
                    activeWebView()?.evaluateJavascript(js) { odgovor ->
                        val ocisceno = odgovor?.trim('"') ?: ""
                        val deli = ocisceno.split("|")
                        if (deli.size == 3) {
                            castState = deli[0]
                            castPosition = deli[1].toDoubleOrNull() ?: castPosition
                            castDuration = deli[2].toDoubleOrNull() ?: castDuration
                            sendCastStatus()
                        }
                    }
                } catch (_: Exception) {}
                if (castPollRunning) handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(naloga, 5000)
    }

    private fun sendCastStatus() {
        try {
            si.safeer.tv.cast.CastReceiverService.instance?.broadcastStatus(
                castState, activeUrl(), tabManager.getActiveTab()?.webView?.title,
                castPosition, castDuration
            )
        } catch (_: Exception) {}
    }

    private fun showBrowserStartPage() {
        // V nacinu spletne aplikacije domace strani ne kazemo - uporabnik je odprl aplikacijo.
        if (nacinAplikacije != null) return
        try {
            customVideoCallback?.onCustomViewHidden()
        } catch (_: Exception) {}
        try {
            customVideoView?.let { mainRoot.removeView(it) }
        } catch (_: Exception) {}
        customVideoView = null
        customVideoCallback = null
        webViewContainer.visibility = View.VISIBLE
        mobileTopBar.visibility = View.VISIBLE
        mobileTopBar.translationY = 0f
        val home = "file:///android_asset/brave_home.html"
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null) {
            stopPageMedia("startPage")
            activeTab.webView.loadUrl(home)
        } else {
            tabManager.createTab(this, home, true)
        }
    }

    private fun initViews() {
        mainRoot = findViewById(R.id.mainRoot)
        mobileTopBar = findViewById(R.id.mobileTopBar)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)
        btnReload = findViewById(R.id.btnReload)
        btnFavorite = findViewById(R.id.btnFavorite)
        omniboxContainer = findViewById(R.id.omniboxContainer)
        tvSecurityLock = findViewById(R.id.tvSecurityLock)
        editUrl = findViewById(R.id.editUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnSearchTrigger = findViewById(R.id.btnSearchTrigger)
        btnPointerToggle = findViewById(R.id.btnPointerToggle)
        btnAddTab = findViewById(R.id.btnAddTab)
        btnTabCount = findViewById(R.id.btnTabCount)
        btnMenu = findViewById(R.id.btnMenu)
        pageProgressBar = findViewById(R.id.pageProgressBar)
        webViewContainer = findViewById(R.id.webViewContainer)
        virtualPointerView = findViewById(R.id.virtualPointerView)

        searchSuggestionsOverlay = findViewById(R.id.searchSuggestionsOverlay)
        portalChipsContainer = findViewById(R.id.portalChipsContainer)
        suggestionsListContainer = findViewById(R.id.suggestionsListContainer)

        tabSwitcherOverlay = findViewById(R.id.tabSwitcherOverlay)
        tabsGridView = findViewById(R.id.tabsGridView)
        btnNewTabInSwitcher = findViewById(R.id.btnNewTabInSwitcher)
        btnCloseTabsSwitcher = findViewById(R.id.btnCloseTabsSwitcher)
        btnCloseAllTabs = findViewById(R.id.btnCloseAllTabs)

        findInPageBar = findViewById(R.id.findInPageBar)
        editFindText = findViewById(R.id.editFindText)
        tvFindMatches = findViewById(R.id.tvFindMatches)
        btnFindPrev = findViewById(R.id.btnFindPrev)
        btnFindNext = findViewById(R.id.btnFindNext)
        btnFindClose = findViewById(R.id.btnFindClose)
    }

    private fun setupTabManager() {
        tabManager = TabManager(webViewContainer) { count, activeTab ->
            btnTabCount.text = String.format(java.util.Locale.getDefault(), "%d", count)
            if (activeTab != null) {
                activeTab.webView.isDarkMode = isDarkModeActive
                attachTabListeners(activeTab)
                chrome.updateOmniboxDisplay(activeTab.url, activeTab.webView.title)
                updateBookmarkButton(activeTab.url)
            }
        }
    }

    /** Zadnje prepusceno pojavno okno; en pritisk na daljincu sprozi vec dogodkov klika. */
    private var zadnjePojavno: Pair<String, Long> = "" to 0L

    /**
     * Stran je zahtevala novo okno. Kam pelje, se ne vemo, zato dobi zavihek v ozadju, ki ni
     * viden in v katerem se ne nalozi nic. Ko brskalnik pove prvi naslov, se zavihek pokaze
     * (prijava) ali tiho zapre (oglas).
     *
     * Okno namenoma dobi pravi pogled in ne nadomestka: brez povezave z izvorno stranjo
     * (window.opener) prijava z Google, Facebook ali X ne more vrniti odgovora in se konca
     * v praznem oknu.
     */
    private fun odpriPojavnoOkno(resultMsg: android.os.Message): Boolean {
        val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport ?: return false
        // Pogled se ni zavihek: dokler ne vemo, kam okno pelje, se stevec zavihkov ne
        // premakne in noben uporabnikov zavihek se ne umakne, da bi naredil prostor.
        val pogled = try { tabManager.pripraviPojavni(this) } catch (_: Throwable) { return false }
        val odpiralec = tabManager.getActiveTab()?.id
        var odloceno = false

        fun zavrzi(razlog: String, naslov: String) {
            android.util.Log.i("SafeerPojavno", "$razlog: ${naslov.take(90)}")
            // Pogleda ne unicujemo znotraj njegovega lastnega povratnega klica - Chromium
            // je takrat se sredi obdelave navigacije. Pospravimo ga v naslednjem obhodu.
            pogled.post { tabManager.zavrziPojavni(pogled) }
        }

        pogled.vratarPojavnega = vratar@{ naslov ->
            if (odloceno) return@vratar false
            odloceno = true
            val zdaj = android.os.SystemClock.elapsedRealtime()
            val podvojeno = naslov == zadnjePojavno.first && zdaj - zadnjePojavno.second < 2_000L
            when {
                podvojeno -> {
                    zavrzi("Podvojeno okno zaprto", naslov)
                    false
                }
                !PrijavnaOkna.jePrijava(naslov) -> {
                    zavrzi("Pojavno okno preprečeno", naslov)
                    false
                }
                else -> {
                    zadnjePojavno = naslov to zdaj
                    // Prazna lupina je ze prikazala svojo stran; tak pogled ostane prazen,
                    // ce ga preselimo med zavihke, zato ga zavrzemo in naslov odpremo v
                    // novem zavihku. Okno, ki se ni nicesar prikazalo, pa sprejmemo takega,
                    // kot je - tako ostane povezava z izvorno stranjo (window.opener).
                    val lupina = (pogled.url ?: "") == "about:blank"
                    if (lupina) {
                        android.util.Log.i("SafeerPojavno", "Prijava v novem zavihku: ${naslov.take(90)}")
                        pogled.post {
                            tabManager.zavrziPojavni(pogled)
                            try {
                                // Tudi ta zavihek je pojavno okno: Nazaj ga zapre in vrne na
                                // stran, ki ga je odprla, ne pa hodi po njegovi zgodovini.
                                val nov = tabManager.createTab(this, naslov, true)
                                nov.jePojavni = true
                                nov.odpiralec = odpiralec
                            } catch (_: Throwable) {}
                        }
                        false
                    } else {
                        pogled.post {
                            try {
                                tabManager.posvoji(pogled, naslov, odpiralec)
                                android.util.Log.i("SafeerPojavno", "Prijavno okno odprto: ${naslov.take(90)}")
                            } catch (e: Throwable) {
                                android.util.Log.w("SafeerPojavno", "Zavihka ni bilo mogoce odpreti: ${e.message}")
                                tabManager.zavrziPojavni(pogled)
                            }
                        }
                        true
                    }
                }
            }
        }

        transport.webView = pogled
        resultMsg.sendToTarget()

        // Prazna lupina, ki nikamor ne odnavigira, se po tiho pospravi.
        pogled.postDelayed({
            if (!odloceno) {
                odloceno = true
                pogled.vratarPojavnega = null
                tabManager.zavrziPojavni(pogled)
            }
        }, 15_000L)
        return true
    }

    private fun attachTabListeners(tab: TabModel) {
        val wv = tab.webView
        try {
            wv.setOnScrollChanged { direction, scrollY -> handlePageScroll(direction, scrollY) }
            wv.setOnChromeHidden { hidden ->
                chrome.setChromeHidden(hidden)
            }
        } catch (_: Exception) {}

        wv.onProgressUpdate = { progress ->
            if (tabManager.getActiveTab()?.id == tab.id) {
                if (progress < 100) {
                    pageProgressBar.visibility = View.VISIBLE
                    pageProgressBar.progress = progress
                } else {
                    pageProgressBar.visibility = View.GONE
                }
            }
        }

        wv.onUrlChanged = { newUrl ->
            tab.url = newUrl
            if (tabManager.getActiveTab()?.id == tab.id) {
                chrome.updateOmniboxDisplay(newUrl, wv.title)
                chrome.applyUrlChrome(newUrl)
                updateBookmarkButton(newUrl)
            }
        }

        wv.onPageLoaded = { finalUrl, pageTitle ->
            tab.url = finalUrl
            tab.title = pageTitle
            if (tabManager.getActiveTab()?.id == tab.id) {
                updateBookmarkButton(finalUrl)
            }
            if (finalUrl.isNotEmpty() && !finalUrl.startsWith("about:", ignoreCase = true)) {
                val cleanTitle = if (pageTitle.isNotEmpty()) pageTitle else finalUrl
                repository.addHistory(cleanTitle, finalUrl)
            }
        }

        wv.onTitleChanged = { title ->
            tab.title = title
            if (tabManager.getActiveTab()?.id == tab.id) {
                chrome.updateOmniboxDisplay(tab.url, title)
            }
        }

        wv.onSecurityChanged = { isSecure ->
            if (tabManager.getActiveTab()?.id == tab.id) {
                val url = tab.url
                if (url.startsWith("file://") || url.startsWith("about:") || url.isEmpty()) {
                    tvSecurityLock.text = "S"
                    tvSecurityLock.setTextColor(Color.parseColor("#10B981"))
                } else {
                    tvSecurityLock.text = if (isSecure) "🔒" else "⚠️"
                    tvSecurityLock.setTextColor(
                        if (isSecure) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
                    )
                }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // PDF se ne prenese, ampak odpre v vgrajenem pregledovalniku.
            if (PdfPregledovalnik.jePdf(url, mimeType, contentDisposition)) {
                PdfPregledovalnik.odpri(this, wv, url, userAgent, contentDisposition, mimeType)
                return@setDownloadListener
            }
            // Manifest pretoka ni datoteka za prenos - sam po sebi je neuporaben, brskalnik
            // pa ga predvaja. Preverimo naslov in ne stanja predvajalnika: prenos se javi
            // prej, kot se predvajalnik zazene.
            if (DashPrevzem.jeManifest(url)) {
                return@setDownloadListener
            }
            downloadHandler.startDownload(url, userAgent, contentDisposition, mimeType)
        }
        // Pregledovalnik PDF: "prenesi izvirnik" gre kot navaden prenos, "gor" iz orodne vrstice v naslovno vrstico.
        wv.onPdfPrenos = { url, userAgent -> downloadHandler.startDownload(url, userAgent, null, "application/pdf") }
        wv.naPojavnoOkno = { resultMsg -> odpriPojavnoOkno(resultMsg) }
        wv.onPdfFokusVen = { smer ->
            if (smer == "gor") {
                if (nacinAplikacije == null) {
                    mobileTopBar.visibility = android.view.View.VISIBLE
                    mobileTopBar.animate().translationY(0f).setDuration(150).start()
                }
                editUrl.requestFocus()
            }
        }

        wv.onFullscreenToggled = { customView, callback ->
            val mode = SiteProfileResolver.fromUrl(tab.url.ifEmpty { wv.url ?: "" }).playbackMode()
            if (mode == PlaybackMode.InPlaceWebView || mode == PlaybackMode.ExoPlayer) {
                SafeerDbg.log(
                    "H320",
                    "MainActivity.kt:fs",
                    "skip android custom-view",
                    JSONObject().put("show", customView != null).put("mode", mode.name)
                )
            } else if (customView != null) {
                playback.enter(customView, callback)
            } else {
                playback.exit()
            }
        }
    }

    private fun setupOmnibox() {
        editUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                omniboxContainer.setBackgroundResource(R.drawable.bg_tab_card_active)
                searchSuggestionsOverlay.visibility = View.VISIBLE
                mobileTopBar.animate().translationY(0f).setDuration(150).start()
                val currentUrl = tabManager.getActiveTab()?.url ?: ""
                if (currentUrl.startsWith("https://www.google.com") || currentUrl.startsWith("file:///android_asset") || currentUrl == "about:blank") {
                    editUrl.setText("")
                } else {
                    editUrl.setText(currentUrl)
                    editUrl.selectAll()
                }
                btnClearUrl.visibility = if (editUrl.text.isNotEmpty()) View.VISIBLE else View.GONE
                // Ob samem prihodu fokusa ne posljemo nicesar: v polju je naslov odprte
                // strani, ta pa ni iskalni niz in ne sodi v Googlovo storitev za predloge.
                suggestionsListContainer.removeAllViews()
            } else {
                omniboxContainer.setBackgroundResource(R.drawable.bg_mobile_omnibox)
                btnClearUrl.visibility = View.GONE
                searchSuggestionsOverlay.visibility = View.GONE
                val activeTab = tabManager.getActiveTab()
                chrome.updateOmniboxDisplay(activeTab?.url ?: "", activeTab?.webView?.title)
            }
        }

        editUrl.setOnClickListener {
            showKeyboard()
        }

        omniboxContainer.setOnClickListener {
            editUrl.requestFocus()
            showKeyboard()
        }

        editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editUrl.hasFocus()) {
                    btnClearUrl.visibility = if (!s.isNullOrEmpty()) View.VISIBLE else View.GONE
                    fetchGoogleSuggestions(s?.toString() ?: "")
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                performNavigation(editUrl.text.toString().trim())
                hideKeyboard()
                editUrl.clearFocus()
                searchSuggestionsOverlay.visibility = View.GONE
                tabManager.getActiveTab()?.webView?.requestFocus()
                true
            } else {
                false
            }
        }

        editUrl.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    hideKeyboard()
                    if (portalChipsContainer.childCount > 0) {
                        portalChipsContainer.getChildAt(0).requestFocus()
                        return@setOnKeyListener true
                    } else if (suggestionsListContainer.childCount > 0) {
                        suggestionsListContainer.getChildAt(0).requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        btnClearUrl.setOnClickListener {
            editUrl.setText("")
            editUrl.requestFocus()
            showKeyboard()
            suggestionsListContainer.removeAllViews()
        }

        btnSearchTrigger.setOnClickListener {
            val text = editUrl.text.toString().trim()
            if (text.isNotEmpty()) {
                performNavigation(text)
                hideKeyboard()
                editUrl.clearFocus()
                searchSuggestionsOverlay.visibility = View.GONE
                tabManager.getActiveTab()?.webView?.requestFocus()
            } else {
                editUrl.requestFocus()
                showKeyboard()
            }
        }
    }

    private fun setupSearchSuggestions() {
        chrome.renderPortals()
    }

    internal fun closeSuggestionsAndFocusWeb() {
        hideKeyboard()
        editUrl.clearFocus()
        searchSuggestionsOverlay.visibility = View.GONE
        tabManager.getActiveTab()?.webView?.requestFocus()
    }

    private val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null

    /** Ali je besedilo videti kot naslov strani in ne kot iskanje. */
    private fun jeNaslovStrani(besedilo: String): Boolean {
        val t = besedilo.trim().lowercase()
        if (t.isEmpty()) return false
        if (t.contains("://") || t.startsWith("file:") || t.startsWith("about:") ||
            t.startsWith("safeer:") || t.startsWith("data:") || t.startsWith("content:")
        ) {
            return true
        }
        if (t.contains(" ")) return false
        return t.contains(".") && !t.endsWith(".")
    }

    private fun fetchGoogleSuggestions(query: String) {
        val trimmed = query.trim()
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        // Naslovov strani ne posiljamo nikomur; predlogi so za iskanje, ne za brskanje.
        if (trimmed.length < 2 || jeNaslovStrani(trimmed)) {
            runOnUiThread { suggestionsListContainer.removeAllViews() }
            return
        }
        val runTask = Runnable {
            Thread {
                try {
                    val encoded = URLEncoder.encode(trimmed, "UTF-8")
                    val url = java.net.URL("https://suggestqueries.google.com/complete/search?client=chrome&q=$encoded")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.setRequestProperty("User-Agent", ChromiumEngineView.CHROME_ANDROID_USER_AGENT)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Accept-Language", "sl-SI,sl;q=0.9,en-US;q=0.8,en;q=0.7")
                    conn.setRequestProperty("Referer", "https://www.google.com/")
                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val jsonArr = org.json.JSONArray(responseText)
                        if (jsonArr.length() > 1) {
                            val suggestionsArr = jsonArr.getJSONArray(1)
                            val list = mutableListOf<String>()
                            for (i in 0 until minOf(suggestionsArr.length(), 5)) {
                                list.add(suggestionsArr.getString(i))
                            }
                            displaySuggestions(list)
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        }
        suggestionRunnable = runTask
        suggestionHandler.postDelayed(runTask, 300)
    }

    private fun displaySuggestions(list: List<String>) {
        runOnUiThread {
            suggestionsListContainer.removeAllViews()
            if (list.isEmpty()) return@runOnUiThread

            for (item in list) {
                val tv = TextView(this).apply {
                    text = context.getString(R.string.fmt_ikona_besedilo_2, "🔍", item)
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 14f
                    setBackgroundResource(R.drawable.bg_mobile_omnibox)
                    setPadding(28, 16, 28, 16)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 4, 0, 4)
                    layoutParams = lp

                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            setBackgroundResource(R.drawable.bg_tab_card_active)
                        } else {
                            setBackgroundResource(R.drawable.bg_mobile_omnibox)
                        }
                    }

                    setOnClickListener {
                        performNavigation(item)
                        closeSuggestionsAndFocusWeb()
                    }
                }
                suggestionsListContainer.addView(tv)
            }
        }
    }

    internal fun performNavigation(input: String) {
        var cleanInput = input.trim()
        if (cleanInput.isEmpty()) return

        if (cleanInput.startsWith("file:///android_asset/brave_home.html", ignoreCase = true)) {
            cleanInput = cleanInput.removePrefix("file:///android_asset/brave_home.html").trim()
            if (cleanInput.isEmpty()) return
        }

        val isUrl = cleanInput.startsWith("http://", ignoreCase = true) ||
            cleanInput.startsWith("https://", ignoreCase = true) ||
            cleanInput.startsWith("file://", ignoreCase = true) ||
            (cleanInput.contains(".") && !cleanInput.contains(" "))
        if (!isUrl) {
            val profile = SiteProfileResolver.fromUrl(activeUrl())
            if (profile.handleSearch(cleanInput, this)) return
        }

        val finalUrl = when {
            cleanInput.startsWith("http://", ignoreCase = true) ||
                cleanInput.startsWith("https://", ignoreCase = true) ||
                cleanInput.startsWith("file://", ignoreCase = true) -> cleanInput
            cleanInput.contains(".") && !cleanInput.contains(" ") -> "https://$cleanInput"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(cleanInput, "UTF-8")
        }

        val activeTab = tabManager.getActiveTab()
        if (activeTab != null) {
            activeTab.webView.loadUrl(finalUrl)
        } else {
            tabManager.createTab(this, finalUrl, true)
        }
    }

    private fun setupTopButtons() {
        btnBack.setOnClickListener {
            onBackPressed()
        }

        btnHome.setOnClickListener {
            stopPageMedia("btnHome")
            tabManager.getActiveTab()?.webView?.loadUrl("file:///android_asset/brave_home.html")
        }

        btnReload.setOnClickListener {
            val activeTab = tabManager.getActiveTab()
            activeTab?.webView?.reload()
        }

        btnFavorite.setOnClickListener {
            toggleBookmarkCurrentPage()
        }

        btnFavorite.setOnLongClickListener {
            showBookmarksDialog()
            true
        }

        btnPointerToggle.setOnClickListener {
            virtualPointerView.isPointerVisible = !virtualPointerView.isPointerVisible
            Toast.makeText(
                this,
                if (virtualPointerView.isPointerVisible) UiText.get(R.string.ui_pointer_on) else UiText.get(R.string.ui_pointer_off),
                Toast.LENGTH_SHORT
            ).show()
        }

        btnAddTab.setOnClickListener {
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
        }

        btnTabCount.setOnClickListener {
            toggleTabSwitcher()
        }

        btnMenu.setOnClickListener {
            showMobileMenu()
        }

        // Tab switcher buttons
        btnNewTabInSwitcher.setOnClickListener {
            tabSwitcherOverlay.visibility = View.GONE
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
        }

        btnCloseTabsSwitcher.setOnClickListener {
            tabSwitcherOverlay.visibility = View.GONE
        }

        btnCloseAllTabs.setOnClickListener {
            tabManager.closeAllTabs(this)
            tabSwitcherOverlay.visibility = View.GONE
        }
    }

    internal fun toggleBookmarkCurrentPage() {
        val activeTab = tabManager.getActiveTab() ?: return
        val curUrl = activeTab.url
        if (curUrl.isEmpty() || curUrl == "about:blank" || curUrl.startsWith("file:///android_asset")) {
            Toast.makeText(this, UiText.get(R.string.ui_cannot_bookmark), Toast.LENGTH_SHORT).show()
            return
        }
        val isBm = repository.isBookmarked(curUrl)
        if (isBm) {
            repository.removeBookmark(curUrl)
            updateBookmarkButton(curUrl)
            showTvOsd(UiText.get(R.string.ui_bookmarks), getString(R.string.toast_bookmark_removed))
            Toast.makeText(this, getString(R.string.toast_bookmark_removed), Toast.LENGTH_SHORT).show()
        } else {
            val title = activeTab.webView.title?.takeIf { it.isNotBlank() } ?: curUrl
            repository.addBookmark(title, curUrl)
            updateBookmarkButton(curUrl)
            showTvOsd(UiText.get(R.string.ui_bookmarks), "⭐ $title")
            Toast.makeText(this, getString(R.string.toast_bookmark_added), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun updateBookmarkButton(url: String) {
        if (!::btnFavorite.isInitialized) return
        if (url.isEmpty() || url == "about:blank" || url.startsWith("file:///android_asset")) {
            btnFavorite.text = "☆"
            btnFavorite.setTextColor(getColor(R.color.text_primary))
            return
        }
        val isBm = repository.isBookmarked(url)
        if (isBm) {
            btnFavorite.text = "⭐"
            btnFavorite.setTextColor(Color.parseColor("#FBBF24"))
        } else {
            btnFavorite.text = "☆"
            btnFavorite.setTextColor(getColor(R.color.text_primary))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 80

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX > 0) {
                        tabManager.switchToPrevTab()
                        Toast.makeText(this@MainActivity, UiText.get(R.string.ui_previous_tab), Toast.LENGTH_SHORT).show()
                    } else {
                        tabManager.switchToNextTab()
                        Toast.makeText(this@MainActivity, UiText.get(R.string.ui_next_tab), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        })

        omniboxContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun toggleTabSwitcher() {
        if (tabSwitcherOverlay.visibility == View.VISIBLE) {
            tabSwitcherOverlay.visibility = View.GONE
        } else {
            renderTabsGrid()
            tabSwitcherOverlay.visibility = View.VISIBLE
            btnNewTabInSwitcher.requestFocus()
        }
    }

    private fun renderTabsGrid() {
        val allTabs = tabManager.getAllTabs()
        val activeId = tabManager.getActiveTab()?.id

        tabsGridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = allTabs.size
            override fun getItem(position: Int): Any = allTabs[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tab = allTabs[position]
                val view = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_tab_card, parent, false)

                val tvTitle = view.findViewById<TextView>(R.id.tvTabTitle)
                val tvUrl = view.findViewById<TextView>(R.id.tvTabUrl)
                val tvActiveBadge = view.findViewById<TextView>(R.id.tvActiveBadge)
                val btnClose = view.findViewById<TextView>(R.id.btnTabClose)
                val cardRoot = view.findViewById<RelativeLayout>(R.id.tabCardRoot)

                tvTitle.text = tab.title.ifEmpty { UiText.get(R.string.ui_tab_number , position + 1) }
                tvUrl.text = tab.url
                
                val isActive = (tab.id == activeId)
                cardRoot.setBackgroundResource(if (isActive) R.drawable.bg_tab_card_active else R.drawable.bg_tab_card)
                tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE

                view.setOnClickListener {
                    tabManager.switchTab(tab.id)
                    tabSwitcherOverlay.visibility = View.GONE
                }

                btnClose.setOnClickListener {
                    tabManager.closeTab(this@MainActivity, tab.id)
                    renderTabsGrid()
                }

                return view
            }
        }
    }

    // ------------------------------------------------------------------
    // Safeer Link — stanje, seznanitev in sinhronizacija na televizorju
    // ------------------------------------------------------------------

    private var linkOkno: Dialog? = null

    // ---- Safeer Link: koda za seznanitev, ko stran Linka ni odprta ----
    private var kodaOkno: android.app.AlertDialog? = null

    /**
     * Druga naprava se zeli povezati na Hub tega televizorja: kodo pokazemo takoj, cez karkoli
     * gleda uporabnik. Ce je odprta stran Safeer Linka, kodo pokaze ona.
     */
    private fun pokaziKodoZaSeznanitev() {
        try {
            if (linkOkno != null) {
                kodaOkno?.let { if (it.isShowing) it.dismiss() }
                kodaOkno = null
                return
            }
            val p = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik?.cakajocePrijave()?.lastOrNull()
            if (p == null) {
                kodaOkno?.let { if (it.isShowing) it.dismiss() }
                kodaOkno = null
                return
            }
            val koda = p.pin.map { it.toString() }.joinToString(" ")
            val navodilo = UiText.get(R.string.ui_link_pair_code_body).replace("%1\$s", p.ime)
            val obstojece = kodaOkno
            if (obstojece != null && obstojece.isShowing && obstojece.window?.decorView?.tag == p.pairId) return
            obstojece?.let { if (it.isShowing) it.dismiss() }

            val vsebina = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(64, 32, 64, 16)
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = navodilo
                    textSize = 20f
                })
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = koda
                    textSize = 44f
                    setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 32, 0, 16)
                })
            }
            val okno = android.app.AlertDialog.Builder(this)
                .setTitle(UiText.get(R.string.ui_link_pair_code_title))
                .setView(vsebina)
                .setNegativeButton(UiText.get(R.string.ui_link_pair_reject)) { _, _ ->
                    try { si.safeer.tv.cast.HubKrmilnik.usmerjevalnik?.zavrniPrijavo(p.pairId) } catch (_: Exception) { }
                }
                .setPositiveButton(getString(android.R.string.ok), null)
                .create()
            okno.setOnDismissListener { if (kodaOkno === okno) kodaOkno = null }
            kodaOkno = okno
            okno.show()
            try { okno.window?.decorView?.tag = p.pairId } catch (_: Exception) { }
            try { okno.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus() } catch (_: Exception) { }
        } catch (e: Exception) {
            android.util.Log.w("SafeerLink", "Kode za seznanitev ni bilo mogoce pokazati: " + e.message)
        }
    }

    /** Odpre naslov tako, kot ga odpira brskalnik sam: v dejavnem zavihku, sicer v novem. */
    private fun odpriVZavihku(naslov: String) {
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null) {
            activeTab.webView.loadUrl(naslov)
        } else {
            tabManager.createTab(this, naslov, true)
        }
    }

    /**
     * Sistemsko vprasanje za zajem zaslona (MediaProjection); odgovor pride v onActivityResult.
     * Od Androida 14 vprasamo samo za cel zaslon: eno vprasanje, brez izbire med aplikacijami.
     */
    private fun zahtevajZajemZaslona(cilj: String, ime: String) {
        linkZaslonCilj = cilj
        linkZaslonIme = ime
        try {
            val upravitelj = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val namera = try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val razredNastavitve = Class.forName("android.media.projection.MediaProjectionConfig")
                    val nastavitev = razredNastavitve.getMethod("createConfigForDefaultDisplay").invoke(null)
                    upravitelj.javaClass.getMethod("createScreenCaptureIntent", razredNastavitve)
                        .invoke(upravitelj, nastavitev) as android.content.Intent
                } else upravitelj.createScreenCaptureIntent()
            } catch (_: Throwable) {
                upravitelj.createScreenCaptureIntent()
            }
            @Suppress("DEPRECATION")
            startActivityForResult(namera, ZAHTEVA_ZAJEM_ZASLONA)
        } catch (e: Exception) {
            linkZaslonCilj = ""
            linkZaslonIme = ""
            linkMost?.zajemZavrnjen(cilj)
        }
    }

    @Deprecated("Activity.onActivityResult - dejavnost ni ComponentActivity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ZAHTEVA_ZAJEM_ZASLONA) return
        val cilj = linkZaslonCilj
        val ime = linkZaslonIme
        linkZaslonCilj = ""
        linkZaslonIme = ""
        if (resultCode == android.app.Activity.RESULT_OK && data != null && cilj.isNotBlank()) {
            val most = linkMost
            if (most != null) most.zajemDovoljen(resultCode, data, cilj, ime)
            else si.safeer.tv.link.LinkMost.zazeniDeljenje(this, resultCode, data, cilj, ime)
        } else if (cilj.isNotBlank()) {
            linkMost?.zajemZavrnjen(cilj)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Stran Linka je vprasala za dovoljenje za medije; naj se izrise s pravim stanjem.
        linkMost?.naDovoljenje(requestCode)
    }

    /**
     * Odpre Safeer Link v svojem pogledu.
     *
     * Pogled je locen od zavihkov in nalozi samo stran iz aplikacije, zato njegov most
     * ni dosegljiv nobeni spletni strani. Ta pogled tudi ne sme nikamor navigirati.
     */
    // JavaScript potrebuje sama stran Safeer Linka iz aplikacije; pogled ne nalozi nicesar drugega.
    @Suppress("SetJavaScriptEnabled")
    private fun odpriSafeerLink() {
        try {
            val pogled = android.webkit.WebView(this)
            pogled.settings.javaScriptEnabled = true
            pogled.settings.domStorageEnabled = true
            pogled.settings.allowFileAccess = false
            pogled.settings.allowContentAccess = false
            pogled.settings.setSupportMultipleWindows(false)
            pogled.settings.javaScriptCanOpenWindowsAutomatically = false
            pogled.setBackgroundColor(android.graphics.Color.parseColor("#0b1017"))
            // Na televizorju mora fokus prevzeti stran, sicer daljinec nima kam.
            pogled.isFocusable = true
            pogled.isFocusableInTouchMode = true

            val okno = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val izOs = linkIzOs
            linkIzOs = false
            linkOdprlZavihek = false
            if (izOs) {
                // Odprl ga je Safeer OS: stran lezi na ozadju Safeer OS in je videti kot del sistema,
                // ne kot okno brskalnika. Lastne fotografije Safeer OS brskalnik ne vidi (je v drugi
                // aplikaciji), zato takrat vzamemo privzeto ozadje.
                pogled.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val izbira = if (izSafeerOs == packageName) si.safeer.tv.os.Ozadje.izbrana(this)
                    else si.safeer.tv.os.Ozadje.VSE.firstOrNull {
                        it.oznaka == linkOsOzadje && it.oznaka != si.safeer.tv.os.Ozadje.LASTNA
                    } ?: si.safeer.tv.os.Ozadje.VSE[0]
                val podlaga = android.widget.FrameLayout(this)
                podlaga.background = si.safeer.tv.os.Ozadje.sestavi(this, izbira, linkOsZatemnitev)
                podlaga.addView(pogled, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                okno.setContentView(podlaga)
            } else {
                okno.setContentView(pogled)
            }

            // Naslov strani preberemo tu, na glavni niti: most ga bo vprasal z druge,
            // kjer WebView svojih metod ne da brati.
            val zavihek = tabManager.getActiveTab()
            // Odprt PDF ima notranji naslov pregledovalnika; drugim napravam posljemo izvornega.
            val naslovStrani = PdfPregledovalnik.javniNaslov(zavihek?.webView?.url ?: zavihek?.url ?: "")
            val imeStrani = zavihek?.webView?.title

            val most = si.safeer.tv.link.LinkMost(
                this,
                pogled,
                { Pair(naslovStrani, imeStrani) },
                { okno.dismiss() },
                { naslov -> linkOdprlZavihek = true; odpriVZavihku(naslov) },
                { cilj, ime -> zahtevajZajemZaslona(cilj, ime) }
            )
            linkMost = most
            pogled.addJavascriptInterface(most, "SafeerLink")

            // onRenderProcessGone je spodaj; Lint ga v anonimnem razredu Kotlina ne najde.
            pogled.webViewClient = @Suppress("MissingOnRenderProcessGone") object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val naslov = request?.url?.toString() ?: return true
                    if (naslov.startsWith("file:///android_asset/link/")) return false
                    okno.dismiss()
                    if (naslov.startsWith("http://") || naslov.startsWith("https://")) {
                        linkOdprlZavihek = true
                        odpriVZavihku(naslov)
                    }
                    return true
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.requestFocus()
                }

                // Ce sistem ubije izrisovalnik (malo pomnilnika) in tu vrnemo false, Android zapre
                // ves brskalnik. Zapremo samo okno Safeer Link; pogled unici poslusalec ob zaprtju.
                override fun onRenderProcessGone(
                    view: android.webkit.WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    android.util.Log.w("SafeerLink", "Izrisovalnik okna Safeer Link je koncal; zapiram okno.")
                    webViewContainer.post { try { okno.dismiss() } catch (_: Exception) {} }
                    return true
                }
            }

            okno.setOnDismissListener {
                try { most.pospravi() } catch (_: Exception) {}
                try { pogled.destroy() } catch (_: Exception) {}
                linkOkno = null
                if (izOs && !linkOdprlZavihek) vrniVOsPoLinku()
            }

            pogled.loadUrl("file:///android_asset/link/index.html" + if (izOs) "?os=1" else "")
            linkOkno = okno
            okno.show()
        } catch (e: Exception) {
            Toast.makeText(this, UiText.get(R.string.ui_link_open_failed), Toast.LENGTH_SHORT).show()
            android.util.Log.w("SafeerLink", "Zaslon se ni odprl: " + e.message)
        }
    }

    private fun showMobileMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_mobile_menu)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.BOTTOM)

        val activeTab = tabManager.getActiveTab()
        val wv = activeTab?.webView

        val menuBtnBack = dialog.findViewById<ImageButton>(R.id.menuBtnBack)
        val menuBtnForward = dialog.findViewById<ImageButton>(R.id.menuBtnForward)
        val menuBtnReload = dialog.findViewById<ImageButton>(R.id.menuBtnReload)
        val menuBtnStar = dialog.findViewById<ImageButton>(R.id.menuBtnStar)

        menuBtnBack.setOnClickListener {
            if (wv?.canGoBack() == true) wv.goBack()
            dialog.dismiss()
        }

        menuBtnForward.setOnClickListener {
            if (wv?.canGoForward() == true) wv.goForward()
            dialog.dismiss()
        }

        menuBtnReload.setOnClickListener {
            wv?.reload()
            dialog.dismiss()
        }

        val curUrl = activeTab?.url ?: ""
        val isBm = repository.isBookmarked(curUrl)
        menuBtnStar.setImageResource(if (isBm) R.drawable.ic_m_star_filled else R.drawable.ic_m_star)
        menuBtnStar.setOnClickListener {
            if (isBm) {
                repository.removeBookmark(curUrl)
                updateBookmarkButton(curUrl)
                Toast.makeText(this, getString(R.string.toast_bookmark_removed), Toast.LENGTH_SHORT).show()
            } else {
                repository.addBookmark(wv?.title ?: UiText.get(R.string.ui_bookmark), curUrl)
                updateBookmarkButton(curUrl)
                Toast.makeText(this, getString(R.string.toast_bookmark_added), Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuNewTab).setOnClickListener {
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
            dialog.dismiss()
            editUrl.requestFocus()
            showKeyboard()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuSafeerLink).setOnClickListener {
            dialog.dismiss()
            odpriSafeerLink()
        }

        // ⭐ Trenutna stran med aplikacije Safeer OS: na domacem zaslonu dobi svojo plosccico z
        // imenom in ikono iz manifesta, odpre pa se cez ves zaslon, brez vrstice brskalnika.
        val rowDodajAplikacijo = dialog.findViewById<LinearLayout>(R.id.rowMenuDodajAplikacijo)
        val txtDodajAplikacijo = dialog.findViewById<android.widget.TextView>(R.id.txtMenuDodajAplikacijo)
        val naslovZaAplikacijo = activeUrl()
        val zeDodana = naslovZaAplikacijo.isNotBlank() && si.safeer.tv.os.SpletneAplikacije.jeDodana(this, naslovZaAplikacijo)
        if (zeDodana) txtDodajAplikacijo.text = UiText.get(R.string.fmt_ikona_besedilo, UiText.get(R.string.menu_dodaj_aplikacijo), "✓")
        rowDodajAplikacijo.setOnClickListener {
            dialog.dismiss()
            dodajTrenutnoStranMedAplikacije()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuBookmarks).setOnClickListener {
            dialog.dismiss()
            showBookmarksDialog()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuDownloads).setOnClickListener {
            dialog.dismiss()
            showDownloadsDialog()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuHistory).setOnClickListener {
            dialog.dismiss()
            showHistoryDialog()
        }

        val cbDesktop = dialog.findViewById<CheckBox>(R.id.cbDesktopSite)
        cbDesktop.isChecked = activeTab?.isDesktop ?: false
        dialog.findViewById<LinearLayout>(R.id.rowMenuDesktopSite).setOnClickListener {
            val nextState = !cbDesktop.isChecked
            cbDesktop.isChecked = nextState
            activeTab?.isDesktop = nextState
            wv?.isDesktopMode = nextState
            wv?.reload()
            dialog.dismiss()
        }

        // Stanje scita: zeleno, s stevilom groženj, ki jih je ta zagon ze ustavil.
        val ustavljenih = ThreatBlockEngine.totalBlockedThreats.get()
        dialog.findViewById<TextView>(R.id.tvThreatCountBadge).text =
            if (ustavljenih > 0) UiText.get(R.string.ui_shield_active_count, ustavljenih) else UiText.get(R.string.ui_shield_active)
        // Threat Shield Status Dialog
        dialog.findViewById<LinearLayout>(R.id.rowMenuThreatStats).setOnClickListener {
            dialog.dismiss()
            showThreatStatsDialog()
        }

        val cbAdBlock = dialog.findViewById<CheckBox>(R.id.cbAdBlock)
        cbAdBlock.isChecked = AdBlockEngine.isEnabled
        dialog.findViewById<LinearLayout>(R.id.rowMenuAdBlock).setOnClickListener {
            AdBlockEngine.isEnabled = !AdBlockEngine.isEnabled
            cbAdBlock.isChecked = AdBlockEngine.isEnabled
            Toast.makeText(
                this,
                if (AdBlockEngine.isEnabled) UiText.get(R.string.ui_adblock_on) else UiText.get(R.string.ui_adblock_off),
                Toast.LENGTH_SHORT
            ).show()
            wv?.reload()
            dialog.dismiss()
        }

        // Ozadje domacega zaslona televizorja. Philips te nastavitve nima nikjer v sistemu,
        // zato jo ponudimo tu - in jo znamo tudi vrniti, kakrsna je bila.
        val txtJezik = dialog.findViewById<android.widget.TextView>(R.id.txtJezik)
        txtJezik.text = JezikVmesnika.imeIzbire(this)
        dialog.findViewById<LinearLayout>(R.id.rowMenuJezik).setOnClickListener {
            val oznake = listOf(JezikVmesnika.SAMODEJNO) + JezikVmesnika.JEZIKI.map { it.first }
            val imena = (listOf(UiText.get(R.string.ui_lang_auto).ifBlank { "Automatic" }) +
                JezikVmesnika.JEZIKI.map { it.second }).toTypedArray()
            val trenutni = oznake.indexOf(JezikVmesnika.izbrani(this))
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_language))
                .setSingleChoiceItems(imena, trenutni) { d, izbrano ->
                    d.dismiss()
                    dialog.dismiss()
                    if (oznake[izbrano] != JezikVmesnika.izbrani(this)) {
                        JezikVmesnika.nastavi(this, oznake[izbrano])
                        recreate()
                    }
                }
                .show()
        }

        val cbTvOzadje = dialog.findViewById<CheckBox>(R.id.cbTvOzadje)
        cbTvOzadje.isChecked = TvOzadje.jeCrno(this)
        dialog.findViewById<LinearLayout>(R.id.rowMenuTvOzadje).setOnClickListener {
            if (!TvOzadje.podprto(this)) {
                Toast.makeText(this, UiText.get(R.string.ui_tv_wallpaper_failed), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val crno = !TvOzadje.jeCrno(this)
            val uspelo = if (crno) TvOzadje.vklopiCrno(this) else TvOzadje.povrni(this)
            cbTvOzadje.isChecked = TvOzadje.jeCrno(this)
            val sporocilo = when {
                !uspelo -> UiText.get(R.string.ui_tv_wallpaper_failed)
                crno -> UiText.get(R.string.ui_tv_wallpaper_black)
                else -> UiText.get(R.string.ui_tv_wallpaper_restored)
            }
            Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        val cbSponsorBlock = dialog.findViewById<CheckBox>(R.id.cbSponsorBlock)
        cbSponsorBlock.isChecked = SponsorBlockSettings.isEnabled(this)
        dialog.findViewById<LinearLayout>(R.id.rowMenuSponsorBlock).setOnClickListener {
            val enabled = !SponsorBlockSettings.isEnabled(this)
            SponsorBlockSettings.setEnabled(this, enabled)
            cbSponsorBlock.isChecked = enabled
            Toast.makeText(
                this,
                if (enabled) UiText.get(R.string.ui_sponsorblock_on) else UiText.get(R.string.ui_sponsorblock_off),
                Toast.LENGTH_SHORT
            ).show()
            wv?.reload()
            dialog.dismiss()
        }

        // 🚫 Preprecevanje pojavnih oken. Privzeto vklopljeno; en klik na predvajalnik naj
        // predvaja film, ne odpira zavihka z oglasom.
        val cbPopupBlock = dialog.findViewById<CheckBox>(R.id.cbPopupBlock)
        cbPopupBlock.isChecked = PojavnaOknaNastavitve.jeVklopljeno(this)
        dialog.findViewById<LinearLayout>(R.id.rowMenuPopupBlock).setOnClickListener {
            val vklopljeno = !PojavnaOknaNastavitve.jeVklopljeno(this)
            PojavnaOknaNastavitve.nastavi(this, vklopljeno)
            cbPopupBlock.isChecked = vklopljeno
            Toast.makeText(
                this,
                if (vklopljeno) UiText.get(R.string.ui_popup_block_on) else UiText.get(R.string.ui_popup_block_off),
                Toast.LENGTH_SHORT
            ).show()
            wv?.reload()
            dialog.dismiss()
        }

        // 🛡 Safeer Scit: filter DNS za ves televizor (vse aplikacije). Vklop odpre sistemsko okno z dovoljenjem (prvic).
        val cbScit = dialog.findViewById<CheckBox>(R.id.cbScit)
        val scitVklopljen = si.safeer.tv.scit.Scit.jeVklopljen(this)
        cbScit.isChecked = scitVklopljen
        dialog.findViewById<android.widget.TextView>(R.id.txtMenuScit).text = if (scitVklopljen) {
            UiText.get(R.string.menu_scit) + " · " + UiText.get(R.string.scit_blokiranih_danes, si.safeer.tv.scit.Scit.statistika(this).blokiranih)
        } else UiText.get(R.string.menu_scit)
        dialog.findViewById<LinearLayout>(R.id.rowMenuScit).setOnClickListener {
            val namera = Intent(this, si.safeer.tv.scit.ScitActivity::class.java)
            if (scitVklopljen) namera.putExtra(si.safeer.tv.scit.ScitActivity.EXTRA_IZKLOPI, true)
            try { startActivity(namera) } catch (_: Throwable) { }
            dialog.dismiss()
        }

        // 📁 Kam se shranjujejo prenesene datoteke (velja tudi za datoteke prek Safeer Linka).
        val txtPrenosiMapa = dialog.findViewById<android.widget.TextView>(R.id.txtPrenosiMapa)
        txtPrenosiMapa.text = PrenosiMapa.opis(this)
        dialog.findViewById<LinearLayout>(R.id.rowMenuPrenosiMapa).setOnClickListener {
            val oznake = PrenosiMapa.VSE
            val imena = arrayOf(
                UiText.get(R.string.ui_dir_downloads),
                UiText.get(R.string.ui_dir_documents),
                UiText.get(R.string.ui_dir_pictures),
                UiText.get(R.string.ui_dir_music),
                UiText.get(R.string.ui_dir_movies)
            )
            val trenutni = oznake.indexOf(PrenosiMapa.izbranaMapa(this))
            android.app.AlertDialog.Builder(this)
                .setTitle(UiText.get(R.string.ui_download_dir_title))
                .setSingleChoiceItems(imena, trenutni) { d, izbrano ->
                    d.dismiss()
                    dialog.dismiss()
                    PrenosiMapa.nastaviMapo(this, oznake[izbrano])
                    Toast.makeText(
                        this,
                        UiText.get(R.string.ui_download_dir_set, PrenosiMapa.opis(this)),
                        Toast.LENGTH_LONG
                    ).show()
                }
                .show()
        }

        val cbDark = dialog.findViewById<CheckBox>(R.id.cbDarkMode)
        cbDark.isChecked = isDarkModeActive
        dialog.findViewById<LinearLayout>(R.id.rowMenuDarkMode).setOnClickListener {
            isDarkModeActive = !isDarkModeActive
            cbDark.isChecked = isDarkModeActive
            getSharedPreferences("safeer_ui_prefs", MODE_PRIVATE).edit().putBoolean("dark_mode", isDarkModeActive).apply()
            tabManager.getAllTabs().forEach { t ->
                t.webView.applyDarkMode(isDarkModeActive)
            }
            Toast.makeText(
                this,
                if (isDarkModeActive) UiText.get(R.string.ui_dark_on) else UiText.get(R.string.ui_light_on),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        // Daljinec s telefona: tipke gredo v odprti meni (svoje okno), ne v dejavnost.
        meniDaljinca = dialog
        dialog.setOnDismissListener { if (meniDaljinca === dialog) meniDaljinca = null }
        dialog.show()
        dialog.findViewById<View>(R.id.rowMenuNewTab)?.requestFocus()
    }

    /**
     * Zahteve service workerjev ne gredo skozi WebViewClient; brez tega bi kompromitirana stran lahko
     * iz service workerja kontaktirala C2 strežnik. (WebSocket povezav WebView ne izpostavi.)
     */
    private fun installServiceWorkerThreatShield() {
        try {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                    ThreatBlockEngine.handleThreatIntercept(request.url.toString(), false)
            })
        } catch (e: Exception) {
            android.util.Log.w("SafeerSecurity", "Service worker Threat Shield ni na voljo: ${e.message}")
        }
    }

    private fun showThreatStatsDialog() {
        val totalThreats = ThreatBlockEngine.totalBlockedThreats.get()
        val c2 = ThreatBlockEngine.blockedC2Count.get()
        val malware = ThreatBlockEngine.blockedMalwareCount.get()
        val phishing = ThreatBlockEngine.blockedPhishingCount.get()
        val totalAds = AdBlockEngine.blockedAdsCount.get()

        AlertDialog.Builder(this)
            .setTitle("🛑 Safeer Threat Shield & AdBlock")
            .setMessage(
                """
                Varnostni ščit varuje vašo napravo pred nevarnimi C2 strežniki in zlonamerno kodo:
                
                • Blokiranih C2 Botnet strežnikov: $c2
                • Blokiranih Malware prenosov: $malware
                • Blokiranih Phishing strani: $phishing
                • Skupaj preprečenih groženj: $totalThreats
                • Blokiranih oglasov in sledilcev: $totalAds
                
                Viri: abuse.ch Feodo Tracker, URLhaus, ThreatFox, Phishing Army, HaGeZi TIF in Fake, SI-CERT, StevenBlack Hosts; oglasi: EasyList.
                """.trimIndent() + "\n" + UiText.get(R.string.ui_bankguard_status) + "\n" +
                    ThreatFeedsUpdater.statusLine() + "\n" + SignedThreatIntel.statusLine()
            )
            .setPositiveButton(UiText.get(R.string.ui_update_lists)) { _, _ ->
                Toast.makeText(this, UiText.get(R.string.ui_updating), Toast.LENGTH_SHORT).show()
                ThreatFeedsUpdater.updateFeedsAsync(this) { added ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, UiText.get(R.string.ui_lists_checked, added), Toast.LENGTH_LONG).show()
                    }
                }
                SignedThreatIntel.requestUpdate { installed ->
                    if (installed) runOnUiThread {
                        Toast.makeText(this@MainActivity, UiText.get(R.string.ui_signed_intel_updated), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(UiText.get(R.string.ui_close), null)
            .show()
    }

    private fun showFindInPage() {
        findInPageBar.visibility = View.VISIBLE
        editFindText.requestFocus()
        showKeyboard()

        val activeWv = tabManager.getActiveTab()?.webView
        activeWv?.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            tvFindMatches.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
        }
        val query = editFindText.text.toString()
        if (query.isNotEmpty()) {
            activeWv?.findAllAsync(query)
        } else {
            tvFindMatches.text = "0/0"
        }
    }

    internal fun showBookmarksDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_bookmarks)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val listView = dialog.findViewById<ListView>(R.id.bookmarksListView)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseBookmarks)
        val bookmarks = repository.getBookmarks()

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = bookmarks.size
            override fun getItem(position: Int): Any = bookmarks[position]
            override fun getItemId(position: Int): Long = bookmarks[position].id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val bm = bookmarks[position]
                val view = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_bookmark, parent, false)

                view.findViewById<TextView>(R.id.tvBmIcon).text = bm.icon
                view.findViewById<TextView>(R.id.tvBmTitle).text = bm.title
                view.findViewById<TextView>(R.id.tvBmUrl).text = bm.url

                view.setOnClickListener {
                    tabManager.getActiveTab()?.webView?.loadUrl(bm.url)
                    dialog.dismiss()
                }

                view.findViewById<Button>(R.id.btnDeleteBm).setOnClickListener {
                    repository.removeBookmark(bm.url)
                    updateBookmarkButton(activeUrl())
                    dialog.dismiss()
                    showBookmarksDialog()
                }

                return view
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showHistoryDialog() {
        val history = repository.getHistory(BrowserRepository.NAJVEC_ZGODOVINE)
        // Ciscenje je prva vrstica: prej je bilo na gumbu pod seznamom in si moral z
        // daljincem cez vse obiske, da si prisel do njega.
        val items = (listOf(UiText.get(R.string.ui_history_clear_item)) +
            history.map { "${it.title}\n${it.url}" }).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(UiText.get(R.string.ui_history_title))
            .setItems(items) { _, which ->
                if (which == 0) {
                    potrdiCiscenjeZgodovine()
                } else {
                    val selected = history[which - 1]
                    tabManager.getActiveTab()?.webView?.loadUrl(selected.url)
                }
            }
            .setNegativeButton(UiText.get(R.string.ui_close), null)
            .show()
    }

    /** Kratko vprasanje pred brisanjem, da en napacen pritisk ne pobrise vsega. */
    private fun potrdiCiscenjeZgodovine() {
        AlertDialog.Builder(this)
            .setTitle(UiText.get(R.string.ui_clear_history))
            .setMessage(UiText.get(R.string.ui_history_clear_confirm))
            .setPositiveButton(UiText.get(R.string.ui_ok)) { _, _ ->
                repository.clearHistory()
                Toast.makeText(this, UiText.get(R.string.ui_history_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(UiText.get(R.string.ui_cancel), null)
            .show()
    }

    /**
     * Prenosi v svojem oknu.
     *
     * Prej je ta vrstica v meniju odprla sistemsko namero za prikaz prenosov, televizor pa
     * take aplikacije nima - uporabnik je dobil sporocilo, da dejanja ne more obdelati noben
     * program. Zdaj seznam pokazemo sami: preberemo ga pri sistemskem prenosniku, kar pomeni,
     * da vidimo tudi tiste, ki se prenasajo, in da ne potrebujemo dovoljenja za shrambo.
     */
    private fun showDownloadsDialog() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
        val vrstice = ArrayList<String>()
        val idji = ArrayList<Long>()
        try {
            dm?.query(android.app.DownloadManager.Query())?.use { c ->
                val iId = c.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
                val iNaslov = c.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
                val iStanje = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                val iVelikost = c.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iDoslej = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                while (c.moveToNext() && vrstice.size < 50) {
                    val ime = (if (iNaslov >= 0) c.getString(iNaslov) else null) ?: "?"
                    val stanje = if (iStanje >= 0) c.getInt(iStanje) else 0
                    val velikost = if (iVelikost >= 0) c.getLong(iVelikost) else -1L
                    val doslej = if (iDoslej >= 0) c.getLong(iDoslej) else 0L
                    val opis = when (stanje) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> velikostVBesedi(velikost)
                        android.app.DownloadManager.STATUS_FAILED -> UiText.get(R.string.ui_downloads_failed)
                        else -> {
                            val delez = if (velikost > 0) (doslej * 100 / velikost) else 0L
                            UiText.get(R.string.ui_downloads_running) + " · " + delez + "%"
                        }
                    }
                    vrstice.add(ime + "\n" + opis)
                    idji.add(if (iId >= 0) c.getLong(iId) else -1L)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SafeerPrenosi", "Seznama prenosov ni bilo mogoce prebrati: " + e.message)
        }

        val gradnik = AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_downloads_title))
        if (vrstice.isEmpty()) {
            gradnik.setMessage(UiText.get(R.string.ui_downloads_empty))
        } else {
            gradnik.setItems(vrstice.toTypedArray()) { _, kateri -> odpriPrenos(idji[kateri]) }
        }
        gradnik.setNegativeButton(UiText.get(R.string.ui_close), null).show()
    }

    private fun velikostVBesedi(bajtov: Long): String {
        if (bajtov <= 0) return ""
        val mb = bajtov / 1048576.0
        val jezik = java.util.Locale.getDefault()
        return if (mb >= 1) String.format(jezik, "%.1f MB", mb) else String.format(jezik, "%.0f kB", bajtov / 1024.0)
    }

    /** Odpre preneseno datoteko s programom, ki jo zna odpreti; ce ga ni, to jasno povemo. */
    private fun odpriPrenos(id: Long) {
        if (id < 0) return
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager ?: return
        try {
            val naslov = dm.getUriForDownloadedFile(id)
            if (naslov == null) {
                Toast.makeText(this, UiText.get(R.string.ui_downloads_running), Toast.LENGTH_SHORT).show()
                return
            }
            val vrsta = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
            val namera = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(naslov, vrsta)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(namera)
        } catch (e: Exception) {
            Toast.makeText(this, UiText.get(R.string.ui_downloads_cannot_open), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupFindInPage() {
        editFindText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val wv = tabManager.getActiveTab()?.webView ?: return
                wv.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                    tvFindMatches.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
                }
                wv.findAllAsync(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnFindPrev.setOnClickListener {
            tabManager.getActiveTab()?.webView?.findNext(false)
        }

        btnFindNext.setOnClickListener {
            tabManager.getActiveTab()?.webView?.findNext(true)
        }

        btnFindClose.setOnClickListener {
            tabManager.getActiveTab()?.webView?.clearMatches()
            findInPageBar.visibility = View.GONE
            hideKeyboard()
        }
    }

    internal fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editUrl, InputMethodManager.SHOW_IMPLICIT)
    }

    internal fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = currentFocus?.windowToken ?: editUrl.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    internal var lastCenterClickTime: Long = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return keyRouter.dispatch(event)
    }

    internal fun handleBrowserBack() {
        val urlBefore = activeUrl()
        // Deljen zaslon druge naprave: "nazaj" ga zapre in vrne vrstico z naslovom.
        if (urlBefore.contains("/cast/screen/") && urlBefore.contains("/view")) {
            showBrowserStartPage()
            return
        }
        val native = playback.isNativeActive()
        if (playback.isActive()) {
            playback.exit()
            activeWebView()?.exitFullscreenVideo()
            // Po domacem predvajanju stran v ozadju pogosto obtici na svojem predvajalniku;
            // osvezimo jo, da spet pokaze svoj seznam. Velja za vsako stran enako.
            if (native) {
                activeWebView()?.reload()
                return
            }
            if (!TvSite.isYoutubeTv(urlBefore)) {
                return
            }
        }

        if (searchSuggestionsOverlay.visibility == View.VISIBLE) {
            searchSuggestionsOverlay.visibility = View.GONE
            hideKeyboard()
            editUrl.clearFocus()
            activeWebView()?.requestFocus()
            return
        }

        if (findInPageBar.visibility == View.VISIBLE) {
            activeWebView()?.clearMatches()
            findInPageBar.visibility = View.GONE
            return
        }

        if (tabSwitcherOverlay.visibility == View.VISIBLE) {
            tabSwitcherOverlay.visibility = View.GONE
            btnTabCount.requestFocus()
            return
        }

        if (editUrl.hasFocus()) {
            hideKeyboard()
            editUrl.clearFocus()
            activeWebView()?.requestFocus()
            return
        }

        // Spletna aplikacija: nazaj gre po njeni zgodovini, na zacetku pa se aplikacija zapre.
        if (nacinAplikacije != null) {
            val pogled = activeWebView()
            // Domaca stran brskalnika ni del aplikacije: ce bi prisli nanjo, raje koncamo.
            if (pogled != null && pogled.canGoBack() && !TvSite.isBrowserHome(activeUrl())) { pogled.goBack(); return }
            izklopiNacinAplikacije()
            silenceBackgroundMedia("backWebApp")
            koncajVrniSe()
            return
        }

        val curUrl = activeUrl()
        if (TvSite.isBrowserHome(curUrl)) {
            SafeerDbg.log("H220", "MainActivity.kt:back", "leave browser", JSONObject().put("url", curUrl.take(80)))
            silenceBackgroundMedia("backHome")
            koncajVrniSe()
            return
        }

        val profile = SiteProfileResolver.fromUrl(curUrl)
        if (profile.handleBack(this)) return

        super.onBackPressed()
    }

    override fun onBackPressed() {
        handleBrowserBack()
    }
}
