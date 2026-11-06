package si.safeer.tv

import android.net.Uri
import android.webkit.WebResourceResponse
import com.safeer.threatfeed.BankGuard
import com.safeer.threatfeed.BankVerdict
import com.safeer.threatfeed.PlainList
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 🛡️ ThreatBlockEngine
 * Namensko jedro za brezkompromisno blokado Botnet C2 strežnikov, zlonamerne programske opreme (Malware),
 * ribarjenja (Phishing) in indikatorjev napadov (IOC).
 * 
 * 🔒 PRAVILO O NEDOTAKLJIVOSTI: Za te grožnje NE obstaja noben video ali embed bypass!
 */
object ThreatBlockEngine {

    var isEnabled: Boolean = true

    // Statistika blokiranih groženj
    val blockedC2Count = AtomicLong(0)
    val blockedMalwareCount = AtomicLong(0)
    val blockedPhishingCount = AtomicLong(0)
    val blockedIocCount = AtomicLong(0)
    val totalBlockedThreats = AtomicLong(0)

    // Hitri Domain Suffix Trie za grožnje (atomska zamenjava, ko agent naloži sezname)
    @Volatile
    private var threatTrie = DomainSuffixTrie()

    /**
     * Drevo grozenj iz datoteke (mmap, 4. 11. 2026): seme in vsi seznami agenta, zgrajeni ob zadnji spremembi seznamov.
     * Ko je odprto, je [threatTrie] samo seme; iskanje gre najprej sem. TV: prej se je drevo ob vsakem zagonu gradilo
     * v kopici iz shranjenih seznamov (6,2 s do zascite), zdaj se odpre sinhrono pred prvim zavihkom.
     */
    @Volatile
    private var threatKazalo: DomenskoKazalo? = null

    /** Vgrajeno drevo grozenj (najprej kazalo, sicer drevo v kopici). */
    private fun lokalno(host: String): DomainSuffixTrie.MatchResult? =
        threatKazalo?.findMatch(host) ?: threatTrie.findMatch(host)

    /** Stevilo domen v vgrajenem drevesu (za dnevnik). */
    val steviloDomen: Int get() = threatKazalo?.size ?: threatTrie.size

    /**
     * Kljuc kazala: oblika, [razlicica] (koda aplikacije: seme in BankGuard se spremenita samo z novo razlicico), seme in
     * pari (id seznama, SHA-256 seznama) v vrstnem redu agenta. null, ce kak seznam nima vsote ali seznamov ni - takrat
     * kazala ni (raje drevo v kopici kot kazalo iz neznanega vira).
     */
    fun kljucKazala(seznami: List<Pair<String, String>>, razlicica: String): ByteArray? {
        if (seznami.isEmpty() || seznami.any { it.second.isEmpty() }) return null
        val deli = ArrayList<String>()
        // -2 (5. 11. 2026): drevo brez korena Googla in skupnih gostiteljev (ScitGostitelji); staro kazalo se zgradi znova,
        // tudi ce se koda aplikacije ni spremenila (razvojna gradnja).
        deli.add("safeer-drevo-grozenj-2"); deli.add(razlicica)
        for ((domene, kategorija, vir) in SEME) { deli.add(kategorija); deli.add(vir); deli.addAll(domene) }
        for ((id, sha) in seznami) { deli.add(id); deli.add(sha) }
        return com.safeer.threatfeed.SortedStringTable.keyOf(deli)
    }

    /** Odpre kazalo, ce je zgrajeno iz [kljuc]; vrne true, ce je v uporabi. Bere samo glavo datoteke. */
    fun odpriKazalo(datoteka: java.io.File, kljuc: ByteArray?): Boolean {
        val k = kljuc ?: return false
        val kazalo = DomenskoKazalo.odpri(datoteka, k) ?: return false
        threatKazalo = kazalo
        return true
    }

    /**
     * Preveri CRC kazala v uporabi (v ozadju). Ce je poskodovano, ga umakne (velja seme, dokler drevo ni zgrajeno znova)
     * in vrne false - klicatelj takrat zgradi drevo iz seznamov.
     */
    fun preveriKazalo(): Boolean {
        val kazalo = threatKazalo ?: return true
        if (kazalo.neposkodovano()) return true
        threatKazalo = null
        return false
    }

    // Začasno odobrena spletna mesta (uporabnik je izrecno kliknil 'Nadaljuj na lastno odgovornost' za to sejo)
    private val sessionBypassedDomains = ConcurrentHashMap.newKeySet<String>()

    // Enokratni žetoni za potrditev obvoza: stran ne more sama sestaviti povezave za odklep.
    data class PendingBypass(val domain: String, val targetUrl: String, val expiryMs: Long)
    private val pendingBypasses = ConcurrentHashMap<String, PendingBypass>()

    /**
     * Dodatni preverjeni vir groženj: Safeer Threat Intelligence (Ed25519 podpisan seznam, glej
     * SignedThreatIntel.kt). Dopolnjuje vgrajeni seznam, nikoli ga ne nadomešča; napaka vira nikoli
     * ne prekine navigacije.
     */
    @Volatile
    var signedFeedMatcher: ((url: String, host: String) -> DomainSuffixTrie.MatchResult?)? = null

    private fun signedMatch(url: String, host: String): DomainSuffixTrie.MatchResult? {
        val matcher = signedFeedMatcher ?: return null
        return try {
            matcher(url, host)
        } catch (e: Exception) {
            null
        }
    }

    /** Samo pravila za tocen naslov podpisanega vira (pot), brez pravil gostitelja; nastavi SignedThreatIntel. */
    @Volatile
    var signedUrlMatcher: ((url: String) -> DomainSuffixTrie.MatchResult?)? = null

    private fun signedUrlMatch(url: String): DomainSuffixTrie.MatchResult? {
        val matcher = signedUrlMatcher ?: return null
        return try {
            matcher(url)
        } catch (e: Exception) {
            null
        }
    }

    /** Pravila gostitelja podpisanega vira, omejena na gostitelje, za katere [sme] vrne true; nastavi SignedThreatIntel. */
    @Volatile
    var signedHostMatcher: ((host: String, sme: (String) -> Boolean) -> DomainSuffixTrie.MatchResult?)? = null

    private fun signedHostMatch(host: String, sme: (String) -> Boolean): DomainSuffixTrie.MatchResult? {
        val matcher = signedHostMatcher ?: return null
        return try {
            matcher(host, sme)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Vgrajeni seznam in podpisani vir; kritična kategorija (C2/malware) ima vedno prednost. Zadetek za koren Googla,
     * skupnega gostitelja ali celotno platformo (google.com, web.app) se zavrze: tak vnos ne sme blokirati gostiteljev
     * pod njim (mail.google.com, x.storage.googleapis.com). Pravilo za tocen naslov (vsebuje "://") ostane.
     */
    private fun findThreat(url: String, host: String): DomainSuffixTrie.MatchResult? {
        val local = lokalno(host)?.takeIf { ScitGostitelji.smeGostitelj(it.matchedDomain) }
        if (local != null && isCriticalThreat(local.category)) return local
        val signed = signedMatch(url, host)
            ?.takeIf { it.matchedDomain.contains("://") || ScitGostitelji.smeGostitelj(it.matchedDomain) } ?: return local
        return if (local == null || isCriticalThreat(signed.category)) signed else local
    }

    /** Kritične C2/malware grožnje nimajo obvoza (pravilo Zero-Bypass). */
    fun isCriticalThreat(category: String?): Boolean {
        if (category == null) return false
        val c = category.lowercase()
        return c.contains("c2") || c.contains("botnet") || c.contains("malware") || c.contains("zlonamerna")
    }

    fun createBypassToken(domain: String, targetUrl: String): String {
        val now = System.currentTimeMillis()
        pendingBypasses.entries.removeIf { it.value.expiryMs < now }
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        pendingBypasses[token] = PendingBypass(domain, targetUrl, now + 300_000L)
        return token
    }

    fun consumeBypassToken(token: String): PendingBypass? {
        val entry = pendingBypasses.remove(token) ?: return null
        if (System.currentTimeMillis() > entry.expiryMs) return null
        return entry
    }

    private fun htmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;")

    // Dogodek ob blokadi
    var onThreatBlocked: ((domain: String, category: String, source: String, isMainFrame: Boolean) -> Unit)? = null

    /**
     * Vnaprej naložena semenska baza znanih nevarnih C2, malware in phishing domen (domene, kategorija, vir).
     * Je tudi del kljuca kazala: sprememba semena zgradi kazalo znova.
     * POZOR: razglasitev mora stati PRED blokom init (ta seme ze uporabi).
     */
    private val SEME: List<Triple<List<String>, String, String>> = listOf(
        // 1. abuse.ch Feodo Tracker (Botnet C2 strežniki - Dridex, Emotet, QakBot, TrickBot)
        Triple(listOf(
            "c2-tracker.net", "botnet-master.org", "dridex-panel.cc",
            "dridex-c2-botnet.ru", "emotet-feed.com", "emotet-loader.biz", "qakbot-gate.biz",
            "qakbot-drop.cc", "trickbot-c2.top", "icedid-network.cc", "icedid-c2-network.net",
            "bazarloader-c2.net", "cobaltstrike-beacon.info", "cobaltstrike-beacon.xyz", "lokibot-panel.ru",
            "redline-stealer.cc", "redline-stealer-gate.org", "vidar-c2.top", "vidar-c2-gate.net",
            "raccoon-gate.com", "raccoon-stealer.biz", "asyncrat-host.duckdns.org", "njrat-beacon.biz",
            "njrat-server.com", "remcos-c2.org", "remcos-panel.net", "agenttesla-gate.net",
            "agenttesla-c2.net", "formbook-panel.cc", "xworm-controller.top", "lumma-stealer.top",
            "meduza-stealer.cc"), "Botnet C2 Server", "abuse.ch Feodo Tracker"),
        // 2. abuse.ch URLhaus & ThreatFox (Zlonamerna koda / Malware distribution & IOC)
        Triple(listOf(
            "malware-drop.com", "payload-delivery.cc",
            "evil-apk-download.net", "stealer-gate.org", "cryptominer-pool.top", "ransomware-host.xyz",
            "dropper-server.ru", "trojan-source.cc", "apk-injector.top", "malicious-script.biz",
            "23vlcfp.cfd", "2lizguk.buzz", "x91kza.monster", "dl-android-update.top",
            "system-patch-android.click", "security-alert-center.top", "device-scan-security.cc",
            "update-system-firmware.top", "cleaner-update-android.xyz"), "Zlonamerna koda (Malware)", "abuse.ch URLhaus / ThreatFox"),
        // 3. Phishing Army & Lažno predstavljanje (Kraja gesel in bančnih podatkov)
        Triple(listOf(
            "login-bank-verification.com", "secure-account-update.net",
            "verify-paypal-center.com", "apple-id-suspended.info", "google-account-recovery.top",
            "microsoft-auth-verify.cc", "nlb-klik-prijava.com", "nkbm-varnostni-pregled.net",
            "posta-slovenije-paket.top", "dhl-slovenia-slednje.cc", "si-pass-prijava.info"), "Spletno ribarjenje (Phishing)", "Phishing Army"),
        // 4. StevenBlack Malware & Agresivna stavniška omrežja z nevarno kodo
        Triple(listOf(
            "20bet.top", "20bet-aff.com", "1xbet.mobi", "1xbet-partner.com", "vulkanvegas-play.top",
            "parimatch-aff.com", "monetag-loader.com", "richpush-ads.co", "onclickalgo.com",
            "syndication.exoclick.com"), "Nevarno oglasno/stavno omrežje", "StevenBlack Unified"),
    )

    init {
        loadSeedThreatDatabase(threatTrie)
    }

    // 🏦 Safeer BankGuard ------------------------------------------------------------------------

    /** Kategorija opozorila BankGuard: ni kritična, po opozorilu je mogoče nadaljevati. */
    const val FAKE_BANK_CATEGORY = "Fake bank (Phishing)"

    private val bankHostCache = object : LinkedHashMap<String, BankVerdict?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BankVerdict?>?) = size > 512
    }
    private val fakeBankOfficialDomains = ConcurrentHashMap<String, String>()

    /** Gostitelj, ki se s svojim imenom izdaja za banko (lokalno preverjanje, rezultat se hrani). */
    fun fakeBankHost(host: String): BankVerdict? {
        synchronized(bankHostCache) {
            if (bankHostCache.containsKey(host)) return bankHostCache[host]
        }
        val verdict = try { BankGuard.hostVerdict(host) } catch (e: Exception) { null }
        synchronized(bankHostCache) { bankHostCache[host] = verdict }
        return verdict
    }

    /** Uporabnik je po opozorilu izbral nadaljevanje za ta gostitelj (ta seja). */
    fun isAllowedForSession(host: String): Boolean = sessionBypassedDomains.contains(host.lowercase().trim().trimEnd('.'))

    /** Naslov zgodovine za opozorilo po naložitvi strani (prepozna vrnitev z "Nazaj"). */
    const val FAKE_BANK_HISTORY_URL = "safeer://security-interstitial/fake-bank"

    /** Prave banke (uradne domene, bančne skupine, plačilna in identitetna infrastruktura). */
    fun isRealBankHost(host: String): Boolean = try { BankGuard.isTrusted(host) } catch (e: Exception) { false }

    /** Ključ za lokalno odprto datoteko (priponka iz pošte), ki nima gostitelja. */
    const val LOCAL_PAGE_KEY = "lokalna-datoteka"
    /** Sheme lokalno odprtih strani (priponke HTML), ki jih BankGuard preveri po vsebini. */
    val LOCAL_PAGE_SCHEMES = setOf("file", "content")

    private fun fakeBankMatch(host: String, verdict: BankVerdict): DomainSuffixTrie.MatchResult {
        if (fakeBankOfficialDomains.size > 256) fakeBankOfficialDomains.clear()
        if (verdict.officialDomain.isNotEmpty()) fakeBankOfficialDomains[host] = verdict.officialDomain
        val explanation = when (verdict.reason) {
            "lure" -> UiText.get(R.string.ui_fake_bank_lure, verdict.detail)
            "local" -> UiText.get(R.string.ui_fake_bank_local, verdict.bankName, verdict.officialDomain)
            "page" -> UiText.get(R.string.ui_fake_bank_page, verdict.bankName, verdict.officialDomain)
            else -> "${verdict.bankName} → ${verdict.officialDomain}"
        }
        return DomainSuffixTrie.MatchResult(
            isMatched = true,
            matchedDomain = host,
            category = FAKE_BANK_CATEGORY,
            sourceFeed = "Safeer Threat Shield · $explanation",
        )
    }

    /** Naložena stran: [signalsJson] je rezultat BankGuard.PAGE_SCRIPT v glavnem oknu. */
    fun checkFakeBankPage(pageUrl: String, signalsJson: String?): DomainSuffixTrie.MatchResult? {
        if (!isEnabled) return null
        return try {
            val signals = BankGuard.signalsFromJson(signalsJson) ?: return null
            val pageScheme = Uri.parse(pageUrl).scheme?.lowercase() ?: return null
            if (pageScheme in LOCAL_PAGE_SCHEMES) {
                // Priponka HTML (file:, content:) nima gostitelja; zadostuje, da se strinjata shemi.
                if (signals.scheme != pageScheme || sessionBypassedDomains.contains(LOCAL_PAGE_KEY)) return null
                val verdict = BankGuard.pageVerdict(signals) ?: return null
                return fakeBankMatch(LOCAL_PAGE_KEY, verdict)
            }
            val pageHost = Uri.parse(pageUrl).host?.lowercase()?.trim()?.trimEnd('.') ?: return null
            val host = signals.host.lowercase().trim().trimEnd('.')
            if (host.isEmpty() || host != pageHost) return null // odgovor prejšnje strani
            if (ScitGostitelji.vrsta(host) == ScitGostitelji.Vrsta.KOREN) return null // iskanje in prijava Googla nikoli
            if (sessionBypassedDomains.contains(host)) return null
            val verdict = BankGuard.pageVerdict(signals) ?: return null
            fakeBankMatch(host, verdict)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Zgradi novo drevo iz vgrajenega seznama in seznamov agenta ter ga atomsko zamenja.
     * Domene pravih bank se iz zunanjih seznamov prevzamejo samo kot potrjen C2/malware, nikoli kot ribarjenje.
     */
    fun rebuildFromLists(lists: List<PlainList>, kazalo: java.io.File? = null, razlicica: String = ""): Int {
        val kljuc = if (kazalo != null) kljucKazala(lists.filter { !it.source.raw }.map { it.source.id to it.sha256 }, razlicica) else null
        // Kazalo iz istih seznamov ze obstaja (npr. poln prvi nalog zaradi Scita): brez gradnje v kopici.
        if (kazalo != null && kljuc != null) {
            val obstojece = DomenskoKazalo.odpri(kazalo, kljuc)
            if (obstojece != null && obstojece.neposkodovano()) {
                threatKazalo = obstojece
                threatTrie = DomainSuffixTrie().also { loadSeedThreatDatabase(it); it.pripravi() }
                return lists.filter { !it.source.raw }.sumOf { it.entries.size }
            }
        }
        val newTrie = DomainSuffixTrie()
        loadSeedThreatDatabase(newTrie)
        var added = 0
        for (list in lists) {
            if (list.source.raw) continue // pravila EasyList spadajo v AdBlockEngine, ne v drevo groženj
            // Real bank domains are taken over only as confirmed C2/malware (a compromised server), never as phishing.
            val critical = isCriticalThreat(list.source.category)
            for (entry in list.entries) {
                // Koren Googla, skupni gostitelj ali celotna platforma nikoli v drevo: na njih drevo ne odloca, drevo pa vrne
                // najkrajso pripono, zato bi tak vnos zakril pravilo projekta (zlo.web.app) ali gostitelja pod njim.
                if (!ScitGostitelji.smeGostitelj(entry)) continue
                if (!critical && isRealBankHost(entry)) continue
                newTrie.insert(entry, list.source.category, list.source.name)
                added++
            }
        }
        // Urejanje in strnitev tukaj (nit posodobitve seznamov), ne ob prvem iskanju na glavni niti (lastnik 27. 10. 2026).
        newTrie.pripravi()
        // Kazalo: seznami so preverjeni (SHA-256 agenta), zato jih zapisemo v datoteko in drevo iz kopice spustimo.
        if (kazalo != null && kljuc != null) {
            val odprto = try {
                newTrie.zapisiKazalo(kazalo, kljuc)
                DomenskoKazalo.odpri(kazalo, kljuc)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (odprto != null) {
                threatKazalo = odprto
                threatTrie = DomainSuffixTrie().also { loadSeedThreatDatabase(it); it.pripravi() }
                return added
            }
        }
        threatTrie = newTrie
        threatKazalo = null
        return added
    }

    private fun loadSeedThreatDatabase(threatTrie: DomainSuffixTrie) {
        for ((domene, kategorija, vir) in SEME) for (d in domene) threatTrie.insert(d, category = kategorija, sourceFeed = vir)
    }

    /**
     * Vstavi novo zaznano grožnjo v bazo.
     */
    /**
     * Kategorija grožnje v jeziku televizorja. V seznamih je zapisana po slovensko (tako pride
     * tudi iz podpisanega vira), na zaslonu pa jo mora prebrati tudi nekdo, ki slovensko ne zna.
     * Ce prevoda ni, ostane izvirni zapis - raje nekoliko tuja beseda kot prazno polje.
     */
    fun kategorijaVJeziku(kategorija: String?): String {
        val izvirna = kategorija?.trim() ?: ""
        val c = izvirna.lowercase()
        val prevod = when {
            c.isEmpty() -> UiText.get(R.string.ui_security_threat)
            c.contains("botnet") || c.contains("c2") -> UiText.get(R.string.ui_cat_botnet)
            c.contains("malware") || c.contains("zlonamerna") -> UiText.get(R.string.ui_cat_malware)
            c.contains("phishing") || c.contains("ribarjenje") -> UiText.get(R.string.ui_cat_phishing)
            c.contains("oglasno") || c.contains("stavno") -> UiText.get(R.string.ui_cat_adnet)
            c.contains("lažn") || c.contains("prevar") || c.contains("fake") ||
                c.contains("scam") -> UiText.get(R.string.ui_cat_fake_shop)
            else -> ""
        }
        if (prevod.isNotBlank()) return prevod
        if (izvirna.isNotBlank()) return izvirna
        val zasilno = UiText.get(R.string.ui_security_threat)
        return if (zasilno.isNotBlank()) zasilno else "Security threat"
    }

    fun addThreat(domain: String, category: String, sourceFeed: String) {
        if (!ScitGostitelji.smeGostitelj(domain)) return // kot v rebuildFromLists
        threatTrie.insert(domain, category, sourceFeed)
    }

    /**
     * Preveri, ali URL ali gostitelj predstavlja varnostno grožnjo.
     * Vrne podrobnosti o grožnji ali null, če je domena varna.
     */
    fun checkThreat(url: String): DomainSuffixTrie.MatchResult? {
        if (!isEnabled || url.isEmpty()) return null

        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase()?.trim() ?: return null
            if (host.isEmpty()) return null

            // Koren Googla (iskanje, prijava) ni nikoli blokiran, tudi ne kot C2/malware: napacen ali podtaknjen vnos v viru
            // ne sme vzeti iskanja in prijave vsem uporabnikom. Gostitelji z uporabnisko vsebino imajo svoje pravilo.
            when (ScitGostitelji.vrsta(host)) {
                ScitGostitelji.Vrsta.KOREN -> return null
                ScitGostitelji.Vrsta.VSEBINA -> return preveriUporabniskoVsebino(url, host)
                ScitGostitelji.Vrsta.NAVADEN -> Unit
            }

            val match = findThreat(url, host)
            if (match == null) {
                // 🏦 BankGuard: naslov, ki posnema banko (npr. nlb-klik-prijava.com, otpbamka.si)
                if (sessionBypassedDomains.contains(host)) return null
                val verdict = fakeBankHost(host) ?: return null
                return fakeBankMatch(host, verdict)
            }

            // 🔒 ZERO-BYPASS: kritične C2 in malware grožnje nikoli nimajo izjeme
            if (isCriticalThreat(match.category)) return match

            // Prave banke nikoli ne dobijo opozorila o ribarjenju ali prevari (seznami se lahko zmotijo)
            if (isRealBankHost(host)) return null

            // Preveri začasne izjeme za to sejo (le za manj nevarne kategorije)
            if (sessionBypassedDomains.contains(host) || sessionBypassedDomains.contains(match.matchedDomain.lowercase())) {
                return null
            }
            return match
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Gostitelji z uporabnisko vsebino (sites.google.com, script.google.com, *.web.app ...): objavlja kdorkoli, zato so
     * tam pogoste lazne prijavne strani. Blokiramo tocen naslov iz vira (vsaj dva dela poti) ali gostitelja enega projekta
     * (zlo.web.app), nikoli celotnega skupnega gostitelja: vnos "sites.google.com" v viru bi sicer vzel vse strani Sites.
     */
    private fun preveriUporabniskoVsebino(url: String, host: String): DomainSuffixTrie.MatchResult? {
        val zadetki = ArrayList<DomainSuffixTrie.MatchResult>(3)
        if (ScitGostitelji.smePot(url, host)) signedUrlMatch(url)?.let { zadetki.add(it) }
        lokalno(host)?.takeIf { ScitGostitelji.jeNajemnik(it.matchedDomain) }?.let { zadetki.add(it) }
        signedHostMatch(host) { ScitGostitelji.jeNajemnik(it) }?.let { zadetki.add(it) }
        val match = zadetki.firstOrNull { isCriticalThreat(it.category) } ?: zadetki.firstOrNull()
        if (match == null) {
            // BankGuard samo za gostitelja enega projekta (nlb-prijava.web.app); skupni gostitelj ne posnema banke.
            if (!ScitGostitelji.jeNajemnik(host) || sessionBypassedDomains.contains(host)) return null
            val verdict = fakeBankHost(host) ?: return null
            return fakeBankMatch(host, verdict)
        }
        if (isCriticalThreat(match.category)) return match
        // Sejna izjema velja samo za ta zadetek (naslov ali projekt), ne za ves skupni gostitelj.
        if (sessionBypassedDomains.contains(match.matchedDomain.lowercase())) return null
        return match
    }

    /**
     * Pomožna funkcija za hitro preverjanje, ali je URL grožnja.
     */
    fun isThreat(url: String): Boolean = checkThreat(url) != null

    /**
     * Odobri domeno za to sejo (uporabnik je izbral 'Nadaljuj na lastno odgovornost').
     */
    fun allowForSession(domain: String) {
        val clean = domain.trim().lowercase()
        if (clean.isEmpty()) return
        val isUrl = clean.contains("://")
        val host = if (isUrl) {
            (try { Uri.parse(clean).host } catch (e: Exception) { null })?.lowercase() ?: return
        } else {
            clean
        }
        if (ScitGostitelji.vrsta(host) != ScitGostitelji.Vrsta.NAVADEN) {
            // Googlovi gostitelji: obvoz zavrne samo kriticni zadetek, ki ga Scit tu res uporabi (checkThreat). Vnos
            // "sites.google.com" kot malware, ki ga nikoli ne uporabimo, ne sme prepreciti obvoza ene lazne poti.
            val m = checkThreat(if (isUrl) clean else "https://$clean/")
            if (m != null && isCriticalThreat(m.category)) {
                android.util.Log.w("SafeerSecurity", "Zero-Bypass: zavrnjen obvoz za kritično grožnjo '$clean'")
                return
            }
            sessionBypassedDomains.add(clean)
            return
        }
        val local = lokalno(host)
        val signed = signedMatch(if (isUrl) clean else "https://$clean/", host)
        if (listOfNotNull(local, signed).any { isCriticalThreat(it.category) }) {
            android.util.Log.w("SafeerSecurity", "Zero-Bypass: zavrnjen obvoz za kritično grožnjo '$clean'")
            return
        }
        sessionBypassedDomains.add(clean)
    }

    /**
     * Zabeleži statistiko blokade.
     */
    fun recordBlock(result: DomainSuffixTrie.MatchResult) {
        totalBlockedThreats.incrementAndGet()
        val cat = result.category?.lowercase() ?: ""
        when {
            cat.contains("c2") || cat.contains("botnet") -> blockedC2Count.incrementAndGet()
            cat.contains("malware") || cat.contains("zlonamerna") -> blockedMalwareCount.incrementAndGet()
            cat.contains("phishing") || cat.contains("ribarjenje") -> blockedPhishingCount.incrementAndGet()
            else -> blockedIocCount.incrementAndGet()
        }
    }

    /**
     * Ustvari privlačen AMOLED Red varnostni opozorilni zaslon (Security Interstitial Page) za glavno okno.
     */
    fun createSecurityInterstitialHtml(blockedUrl: String, match: DomainSuffixTrie.MatchResult, afterPageLoad: Boolean = false): String {
        // Opozorilo po naložitvi strani stoji za lažno stranjo v zgodovini: "Nazaj" preskoči obe.
        val backSteps = if (afterPageLoad) 2 else 1
        val isFakeBank = match.category == FAKE_BANK_CATEGORY
        val officialDomain = if (isFakeBank) fakeBankOfficialDomains[match.matchedDomain.lowercase()] else null
        val heading = if (isFakeBank) UiText.get(R.string.ui_fake_bank_title) else UiText.get(R.string.ui_threat_blocked)
        val description = if (isFakeBank) UiText.get(R.string.ui_fake_bank_desc) else UiText.get(R.string.ui_threat_desc)
        val officialButtonHtml = if (officialDomain != null) {
            val safeDomain = htmlEscape(officialDomain)
            "<a class=\"btn btn-primary\" style=\"background:#16a34a\" href=\"https://$safeDomain/\">${htmlEscape(UiText.get(R.string.ui_fake_bank_open_real, officialDomain))}</a>"
        } else ""
        val domain = htmlEscape(match.matchedDomain)
        val category = htmlEscape(if (isFakeBank) UiText.get(R.string.ui_fake_bank_type) else kategorijaVJeziku(match.category))
        val source = htmlEscape(match.sourceFeed ?: UiText.get(R.string.ui_security_shield))
        val bypassActionHtml = if (isCriticalThreat(match.category)) {
            "<div class=\"footer-text\" style=\"color:#ff8888;font-size:13px\">🔒 ${htmlEscape(UiText.get(R.string.ui_zero_bypass))}</div>"
        } else {
            val token = createBypassToken(match.matchedDomain, blockedUrl)
            "<a class=\"btn btn-danger-outline\" href=\"safeer://bypass-threat?token=$token\">${UiText.get(R.string.ui_continue_risk)}</a>"
        }

        return """
        <!DOCTYPE html>
        <html lang="${UiText.language}">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>${UiText.get(R.string.ui_security_warning)}</title>
            <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    background-color: #050508;
                    color: #e5e5e5;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                    min-height: 100vh;
                    display: flex;
                    flex-direction: column;
                    justify-content: center;
                    align-items: center;
                    padding: 24px;
                    text-align: center;
                }
                .card {
                    background: rgba(22, 10, 14, 0.85);
                    border: 1px solid rgba(255, 68, 68, 0.35);
                    border-radius: 20px;
                    padding: 32px 24px;
                    max-width: 480px;
                    width: 100%;
                    box-shadow: 0 10px 40px rgba(255, 0, 0, 0.25);
                    backdrop-filter: blur(12px);
                }
                .icon {
                    width: 72px;
                    height: 72px;
                    margin: 0 auto 20px;
                    background: rgba(255, 68, 68, 0.15);
                    border: 2px solid #ff4444;
                    border-radius: 50%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 36px;
                    animation: pulse 2s infinite;
                }
                @keyframes pulse {
                    0% { box-shadow: 0 0 0 0 rgba(255, 68, 68, 0.5); }
                    70% { box-shadow: 0 0 0 16px rgba(255, 68, 68, 0); }
                    100% { box-shadow: 0 0 0 0 rgba(255, 68, 68, 0); }
                }
                h1 {
                    font-size: 22px;
                    font-weight: 700;
                    color: #ff5555;
                    margin-bottom: 12px;
                }
                p.desc {
                    font-size: 15px;
                    color: #a0a0b0;
                    line-height: 1.5;
                    margin-bottom: 24px;
                }
                .badge-box {
                    background: rgba(0, 0, 0, 0.5);
                    border: 1px solid rgba(255, 255, 255, 0.1);
                    border-radius: 12px;
                    padding: 14px;
                    margin-bottom: 24px;
                    text-align: left;
                }
                .badge-row {
                    display: flex;
                    justify-content: space-between;
                    font-size: 13px;
                    margin-bottom: 6px;
                }
                .badge-row:last-child { margin-bottom: 0; }
                .badge-label { color: #888; }
                .badge-val { color: #fff; font-weight: 600; word-break: break-all; }
                .badge-danger { color: #ff5555; font-weight: 700; }
                
                .btn {
                    display: block;
                    width: 100%;
                    padding: 14px;
                    border-radius: 12px;
                    font-size: 15px;
                    font-weight: 600;
                    text-decoration: none;
                    cursor: pointer;
                    margin-bottom: 12px;
                    border: none;
                    transition: all 0.2s ease;
                }
                .btn-primary {
                    background: #2563eb;
                    color: #fff;
                    box-shadow: 0 4px 14px rgba(37, 99, 235, 0.4);
                }
                .btn-primary:active { background: #1d4ed8; transform: scale(0.98); }
                .btn-danger-outline {
                    background: transparent;
                    color: #888;
                    border: 1px solid rgba(255, 255, 255, 0.15);
                    font-size: 13px;
                    padding: 10px;
                }
                .btn-danger-outline:active { color: #ff5555; border-color: #ff5555; }
                .footer-text {
                    font-size: 12px;
                    color: #555;
                    margin-top: 16px;
                }
            </style>
        </head>
        <body>
            <div class="card">
                <div class="icon">🛑</div>
                <h1>${htmlEscape(heading)}</h1>
                <p class="desc">${htmlEscape(description)}</p>
                
                <div class="badge-box">
                    <div class="badge-row">
                        <span class="badge-label">${UiText.get(R.string.ui_domain)}</span>
                        <span class="badge-val">$domain</span>
                    </div>
                    <div class="badge-row">
                        <span class="badge-label">${UiText.get(R.string.ui_threat_type)}</span>
                        <span class="badge-danger">$category</span>
                    </div>
                    <div class="badge-row">
                        <span class="badge-label">${UiText.get(R.string.ui_security_source)}</span>
                        <span class="badge-val">$source</span>
                    </div>
                </div>

                <button class="btn btn-primary" onclick="if (history.length > $backSteps) { history.go(-$backSteps); } else { location.href = 'about:blank'; }">
                    ${UiText.get(R.string.ui_back_safe)}
                </button>

                $officialButtonHtml
                
                $bypassActionHtml

                <div class="footer-text">
                    Safeer Threat Shield • abuse.ch Feodo / URLhaus / ThreatFox • Phishing Army • HaGeZi • SI-CERT • EasyList
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private val nepripravljenihPorocil = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Omejeno cakanje na prvo nalaganje zascite ([ZascitaZagona]): vse zahteve na drevo grozenj in podpisani vir, podviri
     * tudi na EasyList. Dnevnik pove, ali je bila prva zahteva filtrirana.
     */
    private fun pocakajNaZascito(isMainFrame: Boolean) {
        val glavna = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val izid = ZascitaZagona.pocakaj(glavna, isMainFrame) ?: return
        if (izid.prva) {
            android.util.Log.i("SafeerSecurity", "Zascita ob zagonu: prva zahteva cakala ${izid.cakalaMs} ms, pripravljeno=${izid.pripravljeno}" +
                ", ${izid.odZacetkaMs} ms od zacetka, glavni okvir=$isMainFrame, glavna nit=$glavna (${ZascitaZagona.porocilo()})")
        } else if (!izid.pripravljeno && nepripravljenihPorocil.incrementAndGet() <= 5) {
            android.util.Log.w("SafeerSecurity", "Zascita ob zagonu: zahteva brez seznamov po ${izid.cakalaMs} ms cakanja (${izid.odZacetkaMs} ms od zacetka" +
                ", glavni okvir=$isMainFrame; ${ZascitaZagona.porocilo()})")
        }
    }

    /**
     * Vrne WebResourceResponse z varnostnim opozorilom za glavno okno ali prazen odgovor za podvire.
     */
    fun handleThreatIntercept(url: String, isMainFrame: Boolean): WebResourceResponse? {
        // Hladni zagon (raziskava 4. 11. 2026): prve zahteve so sle mimo seznamov, ker so se ti nalagali v ozadju. Ta klic
        // tece v nitnem bazenu WebView (shouldInterceptRequest) in tu omejeno pocaka na prvo nalaganje; glavna nit nikoli.
        // Podvir caka tudi na EasyList (AdBlockEngine.filterListBlocks za njim), glavni okvir ne.
        pocakajNaZascito(isMainFrame)
        val match = checkThreat(url) ?: return null
        recordBlock(match)
        onThreatBlocked?.invoke(match.matchedDomain, kategorijaVJeziku(match.category), match.sourceFeed ?: "Safeer Shield", isMainFrame)

        return if (isMainFrame) {
            val html = createSecurityInterstitialHtml(url, match)
            WebResourceResponse(
                "text/html",
                "UTF-8",
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        } else {
            // Podviri (skripte, slike, C2 beaconi) se tiho prekinejo z varnim praznim odgovorom
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }
}
