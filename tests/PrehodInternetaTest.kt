package si.safeer.tv.link

import android.content.Context
import org.json.JSONObject
import si.safeer.tv.cast.KrogNaprave
import si.safeer.tv.cast.KrogZaupanja
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pravi prehod interneta (AndroidApplicationGateway) na JVM z nadomestki Androida (tests/stubs/prehod) in streznikom na
 * zanki. Pregled 10. 11. 2026: umik naprave iz kroga zapre njene odprte tokove in zbrise dovoljenje interneta (2.5).
 */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private fun pocakaj(opis: String, rokMs: Long = 5_000L, pogoj: () -> Boolean) {
    val konec = System.currentTimeMillis() + rokMs
    while (!pogoj() && System.currentTimeMillis() < konec) Thread.sleep(20)
    preveri(opis, pogoj())
}

private fun novKljuc(): String {
    val g = java.security.KeyPairGenerator.getInstance("EC")
    g.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    return java.util.Base64.getEncoder().encodeToString(g.generateKeyPair().public.encoded)
}

private class Oder {
    val ctx = Context()
    val poslano = CopyOnWriteArrayList<JSONObject>()
    val streznik = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val sprejete = CopyOnWriteArrayList<Socket>()
    val krog = KrogZaupanja()
    val kljuc = novKljuc()
    val jedro = KrogZaupanja.idIzKljuca(kljuc)
    val naprava = "$jedro-control"
    val prehod: AndroidApplicationGateway

    init {
        KrogNaprave.krog = krog
        krog.dodaj(KrogZaupanja.Clan("tv", novKljuc(), "TV", "tv", 100.0, "tv"))
        krog.dodaj(KrogZaupanja.Clan(naprava, kljuc, "Racunalnik", "linux", 100.0, "tv"))
        ctx.getSharedPreferences(AndroidApplicationGateway.PREFS, Context.MODE_PRIVATE).edit().putBoolean("gateway_enabled", true).apply()
        AndroidApplicationGateway.dovoljenja(ctx).odloci(jedro, true, "Racunalnik")
        Thread({ try { while (true) sprejete.add(streznik.accept()) } catch (_: Throwable) { } }, "preizkus-streznik").apply { isDaemon = true; start() }
        prehod = AndroidApplicationGateway(ctx, AndroidInternetPoti(ctx), varenNaslov = { true }) { s -> poslano.add(JSONObject(s.toString())); true }
        // Kot KrogNaprave.krog v aplikaciji: sprememba kroga pride do prijavljenih odzivov (prehod se prijavi sam).
        krog.obSpremembi = { KrogNaprave.javiSpremembo(ctx) }
    }

    fun sporocilo(tip: String, tok: String = "") = JSONObject().put("type", tip).put("sender", naprava).put("v", 2).put("epoch", 7L)
        .apply { if (tok.isNotEmpty()) put("stream_id", tok) }

    fun odpri(tok: String) {
        prehod.obdelaj(sporocilo("internet.open", tok).put("host", "127.0.0.1").put("port", streznik.localPort))
    }

    fun zadnje(tip: String, tok: String): JSONObject? =
        poslano.lastOrNull { it.optString("type") == tip && it.optString("stream_id") == tok }

    fun podatki(tok: String, besedilo: String, odmik: Long) {
        prehod.obdelaj(sporocilo("internet.data", tok).put("off", odmik)
            .put("data", java.util.Base64.getEncoder().encodeToString(besedilo.toByteArray())))
    }

    fun konec() { prehod.close(); streznik.close(); sprejete.forEach { try { it.close() } catch (_: Throwable) { } } }
}

/** 2.5: odprt tok -> umik naprave -> tok zaprt, dovoljenje zbrisano, kos toka dobi »gone«, novo odpiranje zavrnjeno. */
private fun umikZapreTokove(izvorna: String) {
    val o = Oder()
    try {
        val tok = "tok-umik-0001"
        o.odpri(tok)
        pocakaj("tok se odpre") { o.zadnje("internet.opened", tok) != null }
        pocakaj("streznik sprejme povezavo") { o.sprejete.size == 1 }
        o.podatki(tok, "prej", 0)
        val vhod = o.sprejete[0].getInputStream()
        o.sprejete[0].soTimeout = 5_000
        val b = ByteArray(4)
        preveri("kos pred umikom pride do streznika", vhod.read(b) == 4 && String(b) == "prej")
        preveri("pred umikom en tok", o.prehod.stanje().aktivniTokovi == 1)

        preveri("umik spremeni krog", o.krog.umakni(o.naprava, "tv", 200.0))

        preveri("tok umaknjene naprave je zaprt takoj", o.prehod.stanje().aktivniTokovi == 0)
        preveri("naprava dobi konec toka z razlogom not_trusted", o.zadnje("internet.close", tok)?.optString("reason") == "not_trusted")
        preveri("streznik vidi konec povezave", try { vhod.read() == -1 } catch (_: java.io.IOException) { true })
        preveri("dovoljenje interneta umaknjene naprave je zbrisano",
            o.prehod.dovoljenja.stanje(o.jedro) == InternetDovoljenja.Stanje.NEZNANO)
        o.poslano.clear()
        o.podatki(tok, "potem", 4)
        preveri("kos zaprtega toka dobi gone", o.zadnje("internet.close", tok)?.optString("reason") == "gone")
        o.odpri("tok-umik-0002")
        pocakaj("novo odpiranje umaknjene naprave je zavrnjeno") { o.zadnje("internet.error", "tok-umik-0002")?.optString("reason") == "not_trusted" }
        Thread.sleep(200)
        preveri("streznik ni dobil nove povezave", o.sprejete.size == 1)

        // Vrnitev v krog (nov, novejsi vnos): dovoljenja ni vec, uporabnik odloca znova.
        o.krog.dodaj(KrogZaupanja.Clan(o.naprava, o.kljuc, "Racunalnik", "linux", 300.0, "tv"))
        o.odpri("tok-umik-0003")
        pocakaj("po vrnitvi v krog brez dovoljenja") { o.zadnje("internet.error", "tok-umik-0003")?.optString("reason") == "permission_required" }
        preveri("uporabnik je vprasan znova", InternetVprasanje.pokazana.contains(o.jedro))
    } finally { o.konec() }

    // Druga naprava ostane: umik ene ne zapre tokov druge.
    val p = Oder()
    try {
        p.odpri("tok-ostane-01")
        pocakaj("tok druge naprave se odpre") { p.zadnje("internet.opened", "tok-ostane-01") != null }
        p.krog.dodaj(KrogZaupanja.Clan("tretja", novKljuc(), "Tretja", "tv", 100.0, "tv"))
        p.krog.umakni("tretja", "tv", 200.0)
        preveri("tok naprave, ki je ostala v krogu, tece naprej", p.prehod.stanje().aktivniTokovi == 1)
        preveri("njeno dovoljenje ostane", p.prehod.dovoljenja.stanje(p.jedro) == InternetDovoljenja.Stanje.DOVOLJENO)
    } finally { p.konec() }

    // Prehod ne tece (npr. Link ugasnjen): umik vseeno zbrise dovoljenje.
    val q = Oder()
    q.konec()
    q.krog.umakni(q.naprava, "tv", 200.0)
    preveri("brez tekocega prehoda umik zbrise dovoljenje",
        AndroidApplicationGateway.dovoljenja(q.ctx).stanje(q.jedro) == InternetDovoljenja.Stanje.NEZNANO)

    // Povezava v aplikaciji: KrogNaprave ob spremembi kroga poklice prijavljene odzive (nadomestek jih klice sam).
    if (izvorna.isNotEmpty()) {
        val krogNaprave = java.io.File(izvorna, "si/safeer/tv/cast/KrogNaprave.kt").readText()
        preveri("KrogNaprave.obSpremembi poklice prijavljene odzive",
            krogNaprave.substringAfter("k.obSpremembi = {").substringBefore("krog = k").contains("for (odziv in poslusalci.values) try { odziv(app) }"))
    }
}

/** Najdba B: internet.open/query iz preverjene seje; naprava, ki to zna, nezascitenega ne sme vec. */
private fun zascitenoOdpiranje(izvorna: String) {
    // Cista odlocitev.
    val nikoli: (String) -> Boolean = { false }
    val vedno: (String) -> Boolean = { true }
    val j = "n-0123456789abcdef"
    preveri("kos toka ni stvar zascite odpiranja", InternetZascita.odloci("internet.data", "", j, vedno) == InternetZascita.Izid.SPREJMI)
    preveri("starejsa naprava nezasciteno: kot prej", InternetZascita.odloci("internet.open", "", j, nikoli) == InternetZascita.Izid.SPREJMI)
    preveri("zasciteno odpiranje se sprejme", InternetZascita.odloci("internet.open", j, j, nikoli) == InternetZascita.Izid.SPREJMI_ZASCITENO)
    preveri("naprava, ki zascito zna: nezasciteno odpiranje ne", InternetZascita.odloci("internet.open", "", j, vedno) == InternetZascita.Izid.ZAVRNI)
    preveri("naprava, ki zascito zna: nezascitena poizvedba ne", InternetZascita.odloci("internet.query", "", j, vedno) == InternetZascita.Izid.ZAVRNI)
    preveri("preverjen kljuc druge naprave ne velja za to oznako", InternetZascita.odloci("internet.open", "n-ffffffffffffffff", j, nikoli) == InternetZascita.Izid.ZAVRNI)
    preveri("starejsa oznaka (ne iz kljuca) z zascito se sprejme", InternetZascita.odloci("internet.open", j, "tv-dnevna", nikoli) == InternetZascita.Izid.SPREJMI_ZASCITENO)

    val o = Oder()
    try {
        // Starejsi racunalnik (zascite interneta se ne zna): dela kot prej.
        o.odpri("tok-star-0001")
        pocakaj("starejsa naprava odpre tok nezasciteno") { o.zadnje("internet.opened", "tok-star-0001") != null }
        preveri("zmoznost prehoda v stanju", InternetZascita.ZMOZNOST == "internet.e2e1")

        // Ista naprava po posodobitvi: odpiranje iz preverjene seje (ZascitaLinka doda polje z jedrom kljuca).
        o.prehod.obdelaj(o.sporocilo("internet.open", "tok-zasc-0001").put("host", "127.0.0.1").put("port", o.streznik.localPort)
            .put(InternetZascita.POLJE, o.jedro))
        pocakaj("zasciteno odpiranje uspe") { o.zadnje("internet.opened", "tok-zasc-0001") != null }
        pocakaj("streznik ima dve povezavi") { o.sprejete.size == 2 }

        // Sredisce v imenu iste naprave poslje nezasciteno odpiranje: zavrnjeno, brez povezave.
        o.poslano.clear()
        o.odpri("tok-ponaredek-1")
        preveri("nezasciteno odpiranje v imenu naprave, ki zascito zna: not_trusted",
            o.zadnje("internet.error", "tok-ponaredek-1")?.optString("reason") == "not_trusted")
        // ... tudi pod oznako drugega programa iste naprave (isti kljuc).
        o.prehod.obdelaj(JSONObject().put("type", "internet.open").put("sender", "${o.jedro}-os").put("v", 2).put("epoch", 7L)
            .put("stream_id", "tok-ponaredek-2").put("host", "127.0.0.1").put("port", o.streznik.localPort))
        preveri("nezasciteno odpiranje pod oznako drugega programa iste naprave: not_trusted",
            o.zadnje("internet.error", "tok-ponaredek-2")?.optString("reason") == "not_trusted")
        // Nezascitena poizvedba s tujo dobo ne zapre tokov prave naprave in ne izda omrezij telefona.
        o.poslano.clear()
        o.prehod.obdelaj(JSONObject().put("type", "internet.query").put("sender", o.naprava).put("v", 2).put("epoch", 99L))
        val stanje = o.poslano.lastOrNull { it.optString("type") == "internet.status" }?.optJSONObject("payload")
        preveri("nezascitena poizvedba: not_trusted brez seznama poti",
            stanje?.optString("permission") == "not_trusted" && stanje.has("paths") == false)
        // Zasciteno s kljucem druge naprave pod to oznako: zavrnjeno.
        o.prehod.obdelaj(o.sporocilo("internet.open", "tok-ponaredek-3").put("host", "127.0.0.1").put("port", o.streznik.localPort)
            .put(InternetZascita.POLJE, "n-ffffffffffffffff"))
        preveri("preverjen kljuc druge naprave: not_trusted", o.zadnje("internet.error", "tok-ponaredek-3")?.optString("reason") == "not_trusted")
        Thread.sleep(300)
        preveri("ponaredki niso odprli povezave", o.sprejete.size == 2)
        preveri("tokova prave naprave teceta naprej (tuja doba ju ni zaprla)", o.prehod.stanje().aktivniTokovi == 2)

        // Zapis prezivi ponovni zagon prehoda (nastavitve aplikacije).
        o.prehod.close()
        val drugi = AndroidApplicationGateway(o.ctx, AndroidInternetPoti(o.ctx), varenNaslov = { true }) { s -> o.poslano.add(JSONObject(s.toString())); true }
        try {
            o.poslano.clear()
            drugi.obdelaj(o.sporocilo("internet.open", "tok-ponaredek-4").put("host", "127.0.0.1").put("port", o.streznik.localPort))
            preveri("po ponovnem zagonu nezasciteno se vedno zavrnjeno", o.zadnje("internet.error", "tok-ponaredek-4")?.optString("reason") == "not_trusted")
            drugi.obdelaj(o.sporocilo("internet.open", "tok-zasc-0002").put("host", "127.0.0.1").put("port", o.streznik.localPort)
                .put(InternetZascita.POLJE, o.jedro))
            pocakaj("po ponovnem zagonu zasciteno odpiranje uspe") { o.zadnje("internet.opened", "tok-zasc-0002") != null }
        } finally { drugi.close() }
    } finally { o.konec() }

    if (izvorna.isNotEmpty()) {
        fun vir(pot: String) = java.io.File(izvorna, pot).readText()
        preveri("polje zascite je isto kot v ZascitaLinka", vir("si/safeer/tv/cast/ZascitaLinka.kt").contains("const val POLJE = \"${InternetZascita.POLJE}\""))
        preveri("ZascitaLinka sprejme internet.open/query iz preverjene seje",
            vir("si/safeer/tv/cast/ZascitaLinka.kt").contains("tip !in E2e.ZASCITENI_TIPI && tip !in E2e.TIPI_INTERNETA"))
        val e2e = vir("si/safeer/tv/cast/E2e.kt")
        preveri("E2e pozna tipa interneta", InternetZascita.TIPI.all { e2e.substringAfter("val TIPI_INTERNETA = setOf(").substringBefore(")").contains("\"$it\"") })
        preveri("internet ni med obveznimi zascitenimi tipi (starejse naprave)", !e2e.substringAfter("val ZASCITENI_TIPI = setOf(").substringBefore(")").contains("internet."))
        preveri("telefon zmoznost oglasi", vir("si/safeer/tv/cast/CastReceiverService.kt").contains("zmoznosti.add(si.safeer.tv.link.InternetZascita.ZMOZNOST)"))
    }
}

/** 3.5: dobo (in z njo zaprtje tokov prejsnje dobe) zamenja samo naprava, ki internet sme uporabljati. */
private fun dobaSamoOdPreverjenega() {
    val o = Oder()
    try {
        o.odpri("tok-doba-0001")
        pocakaj("tok se odpre") { o.zadnje("internet.opened", "tok-doba-0001") != null }
        // Dovoljenje je vmes odvzeto (zapis v shrambi), prehod se osvezi sele za tem: sporocilo z drugo dobo ne zapira.
        AndroidApplicationGateway.dovoljenja(o.ctx).odloci(o.jedro, false)
        o.prehod.obdelaj(JSONObject().put("type", "internet.query").put("sender", o.naprava).put("v", 2).put("epoch", 3L))
        o.prehod.obdelaj(JSONObject().put("type", "internet.open").put("sender", o.naprava).put("v", 2).put("epoch", 3L)
            .put("stream_id", "tok-doba-0002").put("host", "127.0.0.1").put("port", o.streznik.localPort))
        preveri("zavrnjeno odpiranje z drugo dobo ne zapre tokov", o.prehod.stanje().aktivniTokovi == 1 &&
            o.zadnje("internet.error", "tok-doba-0002")?.optString("reason") == "denied")
        // Naprava z dovoljenjem: nova doba (tudi nizja - racunalnik jo ob zagonu izbere nakljucno) zapre stare tokove.
        AndroidApplicationGateway.dovoljenja(o.ctx).odloci(o.jedro, true)
        o.prehod.obdelaj(JSONObject().put("type", "internet.query").put("sender", o.naprava).put("v", 2).put("epoch", 3L))
        preveri("nova doba preverjene naprave zapre tokove prejsnje dobe", o.prehod.stanje().aktivniTokovi == 0)
    } finally { o.konec() }
}

fun main(args: Array<String>) {
    dobaSamoOdPreverjenega()
    umikZapreTokove(args.firstOrNull().orEmpty())
    zascitenoOdpiranje(args.firstOrNull().orEmpty())
    println("PrehodInternetaTest: OK ($preverb preverb)")
}
