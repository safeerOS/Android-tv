package si.safeer.tv.os

private var napak = 0

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis")
    else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

/** Lastnik 13. 11. 2026: gumb za preskok uvoda, povzetka, odjavne spice (TheIntroDB). */
fun main() {
    val P = PreskokPravila
    // Naslov poizvedbe: samo veljaven IMDb id, epizoda s sezono, film brez.
    enako("epizoda", "https://api.theintrodb.org/v4/media?imdb_id=tt0903747&season=1&episode=2", P.naslov("tt0903747", 1, 2))
    enako("film", "https://api.theintrodb.org/v4/media?imdb_id=tt0133093", P.naslov("tt0133093", 0, 0))
    enako("neveljaven id (vbrizgan parameter)", null, P.naslov("tt0133093&x=1", 0, 0))
    enako("neveljaven id (pot)", null, P.naslov("../tt0133093", 0, 0))
    enako("prazen id", null, P.naslov("", 1, 1))
    enako("negativna epizoda", null, P.naslov("tt0903747", 1, -1))
    enako("sezona brez epizode", null, P.naslov("tt0903747", 1, 0))
    enako("posebna epizoda (sezona 0)", "https://api.theintrodb.org/v4/media?imdb_id=tt0903747&season=0&episode=3", P.naslov("tt0903747", 0, 3))
    enako("v zivo: brez poizvedbe", null, P.poizvedba(true, true, true, "tt0903747", 1, 2))
    enako("izklopljeno: brez poizvedbe", null, P.poizvedba(false, true, false, "tt0903747", 1, 2))
    enako("zvok: brez poizvedbe", null, P.poizvedba(true, false, false, "tt0903747", 1, 2))

    // Razclenitev: pravi odgovor (13. 11. 2026, Breaking Bad 1x2).
    val bb = """{"tvdb_id":81189,"type":"tv","season":1,"episode":2,"intro":[{"start_ms":314551,"end_ms":331127}],"credits":[{"start_ms":2848149,"end_ms":null}]}"""
    val o = P.razcleni(bb)
    enako("dva odseka", 2, o.size)
    enako("uvod", PreskokPravila.Odsek(PreskokPravila.Vrsta.UVOD, 314551, 331127), o[0])
    enako("odjavna do konca", PreskokPravila.Odsek(PreskokPravila.Vrsta.ODJAVNA, 2848149, null), o[1])
    enako("uvod od zacetka (start null)", 0L, P.razcleni("""{"intro":[{"start_ms":null,"end_ms":40000}]}""").single().odMs)
    enako("uvod brez konca ni veljaven", 0, P.razcleni("""{"intro":[{"start_ms":1000,"end_ms":null}]}""").size)
    enako("konec pred zacetkom ni veljaven", 0, P.razcleni("""{"recap":[{"start_ms":5000,"end_ms":4000}]}""").size)
    enako("negativen cas ni veljaven", 0, P.razcleni("""{"intro":[{"start_ms":-5,"end_ms":40000}]}""").size)
    enako("prevelik cas ni veljaven", 0, P.razcleni("""{"intro":[{"start_ms":0,"end_ms":99999999999}]}""").size)
    enako("napaka API", 0, P.razcleni("""{"error":"Invalid imdb_id format"}""").size)
    enako("prevelik odgovor", 0, P.razcleni("""{"intro":[{"start_ms":0,"end_ms":40000}]}""" + " ".repeat(PreskokPravila.NAJVEC_BAJTOV)).size)
    enako("vec povzetkov", 2, P.razcleni("""{"recap":[{"start_ms":0,"end_ms":30000},{"start_ms":60000,"end_ms":90000}]}""").size)

    // Kdaj gumb in kam skoci.
    val tr = 2_900_000L
    enako("pred uvodom ni gumba", null, P.aktiven(o, 300_000, tr))
    enako("med uvodom gumb", PreskokPravila.Vrsta.UVOD, P.aktiven(o, 320_000, tr)?.vrsta)
    enako("1,5 s pred koncem uvoda gumb izgine", null, P.aktiven(o, 331127 - 1_500, tr))
    enako("med odjavno gumb", PreskokPravila.Vrsta.ODJAVNA, P.aktiven(o, 2_860_000, tr)?.vrsta)
    enako("odjavna do konca brez trajanja ne", null, P.aktiven(o, 2_860_000, 0))
    enako("cilj uvoda je konec uvoda", 331127L, P.cilj(o[0], tr))
    enako("cilj odjavne: zadnja sekunda (sprozi naslednjo epizodo)", tr - 1_000, P.cilj(o[1], tr))
    enako("prekratek odsek se ne ponudi", null, P.aktiven(listOf(PreskokPravila.Odsek(PreskokPravila.Vrsta.UVOD, 0, 3_000)), 1_000, tr))
    enako("odsek cez konec posnetka je omejen", 9_000L, P.cilj(PreskokPravila.Odsek(PreskokPravila.Vrsta.ODJAVNA, 1_000, 99_000), 10_000))

    // N1: pokvarjeni vnosi iz skupnostne baze ne zasedejo OK za ves film.
    val U = PreskokPravila.Vrsta.UVOD; val O = PreskokPravila.Vrsta.ODJAVNA
    enako("uvod 0..24 h zavrzen", null, P.aktiven(listOf(PreskokPravila.Odsek(U, 0, 86_400_000)), 60_000, tr))
    enako("uvod 6 min zavrzen", null, P.aktiven(listOf(PreskokPravila.Odsek(U, 0, 360_000)), 60_000, tr))
    enako("uvod po 45 min (vec kot tretjina 2 h filma) zavrzen", null, P.aktiven(listOf(PreskokPravila.Odsek(U, 2_700_000, 2_760_000)), 2_710_000, 7_200_000))
    enako("uvod po 30 min v 2 h filmu (prva tretjina) velja", true, P.smiseln(PreskokPravila.Odsek(U, 1_800_000, 1_860_000), 7_200_000))
    enako("odjavna od zacetka zavrzena", null, P.aktiven(listOf(PreskokPravila.Odsek(O, 0, null)), 60_000, tr))
    enako("odjavna brez trajanja zavrzena", false, P.smiseln(PreskokPravila.Odsek(O, 2_000_000, 2_100_000), 0))
    enako("pravi uvod (5 min 14 s) velja", true, P.smiseln(o[0], tr))
    // N2: gumb najvec 15 s od vstopa v odsek.
    enako("gumb viden po 14 s", true, P.gumbViden(1_000, 15_000))
    enako("gumb skrit po 15 s", false, P.gumbViden(1_000, 16_000))

    // Slepi pregled 13. 11. 2026 (C4): odjavna sredi filma bi prevzela OK - zacne v zadnji cetrtini in sega do konca.
    val T = 6_000_000L
    enako("odjavna sredi filma (55-60 %) zavrzena", false, P.smiseln(PreskokPravila.Odsek(O, 3_300_000, 3_600_000), T))
    enako("odjavna 80-85 % (ne sega do konca) zavrzena", false, P.smiseln(PreskokPravila.Odsek(O, 4_800_000, 5_100_000), T))
    enako("odjavna od 80 % do konca velja", true, P.smiseln(PreskokPravila.Odsek(O, 4_800_000, null), T))
    enako("napoved 95 % do konca velja", true, P.smiseln(PreskokPravila.Odsek(PreskokPravila.Vrsta.NAPOVED, 5_700_000, T), T))
    val petUvodov = (0 until 5).joinToString(",") { "{\"start_ms\":${it * 100_000},\"end_ms\":${it * 100_000 + 30_000}}" }
    enako("najvec dva odseka iste vrste", 2, P.razcleni("{\"intro\":[$petUvodov]}").size)
    val smetiNaZacetku = "{\"intro\":[{\"start_ms\":-5},{\"start_ms\":\"x\"},{\"start_ms\":1000,\"end_ms\":61000}]}"
    enako("neveljavni vnosi na zacetku ne skrijejo veljavnega", 1, P.razcleni(smetiNaZacetku).size)

    if (napak > 0) { println("PreskokPravila: $napak napak"); kotlin.system.exitProcess(1) }
    println("PreskokPravila: vse ok")
}
