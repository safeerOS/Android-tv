package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 130: serije iz katalogov z lastnimi id-ji ([SerijePravila]). */
fun main() {
    println("Preizkus pravil serij")
    preveriEnako("id IMDb", true, SerijePravila.jeImdb("tt0903747"))
    preveriEnako("tmdb ni IMDb", false, SerijePravila.jeImdb("tmdb:1396"))
    preveriEnako("samo tt ni IMDb", false, SerijePravila.jeImdb("tt"))
    preveriEnako("null ni IMDb", false, SerijePravila.jeImdb(null))
    preveriEnako("imdb_id ima prednost", "tt0903747", SerijePravila.imdbIzMeta("tt0903747", "tmdb:1396"))
    preveriEnako("brez imdb_id: id sam", "tt7631058", SerijePravila.imdbIzMeta("", "tt7631058"))
    preveriEnako("brez obojega null", null, SerijePravila.imdbIzMeta("null", "tmdb:1396"))
    preveriEnako("epizoda po IMDb", "tt0903747:1:2", SerijePravila.idEpizodePoImdb("tt0903747", "tmdb:1396:1:2", 1, 2))
    preveriEnako("id je ze ta - ni potreben", null, SerijePravila.idEpizodePoImdb("tt0903747", "tt0903747:1:2", 1, 2))
    preveriEnako("sezona 0 ne", null, SerijePravila.idEpizodePoImdb("tt0903747", "x", 0, 1))
    preveriEnako("brez IMDb ne", null, SerijePravila.idEpizodePoImdb(null, "x", 1, 1))
    preveriEnako("JSON null ni ime", "", SerijePravila.niz("null"))
    preveriEnako("ime ostane", "Torrentio", SerijePravila.niz("Torrentio"))
    // Naloga 132: epizoda izgine samo, ce je ni pri dodatku IN po IMDb
    preveriEnako("132: ni pri dodatku, po IMDb je -> ostane", false, SerijePravila.skrijEpizodo(false, true, true))
    preveriEnako("132: ni pri dodatku, po IMDb se ne vemo -> ostane", false, SerijePravila.skrijEpizodo(false, null, true))
    preveriEnako("132: ni nikjer -> skrij", true, SerijePravila.skrijEpizodo(false, false, true))
    preveriEnako("132: brez IMDb kot prej: ni pri dodatku -> skrij", true, SerijePravila.skrijEpizodo(false, null, false))
    preveriEnako("132: pri dodatku je -> ostane", false, SerijePravila.skrijEpizodo(true, false, true))
    preveriEnako("132: se ne vemo -> ostane", false, SerijePravila.skrijEpizodo(null, null, true))
    preveriEnako("132: preveri po IMDb, ko pri dodatku ni", true, SerijePravila.preveriPoImdb(false, null, true))
    preveriEnako("132: preveri po IMDb, ko pri dodatku se ne vemo", true, SerijePravila.preveriPoImdb(null, null, true))
    preveriEnako("132: pri dodatku je - ne sprasujemo vec", false, SerijePravila.preveriPoImdb(true, null, true))
    preveriEnako("132: IMDb ze znan - ne sprasujemo znova", false, SerijePravila.preveriPoImdb(false, false, true))
    preveriEnako("132: brez IMDb ni preverbe", false, SerijePravila.preveriPoImdb(false, null, false))

    // Naloga 140: »Nadaljuj« pri epizodi nadaljuje epizodo, ne odpre seznama sezon
    preveriEnako("140: S1E1 iz naslova", 1 to 1, SerijePravila.epizodaIzNaslova("Friends · S1E1 · The One Where Monica Gets A Roommate"))
    preveriEnako("140: S0E5 (posebna epizoda)", 0 to 5, SerijePravila.epizodaIzNaslova("Friends · S0E5 · Friends on The Tonight Show"))
    preveriEnako("140: S12E103", 12 to 103, SerijePravila.epizodaIzNaslova("Serija · S12E103 · Ime"))
    preveriEnako("140: film nima epizode", null, SerijePravila.epizodaIzNaslova("Inception"))
    preveriEnako("140: »S1E1« sredi besede ni epizoda", null, SerijePravila.epizodaIzNaslova("BOS1E1X"))
    preveriEnako("140: shranjena sezona in epizoda", 2 to 8, SerijePravila.epizodaZaNadaljevanje("series", 2, 8, "Rings · S9E9 · x"))
    preveriEnako("140: stari zapis brez sezone - iz naslova", 1 to 1, SerijePravila.epizodaZaNadaljevanje("series", 0, 0, "Friends · S1E1 · x"))
    preveriEnako("140: serija brez epizode (kartica serije) - ni nadaljevanja", null, SerijePravila.epizodaZaNadaljevanje("series", 0, 0, "Friends"))
    preveriEnako("140: film - ni nadaljevanja epizode", null, SerijePravila.epizodaZaNadaljevanje("movie", 0, 0, "Film · S1E1"))
    preveriEnako("140: brez tipa", null, SerijePravila.epizodaZaNadaljevanje(null, 1, 1, "x"))

    if (napak == 0) println("SerijePravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
