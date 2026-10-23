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
     */
    fun preveriZRazlogom(naslov: String, glave: Map<String, String>, agent: String): Pair<OsPravila.IzidSonde, String> {
        var url = naslov
        if (!url.startsWith("http://") && !url.startsWith("https://")) return OsPravila.IzidSonde.NEZNANO to "ni http"
        try {
            for (korak in 0 until 5) {
                val p = URL(url).openConnection() as HttpURLConnection
                try {
                    p.connectTimeout = OsPravila.ROK_POVEZAVE_V_ZIVO_MS
                    p.readTimeout = OsPravila.ROK_POVEZAVE_V_ZIVO_MS
                    p.instanceFollowRedirects = false
                    if (agent.isNotBlank()) p.setRequestProperty("User-Agent", agent)
                    for ((k, v) in glave) p.setRequestProperty(k, v)
                    val koda = p.responseCode
                    if (koda !in 300..399) return OsPravila.izidPoOdgovoru(koda) to "HTTP $koda po $korak preusmeritvah"
                    // Preusmeritev (tudi med http in https, kot pri predvajanju): sledimo ji sami.
                    val naprej = p.getHeaderField("Location").orEmpty()
                    if (naprej.isBlank()) return OsPravila.IzidSonde.NEZNANO to "preusmeritev brez cilja"
                    url = URL(URL(url), naprej).toString()
                } finally { try { p.disconnect() } catch (_: Throwable) { } }
            }
            return OsPravila.IzidSonde.NEZNANO to "prevec preusmeritev"
        } catch (e: java.net.UnknownHostException) {
            val gostitelj = OsPravila.gostiteljIzNapake(e.message).ifEmpty { try { URL(url).host.orEmpty().lowercase() } catch (_: Exception) { "" } }
            if (gostitelj.isEmpty()) return OsPravila.IzidSonde.NEZNANO to "neznan gostitelj"
            val o = Imenik.obstaja(listOf(gostitelj, Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            return (if (OsPravila.mrtevGostitelj(o[0], o[1])) OsPravila.IzidSonde.MRTEV else OsPravila.IzidSonde.NEZNANO) to
                "imenik ne pozna $gostitelj (ime=${o[0]}, kontrola=${o[1]})"
        } catch (e: java.io.IOException) {
            if (e !is java.net.SocketTimeoutException && e !is java.net.ConnectException && e !is java.net.NoRouteToHostException)
                return OsPravila.IzidSonde.NEZNANO to "napaka ${e.javaClass.simpleName}"
            val o = Imenik.obstaja(listOf(Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            // Naloga 75: zavrnjena povezava ali ni poti ob delujocem omrezju je dokaz (kot doslej); casovna omejitev ni -
            // tok je lahko zacasno nedosegljiv ali geografsko omejen.
            if (e is java.net.SocketTimeoutException) return OsPravila.izidCasovneOmejitve(o[0]) to "rok povezave (kontrola=${o[0]})"
            return (if (OsPravila.mrtevNeodziven(false, o[0])) OsPravila.IzidSonde.MRTEV else OsPravila.IzidSonde.NEZNANO) to
                "${e.javaClass.simpleName} (kontrola=${o[0]})"
        } catch (e: Exception) { return OsPravila.IzidSonde.NEZNANO to "napaka ${e.javaClass.simpleName}" }
    }

    /**
     * Naloga 100: odtis (SHA-256, hex) prvih [bajtov] toka in koliko jih je prebranih; null = ni odgovora 2xx ali napaka.
     * Preusmeritvam sledimo kot [preveriZRazlogom]. Klic iz ozadja (omrezje, do [OsPravila.ROK_POVEZAVE_V_ZIVO_MS] na korak).
     */
    fun odtisZacetka(naslov: String, glave: Map<String, String>, agent: String, bajtov: Int = OsPravila.NADOMESTEK_BAJTOV): Pair<String, Int>? {
        var url = naslov
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            for (korak in 0 until 5) {
                val p = URL(url).openConnection() as HttpURLConnection
                try {
                    p.connectTimeout = OsPravila.ROK_POVEZAVE_V_ZIVO_MS
                    p.readTimeout = OsPravila.ROK_POVEZAVE_V_ZIVO_MS
                    p.instanceFollowRedirects = false
                    if (agent.isNotBlank()) p.setRequestProperty("User-Agent", agent)
                    for ((k, v) in glave) p.setRequestProperty(k, v)
                    val koda = p.responseCode
                    if (koda in 300..399) {
                        val naprej = p.getHeaderField("Location").orEmpty()
                        if (naprej.isBlank()) return null
                        url = URL(URL(url), naprej).toString()
                    } else {
                        if (koda !in 200..299) return null
                        val sha = java.security.MessageDigest.getInstance("SHA-256")
                        val kos = ByteArray(16 * 1024)
                        var skupaj = 0
                        p.inputStream.use { vhod ->
                            var n = vhod.read(kos, 0, minOf(kos.size, bajtov))
                            while (n >= 0) {
                                sha.update(kos, 0, n)
                                skupaj += n
                                n = if (skupaj < bajtov) vhod.read(kos, 0, minOf(kos.size, bajtov - skupaj)) else -1
                            }
                        }
                        return sha.digest().joinToString("") { "%02x".format(it) } to skupaj
                    }
                } finally { try { p.disconnect() } catch (_: Throwable) { } }
            }
            null
        } catch (_: Exception) { null }
    }
}
