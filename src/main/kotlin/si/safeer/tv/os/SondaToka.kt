package si.safeer.tv.os

import java.net.HttpURLConnection
import java.net.URL

/**
 * Sonda toka kanala ali postaje v zivo: ali streznik toka s te naprave res odgovori. Dodatek kanalov nasteva tudi
 * kanale, katerih streznika ni vec ali s te mreze ni dosegljiv (izmerjeno 5. 10. 2026: trije od treh izbranih) - takega
 * kanala ne kazemo (lastnik: »samo tok, ki ga lahko predvajamo«). Ena kratka zahteva, prebere samo glavo odgovora.
 * Klice se iz niti v ozadju (caka na omrezje in na [Imenik]).
 */
object SondaToka {
    /** true = streznik je odgovoril z vsebino, false = toka pri viru ni (dokazano), null = ne vemo (kanal ostane). */
    fun dosegljiv(naslov: String, glave: Map<String, String>, agent: String): Boolean? = when (preveri(naslov, glave, agent)) {
        OsPravila.IzidSonde.ZIV -> true
        OsPravila.IzidSonde.MRTEV -> false
        // Naloga 75: negotovo (casovna omejitev, 5xx) samo po sebi ni dokaz - steje ga [MrtviKanali.zapomniNegotovega].
        else -> null
    }

    /** Naloga 75: izid sonde z zanesljivostjo ([OsPravila.IzidSonde]). */
    fun preveri(naslov: String, glave: Map<String, String>, agent: String): OsPravila.IzidSonde = preveriZRazlogom(naslov, glave, agent).first

    /**
     * Naloga 96: izid sonde in razlog za dnevnik (koda HTTP, imenik, zavrnjena povezava, rok ...) - brez naslova toka (ima lahko
     * geslo); gostitelj samo, ce ga imenik ne pozna. Sonda sprejme vsak odgovor 2xx ne glede na vrsto vsebine (tudi surov
     * MPEG-TS, video/mp2t, application/octet-stream) - [OsPravila.izidPoOdgovoru].
     *
     * 25. 10. 2026: seznam HLS preverimo do konca verige ([SondaHlsPravila]) - razlicica, kljuc in zadnji kos. Prej je glavni
     * seznam z odgovorom 200 pomenil »ziv«, ceprav so bile vse razlicice 404 (ABC News Live) ali kosi geografsko zaprti.
     */
    fun preveriZRazlogom(naslov: String, glave: Map<String, String>, agent: String): Pair<OsPravila.IzidSonde, String> {
        if (!naslov.startsWith("http://") && !naslov.startsWith("https://")) return OsPravila.IzidSonde.NEZNANO to "ni http"
        val od = android.os.SystemClock.uptimeMillis()
        val zadnji = arrayOf(naslov)
        var korak = ""
        fun casPotekel() = android.os.SystemClock.uptimeMillis() - od > SondaHlsPravila.ROK_SONDE_MS
        try {
            val prvi = zahteva(naslov, glave, agent, telo = true, kos = false, zadnji)
            if (prvi.koda !in 200..299) return prvi.izid to "${prvi.opis} po ${prvi.preusmeritev} preusmeritvah"
            // Ni seznam HLS (surov tok, MPEG-TS, MP4): odgovor 2xx je dovolj, kot doslej.
            if (prvi.telo == null || !SondaHlsPravila.jeSeznam(prvi.telo)) return OsPravila.IzidSonde.ZIV to "${prvi.opis} po ${prvi.preusmeritev} preusmeritvah"
            var seznam: String = prvi.telo
            var osnova = prvi.naslov
            var prirezano = prvi.prirezano
            if (SondaHlsPravila.jeGlavni(seznam)) {
                korak = "razlicica: "
                val razlicice = SondaHlsPravila.razlicice(seznam, osnova, prirezano)
                if (razlicice.isEmpty()) return OsPravila.IzidSonde.NEZNANO to "glavni seznam brez razlicic"
                val izidi = ArrayList<OsPravila.IzidSonde>()
                var opisi = ""
                var ziva: Odgovor? = null
                for (r in razlicice) {
                    if (casPotekel()) break
                    val o = try { zahteva(r, glave, agent, telo = true, kos = false, zadnji) } catch (e: java.io.IOException) { izidi += izIzjeme(e, zadnji[0]).first; continue }
                    if (o.koda in 200..299 && o.telo != null && SondaHlsPravila.jeSeznam(o.telo)) { ziva = o; break }
                    izidi += if (o.koda in 200..299) OsPravila.IzidSonde.NEZNANO else o.izid
                    opisi = o.opis
                }
                val z = ziva
                val zTelo = z?.telo
                if (z == null || zTelo == null) {
                    val izid = SondaHlsPravila.izidRazlicic(izidi)
                    return izid to "razlicice (${izidi.size}/${razlicice.size}): $opisi"
                }
                seznam = zTelo; osnova = z.naslov; prirezano = z.prirezano
            }
            // Nas predvajalnik kanala z DRM ne predvaja (ni licencnega streznika).
            if (SondaHlsPravila.drm(seznam)) return OsPravila.IzidSonde.MRTEV to "DRM"
            SondaHlsPravila.kljuc(seznam, osnova, prirezano)?.let { k ->
                if (casPotekel()) return OsPravila.IzidSonde.NEZNANO to "rok sonde"
                korak = "kljuc: "
                val o = zahteva(k, glave, agent, telo = false, kos = true, zadnji)
                val izid = SondaHlsPravila.izidKosa(o.koda)
                if (izid != OsPravila.IzidSonde.ZIV) return izid to "kljuc: ${o.opis}"
            }
            val kos = SondaHlsPravila.zadnjiKos(seznam, osnova, prirezano) ?: return OsPravila.IzidSonde.NEZNANO to "seznam brez kosov"
            if (casPotekel()) return OsPravila.IzidSonde.NEZNANO to "rok sonde"
            korak = "kos: "
            val o = zahteva(kos, glave, agent, telo = false, kos = true, zadnji)
            return SondaHlsPravila.izidKosa(o.koda) to "kos: ${o.opis}"
        } catch (e: java.io.IOException) {
            val (izid, opis) = izIzjeme(e, zadnji[0])
            return izid to korak + opis
        } catch (e: Exception) { return OsPravila.IzidSonde.NEZNANO to "${korak}napaka ${e.javaClass.simpleName}" }
    }

    /** Odgovor ene zahteve: koda, naslov po preusmeritvah, besedilo seznama (null = ni seznam ali ga nismo brali). */
    private class Odgovor(val koda: Int, val naslov: String, val telo: String?, val prirezano: Boolean, val preusmeritev: Int) {
        val izid: OsPravila.IzidSonde get() = if (koda <= 0) OsPravila.IzidSonde.NEZNANO else OsPravila.izidPoOdgovoru(koda)
        val opis: String get() = when (koda) { -1 -> "preusmeritev brez cilja"; -2 -> "prevec preusmeritev"; else -> "HTTP $koda" }
    }

    /**
     * Ena zahteva s sledenjem preusmeritvam (tudi med http in https, kot pri predvajanju). [telo]: pri 2xx prebere seznam HLS
     * (najvec [SondaHlsPravila.NAJVEC_BAJTOV]; kar se ne zacne z #EXTM3U - surov tok -, neha po prvih bajtih). [kos]: zahteva
     * samo prve bajte (Range), telesa ne bere. [zadnji][0] = naslov zadnje zahteve (za imenik ob napaki).
     */
    private fun zahteva(naslov: String, glave: Map<String, String>, agent: String, telo: Boolean, kos: Boolean, zadnji: Array<String>): Odgovor {
        var url = naslov
        for (korak in 0 until 5) {
            zadnji[0] = url
            val p = URL(url).openConnection() as HttpURLConnection
            try {
                p.connectTimeout = OsPravila.ROK_POVEZAVE_V_ZIVO_MS
                p.readTimeout = OsPravila.ROK_POVEZAVE_V_ZIVO_MS
                p.instanceFollowRedirects = false
                if (agent.isNotBlank()) p.setRequestProperty("User-Agent", agent)
                for ((k, v) in glave) p.setRequestProperty(k, v)
                if (kos) p.setRequestProperty("Range", "bytes=0-${SondaHlsPravila.BAJTOV_KOSA - 1}")
                val koda = p.responseCode
                if (koda !in 300..399) {
                    val besedilo = if (telo && koda in 200..299) beriSeznam(p) else null
                    return Odgovor(koda, url, besedilo?.first, besedilo?.second == true, korak)
                }
                val naprej = p.getHeaderField("Location").orEmpty()
                if (naprej.isBlank()) return Odgovor(-1, url, null, false, korak)
                url = URL(URL(url), naprej).toString()
            } finally { try { p.disconnect() } catch (_: Throwable) { } }
        }
        return Odgovor(-2, url, null, false, 5)
    }

    /** Besedilo seznama HLS in ali je prirezano; null = odgovor ni seznam (prvih bajtov ni #EXTM3U). */
    private fun beriSeznam(p: HttpURLConnection): Pair<String, Boolean>? {
        val vhod = p.inputStream
        val buf = java.io.ByteArrayOutputStream()
        val kos = ByteArray(8192)
        var prirezano = false
        while (true) {
            val n = vhod.read(kos, 0, minOf(kos.size, SondaHlsPravila.NAJVEC_BAJTOV - buf.size()))
            if (n <= 0) { prirezano = n == 0; break }
            buf.write(kos, 0, n)
            // Surov tok (MPEG-TS, MP4, zvok) ne bere naprej: dovolj je, da streznik odgovori.
            if (buf.size() >= 16 && !SondaHlsPravila.jeSeznam(String(buf.toByteArray(), Charsets.UTF_8))) return null
            if (buf.size() >= SondaHlsPravila.NAJVEC_BAJTOV) { prirezano = true; break }
        }
        val s = String(buf.toByteArray(), Charsets.UTF_8)
        return if (SondaHlsPravila.jeSeznam(s)) s to prirezano else null
    }

    /** Izid ob napaki povezave: neznan gostitelj in zavrnjena povezava ob delujocem omrezju sta dokaz, rok je negotov. */
    private fun izIzjeme(e: java.io.IOException, url: String): Pair<OsPravila.IzidSonde, String> {
        if (e is java.net.UnknownHostException) {
            val gostitelj = OsPravila.gostiteljIzNapake(e.message).ifEmpty { try { URL(url).host.orEmpty().lowercase() } catch (_: Exception) { "" } }
            if (gostitelj.isEmpty()) return OsPravila.IzidSonde.NEZNANO to "neznan gostitelj"
            val o = Imenik.obstaja(listOf(gostitelj, Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            return (if (OsPravila.mrtevGostitelj(o[0], o[1])) OsPravila.IzidSonde.MRTEV else OsPravila.IzidSonde.NEZNANO) to
                "imenik ne pozna $gostitelj (ime=${o[0]}, kontrola=${o[1]})"
        }
        if (e !is java.net.SocketTimeoutException && e !is java.net.ConnectException && e !is java.net.NoRouteToHostException)
            return OsPravila.IzidSonde.NEZNANO to "napaka ${e.javaClass.simpleName}"
        val o = Imenik.obstaja(listOf(Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
        // Naloga 75: zavrnjena povezava ali ni poti ob delujocem omrezju je dokaz (kot doslej); casovna omejitev ni -
        // tok je lahko zacasno nedosegljiv ali geografsko omejen.
        if (e is java.net.SocketTimeoutException) return OsPravila.izidCasovneOmejitve(o[0]) to "rok povezave (kontrola=${o[0]})"
        return (if (OsPravila.mrtevNeodziven(false, o[0])) OsPravila.IzidSonde.MRTEV else OsPravila.IzidSonde.NEZNANO) to
            "${e.javaClass.simpleName} (kontrola=${o[0]})"
    }
}
