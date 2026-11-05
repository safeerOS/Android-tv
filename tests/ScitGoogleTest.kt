package si.safeer.tv

import com.safeer.threatfeed.FeedSource
import com.safeer.threatfeed.PlainList
import com.safeer.threatfeed.PlainListSource
import com.safeer.threatfeed.ThreatIndex

/**
 * Scit grozenj na Googlovih gostiteljih (5. 11. 2026, kot telefon tests/ScitGoogleTest.kt): lazna prijava na
 * sites.google.com / script.google.com je blokirana, iskanje in prijava Googla pa nikoli - tudi ce vir (napaka ali
 * zastrupitev) navede google.com, sites.google.com ali web.app kot malware. Svoj JVM: ThreatBlockEngine je enojec.
 */
fun main() {
    val vir = listOf(FeedSource("fixture", "CC0-1.0", "Fixture", "https://example.invalid/"))
    val malware = 1 shl 16
    val phishing = 2 shl 16
    val lazne = listOf(
        "https://sites.google.com/view/nlb-prijava-2026",
        "https://script.google.com/macros/s/AKfyc-lazno/exec",
        "https://docs.google.com/forms/d/e/1FAIpQ-lazno/viewform",
        "https://storage.googleapis.com/zlo-vedro/prijava.html",
        "https://www.googleapis.com/download/storage/v1/b/zlo-vedro/o/prijava.html",
        "https://lh3.googleusercontent.com/zlo/prijava.html",
        "https://lazna-banka.web.app/prijava",
        "https://zlo-vedro.storage.googleapis.com/prijava.html",
    )
    val urls = HashMap<String, Int>()
    for (u in lazne.dropLast(2)) urls[u] = phishing
    urls["https://storage.googleapis.com/zlo-vedro/prijava.html"] = malware
    // Podtaknjeni vnosi: vstopne strani izdelkov ter iskanje in prijava Googla.
    val podtaknjeniNaslovi = hashMapOf(
        "https://sites.google.com/" to malware, "https://docs.google.com/forms" to phishing,
        "https://www.google.si/search?q=safeer" to malware, "https://accounts.google.com/signin" to malware,
        "https://accounts.google.com/" to malware, "https://oauth2.googleapis.com/token" to malware,
    )
    val podtaknjeneDomene = hashMapOf(
        "sites.google.com" to malware, "script.google.com" to malware, "google.com" to malware, "google.si" to malware,
        "web.app" to malware, "googleusercontent.com" to malware, "googleapis.com" to malware, "storage.googleapis.com" to malware,
        "www.googleapis.com" to malware, "accounts.youtube.com" to malware, "oauth2.googleapis.com" to malware,
        // google.co.zz ni Googlova domena (ni na seznamu supported_domains): lahko jo registrira kdorkoli, zato ni koren.
        "google.co.zz" to phishing,
    )
    val projekti = hashMapOf("lazna-banka.web.app" to phishing, "zlo-vedro.storage.googleapis.com" to malware)

    // Ujemalnik kot v SignedThreatIntel.install (zadetekVira), da preizkus preveri tudi povezavo podpisanega vira.
    fun namesti(index: ThreatIndex) {
        ThreatBlockEngine.signedFeedMatcher = { u, h -> SignedThreatIntel.zadetekVira(u, h, index::matchUrlRule, index::matchHostWhere) }
        ThreatBlockEngine.signedUrlMatcher = { u -> SignedThreatIntel.toMatchResult(index.matchUrlRule(u)) }
        ThreatBlockEngine.signedHostMatcher = { h, sme -> SignedThreatIntel.toMatchResult(index.matchHostWhere(h, sme)) }
    }

    // 1. Navaden vir: samo lazne poti in projekti.
    namesti(ThreatIndex(HashMap(projekti), HashMap(), HashMap(urls), vir))
    for (u in lazne) check(ThreatBlockEngine.isThreat(u)) { "lazna stran ni blokirana: $u" }
    check(ThreatBlockEngine.isCriticalThreat(ThreatBlockEngine.checkThreat("https://storage.googleapis.com/zlo-vedro/prijava.html")?.category))
    check(!ThreatBlockEngine.isThreat("https://sites.google.com/view/prava-stran-sole"))
    println("PASS: lazne poti na sites, script, docs/forms, storage, www.googleapis, googleusercontent in projekti")

    // 2. Vir s podtaknjenimi vnosi (celotne domene, skupni gostitelji, iskanje in prijava kot malware).
    val vsiNaslovi = HashMap(urls).also { it.putAll(podtaknjeniNaslovi) }
    val vseDomene = HashMap(projekti).also { it.putAll(podtaknjeneDomene) }
    namesti(ThreatIndex(vseDomene, HashMap(), vsiNaslovi, vir))
    for (u in lazne) check(ThreatBlockEngine.isThreat(u)) { "lazna stran ni blokirana ob podtaknjenih vnosih: $u" }
    val varni = listOf(
        "https://www.google.si/search?q=safeer",
        "https://www.google.com/search?q=safeer",
        "https://www.google.com./",
        "https://google.si/",
        "https://www.google.co.uk/",
        "https://accounts.google.com/",
        "https://accounts.google.com/signin",
        "https://myaccount.google.com/",
        "https://accounts.youtube.com/accounts/SetSID",
        "https://oauth2.googleapis.com/token",
        "https://mail.google.com/mail/u/0/",
        "https://sites.google.com/",
        "https://sites.google.com/view/prava-stran-sole",
        "https://script.google.com/home",
        "https://docs.google.com/forms",
        "https://www.googleapis.com/oauth2/v3/certs",
        "https://druga.web.app/",
        "https://drugo-vedro.storage.googleapis.com/x.html",
        "https://lh3.googleusercontent.com/a/slika",
        "https://fonts.gstatic.com/s/roboto.woff2",
    )
    for (u in varni) check(!ThreatBlockEngine.isThreat(u)) { "blokirano, cesar ne smemo: $u" }
    // Ponarejen »Google« z neobstojeco pripono ni koren in ga vir sme blokirati.
    check(ThreatBlockEngine.isThreat("https://www.google.co.zz/prijava")) { "google.co.zz ni Googlov koren" }
    check(ThreatBlockEngine.isThreat("https://accounts.google.co.zz/")) { "accounts.google.co.zz ni Googlov koren" }
    println("PASS: Googlovo iskanje, prijava in skupni gostitelji niso blokirani zaradi vnosa v viru; google.co.zz je")

    // 3. Lokalni kriticni seznam (samo gostitelji): koren in skupni gostitelj nikoli, projekt in vedro da.
    val urlhaus = PlainListSource("urlhaus", "URLhaus", "https://example.invalid/u", "Zlonamerna koda (Malware)", "urlhaus")
    ThreatBlockEngine.signedFeedMatcher = null
    ThreatBlockEngine.signedUrlMatcher = null
    ThreatBlockEngine.signedHostMatcher = null
    ThreatBlockEngine.rebuildFromLists(listOf(PlainList(urlhaus, listOf(
        "google.com", "www.google.com", "accounts.google.com", "script.google.com", "appspot.com", "evil-c2.appspot.com",
        "storage.googleapis.com", "zlo-vedro.storage.googleapis.com", "googleapis.com", "evil-c2.example",
    ), 0)))
    for (u in listOf("https://evil-c2.appspot.com/gate", "https://zlo-vedro.storage.googleapis.com/x", "https://evil-c2.example/"))
        check(ThreatBlockEngine.isThreat(u)) { "lokalni seznam ne blokira: $u" }
    for (u in listOf("https://www.google.com/", "https://accounts.google.com/", "https://script.google.com/home",
        "https://mail.google.com/", "https://drugi-projekt.appspot.com/", "https://drugo-vedro.storage.googleapis.com/x",
        "https://storage.googleapis.com/vedro/x")) check(!ThreatBlockEngine.isThreat(u)) { "lokalni seznam blokira: $u" }
    println("PASS: lokalni seznam ne blokira korena Googla ne skupnih gostiteljev, projekt in vedro da")

    // 4. Sejna izjema za lazno pot ne odpre drugih laznih poti na istem gostitelju; kriticna pot nima obvoza.
    namesti(ThreatIndex(HashMap(projekti), HashMap(), HashMap(urls), vir))
    val m = ThreatBlockEngine.checkThreat("https://sites.google.com/view/nlb-prijava-2026")!!
    ThreatBlockEngine.allowForSession(m.matchedDomain)
    check(!ThreatBlockEngine.isThreat("https://sites.google.com/view/nlb-prijava-2026"))
    check(ThreatBlockEngine.isThreat("https://script.google.com/macros/s/AKfyc-lazno/exec"))
    ThreatBlockEngine.allowForSession("https://storage.googleapis.com/zlo-vedro/prijava.html")
    check(ThreatBlockEngine.isThreat("https://storage.googleapis.com/zlo-vedro/prijava.html"))
    println("PASS: sejna izjema samo za ta naslov, kriticna pot brez obvoza")
}
