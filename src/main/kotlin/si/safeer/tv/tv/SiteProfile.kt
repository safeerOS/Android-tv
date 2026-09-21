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

private const val FOKUS_Y = "(function(){var e=document.activeElement;if(!e||e===document.body)return '-';var r=e.getBoundingClientRect();var p=(r.bottom>0&&r.top<innerHeight)?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):e;return (!p||!(p===e||e.contains(p))||e.matches('input,textarea,[contenteditable=\"true\"]')||e.closest('form,[role=\"search\"]')||r.width<8||r.height<8?'v':'')+Math.round(r.top+window.scrollY)+':'+Math.round(r.bottom+window.scrollY);})()"

/**
 * OK na Googlu: fokus na povezavo v fokusiranem elementu (ovoj zadetka je DIV), nato pravi Enter.
 * Pravi Enter odpre povezavo kot uporabnik (tudi prek preusmeritve google.com/goto).
 */
private const val KLIK_FOKUSA = "(function(){var e=document.activeElement;if(!e||e===document.body)return 0;var a=e.matches('a[href]')?e:e.querySelector('a[href]');if(!a)return 0;a.focus({preventScroll:true});return document.activeElement===a?'f':0;})()"

/**
 * Pred korakanjem: ce je fokus nad Googlovim iskalnim obrazcem, ga postavimo na zadnji gumb v obrazcu
 * (mikrofon) - ze bezen Tab cez iskalno polje odpre Googlov predlog cez zadetke (21. 9. 2026). Sicer
 * na zadnji element iste vrstice: vrsta zavihkov je 23 korakov Tab (1,3 s), tako en sam.
 */
private const val PRESKOK_OBRAZCA = "(function(){var e=document.activeElement;var f=document.querySelector('form[role=\"search\"],form[action=\"/search\"]');if(f&&(!e||e===document.body||e===document.documentElement||(!f.contains(e)&&(e.compareDocumentPosition(f)&4)))){var b=f.querySelectorAll('button,[role=\"button\"],a[href],[tabindex]:not([tabindex=\"-1\"])');for(var i=b.length-1;i>=0;i--){var c=b[i];if(c.matches('input,textarea'))continue;var r=c.getBoundingClientRect();if(r.width>=8&&r.height>=8){c.focus({preventScroll:true});return 'obrazec';}}}if(!e||e===document.body)return 0;var L=[].slice.call(document.querySelectorAll('a[href],button,input,textarea,select,[tabindex]'));var k=L.indexOf(e);if(k<0)return 0;var er=e.getBoundingClientRect(),z=null;function vidno(x,q){var cy=q.top+q.height/2;if(cy<0||cy>innerHeight)return true;var p=document.elementFromPoint(q.left+q.width/2,cy);return !!p&&(p===x||x.contains(p));}for(var j=k+1;j<L.length&&j<k+400;j++){var x=L[j],q=x.getBoundingClientRect();if(q.width<1||q.height<1||q.bottom<=er.top+4)continue;if(q.top<er.bottom-4){if(!x.matches('input,textarea'))z=x;continue;}if(vidno(x,q))break;}if(z){z.focus({preventScroll:true});return 'vrstica';}return 0;})()"

private fun dolNaGooglu(wv: android.webkit.WebView, event: KeyEvent) {
    wv.evaluateJavascript(PRESKOK_OBRAZCA) { vrsticaNizje(wv, event, null, 0) }
}

/**
 * DOL na Googlovih rezultatih: Tab, dokler fokus ne pride v nizjo vrstico (najvec 30 korakov).
 * Tab sam je sel cez meni, iskalno polje in vsak zavihek posebej - do prvega zadetka ~20 pritiskov
 * (video Mateja 21. 9. 2026). Iskalni obrazec (polje, X, mikrofon) preskocimo: fokus na polje odpre
 * Googlov predlog cez zadetke. Nevidne povezave "preskoci na vsebino" (1 px ali odrezane) tudi.
 * Tab (ne programski fokus) zato, ker Google okvir fokusa kaze samo pri tipkovnici.
 */
private fun vrsticaNizje(wv: android.webkit.WebView, event: KeyEvent, od: Int?, korak: Int) {
    wv.evaluateJavascript(FOKUS_Y) { r ->
        val s = (r ?: "").trim('"')
        // "v" = preskoci; nato zgornji:spodnji rob elementa na strani.
        val mere = s.removePrefix("v").split(':')
        val vrh = mere.getOrNull(0)?.toIntOrNull()
        // Nova vrstica: element se zacne pod spodnjim robom zacetnega (logotip in Prijava sta ena vrstica).
        if (korak > 0 && !s.startsWith("v") && vrh != null && (od == null || vrh >= od - 4)) return@evaluateJavascript
        if (korak >= 30) return@evaluateJavascript
        nativnaTipka(wv, event)
        wv.postDelayed({ vrsticaNizje(wv, event, if (korak == 0) mere.getOrNull(1)?.toIntOrNull() else od, korak + 1) }, 40)
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
        // Rezultati iskanja: DOL gre v naslednjo vrstico, ne na naslednji zavihek ([vrsticaNizje]).
        if (smer == "DOWN" && jeGoogleIskanje(wv)) { dolNaGooglu(wv, event); return }
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

private fun jeGoogleIskanje(wv: android.webkit.WebView) =
    si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url) && (wv.url ?: "").contains("/search?")

/**
 * Premakne fokus po strani sami. Smerne tipke spletna vsebina ne pozna (daljinec je iznajdba
 * televizorja), zna pa vsaka stran vrstni red s tipko Tab - in brskalnik nov fokus sam
 * pridrsa v pogled. Navzgor in levo gresta nazaj (Shift+Tab).
 */
private fun nativnaTipka(wv: android.webkit.WebView, event: KeyEvent, tipka: Int = KeyEvent.KEYCODE_TAB) {
    try {
        val nazaj = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val meta = if (nazaj && tipka == KeyEvent.KEYCODE_TAB) KeyEvent.META_SHIFT_ON else 0
        val zdaj = android.os.SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(
            KeyEvent(zdaj, zdaj, KeyEvent.ACTION_DOWN, tipka, 0, meta)
        )
        wv.dispatchKeyEvent(
            KeyEvent(zdaj, zdaj + 1, KeyEvent.ACTION_UP, tipka, 0, meta)
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
                // Na Googlu nase navigacije ni: iz naslovne vrstice v prvo vrstico strani.
                if (jeGoogleIskanje(wv)) dolNaGooglu(wv, event)
                else wv.evaluateJavascript("window._safeer_navigate_spatial('DOWN');", null)
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
                // Na Googlu nase skripte ni, zato OK ni naredil nicesar (zadetka ni bilo mogoce odpreti):
                // tam gre Enter strani sami.
                if (si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url)) {
                    // Fokus je pogosto na ovoju zadetka (DIV): najprej na povezavo v njem, nato Enter strani.
                    wv.evaluateJavascript(KLIK_FOKUSA) { nativnaTipka(wv, event, KeyEvent.KEYCODE_ENTER) }
                    return true
                }
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
