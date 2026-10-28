package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis (pricakovano=$pricakovano, dobljeno=$dobljeno)") }
}

/** Lastnik 27. 10. 2026: filmi z Useneta prek lastnega ponudnika v Medijskem centru (most UsenetLastni). */
fun main() {
    val L = UsenetLastniPravila
    val P = UsenetPonudnikPravila
    println("== pot toka ==")
    preveriEnako("ponudnik vpisan: lasten pogon (tudi brez izbire)", true, L.lastni(null, true))
    preveriEnako("brez ponudnika: TorBox kot prej", false, L.lastni(null, false))
    preveriEnako("izrecno TorBox (drugi korak): ne lasten", false, L.lastni(false, true))
    preveriEnako("izrecno lasten", true, L.lastni(true, false))
    println("== Usenet v Medijskem centru ==")
    preveriEnako("indeksator + lasten ponudnik brez TorBoxa: da", true, L.naVoljo(true, torBox = false, lastni = true))
    preveriEnako("indeksator + TorBox: da (kot prej)", true, L.naVoljo(true, torBox = true, lastni = false))
    preveriEnako("brez indeksatorja: ne (NZB ni od kod)", false, L.naVoljo(false, torBox = true, lastni = true))
    preveriEnako("indeksator brez poti predvajanja: ne", false, L.naVoljo(true, torBox = false, lastni = false))
    println("== Preveri v oknu (odjemalec Grok -> pravila okna) ==")
    preveriEnako("prijava uspela", P.NNTP_OK, L.prijavaIz(null).izid)
    preveriEnako("zavrnjeno geslo je v fazi prijave", P.FAZA_PRIJAVA, L.prijavaIz(UsenetNntp.Vrsta.PRIJAVA).faza)
    preveriEnako("zavrnjeno geslo + zapah = ne velja", P.NE_VELJA,
        P.izidNapake(L.prijavaIz(UsenetNntp.Vrsta.PRIJAVA).izid, L.prijavaIz(UsenetNntp.Vrsta.PRIJAVA).faza, true))
    preveriEnako("meja povezav = prevec povezav", P.PREVEC_POVEZAV,
        P.izidNapake(L.prijavaIz(UsenetNntp.Vrsta.ZASEDEN).izid, L.prijavaIz(UsenetNntp.Vrsta.ZASEDEN).faza, false))
    preveriEnako("omrezje brez zapaha = ni odgovora", P.NI_ODGOVORA,
        P.izidNapake(L.prijavaIz(UsenetNntp.Vrsta.OMREZJE).izid, L.prijavaIz(UsenetNntp.Vrsta.OMREZJE).faza, false))
    for (v in UsenetNntp.Vrsta.values()) preveriEnako("vsaka napaka ima izid ($v)", true, L.prijavaIz(v).izid.isNotBlank())
    // Album v RAR (vodja 27. 10. 2026): stisnjen in sifriran imata svoje besedilo, ni_rar je izdaja brez skladb.
    preveriEnako("album RAR stisnjen", "stisnjen", L.napakaAlbumaRar("STISNJEN"))
    preveriEnako("album RAR sifrirane glave", "sifriran", L.napakaAlbumaRar("SIFRIRANE_GLAVE"))
    preveriEnako("album brez RAR", "ni_skladb", L.napakaAlbumaRar("NI_RAR"))
    preveriEnako("album RAR neskladno", "rar_neskladno", L.napakaAlbumaRar("NESKLADNO"))
    preveriEnako("okno: stisnjen ima svoje besedilo", true, L.albumStisnjen("stisnjen"))
    preveriEnako("okno: luknja splosno", false, L.albumStisnjen("luknja"))
    // Vodja 27. 10. 2026: film prek lastnega pogona - napis in dnevnik ne smeta reci TorBox.
    preveriEnako("lasten pogon je lastni", true, L.jeLastni(L.PONUDNIK))
    preveriEnako("TorBox ni lastni", false, L.jeLastni(DebridPravila.TORBOX))
    preveriEnako("brez ponudnika ni lastni", false, L.jeLastni(null))
    preveriEnako("dnevnik lasten pogon", "Usenet lasten pogon", L.dnevnikPripravljenega(L.PONUDNIK))
    preveriEnako("dnevnik TorBox", "Usenet pripravljen pri TorBoxu", L.dnevnikPripravljenega(DebridPravila.TORBOX))
    // Lastnik 27. 10. 2026: album z 20 skladbami po 10 min ne sme poteci (prej 5 min mirovanja na skladbo).
    preveriEnako("album: strezniki skladb zivijo vsaj 3 ure", true, L.NEDEJAVNOST_ALBUMA_MS >= 3L * 60 * 60 * 1000)
    // Lastnik 28. 10. 2026: kvota indeksatorja - izdaje, ki niso vec na voljo, in shramba NZB.
    preveriEnako("luknja je trajna (izdaja se skrije)", true, L.trajnaNapakaAlbuma("luknja"))
    preveriEnako("RAR brez podpore je trajen", true, L.trajnaNapakaAlbuma("rar_neznano"))
    preveriEnako("omrezje ni trajno (drugic lahko uspe)", false, L.trajnaNapakaAlbuma("omrezje"))
    preveriEnako("zaseden ponudnik ni trajen", false, L.trajnaNapakaAlbuma("zaseden"))
    preveriEnako("druga izdaja samo pri luknji", true, L.poskusiDrugoIzdajo("luknja"))
    preveriEnako("sifriran: brez druge izdaje (kvota)", false, L.poskusiDrugoIzdajo("sifriran"))
    preveriEnako("na klik najvec 3 prenosi", true, L.NAJVEC_DRUGIH_IZDAJ <= 2)
    val dan = 24L * 60 * 60 * 1000
    preveriEnako("neuspel po 31 dneh spet viden", setOf("a"), L.pocistiNeuspele(mapOf("a" to 40 * dan, "b" to 9 * dan), 41 * dan).keys)
    preveriEnako("neuspelih najvec 300", L.NEUSPELIH_NAJVEC, L.pocistiNeuspele((1..400).associate { "k$it" to it.toLong() }, 500L).size)
    preveriEnako("shramba NZB: najstarejsa gre prva", listOf("star"),
        L.zaBrisanje((1..L.NZB_NAJVEC_DATOTEK).map { Triple("n$it", 10L, 100L + it) } + Triple("star", 10L, 1L)))
    preveriEnako("shramba NZB: v mejah nic ne brise", emptyList<String>(), L.zaBrisanje(listOf(Triple("a", 10L, 1L), Triple("b", 10L, 2L))))
    // Lastnik 27. 10. 2026: napis med pripravo toka.
    preveriEnako("Usenet z lastnim ponudnikom: svoj napis", true, L.pripravaLastnega(usenet = true, lastniVUporabi = true))
    preveriEnako("Usenet prek TorBoxa: napis debrid", false, L.pripravaLastnega(usenet = true, lastniVUporabi = false))
    preveriEnako("torrent: napis debrid", false, L.pripravaLastnega(usenet = false, lastniVUporabi = true))
    println(if (napak == 0) "UsenetLastniPravilaTest: OK" else "UsenetLastniPravilaTest: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
