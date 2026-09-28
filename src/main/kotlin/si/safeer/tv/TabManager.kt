package si.safeer.tv

import android.content.Context
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import java.util.UUID

data class TabModel(
    val id: String = UUID.randomUUID().toString(),
    var webView: ChromiumEngineView?,
    var title: String = "Google",
    var url: String = "https://www.google.com",
    var isDesktop: Boolean = false,
    var favicon: String = "🔍",
    /** Zavihek spi: stran je odlozena, pogled je null in ne zaseda pomnilnika. */
    var spi: Boolean = false,
    /** Naslov, ki ga ob prebuditvi nalozimo nazaj. */
    var spalniNaslov: String = "",
    /** Kje je uporabnik bral, ko je zavihek zaspal. */
    var spalniOdmik: Int = 0,
    /** Zavihek predvaja zvok ali sliko - tak ne sme zaspati. */
    var predvaja: Boolean = false,
    /** Obrazec vsebuje uporabnikovo se nepredano spremembo. */
    var umazanObrazec: Boolean = false,
    /** Poceni Chromiumovo stanje zgodovine za restoreState. */
    var shranjenoStanje: Bundle? = null,
    /** Naslov ikone strani; bitne slike ne drzimo v pomnilniku zavihka. */
    var faviconUrl: String = "",
    /**
     * Globoko spanje: stari pogled je unicen (izrisovalnik in graficni pomnilnik sta sproscena),
     * `webView` je null. Ob prebuditvi ustvarimo nov pogled in obnovimo stanje.
     */
    var pogledSvez: Boolean = false,
    /** Zavihek je nastal kot pojavno okno (prijava); Nazaj ga zapre, ne pelje na prazno stran. */
    var jePojavni: Boolean = false,
    /** Zavihek, ki je to okno odprl - tja se vrnemo, ko ga zapremo. */
    var odpiralec: String? = null
)

/**
 * Zavihki brskalnika na televizorju.
 *
 * Televizor ima okoli 2,7 GB pomnilnika, prostega pa le nekaj sto MB, in vsak odprt zavihek
 * drzi svojo stran v izrisovalniku. Zato zavihki v ozadju zaspijo: stran odlozimo, naslov in
 * mesto branja pa si zapomnimo in ju ob vrnitvi obnovimo. Uporabnik vidi le, da se stran ob
 * vrnitvi na hitro nalozi - ne vidi pa tega, da bi mu sistem brskalnik ubil.
 *
 * Aktivni zavihek in zavihek, ki predvaja, ne zaspita nikoli.
 */
class TabManager(
    private val container: FrameLayout,
    private val onTabsUpdated: (count: Int, activeTab: TabModel?) -> Unit
) {

    companion object {
        private const val TAG = "SafeerRam"
        private const val PRAZNA = "about:blank"
        /** V tem casu drugo sesutje iste strani stejemo za ponovitev. */
        private const val PONOVITEV_MS = 60_000L
        private const val ZAMRZNI_TV_MS = 2 * 60 * 1000L
        private const val ZAMRZNI_DRUGO_MS = 5 * 60 * 1000L
        private const val STANJE_ZAMRZNITVE_JS = """(function(){try{
          var igra=[].some.call(document.querySelectorAll('video,audio'),function(m){return !m.paused&&!m.ended&&m.readyState>1;});
          var umazan=[].some.call(document.querySelectorAll('input,textarea,select'),function(e){
            if(e.type==='password'||e.type==='file')return !!e.value;
            if(e.tagName==='SELECT'){var d=[].findIndex.call(e.options,function(o){return o.defaultSelected;});return e.selectedIndex!==(d<0?0:d);}
            if(e.type==='checkbox'||e.type==='radio')return e.checked!==e.defaultChecked;
            return e.value!==e.defaultValue;});
          return (igra?'1':'0')+'|'+(umazan?'1':'0');}catch(e){return '0|0';}})()"""
    }

    private val tabs = mutableListOf<TabModel>()
    private val glavna = Handler(Looper.getMainLooper())
    private val roki = HashMap<String, Runnable>()
    private var activeTabId: String? = null

    /** Zadnji zavihek, ki ga je uporabnik zapustil. */
    private var prejsnjiId: String? = null

    /** Zavihki po zadnji uporabi (najnovejsi prvi): budni ostanejo prvi, zapremo zadnjega. */
    private val nedavni = ArrayDeque<String>()

    /** Zadnja smrt izrisovalnika po zavihkih: naslov in cas. Proti zankam sesutja. */
    private val zadnjaSmrt = HashMap<String, Pair<String, Long>>()

    val count: Int get() = tabs.size

    private fun ustvariPogled(context: Context): ChromiumEngineView {
        val pogled = ChromiumEngineView(context)
        Log.i(TAG, "ustvarjen WebView: zavihek")
        pogled.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Ko sistemu zmanjka pomnilnika, ubije izrisovalnik strani. Brez tega bi z njim
        // padel cel brskalnik; tako pa zavihek samo obnovimo.
        pogled.naSmrtIzrisovalnika = { stari, sesul -> obnoviPoSmrti(stari, sesul) }
        return pogled
    }

    /** Naredi prostor za nov zavihek, kadar jih je ze toliko, kot jih dovoli [BrowserMemoryPolicy]. */
    private fun sprostiProstor() {
        val najvec = BrowserMemoryPolicy.za(container.context).najvecZavihkov
        while (tabs.size >= najvec) {
            // Zapremo najdlje neuporabljenega (ne aktivnega).
            val victim = tabs.filter { it.id != activeTabId }
                .maxByOrNull { nedavni.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                ?: tabs.firstOrNull() ?: break
            nedavni.remove(victim.id)
            val idx = tabs.indexOf(victim)
            roki.remove(victim.id)?.let(glavna::removeCallbacks)
            try { victim.webView?.destroy() } catch (_: Exception) {}
            Log.i(TAG, "zavrzen zavihek: ${victim.url.take(60)}")
            if (idx >= 0) tabs.removeAt(idx)
        }
    }

    /**
     * Pogled za pojavno okno, ki (se) ni zavihek. Dokler ne vemo, kam okno pelje, ga med
     * zavihke ne vpisemo - sicer bi oglasno okno na polnem brskalniku zaprlo enega od
     * uporabnikovih zavihkov, ceprav ga cez trenutek sami zavrzemo.
     */
    fun pripraviPojavni(context: Context): ChromiumEngineView {
        val pogled = ustvariPogled(context)
        // Pogled, ki ni v oknu, ne izvede naslova, ki mu ga stran nastavi naknadno
        // (window.open('about:blank'), nato location = ...). Zato ga pritrdimo takoj -
        // a skritega in velikosti 1x1, da uporabnik o njem ne ve nicesar.
        pogled.layoutParams = FrameLayout.LayoutParams(1, 1)
        pogled.visibility = android.view.View.INVISIBLE
        try { container.addView(pogled) } catch (e: Exception) {
            Log.w(TAG, "Pojavnega pogleda ni bilo mogoce pripeti: ${e.message}")
        }
        return pogled
    }

    /** Okno se je izkazalo za pravo (prijava): pogled sprejmemo med zavihke in ga pokazemo. */
    fun posvoji(pogled: ChromiumEngineView, naslov: String, odpiralec: String? = null): TabModel {
        sprostiProstor()
        pogled.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        pogled.visibility = android.view.View.VISIBLE
        val tab = TabModel(
            webView = pogled, title = "Nov zavihek", url = naslov,
            jePojavni = true, odpiralec = odpiralec
        )
        tabs.add(tab)
        switchTab(tab.id)
        return tab
    }

    /**
     * Zapre pojavni zavihek in se vrne na zavihek, ki ga je odprl. Pojavno okno nima
     * zgodovine, v katero bi se lahko vrnilo: Nazaj v njem pomeni "zapri to okno".
     */
    fun zapriPojavni(context: Context, tab: TabModel) {
        val nazaj = tab.odpiralec
        closeTab(context, tab.id)
        if (nazaj != null && tabs.any { it.id == nazaj }) switchTab(nazaj)
    }

    /** Okno je bilo oglas: pogled zavrzemo, zavihkov se nismo dotaknili. */
    fun zavrziPojavni(pogled: ChromiumEngineView) {
        try { (pogled.parent as? ViewGroup)?.removeView(pogled) } catch (_: Exception) {}
        try { pogled.destroy() } catch (e: Exception) {
            Log.w(TAG, "Pojavnega pogleda ni bilo mogoce pospraviti: ${e.message}")
        }
    }

    fun createTab(context: Context, url: String = "https://www.google.com", makeActive: Boolean = true): TabModel {
        sprostiProstor()
        val webView = ustvariPogled(context)

        val tab = TabModel(
            webView = webView,
            title = "Nov zavihek",
            url = url
        )

        tabs.add(tab)

        if (makeActive || tabs.size == 1) {
            switchTab(tab.id)
        }

        webView.loadUrl(url)
        notifyUpdated()
        return tab
    }

    fun switchTab(tabId: String) {
        val target = tabs.find { it.id == tabId } ?: return
        if (activeTabId != null && activeTabId != tabId) prejsnjiId = activeTabId
        activeTabId = tabId
        nedavni.remove(tabId); nedavni.addFirst(tabId)

        roki.remove(tabId)?.let(glavna::removeCallbacks)
        val ciljniPogled = zbudi(target)
        container.removeAllViews()
        if (ciljniPogled.parent != null) {
            (ciljniPogled.parent as? ViewGroup)?.removeView(ciljniPogled)
        }
        container.addView(ciljniPogled)
        strojniSloj(ciljniPogled, true)
        for (tab in tabs) {
            val w = tab.webView ?: continue
            try {
                if (tab.id == tabId) {
                    w.settings.mediaPlaybackRequiresUserGesture = false
                    w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
                    w.onResume()
                } else takojVOzadje(tab, w)
            } catch (_: Exception) {}
        }
        pospraviOzadje()

        notifyUpdated()
    }

    // ------------------------------------------------------------------ spanje zavihkov

    /** Vsak neaktivni zavihek dobi rok za zamrznitev; do takrat je le ustavljen. */
    private fun pospraviOzadje() {
        for (tab in tabs) {
            if (tab.id != activeTabId && !tab.spi) nacrtujZamrznitev(tab)
        }
    }

    /**
     * Uspava zavihke v ozadju. `vse` naj bo true, ko sistem javi pomanjkanje pomnilnika -
     * takrat ne obdrzimo niti zadnjega zapuscenega.
     */
    fun uspavajOzadje(vse: Boolean) {
        for (tab in tabs) {
            if (tab.id == activeTabId) continue
            if (vse) zamrzni(tab, prisilno = true) else nacrtujZamrznitev(tab)
        }
    }

    /**
     * Brskalnik ni vec na zaslonu (uporabnik je sel v drugo aplikacijo ali na domaci zaslon):
     * zavihki v ozadju zaspijo takoj, aktivni pa sele, ko klicatelj tako odloci (`tudiAktivni`,
     * po daljsem casu v ozadju). Zavihek, ki predvaja, ostane buden.
     * Stran se ob vrnitvi nalozi znova; Safeer Link in daljinec med tem delujeta naprej.
     */
    fun uspavajVOzadju(tudiAktivni: Boolean) {
        for (tab in tabs) {
            if (tab.id == activeTabId && !tudiAktivni) continue
            if (tab.id == activeTabId && tudiAktivni) zamrzni(tab, prisilno = false, dovoliAktivnega = true)
            else nacrtujZamrznitev(tab)
        }
    }

    private fun nacrtujZamrznitev(tab: TabModel, dovoliAktivnega: Boolean = false) {
        roki.remove(tab.id)?.let(glavna::removeCallbacks)
        val opravilo = Runnable { zamrzni(tab, prisilno = false, dovoliAktivnega = dovoliAktivnega) }
        roki[tab.id] = opravilo
        glavna.postDelayed(opravilo, casZamrznitve())
    }

    private fun casZamrznitve(): Long {
        val ui = container.context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val jeTv = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        return if (jeTv) ZAMRZNI_TV_MS else ZAMRZNI_DRUGO_MS
    }

    /** Takojsnja varcna nastavitev neaktivnega pogleda brez globalnega pauseTimers. */
    private fun takojVOzadje(tab: TabModel, w: ChromiumEngineView) {
        w.onPause()
        w.settings.mediaPlaybackRequiresUserGesture = true
        strojniSloj(w, false)
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        w.evaluateJavascript(STANJE_ZAMRZNITVE_JS) { odgovor ->
            val deli = odgovor.orEmpty().replace("\"", "").split('|')
            tab.predvaja = deli.firstOrNull() == "1"
            tab.umazanObrazec = deli.getOrNull(1) == "1"
        }
    }

    private fun zamrzni(tab: TabModel, prisilno: Boolean, dovoliAktivnega: Boolean = false) {
        roki.remove(tab.id)?.let(glavna::removeCallbacks)
        val w = tab.webView ?: return
        if (!prisilno && tab.id == activeTabId && !dovoliAktivnega) return
        if (!prisilno) {
            w.evaluateJavascript(STANJE_ZAMRZNITVE_JS) { odgovor ->
                val deli = odgovor.orEmpty().replace("\"", "").split('|')
                tab.predvaja = deli.firstOrNull() == "1"
                tab.umazanObrazec = deli.getOrNull(1) == "1"
                if (tab.predvaja || tab.umazanObrazec) nacrtujZamrznitev(tab, dovoliAktivnega)
                else zamrzniPogled(tab, w, dovoliAktivnega)
            }
        } else {
            zamrzniPogled(tab, w, dovoliAktivnega)
        }
    }

    private fun zamrzniPogled(tab: TabModel, w: ChromiumEngineView, dovoliAktivnega: Boolean = false) {
        if (tab.webView !== w || (tab.id == activeTabId && !dovoliAktivnega)) return
        val naslov = (w.url ?: tab.url).trim().ifBlank { tab.url }
        tab.spalniNaslov = naslov
        tab.spalniOdmik = try { w.scrollY } catch (_: Exception) { 0 }
        tab.title = w.title?.takeIf { it.isNotBlank() } ?: tab.title
        tab.faviconUrl = try {
            val u = android.net.Uri.parse(naslov)
            if (u.scheme in listOf("http", "https") && u.host != null) "${u.scheme}://${u.host}/favicon.ico" else ""
        } catch (_: Exception) { "" }
        tab.shranjenoStanje = Bundle().also { stanje ->
            try { w.saveState(stanje) } catch (_: Exception) { stanje.clear() }
        }.takeIf { !it.isEmpty }
        tab.spi = true
        tab.pogledSvez = true
        tab.webView = null
        try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Exception) { }
        try { w.stopLoading(); w.onPause(); w.destroy() } catch (e: Exception) {
            Log.w(TAG, "Pogleda ni bilo mogoce uniciti: ${e.message}")
        }
        Log.i(TAG, "zamrznjen zavihek: ${naslov.take(60)}")
        Log.i(TAG, "unicen WebView: zamrznjen zavihek")
    }

    /** Ob vrnitvi na zaslon: aktivni zavihek, ki je zaspal v ozadju, se nalozi znova. */
    fun zbudiAktivnega() {
        val tab = tabs.find { it.id == activeTabId } ?: return
        if (!tab.spi) return
        val w = zbudi(tab)
        if (w.parent == null) container.addView(w)
        strojniSloj(w, true)
        // Nov pogled se ni imel fokusa: brez tega bi daljinec po vrnitvi krmilil orodno vrstico.
        try { w.requestFocus() } catch (_: Exception) {}
        notifyUpdated()
    }

    /** Ali kateri zavihek spi (za dnevnik in meritve). */
    fun steviloSpecih(): Int = tabs.count { it.spi }

    private fun zbudi(tab: TabModel): ChromiumEngineView {
        tab.webView?.let { return it }
        tab.spi = false
        val naslov = tab.spalniNaslov
        val odmik = tab.spalniOdmik
        val w = ustvariPogled(container.context)
        tab.webView = w
        tab.pogledSvez = false
        try {
            w.isDesktopMode = tab.isDesktop
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            w.onResume()
            val obnovljeno = tab.shranjenoStanje?.let { w.restoreState(it) } != null
            if (!obnovljeno && naslov.isNotBlank()) w.loadUrl(naslov)
            if (odmik > 0) {
                // Mesto branja obnovimo, ko je stran ze na zaslonu.
                w.postDelayed({
                    try { w.scrollTo(0, odmik) } catch (_: Exception) {}
                }, 900)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zavihka ni bilo mogoce prebuditi: ${e.message}")
        }
        Log.i(TAG, "obnovljen zavihek: ${naslov.take(60)}")
        return w
    }

    /**
     * Strojni sloj je hiter, a stane cel zaslon grafike. Ima ga naj samo zavihek, ki je na
     * zaslonu; drugim ga vzamemo in ga ob vrnitvi vrnemo.
     */
    private fun strojniSloj(pogled: ChromiumEngineView, vklopljen: Boolean) {
        try {
            pogled.setLayerType(
                if (vklopljen) android.view.View.LAYER_TYPE_HARDWARE else android.view.View.LAYER_TYPE_NONE,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Sloja ni bilo mogoce spremeniti: ${e.message}")
        }
    }

    /**
     * Ob hudi stiski s pomnilnikom sprostimo se predpomnilnik strani v pomnilniku (slike in
     * viri se po potrebi preberejo z diska). Vsebine, ki jo uporabnik gleda, to ne pokvari.
     */
    fun sprostiPredpomnilnike() {
        for (tab in tabs) {
            try { tab.webView?.clearCache(false) } catch (_: Exception) {}
        }
        Log.i(TAG, "Predpomnilniki strani sproscen")
    }

    /** Ali kateri zavihek trenutno predvaja; tak ne zaspi. */
    fun oznaciPredvajanje(tabId: String?, predvaja: Boolean) {
        for (tab in tabs) if (tab.id == tabId) tab.predvaja = predvaja
    }

    // ------------------------------------------------------------------ smrt izrisovalnika

    /**
     * Izrisovalnik strani je umrl (sistemu je zmanjkalo pomnilnika ali pa se je stran sesula).
     * Tak pogled je po Androidovih pravilih neuporaben: zavrzemo ga in naredimo novega.
     * Aktivni zavihek stran znova nalozi, zavihek v ozadju pa gre spat - do tja, kjer je bil.
     */
    private fun obnoviPoSmrti(stari: ChromiumEngineView, sesul: Boolean) {
        val idx = tabs.indexOfFirst { it.webView === stari }
        if (idx < 0) {
            try { stari.destroy() } catch (_: Exception) {}
            return
        }
        val tab = tabs[idx]
        val naslov = when {
            tab.spi && tab.spalniNaslov.isNotBlank() -> tab.spalniNaslov
            !(stari.url ?: "").isNullOrBlank() && stari.url != PRAZNA -> stari.url!!
            else -> tab.url
        }
        val bilAktiven = tab.id == activeTabId

        // Ce ista stran podre izrisovalnik dvakrat zapored, je ne nalagamo znova: sicer se
        // brskalnik vrti v krogu sesutij. Namesto nje odpremo domaco stran.
        val zdaj = System.currentTimeMillis()
        val prej = zadnjaSmrt[tab.id]
        val ponovitev = prej != null && prej.first == naslov && (zdaj - prej.second) < PONOVITEV_MS
        zadnjaSmrt[tab.id] = naslov to zdaj
        val zaNalozit = if (ponovitev) SpletDomaca.naslov(container.context) else naslov

        Log.w(TAG, "Izrisovalnik je umrl (${if (sesul) "sesutje" else "sistem je sprostil pomnilnik"}); " +
            (if (ponovitev) "stran se je sesula dvakrat, odpiram domaco stran: "
             else "obnavljam zavihek ") + naslov.take(60))

        try { (stari.parent as? ViewGroup)?.removeView(stari) } catch (_: Exception) {}
        try { stari.destroy() } catch (_: Exception) {}

        tab.url = zaNalozit

        if (bilAktiven) {
            val nov = ustvariPogled(container.context)
            tab.webView = nov
            container.removeAllViews()
            container.addView(nov)
            tab.spi = false
            nov.loadUrl(zaNalozit)
            Log.i(TAG, "obnovljen zavihek po smrti rendererja: ${zaNalozit.take(60)}")
        } else {
            // Zavihka v ozadju ne budimo: naj pocaka, da ga uporabnik res zeli.
            tab.webView = null
            tab.spi = true
            tab.pogledSvez = true
            tab.spalniNaslov = zaNalozit
            Log.i(TAG, "zamrznjen zavihek po smrti rendererja: ${zaNalozit.take(60)}")
        }
        notifyUpdated()
    }

    // ------------------------------------------------------------------ ostalo

    fun closeTab(context: Context, tabId: String) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx == -1) return

        val tabToClose = tabs[idx]
        roki.remove(tabId)?.let(glavna::removeCallbacks)
        tabToClose.webView?.destroy()
        Log.i(TAG, "zavrzen zavihek: ${tabToClose.url.take(60)}")
        tabs.removeAt(idx)
        nedavni.remove(tabId)
        if (prejsnjiId == tabId) prejsnjiId = null

        if (tabs.isEmpty()) {
            createTab(context, SpletDomaca.naslov(context), true)
        } else if (activeTabId == tabId) {
            val nextIdx = if (idx < tabs.size) idx else tabs.size - 1
            switchTab(tabs[nextIdx].id)
        } else {
            notifyUpdated()
        }
    }

    fun closeAllTabs(context: Context) {
        for (tab in tabs) {
            roki.remove(tab.id)?.let(glavna::removeCallbacks)
            tab.webView?.destroy()
            Log.i(TAG, "zavrzen zavihek: ${tab.url.take(60)}")
        }
        tabs.clear()
        nedavni.clear()
        prejsnjiId = null
        container.removeAllViews()
        createTab(context, SpletDomaca.naslov(context), true)
    }

    fun getActiveTab(): TabModel? {
        return tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    }

    fun getAllTabs(): List<TabModel> = tabs.toList()

    fun switchToNextTab() {
        if (tabs.size <= 1) return
        val curIdx = tabs.indexOfFirst { it.id == activeTabId }
        if (curIdx != -1) {
            val nextIdx = (curIdx + 1) % tabs.size
            switchTab(tabs[nextIdx].id)
        }
    }

    fun switchToPrevTab() {
        if (tabs.size <= 1) return
        val curIdx = tabs.indexOfFirst { it.id == activeTabId }
        if (curIdx != -1) {
            val prevIdx = if (curIdx - 1 < 0) tabs.size - 1 else curIdx - 1
            switchTab(tabs[prevIdx].id)
        }
    }

    private fun notifyUpdated() {
        onTabsUpdated(tabs.size, getActiveTab())
    }
}
