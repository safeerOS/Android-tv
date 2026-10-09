package si.safeer.tv.os

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

// Povezava do naprave z vec naslovi (docs/LINK-MESH.md, pravilo 8) in prek njenega Huba (Global Link) s pravimi
// vticnicami TLS. Neposredni primeri so isti kot na Linuxu (tests/test_link_gledalec_naslovi.py) in Windows
// (windows/tests/test_oddaljeni_zaslon_naslovi.py); Hub odgovarja po pravilih core/link_hub_streznik.py (_namizje).

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun primer(ime: String, f: () -> Unit) { println(ime); f() }

private fun pocakaj(najvecMs: Long = 2000, pogoj: () -> Boolean): Boolean {
    val konec = System.currentTimeMillis() + najvecMs
    while (System.currentTimeMillis() < konec) { if (pogoj()) return true; Thread.sleep(20) }
    return pogoj()
}

/** Novo samopodpisano potrdilo: (kontekst TLS streznika, odtis potrdila). */
private fun potrdilo(mapa: File): Pair<SSLContext, String> {
    val shramba = File(mapa, "n-${System.nanoTime()}.p12")
    val geslo = "preizkus"
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "a", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=safeer-preizkus", "-validity", "2", "-storetype", "PKCS12", "-keystore", shramba.path,
        "-storepass", geslo, "-keypass", geslo).redirectErrorStream(true).start()
    val izpis = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "keytool: $izpis" }
    val ks = KeyStore.getInstance("PKCS12")
    shramba.inputStream().use { ks.load(it, geslo.toCharArray()) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, geslo.toCharArray())
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, null, null)
    return Pair(ctx, Pin.sha256Hex(ks.getCertificate("a").encoded))
}

/** Naprava s svojim samopodpisanim potrdilom na enem naslovu zanke; steje povezave, rokovanja in pozdrave. */
private class Naprava(naslov: String, mapa: File, zeljenaVrata: Int = 0) {
    val odtis: String
    private val posluh: SSLServerSocket
    val vrata: Int get() = posluh.localPort
    @Volatile var povezav = 0
    @Volatile var rokovanj = 0
    val pozdravi: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile private var tece = true

    init {
        val (ctx, o) = potrdilo(mapa)
        odtis = o
        posluh = ctx.serverSocketFactory.createServerSocket(zeljenaVrata, 8, InetAddress.getByName(naslov)) as SSLServerSocket
        Thread({
            while (tece) {
                val s = try { posluh.accept() as SSLSocket } catch (_: Throwable) { break }
                povezav++
                Thread({
                    try {
                        s.soTimeout = 3000
                        s.startHandshake()
                        rokovanj++
                        val vrstica = s.inputStream.bufferedReader().readLine()
                        if (vrstica != null) pozdravi.add(vrstica)
                    } catch (_: Throwable) {
                    } finally {
                        try { s.close() } catch (_: Throwable) { }
                    }
                }, "naprava-seja").apply { isDaemon = true; start() }
            }
        }, "naprava").apply { isDaemon = true; start() }
    }

    fun zapri() { tece = false; try { posluh.close() } catch (_: Throwable) { } }
}

/**
 * Hub naprave (kot core/link_hub_streznik.py, pot /cast/desktop): po rokovanju prebere zahtevo HTTP; na pravi zeton
 * in nadgradnjo odgovori 101 in TAKOJ za njim poslje glavo seje, sicer 404 ali 400. Steje zahteve in predaje.
 */
private class Hub(mapa: File, private val zeton: String, private val zamikOdgovoraMs: Long = 0) {
    val odtis: String
    private val posluh: SSLServerSocket
    val vrata: Int get() = posluh.localPort
    val zahteve: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile var predanih = 0
    @Volatile var koncanih = 0
    /** Sejo ima ze drug gledalec: Hub je ne preda (Zaslon.caka() je False). */
    @Volatile var oddana = false
    @Volatile private var tece = true

    init {
        val (ctx, o) = potrdilo(mapa)
        odtis = o
        posluh = ctx.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        Thread({
            while (tece) {
                val s = try { posluh.accept() as SSLSocket } catch (_: Throwable) { break }
                Thread({ seja(s) }, "hub-seja").apply { isDaemon = true; start() }
            }
        }, "hub").apply { isDaemon = true; start() }
    }

    private fun seja(s: SSLSocket) {
        var zahtevaPrisla = false
        try {
            s.soTimeout = 4000
            s.startHandshake()
            val vhod = s.inputStream
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val z = vhod.read()
                if (z < 0) return
                sb.append(z.toChar())
            }
            val zahteva = sb.toString()
            zahteve.add(zahteva)
            zahtevaPrisla = true
            if (zamikOdgovoraMs > 0) Thread.sleep(zamikOdgovoraMs)
            val glave = HashMap<String, String>()
            for (v in zahteva.split("\r\n").drop(1)) {
                val i = v.indexOf(':')
                if (i > 0) glave[v.substring(0, i).trim().lowercase()] = v.substring(i + 1).trim()
            }
            val o = s.outputStream
            if (!zahteva.startsWith("GET /cast/desktop HTTP/1.1\r\n") || glave["x-safeer-desktop"] != zeton || oddana) {
                o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
                return
            }
            if (glave["upgrade"]?.lowercase() != "safeer-desktop") {
                o.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
                return
            }
            // Odgovor in glava seje v enem zapisu: gledalec ne sme z glavo HTTP pojesti niti bajta pretoka.
            o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: safeer-desktop\r\nConnection: Upgrade\r\n\r\n" +
                "{\"v\":2,\"w\":1920}\n").toByteArray())
            o.flush()
            predanih++
            try { while (vhod.read() >= 0) { } } catch (_: Throwable) { }      // seja: do konca povezave
        } catch (_: Throwable) {
        } finally {
            try { s.close() } catch (_: Throwable) { }
            if (zahtevaPrisla) koncanih++
        }
    }

    fun zapri() { tece = false; try { posluh.close() } catch (_: Throwable) { } }
}

private fun pozdravi(s: SSLSocket?) {
    val o = s?.outputStream ?: return
    o.write("SAFEER-ZASLON zeton\n".toByteArray())
    o.flush()
}

/** Prva vrstica pretoka po povezavi (glava seje), prebrana bajt za bajtom. */
private fun vrstica(s: SSLSocket?): String {
    if (s == null) return ""
    val vhod = s.inputStream
    s.soTimeout = 3000
    val sb = StringBuilder()
    try {
        while (sb.length < 200) {
            val z = vhod.read()
            if (z < 0 || z == '\n'.code) break
            sb.append(z.toChar())
        }
    } catch (_: Throwable) { }
    return sb.toString()
}

fun main() {
    val mapa = java.nio.file.Files.createTempDirectory("safeer-povezava-").toFile()
    try {
        primer("en naslov: povezava s pripetim potrdilom") {
            val n = Naprava("127.0.0.1", mapa)
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis)
            preveri("povezano", s != null)
            preveri("pred pozdravom naprava ne dobi nicesar", n.pozdravi.isEmpty())
            pozdravi(s)
            preveri("pozdrav poslje sele klicatelj", pocakaj { n.pozdravi.size == 1 } && n.pozdravi.toList() == listOf("SAFEER-ZASLON zeton"))
            s?.close(); n.zapri()
        }
        primer("odtis z dvopicji in velikimi crkami") {
            val n = Naprava("127.0.0.1", mapa)
            val lep = n.odtis.uppercase().chunked(2).joinToString(":")
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, lep)
            preveri("povezano", s != null)
            s?.close(); n.zapri()
        }
        primer("prvi naslov mrtev, drugi dela") {
            val n = Naprava("127.0.0.1", mapa)
            val zacetek = System.currentTimeMillis()
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.2", "127.0.0.1"), n.vrata, n.odtis)
            preveri("povezano na drugi naslov", s?.inetAddress?.hostAddress == "127.0.0.1")
            preveri("brez cakanja na mrtvem naslovu", System.currentTimeMillis() - zacetek < 3000)
            pozdravi(s)
            preveri("naprava dobi pozdrav", pocakaj { n.pozdravi.size == 1 })
            s?.close(); n.zapri()
        }
        primer("na prvem naslovu druga naprava: zetona ne dobi") {
            val prava = Naprava("127.0.0.1", mapa)
            val tuja = Naprava("127.0.0.2", mapa, prava.vrata)
            preveri("potrdili sta razlicni", prava.odtis != tuja.odtis)
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.2", "127.0.0.1"), prava.vrata, prava.odtis)
            preveri("povezano s pravo napravo", s?.inetAddress?.hostAddress == "127.0.0.1")
            pozdravi(s)
            preveri("prava naprava dobi pozdrav", pocakaj { prava.pozdravi.size == 1 })
            preveri("tujo napravo smo najprej poskusili", pocakaj { tuja.povezav == 1 })
            Thread.sleep(200)
            preveri("tuja naprava pozdrava (zetona) ne dobi", tuja.pozdravi.isEmpty())
            s?.close(); prava.zapri(); tuja.zapri()
        }
        primer("samo druga naprava: povezave ni") {
            val prava = Naprava("127.0.0.1", mapa)
            val tuja = Naprava("127.0.0.2", mapa)
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.2"), tuja.vrata, prava.odtis, casMs = 1500)
            preveri("ni povezave", s == null)
            Thread.sleep(200)
            preveri("tuja naprava pozdrava ne dobi", tuja.pozdravi.isEmpty())
            s?.close(); prava.zapri(); tuja.zapri()
        }
        primer("nema naprava ne zadrzi naslednjega naslova") {
            val prava = Naprava("127.0.0.1", mapa)
            val nema = ServerSocket(prava.vrata, 8, InetAddress.getByName("127.0.0.2"))   // sprejme TCP, TLS ne odgovori
            val zacetek = System.currentTimeMillis()
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.2", "127.0.0.1"), prava.vrata, prava.odtis, casMs = 700)
            val trajalo = System.currentTimeMillis() - zacetek
            preveri("povezano s pravo napravo", s?.inetAddress?.hostAddress == "127.0.0.1")
            preveri("po roku rokovanja, ne takoj in ne predolgo ($trajalo ms)", trajalo in 600..4000)
            s?.close(); nema.close(); prava.zapri()
        }
        primer("naprave ni na nobenem naslovu") {
            val n = Naprava("127.0.0.1", mapa)
            val prosta = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.2", "127.0.0.3", "127.0.0.1"), prosta, n.odtis, casMs = 700)
            preveri("ni povezave", s == null)
            preveri("brez naslovov ni povezave", NeposrednaPovezava.povezi(emptyList(), n.vrata, n.odtis) == null)
            preveri("naprave nismo motili", n.povezav == 0)
            n.zapri()
        }
        primer("uporabnik je zaslon zapustil: iskanje se konca") {
            val n = Naprava("127.0.0.1", mapa)
            val s = NeposrednaPovezava.povezi(listOf("127.0.0.2", "127.0.0.1"), n.vrata, n.odtis, tece = { false })
            preveri("ni povezave", s == null)
            preveri("naprave nismo poskusili", n.povezav == 0)
            var klicev = 0
            val s2 = NeposrednaPovezava.povezi(listOf("127.0.0.2", "127.0.0.1"), n.vrata, n.odtis, tece = { klicev++ == 0 })
            preveri("po prvem naslovu nehamo", s2 == null && n.povezav == 0)
            n.zapri()
        }
        primer("cas za en naslov in za vec naslovov") {
            preveri("en naslov dobi ves cas", NeposrednaPovezava.casZa(1) == 8000 && NeposrednaPovezava.casZa(0) == 8000)
            preveri("vec naslovov si ga razdeli", NeposrednaPovezava.casZa(2) == 4000 && NeposrednaPovezava.casZa(4) == 4000)
            preveri("stirje poskusi povezave se izidejo v cakanju naprave (30 s)", 4 * NeposrednaPovezava.casZa(4) <= 30_000)
        }

        // ------------------------------------------------------------------ prek Huba naprave (Global Link)
        primer("prek Huba: zeton v zahtevi za predajo, po odgovoru 101 tece pretok seje") {
            val h = Hub(mapa, "zeton-1")
            val s = NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton-1")
            preveri("povezano", s != null)
            preveri("Hub je sejo predal", pocakaj { h.predanih == 1 })
            val zahteva = h.zahteve.firstOrNull().orEmpty()
            preveri("zahteva je GET /cast/desktop", zahteva.startsWith("GET /cast/desktop HTTP/1.1\r\n"))
            preveri("zeton je v glavi X-Safeer-Desktop", zahteva.contains("\r\nX-Safeer-Desktop: zeton-1\r\n"))
            preveri("zahteva prosi za nadgradnjo", zahteva.contains("\r\nUpgrade: safeer-desktop\r\n") && zahteva.contains("\r\nConnection: Upgrade\r\n"))
            preveri("glava seje pride cela (glava HTTP ni pojedla pretoka)", vrstica(s) == "{\"v\":2,\"w\":1920}")
            s?.close(); h.zapri()
        }
        primer("prek Huba: napacen zeton ali oddana seja") {
            val h = Hub(mapa, "zeton-1")
            preveri("napacen zeton: povezave ni", NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "drug-zeton") == null)
            h.oddana = true
            preveri("sejo ima drug gledalec: povezave ni", NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton-1") == null)
            preveri("Hub ni predal nicesar", h.predanih == 0 && h.zahteve.size == 2)
            h.zapri()
        }
        primer("prek Huba: druga naprava zetona ne dobi") {
            val h = Hub(mapa, "zeton-1")
            val tuj = Hub(mapa, "zeton-1")
            preveri("potrdili sta razlicni", h.odtis != tuj.odtis)
            val s = NeposrednaPovezava.poveziPrekHuba(tuj.vrata, h.odtis, "zeton-1", casMs = 1500)
            preveri("ni povezave", s == null)
            Thread.sleep(200)
            preveri("tuji Hub zahteve (zetona) ne dobi", tuj.zahteve.isEmpty())
            h.zapri(); tuj.zapri()
        }
        primer("prek Huba: zeton, ki bi bil druga glava, ne gre na pot") {
            val h = Hub(mapa, "zeton-1")
            preveri("nova vrstica", NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton-1\r\nX-Drugo: 1") == null)
            preveri("presledek", NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton 1") == null)
            preveri("prazen", NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "") == null)
            Thread.sleep(150)
            preveri("Huba nismo niti poklicali", h.zahteve.isEmpty())
            h.zapri()
        }
        primer("prek Huba: Huba ni ali uporabnik je odsel") {
            val h = Hub(mapa, "zeton-1")
            val prosta = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            preveri("na vratih ni nikogar", NeposrednaPovezava.poveziPrekHuba(prosta, h.odtis, "zeton-1", casMs = 700) == null)
            preveri("uporabnik je odsel", NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton-1", tece = { false }) == null)
            Thread.sleep(150)
            preveri("Hub zahteve ne dobi", h.zahteve.isEmpty())
            h.zapri()
        }

        // ------------------------------------------------------------------ obe poti hkrati
        primer("tekma: doma zmaga neposredna pot, Huba se ne dotaknemo") {
            val n = Naprava("127.0.0.1", mapa)
            val h = Hub(mapa, "zeton")
            val izid = NeposrednaPovezava.tekma(
                { t -> NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) })
            preveri("neposredno", izid?.pot == NeposrednaPovezava.Pot.NEPOSREDNO)
            preveri("pred pozdravom naprava ne dobi nicesar", n.pozdravi.isEmpty())
            pozdravi(izid?.vticnica)
            preveri("naprava dobi pozdrav", pocakaj { n.pozdravi.size == 1 })
            Thread.sleep(NeposrednaPovezava.PREDNOST_NEPOSREDNE_MS + 300)
            preveri("Hub ni dobil nobene zahteve", h.zahteve.isEmpty())
            izid?.vticnica?.close(); n.zapri(); h.zapri()
        }
        primer("tekma: zdoma (domaci naslov molci) zmaga pot prek Huba brez cakanja") {
            val h = Hub(mapa, "zeton")
            val nema = ServerSocket(0, 8, InetAddress.getByName("127.0.0.2"))            // sprejme TCP, TLS ne odgovori
            val zacetek = System.currentTimeMillis()
            val izid = NeposrednaPovezava.tekma(
                { t -> NeposrednaPovezava.povezi(listOf("127.0.0.2"), nema.localPort, h.odtis, casMs = 5000, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) })
            val trajalo = System.currentTimeMillis() - zacetek
            preveri("prek Huba", izid?.pot == NeposrednaPovezava.Pot.HUB)
            preveri("po prednosti neposredne poti, ne po njenem roku ($trajalo ms)", trajalo in 250..3000)
            preveri("glava seje pride", vrstica(izid?.vticnica) == "{\"v\":2,\"w\":1920}")
            izid?.vticnica?.close(); nema.close(); h.zapri()
        }
        primer("tekma: neposredna pot odpove takoj - pot prek Huba ne caka prednosti") {
            val h = Hub(mapa, "zeton")
            val prosta = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val zacetek = System.currentTimeMillis()
            val izid = NeposrednaPovezava.tekma(
                { t -> NeposrednaPovezava.povezi(listOf("127.0.0.1"), prosta, h.odtis, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) },
                prednostMs = 5000)
            val trajalo = System.currentTimeMillis() - zacetek
            preveri("prek Huba", izid?.pot == NeposrednaPovezava.Pot.HUB)
            preveri("brez cakanja na prednost ($trajalo ms)", trajalo < 3000)
            izid?.vticnica?.close(); h.zapri()
        }
        primer("tekma: neposredna zmaga, ko je pot prek Huba ze na poti - pocasnejso zapremo") {
            val n = Naprava("127.0.0.1", mapa)
            val h = Hub(mapa, "zeton", zamikOdgovoraMs = 900)
            val izid = NeposrednaPovezava.tekma(
                { t -> Thread.sleep(600); NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) })
            preveri("neposredno", izid?.pot == NeposrednaPovezava.Pot.NEPOSREDNO)
            preveri("Hub je zahtevo ze dobil", pocakaj { h.zahteve.size == 1 })
            preveri("povezavo prek Huba smo zaprli", pocakaj(4000) { h.koncanih == 1 })
            pozdravi(izid?.vticnica)
            preveri("naprava dobi pozdrav", pocakaj { n.pozdravi.size == 1 })
            izid?.vticnica?.close(); n.zapri(); h.zapri()
        }
        primer("tekma: obe poti odpovesta") {
            val h = Hub(mapa, "zeton")
            val prosta = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val zacetek = System.currentTimeMillis()
            val izid = NeposrednaPovezava.tekma(
                { t -> NeposrednaPovezava.povezi(listOf("127.0.0.1"), prosta, h.odtis, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "napacen", tece = t) })
            preveri("ni povezave", izid == null)
            preveri("brez visenja (${System.currentTimeMillis() - zacetek} ms)", System.currentTimeMillis() - zacetek < 4000)
            h.zapri()
        }
        primer("tekma: ena sama pot in nobena") {
            val n = Naprava("127.0.0.1", mapa)
            val h = Hub(mapa, "zeton")
            val samoNeposredno = NeposrednaPovezava.tekma({ t -> NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis, tece = t) }, null)
            preveri("brez poti prek Huba je kot prej", samoNeposredno?.pot == NeposrednaPovezava.Pot.NEPOSREDNO)
            val samoHub = NeposrednaPovezava.tekma(null, { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) })
            preveri("brez naslova gre samo prek Huba", samoHub?.pot == NeposrednaPovezava.Pot.HUB)
            preveri("brez poti ni povezave", NeposrednaPovezava.tekma(null, null) == null)
            samoNeposredno?.vticnica?.close(); samoHub?.vticnica?.close(); n.zapri(); h.zapri()
        }
        primer("tekma: uporabnik je zaslon zapustil") {
            val n = Naprava("127.0.0.1", mapa)
            val h = Hub(mapa, "zeton")
            val izid = NeposrednaPovezava.tekma(
                { t -> NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) },
                tece = { false })
            preveri("ni povezave", izid == null)
            Thread.sleep(150)
            preveri("nikogar nismo poklicali", n.povezav == 0 && h.zahteve.isEmpty())
            n.zapri(); h.zapri()
        }
        primer("tekma: cas do koncanega rokovanja zmagovalne poti (meritve)") {
            val n = Naprava("127.0.0.1", mapa)
            val h = Hub(mapa, "zeton")
            val izid = NeposrednaPovezava.tekma(
                { t -> NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis, tece = t) },
                { t -> NeposrednaPovezava.poveziPrekHuba(h.vrata, h.odtis, "zeton", tece = t) })
            preveri("neposredno", izid?.pot == NeposrednaPovezava.Pot.NEPOSREDNO)
            preveri("pravo rokovanje na zanki je izmerjeno (${izid?.casMs} ms)", (izid?.casMs ?: 0L) > 0L)
            preveri("brez meritve je cas 0", izid != null &&
                NeposrednaPovezava.Izid(izid.vticnica, izid.pot).casMs == 0L)
            izid?.vticnica?.close()
            // Cas tece od zacetka tekme, ne od zacetka poti.
            val pozna = NeposrednaPovezava.tekma(
                { t -> Thread.sleep(250); NeposrednaPovezava.povezi(listOf("127.0.0.1"), n.vrata, n.odtis, tece = t) }, null)
            preveri("od zacetka tekme (${pozna?.casMs} ms)", (pozna?.casMs ?: 0L) >= 250L)
            pozna?.vticnica?.close()
            // Prek Huba je v casu tudi odgovor Huba (predaja seje).
            val pocasen = Hub(mapa, "zeton", zamikOdgovoraMs = 200)
            val hub = NeposrednaPovezava.tekma(null,
                { t -> NeposrednaPovezava.poveziPrekHuba(pocasen.vrata, pocasen.odtis, "zeton", tece = t) })
            preveri("prek Huba", hub?.pot == NeposrednaPovezava.Pot.HUB)
            preveri("z odgovorom Huba (${hub?.casMs} ms)", (hub?.casMs ?: 0L) >= 200L)
            hub?.vticnica?.close(); n.zapri(); h.zapri(); pocasen.zapri()
        }
    } finally {
        mapa.deleteRecursively()
    }
    println()
    if (napak == 0) println("Vse v redu.") else { println("Napak: $napak"); kotlin.system.exitProcess(1) }
}
