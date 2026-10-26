package si.safeer.tv.os

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Omrezni del sonde toka v zivo (pregled 25. 10. 2026): zahteve verige HLS - glavni seznam, razlicice, kljuc, kos - z
 * TRDIM rokom celotne sonde. Brez Androida, zato ga preizkus poganja proti pravemu krajevnemu strezniku HTTP
 * (tests/SondaVerigaTest.kt): pocasen prvi bajt, kapljanje, rok sredi razlicic, streznik brez Range, preusmeritve.
 * Odlocitve so v [SondaHlsPravila]; razvrstitev napak povezave (imenik, kontrolno ime) da klicatelj ([izIzjeme]),
 * glave zahteve za vsak naslov tudi ([glaveZa]) - enake kot pri predvajanju.
 *
 *  - S1: rok ali napaka pred zadnjo razlicico ni dokaz, da je kanal mrtev ([SondaHlsPravila.izidRazlicic]).
 *  - S2: prvi odgovor 2xx, telo pa pocasno ali prekinjeno (surov TS, Icecast, restream na zahtevo) - ziv, kot doslej.
 *  - S3: rok velja za vsako zahtevo in branje; ob izteku straza prekine klic, nit ni zadrzana dlje od roka.
 *  - N1: kos ali kljuc, ki ga streznik z Range zavrne, se zahteva se brez Range (predvajalnik Range ne poslje).
 *  - N3: razlicice od prve nastete (kot Media3); kos 404/410 - preveri se naslednja razlicica.
 *  - J1 (26. 10. 2026, kot Linux f85e316): tudi DNS je pod rokom sonde - razresevanje v svoji niti; sistemski
 *    razresevalnik lahko caka 5-30 s in ga ni mogoce prekiniti.
 *  - J2/J10 (26. 10. 2026, kot Linux a85eef2): sonda se nikoli ne poveze na zasebni naslov, na to napravo (loopback) ali
 *    v domace omrezje - tudi ne po preusmeritvi; NAT64 (64:ff9b::) in 6to4 (2002::) odloci vgrajeni IPv4. Vsako ime se v
 *    sondi razresi enkrat, povezava gre na prav te naslove (OkHttp, brez DNS rebindinga med preverbo in povezavo).
 */
object SondaVeriga {
    /** Rok celotne sonde, rok ene zahteve in ura (ms); preizkus jih skrajsa. [zaseben] in [razresi] zamenja samo preizkus. */
    class Nastavitve(
        val rokMs: Long = SondaHlsPravila.ROK_SONDE_MS,
        val rokZahtevkaMs: Int = OsPravila.ROK_POVEZAVE_V_ZIVO_MS,
        val ura: () -> Long = { System.nanoTime() / 1_000_000L },
        val zaseben: (ByteArray) -> Boolean = ::zasebenCilj,
        val razresi: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    )

    /** Rok sonde je potekel (tudi ko ga je sprozila straza, ki je prekinila klic). */
    class RokSonde : IOException("rok sonde")

    /** Cilj je zaseben naslov, ta naprava ali domace omrezje - zavrnjeno PRED povezavo (J2). */
    class ZasebenCilj : UnknownHostException("zaseben naslov")

    /** Seznam HLS se je zacel (#EXTM3U), branje pa se je prekinilo: to ni surov tok (S2), [vzrok] velja. */
    private class PrekinjenSeznam(val vzrok: IOException) : IOException(vzrok)

    private enum class Nacin { PRVI, SEZNAM, KOS }

    private class Odgovor(val koda: Int, val naslov: String, val telo: String?, val prirezano: Boolean, val preusmeritev: Int) {
        val izid: OsPravila.IzidSonde get() = if (koda <= 0) OsPravila.IzidSonde.NEZNANO else OsPravila.izidPoOdgovoru(koda)
        val opis: String get() = when (koda) { -1 -> "preusmeritev brez cilja"; -2 -> "prevec preusmeritev"; else -> "HTTP $koda" }
    }

    /** Izid koraka: izid, razlog za dnevnik in koda HTTP (0 = ni odgovora). */
    private class Izid(val izid: OsPravila.IzidSonde, val razlog: String, val http: Int = 0)

    /**
     * Ena sonda: rok, zadnji naslov (za imenik ob napaki), korak verige (za razlog) in preverjeni naslovi imen - vsako ime
     * se razresi enkrat, z rokom sonde (J1), in samo, ce so vsi njegovi naslovi javni (J2).
     */
    private class Sonda(val n: Nastavitve, val glaveZa: (String) -> Map<String, String>) : Dns {
        val rokDo = n.ura() + n.rokMs
        var zadnji = ""
        var korak = ""
        fun preostanek() = rokDo - n.ura()
        private val naslovi = ConcurrentHashMap<String, List<InetAddress>>()

        override fun lookup(hostname: String): List<InetAddress> {
            val h = hostname.lowercase(java.util.Locale.ROOT).trimEnd('.')
            naslovi[h]?.let { return it }
            val p = preostanek()
            if (p <= 0) throw RokSonde()
            val f = dns.submit<List<InetAddress>> { n.razresi(h) }
            val r = try { f.get(p, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { f.cancel(true); throw RokSonde() }
            catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? IOException) ?: UnknownHostException("DNS") }
            catch (_: InterruptedException) { f.cancel(true); Thread.currentThread().interrupt(); throw RokSonde() }
            if (r.isEmpty()) throw UnknownHostException(h)
            if (r.any { n.zaseben(it.address) }) throw ZasebenCilj()
            naslovi[h] = r
            return r
        }

        /** Gostitelj, ki je naslov IP: OkHttp zanj ne vprasa [lookup] - preverimo ga tu (J2). */
        fun preveriIp(gostitelj: String) {
            val ip = ipNaslov(gostitelj) ?: return
            if (n.zaseben(ip.address)) throw ZasebenCilj()
        }
    }

    /**
     * SAMO za preizkus (tests/SondaTokaTest.kt): preverba zasebnega cilja za sonde, ki gredo prek [SondaToka] in svojih
     * [Nastavitve] ne morejo podati - isti mehanizem kot [Nastavitve.zaseben] v tests/SondaVerigaTest.kt. Privzeto null:
     * velja [Nastavitve.zaseben] (J2, zaseben naslov nikoli). Produkcijska koda ga ne nastavi; preizkus ga po koncu vrne na null.
     */
    @Volatile internal var zasebenZaPreizkus: ((ByteArray) -> Boolean)? = null

    /** Straza roka: ob izteku prekine klic, da blokirano branje ali povezovanje takoj neha. */
    private val straza = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-sonda-straza").apply { isDaemon = true } }
    /** J1: razresevanje imen v svoji niti (getAllByName se ne da prekiniti); nit, ki obvisi, ne zadrzi sonde. */
    private val dns = Executors.newCachedThreadPool { r -> Thread(r, "safeer-sonda-dns").apply { isDaemon = true } }
    /** Brez samodejnih preusmeritev (vsak skok preveri [zahteva]) in brez ohranjanja povezav (vsaka sonda svoje naslove). */
    private val osnovniKlient: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    }

    /** Naslov IP v obliki besedila (IPv4 s pikami ali IPv6) - brez poizvedbe DNS; null = ni naslov IP. */
    fun ipNaslov(gostitelj: String): InetAddress? {
        val g = gostitelj.trim('[', ']')
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(g)
        if (!ipv4 && (':' !in g || !g.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })) return null
        return try { InetAddress.getByName(g) } catch (_: Exception) { null }
    }

    /**
     * Cilj, na katerega se sonda ne sme povezati (J2/J10, kot Linux kanali_iptv.dovoljen_ip brez domacega omrezja): vse, kar
     * ni javni unicast. IPv4: [WebtorPravila.zasebenNaslov] (0/8, 10/8, 127/8, 100.64/10, 169.254/16, 172.16/12,
     * 192.168/16, 224+) ter 192.0.0/24, 192.0.2/24, 198.18/15, 198.51.100/24, 203.0.113/24. IPv6: samo 2000::/3 brez
     * dokumentacije in Teredo; ::ffff:, NAT64 (64:ff9b::/96, 64:ff9b:1::/48) in 6to4 (2002::/16) po vgrajenem IPv4.
     */
    fun zasebenCilj(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size == 4) {
            if (WebtorPravila.zasebenNaslov(b)) return true
            return (u(0) == 192 && u(1) == 0 && (u(2) == 0 || u(2) == 2)) || (u(0) == 198 && (u(1) == 18 || u(1) == 19)) ||
                (u(0) == 198 && u(1) == 51 && u(2) == 100) || (u(0) == 203 && u(1) == 0 && u(2) == 113)
        }
        if (b.size != 16) return true
        if ((0..9).all { u(it) == 0 } && u(10) == 0xFF && u(11) == 0xFF) return zasebenCilj(b.copyOfRange(12, 16))
        if (u(0) == 0 && u(1) == 0x64 && u(2) == 0xFF && u(3) == 0x9B && ((4..11).all { u(it) == 0 } || (u(4) == 0 && u(5) == 1)))
            return zasebenCilj(b.copyOfRange(12, 16))
        if (u(0) == 0x20 && u(1) == 0x02) return zasebenCilj(b.copyOfRange(2, 6))
        if ((u(0) and 0xE0) != 0x20) return true
        return u(0) == 0x20 && u(1) == 0x01 && ((u(2) == 0x0D && u(3) == 0xB8) || (u(2) == 0 && u(3) == 0))
    }

    /**
     * Izid sonde toka [naslov] in razlog (brez naslova - ima lahko geslo). [izIzjeme] razvrsti napako povezave (neznan
     * gostitelj, zavrnjena povezava, rok povezave) glede na zadnji naslov; rok celotne sonde je vedno NEZNANO, zaseben
     * cilj tudi (kanal ostane viden, odloci predvajanje).
     */
    fun preveri(naslov: String, glaveZa: (String) -> Map<String, String>,
                izIzjeme: (IOException, String) -> Pair<OsPravila.IzidSonde, String>,
                n: Nastavitve = Nastavitve()): Pair<OsPravila.IzidSonde, String> {
        if (!naslov.startsWith("http://") && !naslov.startsWith("https://")) return OsPravila.IzidSonde.NEZNANO to "ni http"
        val z = zasebenZaPreizkus
        val s = Sonda(if (z == null) n else Nastavitve(n.rokMs, n.rokZahtevkaMs, n.ura, z, n.razresi), glaveZa)
        s.zadnji = naslov
        try {
            val prvi = zahteva(s, naslov, Nacin.PRVI)
            if (prvi.koda !in 200..299) return prvi.izid to "${prvi.opis} po ${prvi.preusmeritev} preusmeritvah"
            // Ni seznam HLS (surov tok, MPEG-TS, MP4) ali telo ni prislo v roku (S2): odgovor 2xx je dovolj, kot doslej.
            val telo = prvi.telo ?: return OsPravila.IzidSonde.ZIV to "${prvi.opis} po ${prvi.preusmeritev} preusmeritvah"
            if (!SondaHlsPravila.jeGlavni(telo)) return seznamKosov(s, telo, prvi.naslov, prvi.prirezano).let { it.izid to it.razlog }
            val razlicice = SondaHlsPravila.razlicice(telo, prvi.naslov, prvi.prirezano)
            if (razlicice.isEmpty()) return OsPravila.IzidSonde.NEZNANO to "glavni seznam brez razlicic"
            val izidi = ArrayList<OsPravila.IzidSonde>()
            var opis = ""
            for (r in razlicice) {
                s.korak = "razlicica: "
                val o = try { zahteva(s, r, Nacin.SEZNAM) } catch (e: RokSonde) { throw e } catch (e: ZasebenCilj) { throw e } catch (e: IOException) {
                    // N13: razlog napake razlicice ostane (imenik, zavrnjena povezava).
                    val (i, d) = izIzjeme(e, s.zadnji); izidi += i; opis = d; continue
                }
                val t = o.telo
                if (o.koda !in 200..299 || t == null || !SondaHlsPravila.jeSeznam(t)) {
                    izidi += if (o.koda in 200..299) OsPravila.IzidSonde.NEZNANO else o.izid
                    opis = o.opis
                    continue
                }
                val k = seznamKosov(s, t, o.naslov, o.prirezano)
                if (k.izid == OsPravila.IzidSonde.ZIV) return k.izid to k.razlog
                // N3: kos 404/410 - predvajalnik vzame naslednjo razlicico; sonda tudi. Drugo (403, DRM ...) velja za kanal.
                if (k.izid == OsPravila.IzidSonde.MRTEV && SondaHlsPravila.naslednjaRazlicicaPoKosu(k.http)) { izidi += k.izid; opis = k.razlog; continue }
                return k.izid to k.razlog
            }
            return SondaHlsPravila.izidRazlicic(izidi, razlicice.size) to "razlicice (${izidi.size}/${razlicice.size}): $opis"
        } catch (_: RokSonde) {
            return OsPravila.IzidSonde.NEZNANO to "${s.korak}rok sonde"
        } catch (_: ZasebenCilj) {
            // J2: zasebnega cilja ne odpremo - ni dokaz, da kanala ni.
            return OsPravila.IzidSonde.NEZNANO to "${s.korak}zaseben naslov"
        } catch (e: IOException) {
            val (i, d) = izIzjeme(e, s.zadnji)
            return i to s.korak + d
        } catch (e: Exception) {
            return OsPravila.IzidSonde.NEZNANO to "${s.korak}napaka ${e.javaClass.simpleName}"
        }
    }

    /** Seznam kosov: DRM, kljuc (ce je) in zadnji kos. */
    private fun seznamKosov(s: Sonda, seznam: String, osnova: String, prirezano: Boolean): Izid {
        // Nas predvajalnik kanala z DRM ne predvaja (ni licencnega streznika).
        if (SondaHlsPravila.drm(seznam)) return Izid(OsPravila.IzidSonde.MRTEV, "DRM")
        SondaHlsPravila.kljuc(seznam, osnova, prirezano)?.let { k ->
            s.korak = "kljuc: "
            val o = kos(s, k)
            val izid = SondaHlsPravila.izidKosa(o.koda)
            if (izid != OsPravila.IzidSonde.ZIV) return Izid(izid, "kljuc: ${o.opis}", o.koda)
        }
        val k = SondaHlsPravila.zadnjiKos(seznam, osnova, prirezano) ?: return Izid(OsPravila.IzidSonde.NEZNANO, "seznam brez kosov")
        s.korak = "kos: "
        val o = kos(s, k)
        return Izid(SondaHlsPravila.izidKosa(o.koda), "kos: ${o.opis}", o.koda)
    }

    /** Kos ali kljuc: najprej prvi bajti (Range); ce streznik Range zavrne, se enkrat brez Range (N1). */
    private fun kos(s: Sonda, naslov: String): Odgovor {
        val o = zahteva(s, naslov, Nacin.KOS)
        if (!SondaHlsPravila.ponoviBrezRange(o.koda)) return o
        return zahteva(s, naslov, Nacin.KOS, brezRange = true)
    }

    /**
     * Ena zahteva s sledenjem preusmeritvam (tudi med http in https, kot pri predvajanju) - vsak skok gre skozi preverbo
     * cilja ([Sonda.lookup], [Sonda.preveriIp]). Rok povezave in branja je najvec preostanek sonde; straza ob izteku klic
     * prekine. PRVI in SEZNAM bereta seznam HLS (najvec [SondaHlsPravila.NAJVEC_BAJTOV]; kar se ne zacne z #EXTM3U, neha
     * po prvih bajtih). PRVI: napaka ali rok med branjem telesa odgovora 2xx = telesa ni (S2, surov tok). KOS: samo koda.
     */
    private fun zahteva(s: Sonda, naslov: String, nacin: Nacin, brezRange: Boolean = false): Odgovor {
        var url = naslov
        for (korak in 0 until 5) {
            val preostanek = s.preostanek()
            if (preostanek <= 0) throw RokSonde()
            s.zadnji = url
            val zahtevek = try { Request.Builder().url(url) } catch (_: IllegalArgumentException) { return Odgovor(-1, url, null, false, korak) }
            val cilj = zahtevek.build().url
            s.preveriIp(cilj.host)
            for ((k, v) in s.glaveZa(url)) try { zahtevek.header(k, v) } catch (_: IllegalArgumentException) { }
            if (nacin == Nacin.KOS && !brezRange) zahtevek.header("Range", "bytes=0-${SondaHlsPravila.BAJTOV_KOSA - 1}")
            val rok = minOf(s.n.rokZahtevkaMs.toLong(), preostanek).coerceAtLeast(1L)
            val klient = osnovniKlient.newBuilder().dns(s).connectTimeout(rok, TimeUnit.MILLISECONDS)
                .readTimeout(rok, TimeUnit.MILLISECONDS).writeTimeout(rok, TimeUnit.MILLISECONDS).build()
            val klic = klient.newCall(zahtevek.build())
            val zapri = straza.schedule({ klic.cancel() }, preostanek, TimeUnit.MILLISECONDS)
            try {
                val odgovor = try { klic.execute() } catch (e: ZasebenCilj) { throw e } catch (e: RokSonde) { throw e }
                    catch (e: IOException) { if (s.preostanek() <= 0) throw RokSonde(); throw e }
                odgovor.use { o ->
                    val koda = o.code
                    if (koda !in 300..399) {
                        if (nacin == Nacin.KOS || koda !in 200..299) return Odgovor(koda, url, null, false, korak)
                        val vhod = o.body?.byteStream() ?: return Odgovor(koda, url, null, false, korak)
                        val telo = try { beriSeznam(s, vhod) }
                        // Seznam HLS se je zacel in prekinil (kapljanje, S3): rok = ne vemo, napaka povezave po klicatelju.
                        catch (e: PrekinjenSeznam) { throw e.vzrok }
                        // S2: prvi odgovor 2xx je ziv tudi, ce telo pocasi ali sploh ne pride (surov tok); pri razlicici ne.
                        catch (e: IOException) { if (nacin == Nacin.PRVI) null else throw e }
                        return Odgovor(koda, url, telo?.first, telo?.second == true, korak)
                    }
                    val naprej = o.header("Location").orEmpty()
                    if (naprej.isBlank()) return Odgovor(-1, url, null, false, korak)
                    url = cilj.resolve(naprej)?.toString() ?: return Odgovor(-1, url, null, false, korak)
                }
            } finally {
                zapri.cancel(false)
            }
        }
        return Odgovor(-2, url, null, false, 5)
    }

    /**
     * Besedilo seznama HLS in ali je prirezano; null = odgovor ni seznam (prvih bajtov ni #EXTM3U). Zacetek preverimo
     * enkrat (N12), besedilo sestavimo enkrat na koncu.
     */
    private fun beriSeznam(s: Sonda, vhod: InputStream): Pair<String, Boolean>? {
        val buf = ByteArrayOutputStream()
        val kos = ByteArray(8192)
        var zacetekPreverjen = false
        var prirezano = false
        while (true) {
            if (s.preostanek() <= 0) throw if (zacetekPreverjen) PrekinjenSeznam(RokSonde()) else RokSonde()
            val prostora = SondaHlsPravila.NAJVEC_BAJTOV - buf.size()
            if (prostora <= 0) { prirezano = true; break }
            val n = try { vhod.read(kos, 0, minOf(kos.size, prostora)) } catch (e: IOException) {
                val v = if (s.preostanek() <= 0) RokSonde() else e
                throw if (zacetekPreverjen) PrekinjenSeznam(v) else v
            }
            if (n < 0) break
            buf.write(kos, 0, n)
            if (!zacetekPreverjen && buf.size() >= 16) {
                zacetekPreverjen = true
                val zacetek = String(buf.toByteArray(), 0, minOf(buf.size(), 64), Charsets.UTF_8)
                // Surov tok (MPEG-TS, MP4, zvok) ne bere naprej: dovolj je, da streznik odgovori.
                if (!SondaHlsPravila.jeSeznam(zacetek)) return null
            }
        }
        val besedilo = buf.toString("UTF-8")
        return if (SondaHlsPravila.jeSeznam(besedilo)) besedilo to prirezano else null
    }
}
