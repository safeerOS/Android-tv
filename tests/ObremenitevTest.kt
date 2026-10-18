package si.safeer.tv.cast

/**
 * Meritev ozkega grla huba: kaj se zgodi, ko se veliko naprav poveze hkrati (po ponovnem zagonu
 * sredisca, podjetje z 200 clani za enim naslovom) in ko ena naprava ne bere svoje vticnice (zamrznjena aplikacija,
 * izgubljen WiFi).
 *
 * Usmerjevalnik objavi seznam naprav vsem povezanim; ce je posiljanje blokirajoce, ena zataknjena
 * naprava zadrzi vse ostale. Test to izmeri v stevilkah, brez omrezja.
 */
object ObremenitevTest {

    private class Beleznik(val ime: String, val zakasnitevMs: Long = 0) : HubUsmerjevalnik.Odjemalec {
        override val naslov = "10.0.0.1"
        val prejeto = ArrayList<String>()
        var bajtov = 0L
        override fun poslji(besedilo: String) {
            if (zakasnitevMs > 0) Thread.sleep(zakasnitevMs)
            synchronized(prejeto) { prejeto.add(besedilo); bajtov += besedilo.length }
        }
        override fun zapri(koda: Int, razlog: String) {}
    }

    private fun registriraj(h: HubUsmerjevalnik, od: Beleznik, id: String) {
        h.obdelaj(od, """{"id":"r-$id","type":"cast.register","payload":{"device_id":"$id","name":"$id","role":"sender","capabilities":["url","control"]}}""")
    }

    private fun trdi(pogoj: Boolean, sporocilo: String) {
        if (!pogoj) throw AssertionError(sporocilo)
    }

    // ------------------------------------------------------------------ podjetje: 200 clanov z enega naslova

    /** Minimalen odjemalec WebSocket (RFC 6455): maskirani okvirji besedila, branje okvirjev streznika. */
    private class WsOdjemalec(vrata: Int, vstopnica: String) {
        val vticnica = java.net.Socket()
        val vhod: java.io.InputStream
        val izhod: java.io.OutputStream

        init {
            vticnica.connect(java.net.InetSocketAddress("127.0.0.1", vrata), 60_000)
            vticnica.soTimeout = 60_000
            izhod = vticnica.getOutputStream()
            vhod = vticnica.getInputStream().buffered()
            izhod.write(("GET /cast/ws?ticket=$vstopnica HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .toByteArray(Charsets.US_ASCII))
            izhod.flush()
            val glava = StringBuilder()
            while (!glava.endsWith("\r\n\r\n")) {
                val b = vhod.read()
                if (b < 0) break
                glava.append(b.toChar())
            }
            if (!glava.startsWith("HTTP/1.1 101")) throw IllegalStateException("rokovanje: ${glava.take(60)}")
        }

        fun poslji(besedilo: String) {
            val p = besedilo.toByteArray(Charsets.UTF_8)
            val o = java.io.ByteArrayOutputStream()
            o.write(0x81)
            when {
                p.size < 126 -> o.write(0x80 or p.size)
                p.size <= 0xFFFF -> { o.write(0x80 or 126); o.write(p.size shr 8); o.write(p.size and 0xFF) }
                else -> { o.write(0x80 or 127); for (i in 7 downTo 0) o.write(((p.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            val maska = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            o.write(maska)
            for (i in p.indices) o.write(p[i].toInt() xor maska[i and 3].toInt())
            synchronized(this) { izhod.write(o.toByteArray()); izhod.flush() }
        }

        /** Naslednje besedilo streznika; null ob koncu ali zaprtju. */
        fun beri(): String? {
            while (true) {
                val prvi = vhod.read()
                if (prvi < 0) return null
                val op = prvi and 0x0F
                var n = (vhod.read() and 0x7F).toLong()
                if (n == 126L) n = ((vhod.read() shl 8) or vhod.read()).toLong()
                else if (n == 127L) { n = 0; repeat(8) { n = (n shl 8) or vhod.read().toLong() } }
                val b = ByteArray(n.toInt())
                var k = 0
                while (k < b.size) { val r = vhod.read(b, k, b.size - k); if (r < 0) return null; k += r }
                when (op) {
                    0x1 -> return String(b, Charsets.UTF_8)
                    0x8 -> return null
                }
            }
        }

        fun zapri() = try { vticnica.close() } catch (_: Exception) { }
    }

    /** POST na sredisce po golem HTTP (Connection: close): (koda, telo). */
    private fun post(vrata: Int, pot: String, telo: String): Pair<Int, String> {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", vrata), 60_000)
            s.soTimeout = 60_000
            val b = telo.toByteArray(Charsets.UTF_8)
            s.getOutputStream().write(("POST $pot HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${b.size}\r\n\r\n").toByteArray(Charsets.US_ASCII) + b)
            s.getOutputStream().flush()
            val odgovor = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            return (odgovor.substringAfter(" ").substringBefore(" ").toIntOrNull() ?: 0) to odgovor.substringAfter("\r\n\r\n")
        }
    }

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    private class Izid(val naprav: Int, val vrsta: Int, val zna: Boolean) {
        val prijavljenih = java.util.concurrent.atomic.AtomicInteger(0)
        val celSeznam = java.util.concurrent.atomic.AtomicInteger(0)
        val napak = java.util.concurrent.ConcurrentLinkedQueue<String>()
        @Volatile var vsiPrijavljeniMs = 0.0
        @Volatile var vsiSeznamMs = 0.0
        val prijavaMs = java.util.concurrent.ConcurrentLinkedQueue<Double>()
    }

    /**
     * [naprav] clanov kroga z ENEGA naslova (127.0.0.1, kot podjetje za NAT-om) se hkrati prijavi pri pravem srediscu
     * (HubStreznik + HubUsmerjevalnik, goli HTTP na zanki): izziv -> podpis ECDSA -> vstopnica -> WebSocket ->
     * cast.register. Meri cas do zadnje sprejete prijave in do trenutka, ko ima vsak cel seznam vseh [naprav] naprav.
     * [zna]: odjemalci z devdelta1 (posnetek + spremembe); brez nje starejsi (cel seznam, nadomescanje v vrsti).
     */
    private fun podjetje(naprav: Int, vrsta: Int, zna: Boolean): Izid {
        val izid = Izid(naprav, vrsta, zna)
        val u = HubUsmerjevalnik(oknoSeznamaMs = SeznamSprememb.OKNO_MS)
        u.lastniOdtis = "ab12cd34"
        val gen = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
        val hub = gen.generateKeyPair()
        u.vpisiLastniKljuc("hub-tv", "Sredisce", b64(hub.public.encoded), "tv")
        val kljuci = (0 until naprav).map { gen.generateKeyPair() }
        for (i in 0 until naprav) u.krog.dodaj(KrogZaupanja.Clan("clan-$i", b64(kljuci[i].public.encoded), "Clan $i", "phone", KrogZaupanja.zdaj(), "hub-tv"))
        val streznik = HubStreznik(
            zeljenaVrata = 0,
            naZahtevo = { z -> u.odgovori(z) },
            preveriVstopnico = { z -> u.preveriVstopnico(z) },
            naPovezavo = { p ->
                val o = object : HubUsmerjevalnik.Odjemalec {
                    override val naslov = p.naslov
                    override val vstopnica: String? = p.zahteva.poizvedba["ticket"]
                    override fun poslji(besedilo: String) = p.poslji(besedilo)
                    override fun posljiNadomestljivo(besedilo: String, kljuc: String) = p.poslji(besedilo, kljuc)
                    override fun zapri(koda: Int, razlog: String) = p.zapri(koda, razlog)
                }
                p.naSporocilo = { u.obdelaj(o, it) }
                p.naZaprtje = { u.odklopi(o) }
            },
            cakalnaVrsta = vrsta
        )
        trdi(streznik.zazeni(), "sredisce se ni zagnalo")
        val vrata = streznik.vrata
        val start = java.util.concurrent.CountDownLatch(1)
        val konec = java.util.concurrent.CountDownLatch(naprav)
        val odjemalci = java.util.concurrent.ConcurrentLinkedQueue<WsOdjemalec>()
        var t0 = 0L
        val zmoznosti = if (zna) "[\"url\",\"control\",\"${SeznamSprememb.ZMOZNOST}\"]" else "[\"url\",\"control\"]"
        val niti = (0 until naprav).map { i ->
            Thread {
                val id = "clan-$i"
                try {
                    start.await()
                    val (k1, t1) = post(vrata, "/cast/auth/challenge", "{\"device_id\":\"$id\"}")
                    val nonce = JsonLahki.objekt(t1)?.niz("nonce").orEmpty()
                    if (k1 != 200 || nonce.isEmpty()) throw IllegalStateException("izziv $k1 $t1")
                    val sig = java.security.Signature.getInstance("SHA256withECDSA")
                    sig.initSign(kljuci[i].private)
                    sig.update(u.podatkiZaPodpis(id, nonce))
                    val (k2, t2) = post(vrata, "/cast/auth/ticket",
                        "{\"device_id\":\"$id\",\"nonce\":\"$nonce\",\"signature\":\"${b64(sig.sign())}\"}")
                    val vstopnica = JsonLahki.objekt(t2)?.niz("ticket").orEmpty()
                    if (k2 != 200 || vstopnica.isEmpty()) throw IllegalStateException("vstopnica $k2 ${t2.take(80)}")
                    val ws = WsOdjemalec(vrata, vstopnica)
                    odjemalci.add(ws)
                    ws.poslji("{\"id\":\"r-$id\",\"type\":\"cast.register\",\"payload\":{\"device_id\":\"$id\",\"name\":\"Clan $i\"," +
                        "\"role\":\"sender\",\"capabilities\":$zmoznosti}}")
                    val prejemnik = SeznamSprememb.Prejemnik()
                    var prijavljen = false
                    while (true) {
                        val surovo = ws.beri() ?: throw IllegalStateException("povezava zaprta (prijavljen=$prijavljen)")
                        val (besedilo, zahtevaj) = prejemnik.sprejmi(surovo)
                        if (zahtevaj) ws.poslji(SeznamSprememb.zahteva("s-$id-${System.nanoTime()}"))
                        val b = besedilo ?: continue
                        val p = JsonLahki.objekt(b) ?: continue
                        when (p.niz("type")) {
                            "cast.ack" -> if (p.niz("ref_id") == "r-$id") {
                                if (p.niz("status") != "accepted") throw IllegalStateException("prijava ${p.niz("error_code")}")
                                prijavljen = true
                                val ms = (System.nanoTime() - t0) / 1e6
                                izid.prijavaMs.add(ms)
                                if (izid.prijavljenih.incrementAndGet() == naprav) izid.vsiPrijavljeniMs = ms
                            }
                            SeznamSprememb.POSNETEK -> {
                                val n = JsonLahki.elementi(p.surovo("devices") ?: "[]")?.size ?: 0
                                if (prijavljen && n >= naprav) {
                                    if (izid.celSeznam.incrementAndGet() == naprav) izid.vsiSeznamMs = (System.nanoTime() - t0) / 1e6
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    izid.napak.add("$id: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                } finally {
                    konec.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        t0 = System.nanoTime()
        start.countDown()
        val vRoku = konec.await(120, java.util.concurrent.TimeUnit.SECONDS)
        val fd = try { java.io.File("/proc/self/fd").list()?.size ?: -1 } catch (_: Throwable) { -1 }
        println(("podjetje: %d clanov z enega naslova, vrsta listen %d, %s: vsi prijavljeni v %.0f ms, vsi imajo cel seznam " +
            "v %.0f ms; prijava mediana %.0f ms, najdlje %.0f ms; napak %d%s").format(naprav, vrsta,
            if (zna) "devdelta1" else "starejsi (cel seznam)", izid.vsiPrijavljeniMs, izid.vsiSeznamMs,
            izid.prijavaMs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }, izid.prijavaMs.maxOrNull() ?: 0.0,
            izid.napak.size, if (vRoku) "" else " (NI KONCANO V 120 s)"))
        println(("  najvec hkrati nepreverjenih %d (meja %d na naslov), najdlje nepreverjena %d ms, izrinjenih %d; povezav %d; " +
            "seznamov %.1f MB, nadomestenih v vrsti %d; deskriptorjev procesa %d, niti %d")
            .format(streznik.najvecNepreverjenih, HubStreznik.NAJVEC_NEPREVERJENIH_NA_VIR, streznik.najdljeNepreverjenaMs,
                streznik.izrinjenih, streznik.steviloPovezav(), u.poslanoSeznamov / 1e6, streznik.nadomestenih(), fd,
                Thread.activeCount()))
        izid.napak.take(5).forEach { println("  napaka: $it") }
        odjemalci.forEach { it.zapri() }
        niti.forEach { it.join(5_000) }
        streznik.ustavi()
        u.ustaviObjave()
        return izid
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val naprav = 20

        // 1. Vihar ob ponovnem zagonu: vseh N naprav se poveze ena za drugo.
        run {
            val h = HubUsmerjevalnik()
            val naprave = (1..naprav).map { Beleznik("n-$it") }
            val zacetek = System.nanoTime()
            naprave.forEachIndexed { i, o -> registriraj(h, o, "n-${i + 1}") }
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            val sporocil = naprave.sumOf { it.prejeto.size }
            val bajtov = naprave.sumOf { it.bajtov }
            println("vihar: $naprav naprav v %.1f ms; poslanih sporocil $sporocil, bajtov $bajtov".format(ms))
            println("  na napravo: povprecno %.1f sporocil".format(sporocil.toDouble() / naprav))
            trdi(sporocil > 0, "nic poslanega")
        }

        // 2. Ena naprava ne bere (poslji traja 300 ms): koliko casa traja prijava naslednje naprave?
        run {
            val h = HubUsmerjevalnik()
            val hitre = (1..5).map { Beleznik("h-$it") }
            hitre.forEachIndexed { i, o -> registriraj(h, o, "h-${i + 1}") }
            val zataknjena = Beleznik("zataknjena", zakasnitevMs = 300)
            registriraj(h, zataknjena, "zataknjena")

            val nova = Beleznik("nova")
            val zacetek = System.nanoTime()
            registriraj(h, nova, "nova")
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            println("zataknjena naprava: prijava naslednje naprave je trajala %.0f ms".format(ms))

            // Koliko casa traja navadno posredovanje sporocila med dvema hitrima napravama,
            // medtem ko je v seznamu zataknjena naprava?
            val zacetek2 = System.nanoTime()
            h.obdelaj(hitre[0], """{"id":"s1","type":"share.text","target":"h-2","payload":{"text":"pozdrav"}}""")
            val ms2 = (System.nanoTime() - zacetek2) / 1_000_000.0
            println("  posredovanje besedila med hitrima napravama: %.0f ms".format(ms2))

            // Odklop zataknjene naprave (npr. ko WiFi pade) - tudi to objavi seznam vsem.
            val zacetek3 = System.nanoTime()
            h.odklopi(zataknjena)
            val ms3 = (System.nanoTime() - zacetek3) / 1_000_000.0
            println("  odklop zataknjene naprave: %.0f ms".format(ms3))
        }

        // 3. Vzporedno: 8 niti posilja sporocila, ena naprava je zataknjena.
        run {
            val h = HubUsmerjevalnik()
            val hitre = (1..8).map { Beleznik("p-$it") }
            hitre.forEachIndexed { i, o -> registriraj(h, o, "p-${i + 1}") }
            val zataknjena = Beleznik("p-zataknjena", zakasnitevMs = 200)
            registriraj(h, zataknjena, "p-zataknjena")
            val zacetek = System.nanoTime()
            val niti = (0 until 8).map { k ->
                Thread {
                    repeat(20) {
                        h.obdelaj(hitre[k], """{"id":"m$k-$it","type":"share.text","target":"p-${(k % 8) + 1}","payload":{"text":"x"}}""")
                    }
                }.apply { start() }
            }
            niti.forEach { it.join() }
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            println("vzporedno: 8 niti x 20 sporocil v %.0f ms".format(ms))
        }
        // 4. Podjetje: 200 clanov kroga z ENEGA naslova hkrati, pravo sredisce na zanki (izziv, podpis, vstopnica,
        // WebSocket, prijava). Do 0.5.90: meja 24 vticnic, 16 povezav, 32 naprav, 8 izzivov/vstopnic - tu bi jih vecina
        // dobila 503 ali 401. Vrsta listen 16 (prej) proti CAKALNA_VRSTA (zdaj).
        println("viri: meja deskriptorjev %d, nad rezervo %s; pomnilnika nad rezervo %s MiB".format(ViriNaprave.mejaDeskriptorjev(),
            ViriNaprave.prostihDeskriptorjev()?.toString() ?: "?", ViriNaprave.prostegaPomnilnika()?.let { (it / (1024 * 1024)).toString() } ?: "?"))
        ViriNaprave.ponastavi()
        val ponovitev = (System.getenv("OBREMENITEV_PONOVITEV") ?: "1").toIntOrNull() ?: 1
        repeat(ponovitev) {
            // Izmenicno, da ogrevanje JIT ne koristi samo eni vrsti.
            if (it % 2 == 1) podjetje(200, HubStreznik.CAKALNA_VRSTA, zna = true)
            podjetje(200, 16, zna = true)
            val nova = podjetje(200, HubStreznik.CAKALNA_VRSTA, zna = true)
            val starejsi = podjetje(200, HubStreznik.CAKALNA_VRSTA, zna = false)
            for (iz in listOf(nova, starejsi)) {
                trdi(iz.napak.isEmpty(), "podjetje (zna=${iz.zna}): napake ${iz.napak.take(3)}")
                trdi(iz.prijavljenih.get() == 200, "podjetje (zna=${iz.zna}): prijavljenih ${iz.prijavljenih.get()} od 200")
                trdi(iz.celSeznam.get() == 200, "podjetje (zna=${iz.zna}): cel seznam ima ${iz.celSeznam.get()} od 200")
            }
        }
        trdi(ViriNaprave.stanje() == null, "med preizkusom je zmanjkalo virov: ${ViriNaprave.stanje()}")
        println("OK ObremenitevTest")
    }
}
