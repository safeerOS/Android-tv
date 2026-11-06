package si.safeer.tv

import android.content.Context
import android.util.Log
import com.safeer.threatfeed.FeedMatch
import com.safeer.threatfeed.SignedFeedService
import com.safeer.threatfeed.SignedFeedStore
import java.io.File

/**
 * 🛡️ Safeer Threat Intelligence
 * Dodatna plast zaščite: preverjen, z Ed25519 podpisan seznam nevarnih strani, ki ga objavlja
 * https://github.com/memelandfaner/safeer-threat-intel. Brskalnik prenese le dve javni datoteki
 * (anonimen GET, brez piškotkov in identifikatorjev); vse preverjanje poteka na napravi. Ob vsaki
 * napaki ostane v uporabi zadnji preverjen seznam, vgrajena zaščita pa deluje tudi brez njega.
 */
object SignedThreatIntel {
    private const val TAG = "SafeerThreatIntel"

    /**
     * Zaupanja vredni javni ključi (key_id -> base64 Ed25519). Brez ključa ta plast ostane izklopljena.
     *
     * Produkcijski ključ "safeer-prod-2026-09" (ustvarjen 11. 9. 2026 na računalniku lastnika; zasebni del
     * nikoli ni bil v repozitoriju, sliki Docker ali APK). Nov ključ se doda sem pred menjavo na strežniku,
     * star se odstrani šele, ko ga strežnik ne uporablja več.
     */
    val TRUSTED_KEYS: Map<String, String> = mapOf(
        "safeer-prod-2026-09" to "Z4fKgHcD1wdrYBdvybqszN/z355SrGUREUhJyQJISpA=",
    )
    val BASE_URLS = listOf("https://intel.safeer.si")

    @Volatile
    private var store: SignedFeedStore? = null
    private var service: SignedFeedService? = null

    val isEnabled: Boolean get() = TRUSTED_KEYS.isNotEmpty()

    @Synchronized
    fun start(context: Context) {
        // Brez kljuca te plasti ni: prvo nalaganje zascite nanjo ne caka.
        if (!isEnabled) { koncaj("ni kljuca"); return }
        if (service != null) return
        val feedStore = SignedFeedStore(
            File(context.applicationContext.filesDir, "threat-intel"), "threats", TRUSTED_KEYS, BASE_URLS,
        )
        val feedService = SignedFeedService(feedStore, onChange = { updated ->
            val bundle = updated.bundle
            Log.i(TAG, "Preverjen seznam groženj v${bundle?.manifest?.version}: ${bundle?.ruleCount} pravil")
        }, onFirstLoad = {
            val b = feedStore.bundle
            koncaj(if (b == null) "ni shranjenega vira" else "${b.ruleCount} pravil, ${if (b.index.mapped) "kazalo mmap" else "v kopici"}")
        })
        store = feedStore
        // Pravila gostitelja samo za gostitelje, ki jih Scit sme blokirati (ScitGostitelji): resnejsi vnos za celotno
        // google.com ali web.app sicer zakrije pravilo projekta ali gostitelja pod njim (5. 11. 2026).
        ThreatBlockEngine.signedFeedMatcher = { url, host -> zadetekVira(url, host, feedStore::matchUrlRule, feedStore::matchHostWhere) }
        ThreatBlockEngine.signedUrlMatcher = { url -> toMatchResult(feedStore.matchUrlRule(url)) }
        ThreatBlockEngine.signedHostMatcher = { host, sme -> toMatchResult(feedStore.matchHostWhere(host, sme)) }
        // 4. 11. 2026: shranjeni vir iz kazala (mmap) SINHRONO, pred prvim zavihkom - prej preverba in gradnja HashMap v
        // ozadju (TV: zascita ob zagonu sele po 6,2 s). Podpis in SHA-256 se preverita znova v ozadju (SignedFeedStore.recheck).
        val t = System.nanoTime()
        if (feedStore.loadFast()) {
            koncaj("kazalo mmap, ${feedStore.bundle?.ruleCount} pravil, odprtje ${(System.nanoTime() - t) / 100_000 / 10.0} ms")
        }
        if (feedService.start()) service = feedService else koncaj("storitev ni zagnana")
    }

    private fun koncaj(opis: String) {
        val cas = ZascitaZagona.podpisaniKoncan() ?: return
        Log.i("SafeerSecurity", "Zascita ob zagonu: podpisani vir pripravljen po $cas ms ($opis)" +
            if (ZascitaZagona.pripravljeno) "; vse pripravljeno po ${ZascitaZagona.pripravljenoPoMs} ms" else "")
    }

    /** Takojšnje preverjanje novega seznama (gumb "Posodobi sezname"). */
    fun requestUpdate(callback: ((installed: Boolean) -> Unit)? = null): Boolean = service?.requestUpdate(callback) ?: false

    /** Kratko stanje za pogovorno okno statistike. */
    fun statusLine(): String {
        if (!isEnabled) return UiText.get(R.string.ui_signed_intel_waiting)
        val bundle = store?.bundle ?: return UiText.get(R.string.ui_signed_intel_not_downloaded)
        return UiText.get(R.string.ui_signed_intel_rules, bundle.ruleCount, bundle.manifest.version)
    }

    /** Pravilo naslova in pravila gostitelja, ki jih Scit sme uporabiti; kriticna kategorija ima prednost (tests/ScitGoogleTest.kt). */
    internal fun zadetekVira(url: String, host: String, poNaslovu: (String?) -> FeedMatch?,
                             poGostitelju: (String?, (String) -> Boolean) -> FeedMatch?): DomainSuffixTrie.MatchResult? {
        val naslov = toMatchResult(poNaslovu(url))
        val gostitelj = toMatchResult(poGostitelju(host, ScitGostitelji::smeGostitelj))
        return listOfNotNull(naslov, gostitelj).firstOrNull { ThreatBlockEngine.isCriticalThreat(it.category) } ?: naslov ?: gostitelj
    }

    internal fun toMatchResult(match: FeedMatch?): DomainSuffixTrie.MatchResult? {
        if (match == null) return null
        val category = when (match.category) {
            "botnet_c2" -> "Botnet C2 strežnik"
            "malware" -> "Zlonamerna koda (Malware)"
            "phishing" -> "Spletno ribarjenje (Phishing)"
            "scam" -> "Prevara (Scam)"
            else -> return null // oglasi in sledilci niso varnostna grožnja
        }
        return DomainSuffixTrie.MatchResult(
            isMatched = true,
            matchedDomain = match.indicator,
            category = category,
            sourceFeed = "Safeer Threat Intelligence · ${match.source.name}",
        )
    }
}
