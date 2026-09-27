package si.safeer.tv.os

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import com.google.common.util.concurrent.Futures
import si.safeer.tv.AdBlockEngine
import si.safeer.tv.ChromiumEngineView
import java.io.ByteArrayInputStream
import java.net.URL

/**
 * Varcna druga stopnja spletnega predvajanja. Uporabi sistemski WebView brez ovoja brskalnika,
 * JavaScript vklopi sele po prvem nalaganju in pogled opusti takoj, ko lahko tok prevzame Media3.
 */
class LahkaStran private constructor(
    ctx: Context,
    sk: Jamendo.Skladba,
    private val dovoliPrevzem: Boolean,
    private val obToku: (Jamendo.Skladba) -> Unit,
    private val obPripravi: (LahkaStran) -> Unit,
    private val obNeuspehu: (LahkaStran) -> Unit,
    private val najem: Najem
) : SpletniIgralec(sk, najem.pogled, false, null) {

    constructor(
        ctx: Context,
        sk: Jamendo.Skladba,
        dovoliPrevzem: Boolean,
        obToku: (Jamendo.Skladba) -> Unit,
        obPripravi: (LahkaStran) -> Unit,
        obNeuspehu: (LahkaStran) -> Unit
    ) : this(ctx, sk, dovoliPrevzem, obToku, obPripravi, obNeuspehu, najemi(ctx))

    private val glavna = Handler(Looper.getMainLooper())
    private val zacetniIzvor = izvor(sk.povezava)
    private var jsPonovitev = false
    private var predano = false
    private var koncano = false
    private var mrtevIzrisovalnik = false
    private var najemOdvzet = false
    private var trenutnaStran = sk.povezava
    private val rok = Runnable { if (!predano && !koncano) neuspeh() }
    private var zacetniSkript: ScriptHandler? = null

    init {
        najem.obOdvzemu = {
            if (!sproscen) {
                najemOdvzet = true
                koncano = true
                sproscen = true
                glavna.removeCallbacksAndMessages(null)
                ura.removeCallbacksAndMessages(null)
                try { zacetniSkript?.remove() } catch (_: Exception) { }
                zacetniSkript = null
                obNeuspehu(this)
            }
        }
        nastaviVarcno(pogled, false, true)
        pogled.resumeTimers()
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                zacetniSkript = WebViewCompat.addDocumentStartJavaScript(pogled, SpletniIgralec.POSREDNIK_JS, setOf("*"))
            }
        } catch (_: Exception) { }
        pogled.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                trenutnaStran = url ?: trenutnaStran
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (koncano || view == null) return
                trenutnaStran = url ?: trenutnaStran
                // Prva omrezna pot je bila brez JavaScripta. Vklop je potreben le za vpogled v DOM
                // in, ce je stran prazna, za eno ponovno nalaganje dinamicne strani.
                if (!view.settings.javaScriptEnabled) view.settings.javaScriptEnabled = true
                preveriDom(prvoNalaganju = !jsPonovitev)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val r = request ?: return true
                val shema = r.url.scheme?.lowercase().orEmpty()
                if (shema !in DOVOLJENE_SHEME) return true
                if (!r.isForMainFrame) return false
                return izvor(r.url.toString()) != zacetniIzvor
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return prazno()
                val naslov = r.url.toString()
                val shema = r.url.scheme?.lowercase().orEmpty()
                if (shema !in DOVOLJENE_SHEME) return prazno()
                val accept = r.requestHeaders["Accept"].orEmpty().lowercase()
                if (dovoliPrevzem && r.method.equals("GET", true) && shema in setOf("http", "https")) {
                    val mime = SpletniVir.vrstaToka(naslov)
                    val video = accept.startsWith("video/") && !jeDelToka(naslov)
                    if ((mime != null || video) && !AdBlockEngine.shouldBlockUrl(naslov)) {
                        glavna.post { prevzemiTok(naslov, mime ?: accept.substringBefore(',')) }
                        return null
                    }
                }
                if (jeSlikaAliPisava(naslov, accept)) return prazno()
                return AdBlockEngine.handleIntercept(naslov, trenutnaStran, accept, r.isForMainFrame)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                mrtevIzrisovalnik = true
                glavna.post { neuspeh() }
                return true
            }
        }
        pogled.loadUrl(sk.povezava)
        glavna.postDelayed(rok, 12_000)
    }

    private fun preveriDom(prvoNalaganju: Boolean) {
        if (koncano) return
        pogled.evaluateJavascript("(function(){return !!document.querySelector('video,iframe');})()") { odgovor ->
            if (koncano) return@evaluateJavascript
            if (odgovor == "true") {
                pogled.settings.domStorageEnabled = true
                pripraviNalozenoStran()
                if (!predano) {
                    predano = true
                    glavna.removeCallbacks(rok)
                    obPripravi(this)
                }
            } else if (prvoNalaganju && !jsPonovitev) {
                jsPonovitev = true
                pogled.settings.domStorageEnabled = true
                pogled.reload()
            } else {
                glavna.postDelayed({ preveriDom(false) }, 500)
            }
        }
    }

    private fun prevzemiTok(naslov: String, mime: String) {
        if (koncano) return
        koncano = true
        glavna.removeCallbacks(rok)
        SpletniVir.zapomniGlave(naslov, sk.povezava)
        // WebView mora izginiti pred pripravo Media3, da na televizorju ne ostaneta dva izrisovalnika.
        release()
        obToku(SpletniVir.razresenaSkladba(sk, naslov, mime))
    }

    private fun neuspeh() {
        if (koncano) return
        koncano = true
        glavna.removeCallbacks(rok)
        release()
        obNeuspehu(this)
    }

    override fun handleRelease(): com.google.common.util.concurrent.ListenableFuture<*> {
        if (sproscen || najemOdvzet) return Futures.immediateVoidFuture()
        koncano = true
        sproscen = true
        glavna.removeCallbacksAndMessages(null)
        ura.removeCallbacksAndMessages(null)
        try { zacetniSkript?.remove() } catch (_: Exception) { }
        zacetniSkript = null
        if (mrtevIzrisovalnik) {
            try { (pogled.parent as? android.view.ViewGroup)?.removeView(pogled) } catch (_: Exception) { }
            LahkiWebViewPool.zavrzi(pogled, najem.lastnik, "renderer LahkaStran je umrl")
        } else {
            skrij()
            LahkiWebViewPool.vrni(pogled, najem.lastnik, trenutnaStran)
        }
        return Futures.immediateVoidFuture()
    }

    companion object {
        private data class Najem(val lastnik: Any, val pogled: WebView, var obOdvzemu: (() -> Unit)? = null)

        private fun najemi(ctx: Context): Najem {
            val lastnik = Any()
            val ref = arrayOfNulls<Najem>(1)
            val pogled = LahkiWebViewPool.pridobi(ctx, lastnik) { ref[0]?.obOdvzemu?.invoke() }
            return Najem(lastnik, pogled).also { ref[0] = it }
        }

        private val DOVOLJENE_SHEME = setOf("http", "https", "blob", "data")

        @SuppressLint("SetJavaScriptEnabled")
        internal fun nastaviVarcno(view: WebView, javaScript: Boolean, neviden: Boolean) {
            view.settings.apply {
                javaScriptEnabled = javaScript
                domStorageEnabled = javaScript
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
                userAgentString = ChromiumEngineView.MOBILE_USER_AGENT
                if (Build.VERSION.SDK_INT >= 23) offscreenPreRaster = false
            }
            view.isFocusable = !neviden
            if (neviden) {
                view.alpha = 0f
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            if (Build.VERSION.SDK_INT >= 26 && neviden) {
                view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
            }
        }

        internal fun varcniOdgovor(request: WebResourceRequest, stran: String?): WebResourceResponse? {
            val naslov = request.url.toString()
            val shema = request.url.scheme?.lowercase().orEmpty()
            if (shema !in DOVOLJENE_SHEME) return prazno()
            val accept = request.requestHeaders["Accept"].orEmpty().lowercase()
            if (jeSlikaAliPisava(naslov, accept)) return prazno()
            return AdBlockEngine.handleIntercept(naslov, stran, accept, request.isForMainFrame)
        }

        private fun jeSlikaAliPisava(url: String, accept: String): Boolean {
            if (accept.startsWith("image/") || accept.contains("font/")) return true
            val pot = url.lowercase().substringBefore('?')
            return listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg", ".ico",
                ".woff", ".woff2", ".ttf", ".otf", ".eot").any(pot::endsWith)
        }

        private fun jeDelToka(url: String): Boolean {
            val pot = url.lowercase().substringBefore('?')
            return listOf(".ts", ".m4s", ".cmfv", ".cmfa").any(pot::endsWith)
        }

        private fun izvor(url: String): String = try {
            URL(url).let { "${it.protocol.lowercase()}://${it.host.lowercase()}:${if (it.port >= 0) it.port else it.defaultPort}" }
        } catch (_: Exception) { "" }

        private fun prazno() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }
}
