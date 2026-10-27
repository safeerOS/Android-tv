package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject

private var napak = 0
private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun koda(url: String): String = try { SpletniKlepetiPravila.preveriNaslov(url); "veljaven" } catch (e: SpletniKlepetiPravila.NeveljavenNaslov) { e.koda }

fun main() {
    val P = SpletniKlepetiPravila
    println("Neprebrano iz naslova strani (enako kot Linux tests/test_spletni_klepeti.py)")
    val primeri = mapOf(
        "(3) WhatsApp" to 3, "[12] Element" to 12, "(99+) Facebook" to 99, " (7)  Telegram" to 7,
        "4 · Klepet" to 4, "7 - Pošta" to 7, "Inbox (5) - ime@example.org - Gmail" to 5, "Klepet (2)" to 2,
        "(2) Chat | Microsoft Teams" to 2, "* Slack | splošno" to 1, "• Nekaj" to 1, "! Omemba" to 1,
        "(1 234) Pošta" to 1234, "(1 234) Pošta" to 1234)
    for ((n, st) in primeri) preveriEnako(n, st, P.neprebrano(n))
    for (n in listOf("", null, "WhatsApp", "Telegram Web", "Srečanje (2026) - Teams", "Zoom 5.0", "2048 igra",
            "Inbox - ime@example.org - Gmail", "Klepet | (3)", "x".repeat(2000))) preveriEnako("brez vzorca: ${n?.take(30)}", 0, P.neprebrano(n))
    preveriEnako("sest stevk ni stevilo", 0, P.neprebrano("(123456) X"))
    preveriEnako("meja", P.NAJVEC_NEPREBRANIH, P.neprebrano("(99999) X"))

    println("Lasten naslov")
    preveriEnako("brez poti", "https://chat.example.org/", P.preveriNaslov("https://chat.example.org"))
    preveriEnako("brez sheme", "https://chat.example.org/team", P.preveriNaslov("chat.example.org/team"))
    preveriEnako("velike crke, vrata, poizvedba", "https://chat.example.org:8443/x?y=1#z", P.preveriNaslov("HTTPS://Chat.Example.ORG:8443/x?y=1#z"))
    preveriEnako("IPv4", "https://192.168.1.5/", P.preveriNaslov("https://192.168.1.5/"))
    preveriEnako("IDN", "https://xn--evapii-h2aeb.si/", P.preveriNaslov("https://čevapčiči.si/"))
    preveriEnako("homograf kot punycode", "https://xn--pple-43d.com/", P.preveriNaslov("https://аpple.com/"))
    preveriEnako("ime iz naslova homografa", "xn--pple-43d.com", P.imeIzNaslova(P.preveriNaslov("https://аpple.com/")))
    val slabi = mapOf("" to "prazno", "   " to "prazno", "http://chat.example.org" to "https", "ftp://x.org" to "https",
        "javascript:alert(1)" to "https", "file:///etc/passwd" to "https",
        "https://ime:geslo@chat.example.org" to "poverilnice", "https://ime@chat.example.org" to "poverilnice",
        "https://" to "gostitelj", "https://chat example.org" to "gostitelj", "https://a\nb.org" to "gostitelj",
        "https://-x-.org" to "gostitelj", "https://x.org:99999/" to "gostitelj", "https://x.org:0/" to "gostitelj",
        "https://x.org/" + "a".repeat(3000) to "dolzina",
        "data:text/html,<script>alert(1)</script>" to "https", "JaVaScRiPt:alert(1)" to "https",
        "javascript://chat.example.org/%0aalert(1)" to "https", "https://evil.example\\@chat.example.org" to "poverilnice",
        "https://300.1.1.1/" to "gostitelj")
    for ((u, k) in slabi) preveriEnako("neveljaven ${u.take(40)}", k, koda(u))

    println("Okno storitve")
    preveriEnako("https ostane", true, P.ostaneVOknu("https://web.example.org/"))
    preveriEnako("javascript ne ostane", false, P.ostaneVOknu("javascript:alert(1)"))
    preveriEnako("data ne ostane", false, P.ostaneVOknu("data:text/html,x"))
    preveriEnako("mailto sistemu", true, P.zaSistem("mailto:a@b.si"))
    preveriEnako("file ne sistemu", false, P.zaSistem("file:///sdcard/x"))
    preveriEnako("content ne sistemu", false, P.zaSistem("content://x/y"))
    preveriEnako("javascript ne sistemu", false, P.zaSistem("javascript:x"))
    preveriEnako("profil samo crke, stevke, -", "sporocila-ab12cd34", P.profil("ab12/cd34"))
    preveriEnako("namizni UA z razlicico WebViewa", true,
        P.namizniUa("Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36")
            .contains("X11; Linux x86_64") && P.namizniUa("x Chrome/141.0.7390.122 y").contains("Chrome/141.0.7390.122 Safari"))
    preveriEnako("namizna stran privzeto na sirokem zaslonu", true, P.namiznaPrivzeto(true))
    preveriEnako("namizna stran privzeto tudi na telefonu (Messenger mobilno: samo »prenesi aplikacijo«)", true, P.namiznaPrivzeto(false))

    println("Katalog")
    val surovo = JSONObject().put("pripisi", JSONObject().put("sl", JSONObject().put("osebno", "osebni")).put("en", JSONObject().put("osebno", "personal")))
        .put("storitve", JSONArray()
            .put(JSONObject().put("id", "zeta").put("ime", "Zeta Klepet").put("splet", "https://zeta.example.org/").put("izmerjeno", false))
            .put(JSONObject().put("id", "teams-osebni").put("ime", "Microsoft Teams").put("pripis", "osebno").put("splet", "https://teams.example.org/").put("izmerjeno", true))
            .put(JSONObject().put("id", "slab").put("ime", "Slab").put("splet", "http://slab.example.org/"))
            .put(JSONObject().put("id", "zlo/../").put("ime", "Zlo").put("splet", "https://zlo.example.org/"))
            .put(JSONObject().put("id", "zeta").put("ime", "Podvojen").put("splet", "https://dvojnik.example.org/"))
            .put(JSONObject().put("id", "ahoj").put("ime", "Ahój").put("splet", "https://ahoj.example.org/"))).toString()
    val k = P.razcleni(surovo)
    preveriEnako("brez http, slabega id in podvojenih", listOf("zeta", "teams-osebni", "ahoj"), k.storitve.map { it.id })
    preveriEnako("pripis v jeziku", "Microsoft Teams (osebni)", k.ime(k.poId("teams-osebni")!!, "sl"))
    preveriEnako("pripis: neznan jezik -> en", "Microsoft Teams (personal)", k.ime(k.poId("teams-osebni")!!, "xx"))
    preveriEnako("iskanje brez sumnikov", listOf("ahoj"), k.isci("AHOJ", "sl").map { it.id })
    preveriEnako("iskanje po pripisu", listOf("teams-osebni"), k.isci("osebni", "sl").map { it.id })
    preveriEnako("prazno: izmerjene najprej, nato po imenu", listOf("teams-osebni", "ahoj", "zeta"), k.isci("", "sl").map { it.id })

    // Pravi katalog iz assets (ce test tece v repozitoriju): vsi naslovi https, nic privzeto, Telegram ima ikono.
    val pot = java.io.File(System.getProperty("katalog") ?: "assets/sporocila/spletni-klepeti.json")
    if (pot.isFile) {
        val pravi = P.razcleni(pot.readText())
        val vnosov = JSONObject(pot.readText()).getJSONArray("storitve").length()
        preveriEnako("pravi katalog: noben vnos ne izpade", vnosov, pravi.storitve.size)
        preveriEnako("pravi katalog: Telegram z ikono", true, pravi.poId("telegram")?.ikona?.isNotEmpty())
        preveriEnako("pravi katalog: vsi https", true, pravi.storitve.all { it.splet.startsWith("https://") })
        preveriEnako("pravi katalog: vsaj 80 storitev", true, pravi.storitve.size >= 80)
    } else println("  (pravi katalog ni najden: ${pot.path})")

    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
