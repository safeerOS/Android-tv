package si.safeer.tv.os

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

// Pogon Usenet, korak 1: odjemalec NNTP prek TLS ([UsenetNntp]) proti laznemu strezniku na 127.0.0.1 s samopodpisanim
// potrdilom, ki ga preizkus naredi s keytool iz JDK. Pravi ponudniki in poverilnice se ne uporabljajo.

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private const val UPORABNIK = "uporabnik-preizkusa"
private const val GESLO = "geslo-preizkusa-123"
private const val ID = "skrivni-clanek-1@lazni.invalid"

/** Vsa sporocila izjem (z vzroki) iz preizkusov - nobeno ne sme vsebovati gesla, imena ali message-id. */
private val sporocila: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

private fun zapomni(e: Throwable) {
    var t: Throwable? = e
    while (t != null) { sporocila += t.toString(); t = t.cause }
}

/** Vrsta napake NNTP ali »OK«. */
private fun izid(f: () -> Unit): String = try {
    f(); "OK"
} catch (e: UsenetNntp.NapakaNntp) {
    zapomni(e); e.vrsta.name
} catch (e: IllegalArgumentException) {
    zapomni(e); "ARGUMENT"
} catch (e: IllegalStateException) {
    zapomni(e); "NIT"
}

private class Potrdilo(val streznik: SSLContext, val zaupaj: SSLSocketFactory, val list: java.security.cert.X509Certificate)

/** Samopodpisano potrdilo z imeni [san] (keytool -ext SAN=...): kontekst streznika in tovarna, ki mu zaupa. */
private fun potrdilo(mapa: File, cn: String, san: String): Potrdilo {
    val shramba = File(mapa, "p-${System.nanoTime()}.p12")
    val geslo = "preizkus"
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val ext = if (san.isEmpty()) emptyList() else listOf("-ext", "SAN=$san")
    val p = ProcessBuilder(listOf(keytool, "-genkeypair", "-alias", "a", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=$cn") + ext + listOf("-validity", "2", "-storetype", "PKCS12", "-keystore", shramba.path,
        "-storepass", geslo, "-keypass", geslo)).redirectErrorStream(true).start()
    val izpis = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "keytool: $izpis" }
    val ks = KeyStore.getInstance("PKCS12")
    shramba.inputStream().use { ks.load(it, geslo.toCharArray()) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, geslo.toCharArray())
    val s = SSLContext.getInstance("TLS")
    s.init(kmf.keyManagers, null, null)
    val zaupane = KeyStore.getInstance("PKCS12")
    zaupane.load(null, null)
    zaupane.setCertificateEntry("s", ks.getCertificate("a"))
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(zaupane)
    val k = SSLContext.getInstance("TLS")
    k.init(null, tmf.trustManagers, null)
    return Potrdilo(s, k.socketFactory, ks.getCertificate("a") as java.security.cert.X509Certificate)
}

/** Lazni streznik NNTP: prijava, BODY, STAT, prekinitev sredi bloka, zamuda, posiljanje po koscih. */
private class LazniStreznik(ctx: SSLContext) : Closeable {
    private val posluh = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val vrata: Int get() = posluh.localPort
    /** message-id -> blok, kot ga poslje streznik (pike ze podvojene, brez koncne ».«). */
    val clanki = ConcurrentHashMap<String, ByteArray>()
    @Volatile var pozdrav = "200 Lazni streznik"
    @Volatile var odgovorUporabnik = "381 Geslo, prosim"
    val prekini = AtomicInteger()
    @Volatile var zamudaMs = 0L
    /** Ce ni null, BODY odgovori s tem (npr. »403 Archive server error«). */
    @Volatile var napakaBody: String? = null
    @Volatile var poKoscih = false
    /** Zaklep racuna: zamuda odgovora na PASS (brez vrat bazena bi vse niti hkrati poslale geslo), besedilo zavrnitve. */
    @Volatile var zamudaGeslaMs = 0L
    @Volatile var napacno: String? = null
    /** Najvec hkrati prijavljenih sej (meja povezav racuna) in odgovor ob preseganju. */
    @Volatile var meja = Int.MAX_VALUE
    @Volatile var odgovorMeja = "502 Too many connections"
    @Volatile var geslaNeOdgovori = false
    val prijavljenih = AtomicInteger()
    val povezav = AtomicInteger()
    val hkrati = AtomicInteger()
    val najvecHkrati = AtomicInteger()
    /** Samo imena ukazov (BODY, STAT, AUTHINFO USER ...) - ne argumenti. */
    val ukazi: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
    private val vticnice: MutableList<Socket> = Collections.synchronizedList(ArrayList<Socket>())
    @Volatile private var tece = true

    init {
        Thread { sprejemaj() }.apply { isDaemon = true; start() }
    }

    private fun sprejemaj() {
        while (tece) {
            val s = try { posluh.accept() } catch (_: IOException) { return }
            vticnice += s
            Thread { strezi(s) }.apply { isDaemon = true; start() }
        }
    }

    private fun strezi(s: Socket) {
        var seja = false
        povezav.incrementAndGet()
        val h = hkrati.incrementAndGet()
        najvecHkrati.accumulateAndGet(h) { a, b -> maxOf(a, b) }
        try {
            s.soTimeout = 30_000
            val vhod = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val izhod = s.getOutputStream()
            fun posli(v: String) { izhod.write((v + "\r\n").toByteArray(Charsets.ISO_8859_1)); izhod.flush() }
            posli(pozdrav)
            if (!pozdrav.startsWith("20")) return
            var ime: String? = null
            var prijavljen = false
            while (true) {
                val v = vhod.readLine() ?: return
                val ukaz = v.substringBefore(' ').uppercase()
                ukazi += if (ukaz == "AUTHINFO") "AUTHINFO " + v.split(' ').getOrElse(1) { "" }.uppercase() else ukaz
                val id = v.substringAfter('<', "").substringBefore('>')
                if (v.startsWith("AUTHINFO USER ")) {
                    ime = v.removePrefix("AUTHINFO USER ")
                    posli(odgovorUporabnik)
                } else if (v.startsWith("AUTHINFO PASS ")) {
                    if (zamudaGeslaMs > 0) Thread.sleep(zamudaGeslaMs)
                    if (geslaNeOdgovori) return
                    prijavljen = ime == UPORABNIK && v.removePrefix("AUTHINFO PASS ") == GESLO
                    if (prijavljen) {
                        if (prijavljenih.incrementAndGet() > meja) {
                            prijavljenih.decrementAndGet()
                            posli(odgovorMeja)
                            return
                        }
                        seja = true
                    }
                    posli(if (prijavljen) "281 Prijava uspela" else napacno ?: "481 Authentication failed for $ime")
                } else if (ukaz == "QUIT") {
                    posli("205 Adijo")
                    return
                } else if (!prijavljen) {
                    posli("480 Authentication required")
                } else if (ukaz == "STAT") {
                    posli(if (clanki.containsKey(id)) "223 0 <$id>" else "430 No article with that message-id <$id>")
                } else if (ukaz == "BODY") {
                    val blok = clanki[id]
                    val napaka = napakaBody
                    if (napaka != null) {
                        posli(napaka)
                        if (napaka.startsWith("205") || napaka.startsWith("400")) return
                    } else if (blok == null) {
                        posli("430 No article with that message-id <$id>")
                    } else {
                        posli("222 0 <$id>")
                        if (zamudaMs > 0) Thread.sleep(zamudaMs)
                        if (prekini.get() > 0 && prekini.decrementAndGet() >= 0) {
                            izhod.write(blok, 0, blok.size / 2)
                            izhod.flush()
                            s.close()
                            return
                        }
                        val vse = blok + ".\r\n".toByteArray()
                        if (poKoscih) {
                            var i = 0
                            while (i < vse.size) {
                                val k = minOf(7, vse.size - i)
                                izhod.write(vse, i, k)
                                izhod.flush()
                                i += k
                            }
                        } else {
                            izhod.write(vse)
                            izhod.flush()
                        }
                    }
                } else {
                    posli("500 Neznan ukaz")
                }
            }
        } catch (_: Exception) {
        } finally {
            if (seja) prijavljenih.decrementAndGet()
            hkrati.decrementAndGet()
            try { s.close() } catch (_: IOException) { }
        }
    }

    override fun close() {
        tece = false
        try { posluh.close() } catch (_: IOException) { }
        synchronized(vticnice) { for (s in vticnice) try { s.close() } catch (_: IOException) { } }
    }
}

private fun nastavitve(vrata: Int, uporabnik: String? = UPORABNIK, geslo: String? = GESLO, povezav: Int = 8,
                       rokBranjaMs: Int = 5_000, poskusov: Int = 2, gostitelj: String = "localhost", msPremora: Long = 1000) =
    UsenetNntp.Nastavitve(gostitelj, uporabnik, geslo, vrata = vrata, povezav = povezav, rokPovezaveMs = 3_000,
        rokBranjaMs = rokBranjaMs, rokCakanjaMs = 10_000, poskusov = poskusov, msNaSekundoPremora = msPremora)

/** [niti] niti hkrati (po [zahtev] zahtev): izidi v vrstnem redu konca. */
private fun hkrati(k: UsenetNntp, niti: Int, zahtev: Int, id: String = ID): List<String> {
    val izidi: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
    val start = java.util.concurrent.CountDownLatch(1)
    val t = (1..niti).map { Thread { start.await(); repeat(zahtev) { izidi += izid { k.body(id) } } }.apply { start() } }
    start.countDown()
    for (x in t) x.join(30_000)
    return izidi.toList()
}

/** Clanek yEnc (en del velikega filma) z nakljucnimi bajti: izvorni bajti, telo in telo, kot ga poslje streznik. */
private class Clanek(val podatki: ByteArray, val telo: ByteArray, val poslano: ByteArray)

private fun clanek(velikost: Int, seme: Long): Clanek {
    val d = ByteArray(velikost).also { java.util.Random(seme).nextBytes(it) }
    val t = YencKodirnik.kodiraj(d, "film.mkv", 3, 70, 2L * velikost, 70L * velikost)
    return Clanek(d, t, YencKodirnik.podvojiPike(t))
}

fun main() {
    println("Preizkus NNTP (pogon Usenet)")
    val mapa = java.nio.file.Files.createTempDirectory("usenet-nntp").toFile()
    try {
        val prava = potrdilo(mapa, "localhost", "dns:localhost,ip:127.0.0.1")
        val napacna = potrdilo(mapa, "napacno.example", "dns:napacno.example")

        // Izidi odgovorov (ciste funkcije; vse kode so v skupnih primerih nntp_izid, tu samo poglavitne).
        preveriEnako("pozdrav 200", "OK", UsenetNntp.izid(UsenetNntp.Faza.POZDRAV, "200 ok").name)
        preveriEnako("uporabnik 381 -> geslo", "GESLO", UsenetNntp.izid(UsenetNntp.Faza.UPORABNIK, "381 PASS required").name)
        preveriEnako("geslo 481 meja povezav", "ZASEDEN", UsenetNntp.izid(UsenetNntp.Faza.GESLO, "481 Max Connections reached").name)
        preveriEnako("body 430", "NI_CLANKA", UsenetNntp.izid(UsenetNntp.Faza.BODY, "430 No such article").name)
        preveriEnako("body 403", "NAPAKA_STREZNIKA", UsenetNntp.izid(UsenetNntp.Faza.BODY, "403 Archive server error").name)
        preveriEnako("body 2220", "NAPAKA_PROTOKOLA", UsenetNntp.izid(UsenetNntp.Faza.BODY, "2220 x").name)
        preveriEnako("varen argument", true, UsenetNntp.varenArgument("geslo s presledkom!"))
        preveriEnako("argument s CRLF", false, UsenetNntp.varenArgument("a\r\nQUIT"))
        preveriEnako("prazen argument", false, UsenetNntp.varenArgument(""))
        preveriEnako("konec bloka na zacetku", 0, UsenetNntp.najdiKonec(".\r\n".toByteArray(), 0, 0, 3))
        preveriEnako("konec bloka za vrstico", 5, UsenetNntp.najdiKonec("abc\r\n.\r\n".toByteArray(), 0, 0, 8))
        preveriEnako("podvojena pika ni konec", -1, UsenetNntp.najdiKonec("abc\r\n..\r\n".toByteArray(), 0, 0, 9))
        preveriEnako("pika sredi vrstice ni konec", -1, UsenetNntp.najdiKonec("ab.\r\n".toByteArray(), 0, 0, 5))
        preveriEnako("iskanje sredi vrstice ni zacetek", -1, UsenetNntp.najdiKonec("ab.\r\n".toByteArray(), 0, 2, 5))
        preveriEnako("nepopoln konec", -1, UsenetNntp.najdiKonec("abc\r\n.\r".toByteArray(), 0, 0, 7))
        preveriEnako("toString nastavitev", false, nastavitve(1).toString().let { it.contains(GESLO) || it.contains(UPORABNIK) || it.contains("localhost") })
        preveriEnako("meja povezav 50", 50, UsenetNntp.Nastavitve("x", null, null, povezav = 500).povezav)
        preveriEnako("najmanj 1 povezava", 1, UsenetNntp.Nastavitve("x", null, null, povezav = 0).povezav)

        LazniStreznik(prava.streznik).use { s ->
            val c1 = clanek(716_800, 1)
            val izvorni = c1.podatki
            val blok = c1.telo
            s.clanki[ID] = c1.poslano
            preveriEnako("clanek ima vrstice s piko na zacetku (podvojene)", true, String(c1.poslano, Charsets.ISO_8859_1).contains("\n.."))
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                println("prijava, BODY, yEnc")
                var surovo = ByteArray(0)
                preveriEnako("BODY uspe", "OK", izid { surovo = k.body(ID) })
                preveriEnako("telo brez podvojenih pik je tako, kot ga je kodiral posiljatelj", true, surovo.contentEquals(blok))
                val del = UsenetYenc.dekodiraj(surovo)
                preveriEnako("yEnc: bajti filma", true, del.podatki.contentEquals(izvorni))
                preveriEnako("yEnc: odmik dela", 2L * 716_800, del.zacetek)
                preveriEnako("yEnc: CRC", true, del.crcPreverjen)
                preveriEnako("BODY z oklepaji", "OK", izid { k.body("<$ID>") })
                preveriEnako("ena povezava za vec clankov", 1, k.odprtoSkupaj)
                preveriEnako("430 -> NI_CLANKA", "NI_CLANKA", izid { k.body("ni-ga@lazni.invalid") })
                preveriEnako("po 430 ista povezava", 1, k.odprtoSkupaj)
                preveriEnako("STAT obstaja", true, k.stat(ID))
                preveriEnako("STAT ne obstaja", false, k.stat("ni-ga@lazni.invalid"))
                preveriEnako("prijava enkrat na povezavo", 1, s.ukazi.count { it == "AUTHINFO PASS" })
                println("vrinjanje ukaza")
                val pred = s.ukazi.size
                preveriEnako("id s CRLF zavrnjen", "ARGUMENT", izid { k.body("a@b\r\nQUIT") })
                preveriEnako("id s presledkom zavrnjen", "ARGUMENT", izid { k.stat("a b@c") })
                preveriEnako("nic poslano", pred, s.ukazi.size)
            }
            println("prijava")
            UsenetNntp(nastavitve(s.vrata, geslo = "napacno-geslo"), prava.zaupaj).use { k ->
                preveriEnako("napacno geslo -> PRIJAVA", "PRIJAVA", izid { k.body(ID) })
                preveriEnako("napacno geslo: brez ponovitve", 1, k.odprtoSkupaj)
            }
            UsenetNntp(nastavitve(s.vrata, uporabnik = null, geslo = null), prava.zaupaj).use { k ->
                preveriEnako("brez prijave -> 480 PRIJAVA", "PRIJAVA", izid { k.body(ID) })
            }
            val predUser = s.ukazi.count { it == "AUTHINFO USER" }
            UsenetNntp(nastavitve(s.vrata, uporabnik = "a\r\nSTAT <x>"), prava.zaupaj).use { k ->
                preveriEnako("ime s CRLF -> PRIJAVA", "PRIJAVA", izid { k.body(ID) })
            }
            preveriEnako("ime s CRLF ni poslano", predUser, s.ukazi.count { it == "AUTHINFO USER" })
            s.odgovorUporabnik = "481 Too many connections for this account"
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                preveriEnako("481 meja povezav -> ZASEDEN", "ZASEDEN", izid { k.body(ID) })
            }
            s.odgovorUporabnik = "381 Geslo, prosim"

            println("prekinitev, rok, zapiranje")
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                s.prekini.set(1)
                var surovo = ByteArray(0)
                preveriEnako("prekinitev sredi BODY: nov poskus uspe", "OK", izid { surovo = k.body(ID) })
                preveriEnako("prekinitev: blok cel", true, surovo.contentEquals(blok))
                preveriEnako("prekinitev: druga povezava", 2, k.odprtoSkupaj)
                s.prekini.set(100)
                preveriEnako("prekinitev vsakic -> OMREZJE", "OMREZJE", izid { k.body(ID) })
                preveriEnako("najvec 2 poskusa (prosta + nova)", 3, k.odprtoSkupaj)
                s.prekini.set(0)
            }
            s.zamudaMs = 3_000
            UsenetNntp(nastavitve(s.vrata, rokBranjaMs = 400), prava.zaupaj).use { k ->
                val t0 = System.nanoTime()
                preveriEnako("rok branja -> ROK (sveza povezava: brez ponovitve)", "ROK", izid { k.body(ID) })
                val ms = (System.nanoTime() - t0) / 1_000_000
                preveriEnako("rok: en poskus v < 2,5 s (bilo $ms ms)", true, ms < 2_500)
            }
            s.zamudaMs = 5_000
            UsenetNntp(nastavitve(s.vrata, rokBranjaMs = 10_000), prava.zaupaj).use { k ->
                var rez = ""
                val t0 = System.nanoTime()
                val nit = Thread { rez = izid { k.body(ID) } }.apply { start() }
                Thread.sleep(400)
                k.close()
                nit.join(3_000)
                val ms = (System.nanoTime() - t0) / 1_000_000
                preveriEnako("zapiranje med branjem -> ZAPRTO", "ZAPRTO", rez)
                preveriEnako("zapiranje prekine branje v < 1,5 s (bilo $ms ms)", true, ms < 1_500)
                preveriEnako("po zapiranju ni povezav", 0, k.odprtih)
                preveriEnako("po zapiranju ni novih klicev", "ZAPRTO", izid { k.body(ID) })
            }
            s.zamudaMs = 0

            println("napake streznika")
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                preveriEnako("prva povezava", "OK", izid { k.body(ID) })
                s.napakaBody = "403 Archive server error"
                preveriEnako("403 -> NAPAKA_STREZNIKA", "NAPAKA_STREZNIKA", izid { k.body(ID) })
                preveriEnako("403: nov poskus z novo povezavo", 2, k.odprtoSkupaj)
                s.napakaBody = "205 Bye"
                preveriEnako("205 med BODY -> ZAPRTO", "ZAPRTO", izid { k.body(ID) })
                s.napakaBody = null
                preveriEnako("po napakah spet dela", "OK", izid { k.body(ID) })
                val pred = k.odprtoSkupaj
                s.napakaBody = "400 Idle timeout, closing connection"
                preveriEnako("400 na prosti povezavi (zapira): nov poskus, nato ZAPRTO", "ZAPRTO", izid { k.body(ID) })
                preveriEnako("400: ena nova povezava", pred + 1, k.odprtoSkupaj)
                s.napakaBody = null
            }

            println("pocasno posiljanje (konec bloka med dvema branjema)")
            val mali = clanek(5_000, 7)
            val izvMal = mali.podatki
            s.clanki["mali@lazni.invalid"] = mali.poslano
            s.poKoscih = true
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                var surovo = ByteArray(0)
                preveriEnako("po 7 bajtov", "OK", izid { surovo = k.body("mali@lazni.invalid") })
                preveriEnako("po 7 bajtov: yEnc", true, UsenetYenc.dekodiraj(surovo).podatki.contentEquals(izvMal))
                preveriEnako("po 7 bajtov: naslednji ukaz na isti povezavi", true, k.stat("mali@lazni.invalid"))
            }
            s.poKoscih = false

            println("prevelik clanek")
            s.clanki["velik@lazni.invalid"] = ByteArray(UsenetNntp.NAJVEC_TELESA + 10) { 'k'.code.toByte() }
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                preveriEnako("vec kot NAJVEC_TELESA -> NAPAKA_PROTOKOLA", "NAPAKA_PROTOKOLA", izid { k.body("velik@lazni.invalid") })
            }
            s.clanki.remove("velik@lazni.invalid")

            println("glavna nit")
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k ->
                android.os.Looper.glavnaNit = Thread.currentThread().id
                preveriEnako("na glavni niti -> IllegalStateException", "NIT", izid { k.body(ID) })
                android.os.Looper.glavnaNit = -1
                preveriEnako("na glavni niti nic odprto", 0, k.odprtoSkupaj)
            }

            println("potrdilo")
            UsenetNntp(nastavitve(s.vrata)).use { k ->
                preveriEnako("samopodpisano s sistemskimi overitelji -> POTRDILO", "POTRDILO", izid { k.body(ID) })
            }
            UsenetNntp(nastavitve(s.vrata, gostitelj = "127.0.0.1"), prava.zaupaj).use { k ->
                preveriEnako("ime IP v potrdilu", "OK", izid { k.body(ID) })
            }
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj, javax.net.ssl.HostnameVerifier { _, _ -> false }).use { k ->
                preveriEnako("dodatna preverba imena zavrne -> POTRDILO", "POTRDILO", izid { k.body(ID) })
            }
        }
        // Bazen na svojem strezniku: niti prejsnjih preizkusov (zamuda) bi sicer stele kot hkratne povezave.
        LazniStreznik(prava.streznik).use { s ->
            println("bazen")
            s.clanki[ID] = clanek(50_000, 5).poslano
            s.zamudaMs = 150
            UsenetNntp(nastavitve(s.vrata, povezav = 3), prava.zaupaj).use { k ->
                val izidi: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
                val niti = (1..9).map { Thread { izidi += izid { k.body(ID) } }.apply { start() } }
                for (t in niti) t.join(20_000)
                preveriEnako("9 hkratnih clankov uspe", List(9) { "OK" }, izidi.toList())
                preveriEnako("najvec 3 povezave hkrati (bilo ${s.najvecHkrati.get()})", true, s.najvecHkrati.get() <= 3)
                preveriEnako("povezave se ponovno rabijo", 3, s.povezav.get())
            }
        }
        // Nasa preverba imena (RFC 6125) neodvisno od platforme TLS (Grok 35 Y1).
        println("ime v potrdilu (RFC 6125)")
        preveriEnako("ime: localhost", true, UsenetNntp.imeUstreza("localhost", prava.list))
        preveriEnako("ime: velike crke in pika na koncu", true, UsenetNntp.imeUstreza("LocalHost.", prava.list))
        preveriEnako("ime: IP v SAN", true, UsenetNntp.imeUstreza("127.0.0.1", prava.list))
        preveriEnako("ime: drug IP", false, UsenetNntp.imeUstreza("127.0.0.2", prava.list))
        preveriEnako("ime: IPv6 ni v SAN", false, UsenetNntp.imeUstreza("::1", prava.list))
        preveriEnako("ime: localhost2", false, UsenetNntp.imeUstreza("localhost2", prava.list))
        preveriEnako("ime: napacno.example za drugo potrdilo", false, UsenetNntp.imeUstreza("localhost", napacna.list))
        preveriEnako("ime: prazno", false, UsenetNntp.imeUstreza("", prava.list))
        preveriEnako("dns: *.primer.invalid / news", true, UsenetNntp.dnsUstreza("news.primer.invalid", "*.primer.invalid"))
        preveriEnako("dns: * samo ena oznaka", false, UsenetNntp.dnsUstreza("a.b.primer.invalid", "*.primer.invalid"))
        preveriEnako("dns: * ne velja za golo domeno", false, UsenetNntp.dnsUstreza("primer.invalid", "*.primer.invalid"))
        preveriEnako("dns: *.invalid prevec splosno", false, UsenetNntp.dnsUstreza("news.invalid", "*.invalid"))
        preveriEnako("dns: delni nadomestni znak ne", false, UsenetNntp.dnsUstreza("news.primer.invalid", "n*.primer.invalid"))
        preveriEnako("dns: pripona brez pike ne", false, UsenetNntp.dnsUstreza("xprimer.invalid", "*.primer.invalid"))
        val brezSan = potrdilo(mapa, "localhost", "")
        preveriEnako("ime: brez SAN (samo CN) ne", false, UsenetNntp.imeUstreza("localhost", brezSan.list))
        // Naprava, ki endpointIdentificationAlgorithm ne uposteva: zaupano potrdilo za drugo ime mora pasti na nasi preverbi.
        LazniStreznik(napacna.streznik).use { s ->
            s.clanki[ID] = clanek(1000, 4).poslano
            UsenetNntp(UsenetNntp.Nastavitve("localhost", UPORABNIK, GESLO, vrata = s.vrata, endpointHttps = false), napacna.zaupaj).use { k ->
                preveriEnako("brez endpoint: napacno ime -> POTRDILO (nasa preverba)", "POTRDILO", izid { k.body(ID) })
                preveriEnako("brez endpoint: noben ukaz (ne AUTHINFO)", 0, s.ukazi.size)
            }
        }
        // Veljavno (zaupano) potrdilo za drugo ime: SSLSocket imena sam ne preveri - pade samo z endpointIdentification.
        LazniStreznik(napacna.streznik).use { s ->
            s.clanki[ID] = clanek(1000, 3).poslano
            UsenetNntp(nastavitve(s.vrata), napacna.zaupaj).use { k ->
                preveriEnako("napacno ime v potrdilu -> POTRDILO", "POTRDILO", izid { k.body(ID) })
                preveriEnako("napacno ime: ni prijave", 0, s.ukazi.size)
            }
        }
        LazniStreznik(prava.streznik).use { s ->
            s.pozdrav = "502 Too many connections"
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k -> preveriEnako("pozdrav 502 -> ZASEDEN", "ZASEDEN", izid { k.body(ID) }) }
            s.pozdrav = "400 Service temporarily unavailable"
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k -> preveriEnako("pozdrav 400 (zacasno) -> NAPAKA_STREZNIKA", "NAPAKA_STREZNIKA", izid { k.body(ID) }) }
        }
        UsenetNntp(nastavitve(1), prava.zaupaj).use { k ->
            preveriEnako("ni streznika -> OMREZJE", "OMREZJE", izid { k.body(ID) })
        }

        // Zaklep racuna (pregled Grok 35 Y2; lastnik 26. 10. 2026 03:05: »Nevarnost zaklepa racuna je prioriteta«).
        LazniStreznik(prava.streznik).use { s ->
            println("zaklep racuna")
            s.clanki[ID] = clanek(20_000, 11).poslano
            fun gesel() = s.ukazi.count { it == "AUTHINFO PASS" }
            s.zamudaGeslaMs = 300
            val napacno = nastavitve(s.vrata, geslo = "napacno-geslo-8")
            UsenetNntp(napacno, prava.zaupaj).use { k ->
                preveriEnako("8 niti x 3, napacno geslo: vse PRIJAVA", List(24) { "PRIJAVA" }, hkrati(k, 8, 3))
                preveriEnako("8 niti: napacno geslo do streznika enkrat", 1, gesel())
                preveriEnako("8 niti: ena povezava", 1, s.povezav.get())
            }
            s.zamudaGeslaMs = 0
            UsenetNntp(napacno, prava.zaupaj).use { k ->
                preveriEnako("zapah velja za racun (nov bazen) -> PRIJAVA", "PRIJAVA", izid { k.body(ID) })
                preveriEnako("zapah: brez nove povezave in gesla", 1 to 1, s.povezav.get() to gesel())
            }
            preveriEnako("prijavaZavrnjena", true, UsenetNntp.prijavaZavrnjena("localhost", UPORABNIK, "napacno-geslo-8"))
            UsenetNntp(nastavitve(s.vrata), prava.zaupaj).use { k -> preveriEnako("drugo geslo gre", "OK", izid { k.body(ID) }) }
            UsenetNntp.dovoliPrijavoZnova("localhost", UPORABNIK, "napacno-geslo-8")
            UsenetNntp(napacno, prava.zaupaj).use { k ->
                preveriEnako("po ponovnem vpisu en poskus", "PRIJAVA", izid { k.body(ID) })
            }
            preveriEnako("gesel skupaj (1 + 1 pravo + 1 po vpisu)", 3, gesel())
            UsenetNntp.dovoliPrijavoZnova("localhost", UPORABNIK, "napacno-geslo-8")

            s.napacno = "481 Authentication failed for this connection"
            val pred481 = gesel()
            UsenetNntp(nastavitve(s.vrata, geslo = "napacno-481", povezav = 3), prava.zaupaj).use { k ->
                preveriEnako("481 ... connection = PRIJAVA, 3x", List(3) { "PRIJAVA" }, List(3) { izid { k.body(ID) } })
                preveriEnako("481 ... connection: en poskus", pred481 + 1, gesel())
                preveriEnako("481: bazen ni skrcen", 3, k.najvecPovezav)
            }
            s.napacno = null
            UsenetNntp.dovoliPrijavoZnova("localhost", UPORABNIK, "napacno-481")

            // Ponudnik: prevec povezav javi kot napako prijave - po uspeli prijavi ZASEDEN in krcenje, ne zapah.
            s.meja = 2
            s.odgovorMeja = "481 Authentication failed"
            s.zamudaGeslaMs = 50
            s.zamudaMs = 30
            val predMeja = gesel()
            UsenetNntp(nastavitve(s.vrata, povezav = 6), prava.zaupaj).use { k ->
                preveriEnako("meja 2 po uspeli prijavi: 18 zahtev uspe", List(18) { "OK" }, hkrati(k, 6, 3))
                preveriEnako("meja 2: bazen na sprejete povezave", 2, k.najvecPovezav)
                preveriEnako("meja 2: najvec 6 prijav (brez zanke)", true, gesel() - predMeja <= 6)
            }
            s.meja = Int.MAX_VALUE
            s.zamudaGeslaMs = 0
            s.zamudaMs = 0

            s.geslaNeOdgovori = true
            val predNeznano = gesel()
            UsenetNntp(nastavitve(s.vrata, geslo = "ne-odgovori", msPremora = 5), prava.zaupaj).use { k ->
                preveriEnako("prijava brez odgovora: prvic premor, drugic zapah -> PRIJAVA", "PRIJAVA", izid { k.body(ID) })
                preveriEnako("prijava brez odgovora: 2 poskusa", predNeznano + 2, gesel())
                preveriEnako("prijava brez odgovora: nato nic", "PRIJAVA", izid { k.body(ID) })
                preveriEnako("prijava brez odgovora: se vedno 2", predNeznano + 2, gesel())
            }
            s.geslaNeOdgovori = false
            UsenetNntp.dovoliPrijavoZnova("localhost", UPORABNIK, "ne-odgovori")

            s.zamudaMs = 30
            UsenetNntp(nastavitve(s.vrata, povezav = 8), prava.zaupaj).use { k ->
                k.nastaviNajvec(2)
                s.najvecHkrati.set(0)
                preveriEnako("nastaviNajvec(2): 12 zahtev", List(12) { "OK" }, hkrati(k, 6, 2))
                preveriEnako("nastaviNajvec(2): najvec 2 hkrati", true, s.najvecHkrati.get() <= 2)
            }
            s.zamudaMs = 0
        }
        LazniStreznik(prava.streznik).use { s ->
            s.pozdrav = "502 Too many connections"
            UsenetNntp(nastavitve(s.vrata, povezav = 3), prava.zaupaj).use { k ->
                val t0 = System.nanoTime()
                preveriEnako("pozdrav prevec povezav -> ZASEDEN", "ZASEDEN", izid { k.body(ID) })
                preveriEnako("brez drugih povezav: bazen 1", 1, k.najvecPovezav)
                preveriEnako("med premorom brez nove povezave", "ZASEDEN", izid { k.body(ID) })
                preveriEnako("ena povezava skupaj (brez zanke)", 1, s.povezav.get())
                preveriEnako("brez cakanja na rok (premor 60 s > rok)", true, (System.nanoTime() - t0) / 1_000_000 < 2_000)
            }
        }

        // Zasebnost: sporocila izjem in vzrokov brez gesla, imena in message-id.
        val pusca = sporocila.filter { it.contains(GESLO) || it.contains(UPORABNIK) || it.contains("skrivni-clanek") || it.contains("lazni.invalid") }
        preveriEnako("izjem zbranih", true, sporocila.size > 10)
        preveriEnako("sporocila izjem brez gesla, imena in id", listOf<String>(), pusca)
    } finally {
        mapa.deleteRecursively()
    }
    if (napak == 0) println("UsenetNntpTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
