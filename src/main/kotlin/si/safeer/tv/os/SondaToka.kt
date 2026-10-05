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
    fun dosegljiv(naslov: String, glave: Map<String, String>, agent: String): Boolean? {
        var url = naslov
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
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
                    if (koda !in 300..399) return OsPravila.sondaPoOdgovoru(koda)
                    // Preusmeritev (tudi med http in https, kot pri predvajanju): sledimo ji sami.
                    val naprej = p.getHeaderField("Location").orEmpty()
                    if (naprej.isBlank()) return null
                    url = URL(URL(url), naprej).toString()
                } finally { try { p.disconnect() } catch (_: Throwable) { } }
            }
            return null
        } catch (e: java.net.UnknownHostException) {
            val gostitelj = OsPravila.gostiteljIzNapake(e.message).ifEmpty { try { URL(url).host.orEmpty().lowercase() } catch (_: Exception) { "" } }
            if (gostitelj.isEmpty()) return null
            val o = Imenik.obstaja(listOf(gostitelj, Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            return if (OsPravila.mrtevGostitelj(o[0], o[1])) false else null
        } catch (e: java.io.IOException) {
            if (e !is java.net.SocketTimeoutException && e !is java.net.ConnectException && e !is java.net.NoRouteToHostException) return null
            val o = Imenik.obstaja(listOf(Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            return if (OsPravila.mrtevNeodziven(false, o[0])) false else null
        } catch (_: Exception) { return null }
    }
}
