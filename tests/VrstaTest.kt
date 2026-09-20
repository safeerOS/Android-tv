package si.safeer.tv.cast

import java.io.InputStream
import java.net.Socket

/**
 * Izhodna vrsta povezave (HubStreznik): naprava, ki svojih podatkov ne bere, ne sme zadrzati
 * huba. Test govori pravi WebSocket cez pravo vticnico - odjemalec se poveze in potem samo
 * molci, streznik pa mu posilja.
 */
object VrstaTest {

    private fun trdi(pogoj: Boolean, sporocilo: String) {
        if (!pogoj) throw AssertionError(sporocilo)
    }

    /** Minimalen WebSocket odjemalec: rokovanje in branje okvirjev streznika (ta ne maskira). */
    private class Odjemalec(vrata: Int, sprejemniPomnilnik: Int = 0) {
        val vticnica = Socket()
        lateinit var vhod: InputStream

        init {
            if (sprejemniPomnilnik > 0) vticnica.receiveBufferSize = sprejemniPomnilnik
            vticnica.connect(java.net.InetSocketAddress("127.0.0.1", vrata), 3000)
            val izhod = vticnica.getOutputStream()
            izhod.write(("GET /cast/ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .toByteArray(Charsets.UTF_8))
            izhod.flush()
            vticnica.soTimeout = 1_000   // brez tega bi branje cakalo v nedogled
            vhod = vticnica.getInputStream().buffered()
            // preberi glave odgovora
            val glava = StringBuilder()
            while (!glava.endsWith("\r\n\r\n")) {
                val b = vhod.read()
                if (b < 0) break
                glava.append(b.toChar())
            }
            trdi(glava.startsWith("HTTP/1.1 101"), "rokovanje ni uspelo: ${glava.take(40)}")
        }

        /** Prebere en okvir; vrne par (opkoda, besedilo) ali null ob koncu. */
        fun preberiOkvir(): Pair<Int, String>? {
            val prvi = try { vhod.read() } catch (_: java.net.SocketTimeoutException) { return null }
            if (prvi < 0) return null
            val opkoda = prvi and 0x0F
            var dolzina = (vhod.read() and 0x7F).toLong()
            if (dolzina == 126L) {
                dolzina = ((vhod.read() shl 8) or vhod.read()).toLong()
            } else if (dolzina == 127L) {
                dolzina = 0
                repeat(8) { dolzina = (dolzina shl 8) or vhod.read().toLong() }
            }
            val podatki = ByteArray(dolzina.toInt())
            var prebrano = 0
            while (prebrano < podatki.size) {
                val n = vhod.read(podatki, prebrano, podatki.size - prebrano)
                if (n < 0) return null
                prebrano += n
            }
            return opkoda to String(podatki, Charsets.UTF_8)
        }

        fun zapri() = try { vticnica.close() } catch (_: Exception) { }
    }

    private fun seznam(st: Int) = """{"id":"x$st","type":"cast.devices","devices":[{"id":"n$st"}]}"""

    @JvmStatic
    fun main(args: Array<String>) {
        val povezave = java.util.concurrent.CopyOnWriteArrayList<HubStreznik.Povezava>()
        val streznik = HubStreznik(
            zeljenaVrata = 0,
            naZahtevo = { HubStreznik.Odgovor(200, "{}") },
            preveriVstopnico = { null },
            naPovezavo = { povezave.add(it) },
        )
        streznik.zazeni()
        val vrata = streznik.vrata
        trdi(vrata > 0, "streznik se ni zagnal")

        // 1. Naprava, ki bere: dobi vsa sporocila v pravem vrstnem redu.
        run {
            val o = Odjemalec(vrata)
            Thread.sleep(200)
            val p = povezave.last()
            p.poslji("""{"type":"cast.pong","n":1}""")
            p.poslji("""{"type":"cast.pong","n":2}""")
            val prvo = o.preberiOkvir()
            val drugo = o.preberiOkvir()
            trdi(prvo?.second?.contains("\"n\":1") == true, "prvo sporocilo: $prvo")
            trdi(drugo?.second?.contains("\"n\":2") == true, "drugo sporocilo: $drugo")
            println("1. zaporedje sporocil: OK")
            o.zapri()
        }

        // 2. Zdruzevanje seznamov naprav: kdor je bil zaposlen, dobi zadnje stanje, ne vseh 30.
        run {
            val o = Odjemalec(vrata, sprejemniPomnilnik = 512)
            Thread.sleep(200)
            val p = povezave.last()
            // Napolni vrsto, dokler odjemalec ne bere (nit pisanja je zaposlena s prvim okvirjem).
            for (i in 1..30) p.poslji(seznam(i))
            Thread.sleep(150)
            var zadnjiSeznam = ""
            var seznamov = 0
            var beri = true
            while (beri) {
                val okvir = try { o.preberiOkvir() } catch (_: Exception) { null }
                if (okvir == null) beri = false
                else if (okvir.second.contains("cast.devices")) { seznamov++; zadnjiSeznam = okvir.second }
            }
            println("2. zdruzevanje seznamov: poslanih 30, prejetih $seznamov, zadnji vsebuje n30: ${zadnjiSeznam.contains("n30")}")
            trdi(seznamov in 1..29, "zdruzevanja ni bilo ($seznamov seznamov)")
            trdi(zadnjiSeznam.contains("n30"), "zadnje stanje ni prislo: $zadnjiSeznam")
            o.zapri()
        }

        // 3. Naprava, ki ne bere: posiljanje ne sme cakati nanjo, povezava se zapre.
        run {
            val o = Odjemalec(vrata, sprejemniPomnilnik = 512)
            Thread.sleep(200)
            val p = povezave.last()
            val velik = "x".repeat(16 * 1024)
            val zacetek = System.nanoTime()
            var poslanih = 0
            repeat(200) {
                p.poslji("""{"type":"cast.text","payload":"$velik"}""")
                poslanih++
            }
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            println("3. naprava ne bere: %d klicev poslji v %.0f ms".format(poslanih, ms))
            trdi(ms < 1000, "posiljanje je cakalo na napravo (%.0f ms)".format(ms))
            var cakal = 0
            while (p.jeOdprta() && cakal < 3000) { Thread.sleep(50); cakal += 50 }
            println("   povezava zaprta po ${cakal} ms: ${!p.jeOdprta()}")
            trdi(!p.jeOdprta(), "povezava naprave, ki ne bere, ostaja odprta")
            o.zapri()
        }

        // 4. Zapiranje povezave, ki ne bere, ne sme cakati dlje od roka.
        run {
            val o = Odjemalec(vrata, sprejemniPomnilnik = 512)
            Thread.sleep(200)
            val p = povezave.last()
            repeat(20) { p.poslji("""{"type":"cast.text","payload":"${"y".repeat(8 * 1024)}"}""") }
            val zacetek = System.nanoTime()
            p.zapri(1000, "konec")
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            println("4. zapiranje zaposlene povezave: %.0f ms".format(ms))
            trdi(ms < 900, "zapiranje je cakalo predolgo (%.0f ms)".format(ms))
            o.zapri()
        }

        streznik.ustavi()
        println("OK VrstaTest")
    }
}
