package si.safeer.tv.os

import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient

/** En sam sistemski WebView za lahke strani in nevidno branje spletnih virov. */
object LahkiWebViewPool {
    private const val TAG = "SafeerRam"
    private const val PRAZEN = "about:blank"
    /**
     * Prost pogled zivi samo toliko, da ga naslednji vir istega pregleda kataloga (SpletniVir.beriStran - viri gredo
     * zaporedno) prevzame brez novega izrisovalnika. Prej 60 s: po odprtju Filmov je nevidni WebView ostal ziv
     * minuto po koncu branja (naloga 53).
     */
    private const val PROST_MS = 5_000L
    private val glavna = Handler(Looper.getMainLooper())

    private var pogled: WebView? = null
    private var lastnik: Any? = null
    private var obOdvzemu: (() -> Unit)? = null

    /**
     * Naloga 64: vsi WebView-ji procesa, ki jih se nismo unicili (destroy). Izmerjeno na TV med predvajanjem filma iz dodatka:
     * v nasem procesu 0 WebView-jev, izrisovalnik (sandboxed_process0) pa 178 MB - Chromium ga ne konca, ko zadnji
     * WebView izgine, ampak ga drzi do konca procesa. Koncamo ga sami ([koncajIzrisovalnik]), a samo, ko je ta mnozica
     * prazna: vsi WebView-ji aplikacije si delijo en izrisovalnik (zavihki brskalnika, okno Linka, spletni predvajalnik).
     * Samo glavna nit (WebView ustvarjamo in unicujemo na njej).
     */
    private val zivi: MutableSet<WebView> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    /** Zadnji znani izrisovalnik (WebViewRenderProcess, API 29+): dosegljiv je samo prek zivega WebView-ja. */
    private var izrisovalnik: Any? = null

    /** WebView je nastal (ChromiumEngineView, okno Linka, pool). */
    fun prijavi(w: WebView) { zivi += w }

    /** WebView bo unicen: zapomnimo si njegov izrisovalnik (po destroy() ga ne dobimo vec). */
    fun odjavi(w: WebView) {
        if (Build.VERSION.SDK_INT >= 29) try { w.webViewRenderProcess?.let { izrisovalnik = it } } catch (_: Throwable) { }
        zivi -= w
    }

    /**
     * Naloga 64: nas predvajalnik (Media3) je zacel igrati - tok je najden, stran ga ne rabi vec. Pogled poola (prost
     * ali najet za nevidno branje oz. iskanje toka) unicimo takoj, ne sele cez [PROST_MS], in ce v procesu ni nobenega
     * drugega WebView-ja, koncamo tudi izrisovalnik (~178 MB). [spletnoPredvajanje]: igra stran (stopnja 2 LahkaStran
     * ali 3 SpletniIgralec) - takrat ostane vse.
     */
    fun obNativnemPredvajanju(spletnoPredvajanje: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            glavna.post { obNativnemPredvajanju(spletnoPredvajanje) }
            return
        }
        if (spletnoPredvajanje) return
        // Pregled 70: najet pogled (nevidno branje spletnega vira, iskanje toka) pustimo - sicer bi vsaka nova skladba
        // ali nadaljevanje po pavzi prekinila branje vira. Ob vrnitvi ga poberejo PROST_MS / onTrimMemory.
        if (lastnik != null) { izrisovalnikPoPredvajanju = true; return }
        val w = pogled
        if (w != null) {
            pogled = null
            obOdvzemu = null
            glavna.removeCallbacks(unicenje)
            unicuj(w, "nativno predvajanje")
        }
        koncajIzrisovalnik("nativno predvajanje")
    }

    /**
     * Konca izrisovalnik WebView, ce ga noben WebView procesa ne rabi. Android 9 (API 28) tega ne zna (ni
     * WebViewRenderProcess): tam izrisovalnik ostane kot doslej.
     */
    private fun koncajIzrisovalnik(razlog: String) {
        if (Build.VERSION.SDK_INT < 29) return
        if (zivi.isNotEmpty()) { Log.i(TAG, "izrisovalnik WebView ostane: zivih WebView ${zivi.size} ($razlog)"); return }
        val r = izrisovalnik as? android.webkit.WebViewRenderProcess ?: return
        izrisovalnik = null
        val koncan = try { r.terminate() } catch (_: Throwable) { false }
        Log.i(TAG, "izrisovalnik WebView koncan=$koncan ($razlog)")
    }

    /**
     * Odjemalec pogleda, ki ta trenutek nima lastnika. Vsi WebView-ji aplikacije si delijo en izrisovalni proces: ko se
     * ta sesuje (stran v zavihku, npr. Googlov odgovor z umetno inteligenco - posnetek 3. 10. 2026) in KATERI KOLI
     * WebView vrne false, Android ubije celo aplikacijo (sistem nato ponudi "odstrani posodobitve WebView"). Privzeti
     * WebViewClient vrne false - zato mirujoc pogled iz poola ob smrti izrisovalnika samo zavrzemo.
     */
    private class MirujociKlient : WebViewClient() {
        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            Log.w(TAG, "izrisovalnik je koncal (sesutje=${detail?.didCrash() == true}); lahki pogled zavrzem")
            val w = view ?: return true
            glavna.post {
                odjavi(w)
                if (pogled === w) {
                    pogled = null
                    val klic = obOdvzemu
                    lastnik = null
                    obOdvzemu = null
                    glavna.removeCallbacks(unicenje)
                    try { klic?.invoke() } catch (_: Throwable) { }
                }
                try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Throwable) { }
                try { w.destroy() } catch (_: Throwable) { }
            }
            return true
        }
    }

    private val unicenje = Runnable {
        val w = pogled ?: return@Runnable
        if (lastnik != null) return@Runnable
        pogled = null
        unicuj(w, "lahki pool je neaktiven")
        // Izmerjeno na TV (00:47): ob zacetku filma je bil pogled se najet (iskanje toka), zato ga obNativnemPredvajanju ni
        // vzel; izrisovalnik (92 MB) je ostal ves film. Ko ga pool zdaj sprosti, izrisovalnik koncamo, ce predvajanje caka.
        if (izrisovalnikPoPredvajanju) { izrisovalnikPoPredvajanju = false; koncajIzrisovalnik("nativno predvajanje, pool neaktiven") }
    }

    /** Nativno predvajanje se je zacelo, ko je bil pogled najet: izrisovalnik koncamo, ko pool pogled sprosti. */
    private var izrisovalnikPoPredvajanju = false

    /**
     * Vrne edini pogled procesa. Ce ga se uporablja staro nevidno opravilo, ga prekine in
     * isti pogled takoj preda novemu lastniku; tako nikoli ne obstajata dva lahka rendererja.
     */
    fun pridobi(context: Context, noviLastnik: Any, odvzemiStaremu: () -> Unit): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView pool mora teci na glavni niti" }
        glavna.removeCallbacks(unicenje)
        val obstojeci = pogled
        if (obstojeci != null && lastnik != null && lastnik !== noviLastnik) {
            val stariKlic = obOdvzemu
            pocisti(obstojeci, obstojeci.url)
            lastnik = null
            obOdvzemu = null
            stariKlic?.invoke()
        }
        val w = pogled ?: WebView(MutableContextWrapper(context.applicationContext)).also {
            pogled = it
            prijavi(it)
            try { it.webViewClient = MirujociKlient() } catch (_: Throwable) { }
            Log.i(TAG, "ustvarjen WebView: lahki pool")
        }
        (w.context as? MutableContextWrapper)?.baseContext = context
        lastnik = noviLastnik
        obOdvzemu = odvzemiStaremu
        try { w.onResume() } catch (_: Throwable) { }
        Log.i(TAG, if (obstojeci == null) "lahki WebView pripravljen" else "ponovno uporabljen WebView: lahki pool")
        return w
    }

    /** Nevidno opravilo pocaka/odstopi, kadar lahki predvajalnik ze uporablja edini pogled. */
    fun poskusiPridobiti(context: Context, noviLastnik: Any, odvzemiStaremu: () -> Unit): WebView? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView pool mora teci na glavni niti" }
        if (lastnik != null && lastnik !== noviLastnik) return null
        return pridobi(context, noviLastnik, odvzemiStaremu)
    }

    fun vrni(w: WebView, lastnikPogleda: Any, izvor: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            glavna.post { vrni(w, lastnikPogleda, izvor) }
            return
        }
        if (pogled !== w || lastnik !== lastnikPogleda) return
        pocisti(w, izvor)
        lastnik = null
        obOdvzemu = null
        (w.context as? MutableContextWrapper)?.baseContext = w.context.applicationContext
        glavna.postDelayed(unicenje, PROST_MS)
    }

    /** Mrtvega ali okvarjenega pogleda ne vracamo v pool. */
    fun zavrzi(w: WebView, lastnikPogleda: Any, razlog: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            glavna.post { zavrzi(w, lastnikPogleda, razlog) }
            return
        }
        if (pogled === w) pogled = null
        if (lastnik === lastnikPogleda) {
            lastnik = null
            obOdvzemu = null
        }
        glavna.removeCallbacks(unicenje)
        unicuj(w, razlog)
    }

    /** Pomnilniski pritisk vedno sprosti prost pogled; dejavnega ne podre sredi opravila. */
    fun sprostiProstega() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            glavna.post(::sprostiProstega)
            return
        }
        val w = pogled
        if (w != null && lastnik != null) return
        if (w != null) {
            pogled = null
            glavna.removeCallbacks(unicenje)
            unicuj(w, "onTrimMemory")
        }
        // Naloga 64: pomnilniski pritisk konca tudi izrisovalnik, ki je ostal brez WebView-jev.
        koncajIzrisovalnik("onTrimMemory")
    }

    private fun pocisti(w: WebView, naslov: String?) {
        try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Throwable) { }
        try { w.stopLoading() } catch (_: Throwable) { }
        try { w.onPause() } catch (_: Throwable) { }
        try { w.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null) } catch (_: Throwable) { }
        try { w.loadUrl(PRAZEN) } catch (_: Throwable) { }
        try { w.clearHistory() } catch (_: Throwable) { }
        try { w.clearFormData() } catch (_: Throwable) { }
        odstraniPodatkeIzvora(naslov)
        for (ime in arrayOf("SafeerBridge", "SafeerPdf", "SafeerLink")) {
            try { w.removeJavascriptInterface(ime) } catch (_: Throwable) { }
        }
        try { w.webChromeClient = WebChromeClient() } catch (_: Throwable) { }
        try { w.webViewClient = MirujociKlient() } catch (_: Throwable) { }
    }

    private fun odstraniPodatkeIzvora(naslov: String?) {
        val uri = try { Uri.parse(naslov) } catch (_: Throwable) { null } ?: return
        val shema = uri.scheme?.lowercase() ?: return
        val gostitelj = uri.host ?: return
        if (shema != "http" && shema != "https") return
        val vrata = uri.port
        val nestandardnaVrata = vrata >= 0 && !((shema == "http" && vrata == 80) || (shema == "https" && vrata == 443))
        val izvor = "$shema://$gostitelj" + if (nestandardnaVrata) ":$vrata" else ""
        try { WebStorage.getInstance().deleteOrigin(izvor) } catch (_: Throwable) { }
        try {
            val cm = CookieManager.getInstance()
            cm.getCookie(izvor).orEmpty().split(';').mapNotNull { del ->
                del.substringBefore('=').trim().takeIf { it.isNotEmpty() }
            }.distinct().forEach { ime ->
                cm.setCookie(izvor, "$ime=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
            cm.flush()
        } catch (_: Throwable) { }
    }

    private fun unicuj(w: WebView, razlog: String) {
        odjavi(w)
        try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Throwable) { }
        try { w.stopLoading() } catch (_: Throwable) { }
        try { w.destroy() } catch (_: Throwable) { }
        Log.i(TAG, "unicen WebView: $razlog")
    }
}
