package si.safeer.tv

/**
 * Kateri naslov je Googlov ali YouTubov - samo po gostitelju, nikoli po delu poti ali poizvedbe. Brez Androida, da jih
 * preverimo v JVM (tests/GooglePravilaTest.kt).
 *
 * Zunanji pregled 2. 11. 2026: prej je zadoscal niz kjerkoli v naslovu (»signin/v2«, ».google.«, »gstatic.com«,
 * »youtube.com«) - https://zlo.example/signin/v2 ali https://google.zlo.example/ je veljal za Google (brez filtra
 * oglasov, piskotki tretjih strani, samodejna okna, brez mostu), notyoutube.com pa je bil preusmerjen na youtube.com/tv.
 */
object GooglePravila {
    /** Gostitelj naslova http(s): male crke, brez uporabnika, vrat in koncne pike; "" = ni spletni naslov. */
    fun gostitelj(url: String?): String {
        val n = url?.trim() ?: return ""
        val i = n.indexOf("://")
        if (i <= 0) return ""
        val shema = n.substring(0, i).lowercase()
        if (shema != "http" && shema != "https") return ""
        // Chromium bere »\« kot »/«; oblast se konca pri prvem od teh znakov.
        val konec = n.indexOfAny(charArrayOf('/', '?', '#', '\\'), i + 3).let { if (it < 0) n.length else it }
        var h = n.substring(i + 3, konec).substringAfterLast('@').lowercase()
        h = if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':')
        return h.trimEnd('.')
    }

    /** Pot naslova (brez poizvedbe in sidra), male crke; "" ce je ni. */
    fun pot(url: String?): String {
        val n = url?.trim() ?: return ""
        val i = n.indexOf("://")
        if (i <= 0) return ""
        val z = n.indexOfAny(charArrayOf('/', '\\'), i + 3)
        if (z < 0) return ""
        val ostanek = n.substring(z).replace('\\', '/')
        return ostanek.substringBefore('?').substringBefore('#').lowercase()
    }

    private fun jeVDomeni(h: String, domena: String): Boolean = h == domena || h.endsWith(".$domena")

    /**
     * Pripone za »google.« - izrecen seznam Googlovih domen (peti slepi pregled 5. 11. 2026, N3: prej regex, ki je sprejel vsako
     * dvocrkovno pripono ter co.xx in com.xx, npr. google.zz ali google.co.xx, ki ju lahko registrira kdorkoli).
     * Vir: https://www.google.com/supported_domains, prenos 5. 11. 2026 (187 domen, SHA-256 zacetek ba827017007f54b6).
     */
    private val GOOGLE_PRIPONE: Set<String> = setOf(
        "com", "ad", "ae", "com.af", "com.ag", "al", "am", "co.ao", "com.ar", "as", "at", "com.au", "az", "ba",
        "com.bd", "be", "bf", "bg", "com.bh", "bi", "bj", "com.bn", "com.bo", "com.br", "bs", "bt", "co.bw", "by",
        "com.bz", "ca", "cd", "cf", "cg", "ch", "ci", "co.ck", "cl", "cm", "cn", "com.co", "co.cr", "com.cu", "cv",
        "com.cy", "cz", "de", "dj", "dk", "dm", "com.do", "dz", "com.ec", "ee", "com.eg", "es", "com.et", "fi",
        "com.fj", "fm", "fr", "ga", "ge", "gg", "com.gh", "com.gi", "gl", "gm", "gr", "com.gt", "gy", "com.hk", "hn",
        "hr", "ht", "hu", "co.id", "ie", "co.il", "im", "co.in", "iq", "is", "it", "je", "com.jm", "jo", "co.jp",
        "co.ke", "com.kh", "ki", "kg", "co.kr", "com.kw", "kz", "la", "com.lb", "li", "lk", "co.ls", "lt", "lu",
        "lv", "com.ly", "co.ma", "md", "me", "mg", "mk", "ml", "com.mm", "mn", "com.mt", "mu", "mv", "mw", "com.mx",
        "com.my", "co.mz", "com.na", "com.ng", "com.ni", "ne", "nl", "no", "com.np", "nr", "nu", "co.nz", "com.om",
        "com.pa", "com.pe", "com.pg", "com.ph", "com.pk", "pl", "pn", "com.pr", "ps", "pt", "com.py", "com.qa", "ro",
        "ru", "rw", "com.sa", "com.sb", "sc", "se", "com.sg", "sh", "si", "sk", "com.sl", "sn", "so", "sm", "sr",
        "st", "com.sv", "td", "tg", "co.th", "com.tj", "tl", "tm", "tn", "to", "com.tr", "tt", "com.tw", "co.tz",
        "com.ua", "co.ug", "co.uk", "com.uy", "co.uz", "com.vc", "co.ve", "co.vi", "com.vn", "vu", "ws", "rs",
        "co.za", "co.zm", "co.zw", "cat"
    )

    /** google.com, google.si, www.google.co.uk, mail.google.com ... - oznaka »google« pred Googlovo pripono. */
    fun jeGoogleGostitelj(h: String): Boolean {
        val deli = h.split('.')
        for (i in deli.indices) {
            if (deli[i] != "google" || i == deli.size - 1) continue
            if (deli.subList(i + 1, deli.size).joinToString(".") in GOOGLE_PRIPONE) return true
        }
        return false
    }

    /** Gostitelji Googlove prijave (tudi YouTubova, ki je del iste prijave). */
    private fun jePrijavniGostitelj(h: String): Boolean {
        if (h == "accounts.youtube.com") return true
        val prvi = h.substringBefore('.')
        return (prvi == "accounts" || prvi == "myaccount") && h.indexOf('.') > 0 && jeGoogleGostitelj(h.substringAfter('.'))
    }

    /**
     * Googlova stran (iskanje, prijava, reCAPTCHA in njeni viri). YouTube, googlevideo in ytimg niso - tam velja
     * filter oglasov - razen accounts.youtube.com (prijava).
     */
    fun jeGoogle(url: String?): Boolean {
        val h = gostitelj(url)
        if (h.isEmpty()) return false
        if (jePrijavniGostitelj(h)) return true
        return jeGoogleGostitelj(h) || jeVDomeni(h, "gstatic.com") || jeVDomeni(h, "googleapis.com") ||
            jeVDomeni(h, "recaptcha.net")
    }

    private val PRIJAVNE_POTI = listOf("/accounts", "/signin", "/v3/signin", "/servicelogin", "/accountchooser")

    /** Googlova prijava: prijavni gostitelj ali Googlov gostitelj s prijavno potjo - nikoli samo pot. */
    fun jePrijava(url: String?): Boolean {
        val h = gostitelj(url)
        if (h.isEmpty()) return false
        if (jePrijavniGostitelj(h)) return true
        if (!jeGoogleGostitelj(h)) return false
        val p = pot(url)
        return PRIJAVNE_POTI.any { p == it || p.startsWith("$it/") }
    }

    /**
     * User-Agent za Googlove strani in prijavo: privzeti WebView brez oznak »; wv« in »Version/4.0« - Chrome na Androidu z
     * resnicno razlicico (skladno z navigator.userAgentData). Lastnik 2. 11. 2026 17:20 (moznost b): tako se uvoz YouTuba
     * (YouTubeUvozOkno) na TV uspesno prijavi; Safari na Macu je Google 2. 11. 17:19 zavrnil (»ta brskalnik morda ni varen«).
     */
    fun uaBrezWebView(privzeti: String): String =
        privzeti.replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Strani, ki dobijo isto identiteto kot Googlova prijava (Chrome na Androidu, uaBrezWebView). Lastnik 3. 11. 2026:
     * prijava v claude.ai na TV je obticala pri Cloudflare preverjanju in uganki (namizni UA na televizorju). Cloudflare
     * dovoljenje veze na UA, zato ga dobi cela stran in njene poddomene, ne glede na nacin (aplikacija ali brskalnik).
     */
    private val CHROME_ANDROID_GOSTITELJI = listOf("claude.ai", "anthropic.com", "challenges.cloudflare.com")

    fun chromeAndroidStran(url: String?): Boolean {
        val h = gostitelj(url)
        return h.isNotEmpty() && CHROME_ANDROID_GOSTITELJI.any { h == it || h.endsWith(".$it") }
    }

    /** YouTube: youtube.com in njegove poddomene ter youtu.be (enako kot UserScriptManager.isYouTubeUrl). */
    fun jeYoutube(url: String?): Boolean {
        val h = gostitelj(url)
        return h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be"
    }

    /** YouTube, ki ga televizor odpre kot youtube.com/tv: brez YouTube Music, Studia in prijave. */
    fun youtubeZaTv(url: String?): Boolean {
        if (!jeYoutube(url)) return false
        val h = gostitelj(url)
        return h != "music.youtube.com" && h != "studio.youtube.com" && h != "accounts.youtube.com"
    }

    /** Naslov YouTuba za televizor (youtube.com/tv); drugi naslovi ostanejo, kot so. */
    fun youtubeNaTv(url: String): String {
        if (!youtubeZaTv(url)) return url
        val lower = url.lowercase()
        // Prehod prijave (continue/next na Googlovo prijavo) ostane nespremenjen.
        if (lower.contains("accounts.google") || lower.contains("accounts.youtube")) return url
        val h = gostitelj(url)
        val p = pot(url)
        if (h != "youtu.be" && (p == "/tv" || p.startsWith("/tv/"))) return url
        val poizvedba = url.substringAfter('?', "").substringBefore('#')
        fun parameter(ime: String): String? = poizvedba.split('&').firstOrNull { it.startsWith("$ime=") }?.substringAfter('=')
        val id = Regex("^[\\w-]{6,}$")
        if (h == "youtu.be") {
            val v = p.trim('/').substringBefore('/')
            val izvirni = url.substringAfter("://").substringAfter('/').substringBefore('?').substringBefore('#').substringBefore('/')
            if (id.matches(v)) return "https://www.youtube.com/tv#/watch?v=$izvirni"
            return "https://www.youtube.com/tv"
        }
        if (p == "/watch") {
            val v = parameter("v")
            if (v != null && id.matches(v)) return "https://www.youtube.com/tv#/watch?v=$v"
        }
        if (p == "/results" || p == "/search") {
            val q = parameter("search_query") ?: parameter("q")
            if (!q.isNullOrEmpty()) return "https://www.youtube.com/tv#/search?q=$q"
        }
        return "https://www.youtube.com/tv"
    }

    /** Ali je [pripona] (com, si, co.uk ...) Googlova domena za »google.«; Scit grozenj (ScitGostitelji) loci koren. */
    fun jeGooglePripona(pripona: String): Boolean = pripona in GOOGLE_PRIPONE
}
