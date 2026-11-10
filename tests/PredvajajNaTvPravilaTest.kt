package si.safeer.tv.os

fun main() {
    var pr = 0
    fun preveri(vOzjemKrogu: Boolean, polja: Map<String, String?>, pricakovano: si.safeer.tv.os.PredvajajNaTvPravila.Izid) {
        val izid = si.safeer.tv.os.PredvajajNaTvPravila.preveri(vOzjemKrogu, polja)
        check(izid == pricakovano) { "Napaka! Pricakovano $pricakovano, dobil $izid za polja $polja in krog $vOzjemKrogu" }
        pr++
    }

    // a) ni v krogu + veljavno -> NI_V_KROGU
    preveri(false, mapOf("vrsta" to "film", "id" to "tt1234567"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NI_V_KROGU))
    // ni v krogu + neveljavno -> NI_V_KROGU
    preveri(false, mapOf("vrsta" to "nepoznano"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NI_V_KROGU))

    // b) neznana vrsta
    preveri(true, mapOf("vrsta" to "FILM", "id" to "tt1234567"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEZNANA_VRSTA))
    preveri(true, mapOf("vrsta" to "nesto"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEZNANA_VRSTA))
    
    // c) id
    // FILM
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.FILM, "tt1234567", null, null, "", 0L)))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567890"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.FILM, "tt1234567890", null, null, "", 0L)))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567/../x"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567%00"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "film", "id" to "http://x"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt12345678901"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt123456"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt١٢٣٤٥٦٧"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "film", "id" to " tt1234567"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))

    // KANAL
    preveri(true, mapOf("vrsta" to "kanal", "id" to "kanal1"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.KANAL, "kanal1", null, null, "", 0L)))
    preveri(true, mapOf("vrsta" to "kanal", "id" to "a".repeat(64)), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.KANAL, "a".repeat(64), null, null, "", 0L)))
    preveri(true, mapOf("vrsta" to "kanal", "id" to "a".repeat(65)), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "kanal", "id" to ".."), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "kanal", "id" to "a..b"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "kanal", "id" to ".skrit"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))
    preveri(true, mapOf("vrsta" to "kanal", "id" to "a/b"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID))

    // d) EPIZODA
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "1", "epizoda" to "2"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.EPIZODA, "tt1234567", 1, 2, "", 0L)))
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "0", "epizoda" to "9999"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.EPIZODA, "tt1234567", 0, 9999, "", 0L)))
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "1000", "epizoda" to "1"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVNA_EPIZODA))
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "1", "epizoda" to "10000"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVNA_EPIZODA))
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "1", "epizoda" to "0"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVNA_EPIZODA))
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "-1", "epizoda" to "1"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVNA_EPIZODA))
    preveri(true, mapOf("vrsta" to "epizoda", "id" to "tt1234567", "sezona" to "1"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVNA_EPIZODA))

    // e) naslov
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "naslov" to "a".repeat(200)), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.FILM, "tt1234567", null, null, "a".repeat(200), 0L)))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "naslov" to "a".repeat(201)), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.PREDOLGO))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "naslov" to "a\nb\u0000c"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.FILM, "tt1234567", null, null, "abc", 0L)))

    // f) polozajMs
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "polozajMs" to "86400000"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.FILM, "tt1234567", null, null, "", 86400000L)))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "polozajMs" to "86400001"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_POLOZAJ))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "polozajMs" to "-1"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_POLOZAJ))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "polozajMs" to "1e3"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_POLOZAJ))
    preveri(true, mapOf("vrsta" to "film", "id" to "tt1234567", "polozajMs" to "1234567890"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Zavrnjeno(si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_POLOZAJ)) // 10 stevk

    // KANAL polozaj vedno 0
    preveri(true, mapOf("vrsta" to "kanal", "id" to "kanal1", "polozajMs" to "123"), si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.KANAL, "kanal1", null, null, "", 0L)))

    // Krozna pot
    val z1 = si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.FILM, "tt1234567", null, null, "Film", 1234L)
    check(si.safeer.tv.os.PredvajajNaTvPravila.preveri(true, si.safeer.tv.os.PredvajajNaTvPravila.sporocilo(z1)) == si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(z1))
    pr++

    val z2 = si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.EPIZODA, "tt1234567", 1, 2, "Ep", 1234L)
    check(si.safeer.tv.os.PredvajajNaTvPravila.preveri(true, si.safeer.tv.os.PredvajajNaTvPravila.sporocilo(z2)) == si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(z2))
    pr++

    val z3 = si.safeer.tv.os.PredvajajNaTvPravila.Zahteva(si.safeer.tv.os.PredvajajNaTvPravila.Vrsta.KANAL, "kanal1", null, null, "Kan", 0L)
    check(si.safeer.tv.os.PredvajajNaTvPravila.preveri(true, si.safeer.tv.os.PredvajajNaTvPravila.sporocilo(z3)) == si.safeer.tv.os.PredvajajNaTvPravila.Izid.Sprejeto(z3))
    pr++

    // Besedila zavrnitve
    val jeziki = listOf("sl", "en", "de", "es", "fr", "it")
    val zavrnitve = listOf(
        si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NI_V_KROGU,
        si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEZNANA_VRSTA,
        si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_ID,
        si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVNA_EPIZODA,
        si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.PREDOLGO,
        si.safeer.tv.os.PredvajajNaTvPravila.Zavrnitev.NEVELJAVEN_POLOZAJ
    )
    for (r in zavrnitve) {
        for (j in jeziki) {
            check(si.safeer.tv.os.PredvajajNaTvPravila.besediloZavrnitve(r, j).isNotEmpty())
            pr++
        }
    }

    println("PredvajajNaTvPravilaTest: OK ($pr preverb)")
}
