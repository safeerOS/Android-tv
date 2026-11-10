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

fun main(args: Array<String>) {
    umikZapreTokove(args.firstOrNull().orEmpty())
    println("PrehodInternetaTest: OK ($preverb preverb)")
}
