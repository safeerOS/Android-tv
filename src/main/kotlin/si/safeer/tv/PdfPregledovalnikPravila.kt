package si.safeer.tv

import java.net.HttpURLConnection
import java.net.URL

object PdfPregledovalnikPravila {

    /**
     * Vzpostavi povezavo za prenos PDF dokumenta. Sledi preusmeritvam po pravilih varnosti.
     * Pravila (1. 11. 2026):
     * - instanceFollowRedirects=false (rocno sledenje),
     * - rocno najvec 5 skokov,
     * - vsak skok samo https (http le, ce je bil ze prvotni http in isti gostitelj),
     * - Cookie in User-Agent na novo za NOV naslov.
     */
    fun vzpostaviPovezavo(
        zacetniUrl: String,
        prvotniUserAgent: String?,
        pridobiPiskotek: (String) -> String?,
        odpriPovezavo: (String) -> HttpURLConnection
    ): HttpURLConnection {
        val najvecSkokov = 5
        var trenutenUrl = zacetniUrl
        val zacetniGostitelj = try { URL(zacetniUrl).host } catch (_: Throwable) { "" }
        val prvotniJeHttp = zacetniUrl.startsWith("http://")

        for (skok in 0..najvecSkokov) {
            val povezava = odpriPovezavo(trenutenUrl)
            povezava.instanceFollowRedirects = false
            povezava.connectTimeout = 15000
            povezava.readTimeout = 60000

            prvotniUserAgent?.let { povezava.setRequestProperty("User-Agent", it) }
            povezava.setRequestProperty("Accept", "application/pdf,*/*")

            try {
                pridobiPiskotek(trenutenUrl)?.takeIf { it.isNotBlank() }?.let {
                    povezava.setRequestProperty("Cookie", it)
                }
            } catch (_: Throwable) { }

            val koda = try { povezava.responseCode } catch (e: Throwable) {
                povezava.disconnect()
                throw e
            }

            if (koda in 300..399) {
                val lokacija = povezava.getHeaderField("Location")
                if (lokacija.isNullOrBlank()) {
                    return povezava // Ce ni lokacije, vrnemo in naj klicoci obravnava
                }

                val novUrl = try { URL(URL(trenutenUrl), lokacija).toString() } catch (e: Throwable) {
                    povezava.disconnect()
                    throw IllegalArgumentException("Neveljavna lokacija preusmeritve: $lokacija")
                }
                
                val novGostitelj = try { URL(novUrl).host } catch (_: Throwable) { "" }

                if (novUrl.startsWith("http://")) {
                    if (!prvotniJeHttp || zacetniGostitelj != novGostitelj) {
                        povezava.disconnect()
                        throw IllegalStateException("Nedovoljen prehod na http")
                    }
                } else if (!novUrl.startsWith("https://")) {
                    povezava.disconnect()
                    throw IllegalStateException("Nedovoljen protokol")
                }

                povezava.disconnect()

                if (skok == najvecSkokov) {
                    throw IllegalStateException("Prevec preusmeritev")
                }
                trenutenUrl = novUrl
            } else {
                return povezava
            }
        }
        throw IllegalStateException("Nedosegljivo")
    }
}
