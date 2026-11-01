package si.safeer.tv

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

private var napak = 0

private fun preveri(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus pravil preusmeritve za PDF")
    val streznik = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    
    streznik.createContext("/prva") { x ->
        x.responseHeaders.add("Location", "/druga")
        x.responseHeaders.add("Set-Cookie", "c=1")
        x.sendResponseHeaders(302, -1)
        x.close()
    }
    
    val stetje = AtomicInteger()
    streznik.createContext("/neskoncno") { x ->
        x.responseHeaders.add("Location", "/neskoncno?i=${stetje.incrementAndGet()}")
        x.sendResponseHeaders(302, -1)
        x.close()
    }
    
    var zadnjiPiskotek = ""
    var zadnjiUA = ""
    streznik.createContext("/druga") { x ->
        zadnjiPiskotek = x.requestHeaders.getFirst("Cookie") ?: ""
        zadnjiUA = x.requestHeaders.getFirst("User-Agent") ?: ""
        val b = "pdf".toByteArray()
        x.sendResponseHeaders(200, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }

    streznik.createContext("/http_nedovoljen") { x ->
        x.responseHeaders.add("Location", "http://drug.streznik.test/pdf")
        x.sendResponseHeaders(302, -1)
        x.close()
    }
    
    streznik.start()
    val vrata = streznik.address.port
    
    try {
        // Pravilo: Sledenje do /druga
        val p = PdfPregledovalnikPravila.vzpostaviPovezavo(
            zacetniUrl = "http://127.0.0.1:$vrata/prva",
            prvotniUserAgent = "TestUA",
            pridobiPiskotek = { url -> if (url.contains("druga")) "piskotek_za_druga=1" else null },
            odpriPovezavo = { url -> URL(url).openConnection() as HttpURLConnection }
        )
        preveri("Preusmeritev na /druga uspesna", 200, p.responseCode)
        preveri("Piskotek za drugi naslov podan", "piskotek_za_druga=1", zadnjiPiskotek)
        preveri("User-Agent prenasa naprej", "TestUA", zadnjiUA)
        p.disconnect()
        
        // Prevec preusmeritev
        val prevec = try {
            PdfPregledovalnikPravila.vzpostaviPovezavo(
                zacetniUrl = "http://127.0.0.1:$vrata/neskoncno",
                prvotniUserAgent = null,
                pridobiPiskotek = { null },
                odpriPovezavo = { url -> URL(url).openConnection() as HttpURLConnection }
            )
            "ni napake"
        } catch (e: IllegalStateException) {
            e.message
        }
        preveri("Zavrne pri vec kot 5 skokih", "Prevec preusmeritev", prevec)
        
        // Nedovoljen prehod na http
        val httpNedovoljen = try {
            PdfPregledovalnikPravila.vzpostaviPovezavo(
                zacetniUrl = "https://pdf.example.com/", // zacetni je https, mi pa gremo simulirano na /http_nedovoljen
                prvotniUserAgent = null,
                pridobiPiskotek = { null },
                odpriPovezavo = { _ -> 
                    // Simulirajmo odziv prve zahteve kot preusmeritev na http
                    val simStreznik = URL("http://127.0.0.1:$vrata/http_nedovoljen").openConnection() as HttpURLConnection
                    simStreznik 
                }
            )
            "ni napake"
        } catch (e: IllegalStateException) {
            e.message
        }
        preveri("Nedovoljen prehod na http", "Nedovoljen prehod na http", httpNedovoljen)

    } finally {
        streznik.stop(0)
    }
    
    if (napak == 0) println("PdfPregledovalnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
