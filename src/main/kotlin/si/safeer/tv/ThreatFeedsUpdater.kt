package si.safeer.tv

import android.content.Context
import com.safeer.threatfeed.FilterSetPredpomnilnik
import com.safeer.threatfeed.PlainList
import com.safeer.threatfeed.PlainListInfo
import com.safeer.threatfeed.PlainListSource
import com.safeer.threatfeed.ThreatListAgent
import java.io.File

/**
 * 🔄 ThreatFeedsUpdater – agent za sezname nevarnih strani (Feodo Tracker, URLhaus, Phishing Army).
 *
 * Ob vsakem zagonu brskalnika:
 *  1. takoj v ozadju (nizka prioriteta) naloži sezname, shranjene ob prejšnjem zagonu (preverjeni s SHA-256),
 *  2. približno 12 sekund po zagonu preveri nove sezname (pogojni prenos: nespremenjen seznam je ena majhna
 *     zahteva) in jih zamenja naenkrat, brez prekinitve brskanja,
 *  3. med delovanjem preverja vsakih 6 ur.
 * Prej so se seznami ob vsakem zagonu v celoti prenašali in sproti vstavljali v aktivno drevo.
 */
object ThreatFeedsUpdater {

    private val SOURCES = listOf(
        PlainListSource(
            id = "feodo", name = "abuse.ch Feodo Tracker", url = "https://feodotracker.abuse.ch/downloads/ipblocklist.txt",
            category = "Botnet C2 Server", marker = "feodo", minEntries = 0, ipv4 = true,
        ),
        // Feodo »aggressive« (CC0, ok. 7600 IP, vkljucuje tudi starejse C2): samo za blokado po IP odgovora v Scitu
        // (dnsOnly: brskalnik ga ne bere). 26. 11. 2026.
        PlainListSource(
            id = "feodo-aggressive", name = "abuse.ch Feodo Tracker (aggressive)",
            url = "https://feodotracker.abuse.ch/downloads/ipblocklist_aggressive.txt",
            category = "Botnet C2 Server", marker = "feodo", minEntries = 0, ipv4 = true, dnsOnly = true,
        ),
        // abuse.ch od 21. 10. 2026 brez kljuca vrne 410 (lastnik 9. 11. 2026): uporabnik vpise SVOJ brezplacni kljuc na
        // zaslonu Scita (si.safeer.tv.scit.AbuseChKljuc); brez njega ostane zadnja shranjena kopija. Glava seznama s kljucem ni
        // preverjena, zato headerless (90 % vrstic mora biti vnosov).
        PlainListSource(
            id = "urlhaus", name = "abuse.ch URLhaus", url = si.safeer.tv.scit.AbuseChKljuc.URL_URLHAUS,
            category = "Zlonamerna koda (Malware)", marker = "", headerless = true,
            kljuc = { si.safeer.tv.scit.AbuseChKljuc.zaVir() },
        ),
        PlainListSource(
            id = "phishing-army", name = "Phishing Army Extended",
            url = "https://phishing.army/download/phishing_army_blocklist_extended.txt",
            category = "Spletno ribarjenje (Phishing)", marker = "phishing",
        ),
        PlainListSource(
            id = "hagezi-tif", name = "HaGeZi Threat Intelligence Feeds (mini)",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/tif.mini-onlydomains.txt",
            category = "Nevarne strani (grožnje, ribarjenje, prevare)", marker = "hagezi",
        ),
        PlainListSource(
            id = "hagezi-fake", name = "HaGeZi Fake (lažne trgovine in prevare)",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/fake-onlydomains.txt",
            category = "Lažne trgovine in prevare", marker = "hagezi",
        ),
        PlainListSource(
            id = "hagezi-pro", name = "HaGeZi Multi PRO (oglasi in sledenje, za Safeer Ščit)",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/pro-onlydomains.txt",
            category = "Oglasi in sledenje (DNS)", marker = "hagezi", minEntries = 10000, dnsOnly = true,
        ),
        // Lastnik 9. 11. 2026: »Scit enako dober ali boljsi kot Pi-hole«. Privzeti seznam Pi-hole je StevenBlack Unified;
        // meritev 9. 11.: Scit (PRO, TIF, Fake ...) jih je ustavil 79,4 %, ~8.500 zivih domen ne. Isti vir kot Safeer-linux.
        PlainListSource(
            id = "stevenblack", name = "StevenBlack Unified hosts (privzeti seznam Pi-hole)",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            category = "Oglasi in zlonamerne strani (DNS)", marker = "stevenblack", minEntries = 10000, dnsOnly = true,
        ),
        // Lastnik 9. 11. 2026: »upostevaj vse, kar AdGuard blokira«. AdGuard DNS filter je javen (GPL-3.0): AdGuard Base,
        // Tracking Protection, Mobile ads, EasyList, EasyPrivacy za DNS. Meritev 9. 11.: Scit jih je ustavil 43,1 %.
        PlainListSource(
            id = "adguard-dns", name = "AdGuard DNS filter",
            url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            category = "Oglasi in sledenje (DNS)", marker = "adguard dns filter", minEntries = 10000, dnsOnly = true,
        ),
        PlainListSource(
            id = "easylist", name = "EasyList (pravila za oglase)", url = "https://easylist.to/easylist/easylist.txt",
            category = "Oglasi (EasyList)", marker = "easylist", minEntries = 1000, raw = true,
        ),
        PlainListSource(
            id = "si-cert", name = "SI-CERT phishing domene (Slovenija)",
            url = "https://www.cert.si/misp/rpz/last.txt",
            category = "Spletno ribarjenje (Phishing) – potrdil SI-CERT", marker = "", headerless = true,
        ),
    )

    @Volatile
    private var agent: ThreatListAgent? = null

    /** Agent samo za nabor Scita (proces :scit, [startSamoScit]); v glavnem procesu ostane null. */
    @Volatile
    private var agentScit: ThreatListAgent? = null

    @Volatile
    var ruleCount: Int = 0
        private set

    /** Število prevedenih pravil EasyList v uporabi. */
    @Volatile
    var filterRuleCount: Int = 0
        private set

    /** Drevo grozenj v datoteki (mmap): seme in seznami grozenj, zgrajeno ob spremembi seznamov. */
    private fun kazaloDatoteka(context: Context) = File(File(context.applicationContext.filesDir, "threat-index"), "drevo-grozenj.bin")

    /** Seme in BankGuard se spremenita samo z novo razlicico aplikacije; ta je del kljuca kazala. */
    private val razlicica: String get() = "${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_CODE}"

    private fun jeGrozenj(s: PlainListSource) = !s.raw && !s.dnsOnly

    /** Predprevedeni nabor EasyList (12. 11. 2026), zraven kazala grozenj. */
    private fun predpomnilnikDatoteka(app: Context) = File(File(app.filesDir, "threat-index"), "easylist-predprevedeno.bin")

    /**
     * Kljuc za BRANJE ob zagonu (seznam se se ni prevedel): iz .meta (id + SHA-256 surovih seznamov) in razlicice aplikacije.
     * Samo za branje: kljuc za ZAPIS gre prek FilterSetPredpomnilnik.kljucZaZapis iz dejansko prevedenih seznamov (N2).
     */
    private fun kljucZaBranje(info: List<PlainListInfo>): String? {
        val raw = info.filter { it.source.raw }
        if (raw.isEmpty() || raw.any { it.sha256.isEmpty() }) return null
        return FilterSetPredpomnilnik.kljuc(listOf(razlicica) + raw.flatMap { listOf(it.source.id, it.sha256) })
    }

    /** Kljuc veljavnega predpomnilnika za ta zagon (null = predpomnilnika ni ali ne velja). */
    @Volatile private var predpomnilnikKljuc: String? = null

    /**
     * Zapise prevedeni nabor (v ozadju, po tem ko je zascita ze pripravljena). Kljuc je iz seznamov, ki so bili res PREVEDENI
     * ([prevedeni]), ne iz .meta (pregled 12. 11. 2026, N2): poskodovan ali nebran seznam ne dobi veljavnega kljuca, prazen nabor se
     * ne zapise. [branjeNeuspelo]: predpomnilnik je bil neveljaven, datoteka se zbrise in prepise (N1).
     */
    private fun zapisiPredpomnilnik(app: Context, prevedeni: List<PlainList>, branjeNeuspelo: Boolean) {
        try {
            val kl = FilterSetPredpomnilnik.kljucZaZapis(razlicica, prevedeni, agent?.savedInfo() ?: return)
            val d = predpomnilnikDatoteka(app)
            if (kl == null) {
                // Seznam ni bil v celoti preveden: stari predpomnilnik ne sme ostati (bi ustrezal drugemu stanju).
                if (branjeNeuspelo) d.delete()
                android.util.Log.w("SafeerSecurity", "Predpomnilnik EasyList ni zapisan: prevedeni seznami se ne ujemajo z metapodatki")
                return
            }
            val t = System.nanoTime()
            val ok = FilterSetPredpomnilnik.osvezi(AdBlockEngine.trenutniNabor(), kl, d, branjeNeuspelo)
            android.util.Log.i("SafeerSecurity", "Predpomnilnik EasyList velja=$ok, ${(System.nanoTime() - t) / 1_000_000} ms")
        } catch (e: Throwable) {
            android.util.Log.w("SafeerSecurity", "Predpomnilnika EasyList ni bilo mogoce zapisati: ${e.message}")
        }
    }

    /**
     * Viri agenta brskalnika: brez seznamov samo za Scit (dnsOnly, HaGeZi PRO 247 757 domen). TV 4. 11. 2026: ob osvezitvi
     * je glavni proces bral tudi PRO (logcat »Seznami v uporabi: ... HaGeZi Multi PRO«, GC 98/122 MB), ceprav ga brskalnik
     * ne uporablja. Nabor Scita gradi samo proces :scit tiste aplikacije, ki Scit vodi ([startSamoScit]).
     */
    private val BRSKALNIK_VIRI = SOURCES.filter(si.safeer.tv.scit.ScitViri::zaBrskalnik)

    /**
     * Zažene agenta (enkrat na proces). Vrne v nekaj ms; vse delo z vsebino seznamov poteka v ozadju.
     *
     * 4. 11. 2026 (TV: zascita pripravljena sele 6,2 s po zagonu): drevo grozenj se odpre SINHRONO iz kazala (mmap), ce
     * je zgrajeno iz istih seznamov (kljuc iz SHA-256 v metapodatkih agenta). Takrat agent ob zagonu prebere in prevede
     * samo EasyList; ostali seznami ostanejo na disku. Brez kazala (prvi zagon, nova razlicica, nov seznam) se vse zgradi v
     * ozadju kot prej in kazalo se zapise.
     */
    @Synchronized
    fun start(context: Context) {
        if (agent != null) return
        si.safeer.tv.scit.AbuseChKljuc.init(context)
        // Celoten agent gradi tudi nabor Scita: dveh agentov nad isto mapo v enem procesu ne zelimo.
        agentScit?.stop()
        agentScit = null
        // Od tu zahteve v shouldInterceptRequest omejeno pocakajo na prvo nalaganje (ZascitaZagona, 4. 11. 2026).
        ZascitaZagona.zacni()
        val app = context.applicationContext
        val kazalo = kazaloDatoteka(app)
        val listAgent = ThreatListAgent(File(app.filesDir, "threat-lists"), BRSKALNIK_VIRI,
            firstLoadPriority = Thread.NORM_PRIORITY,
            onFirstLoad = {
                // Tudi ce seznamov ni ali je kaj padlo: zahteve ne cakajo do roka.
                koncaj(ZascitaZagona.Del.DREVO, "seznamov ni ali niso berljivi")
                koncaj(ZascitaZagona.Del.EASYLIST, "seznamov ni ali niso berljivi")
            },
            onPartialLists = { lists ->
                val t = System.nanoTime()
                // 12. 11. 2026: ob zagonu iz predprevedenega nabora (seznam se ni bral): zahteve podvirov so na prevod EasyList (1,7 s na
                // TV) cakale do 0,8 s na klic. Neveljaven ali pokvarjen predpomnilnik: seznam se prebere in prevede kot prej.
                var izPredpomnilnika = false
                var branjeNeuspelo = false
                var vsebina = lists
                val kl = predpomnilnikKljuc
                if (kl != null) {
                    val nabor = FilterSetPredpomnilnik.preberi(predpomnilnikDatoteka(app), kl)
                    if (nabor != null) {
                        filterRuleCount = AdBlockEngine.installPrecompiled(nabor)
                        koncaj(ZascitaZagona.Del.EASYLIST, "$filterRuleCount pravil iz predpomnilnika, nalaganje ${(System.nanoTime() - t) / 1_000_000} ms")
                        izPredpomnilnika = true
                    } else {
                        android.util.Log.w("SafeerSecurity", "Predpomnilnik EasyList neveljaven: seznam se prevede")
                        branjeNeuspelo = true
                        vsebina = agent?.loadSaved(BRSKALNIK_VIRI.filter { it.raw }.map { it.id }.toSet()) ?: lists
                    }
                }
                if (!izPredpomnilnika) {
                    if (FilterSetPredpomnilnik.imaSuroveSezname(vsebina)) {
                        filterRuleCount = AdBlockEngine.installFilterLists(vsebina.filter { it.source.raw })
                    } else {
                        android.util.Log.w("SafeerSecurity", "EasyList neberljiv: obstojeci nabor ostane (ne prazen)")
                    }
                    koncaj(ZascitaZagona.Del.EASYLIST, "$filterRuleCount pravil, prevod ${(System.nanoTime() - t) / 1_000_000} ms")
                }
                // Kazalo je bilo odprto brez branja vsebine; tu (v ozadju) se preverimo CRC celotne datoteke.
                if (!ThreatBlockEngine.preveriKazalo()) {
                    android.util.Log.w("SafeerSecurity", "Kazalo grozenj poskodovano: gradim znova iz seznamov")
                    try { kazalo.delete() } catch (_: Exception) { }
                    agent?.reloadSaved()
                }
                // Zapis predpomnilnika po preverbi kazala in z najnizjo prioriteto (N3): ne tekmuje z nalaganjem prve strani.
                if (!izPredpomnilnika) {
                    val nit = Thread.currentThread(); val staraPrioriteta = nit.priority
                    try { nit.priority = Thread.MIN_PRIORITY } catch (_: Exception) { }
                    try { zapisiPredpomnilnik(app, vsebina, branjeNeuspelo) }
                    finally { try { nit.priority = staraPrioriteta } catch (_: Exception) { } }
                }
            },
        ) { lists ->
            val t = System.nanoTime()
            val grozenj = lists.filter { jeGrozenj(it.source) }
            ruleCount = ThreatBlockEngine.rebuildFromLists(grozenj, kazalo, razlicica)
            koncaj(ZascitaZagona.Del.DREVO, "zgrajeno iz ${grozenj.size} seznamov, ${ThreatBlockEngine.steviloDomen} domen, " +
                "${(System.nanoTime() - t) / 1_000_000} ms")
            val te = System.nanoTime()
            if (FilterSetPredpomnilnik.imaSuroveSezname(lists)) {
                filterRuleCount = AdBlockEngine.installFilterLists(lists.filter { it.source.raw })
                koncaj(ZascitaZagona.Del.EASYLIST, "$filterRuleCount pravil, prevod ${(System.nanoTime() - te) / 1_000_000} ms")
                zapisiPredpomnilnik(app, lists, false)
            } else {
                // S2: neberljiv surov seznam ne sme zamenjati veljavnega nabora s praznim.
                android.util.Log.w("SafeerSecurity", "EasyList neberljiv ob osvezitvi: obstojeci nabor ostane")
                koncaj(ZascitaZagona.Del.EASYLIST, "obstojeci nabor ostane")
            }
            // Nabora Scita brskalnik ne gradi vec (4. 11. 2026): to dela proces :scit (startSamoScit), ki ima tudi HaGeZi PRO.
            android.util.Log.i("SafeerSecurity", "Seznami v uporabi: ${lists.joinToString { "${it.source.name} (${it.entries.size})" }}" +
                "; pravila EasyList: $filterRuleCount; pravila za skrivanje: ${AdBlockEngine.cosmeticRuleCount}")
        }
        agent = listAgent

        // Sinhrono: metapodatki (nekaj majhnih datotek) in glava kazala. Izmerjeno v JVM na 300 000 domenah: glej DomenTest.
        val t = System.nanoTime()
        val shranjeni = try { listAgent.savedInfo() } catch (_: Exception) { emptyList() }
        val grozenj = shranjeni.filter { jeGrozenj(it.source) }
        val kljuc = ThreatBlockEngine.kljucKazala(grozenj.map { it.source.id to it.sha256 }, razlicica)
        val odprto = ThreatBlockEngine.odpriKazalo(kazalo, kljuc)
        val ms = (System.nanoTime() - t) / 100_000 / 10.0
        if (odprto) {
            ruleCount = grozenj.sumOf { it.count }
            koncaj(ZascitaZagona.Del.DREVO, "kazalo mmap, ${ThreatBlockEngine.steviloDomen} domen, odprtje $ms ms")
        } else {
            android.util.Log.i("SafeerSecurity", "Kazalo grozenj ni v uporabi (${if (kljuc == null) "ni shranjenih seznamov" else "zgrajeno iz drugih seznamov ali ga ni"}): " +
                "drevo se gradi v ozadju (preverba $ms ms)")
        }
        // Ce je kazalo zgrajeno iz istih seznamov, ob zagonu beremo samo EasyList (raw); ce je veljaven se predprevedeni nabor
        // EasyList (kljuc = SHA-256 seznamov iz metapodatkov + razlicica aplikacije), ne beremo nicesar (glej onPartialLists).
        // S1 (pregled 3): odtis razclenjevalnika se prvic izracuna tu, na klicatelju (glavna nit); izjema ne sme prekiniti start()
        // pred listAgent.start(): null = predpomnilnik ne velja, seznam se prevede kot prej.
        val tOdtis = System.nanoTime()
        predpomnilnikKljuc = if (odprto) try {
            kljucZaBranje(shranjeni)?.takeIf { FilterSetPredpomnilnik.glavaVelja(predpomnilnikDatoteka(app), it) }
        } catch (e: Throwable) {
            android.util.Log.w("SafeerSecurity", "Kljuc predpomnilnika EasyList ni izracunan: ${e.message}")
            null
        } else null
        android.util.Log.i("SafeerSecurity", "Kljuc predpomnilnika EasyList: ${(System.nanoTime() - tOdtis) / 1000 / 1000.0} ms, ${if (predpomnilnikKljuc != null) "velja" else "ne velja"}")
        listAgent.start(if (!odprto) null else if (predpomnilnikKljuc != null) emptySet() else BRSKALNIK_VIRI.filter { it.raw }.map { it.id }.toSet())
    }

    /**
     * Samo za proces :scit (ScitStoritev, ob vsakem zagonu Scita): agent nad domenskimi seznami ([si.safeer.tv.scit.ScitViri]),
     * ki zgradi in posodablja samo nabor Scita (domene.bin, grozne.bin). Brez EasyList, drevesa grozenj, kazala,
     * ZascitaZagona in podpisanega vira: te rabi samo brskalnik, Scit kot filter DNS ne (4. 11. 2026; prej je :scit zagnal
     * [start] in v svojem procesu zgradil vse). Ista mapa in isti viri kot v brskalniku (isti kljuc vira, isti format nabora),
     * isti razpored preverjanja (12 s po zagonu, nato vsakih 6 h); svoja datoteka stanja, da ne zadrzi preverbe brskalnika.
     */
    @Synchronized
    fun startSamoScit(context: Context) {
        if (agent != null || agentScit != null) return
        si.safeer.tv.scit.AbuseChKljuc.init(context)
        val app = context.applicationContext
        // Lastnik 9. 11. 2026: filtri po meri (uporabnik doda naslov seznama) gredo samo v nabor Scita.
        val viri = si.safeer.tv.scit.ScitViri.viri(SOURCES) + si.safeer.tv.scit.ScitViri.viriIp(SOURCES) + si.safeer.tv.scit.ScitPoMeri.viri(app)
        val listAgent = ThreatListAgent(File(app.filesDir, "threat-lists"), viri,
            stateName = "agent-scit.state") { lists ->
            try { si.safeer.tv.scit.Scit.osveziNabor(app, lists) } catch (e: Throwable) {
                android.util.Log.w("SafeerScit", "Nabora za Scit ni bilo mogoce zgraditi: ${e.message}")
            }
        }
        agentScit = listAgent
        // Nabor ze zgrajen iz shranjenih seznamov: ob zagonu ne beremo nicesar (kot v [start]); sicer jih preberemo in zgradimo.
        val shranjeni = try { listAgent.savedInfo() } catch (_: Exception) { emptyList() }
        val veljaven = shranjeni.isNotEmpty() && try { si.safeer.tv.scit.Scit.naborVeljaven(app, shranjeni) } catch (_: Throwable) { false }
        listAgent.start(if (veljaven) emptySet() else null)
    }

    /** Del zascite je pripravljen: dnevnik s casom tega dela (in skupnim, ko je pripravljeno vse). */
    private fun koncaj(del: ZascitaZagona.Del, opis: String) {
        val cas = ZascitaZagona.koncan(del) ?: return
        val ime = when (del) {
            ZascitaZagona.Del.DREVO -> "drevo grozenj pripravljeno"
            ZascitaZagona.Del.PODPISANI -> "podpisani vir pripravljen"
            ZascitaZagona.Del.EASYLIST -> "EasyList pripravljen"
        }
        android.util.Log.i("SafeerSecurity", "Zascita ob zagonu: $ime po $cas ms ($opis)" +
            if (ZascitaZagona.pripravljeno) "; vse pripravljeno po ${ZascitaZagona.pripravljenoPoMs} ms" else "")
    }

    /**
     * Proces :scit: seznami takoj (nov kljuc abuse.ch, filter po meri dodan, vklopljen ali odstranjen). Agent Scita se
     * ustvari znova, ker so viri del agenta; nato preveri vse sezname. Vrne false, ce agent Scita ne tece.
     */
    @Synchronized
    fun osveziScitZdaj(context: Context): Boolean {
        // CodeRabbit 9. 11. 2026: agent Scita se ne tece - zazenemo ga (zahteva se ne izgubi); requestUpdate je prisilen.
        if (agentScit == null && agent != null) return agent?.requestUpdate() ?: false
        agentScit?.stop()
        agentScit = null
        startSamoScit(context)
        return agentScit?.requestUpdate() ?: false
    }

    /** Takojšnje preverjanje (gumb "Posodobi sezname"); [onComplete] dobi število pravil v uporabi. */
    fun updateFeedsAsync(context: Context, onComplete: ((totalRules: Int) -> Unit)? = null) {
        start(context)
        val requested = agent?.requestUpdate { onComplete?.invoke(ruleCount) } ?: false
        if (!requested) onComplete?.invoke(ruleCount)
    }

    fun statusLine(): String {
        val lists = agent?.lists.orEmpty()
        if (lists.isEmpty()) return UiText.get(R.string.ui_lists_first_download)
        return UiText.get(R.string.ui_lists_status, lists.filter { !it.source.raw && !it.source.dnsOnly }.sumOf { it.count }, lists.size, BRSKALNIK_VIRI.size) +
            " · EasyList: $filterRuleCount (+${AdBlockEngine.cosmeticRuleCount})"
    }
}
