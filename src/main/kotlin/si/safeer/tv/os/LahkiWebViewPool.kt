package si.safeer.tv.os

import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
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
    private const val PROST_MS = 60_000L
    private val glavna = Handler(Looper.getMainLooper())

    private var pogled: WebView? = null
    private var lastnik: Any? = null
    private var obOdvzemu: (() -> Unit)? = null

    private val unicenje = Runnable {
        val w = pogled ?: return@Runnable
        if (lastnik != null) return@Runnable
        pogled = null
        unicuj(w, "lahki pool je neaktiven")
    }

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
        val w = pogled ?: return
        if (lastnik != null) return
        pogled = null
        glavna.removeCallbacks(unicenje)
        unicuj(w, "onTrimMemory")
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
        try { w.webViewClient = WebViewClient() } catch (_: Throwable) { }
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
        try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Throwable) { }
        try { w.stopLoading() } catch (_: Throwable) { }
        try { w.destroy() } catch (_: Throwable) { }
        Log.i(TAG, "unicen WebView: $razlog")
    }
}
