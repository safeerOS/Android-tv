package si.safeer.tv.os

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import si.safeer.tv.R

/**
 * Spletna storitev iz Sporocil (WhatsApp, Telegram, Gmail ... - katalog po vzoru Ferdium, lastnik 27. 10. 2026) v
 * celem oknu. Na vseh napravah enako, tudi na televizorju (tipkovnica in miska; uporabniki brez racunalnika).
 *  - Vsaka storitev ima svoj profil WebViewa (piskotki, shramba), ce ga naprava podpira (MULTI_PROFILE), zato sta lahko
 *    hkrati prijavljena dva racuna iste storitve; odstranitev kanala profil izbrise.
 *  - Stevilo neprebranih iz naslova strani (SpletniKlepetiPravila.neprebrano) gre v stanje kanala - znacka v Sporocilih.
 *  - Nobene kode za posamezno stran: samo naslov iz kataloga (CONTRIBUTING.md »brez receptov za strani«).
 *  - Brez kamere in mikrofona (zahteve strani zavrnemo); nalaganje datotek prek sistemskega izbirnika.
 */
class SpletniKlepetActivity : OsActivity() {
    private lateinit var shramba: SporocilaShramba
    private var splet: WebView? = null
    private lateinit var naslovGlave: TextView
    private lateinit var napredek: ProgressBar
    private lateinit var namiznaGumb: TextView
    private lateinit var vsebina: FrameLayout
    private var kanal: SporocilaShramba.Kanal? = null
    private var namizna = false
    private var mobilniUa = ""
    private var zadnjeNeprebrano = -1
    private var izbirnik: ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shramba = SporocilaShramba(this)
        val k = shramba.kanali().firstOrNull { it.id == intent.getStringExtra(EXTRA_KANAL) && it.vrsta == SpletniKlepetiPravila.VRSTA }
        val url = k?.nastavitve?.optString("url").orEmpty()
        val varen = try { SpletniKlepetiPravila.preveriNaslov(url) } catch (_: SpletniKlepetiPravila.NeveljavenNaslov) { null }
        if (k == null || varen == null) { finish(); return }
        kanal = k
        val siroko = resources.configuration.screenWidthDp >= 720
        namizna = if (k.nastavitve.has("namizna")) k.nastavitve.optBoolean("namizna") else SpletniKlepetiPravila.namiznaPrivzeto(siroko)
        setContentView(zgradi(k))
        val w = ustvariSplet(k) ?: run { Toast.makeText(this, R.string.os_spor_splet_ni_webview, Toast.LENGTH_LONG).show(); finish(); return }
        if (savedInstanceState != null) w.restoreState(savedInstanceState)
        if (w.url == null) w.loadUrl(varen)
    }

    private fun zgradi(k: SporocilaShramba.Kanal): View {
        val koren = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(osBarva(R.color.os_ozadje)) }
        fun gumb(besedilo: String, opis: String, ob: () -> Unit) = TextView(this).apply {
            text = besedilo; contentDescription = opis; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setTextColor(osBarva(R.color.os_besedilo)); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(8), dp(14), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
            setOnClickListener { ob() }
        }
        val glava = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        glava.addView(gumb("‹ " + getString(R.string.os_spor_nazaj), getString(R.string.os_spor_nazaj)) { finish() })
        naslovGlave = TextView(this).apply {
            text = k.ime; setTextColor(osBarva(R.color.os_besedilo)); textSize = 16f; maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(12), 0, dp(12), 0)
        }
        glava.addView(naslovGlave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        namiznaGumb = gumb(namiznaBesedilo(), getString(R.string.os_spor_splet_namizna)) { preklopiNamizno() }
        glava.addView(namiznaGumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        glava.addView(gumb("⟳", getString(R.string.os_spor_splet_osvezi)) { splet?.reload() })
        koren.addView(glava)
        napredek = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        koren.addView(napredek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        vsebina = FrameLayout(this)
        koren.addView(vsebina,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return koren
    }

    private fun namiznaBesedilo() = getString(if (namizna) R.string.os_spor_splet_mobilna else R.string.os_spor_splet_namizna)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ustvariSplet(k: SporocilaShramba.Kanal): WebView? {
        val w = try { WebView(this) } catch (e: Throwable) { Log.w(TAG, "WebView: ${e.javaClass.simpleName}"); return null }
        // Locen profil na storitev (piskotki, shramba, predpomnilnik) - mora biti nastavljen pred prvim nalaganjem.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(SpletniKlepetiPravila.profil(k.id))
                WebViewCompat.setProfile(w, SpletniKlepetiPravila.profil(k.id))
            } catch (e: Throwable) { Log.w(TAG, "profil: ${e.javaClass.simpleName}") }
        }
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (android.os.Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true
        mobilniUa = s.userAgentString.orEmpty()
        nastaviUa(s)
        // Prijava z Googlom ali Microsoftom (Gmail, Teams ...) rabi piskotke tretjih strani znotraj okna storitve.
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, zahteva: WebResourceRequest): Boolean {
                val u = zahteva.url?.toString().orEmpty()
                if (SpletniKlepetiPravila.ostaneVOknu(u)) return false
                if (SpletniKlepetiPravila.zaSistem(u)) {
                    try {
                        val namera = if (u.startsWith("intent:", ignoreCase = true))
                            Intent.parseUri(u, Intent.URI_INTENT_SCHEME).apply { addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null }
                        else Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        startActivity(namera)
                    } catch (_: Throwable) { }
                }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { napredek.visibility = View.VISIBLE }
            override fun onPageFinished(view: WebView, url: String?) { napredek.visibility = View.GONE; CookieManager.getInstance().flush() }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // Izrisovalnik je ugasnil (malo pomnilnika): zapremo okno, ne aplikacije.
                odstraniSplet(); finish(); return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, naslov: String?) { zapomniNeprebrano(SpletniKlepetiPravila.neprebrano(naslov)) }
            override fun onProgressChanged(view: WebView, napredekStrani: Int) { napredek.progress = napredekStrani }
            override fun onPermissionRequest(zahteva: PermissionRequest) { zahteva.deny() }
            override fun onShowFileChooser(view: WebView, povratek: ValueCallback<Array<Uri>>, parametri: FileChooserParams): Boolean {
                izbirnik?.onReceiveValue(null)
                izbirnik = povratek
                return try { startActivityForResult(parametri.createIntent(), IZBIRA_DATOTEKE); true }
                catch (_: Throwable) { izbirnik = null; false }
            }
        }
        w.setDownloadListener { url, _, _, _, _ ->
            // Prenos odpre sistem (brskalnik ali upravitelj prenosov) - samo https.
            if (url.startsWith("https://", ignoreCase = true)) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) { }
        }
        vsebina.addView(w, FrameLayout.LayoutParams(-1, -1))
        LahkiWebViewPool.prijavi(w)
        splet = w
        w.requestFocus()
        return w
    }

    private fun nastaviUa(s: WebSettings) {
        s.userAgentString = if (namizna) SpletniKlepetiPravila.namizniUa(mobilniUa) else mobilniUa
        s.useWideViewPort = namizna
        s.loadWithOverviewMode = namizna
    }

    private fun preklopiNamizno() {
        val k = kanal ?: return
        namizna = !namizna
        k.nastavitve.put("namizna", namizna)
        try { shramba.posodobiNastavitve(k.id, k.nastavitve) } catch (_: Throwable) { }
        namiznaGumb.text = namiznaBesedilo()
        splet?.let { nastaviUa(it.settings); it.reload() }
    }

    private fun zapomniNeprebrano(n: Int) {
        val k = kanal ?: return
        if (n == zadnjeNeprebrano) return
        zadnjeNeprebrano = n
        naslovGlave.text = if (n > 0) "${k.ime} ($n)" else k.ime
        try {
            val st = shramba.stanjeKanala(k.id)
            if (st.optInt("neprebrano", -1) != n) shramba.shraniStanjeKanala(k.id, st.put("neprebrano", n))
        } catch (_: Throwable) { }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == IZBIRA_DATOTEKE) {
            izbirnik?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            izbirnik = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        splet?.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val w = splet
        if (keyCode == KeyEvent.KEYCODE_BACK && w != null && w.canGoBack()) { w.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { splet?.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onResume() { super.onResume(); splet?.onResume() }

    private fun odstraniSplet() {
        val w = splet ?: return
        splet = null
        LahkiWebViewPool.odjavi(w)
        (w.parent as? ViewGroup)?.removeView(w)
        try { w.destroy() } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        izbirnik?.onReceiveValue(null); izbirnik = null
        odstraniSplet()
        if (::shramba.isInitialized) shramba.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KANAL = "kanal"
        private const val IZBIRA_DATOTEKE = 4711
        private const val TAG = "SafeerSporocilaSplet"

        fun odpri(a: android.app.Activity, kanalId: String) {
            a.startActivity(Intent(a, SpletniKlepetActivity::class.java).putExtra(EXTRA_KANAL, kanalId))
        }

        /** Odstranjen kanal: izbrise tudi profil (piskotke, prijavo) - ce ga noben odprt WebView ne uporablja. */
        fun pozabiProfil(kanalId: String) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            try { ProfileStore.getInstance().deleteProfile(SpletniKlepetiPravila.profil(kanalId)) } catch (_: Throwable) { }
        }
    }
}
