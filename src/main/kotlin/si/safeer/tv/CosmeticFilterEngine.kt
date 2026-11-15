package si.safeer.tv

/**
 * 🎨 CosmeticFilterEngine
 * Generira in injicira EasyList CSS element hiding pravila v spletne strani za popolno odstranitev praznih oglasnih okvirjev,
 * lažnih gumbov za prenos (Fake Download Buttons), oglasnih srcdoc okvirjev in lebdečih promocijskih pripomočkov (Reward Zone / Floating Popups).
 */
object CosmeticFilterEngine {

    var isEnabled: Boolean = true

    // Splošna EasyList pravila za skrivanje oglasnih elementov
    private val GENERIC_ELEMENT_HIDING_RULES = listOf(
        // Google Ads & DFP
        ".adsbygoogle", "[id^='google_ads']", "[id^='div-gpt-ad']", "[class*='google-ad']",
        ".a4bIc-ad", ".commercial-unit", ".ad-slot", ".ad-container", ".ad-wrapper",
        
        // Sponzorirane vsebine & Native Ads (Taboola, Outbrain)
        ".taboola", ".outbrain", ".trc_rbox_container", "[data-ad]", "[data-ad-unit]",
        ".sponsored-post", ".sponsored-content", ".promoted-tweet", ".promoted-post",
        
        // Popunder, Banner, In-Page Push & Floating Ad elementi
        ".pop-under", ".ad-banner", ".banner-ad", ".sticky-ad", ".floating-banner",
        ".monetag-banner", ".richpush-banner", ".interstitial-ad",
        "[class*='reward-zone']", "[id*='reward-zone']", "[class*='floating-ad']",
        "[class*='gamify-ad']", "[class*='coin-chest']", "[class*='ad-floating']",
        
        // Lažni gumbi za prenos (Fake Download Ads)
        "[class*='fake-download']", "[id*='fake-download']", ".download-button-ad",
        "div[class*='download-arrow']",
        
        // YouTube elementi & samodejni vklop zvoka (skrit mute gumb & popolnoma odstranjen Odpri aplikacijo gumb)
        ".ytp-ad-overlay-container", ".ytp-ad-message-container", ".ytp-ad-text",
        ".ytd-ad-slot-renderer", "ytd-in-feed-ad-layout-renderer", "ytd-banner-promo-renderer",
        "ytd-player-legacy-desktop-watch-ads-renderer", ".ytd-display-ad-renderer",
        ".ytp-unmute", ".ytp-unmute-inner", ".ytp-unmute-animated", ".ytp-unmute-box", "button[aria-label*='Vklopite zvok']", "button[aria-label*='Unmute']",
        "ytm-open-app-button", "ytm-app-promo-renderer", "ytm-mealbar-promo-renderer", "ytm-upsell-dialog-renderer",
        "button[aria-label*='Odpri aplikacijo']", "button[aria-label*='Odpri v aplikaciji']", "button[aria-label*='Odpri']",
        "button[aria-label*='Open app']", "button[aria-label*='Open in app']",
        ".topbar-action-buttons", ".topbar-action-buttons ytm-open-app-button", ".topbar-action-buttons button",
        "ytm-mobile-topbar-renderer .topbar-action-buttons", "ytm-mobile-topbar-renderer ytm-open-app-button",
        "ytm-mobile-topbar-renderer [aria-label*='Odpri']", "ytm-mobile-topbar-renderer [aria-label*='Open']",
        "a[href*='app_redirect']", "a[href*='open_in_app']", "button[title*='Odpri']", "button[title*='Open']",

        // 🎵 YouTube Mix / Playlist Obstructing Popup Overlays, Autonav & Dimming Backdrops (Bugfix)
        ".engagement-panel-backdrop", "ytm-bottom-sheet-renderer", "ytm-bottom-sheet-renderer.backdrop",
        "ytm-playlist-panel-renderer", "ytm-macro-markers-list-item-renderer", "ytm-chapter-renderer",
        "ytm-engagement-panel-section-list-renderer[target-id='engagement-panel-playlist-panel']",
        "ytm-engagement-panel-section-list-renderer .engagement-panel-header-container",
        "ytm-autonav-bar-renderer", ".ytm-autonav-bar", "ytm-autonav-toggle",
        ".ytp-pause-overlay", ".ytp-pause-overlay-container",
        "ytm-fullscreen-engagement-overlay-renderer", "ytm-companion-ad-renderer",
        "ytm-promoted-sparkles-web-renderer", "ytm-paid-content-overlay-renderer",

        // 🎬 In-Video Preroll & Streaming Ad Overlays (HTML5, Casino)
        // Brez pravil za notranjost predvajalnika: predvajalnik si stanje oglasa zapise kot razred na svojem glavnem
        // vsebniku, zato je pravilo div[class*='mgp_ad'] ob pripravljenem oglasu skrilo cel predvajalnik (4. 10. 2026).
        ".adBlockContainer", "div[class*='adOverlay']", "div[class*='adSkip']",
        "a[class*='adLink']", "div[class*='adInformation']", ".adInformation",
        ".removeAds", "a[href*='casino']", ".topAd", ".bottomAd",
        ".wideBanner", ".underPlayerAd", ".commercial-unit", ".ad-zone", ".player-ad",
        "[class*='ad-banner']", "[class*='player-advertisement']", "[id*='player-advertisement']",
        ".ad-banner-overlay", ".jw-ad-container", ".plyr__ad", ".vjs-ad", ".video-ad-overlay",
        
        // AdBlock opozorila in prekrivna okna
        ".fc-ab-root", ".adblock-overlay", "#adblock-modal", ".ad-block-warning"
    )

    /**
     * Prazna mesta oglasnih omrezij, ki jih blokiramo ze v omrezju ([AdBlockEngine]): skripta omrezja se ne nalozi,
     * mesto pa ostane - prazen okvir s fiksno sirino, ki je na telefonu sirsi od zaslona in razsiri vso stran.
     * Skrijemo jih samo, kadar je blokiranje vklopljeno (sicer je v njih oglas, ki ga je uporabnik dovolil).
     */
    internal val PRAZNA_OGLASNA_MESTA = listOf("ins.adsbytrafficjunky")

    /**
     * Lastnik 15. 11. 2026 (24ur.com: »Lidl kljub Ščitu«, prazna okna odstranjenih reklam): DNS Ščit ne more blokirati oglasa, ki je
     * v HTML strani (sponzoriran trgovinski drsnik, slike z images.24ur.com, klik prek ad.doubleclick.net) in praznih mest GPT
     * (div-gpt-ad v .banner-placeholder), ki po blokadi ostanejo kot luknje. Izbirniki so prebrani iz prave strani 15. 11. 2026
     * (section.shopping > .shop-image + .shop-slider, .banner-placeholder, .sidebar__banner); NA TV JE TREBA PREVERITI s posnetkom pred/po.
     * Veljajo samo za gostitelja (domena ali poddomena, nikoli podniz) in samo ob vklopljenem blokiranju, kot [PRAZNA_OGLASNA_MESTA].
     */
    internal val PRAVILA_PO_GOSTITELJU: Map<String, List<String>> = mapOf(
        "24ur.com" to listOf(
            "section.shopping", ".shop-image", ".shop-slider", ".banner-placeholder", ".sidebar__banner",
            ".banner-sticky", "a[href*='ad.doubleclick.net']",
        ),
        // Izbirniki iz prave strani 15. 11. 2026 (HTML z naslovnice); na TV se NEPREVERJENO s posnetkom pred/po.
        "rtvslo.si" to listOf("#block-oglas-sport", "#block-oglas-zabava", "#block-predstavitveni-oglas", ".dynamic-advert", ".iAdserver"),
        "zurnal24.si" to listOf(".banner_placeholder", ".dfp_banner", ".fold_home_top--banner", ".fold_home_mid--banner"),
    )

    /** Pravila za gostitelja [pageUrl] (domena ali poddomena iz [PRAVILA_PO_GOSTITELJU]); prazno brez gostitelja ali ob izklopljenem blokiranju. */
    internal fun pravilaZaGostitelja(pageUrl: String?, blokiranje: Boolean): List<String> {
        if (!blokiranje) return emptyList()
        val g = GooglePravila.gostitelj(pageUrl).ifEmpty { return emptyList() }
        return PRAVILA_PO_GOSTITELJU.entries.firstOrNull { (d, _) -> g == d || g.endsWith(".$d") }?.value ?: emptyList()
    }

    /** Splosna pravila brez pravil s seznamov - za preizkus. */
    internal fun splosnaPravila(blokiranje: Boolean): List<String> =
        if (blokiranje) GENERIC_ELEMENT_HIDING_RULES + PRAZNA_OGLASNA_MESTA else GENERIC_ELEMENT_HIDING_RULES

    /**
     * Zgradi strnjen CSS niz za injiciranje v spletno stran.
     */
    fun buildCosmeticCss(pageUrl: String? = null): String {
        if (!isEnabled || AbStikala.stikala.cssIzklop) return ""
        // Pravila iz seznamov (EasyList in drugi), vezana na to domeno. Doslej smo jih
        // prenesli in zavrgli; zaradi njih so na tujih straneh ostajale prazne luknje.
        val listRules = try {
            AdBlockEngine.cosmeticSelectors(pageUrl)
        } catch (_: Exception) {
            emptyList()
        }
        // XX11 (pregled 15. 11. 2026): izbirniki iz zunanjih seznamov so lahko neveljavni ali zlonamerni; en neveljaven izbirnik v vejicnem
        // seznamu razveljavi celo pravilo, znak } ali ; pa bi zaprl pravilo. Zato strog filter znakov in pravila po skupinah.
        val vsi = (splosnaPravila(AdBlockEngine.isEnabled) + pravilaZaGostitelja(pageUrl, AdBlockEngine.isEnabled) + varniIzbirniki(listRules)).distinct()
        return vsi.chunked(SKUPINA_IZBIRNIKOV).joinToString("\n") { skupina -> pravilo(skupina.joinToString(", ")) }
    }

    /** Najvec izbirnikov v enem pravilu CSS: en neveljaven izbirnik razveljavi najvec to skupino, ne vseh skritij strani. */
    internal const val SKUPINA_IZBIRNIKOV = 20

    /**
     * Izbirnik iz seznama brez znakov, ki bi zaprli pravilo ali dodali novo (} { ; @ \\ < novi vrstici), nepraznih, najvec 300 znakov,
     * brez komentarjev CSS in z uravnotezenimi oklepaji in narekovaji (FFF4: neuravnotezen ( [ ' " bi uniel vsa naslednja pravila).
     * Znak > (otroski kombinator) je dovoljen (FFF5): v CSS ne zapusti pravila, nevaren je samo < (zapiranje oznake).
     */
    internal fun varniIzbirniki(izbirniki: List<String>): List<String> = izbirniki.filter { s ->
        s.isNotBlank() && s.length <= 300 && s.none { it in "{};@\\<\n\r\u000c" } && s.trim() !in SIROKI && !s.contains("/*") && !s.contains("*/") && uravnotezen(s)
    }

    /** GGG6: izbirniki, ki bi skrili celo stran (seznam jih sme imeti, mi ne). */
    private val SIROKI = setOf("*", "html", "body", ":root", "head")

    internal fun uravnotezen(s: String): Boolean {
        val sklad = ArrayDeque<Char>()
        var narekovaj: Char? = null
        for (c in s) {
            if (narekovaj != null) { if (c == narekovaj) narekovaj = null; continue }
            when (c) {
                '\'', '"' -> narekovaj = c
                '(' -> sklad.addLast(')')
                '[' -> sklad.addLast(']')
                ')', ']' -> if (sklad.isEmpty() || sklad.removeLast() != c) return false
            }
        }
        return narekovaj == null && sklad.isEmpty()
    }

    private fun pravilo(izbirniki: String): String = """
            $izbirniki {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                min-height: 0 !important;
                max-height: 0 !important;
                opacity: 0 !important;
                pointer-events: none !important;
                position: absolute !important;
                left: -9999px !important;
            }
        """.trimIndent()

    /**
     * Zgradi JavaScript ukaz za takojšnjo uveljavitev CSS pravil.
     */
    fun buildInjectionScript(): String {
        val css = buildCosmeticCss().replace("\n", " ").replace("\"", "\\\"")
        return """
            (function() {
                try {
                    var styleId = 'safeer-cosmetic-filter';
                    var existing = document.getElementById(styleId);
                    if (!existing) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.type = 'text/css';
                        style.innerHTML = "$css";
                        (document.head || document.documentElement).appendChild(style);
                    }
                } catch(_) {}
            })();
        """.trimIndent()
    }
}
