package si.safeer.tv.sdk

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64

/**
 * Primer dodatka po protokolu Safeer v1 (docs/DODATKI-PROTOKOL.md) - za razvijalce kot zacetek in za nase preizkuse kot
 * lazni dodatek (tests/DodatkiSdkTest.kt). Samo JDK (com.sun.net.httpserver), brez knjiznic.
 *
 * Kaj pokaze: dodatek Stremio (manifest, catalog, meta, stream) z razsiritvami `safeer`: dejanje »Dodaj na moj seznam«,
 * dopolnitev (enrich), mapo »Moj seznam« in nastavitve (kljuc API kot skrivnost, kakovost). Nastavitve pridejo v glavi
 * `X-Safeer-Config` (base64url JSON). Za preizkuse ima se kanal, ki da samo placilni zid - Safeer ga skrije.
 *
 *   sdk/zazeni_primer.sh 7000      -> http://<ip racunalnika>:7000/manifest.json (Safeer: razvojni nacin, Dodaj dodatek)
 */
class PrimerDodatka(vrata: Int = 0) {
    private val streznik: HttpServer = HttpServer.create(InetSocketAddress("0.0.0.0", vrata), 0)
    val vrata: Int get() = streznik.address.port

    /** Moj seznam (dejanje ga polni) - v pomnilniku, samo za primer. */
    private val seznam = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Zadnja prejeta glava X-Safeer-Config (dekodirana) - preizkusi preverijo, kaj je Safeer poslal. */
    @Volatile var zadnjaNastavitev: String = ""
        private set

    init {
        streznik.createContext("/") { x -> try { odgovori(x) } catch (e: Exception) { poslji(x, 500, """{"error":"server"}""") } }
    }

    fun zazeni(): PrimerDodatka { streznik.start(); return this }
    fun ustavi() = streznik.stop(0)

    private fun poslji(x: HttpExchange, koda: Int, json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        x.responseHeaders.add("Access-Control-Allow-Origin", "*")
        x.sendResponseHeaders(koda, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }

    /** Niz za vstavljanje v JSON (id iz poti lahko vsebuje narekovaje). */
    private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").filter { it >= ' ' }

    private fun nastavitve(x: HttpExchange): String {
        val g = x.requestHeaders.getFirst("X-Safeer-Config") ?: return ""
        return try { String(Base64.getUrlDecoder().decode(g), Charsets.UTF_8) } catch (_: Exception) { "" }
    }

    private fun odgovori(x: HttpExchange) {
        zadnjaNastavitev = nastavitve(x)
        val pot = URLDecoder.decode(x.requestURI.rawPath, "UTF-8")
        when {
            pot == "/manifest.json" -> poslji(x, 200, MANIFEST)
            pot == "/catalog/movie/primer.json" -> poslji(x, 200, """{"metas":[
                {"id":"primer:film1","type":"movie","name":"Big Buck Bunny","poster":"https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg"},
                {"id":"primer:placljiv","type":"movie","name":"Premium film"}]}""")
            pot == "/meta/movie/primer:film1.json" -> poslji(x, 200, """{"meta":{"id":"primer:film1","type":"movie","name":"Big Buck Bunny","releaseInfo":"2008"}}""")
            pot == "/stream/movie/primer:film1.json" -> poslji(x, 200, """{"streams":[{"name":"Primer","title":"360p, 10 s",
                "url":"https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"}]}""")
            // Samo placilni zid (naloga 120): Safeer to vsebino skrije, orodje za preverjanje opozori.
            pot == "/stream/movie/primer:placljiv.json" -> poslji(x, 200, """{"streams":[{"name":"Primer","title":"Click to Unlock Premium Access",
                "externalUrl":"https://example.com/premium"}]}""")
            pot.startsWith("/safeer/action/add-to-list/") -> {
                val id = pot.removePrefix("/safeer/action/add-to-list/").substringAfter('/').removeSuffix(".json")
                if (id !in seznam) seznam += id
                // Primer, kako dodatek vidi nastavitve: kljuc API pove samo, ali je bil poslan (nikoli vrednosti nazaj).
                val kljuc = if (zadnjaNastavitev.contains("\"api_key\"")) "z" else "brez"
                poslji(x, 200, """{"ok":true,"message":{"en":"Added ($kljuc API key)","sl":"Dodano ($kljuc kljuca API)"},"refresh":false}""")
            }
            pot.startsWith("/safeer/enrich/movie/") -> poslji(x, 200,
                """{"meta":{"imdbRating":"7.2","language":"en","cast":["Bunny"],"description":"A giant rabbit and three bullies."}}""")
            pot == "/safeer/folder/my-list.json" -> poslji(x, 200, """{"items":[{"kind":"folder","path":"all","name":"All"}]}""")
            pot == "/safeer/folder/my-list/all.json" -> poslji(x, 200, """{"items":[""" +
                seznam.joinToString(",") { val v = json(it); """{"kind":"meta","type":"movie","id":"$v","name":"$v"}""" } + "]}")
            else -> poslji(x, 404, """{"error":"not found"}""")
        }
    }

    companion object {
        val MANIFEST = """{
  "id": "si.safeer.primer", "version": "1.0.0", "name": "Safeer primer dodatka",
  "description": "Example add-on for the Safeer Add-on Protocol v1",
  "resources": ["catalog", "meta", "stream"], "types": ["movie"], "idPrefixes": ["primer:"],
  "catalogs": [{ "type": "movie", "id": "primer", "name": "Primer" }],
  "safeer": {
    "protocol": 1,
    "actions": [{ "id": "add-to-list", "on": ["movie"], "icon": "plus",
                  "label": { "en": "Add to my list", "sl": "Dodaj na moj seznam" } }],
    "enrich": ["movie"],
    "folders": [{ "id": "my-list", "name": { "en": "My list", "sl": "Moj seznam" } }],
    "settings": [
      { "key": "api_key", "type": "secret", "label": { "en": "API key", "sl": "Kljuc API" } },
      { "key": "quality", "type": "choice", "options": ["auto", "1080p", "720p"], "default": "auto",
        "label": { "en": "Quality", "sl": "Kakovost" } }
    ],
    "changelog": { "1.0.0": { "en": "First version", "sl": "Prva razlicica" } }
  }
}"""
    }
}

fun main(args: Array<String>) {
    val d = PrimerDodatka(args.firstOrNull()?.toIntOrNull() ?: 7000).zazeni()
    println("Primer dodatka tece: http://<ip tega racunalnika>:${d.vrata}/manifest.json (Ctrl+C za konec)")
}
