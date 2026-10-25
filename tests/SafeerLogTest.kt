package si.safeer.tv.cast

/**
 * Dnevnik brez skrivnosti (cast/SafeerLog) - pregled Webtor rezerve U7: sporocilo izjeme HttpURLConnection (npr.
 * FileNotFoundException ob 404) je cel naslov; zeton v poti in kljuc debrid v nastavitvah dodatka ne gresta v dnevnik.
 */
fun main() {
    val zapisano = ArrayList<String>()
    val prej = SafeerLog.izhod
    SafeerLog.izhod = { zapisano.add(it) }
    SafeerLog.napaka("Preizkus", "dodatek", java.io.FileNotFoundException("https://webtor.io/token/kratek123/stremio/stream/movie/x.json"))
    SafeerLog.napaka("Preizkus", "torrentio", java.io.IOException("https://torrentio.strem.fun/providers=yts|torbox=tbk9876|sort=qs/manifest.json"))
    SafeerLog.izhod = prej
    val vse = zapisano.joinToString("\n")
    check(zapisano.size == 2) { "zapisa nista dva" }
    check(!vse.contains("kratek123") && vse.contains("/token/***")) { "zeton v poti v dnevniku: $vse" }
    check(!vse.contains("tbk9876") && vse.contains("torbox=***")) { "kljuc debrid v dnevniku: $vse" }
    check(vse.contains("FileNotFoundException") && vse.contains("webtor.io")) { "vrsta napake in gostitelj morata ostati" }
    println("SafeerLogTest: OK")
}
