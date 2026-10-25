package si.safeer.tv.os

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
 *  - S3: rok velja za vsako zahtevo in branje; ob izteku straza zapre povezavo, nit ni zadrzana dlje od roka.
 *  - N1: kos ali kljuc, ki ga streznik z Range zavrne, se zahteva se brez Range (predvajalnik Range ne poslje).
 *  - N3: razlicice od prve nastete (kot Media3); kos 404/410 - preveri se naslednja razlicica.
 */
object SondaVeriga {
    /** Rok celotne sonde, rok ene zahteve in ura (ms); preizkus jih skrajsa. */
    class Nastavitve(
        val rokMs: Long = SondaHlsPravila.ROK_SONDE_MS,
        val rokZahtevkaMs: Int = OsPravila.ROK_POVEZAVE_V_ZIVO_MS,
        val ura: () -> Long = { System.nanoTime() / 1_000_000L },
    )

    /** Rok sonde je potekel (tudi ko ga je sprozila straza, ki je zaprla povezavo). */
    class RokSonde : IOException("rok sonde")

    /** Seznam HLS se je zacel (#EXTM3U), branje pa se je prekinilo: to ni surov tok (S2), [vzrok] velja. */
    private class PrekinjenSeznam(val vzrok: IOException) : IOException(vzrok)

    private enum class Nacin { PRVI, SEZNAM, KOS }

    private class Odgovor(val koda: Int, val naslov: String, val telo: String?, val prirezano: Boolean, val preusmeritev: Int) {
        val izid: OsPravila.IzidSonde get() = if (koda <= 0) OsPravila.IzidSonde.NEZNANO else OsPravila.izidPoOdgovoru(koda)
        val opis: String get() = when (koda) { -1 -> "preusmeritev brez cilja"; -2 -> "prevec preusmeritev"; else -> "HTTP $koda" }
    }

    /** Izid koraka: izid, razlog za dnevnik in koda HTTP (0 = ni odgovora). */
    private class Izid(val izid: OsPravila.IzidSonde, val razlog: String, val http: Int = 0)

    /** Ena sonda: rok, zadnji naslov (za imenik ob napaki) in korak verige (za razlog). */
    private class Sonda(val n: Nastavitve, val glaveZa: (String) -> Map<String, String>) {
        val rokDo = n.ura() + n.rokMs
        var zadnji = ""
        var korak = ""
        fun preostanek() = rokDo - n.ura()
    }

    /** Straza roka: ob izteku zapre povezavo, da blokirano branje ali povezovanje takoj neha. */
    private val straza = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-sonda-straza").apply { isDaemon = true } }

    /**
     * Izid sonde toka [naslov] in razlog (brez naslova - ima lahko geslo). [izIzjeme] razvrsti napako povezave (neznan
     * gostitelj, zavrnjena povezava, rok povezave) glede na zadnji naslov; rok celotne sonde je vedno NEZNANO.
     */
    fun preveri(naslov: String, glaveZa: (String) -> Map<String, String>,
                izIzjeme: (IOException, String) -> Pair<OsPravila.IzidSonde, String>,
                n: Nastavitve = Nastavitve()): Pair<OsPravila.IzidSonde, String> {
        if (!naslov.startsWith("http://") && !naslov.startsWith("https://")) return OsPravila.IzidSonde.NEZNANO to "ni http"
        val s = Sonda(n, glaveZa)
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
                val o = try { zahteva(s, r, Nacin.SEZNAM) } catch (e: RokSonde) { throw e } catch (e: IOException) {
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
     * Ena zahteva s sledenjem preusmeritvam (tudi med http in https, kot pri predvajanju). Rok povezave in branja je
     * najvec preostanek sonde; straza ob izteku povezavo zapre. PRVI in SEZNAM bereta seznam HLS (najvec
     * [SondaHlsPravila.NAJVEC_BAJTOV]; kar se ne zacne z #EXTM3U, neha po prvih bajtih). PRVI: napaka ali rok med
     * branjem telesa odgovora 2xx = telesa ni (S2, surov tok). KOS: samo koda, telesa ne beremo.
     */
    private fun zahteva(s: Sonda, naslov: String, nacin: Nacin, brezRange: Boolean = false): Odgovor {
        var url = naslov
        for (korak in 0 until 5) {
            val preostanek = s.preostanek()
            if (preostanek <= 0) throw RokSonde()
            s.zadnji = url
            val p = URL(url).openConnection() as HttpURLConnection
            val rok = minOf(s.n.rokZahtevkaMs.toLong(), preostanek).toInt().coerceAtLeast(1)
            val zapri = straza.schedule({ try { p.disconnect() } catch (_: Throwable) { } }, preostanek, TimeUnit.MILLISECONDS)
            try {
                p.connectTimeout = rok
                p.readTimeout = rok
                p.instanceFollowRedirects = false
                for ((k, v) in s.glaveZa(url)) p.setRequestProperty(k, v)
                if (nacin == Nacin.KOS && !brezRange) p.setRequestProperty("Range", "bytes=0-${SondaHlsPravila.BAJTOV_KOSA - 1}")
                val koda = try { p.responseCode } catch (e: IOException) { if (s.preostanek() <= 0) throw RokSonde(); throw e }
                if (koda !in 300..399) {
                    if (nacin == Nacin.KOS || koda !in 200..299) return Odgovor(koda, url, null, false, korak)
                    val telo = try { beriSeznam(s, p) }
                    // Seznam HLS se je zacel in prekinil (kapljanje, S3): rok = ne vemo, napaka povezave po klicatelju.
                    catch (e: PrekinjenSeznam) { throw e.vzrok }
                    // S2: prvi odgovor 2xx je ziv tudi, ce telo pocasi ali sploh ne pride (surov tok); pri razlicici ne.
                    catch (e: IOException) { if (nacin == Nacin.PRVI) null else throw e }
                    return Odgovor(koda, url, telo?.first, telo?.second == true, korak)
                }
                val naprej = p.getHeaderField("Location").orEmpty()
                if (naprej.isBlank()) return Odgovor(-1, url, null, false, korak)
                url = URL(URL(url), naprej).toString()
            } finally {
                zapri.cancel(false)
                try { p.disconnect() } catch (_: Throwable) { }
            }
        }
        return Odgovor(-2, url, null, false, 5)
    }

    /**
     * Besedilo seznama HLS in ali je prirezano; null = odgovor ni seznam (prvih bajtov ni #EXTM3U). Zacetek preverimo
     * enkrat (N12), besedilo sestavimo enkrat na koncu.
     */
    private fun beriSeznam(s: Sonda, p: HttpURLConnection): Pair<String, Boolean>? {
        val vhod = p.inputStream
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
