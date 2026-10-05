package si.safeer.tv.os

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

// Neposredna povezava do naprave z vec naslovi (docs/LINK-MESH.md, pravilo 8) s pravimi vticnicami TLS.
// Isti primeri kot na Linuxu (tests/test_link_gledalec_naslovi.py) in Windows (windows/tests/test_oddaljeni_zaslon_naslovi.py).

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
        odtis = Pin.sha256Hex(ks.getCertificate("a").encoded)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, geslo.toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
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

private fun pozdravi(s: SSLSocket?) {
    val o = s?.outputStream ?: return
    o.write("SAFEER-ZASLON zeton\n".toByteArray())
    o.flush()
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
    } finally {
        mapa.deleteRecursively()
    }
    println()
    if (napak == 0) println("Vse v redu.") else { println("Napak: $napak"); kotlin.system.exitProcess(1) }
}
