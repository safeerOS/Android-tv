package si.safeer.tv.os

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.Collections
import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

// Lasten pogon Usenet, korak 2 (26. 10. 2026): UsenetTok, UsenetRar, UsenetBralnik, UsenetStreznik - enako kot
// tests/test_usenet_tok.py v Safeer-linux. Vsebina je psevdonakljucna in ponovljiva, message-id izmisljeni (.invalid),
// lazni streznik NNTP prek TLS na 127.0.0.1 s potrdilom iz keytool. Pravi ponudniki in poverilnice se ne uporabljajo.

private var napak = 0
private var preverb = 0
private const val MIB = 1024L * 1024

private fun preveri(opis: String, pogoj: Boolean, podrobno: String = "") {
    preverb++
    if (pogoj) return
    napak++
    println("  NAPAKA  $opis $podrobno")
}

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri(opis, pricakovano == dobljeno, "(pricakovano $pricakovano, dobljeno $dobljeno)")

private fun izid(f: () -> Any?): String = try { f(); "ok" } catch (e: UsenetTok.NapakaToka) { e.izid.name.lowercase() }
    catch (e: UsenetRar.NapakaRar) { e.izid.name.lowercase() }

// ------------------------------------------------------------------ vsebina in objava

/** Vsebina z nakljucnim dostopom. */
private interface Vsebina { val velikost: Long; fun beri(o: Long, n: Int): ByteArray }

/** Ponovljiva psevdonakljucna vsebina brez drzanja v RAM (bloki po 64 KiB iz semena). */
private class Leno(override val velikost: Long, private val seme: Long) : Vsebina {
    private val blok = 65536
    override fun beri(o: Long, n: Int): ByteArray {
        val konec = minOf(velikost, o + n)
        if (o >= konec) return ByteArray(0)
        val izid = ByteArray((konec - o).toInt())
        var p = o
        while (p < konec) {
            val k = p / blok
            val r = SplittableRandom(seme * 1_000_003 + k)
            val b = ByteArray(minOf(blok.toLong(), velikost - k * blok).toInt())
            var i = 0
            while (i < b.size) { val x = r.nextLong(); var j = 0; while (j < 8 && i < b.size) { b[i++] = (x shr (8 * j)).toByte(); j++ } }
            val z = (p - k * blok).toInt()
            val m = minOf(b.size - z.toLong(), konec - p).toInt()
            System.arraycopy(b, z, izid, (p - o).toInt(), m)
            p += m
        }
        return izid
    }
}

private class Bajti(val b: ByteArray) : Vsebina {
    override val velikost: Long get() = b.size.toLong()
    override fun beri(o: Long, n: Int) = if (o >= b.size) ByteArray(0) else b.copyOfRange(o.toInt(), minOf(b.size.toLong(), o + n).toInt())
}

/** Zlepek vsebin (glava RAR + kos lenega filma + konec). */
private class Zlepek(val deli: List<Triple<Vsebina, Long, Long>>) : Vsebina {
    override val velikost: Long = deli.sumOf { it.third }
    override fun beri(o: Long, n: Int): ByteArray {
        val izid = ByteArrayOutputStream()
        var z = 0L
        var oo = o
        var nn = n.toLong()
        for ((v, od, d) in deli) {
            if (nn <= 0) break
            if (oo < z + d) {
                val k = maxOf(0L, oo - z)
                val kos = v.beri(od + k, minOf(d - k, nn).toInt())
                izid.write(kos); nn -= kos.size; oo += kos.size
            }
            z += d
        }
        return izid.toByteArray()
    }
}

/** Datoteke, razrezane v clanke yEnc (enaki deli, zadnji krajsi); steje poslane bajte. */
private class Objava(val delBajtov: Int = 700 * 1024, private val predpomni: Boolean = false) {
    private val kodirani = ConcurrentHashMap<String, ByteArray>()
    val datoteke = ArrayList<Triple<String, Vsebina, List<String>>>()
    private val clanki = HashMap<String, Pair<Int, Int>>()
    val poslano = AtomicLong()
    val poslanih = AtomicLong()
    val manjka: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    /** Clanki, ki pridejo pokvarjeni (ni yEnc), in koliko krat je bil vsak clanek poslan (T3). */
    val pokvarjeni: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    val poClanku = ConcurrentHashMap<String, Int>()

    fun dodaj(ime: String, v: Vsebina): List<String> {
        val n = maxOf(1L, (v.velikost + delBajtov - 1) / delBajtov).toInt()
        val idji = (1..n).map { "o${datoteke.size}.s$it.xxxxxxxx@primer.invalid" }
        idji.forEachIndexed { s, id -> clanki[id] = datoteke.size to s + 1 }
        datoteke += Triple(ime, v, idji)
        return idji
    }

    fun ima(id: String) = clanki.containsKey(id) && id !in manjka

    /** Telo clanka (CRLF, brez podvojenih pik), kot ga vrne UsenetNntp.body. */
    fun telo(id: String): ByteArray {
        if (!ima(id)) throw UsenetNntp.NapakaNntp(UsenetNntp.Vrsta.NI_CLANKA, 430)
        poClanku.merge(id, 1, Int::plus)
        if (id in pokvarjeni) return "nesmisel\r\n".toByteArray()
        if (predpomni) kodirani[id]?.let { poslano.addAndGet(it.size.toLong()); poslanih.incrementAndGet(); return it }
        val (d, st) = clanki.getValue(id)
        val (ime, v, idji) = datoteke[d]
        val od = (st - 1).toLong() * delBajtov
        val kos = v.beri(od, delBajtov)
        val t = YencKodirnik.kodiraj(kos, ime, st, idji.size, od, v.velikost)
        poslano.addAndGet(t.size.toLong()); poslanih.incrementAndGet()
        if (predpomni) kodirani[id] = t
        return t
    }

    fun nzb(izpusti: Set<String> = emptySet()): ByteArray {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">")
        for ((ime, _, idji) in datoteke) {
            sb.append("<file poster=\"p@primer.invalid\" date=\"1\" subject=\"[1/1] - &quot;$ime&quot; yEnc (1/${idji.size})\">")
            sb.append("<groups><group>alt.binaries.test</group></groups><segments>")
            idji.forEachIndexed { s, id -> if (id !in izpusti) sb.append("<segment bytes=\"${delBajtov + 30000}\" number=\"${s + 1}\">$id</segment>") }
            sb.append("</segments></file>")
        }
        return sb.append("</nzb>").toString().toByteArray(Charsets.UTF_8)
    }
}

// ------------------------------------------------------------------ RAR po specifikaciji (samo za preizkuse)

private object Rar {
    fun vint(n0: Long): ByteArray {
        val o = ByteArrayOutputStream()
        var n = n0
        while (true) { val b = (n and 0x7F).toInt(); n = n ushr 7; if (n != 0L) o.write(b or 0x80) else { o.write(b); return o.toByteArray() } }
    }
    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    fun blok5(vrsta: Int, zast: Int, polja: ByteArray, dodatno: ByteArray = ByteArray(0), podatkov: Long? = null): ByteArray {
        val z = zast or (if (dodatno.isNotEmpty()) 1 else 0) or (if (podatkov != null) 2 else 0)
        val telo = vint(vrsta.toLong()) + vint(z.toLong()) + (if (dodatno.isNotEmpty()) vint(dodatno.size.toLong()) else ByteArray(0)) +
            (if (podatkov != null) vint(podatkov) else ByteArray(0)) + polja + dodatno
        val vel = vint(telo.size.toLong())
        return le32(crc(vel + telo)) + vel + telo
    }

    fun glava5(ime: String, velikost: Long, kos: ByteArray, metoda: Int = 0, pred: Boolean = false, po: Boolean = false, sifriran: Boolean = false): ByteArray {
        val i = ime.toByteArray(Charsets.UTF_8)
        val polja = vint(0x4) + vint(velikost) + vint(0x20) + le32(crc(kos)) + vint((metoda shl 7).toLong()) + vint(0) + vint(i.size.toLong()) + i
        val dod = if (sifriran) (vint(1) + vint(0) + vint(0) + ByteArray(32)).let { vint(it.size.toLong()) + it } else ByteArray(0)
        return blok5(2, (if (pred) 0x8 else 0) or (if (po) 0x10 else 0), polja, dod, kos.size.toLong())
    }

    val PODPIS5 = UsenetRar.PODPIS5
    val PODPIS4 = UsenetRar.PODPIS4

    fun glavni5(zvezek: Int, vec: Boolean) =
        PODPIS5 + blok5(1, 0, vint(((if (vec) 1 else 0) or (if (zvezek > 0) 2 else 0)).toLong()) + (if (zvezek > 0) vint(zvezek.toLong()) else ByteArray(0)))

    fun konec5(zadnji: Boolean) = blok5(5, 0, vint(if (zadnji) 0 else 1))

    fun blok4(vrsta: Int, zast: Int, telo: ByteArray): ByteArray {
        val g = byteArrayOf(vrsta.toByte()) + le16(zast) + le16(7 + telo.size) + telo
        return le16((crc(g) and 0xFFFF).toInt()) + g
    }

    fun glava4(ime: String, velikost: Long, kos: ByteArray, metoda: Int = 0x30, pred: Boolean = false, po: Boolean = false, sifriran: Boolean = false): ByteArray {
        val i = ime.toByteArray(Charsets.UTF_8)
        val zast = 0x8000 or (if (pred) 1 else 0) or (if (po) 2 else 0) or (if (sifriran) 4 else 0)
        val telo = le32(kos.size.toLong()) + le32(velikost) + byteArrayOf(2) + le32(crc(kos)) + le32(0) + byteArrayOf(29, metoda.toByte()) +
            le16(i.size) + le32(0x20) + i
        return blok4(0x74, zast, telo)
    }

    fun glavni4(vec: Boolean, prvi: Boolean) =
        PODPIS4 + blok4(0x73, (if (vec) 0x11 else 0) or (if (vec && prvi) 0x100 else 0), ByteArray(6))

    val konec4 get() = blok4(0x7B, 0x4000, ByteArray(0))

    /** RAR4 glava z LHD_LARGE (0x100): HIGH_PACK z zgornjim bitom (W1). */
    fun glava4Velika(ime: String, kos: ByteArray, highPack: Long): ByteArray {
        val i = ime.toByteArray(Charsets.UTF_8)
        val telo = le32(kos.size.toLong()) + le32(kos.size.toLong()) + byteArrayOf(2) + le32(crc(kos)) + le32(0) + byteArrayOf(29, 0x30) +
            le16(i.size) + le32(0x20) + le32(highPack) + le32(0) + i
        return blok4(0x74, 0x8000 or 0x100, telo)
    }

    /** Vec zvezkov store nad leno vsebino (glave prave, podatki se ne kopirajo). */
    fun zvezki(razlicica: Int, ime: String, film: Vsebina, naZvezek: Long): List<Vsebina> {
        val n = ((film.velikost + naZvezek - 1) / naZvezek).toInt()
        return (0 until n).map { i ->
            val od = i * naZvezek
            val d = minOf(naZvezek, film.velikost - od)
            val kos = film.beri(od, d.toInt())
            val glava: ByteArray
            val rep: ByteArray
            if (razlicica == 5) {
                glava = glavni5(i, true) + glava5(ime, film.velikost, kos, pred = i > 0, po = i < n - 1)
                rep = konec5(i == n - 1)
            } else {
                glava = glavni4(true, i == 0) + glava4(ime, film.velikost, kos, pred = i > 0, po = i < n - 1)
                rep = konec4
            }
            Zlepek(listOf(Triple(Bajti(glava), 0L, glava.size.toLong()), Triple(film, od, d), Triple(Bajti(rep), 0L, rep.size.toLong())))
        }
    }
}

// ------------------------------------------------------------------ lazni streznik NNTP prek TLS

private class Tls(val streznik: SSLContext, val zaupaj: SSLSocketFactory)

private fun tls(mapa: File): Tls {
    val shramba = File(mapa, "tok-${System.nanoTime()}.p12")
    val geslo = "preizkus"
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val p = ProcessBuilder(listOf(keytool, "-genkeypair", "-alias", "a", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
        "-keystore", shramba.path, "-storepass", geslo, "-keypass", geslo)).redirectErrorStream(true).start()
    val izpis = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "keytool: $izpis" }
    val ks = KeyStore.getInstance("PKCS12").apply { shramba.inputStream().use { load(it, geslo.toCharArray()) } }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, geslo.toCharArray()) }
    val s = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    val zaupane = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("s", ks.getCertificate("a")) }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(zaupane) }
    return Tls(s, SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory)
}

private const val UPORABNIK = "preizkus-tok-uporabnik"
private const val GESLO = "preizkus-tok-geslo-9"

/** Streznik NNTP z clanki iz [objava] (pike podvoji streznik). */
private class LazenNntp(ctx: SSLContext, val objava: Objava) : Closeable {
    private val posluh = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val vrata: Int get() = posluh.localPort
    private val vticnice = Collections.synchronizedList(ArrayList<Socket>())
    @Volatile private var tece = true

    init { Thread { sprejemaj() }.apply { isDaemon = true; start() } }

    private fun sprejemaj() {
        while (tece) {
            val s = try { posluh.accept() } catch (_: IOException) { return }
            vticnice += s
            Thread { strezi(s) }.apply { isDaemon = true; start() }
        }
    }

    private fun strezi(s: Socket) {
        try {
            s.soTimeout = 30_000
            val vhod = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val izhod = s.getOutputStream()
            fun posli(v: String) { izhod.write((v + "\r\n").toByteArray(Charsets.ISO_8859_1)); izhod.flush() }
            posli("200 lazni")
            var ime: String? = null
            var prijavljen = false
            while (tece) {
                val v = vhod.readLine() ?: return
                val id = v.substringAfter('<', "").substringBefore('>')
                when {
                    v.startsWith("AUTHINFO USER ") -> { ime = v.removePrefix("AUTHINFO USER "); posli("381 Geslo") }
                    v.startsWith("AUTHINFO PASS ") -> {
                        prijavljen = ime == UPORABNIK && v.removePrefix("AUTHINFO PASS ") == GESLO
                        posli(if (prijavljen) "281 ok" else "481 Authentication failed")
                    }
                    v.startsWith("QUIT") -> { posli("205 adijo"); return }
                    !prijavljen -> posli("480 Authentication required")
                    v.startsWith("BODY") -> {
                        if (!objava.ima(id)) posli("430 No article") else {
                            val t = YencKodirnik.podvojiPike(objava.telo(id))
                            izhod.write(("222 0 <$id>\r\n").toByteArray(Charsets.ISO_8859_1))
                            izhod.write(t); izhod.write(".\r\n".toByteArray()); izhod.flush()
                        }
                    }
                    v.startsWith("STAT") -> posli(if (objava.ima(id)) "223 0 <$id>" else "430 No article")
                    else -> posli("500 ?")
                }
            }
        } catch (_: IOException) {
        } finally { try { s.close() } catch (_: IOException) { } }
    }

    override fun close() {
        tece = false
        try { posluh.close() } catch (_: IOException) { }
        synchronized(vticnice) { for (s in vticnice) try { s.close() } catch (_: IOException) { } }
    }
}

// ------------------------------------------------------------------ pomoc

private fun bralnik(o: Objava, profil: UsenetBralnik.Profil = UsenetBralnik.PROFILI.getValue("namizje"), izpusti: Set<String> = emptySet()) =
    UsenetBralnik.Bralnik(o::telo, UsenetBralnik.pripravi(UsenetNzb.razcleni(o.nzb(izpusti)), o::telo).first, profil)

private fun pocakajMir(b: UsenetBralnik.Bralnik) {
    val konec = System.nanoTime() + 30_000_000_000L
    while (!b.miruje() && System.nanoTime() < konec) Thread.sleep(20)
}

/** Model predvajalnika (mpv --cache-secs=20): odprtje (glava 1 MiB, indeks 64 KiB pri -2 MiB), nato zaporedno. */
private fun ogled(beri: (Long, Int) -> ByteArray, velikost: Long, bitovNaS: Double, sekund: Double = 60.0, vnaprej: Double = 20.0, od: Long = 0): Long {
    var prebrano = 0L
    prebrano += beri(0, MIB.toInt()).size
    prebrano += beri(velikost - 2 * MIB, 65536).size
    val cilj = minOf(velikost - od, ((bitovNaS / 8) * (sekund + vnaprej)).toLong())
    var poz = od
    while (poz - od < cilj) {
        val b = beri(poz, minOf(256 * 1024L, cilj - (poz - od)).toInt())
        if (b.isEmpty()) break
        poz += b.size; prebrano += b.size
    }
    return prebrano
}

private fun http(url: String, od: Long, doo: Long?): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("Range", "bytes=$od-" + (doo?.toString() ?: ""))
        connectTimeout = 10_000; readTimeout = 30_000
    }

// ------------------------------------------------------------------ preizkusi

private fun preslikava() {
    println("preslikava odmikov")
    val vel = listOf(300L, 300, 120, 300, 300, 300, 80)
    val film = Leno(vel.sum(), 3)
    fun del(st: Int): UsenetYenc.Del {
        val od = vel.take(st - 1).sum()
        return UsenetYenc.dekodiraj(YencKodirnik.kodiraj(film.beri(od, vel[st - 1].toInt()), "film.mkv", st, vel.size, od, film.velikost))
    }
    val dat = UsenetNzb.Datoteka("film.mkv", 0, emptyList(), (1..vel.size).map { UsenetNzb.Segment(it, 1000, "d$it@primer.invalid") }, 0)
    val rng = SplittableRandom(25)
    var vsaj = 0
    repeat(40) {
        val odmik = rng.nextLong(film.velikost)
        val p = UsenetTok.Preslikava(dat, del(1))
        var branj = 0
        var u = p.poisci(odmik)
        while (!u.natancno && branj < UsenetTok.NAJVEC_ISKANJ) { p.vnesi(u.stevilka, del(u.stevilka)); branj++; u = p.poisci(odmik) }
        var z = 0L; var st = 1
        while (odmik >= z + vel[st - 1]) { z += vel[st - 1]; st++ }
        if (u.stevilka == st && u.odmik == odmik - z) vsaj++
    }
    enako("dve zdruzeni objavi: vsi odmiki najdeni", 40, vsaj)
    val luknja = UsenetNzb.Datoteka("film.mkv", 0, emptyList(), listOf(1, 2, 4, 5, 6, 7).map { UsenetNzb.Segment(it, 1000, "d$it@primer.invalid") }, 1)
    val p = UsenetTok.Preslikava(luknja, del(1))
    enako("luknja v NZB", listOf(3), p.manjkajoci())
    enako("stevilo manjkajocih", 1, p.manjka)
    enako("branje v luknji", "luknja", izid { p.poisci(650) })
    enako("toString brez id", false, p.toString().contains("primer.invalid"))
}

private fun rar() {
    println("RAR store")
    val film = Leno(70_001, 4)
    for (r in listOf(4, 5)) {
        val vols = Rar.zvezki(r, "Film.2026.mkv", film, 25_000)
        val zv = vols.map { UsenetRar.razcleniZvezek(it.beri(0, it.velikost.toInt()), it.velikost) }
        val (ime, velikost, kosi) = UsenetRar.sestavi(zv)
        val sestavljeno = ByteArrayOutputStream().apply { for (k in kosi) write(vols[k.zvezek].beri(k.odmik, k.dolzina.toInt())) }.toByteArray()
        enako("RAR$r ime in velikost", "Film.2026.mkv" to film.velikost, ime to velikost)
        preveri("RAR$r bajti enaki izvirniku", sestavljeno.contentEquals(film.beri(0, film.velikost.toInt())))
        enako("RAR$r odlocitev iz prvih 300 B", UsenetRar.Izid.OK, UsenetRar.preveri(vols[0].beri(0, 300), vols[0].velikost))
        enako("RAR$r prvih 30 B = nepopolno", UsenetRar.Izid.NEPOPOLNO, UsenetRar.preveri(vols[0].beri(0, 30), vols[0].velikost))
        enako("RAR$r manjka srednji", "neskladno", izid { UsenetRar.sestavi(listOf(zv[0], zv[2])) })
    }
    val st = Rar.glavni5(0, false) + Rar.glava5("Film.mkv", 100_000, ByteArray(500), metoda = 3) + ByteArray(500) + Rar.konec5(true)
    enako("RAR5 stisnjen", UsenetRar.Izid.STISNJEN, UsenetRar.preveri(st, st.size.toLong()))
    val sif = Rar.glavni4(false, true) + Rar.glava4("Film.mkv", 500, ByteArray(500), sifriran = true) + ByteArray(500) + Rar.konec4
    enako("RAR4 sifriran", UsenetRar.Izid.SIFRIRAN, UsenetRar.preveri(sif, sif.size.toLong()))
    enako("mkv ni RAR", UsenetRar.Izid.NI_RAR, UsenetRar.preveri(byteArrayOf(0x1A, 0x45, -33, -93) + ByteArray(60)))
    enako("vrstni red .r00", listOf(1, 2, 0), UsenetRar.vrstniRedZvezkov(listOf("A.r01", "a.rar", "a.r00")))
}

private fun bralnikPreizkusi() {
    println("bralnik")
    enako("okno po skoku", MIB, UsenetBralnik.oknoVnaprej(0, MIB, 16 * MIB))
    enako("okno raste", 6 * MIB, UsenetBralnik.oknoVnaprej(3 * MIB, MIB, 16 * MIB))
    enako("okno meja", 16 * MIB, UsenetBralnik.oknoVnaprej(100 * MIB, MIB, 16 * MIB))

    val film = Leno(9 * MIB + 12345, 3)
    val o = Objava().apply { dodaj("Film.2026.mkv", film); dodaj("Film.2026.nfo", Bajti("nfo".toByteArray())); dodaj("Film.2026.sample.mkv", Leno(MIB, 9)) }
    bralnik(o).use { b ->
        enako("izbran film (ne vzorec)", film.velikost, b.vir.velikost)
        preveri("zaporedno enako izvirniku", UsenetBralnik.beriVse(b, 0, film.velikost.toInt()).contentEquals(film.beri(0, film.velikost.toInt())))
        val rng = SplittableRandom(5)
        var dobri = 0
        repeat(30) {
            val od = rng.nextLong(film.velikost)
            val n = rng.nextInt(1, 3 * MIB.toInt())
            if (UsenetBralnik.beriVse(b, od, n).contentEquals(film.beri(od, n))) dobri++
        }
        enako("nakljucni skoki enaki izvirniku", 30, dobri)
        enako("konec datoteke", 0, b.beri(film.velikost, 10).size)
        enako("toString brez id", false, (b.toString() + b.vir.toString()).contains("primer.invalid"))
    }
    // RAR store cez zvezke
    val f2 = Leno(7 * MIB + 77, 4)
    for (r in listOf(4, 5)) {
        val o2 = Objava()
        Rar.zvezki(r, "Film.mkv", f2, 2 * MIB + 1).forEachIndexed { i, z -> o2.dodaj("Film.part${i + 1}.rar", z) }
        bralnik(o2).use { b ->
            enako("RAR$r vir", Triple(f2.velikost, "Film.mkv", 4), Triple(b.vir.velikost, b.vir.ime, b.vir.kosi.size))
            var dobri = 0
            for (od in listOf(0L, 2 * MIB - 100, 2 * MIB + 1, 4 * MIB + 1 - 3, f2.velikost - 10)) {
                if (UsenetBralnik.beriVse(b, od, 300_000).contentEquals(f2.beri(od, 300_000))) dobri++
            }
            enako("RAR$r branja cez meje zvezkov", 5, dobri)
        }
    }
    // stisnjen RAR zavrnjen iz enega clanka
    val o3 = Objava().apply {
        dodaj("Film.part1.rar", Bajti(Rar.glavni5(0, true) + Rar.glava5("Film.mkv", 1_000_000_000, ByteArray(900_000), metoda = 3, po = true) + ByteArray(900_000) + Rar.konec5(false)))
        dodaj("Film.part2.rar", Bajti(ByteArray(900_000)))
    }
    val nzb3 = UsenetNzb.razcleni(o3.nzb())
    enako("preveriIzdajo stisnjen", "stisnjen", UsenetBralnik.preveriIzdajo(nzb3, o3::telo))
    enako("odlocitev iz enega clanka", 1L, o3.poslanih.get())
    enako("pripravi zavrne stisnjen", "stisnjen", izid { UsenetBralnik.pripravi(nzb3, o3::telo) })
    // okno po skoku in rast
    val o4 = Objava().apply { dodaj("Film.mkv", Leno(64 * MIB, 8)) }
    bralnik(o4).use { b ->
        var z = o4.poslano.get()
        b.beri(40 * MIB, 65536)
        pocakajMir(b)
        preveri("skok: samo segment + zacetno okno", o4.poslano.get() - z <= 4 * 760 * 1024, "${o4.poslano.get() - z}")
        z = o4.poslano.get()
        UsenetBralnik.beriVse(b, 0, 8 * MIB.toInt())
        pocakajMir(b)
        preveri("zaporedno 8 MiB: okno najvec 16 MiB", o4.poslano.get() - z <= ((8 + 16 + 2) * MIB * 1.03).toLong(), "${o4.poslano.get() - z}")
    }
    // skok zavrze cakajoca branja vnaprej
    val o5 = Objava().apply { dodaj("Film.mkv", Leno(64 * MIB, 2)) }
    val vir5 = UsenetBralnik.pripravi(UsenetNzb.razcleni(o5.nzb()), o5::telo).first
    UsenetBralnik.Bralnik({ id -> Thread.sleep(50); o5.telo(id) }, vir5, UsenetBralnik.Profil(16 * MIB, MIB, 64 * MIB, 2)).use { b ->
        UsenetBralnik.beriVse(b, 0, 8 * MIB.toInt())
        val prej = o5.poslanih.get()
        b.beri(50 * MIB, 1000)
        pocakajMir(b)
        preveri("skok zavrze cakajoca branja", o5.poslanih.get() - prej < 10, "${o5.poslanih.get() - prej}")
    }
    // luknje: v NZB in 430 pri ponudniku
    val f6 = Leno(5 * 700 * 1024L, 6)
    val o6 = Objava().apply { dodaj("Film.mkv", f6) }
    bralnik(o6, izpusti = setOf(o6.datoteke[0].third[2])).use { b ->
        enako("luknja v NZB", "luknja", izid { b.beri(2 * 700 * 1024L + 5, 10) })
        o6.manjka += o6.datoteke[0].third[3]
        enako("430 pri ponudniku = luknja", "luknja", izid { b.beri(3 * 700 * 1024L + 5, 10) })
        preveri("za luknjo se bere", UsenetBralnik.beriVse(b, 4 * 700 * 1024L, 100).contentEquals(f6.beri(4 * 700 * 1024L, 100)))
    }
    // close ne caka na zahtevo v teku
    val o7 = Objava().apply { dodaj("Film.mkv", Leno(4 * MIB, 1)) }
    val vir7 = UsenetBralnik.pripravi(UsenetNzb.razcleni(o7.nzb()), o7::telo).first
    val sprosti = java.util.concurrent.CountDownLatch(1)
    val b7 = UsenetBralnik.Bralnik({ id -> sprosti.await(); o7.telo(id) }, vir7)
    val nit = Thread { izid { b7.beri(2 * MIB, 10) } }.apply { start() }
    Thread.sleep(100)
    val t0 = System.nanoTime()
    b7.close()
    preveri("close ne caka", System.nanoTime() - t0 < 500_000_000L)
    enako("po close zaprto", "zaprto", izid { b7.beri(0, 10) })
    sprosti.countDown()
    nit.join(5000)
    preveri("nit koncala", !nit.isAlive)
    // LRU meja
    val o8 = Objava().apply { dodaj("Film.mkv", Leno(40 * MIB, 5)) }
    bralnik(o8, UsenetBralnik.Profil(2 * MIB, MIB, 6 * MIB, 2)).use { b ->
        UsenetBralnik.beriVse(b, 0, 40 * MIB.toInt())
        pocakajMir(b)
        preveri("LRU v meji", b.lruBajtov <= b.lruMeja && b.lruMeja < 40 * MIB, "${b.lruBajtov}/${b.lruMeja}")
    }
    // glavna nit
    android.os.Looper.glavnaNit = Thread.currentThread().id
    val zavrnjeno = try { UsenetBralnik.pripravi(UsenetNzb.razcleni(o8.nzb()), o8::telo); false } catch (_: IllegalStateException) { true }
    android.os.Looper.glavnaNit = -1
    preveri("pripravi zavrne glavno nit", zavrnjeno)
    // promet za 60 s ogleda
    for (ime in listOf("namizje", "tv")) {
        val o9 = Objava().apply { dodaj("Film.mkv", Leno(200 * MIB, 12)) }
        bralnik(o9, UsenetBralnik.PROFILI.getValue(ime)).use { b ->
            val pogl = ogled(b::beri, b.vir.velikost, 5.2e6)
            pocakajMir(b)
            val x = o9.poslano.get().toDouble() / pogl
            println("  promet $ime: ${"%.2f".format(x)}x pogledanega")
            preveri("promet $ime <= 1,5x", x <= 1.5, "%.2f".format(x))
        }
    }
}

private fun streznik() {
    println("streznik HTTP Range")
    enako("obseg 0-99", 0L to 99L, UsenetStreznik.obseg("bytes=0-99", 1000))
    enako("obseg od 500", 500L to 999L, UsenetStreznik.obseg("bytes=500-", 1000))
    enako("obseg zadnjih 100", 900L to 999L, UsenetStreznik.obseg("bytes=-100", 1000))
    enako("obseg cez konec", 990L to 999L, UsenetStreznik.obseg("bytes=990-5000", 1000))
    enako("416", -1L to -1L, UsenetStreznik.obseg("bytes=1000-", 1000))
    for (prezri in listOf("bytes=0-1,5-6", "items=0-1", "bytes=5-1", "bytes=-", "bytes=a-b")) enako("prezri $prezri", null, UsenetStreznik.obseg(prezri, 1000))
    enako("ime poti", "video.mkv", UsenetStreznik.imePoti("Film.Tajno.1080p.mkv"))
    enako("ime poti brez videa", "video.bin", UsenetStreznik.imePoti("kljuc=abc.exe"))

    val film = Leno(3 * MIB + 17, 21)
    val o = Objava().apply { dodaj("Film.Tajno.Ime.mkv", film) }
    val b = bralnik(o)
    UsenetStreznik(b).use { s ->
        preveri("naslov 127.0.0.1", s.url.startsWith("http://127.0.0.1:${s.vrata}/"))
        preveri("naslov brez skrivnosti", listOf("primer.invalid", "Tajno", "nzb").none { it in s.url } && s.zeton.length >= 40)
        preveri("toString brez zetona", s.zeton !in s.toString())
        val c = http(s.url, 100, 199)
        enako("206", 206, c.responseCode)
        enako("Content-Range", "bytes 100-199/${film.velikost}", c.getHeaderField("Content-Range"))
        preveri("bajti 100-199", c.inputStream.readBytes().contentEquals(film.beri(100, 100)))
        val h = (URL(s.url).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD" }
        enako("HEAD 200 in dolzina", 200 to film.velikost.toString(), h.responseCode to h.getHeaderField("Content-Length"))
        enako("416", 416, http(s.url, film.velikost, null).responseCode)
        enako("napacen zeton 404", 404, (URL(s.url.replace(s.zeton, "A".repeat(s.zeton.length))).openConnection() as HttpURLConnection).responseCode)
        val post = (URL(s.url).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; outputStream.write(1) }
        enako("POST 405", 405, post.responseCode)
        val sock = Socket("127.0.0.1", s.vrata)
        sock.getOutputStream().write("GET /${s.zeton}/${s.pot} HTTP/1.1\r\nHost: zlo.example:${s.vrata}\r\n\r\n".toByteArray())
        enako("tuj Host 404", true, sock.getInputStream().bufferedReader().readLine().contains("404"))
        sock.close()
    }
    preveri("close zapre bralnik", b.zaprt)
    val konec = java.util.concurrent.CountDownLatch(1)
    val b2 = bralnik(o)
    UsenetStreznik(b2, nedejavnostMs = 300, obKoncu = { konec.countDown() }).use { s ->
        preveri("samodejno zapiranje ob nedejavnosti", konec.await(5, java.util.concurrent.TimeUnit.SECONDS) && s.zaprt && b2.zaprt)
    }
}

/** NZB -> UsenetNntp (TLS) -> bralnik -> (Media3 Branje ali streznik) -> bajti kot izvirnik, skok 50 % in 90 %. */
private fun konecDoKonca(mapa: File, t: Tls) {
    println("konec do konca prek NNTP/TLS")
    val film = Leno(12 * MIB + 3, 32)
    for (rar in listOf(false, true)) {
        val o = Objava()
        if (rar) Rar.zvezki(5, "Film.mkv", film, 5 * MIB).forEachIndexed { i, z -> o.dodaj("Film.part${i + 1}.rar", z) } else o.dodaj("Film.mkv", film)
        LazenNntp(t.streznik, o).use { srv ->
            val n = UsenetNntp.Nastavitve("localhost", UPORABNIK, GESLO, vrata = srv.vrata, povezav = 4, rokPovezaveMs = 3_000,
                rokBranjaMs = 10_000, rokCakanjaMs = 10_000)
            UsenetNntp(n, t.zaupaj).use { k ->
                val nzb = UsenetNzb.razcleni(o.nzb())
                enako("preveriIzdajo (rar=$rar)", "ok", UsenetBralnik.preveriIzdajo(nzb, k::body))
                val vir = UsenetBralnik.pripravi(nzb, k::body).first
                val b = UsenetBralnik.Bralnik(k::body, vir)
                val branje = UsenetBralnik.Branje(b)
                UsenetStreznik(b).use { s ->
                    for (delez in listOf(0.0, 0.5, 0.9)) {
                        val od = (film.velikost * delez).toLong()
                        val c = http(s.url, od, od + 1_500_000)
                        preveri("HTTP $delez (rar=$rar)", c.responseCode == 206 && c.inputStream.readBytes().contentEquals(film.beri(od, 1_500_001)))
                        val buf = ByteArray(minOf(1_500_001L, film.velikost - od).toInt())
                        enako("Branje.open dolzina $delez", buf.size.toLong(), branje.open(od, 1_500_001))
                        var p = 0
                        while (p < buf.size) { val r = branje.read(buf, p, buf.size - p); if (r < 0) break; p += r }
                        preveri("Branje (Media3) $delez (rar=$rar)", p == buf.size && buf.contentEquals(film.beri(od, 1_500_001)))
                        enako("Branje konec $delez", -1, branje.read(ByteArray(10), 0, 10))
                    }
                    preveri("geslo ni v naslovu", GESLO !in s.url)
                }
                preveri("bralnik zaprt", b.zaprt)
            }
        }
    }
    mapa.hashCode()
}

/**
 * Odlocitev za Android: Media3 DataSource neposredno ([UsenetBralnik.Branje]) ali lokalni streznik HTTP (kot ExoPlayer
 * DefaultHttpDataSource prek HttpURLConnection). Ista pot NNTP/TLS, isti film; meri pretok (MB/s), CPU procesa, cas do
 * prvega bajta po skoku in promet z Useneta za 60 s ogleda. 3 ponovitve izmenicno.
 */
private fun meritev(t: Tls) {
    println("meritev: Media3 neposredno proti streznik HTTP (3 ponovitve izmenicno)")
    val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    for (ponovitev in 1..3) {
        for (nacin in listOf("neposredno", "http")) {
            val film = Leno(300 * MIB, 40L + ponovitev)
            val o = Objava().apply { dodaj("Film.mkv", film) }
            LazenNntp(t.streznik, o).use { srv ->
                val n = UsenetNntp.Nastavitve("localhost", UPORABNIK, GESLO, vrata = srv.vrata, povezav = 8, rokPovezaveMs = 3_000,
                    rokBranjaMs = 10_000, rokCakanjaMs = 10_000)
                UsenetNntp(n, t.zaupaj).use { k ->
                    val vir = UsenetBralnik.pripravi(UsenetNzb.razcleni(o.nzb()), k::body).first
                    val b = UsenetBralnik.Bralnik(k::body, vir, UsenetBralnik.PROFILI.getValue("tv"))
                    val s = if (nacin == "http") UsenetStreznik(b) else null
                    val cpu0 = os.processCpuTime
                    val t0 = System.nanoTime()
                    // pretok: 64 MiB zaporedno
                    var prebrano = 0L
                    if (s == null) {
                        val br = UsenetBralnik.Branje(b); br.open(0, 64 * MIB); val buf = ByteArray(65536)
                        while (true) { val r = br.read(buf, 0, buf.size); if (r < 0) break; prebrano += r }
                    } else {
                        http(s.url, 0, 64 * MIB - 1).inputStream.use { v -> val buf = ByteArray(65536); while (true) { val r = v.read(buf); if (r < 0) break; prebrano += r } }
                    }
                    val sek = (System.nanoTime() - t0) / 1e9
                    val cpu = (os.processCpuTime - cpu0) / 1e9
                    // prvi bajt po skoku na 70 %
                    val od = (film.velikost * 0.7).toLong()
                    val t1 = System.nanoTime()
                    if (s == null) { val br = UsenetBralnik.Branje(b); br.open(od, -1); br.read(ByteArray(1), 0, 1) } else {
                        http(s.url, od, null).inputStream.use { it.read() }
                    }
                    val skok = (System.nanoTime() - t1) / 1e6
                    pocakajMir(b)
                    // promet za 60 s ogleda pri 5,2 Mbit/s (TV) od 20 %
                    val z0 = o.poslano.get()
                    val beri: (Long, Int) -> ByteArray = if (s == null) { o2, n2 -> UsenetBralnik.beriVse(b, o2, n2) } else { o2, n2 ->
                        http(s.url, o2, o2 + n2 - 1).inputStream.use { it.readBytes() } }
                    val pogl = ogled(beri, vir.velikost, 5.2e6, od = (film.velikost * 0.2).toLong())
                    pocakajMir(b)
                    val x = (o.poslano.get() - z0).toDouble() / pogl
                    println("  $ponovitev $nacin: pretok ${"%.1f".format(prebrano / MIB / sek)} MiB/s, CPU ${"%.2f".format(cpu)} s za 64 MiB, " +
                        "prvi bajt po skoku ${"%.0f".format(skok)} ms, promet ogleda ${"%.2f".format(x)}x")
                    s?.close() ?: b.close()
                }
            }
        }
    }
}

/** Samo pot do predvajalnika: clanki ze kodirani v RAM (brez NNTP), bralnik in dekodiranje enaka v obeh nacinih. */
private fun meritevPrenosa() {
    println("meritev prenosa: Branje (Media3) proti HTTP, clanki v RAM (3 ponovitve izmenicno)")
    val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    val film = Leno(96 * MIB, 77)
    val o = Objava(predpomni = true).apply { dodaj("Film.mkv", film) }
    val nzb = UsenetNzb.razcleni(o.nzb())
    UsenetBralnik.Bralnik(o::telo, UsenetBralnik.pripravi(nzb, o::telo).first).use { b -> UsenetBralnik.beriVse(b, 0, 96 * MIB.toInt()) }
    for (ponovitev in 1..3) {
        for (nacin in listOf("neposredno", "http")) {
            val vir = UsenetBralnik.pripravi(nzb, o::telo).first
            val b = UsenetBralnik.Bralnik(o::telo, vir, UsenetBralnik.PROFILI.getValue("tv"))
            val s = if (nacin == "http") UsenetStreznik(b) else null
            System.gc()
            val cpu0 = os.processCpuTime
            val t0 = System.nanoTime()
            var prebrano = 0L
            val buf = ByteArray(65536)
            if (s == null) {
                val br = UsenetBralnik.Branje(b); br.open(0, 96 * MIB)
                while (true) { val r = br.read(buf, 0, buf.size); if (r < 0) break; prebrano += r }
            } else {
                http(s.url, 0, 96 * MIB - 1).inputStream.use { v -> while (true) { val r = v.read(buf); if (r < 0) break; prebrano += r } }
            }
            val sek = (System.nanoTime() - t0) / 1e9
            val cpu = (os.processCpuTime - cpu0) / 1e9
            println("  $ponovitev $nacin: ${"%.0f".format(prebrano / MIB / sek)} MiB/s, CPU ${"%.2f".format(cpu)} s za 96 MiB")
            s?.close() ?: b.close()
        }
    }
}

// ------------------------------------------------------------------ popravki pregledov Grok 58 in 61 (naloga 29)

private fun nzbIz(o: Objava, izpusti: Set<String> = emptySet()) = UsenetNzb.razcleni(o.nzb(izpusti))

private fun popravki58() {
    println("popravki pregledov Grok 58 in 61")
    // T1: yEnc total=999 999 999 iz enega clanka - prej seznam vseh manjkajocih stevilk (OOM na TV)
    val film = Leno(1000, 1).beri(0, 1000)
    val velik = UsenetYenc.dekodiraj(YencKodirnik.kodiraj(film, "x.mkv", 1, 999_999_999, 0, 100_000_000_000L))
    val dat = UsenetNzb.Datoteka("x.mkv", 0, emptyList(), listOf(UsenetNzb.Segment(1, 1000, "a@primer.invalid")), 0)
    val rt = Runtime.getRuntime()
    System.gc()
    val pred = rt.totalMemory() - rt.freeMemory()
    var t0 = System.nanoTime()
    enako("T1 velik total = neskladno", "neskladno", izid { UsenetTok.Preslikava(dat, velik) })
    val ms = (System.nanoTime() - t0) / 1e6
    val mib = (rt.totalMemory() - rt.freeMemory() - pred) / MIB.toDouble()
    println("  T1: ${"%.1f".format(ms)} ms, ${"%.1f".format(mib)} MiB")
    preveri("T1 hitro (< 100 ms)", ms < 100, "($ms ms)")
    val p50 = UsenetTok.Preslikava(dat, UsenetYenc.dekodiraj(YencKodirnik.kodiraj(film, "x.mkv", 1, 50, 0, 50_000)))
    enako("T1 dovoljen total: luknje kot stevilo", Triple(50, 49, listOf(2, 3, 4)), Triple(p50.celota, p50.manjka, p50.manjkajoci(3)))
    enako("T1 velikost nad celota x 4 MiB", "neskladno",
        izid { UsenetTok.Preslikava(dat, UsenetYenc.dekodiraj(YencKodirnik.kodiraj(film, "x.mkv", 1, 3, 0, 3 * 4 * MIB + 1))) })
    val o1 = Objava().apply { dodaj("Film.mkv", Bajti(film)) }
    t0 = System.nanoTime()
    enako("T1 preveriIzdajo z enim clankom", "neskladno",
        UsenetBralnik.preveriIzdajo(nzbIz(o1)) { YencKodirnik.kodiraj(film, "Film.mkv", 1, 999_999_999, 0, 100_000_000_000L) })
    preveri("T1 preveriIzdajo hitro", (System.nanoTime() - t0) < 500_000_000L)

    // T2: store vzorec pred stisnjenim filmom - glava filma ni v prvem segmentu
    val vz = Leno(2 * MIB, 3).beri(0, (2 * MIB).toInt())
    val fm = Leno(5 * MIB, 4).beri(0, (5 * MIB).toInt())
    for ((metoda, pricakovano) in listOf(3 to "stisnjen", 0 to "ok")) {
        val vol = Rar.glavni5(0, false) + Rar.glava5("Film.sample.mkv", vz.size.toLong(), vz) + vz +
            Rar.glava5("Film.mkv", fm.size.toLong(), fm, metoda = metoda) + fm + Rar.konec5(true)
        val o = Objava().apply { dodaj("Film.rar", Bajti(vol)) }
        enako("T2 prvi segment = nepopolno", UsenetRar.Izid.NEPOPOLNO, UsenetRar.preveri(vol.copyOfRange(0, 700 * 1024), vol.size.toLong()))
        enako("T2 preveriIzdajo metoda $metoda", pricakovano, UsenetBralnik.preveriIzdajo(nzbIz(o), o::telo))
        if (metoda != 0) {
            enako("T2 pripravi stisnjen", "stisnjen", izid { UsenetBralnik.pripravi(nzbIz(o), o::telo) })
        } else {
            val vir = UsenetBralnik.pripravi(nzbIz(o), o::telo).first
            enako("T2 predvaja film, ne vzorca", "Film.mkv" to fm.size.toLong(), vir.ime to vir.velikost)
            UsenetBralnik.Bralnik(o::telo, vir).use { b ->
                preveri("T2 bajti filma", UsenetBralnik.beriVse(b, 1000, 50_000).contentEquals(fm.copyOfRange(1000, 51_000)))
            }
        }
    }

    // T3: pokvarjen segment se prenese najvec enkrat
    val o3 = Objava().apply { dodaj("Film.mkv", Leno(20 * MIB, 9)) }
    val slab = o3.datoteke[0].third[5]
    o3.pokvarjeni += slab
    bralnik(o3).use { b ->
        UsenetBralnik.beriVse(b, 0, 5 * 700 * 1024 - 10)
        pocakajMir(b)
        repeat(20) { b.beri(0, 1000) }
        enako("T3 pokvarjen", "pokvarjen", izid { b.beri(5 * 700 * 1024L + 1, 10) })
        enako("T3 pokvarjen znova (iz spomina)", "pokvarjen", izid { b.beri(5 * 700 * 1024L + 1, 10) })
        pocakajMir(b)
        preveri("T3 pokvarjen clanek se ne prenasa znova", (o3.poClanku[slab] ?: 0) <= 1, "(${o3.poClanku[slab]})")
    }

    // T4: priprava 60 zvezkov - najvec PRIPRAVA_HKRATI hkrati, meja prometa
    val f4 = Leno(60 * 300 * 1024L, 5)
    val o4 = Objava(100 * 1024).apply { Rar.zvezki(5, "Film.mkv", f4, 300 * 1024L).forEachIndexed { i, z -> dodaj("Film.part%02d.rar".format(i + 1), z) } }
    val hkrati = java.util.concurrent.atomic.AtomicInteger()
    val najvec = java.util.concurrent.atomic.AtomicInteger()
    val vir4 = UsenetBralnik.pripravi(nzbIz(o4), { id ->
        najvec.accumulateAndGet(hkrati.incrementAndGet(), ::maxOf)
        try { Thread.sleep(2); o4.telo(id) } finally { hkrati.decrementAndGet() }
    }).first
    enako("T4 velikost filma", f4.velikost, vir4.velikost)
    preveri("T4 najvec hkrati", najvec.get() <= UsenetBralnik.PRIPRAVA_HKRATI, "(${najvec.get()})")
    enako("T4 nad mejo prometa = prevec", "prevec", izid { UsenetBralnik.pripravi(nzbIz(o4), o4::telo, najvecBajtov = MIB) })

    // T5: 40 storitvenih blokov po 1,5 MiB pred glavo datoteke - iskanje glav z mejo
    val blok = Rar.blok5(3, 0, Rar.vint(0) + Rar.vint(0) + Rar.vint(0) + Rar.vint(0) + Rar.vint(0) + Rar.vint(0), ByteArray(0), (1.5 * MIB).toLong())
    val nicle = Bajti(ByteArray((1.5 * MIB).toInt()))
    val f5 = Leno(MIB, 7).beri(0, MIB.toInt())
    val deli5 = ArrayList<Vsebina>()
    deli5 += Bajti(Rar.glavni5(0, true))
    repeat(40) { deli5 += Bajti(blok); deli5 += nicle }
    deli5 += Bajti(Rar.glava5("Film.mkv", f5.size.toLong(), f5, po = true) + f5 + Rar.konec5(false))
    val vol5 = Zlepek(deli5.map { Triple(it, 0L, it.velikost) })
    val vol52 = Rar.glavni5(1, true) + Rar.glava5("Film.mkv", f5.size.toLong(), f5, pred = true) + f5 + Rar.konec5(true)
    val o5 = Objava().apply { dodaj("Film.part1.rar", vol5); dodaj("Film.part2.rar", Bajti(vol52)) }
    enako("T5 iskanje glav z mejo = prevec", "prevec", UsenetBralnik.preveriIzdajo(nzbIz(o5), o5::telo))
    preveri("T5 najvec segmentov", o5.poslanih.get() <= 1 + UsenetBralnik.NAJVEC_GLAV_SEGMENTOV + 1, "(${o5.poslanih.get()})")

    // T9: close zbudi bralca, ki caka na prenos v teku
    val o9 = Objava().apply { dodaj("Film.mkv", Leno(4 * MIB, 1)) }
    val vir9 = UsenetBralnik.pripravi(nzbIz(o9), o9::telo).first
    val sprosti = java.util.concurrent.CountDownLatch(1)
    val b9 = UsenetBralnik.Bralnik({ id -> sprosti.await(10, java.util.concurrent.TimeUnit.SECONDS); o9.telo(id) }, vir9)
    var izid9: String? = null
    val t9 = Thread { izid9 = izid { b9.beri(2 * MIB, 10) } }.apply { start() }
    Thread.sleep(100)
    b9.close()
    t9.join(1000)
    preveri("T9 bralec se zbudi ob close", !t9.isAlive)
    enako("T9 izid zaprto", "zaprto", izid9)
    sprosti.countDown()

    // W4: prekinjena nit nalagalnika (Media3) dobi InterruptedIOException, ne splosne napake
    val o4b = Objava().apply { dodaj("Film.mkv", Leno(4 * MIB, 2)) }
    val sprosti4 = java.util.concurrent.CountDownLatch(1)
    val b4 = UsenetBralnik.Bralnik({ id -> sprosti4.await(10, java.util.concurrent.TimeUnit.SECONDS); o4b.telo(id) },
        UsenetBralnik.pripravi(nzbIz(o4b), o4b::telo).first)
    var napaka4: Throwable? = null
    val t4 = Thread { try { b4.beri(2 * MIB, 10) } catch (e: Throwable) { napaka4 = e } }.apply { start() }
    Thread.sleep(100)
    t4.interrupt()
    t4.join(1000)
    preveri("W4 InterruptedIOException", napaka4 is java.io.InterruptedIOException, "(${napaka4?.javaClass?.simpleName})")
    sprosti4.countDown()
    b4.close()

    // T12: Range z velikimi crkami; napaka na zacetku = 502 pred glavami (ne prirezan 206)
    enako("T12 Bytes=", 0L to 9L, UsenetStreznik.obseg("Bytes=0-9", 100))
    val o12 = Objava().apply { dodaj("Film.mkv", Leno(3 * MIB, 2)) }
    val b12 = bralnik(o12)
    o12.manjka += o12.datoteke[0].third[1]
    UsenetStreznik(b12).use { s ->
        val c = http(s.url, 700 * 1024L + 5, null)
        enako("T12 502 pred glavami", 502, c.responseCode)
    }

    // T13: vzorec kot beseda; zakrito ime s podpisom RAR je en zvezek
    for ((ime, je) in listOf("Film.Sample.mkv" to true, "sample-film.mkv" to true, "Film.2026.vzorec.mkv" to true,
            "Samplers.2026.1080p.mkv" to false, "The.Sampler.mkv" to false, "Film.mkv" to false)) {
        enako("T13 vzorec $ime", je, UsenetBralnik.jeVzorec(ime))
    }
    val f13 = Leno(3 * MIB, 8).beri(0, (3 * MIB).toInt())
    val en = Rar.glavni5(0, false) + Rar.glava5("Film.mkv", f13.size.toLong(), f13) + f13 + Rar.konec5(true)
    val o13 = Objava().apply { dodaj("a8f3c2d19e7b", Bajti(en)) }
    enako("T13 zakrito ime: ok", "ok", UsenetBralnik.preveriIzdajo(nzbIz(o13), o13::telo))
    val vir13 = UsenetBralnik.pripravi(nzbIz(o13), o13::telo).first
    enako("T13 zakrito ime: film", "Film.mkv" to f13.size.toLong(), vir13.ime to vir13.velikost)
    UsenetBralnik.Bralnik(o13::telo, vir13).use { b ->
        preveri("T13 pretaka film, ne surovega zvezka", UsenetBralnik.beriVse(b, 5, 1000).contentEquals(f13.copyOfRange(5, 1005)))
    }
    val o13b = Objava().apply { Rar.zvezki(5, "Film.mkv", Bajti(f13), MIB).forEachIndexed { i, z -> dodaj("%032x".format(i), z) } }
    enako("T13 vec zakritih zvezkov = ni_podprto", "ni_podprto", UsenetBralnik.preveriIzdajo(nzbIz(o13b), o13b::telo))
    preveri("T13 pripravi vec zakritih zvezkov zavrne",
        try { UsenetBralnik.pripravi(nzbIz(o13b), o13b::telo); false } catch (e: UsenetBralnik.NapakaIzdaje) { e.izid == "ni_podprto" })

    // W1: negativna cela stevila (vint nad Long.MAX_VALUE, HIGH_PACK z zgornjim bitom, podatki cez zvezek)
    val polja = Rar.vint(0) + Rar.vint(1) + Rar.vint(0x20) + Rar.vint(0) + Rar.vint(0) + ByteArray(9) { -1 } + byteArrayOf(1) + "x".toByteArray()
    val arh = Rar.PODPIS5 + Rar.blok5(1, 0, Rar.vint(0)) + Rar.blok5(2, 0, polja, ByteArray(0), 1) + "x".toByteArray()
    enako("W1 RAR5 negativna dolzina imena", UsenetRar.Izid.POSKODOVAN, UsenetRar.preveri(arh, arh.size.toLong()))
    val r4 = Rar.glavni4(false, true) + Rar.glava4Velika("film.mkv", "abc".toByteArray(), 0xFFFFFFFFL) + "abc".toByteArray() + Rar.konec4
    enako("W1 RAR4 HIGH_PACK zgornji bit", UsenetRar.Izid.POSKODOVAN, UsenetRar.preveri(r4, r4.size.toLong()))
    val cez = Rar.glavni5(0, false) + Rar.glava5("a.mkv", 10, "0123456789".toByteArray()) + "0123".toByteArray()
    enako("W1 podatki cez konec zvezka", UsenetRar.Izid.POSKODOVAN, UsenetRar.preveri(cez, cez.size.toLong()))

    // W6: brez segmenta 1 ali z luknjo v NZB ni ponujena
    val o6 = Objava().apply { dodaj("Film.mkv", Leno(5 * 700 * 1024L, 4)) }
    enako("W6 brez segmenta 1", "luknja", UsenetBralnik.preveriIzdajo(nzbIz(o6, setOf(o6.datoteke[0].third[0])), o6::telo))
    enako("W6 luknja v sredini", "luknja", UsenetBralnik.preveriIzdajo(nzbIz(o6, setOf(o6.datoteke[0].third[2])), o6::telo))
    enako("W6 cela", "ok", UsenetBralnik.preveriIzdajo(nzbIz(o6), o6::telo))

    // W2 / T7: poplava povezav brez zetona - najvec NAJVEC_POVEZAV, streznik se vseeno sam zapre
    val o7 = Objava().apply { dodaj("Film.mkv", Leno(MIB, 1)) }
    val s7 = UsenetStreznik(bralnik(o7), nedejavnostMs = 500)
    val prazne = (1..UsenetStreznik.NAJVEC_POVEZAV + 40).map { Socket("127.0.0.1", s7.vrata) }
    try {
        Thread.sleep(300)
        preveri("W2 povezav najvec ${UsenetStreznik.NAJVEC_POVEZAV}", s7.povezav <= UsenetStreznik.NAJVEC_POVEZAV, "(${s7.povezav})")
        val niti = Thread.getAllStackTraces().keys.count { it.name == "safeer-usenet-http-zahteva" }
        preveri("W2 niti najvec ${UsenetStreznik.NAJVEC_POVEZAV}", niti <= UsenetStreznik.NAJVEC_POVEZAV, "($niti)")
        val konec = System.nanoTime() + 5_000_000_000L
        while (!s7.zaprt && System.nanoTime() < konec) Thread.sleep(50)
        preveri("T7 povezave brez zetona ne drzijo streznika zivega", s7.zaprt)
    } finally {
        prazne.forEach { try { it.close() } catch (_: IOException) { } }
        s7.close()
    }
}

fun main(args: Array<String>) {
    println("Preizkus pogona Usenet, korak 2 (tok, RAR, bralnik, streznik)")
    val mapa = java.nio.file.Files.createTempDirectory("usenet-tok").toFile()
    try {
        preslikava()
        rar()
        bralnikPreizkusi()
        streznik()
        popravki58()
        val t = tls(mapa)
        konecDoKonca(mapa, t)
        if ("--meritev" in args) { meritevPrenosa(); meritev(t) }
    } finally {
        mapa.deleteRecursively()
    }
    println("  preverb: $preverb")
    if (napak == 0) println("UsenetTokTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
