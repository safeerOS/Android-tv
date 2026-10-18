package si.safeer.tv.cast

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Preizkus streznika Safeer Huba brez naprave.
 *
 * Preverjamo tisto, kar bi se v zivo pokazalo sele kot "ne dela": odgovore HTTP, pravilno
 * rokovanje po RFC 6455 (z znanim primerom iz standarda), pretok sporocil v obe smeri,
 * zavrnitev brez vstopnice, zavrnitev nemaskiranih okvirjev in omejitev velikosti.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) {
        println("  OK   $opis")
    } else {
        println("  NAPAKA $opis")
        napak++
    }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)
}

// ------------------------------------------------------------ pomozni odjemalec

private class TestniOdjemalec(vrata: Int) {
    val vticnica = Socket("127.0.0.1", vrata)
    val vhod: InputStream = vticnica.getInputStream().buffered()
    val izhod: OutputStream = vticnica.getOutputStream()

    fun posljiSurovo(niz: String) {
        izhod.write(niz.toByteArray(Charsets.UTF_8))
        izhod.flush()
    }

    fun preberiVrstico(): String {
        val sb = StringBuilder()
        while (true) {
            val b = vhod.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    fun preberiGlave(): Pair<String, Map<String, String>> {
        val prva = preberiVrstico()
        val glave = HashMap<String, String>()
        while (true) {
            val v = preberiVrstico()
            if (v.isEmpty()) break
            val i = v.indexOf(':')
            if (i > 0) glave[v.substring(0, i).trim().lowercase()] = v.substring(i + 1).trim()
        }
        return Pair(prva, glave)
    }

    /** En (maskiran) okvir s poljubno opkodo in zastavico FIN - za sporocila, razdeljena na vec okvirjev. */
    fun posljiDel(opkoda: Int, zadnji: Boolean, podatki: ByteArray) {
        val glava = java.io.ByteArrayOutputStream()
        glava.write((if (zadnji) 0x80 else 0) or opkoda)
        when {
            podatki.size < 126 -> glava.write(0x80 or podatki.size)
            podatki.size <= 0xFFFF -> { glava.write(0x80 or 126); glava.write((podatki.size shr 8) and 0xFF); glava.write(podatki.size and 0xFF) }
            else -> { glava.write(0x80 or 127); for (i in 7 downTo 0) glava.write(((podatki.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val maska = byteArrayOf(0x7f, 0x00, 0x81.toByte(), 0x5a)
        glava.write(maska)
        val zakrit = ByteArray(podatki.size)
        for (i in podatki.indices) zakrit[i] = (podatki[i].toInt() xor maska[i % 4].toInt()).toByte()
        izhod.write(glava.toByteArray())
        izhod.write(zakrit)
        izhod.flush()
    }

    /** Okvir odjemalca mora biti maskiran - tako kot ga poslje pravi brskalnik. */
    fun posljiBesedilo(besedilo: String, maskiraj: Boolean = true) {
        val podatki = besedilo.toByteArray(Charsets.UTF_8)
        val glava = ArrayList<Byte>()
        glava.add((0x80 or 0x1).toByte())
        val zastavicaMaske = if (maskiraj) 0x80 else 0x00
        when {
            podatki.size < 126 -> glava.add((zastavicaMaske or podatki.size).toByte())
            podatki.size <= 0xFFFF -> {
                glava.add((zastavicaMaske or 126).toByte())
                glava.add(((podatki.size shr 8) and 0xFF).toByte())
                glava.add((podatki.size and 0xFF).toByte())
            }
            else -> {
                glava.add((zastavicaMaske or 127).toByte())
                for (i in 7 downTo 0) glava.add(((podatki.size.toLong() shr (8 * i)) and 0xFF).toByte())
            }
        }
        izhod.write(glava.toByteArray())
        if (maskiraj) {
            val maska = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            izhod.write(maska)
            val zakrit = ByteArray(podatki.size)
            for (i in podatki.indices) zakrit[i] = (podatki[i].toInt() xor maska[i % 4].toInt()).toByte()
            izhod.write(zakrit)
        } else {
            izhod.write(podatki)
        }
        izhod.flush()
    }

    /** Prebere en okvir streznika; vrne par (opkoda, besedilo). */
    fun preberiOkvir(): Pair<Int, String> {
        val prvi = vhod.read()
        if (prvi < 0) return Pair(-1, "")
        val opkoda = prvi and 0x0F
        val drugi = vhod.read()
        if (drugi < 0) return Pair(-1, "")
        preveri("strežnik svojih okvirjev ne maskira", (drugi and 0x80) == 0)
        var dolzina = (drugi and 0x7F).toLong()
        if (dolzina == 126L) dolzina = ((vhod.read() shl 8) or vhod.read()).toLong()
        else if (dolzina == 127L) {
            dolzina = 0
            for (i in 0 until 8) dolzina = (dolzina shl 8) or vhod.read().toLong()
        }
        val podatki = ByteArray(dolzina.toInt())
        var prebrano = 0
        while (prebrano < podatki.size) {
            val n = vhod.read(podatki, prebrano, podatki.size - prebrano)
            if (n < 0) break
            prebrano += n
        }
        return Pair(opkoda, String(podatki, 0, prebrano, Charsets.UTF_8))
    }

    fun zapri() {
        try {
            vticnica.close()
        } catch (_: Exception) {
        }
    }
}

private fun pricakovanSprejem(kljuc: String): String {
    val d = MessageDigest.getInstance("SHA-1")
        .digest((kljuc + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
    return java.util.Base64.getEncoder().encodeToString(d)
}

// ------------------------------------------------------------------------ test

fun main() {
    println("== preizkus strežnika Safeer Huba ==")

    val prispela = CountDownLatch(1)
    var zadnjeSporocilo = ""

    val streznik = HubStreznik(
        zeljenaVrata = 0,
        naZahtevo = { z ->
            when (z.pot) {
                "/cast/health" -> HubStreznik.Odgovor(200, "{\"stanje\":\"v redu\"}")
                else -> null
            }
        },
        preveriVstopnico = { z ->
            if (z.poizvedba["ticket"] == "prava") null else "neveljavna vstopnica"
        },
        naPovezavo = { p ->
            p.naSporocilo = { sporocilo ->
                zadnjeSporocilo = sporocilo
                p.poslji("odmev:$sporocilo")
                prispela.countDown()
            }
        }
    )

    preveri("strežnik se zažene", streznik.zazeni())
    val vrata = streznik.vrata
    preveri("dobil je vrata", vrata > 0)
    println("  (vrata $vrata)")

    // 1) navadna zahteva HTTP
    run {
        val o = TestniOdjemalec(vrata)
        o.posljiSurovo("GET /cast/health HTTP/1.1\r\nHost: test\r\n\r\n")
        val (prva, glave) = o.preberiGlave()
        preveri("zdravje odgovori 200: $prva", prva.contains(" 200 "))
        preveri("odgovor se ne shranjuje", glave["cache-control"] == "no-store")
        o.zapri()
    }

    // 2) neznana pot
    run {
        val o = TestniOdjemalec(vrata)
        o.posljiSurovo("GET /ni-te-poti HTTP/1.1\r\nHost: test\r\n\r\n")
        val (prva, _) = o.preberiGlave()
        preveri("neznana pot odgovori 404: $prva", prva.contains(" 404 "))
        o.zapri()
    }

    // 3) nadgradnja brez veljavne vstopnice mora biti zavrnjena SE PRED rokovanjem
    run {
        val o = TestniOdjemalec(vrata)
        o.posljiSurovo(
            "GET /cast/ws?ticket=napacna HTTP/1.1\r\nHost: test\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        val (prva, _) = o.preberiGlave()
        preveri("brez vstopnice ni povezave: $prva", prva.contains(" 403 "))
        o.zapri()
    }

    // 4) pravilno rokovanje po standardu + pretok sporocil v obe smeri
    run {
        val kljuc = "dGhlIHNhbXBsZSBub25jZQ=="
        val o = TestniOdjemalec(vrata)
        o.posljiSurovo(
            "GET /cast/ws?ticket=prava HTTP/1.1\r\nHost: test\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $kljuc\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        val (prva, glave) = o.preberiGlave()
        preveri("rokovanje odgovori 101: $prva", prva.contains(" 101 "))
        // Znani primer iz standarda: tako preverimo SHA-1 in nase kodiranje base64 hkrati.
        preveriEnako("ključ sprejema po RFC 6455", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", glave["sec-websocket-accept"])
        preveriEnako("ključ sprejema se ujema z izračunom", pricakovanSprejem(kljuc), glave["sec-websocket-accept"])

        o.posljiBesedilo("{\"vrsta\":\"pozdrav\"}")
        preveri("sporočilo je prispelo", prispela.await(5, TimeUnit.SECONDS))
        preveriEnako("strežnik je prebral pravo vsebino", "{\"vrsta\":\"pozdrav\"}", zadnjeSporocilo)
        val (opkoda, odgovor) = o.preberiOkvir()
        preveriEnako("odgovor je besedilni okvir", 1, opkoda)
        preveriEnako("vsebina odgovora", "odmev:{\"vrsta\":\"pozdrav\"}", odgovor)
        preveri("strežnik šteje eno povezavo", streznik.steviloPovezav() == 1)

        // Veliki okvirji (kosi tokov Internet Gatewaya, 32 KiB) in dolzine, ki niso veckratnik stiri:
        // odmaskiranje gre po stiri bajte naenkrat, sporocilo v enem okvirju pa brez vmesnih kopij.
        for (dolzina in listOf(1, 2, 3, 5, 125, 126, 127, 4096, 33001, 65535, 65537)) {
            val vsebina = buildString { for (i in 0 until dolzina) append("čAb9+/=z"[i % 8]) }
            o.posljiBesedilo(vsebina)
            val (op, odmev) = o.preberiOkvir()
            preveriEnako("okvir $dolzina znakov: opkoda", 1, op)
            preveri("okvir $dolzina znakov pride nespremenjen", odmev == "odmev:$vsebina")
        }
        // Sporocilo v treh okvirjih (FIN sele na zadnjem), vmes ping: sestavi se v eno.
        val deli = listOf("prvi del, ", "drugi del s č, ", "tretji del").map { it.toByteArray(Charsets.UTF_8) }
        o.posljiDel(0x1, false, deli[0])
        o.posljiDel(0x0, false, deli[1])
        o.posljiDel(0x9, true, "p".toByteArray())
        o.posljiDel(0x0, true, deli[2])
        val (opPong, pong) = o.preberiOkvir()
        preveriEnako("ping sredi razdeljenega sporocila dobi pong", 0xA, opPong)
        preveriEnako("pong vrne isto vsebino", "p", pong)
        val (opCelo, celo) = o.preberiOkvir()
        preveriEnako("razdeljeno sporocilo: opkoda", 1, opCelo)
        preveriEnako("razdeljeno sporocilo se sestavi", "odmev:prvi del, drugi del s č, tretji del", celo)
        o.posljiBesedilo("se dela")
        preveriEnako("po razdeljenem sporocilu gre navaden okvir", "odmev:se dela", o.preberiOkvir().second)
        o.zapri()
    }

    // 5) nemaskiran okvir odjemalca ni skladen - povezavo je treba zavrniti
    run {
        val o = TestniOdjemalec(vrata)
        o.posljiSurovo(
            "GET /cast/ws?ticket=prava HTTP/1.1\r\nHost: test\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        o.preberiGlave()
        o.posljiBesedilo("brez maske", maskiraj = false)
        val (opkoda, _) = o.preberiOkvir()
        preveri("nemaskiran okvir konča povezavo (opkoda=$opkoda)", opkoda == 0x8 || opkoda == -1)
        o.zapri()
    }

    // 6) preveliko sporocilo mora povezavo koncati, ne pa pojesti pomnilnika naprave
    run {
        val o = TestniOdjemalec(vrata)
        o.posljiSurovo(
            "GET /cast/ws?ticket=prava HTTP/1.1\r\nHost: test\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        o.preberiGlave()
        // Strežnik zavrne že po glavi okvirja in povezavo zapre, zato pisanje najbrž ne
        // steče do konca - prav to je zaželeno, ker podatkov sploh ne vzame v pomnilnik.
        var zaprlJePriPisanju = false
        try {
            o.posljiBesedilo("x".repeat(400 * 1024))
        } catch (e: Exception) {
            zaprlJePriPisanju = true
        }
        val opkoda = if (zaprlJePriPisanju) 0x8 else o.preberiOkvir().first
        preveri(
            "preveliko sporočilo konča povezavo (opkoda=$opkoda, zaprl med pisanjem=$zaprlJePriPisanju)",
            opkoda == 0x8 || opkoda == -1
        )
        o.zapri()
    }

    Thread.sleep(400)

    // 7) clanov (veljavna vstopnica) ne omejuje stevilka: prej 16 povezav in 24 vticnic, zdaj odlocajo viri naprave
    run {
        val odprte = ArrayList<TestniOdjemalec>()
        var sprejetih = 0
        for (i in 0 until 40) {
            val o = TestniOdjemalec(vrata)
            o.posljiSurovo(
                "GET /cast/ws?ticket=prava HTTP/1.1\r\nHost: test\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
            )
            val (prva, _) = o.preberiGlave()
            if (prva.contains(" 101 ")) sprejetih++ else preveri("povezava ${i + 1} je sprejeta: $prva", false)
            odprte.add(o)
        }
        preveriEnako("40 hkratnih clanov je sprejetih (prej meja 16)", 40, sprejetih)
        preveri("vse so odprte hkrati (${streznik.steviloPovezav()})", streznik.steviloPovezav() >= 40)
        for (o in odprte) o.zapri()
    }

    Thread.sleep(400)

    // 8) nepreverjene: tihe vticnice (brez zahteve) z enega naslova nad mejo izrine nova; svezih ne izrinemo
    run {
        val tihi = HubStreznik(zeljenaVrata = 0, naZahtevo = { HubStreznik.Odgovor(200, "{}") },
            preveriVstopnico = { null }, naPovezavo = { }, rokTiheMs = 300)
        preveri("drugi streznik se zazene", tihi.zazeni())
        val n = HubStreznik.NAJVEC_NEPREVERJENIH_NA_VIR + 8
        val tihe = (0 until n).map { Socket("127.0.0.1", tihi.vrata) }
        Thread.sleep(150)
        preveriEnako("val svezih: nobena ni izrinjena", 0, tihi.izrinjenih)
        preveriEnako("val svezih: vse so nepreverjene", n, tihi.steviloNepreverjenih())
        Thread.sleep(400)   // zdaj so vse starejse od roka - tihe
        val nova = TestniOdjemalec(tihi.vrata)
        nova.posljiSurovo("GET /cast/health HTTP/1.1\r\nHost: t\r\n\r\n")
        val (prva, _) = nova.preberiGlave()
        preveri("nova zahteva gre skozi kljub tihim: $prva", prva.contains(" 200 "))
        preveriEnako("izrinjene so tihe nad mejo na naslov", n + 1 - HubStreznik.NAJVEC_NEPREVERJENIH_NA_VIR, tihi.izrinjenih)
        Thread.sleep(100)
        preveri("nepreverjenih ni vec kot meja (${tihi.steviloNepreverjenih()})",
            tihi.steviloNepreverjenih() <= HubStreznik.NAJVEC_NEPREVERJENIH_NA_VIR)
        val zaprtih = tihe.count { s ->
            try { s.soTimeout = 50; s.getInputStream().read() < 0 }
            catch (_: java.net.SocketTimeoutException) { false }   // se odprta (streznik caka na zahtevo)
            catch (_: Exception) { true }
        }
        preveriEnako("najstarejse tihe so zaprte", n + 1 - HubStreznik.NAJVEC_NEPREVERJENIH_NA_VIR, zaprtih)
        tihe.forEach { try { it.close() } catch (_: Exception) { } }
        nova.zapri()

        // 9) viri: brez deskriptorja nad rezervo nova vticnica ni strezena; ze odprte delajo naprej
        val clan = TestniOdjemalec(tihi.vrata)
        clan.posljiSurovo("GET /cast/ws?ticket=x HTTP/1.1\r\nHost: t\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
        preveri("clan je povezan", clan.preberiGlave().first.contains(" 101 "))
        ViriNaprave.ponastavi()
        val obvestila = ArrayList<String>()
        ViriNaprave.naPomanjkanje = { razlog, kje, _ -> synchronized(obvestila) { obvestila.add("$razlog/$kje") } }
        ViriNaprave.preizkus = { _, _ -> ViriNaprave.DESKRIPTORJI }
        try {
            tihi.teloBrezVirov = { "{\"napaka\":\"zasedena\",\"code\":\"${ViriNaprave.KODA}\",\"name\":\"Televizor\"}" }
            val brez = TestniOdjemalec(tihi.vrata)
            brez.vticnica.soTimeout = 3000
            brez.posljiSurovo("GET /cast/ws?ticket=x HTTP/1.1\r\nHost: t\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
            val (prva, _) = brez.preberiGlave()
            val telo = try { brez.vhod.readBytes().toString(Charsets.UTF_8) } catch (_: Exception) { "" }
            preveri("brez virov: jasen odgovor 503 namesto tiho zaprte vticnice: $prva", prva.contains(" 503 "))
            preveriEnako("brez virov: odjemalec iz odgovora razbere ime naprave", "Televizor", ViriNaprave.zavrnitevIzOdgovora(telo))
            brez.zapri()
            preveri("pomanjkanje je zabelezeno za uporabnika: $obvestila", synchronized(obvestila) { obvestila.contains("deskriptorji/sprejem") })
            preveriEnako("stanje virov pove razlog", ViriNaprave.DESKRIPTORJI, ViriNaprave.stanje()?.first)
        } finally {
            ViriNaprave.preizkus = null
            ViriNaprave.naPomanjkanje = null
            ViriNaprave.ponastavi()
        }
        clan.zapri()
        tihi.ustavi()
    }

    // 10) odjemalec: druga naprava nima virov za nas - obvestilo najvec enkrat na 10 min za isto napravo
    run {
        ViriNaprave.ponastavi()
        var cas = 1_000_000L
        val prejUra = ViriNaprave.ura
        ViriNaprave.ura = { cas }
        try {
            preveri("prva zavrnitev naprave obvesti", ViriNaprave.zavrnilaDruga("Televizor"))
            cas += 60_000
            preveri("ponovni poskus cez minuto ne obvesti znova", !ViriNaprave.zavrnilaDruga("Televizor"))
            preveri("druga naprava obvesti posebej", ViriNaprave.zavrnilaDruga("Tablica"))
            cas += ViriNaprave.OBVESTILO_VSAKIH_MS
            preveri("po 10 min spet obvesti", ViriNaprave.zavrnilaDruga("Televizor"))
            preveriEnako("seznam naprav brez virov", listOf("Tablica", "Televizor"), ViriNaprave.zavrnileDruge())
            preveri("odgovor z drugo kodo ni zavrnitev", ViriNaprave.zavrnitevIzOdgovora("{\"code\":\"drugo\"}") == null)
            preveriEnako("cast.ack z error_code brez imena", "",
                ViriNaprave.zavrnitevIzOdgovora("{\"type\":\"cast.ack\",\"status\":\"rejected\",\"error_code\":\"${ViriNaprave.KODA}\"}"))
        } finally {
            ViriNaprave.ura = prejUra
            ViriNaprave.ponastavi()
        }
    }

    Thread.sleep(400)
    streznik.ustavi()
    preveri("po ustavitvi ne teče", !streznik.teceZdaj())

    println()
    if (napak == 0) {
        println("VSE V REDU")
    } else {
        println("NAPAK: $napak")
        System.exit(1)
    }
}
