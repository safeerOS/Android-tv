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
 * OK na Googlu: fokus na povezavo v fokusiranem elementu (ovoj zadetka je DIV), nato pravi Enter.
 * Pravi Enter odpre povezavo kot uporabnik (tudi prek preusmeritve google.com/goto).
 */
private const val KLIK_FOKUSA = "(function(){var e=document.activeElement;if(!e||e===document.body)return 0;var a=e.matches('a[href]')?e:e.querySelector('a[href]');if(!a)return 0;a.focus({preventScroll:true});return document.activeElement===a?'f':0;})()"

/**
 * Smerne tipke na Googlovih zadetkih (22. 9. 2026, video Mateja): Tab je hodil po vsakem zavihku, meniju
 * zadetka (tri pike) in "Prevedi to stran"; GOR je takoj skocil v naslovno vrstico. Zdaj cilj izberemo po
 * legi na zaslonu (naslednja vrstica v smeri tipke, najblizji rob, poravnava levo; ovoji brez povezave in
 * male ikone brez besedila ne stejejo); Google okvir
 * fokusa pokaze le pri tipkovnici ([smerNaGooglu]). V stran ne vbrizgamo nicesar trajnega.
 * Vrne 'ok' (fokus je na cilju), 'rob' (v tej smeri ni nicesar).
 */
private const val GOOGLE_SMER = """((function(smer){ var W=innerWidth,H=innerHeight; var forms=[].slice.call(document.querySelectorAll('form[role="search"],form[action="/search"]')); function vObrazcu(x){for(var i=0;i<forms.length;i++)if(forms[i].contains(x))return true;return false;} var sel='a[href],button,input:not([type="hidden"]),select,textarea,[role="button"],[role="link"],[role="tab"],[tabindex]:not([tabindex="-1"])'; var dno=0; [].slice.call(document.querySelectorAll('input:not([type="hidden"]),textarea,[role="combobox"],[role="search"],form')).forEach(function(z){var f=z.getBoundingClientRect();if(f.height>0&&f.width>W*0.3&&f.bottom>0&&f.top<H*0.4&&f.bottom<H*0.6&&f.bottom>dno)dno=f.bottom;}); function vDrsniku(x){for(var p=x.parentElement;p&&p!==document.body;p=p.parentElement){var o=getComputedStyle(p).overflowX;if((o==='auto'||o==='scroll')&&p.scrollWidth>p.clientWidth+4)return true;}return false;} var vsi=[].slice.call(document.querySelectorAll(sel)),L=[]; for(var i=0;i<vsi.length;i++){var x=vsi[i]; if(vObrazcu(x)||x.matches('input,textarea,select'))continue; if(x.closest('[aria-hidden="true"],[inert]'))continue; var r=x.getBoundingClientRect();if(r.width<8||r.height<8)continue; if(dno>0&&r.bottom<=dno+1)continue; if(r.bottom<-H*1.5||r.top>H*2.5||r.right<-W||r.left>W*2)continue; var cs=getComputedStyle(x);if(cs.visibility==='hidden'||cs.display==='none'||+cs.opacity===0)continue; var cy=r.top+r.height/2; if(r.right<=1||r.left>=W-1){if(!vDrsniku(x))continue;} else if(cy>2&&cy<H-2){var vidna=false,tocke=[r.left+Math.min(12,r.width/2),r.left+r.width*0.25,r.left+r.width/2]; for(var t=0;t<tocke.length&&!vidna;t++){var px=Math.min(Math.max(tocke[t],1),W-1),p=document.elementFromPoint(px,cy);vidna=!!p&&(p===x||x.contains(p)||p.contains(x));} if(!vidna)continue;} L.push({x:x,r:r});} L=L.filter(function(a){var povezava=a.x.matches('a[href],button');for(var j=0;j<L.length;j++){var b=L[j];if(b===a)continue; if(!povezava&&a.x.contains(b.x))return false; if(b.x.matches('a[href],button')&&b.x.contains(a.x))return false;} return true;}); var e=window.__gnavOd||document.activeElement,cur=null; window.__gnavOd=null; if(e===document.body||e===document.documentElement||(e&&vObrazcu(e)))e=null; if(e){var cr=e.getBoundingClientRect();if(cr.width>0&&cr.height>0)cur=cr;else e=null;} if(!cur){if(smer==='UP')return 'rob'; cur={left:0,right:W,top:dno-1,bottom:dno,width:W,height:1};} var vert=(smer==='UP'||smer==='DOWN'),naj=null,ns=1e9; function ikona(a){var t=(a.x.innerText||'').replace(/\s+/g,'').length;return t===0&&a.r.width<=56&&a.r.height<=56;} for(var k=0;k<L.length;k++){var a=L[k],q=a.r; if(a.x===e||(e&&(e.contains(a.x)||a.x.contains(e))))continue; var gap,ov; var tol=Math.min(12,cur.height/2); if(smer==='DOWN'){if(q.top<cur.bottom-tol)continue;gap=q.top-cur.bottom;} else if(smer==='UP'){if(q.bottom>cur.top+tol)continue;gap=cur.top-q.bottom;} else if(smer==='RIGHT'){if(q.left<cur.right-8)continue;gap=q.left-cur.right;} else{if(q.right>cur.left+8)continue;gap=cur.left-q.right;} if(gap<0)gap=0; ov=vert?Math.min(q.right,cur.right)-Math.max(q.left,cur.left):Math.min(q.bottom,cur.bottom)-Math.max(q.top,cur.top); var s=gap+(ov>0?0:-ov)*2+(vert?Math.abs(q.left-cur.left)*0.3:0); if(ikona(a))s+=120; if(s<ns){ns=s;naj=a.x;}} if(!naj)return 'rob'; var rr=naj.getBoundingClientRect(); if(rr.top<0||rr.bottom>H)naj.scrollIntoView({block:rr.height>H*0.6?'start':'center',inline:'nearest'}); else if(rr.left<0||rr.right>W)naj.scrollIntoView({block:'nearest',inline:'nearest'}); try{naj.focus({preventScroll:true,focusVisible:true});}catch(_){naj.focus();} return (document.activeElement===naj?'ok':'ni')+(naj.matches(':focus-visible')?' fv ':' - ')+((naj.innerText||naj.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').slice(0,25); }))"""


/** Smer na Googlovih zadetkih; [naRobu] se poklice, ce v tej smeri ni nicesar (GOR: naslovna vrstica). */
internal fun smerNaGooglu(wv: android.webkit.WebView, smer: String, event: KeyEvent, naRobu: () -> Unit) {
    // Google okvir fokusa vklopi sele, ko vidi tipko Tab (ne programski fokus ne druge tipke - preizkuseno
    // na TV 22. 9.). Zato na strani enkrat posljemo pravi Tab; cilj pa racunamo od elementa, ki je bil
    // izbran PRED njim (__gnavOd), ne od tistega, kamor je Tab skocil.
    wv.evaluateJavascript("(function(){var t=!window.__gnavTab;window.__gnavTab=1;window.__gnavOd=document.activeElement;return t?'1':'0';})()") { prvic ->
        if ((prvic ?: "").contains("1")) nativnaTipka(wv, event)
        wv.evaluateJavascript("$GOOGLE_SMER('$smer')") { odgovor ->
            android.util.Log.d("SafeerGoogleNav", "$smer -> $odgovor")
            if ((odgovor ?: "").trim('"') == "rob") {
                if (smer == "DOWN") wv.evaluateJavascript("window.scrollBy(0,Math.round(innerHeight*0.6))", null) else naRobu()
            }
        }
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
        // Rezultati iskanja: vse smeri po legi na zaslonu ([smerNaGooglu]).
        if (jeGoogleIskanje(wv)) { smerNaGooglu(wv, smer, event) {}; return }
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
                if (jeGoogleIskanje(wv)) wv.evaluateJavascript("document.activeElement&&document.activeElement.blur()") { smerNaGooglu(wv, "DOWN", event) {} }
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
                if (jeGoogleIskanje(wv)) {
                    // GOR na Googlu: prejsnja vrstica; v naslovno vrstico sele z vrha strani.
                    smerNaGooglu(wv, "UP", event) {
                        host.runOnUiThread {
                            if (host.nacinAplikacije == null) {
                                host.mobileTopBar.visibility = android.view.View.VISIBLE
                                host.mobileTopBar.animate().translationY(0f).setDuration(150).start()
                            }
                            host.editUrl.requestFocus()
                        }
                    }
                    return true
                }
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
