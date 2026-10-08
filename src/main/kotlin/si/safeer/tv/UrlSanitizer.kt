package si.safeer.tv

/**
 * 🛡️ UrlSanitizer
 * Kirurško čiščenje sledilnih parametrov v URL-jih (Query Tracker Stripping) brez vpliva
 * na avtentikacijo (OAuth/SSO), video tokove, EPG ali navigacijo na TV napravah.
 */
object UrlSanitizer {

    // Nabor znanih sledilnih parametrov, ki se uporabljajo za profiliranje in sledenje med spletnimi mesti
    private val TRACKING_PARAMS = setOf(
        // Google Ads & Analytics
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
        "gclid", "gclsrc", "dclid", "gad_source", "gbraid", "wbraid",

        // Meta / Facebook & Instagram
        "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref", "igshid",

        // Microsoft / Bing
        "msclkid",

        // Twitter / X
        "twclid",

        // TikTok
        "ttclid",

        // Yandex
        "yclid", "_openstat",

        // E-poštno trženje (Mailchimp, HubSpot, Marketo itd.)
        "mc_cid", "mc_eid", "hsctstracking", "_hsenc", "_hsmi", "mkt_tok", "wickedid", "vero_id",

        // Partnerska in oglasna omrežja
        "zanpid", "s_kwcid", "sc_cid", "rb_clickid",

        // Instagram (novejsi parameter), Google Analytics linker
        "igsh", "_ga", "_gl",

        // Matomo / Piwik (mtm_* se brise po predponi)
        "pk_campaign", "pk_kwd", "pk_keyword", "pk_source", "pk_medium", "pk_content",

        // E-trgovina in novicarske kampanje
        "spm", "scm", "ncid", "cmpid"
    )

    // Stroga bela lista nujnih parametrov aplikacij, ki se nikoli ne smejo odstraniti
    private val ESSENTIAL_WHITELIST = setOf(
        // OAuth 2.0 / OpenID Connect / SAML / SSO
        "code", "state", "session_state", "access_token", "id_token", "token",
        "scope", "authuser", "response_type", "client_id", "redirect_uri",
        "nonce", "saml_response", "relay_state", "assertion", "login_hint",
        "prompt", "display",

        // Iskanje in paginacija
        "q", "query", "search", "p", "page", "start", "limit", "offset",

        // Video / Multimedija (YouTube, pretocne storitve)
        "v", "list", "t", "time_continue", "index", "start_radio", "radio", "shorts",

        // E-trgovina in plačilni sistemi (Stripe, PayPal, Bančni portali)
        "session_id", "checkout_session_id", "payment_id", "order_id", "txn_id",
        "amount", "currency", "return_url", "cancel_url", "success", "canceled"
    )

    /**
     * Očisti URL vseh sledilnih parametrov.
     * Če URL ne vsebuje sledilnih parametrov, vrne točno originalni niz brez sprememb.
     */
    fun sanitize(url: String): String {
        if (url.isEmpty() || !url.contains("?")) return url

        val lowerUrl = url.lowercase()
        // Čisti samo HTTP in HTTPS zahteve
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return url
        }

        return try {
            val hashIdx = url.indexOf('#')
            val fragment = if (hashIdx != -1) url.substring(hashIdx) else ""
            val urlWithoutHash = if (hashIdx != -1) url.substring(0, hashIdx) else url

            val questionIdx = urlWithoutHash.indexOf('?')
            if (questionIdx == -1) return url

            val base = urlWithoutHash.substring(0, questionIdx)
            val queryString = urlWithoutHash.substring(questionIdx + 1)
            if (queryString.isEmpty()) return "$base$fragment"

            val pairs = queryString.split('&')
            val retainedPairs = mutableListOf<String>()
            var anyStripped = false

            for (pair in pairs) {
                if (pair.isEmpty()) continue
                val eqIdx = pair.indexOf('=')
                val key = if (eqIdx != -1) pair.substring(0, eqIdx) else pair
                // Ime parametra je lahko percent-kodirano (utm%5Fsource): pred primerjavo ga dekodiramo.
                val lowerKey = decodeKey(key)

                if (isEssential(lowerKey)) {
                    retainedPairs.add(pair)
                } else if (isTrackingParam(lowerKey) || (lowerKey == "si" && isYouTubeShare(base))) {
                    anyStripped = true
                } else {
                    retainedPairs.add(pair)
                }
            }

            if (!anyStripped) return url

            if (retainedPairs.isEmpty()) {
                "$base$fragment"
            } else {
                val newQuery = retainedPairs.joinToString("&")
                "$base?$newQuery$fragment"
            }
        } catch (_: Exception) {
            url
        }
    }

    /** Ime parametra: percent-dekodirano, male crke, brez presledkov; pri neveljavni kodi ostane surovo. */
    private fun decodeKey(raw: String): String {
        if (raw.indexOf('%') == -1 && raw.indexOf('+') == -1) return raw.lowercase().trim()
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8").lowercase().trim()
        } catch (_: Exception) {
            raw.lowercase().trim()
        }
    }

    /** Parameter si je sledilec deljenja samo na YouTubu; drugje je lahko navadno ime parametra. */
    private fun isYouTubeShare(base: String): Boolean {
        val b = base.lowercase()
        val i = b.indexOf("://")
        if (i == -1) return false
        val host = b.substring(i + 3).substringBefore('/').substringBefore(':')
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun isEssential(param: String): Boolean {
        return ESSENTIAL_WHITELIST.contains(param)
    }

    private fun isTrackingParam(param: String): Boolean {
        if (param.startsWith("utm_") || param.startsWith("mtm_")) return true
        return TRACKING_PARAMS.contains(param)
    }
}
