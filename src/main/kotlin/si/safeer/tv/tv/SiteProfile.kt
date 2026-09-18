package si.safeer.tv

import android.view.KeyEvent
import org.json.JSONObject

enum class PlaybackMode {
    InPlaceWebView,
    CustomView,
    ExoPlayer
}

object TvSite {
    fun isYoutubeTv(url: String): Boolean {
        return url.contains("youtube.com/tv", ignoreCase = true)
    }

    fun isWatchPage(url: String): Boolean {
        val u = url.lowercase()
        if (isYoutubeTv(u)) return false
        return u.contains("/watch") || u.contains("/shorts/") || u.contains("youtube.com/embed")
    }

    fun isBrowserHome(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("android_asset/brave_home") || u.startsWith("file:///android_asset/brave_home")
    }

    /** Stran gledalca deljenega zaslona (Safeer Link): tuj zaslon gledamo cez cel televizor. */
    fun isSharedScreen(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/cast/screen/") && u.contains("/view")
    }

    fun hideChrome(url: String): Boolean {
        return isSharedScreen(url)
    }
}

interface SiteProfile {
    fun matches(url: String): Boolean
    fun hideChrome(url: String): Boolean = false
    fun playbackMode(): PlaybackMode = PlaybackMode.CustomView
    fun consumeActionUp(keyCode: Int): Boolean = false
    fun handleSearch(query: String, host: MainActivity): Boolean = false
    fun handleSearchKey(host: MainActivity): Boolean = false
    fun handleKey(event: KeyEvent, host: MainActivity): Boolean
    fun handleBack(host: MainActivity): Boolean
}

object SiteProfileResolver {
    private val profiles = listOf(
        YoutubeTvSiteProfile,
        GenericWebSiteProfile
    )

    fun fromUrl(url: String): SiteProfile {
        return profiles.first { it.matches(url) }
    }
}

/**
 * Smerno tipko najprej ponudimo nasi navigaciji. Ce ta pove, da ni imela kam (-1), tipko
 * dobi stran sama. Tako pridemo do gumbov v oknih, ki tecejo v svojem okvirju in jih nasa
 * skripta sploh ne vidi - na primer do "Zavrni vse" v Googlovem oknu o piskotkih.
 */
private fun posljiSmer(wv: android.webkit.WebView, smer: String, event: KeyEvent) {
    // V Googlove strani namenoma ne vbrizgavamo nicesar, da ostanejo prijava, iskanje in
    // reCAPTCHA povsem izvirni. Tam torej nase navigacije ni in tipka gre naravnost strani -
    // sicer bi se izgubila in uporabnik ne bi mogel niti do gumbov v oknu o piskotkih.
    if (si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url)) {
        nativnaTipka(wv, event)
        return
    }
    try {
        wv.evaluateJavascript("window._safeer_navigate_spatial('$smer');") { odgovor ->
            val ocisceno = (odgovor ?: "").trim().trim('"')
            if (ocisceno == "-1" || ocisceno == "null" || ocisceno.isEmpty()) {
                nativnaTipka(wv, event)
            }
        }
    } catch (_: Exception) {
        nativnaTipka(wv, event)
    }
}

/**
 * Premakne fokus po strani sami. Smerne tipke spletna vsebina ne pozna (daljinec je iznajdba
 * televizorja), zna pa vsaka stran vrstni red s tipko Tab - in brskalnik nov fokus sam
 * pridrsa v pogled. Navzgor in levo gresta nazaj (Shift+Tab).
 */
private fun nativnaTipka(wv: android.webkit.WebView, event: KeyEvent) {
    try {
        val nazaj = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val meta = if (nazaj) KeyEvent.META_SHIFT_ON else 0
        val zdaj = android.os.SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(
            KeyEvent(zdaj, zdaj, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta)
        )
        wv.dispatchKeyEvent(
            KeyEvent(zdaj, zdaj + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, meta)
        )
    } catch (_: Exception) {}
}

object YoutubeTvSiteProfile : SiteProfile {
    override fun matches(url: String) = TvSite.isYoutubeTv(url)
    override fun playbackMode() = PlaybackMode.InPlaceWebView

    override fun handleSearch(query: String, host: MainActivity): Boolean {
        val wv = host.activeWebView() ?: return false
        val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
        host.hideKeyboard()
        host.editUrl.clearFocus()
        wv.requestFocus()
        wv.evaluateJavascript(
            "window._safeer_yt_tv_search ? window._safeer_yt_tv_search('$escaped') : (location.hash = '#/search?q=' + encodeURIComponent('$escaped'));",
            null
        )
        return true
    }

    override fun handleSearchKey(host: MainActivity): Boolean {
        val wv = host.activeWebView()
        host.hideKeyboard()
        host.editUrl.clearFocus()
        wv?.requestFocus()
        wv?.evaluateJavascript(
            "window._safeer_yt_tv_search ? window._safeer_yt_tv_search('') : (location.hash = '#/search');",
            null
        )
        return true
    }

    /** Cas zadnjega samostojnega pritiska tipke gor (za kretnjo "dvakrat hitro gor"). */
    @Volatile
    private var zadnjiPritiskGor = 0L

    private const val DVOJNI_PRITISK_MS = 700L

    fun dispatchYoutubeTvKey(host: MainActivity, event: KeyEvent): Boolean {
        val webView = host.activeWebView() ?: return host.superDispatchKey(event)
        if (event.action == KeyEvent.ACTION_DOWN && !webView.hasFocus()) {
            host.hideKeyboard()
            host.editUrl.clearFocus()
            webView.requestFocus()
        }
        return webView.dispatchKeyEvent(event)
    }


    override fun handleKey(event: KeyEvent, host: MainActivity): Boolean {
        val keyCode = event.keyCode
        val curUrl = host.activeUrl().lowercase()
        if (host.isChromeFocused() && event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            host.hideKeyboard()
            host.editUrl.clearFocus()
            host.activeWebView()?.requestFocus()
            return true
        }
        if (host.isChromeFocused() || host.virtualPointerView.isPointerVisible ||
            host.tabSwitcherOverlay.visibility == android.view.View.VISIBLE ||
            host.findInPageBar.visibility == android.view.View.VISIBLE
        ) {
            return false
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP) zadnjiPritiskGor = 0L
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (curUrl.contains("#/watch") || curUrl.contains("/watch?v=")) {
                    host.btnBack.requestFocus()
                    true
                } else if (event.repeatCount == 0 &&
                    android.os.SystemClock.uptimeMillis() - zadnjiPritiskGor < DVOJNI_PRITISK_MS
                ) {
                    // Dvakrat hitro gor: leanback vsako smerno tipko obdela sam, zato je to
                    // edina zanesljiva pot iz strani v orodno vrstico brskalnika.
                    zadnjiPritiskGor = 0L
                    host.btnBack.requestFocus()
                    true
                } else {
                    if (event.repeatCount == 0) {
                        zadnjiPritiskGor = android.os.SystemClock.uptimeMillis()
                    }
                    dispatchYoutubeTvKey(host, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> dispatchYoutubeTvKey(host, event)
            else -> false
        }
    }

    override fun handleBack(host: MainActivity): Boolean {
        val ytWv = host.activeWebView() ?: return false
        ytWv.evaluateJavascript(
            """
            (function(){
                var h = (location.hash || '').toLowerCase();
                var domov = h === '' || h === '#' || h === '#/' || h === '#/index';
                try {
                    document.querySelectorAll('video,audio').forEach(function(m){ try { m.pause(); } catch (eP) {} });
                } catch (eV) {}
                if (domov) return 'exit';
                location.replace('https://www.youtube.com/tv');
                return 'browse';
            })();
            """.trimIndent()
        ) { result ->
            if (result != null && result.contains("exit")) {
                host.runOnUiThread {
                    ytWv.loadUrl("file:///android_asset/brave_home.html")
                }
            }
        }
        return true
    }
}

object GenericWebSiteProfile : SiteProfile {
    override fun matches(url: String) = true

    /**
     * Med domacim predvajanjem skrijemo vrstico z naslovom: takrat je na zaslonu slika, ne
     * brskalnik. Prej je bila to lastnost ene nastete strani - zdaj velja povsod, kjer smo
     * ujeli pretok.
     */
    override fun hideChrome(url: String) =
        TvSite.isSharedScreen(url) || DashPrevzem.imaSejo()

    /**
     * Ce smo na tej strani ujeli pretok DASH, ga odigra ExoPlayer - to je na televizorju
     * razlika med zatikanjem in gladko sliko. Odloca pretok, ne ime strani.
     */
    override fun playbackMode() =
        if (DashPrevzem.imaSejo()) PlaybackMode.ExoPlayer else PlaybackMode.CustomView

    override fun handleKey(event: KeyEvent, host: MainActivity): Boolean {
        val wv = host.activeWebView()
        val keyCode = event.keyCode
        val watch = TvSite.isWatchPage(host.activeUrl())
        if (watch && !host.isTopBarFocused() && !host.virtualPointerView.isPointerVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val now = System.currentTimeMillis()
                    if (now - host.lastCenterClickTime < 380) {
                        wv?.evaluateJavascript("window._safeer_toggle_fullscreen();", null)
                    } else {
                        wv?.evaluateJavascript("window._safeer_toggle_play_pause();", null)
                    }
                    host.lastCenterClickTime = now
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    wv?.evaluateJavascript("window._safeer_seek(-10);", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    wv?.evaluateJavascript("window._safeer_seek(10);", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    host.editUrl.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    wv?.evaluateJavascript("window._safeer_toggle_fullscreen();", null)
                    return true
                }
            }
        }
        if (wv == null) return false
        // Vgrajeni pregledovalnik PDF (naslov pogleda je notranji, host.activeUrl() vrne javnega):
        // smerne tipke gredo strani (listanje, orodna vrstica), da fokus ne uide iz pogleda;
        // OK/Enter gre naravno v pogled (klik na izbrani gumb pregledovalnika).
        if (!host.isTopBarFocused() && PdfPregledovalnik.jePregledovalnik(wv.url)) {
            if (!wv.hasFocus()) wv.requestFocus()
            val smer = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
                KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
                KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
                else -> null
            }
            if (smer != null) {
                wv.evaluateJavascript("window.SafeerPdfTipka && window.SafeerPdfTipka('$smer');", null)
                return true
            }
            return false
        }
        if (host.isTopBarFocused()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                host.hideKeyboard()
                host.editUrl.clearFocus()
                wv.requestFocus()
                wv.evaluateJavascript("window._safeer_navigate_spatial('DOWN');", null)
                return true
            }
            return false
        }
        if (!wv.hasFocus()) wv.requestFocus()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                posljiSmer(wv, "DOWN", event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                wv.evaluateJavascript("window._safeer_navigate_spatial('UP');") { result ->
                    if (result == "-1" || result == "null" || result == null) {
                        host.runOnUiThread {
                            if (host.nacinAplikacije == null) {
                                host.mobileTopBar.visibility = android.view.View.VISIBLE
                                host.mobileTopBar.animate().translationY(0f).setDuration(150).start()
                            }
                            host.editUrl.requestFocus()
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                posljiSmer(wv, "LEFT", event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                posljiSmer(wv, "RIGHT", event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                wv.evaluateJavascript("window._safeer_click_focused_card();", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                wv.evaluateJavascript("window._safeer_toggle_play_pause();", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                wv.evaluateJavascript("window._safeer_seek(-10);", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                wv.evaluateJavascript("window._safeer_seek(10);", null)
                return true
            }
            else -> return false
        }
    }

    /**
     * Ali ima Nazaj kam iti? Prva stran pojavnega okna je prazna lupina, zato bi obicajen
     * Nazaj uporabnika pustil na praznem zaslonu namesto da bi okno zaprl.
     */
    private fun jeSmiselnoNazaj(pogled: android.webkit.WebView): Boolean {
        if (!pogled.canGoBack()) return false
        return try {
            val seznam = pogled.copyBackForwardList()
            val prejsnji = seznam.getItemAtIndex(seznam.currentIndex - 1)?.url.orEmpty()
            prejsnji.isNotBlank() && !prejsnji.startsWith("about:")
        } catch (_: Throwable) {
            false
        }
    }

    override fun handleBack(host: MainActivity): Boolean {
        val tab = host.tabManager.getActiveTab()
        // Pojavno okno (prijava): Nazaj ga zapre in nas vrne na stran, ki ga je odprla.
        if (tab != null && tab.jePojavni && !jeSmiselnoNazaj(tab.webView)) {
            host.tabManager.zapriPojavni(host, tab)
            return true
        }
        if (TvSite.isWatchPage(host.activeUrl())) {
            if (tab?.webView?.canGoBack() == true) {
                tab.webView.goBack()
                return true
            }
            tab?.webView?.loadUrl("file:///android_asset/brave_home.html")
            return true
        }
        if (tab != null && tab.webView.canGoBack()) {
            tab.webView.goBack()
            return true
        }
        if (host.tabManager.count > 1 && tab != null) {
            host.tabManager.closeTab(host, tab.id)
            return true
        }
        return false
    }
}
