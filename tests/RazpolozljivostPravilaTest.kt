package si.safeer.tv.os

import si.safeer.tv.os.RazpolozljivostPravila as P

/** Lastna knjiznica dodatka: kartice iz njegovega kataloga, ki jih ne preverjamo vnaprej. */
fun lastnaKnjiznicaTest() {
    val p = P
    val tokovi = setOf("catalog", "meta", "stream")
    // Dodatek s svojimi id-ji, ki zanje sam daje tokove: njegov katalog je njegova knjiznica.
    check(p.lastnaKnjiznica(false, tokovi, setOf("movie"), listOf("moj:"), "movie", "moj:123"))
    check(p.lastnaKnjiznica(false, tokovi, emptySet(), emptyList(), "series", "abc"))
    check(p.lastnaKnjiznica(false, setOf("stream"), setOf("movie", "series"), listOf("x", "moj"), "series", "moj-7"))
    // Javni id (IMDb): katalog le nasteva znane naslove - preverimo kot doslej, tudi ce dodatek daje tokove.
    check(!p.lastnaKnjiznica(true, tokovi, setOf("movie"), listOf("tt"), "movie", "tt0111161"))
    // Dodatek brez tokov (samo katalog), drug tip, druga predpona, prazen id.
    check(!p.lastnaKnjiznica(false, setOf("catalog", "meta"), setOf("movie"), listOf("moj:"), "movie", "moj:123"))
    check(!p.lastnaKnjiznica(false, tokovi, setOf("series"), listOf("moj:"), "movie", "moj:123"))
    check(!p.lastnaKnjiznica(false, tokovi, setOf("movie"), listOf("drug:"), "movie", "moj:123"))
    check(!p.lastnaKnjiznica(false, tokovi, emptySet(), emptyList(), "movie", ""))
    check(!p.lastnaKnjiznica(false, emptySet(), emptySet(), emptyList(), "movie", "moj:1"))
}

/** Zaupanje v lastno knjiznico dodatka: na zaslon pride sele po dokazu, da dodatek zanjo res da tokove. */
fun zaupanjeKnjizniceTest() {
    val ura = 3_600_000L
    val zdaj = 1_800_000_000_000L
    // Vzorec: prvi, srednji in zadnji naslov; v majhni knjiznici vsi.
    check(P.vzorecKnjiznice(0).isEmpty() && P.vzorecKnjiznice(1) == listOf(0) && P.vzorecKnjiznice(3) == listOf(0, 1, 2))
    check(P.vzorecKnjiznice(100) == listOf(0, 50, 99) && P.vzorecKnjiznice(4) == listOf(0, 2, 3))
    // Izid vzorca: en predvajljiv naslov zadosca; »ne« sele, ko je dodatek odgovoril za vse; brez odgovora ne vemo.
    check(P.izidVzorca(listOf(false, true)) == true && P.izidVzorca(listOf(true)) == true)
    check(P.izidVzorca(listOf(false, false, false)) == false && P.izidVzorca(listOf(false)) == false)
    check(P.izidVzorca(emptyList()) == null && P.izidVzorca(listOf(false, null)) == null && P.izidVzorca(listOf(null)) == null)
    // Neznana knjiznica: ne vemo, treba jo je vzorciti.
    check(P.zaupanje(null, zdaj) == null && P.vzorciti(null, zdaj))
    // Dokaz »dela«: svez en dan; za prikaz velja naprej (vzorcimo znova v ozadju), po 30 dneh ne vemo vec.
    val dela = P.poVzorcu(true, zdaj)
    check(P.zaupanje(dela, zdaj) == true && !P.vzorciti(dela, zdaj + 23 * ura))
    check(P.zaupanje(dela, zdaj + 25 * ura) == true && P.vzorciti(dela, zdaj + 25 * ura))
    check(P.zaupanje(dela, zdaj + 31 * 24 * ura) == null)
    // Dokaz »ne dela«: svez pol dneva, potem vzorcimo znova; za prikaz velja do tedna.
    val neDela = P.poVzorcu(false, zdaj)
    check(P.zaupanje(neDela, zdaj) == false && !P.vzorciti(neDela, zdaj + 11 * ura) && P.vzorciti(neDela, zdaj + 13 * ura))
    check(P.zaupanje(neDela, zdaj + 6 * 24 * ura) == false && P.zaupanje(neDela, zdaj + 8 * 24 * ura) == null)
    // Dotik: uspeh potrdi (tudi po »ne dela«); en ali dva dotika brez toka zaupanja ne odvzameta, trije zaporedni ga.
    check(P.zaupanje(P.poDotiku(neDela, true, zdaj + ura), zdaj + ura) == true)
    val en = P.poDotiku(dela, false, zdaj + ura)
    check(P.zaupanje(en, zdaj + ura) == true && en.neuspehov == 1 && en.cas == zdaj) { "en dotik brez toka: dokaz ostane, star kolikor je" }
    val dva = P.poDotiku(en, false, zdaj + 2 * ura)
    check(P.zaupanje(dva, zdaj + 2 * ura) == true)
    val trije = P.poDotiku(dva, false, zdaj + 3 * ura)
    check(P.zaupanje(trije, zdaj + 3 * ura) == false && trije.cas == zdaj + 3 * ura)
    check(P.poDotiku(dva, true, zdaj + 3 * ura).neuspehov == 0) { "uspeh pretrga niz dotikov brez toka" }
    // Neznana knjiznica in dotiki brez toka: ne vemo, dokler niso trije.
    val n1 = P.poDotiku(null, false, zdaj)
    check(P.zaupanje(n1, zdaj) == null && P.vzorciti(n1, zdaj))
    check(P.zaupanje(P.poDotiku(P.poDotiku(n1, false, zdaj), false, zdaj), zdaj) == false)
    // Zapis v niz in nazaj; pokvarjen zapis je neznan.
    for (z in listOf(dela, neDela, en, n1)) check(P.zaupanjeIzNiza(P.zaupanjeVNiz(z)) == z) { P.zaupanjeVNiz(z) }
    for (slab in listOf(null, "", "x;1;2", "d;-1;5", "d;0", "d;0;-3", "d;0;1;2")) check(P.zaupanjeIzNiza(slab) == null) { slab.toString() }
    // Ura naprave nazaj (dokaz »iz prihodnosti« za vec kot uro): ne velja.
    check(P.zaupanje(dela, zdaj - 2 * ura) == null && P.zaupanje(dela, zdaj - ura / 2) == true)
}

/** Pravila razpolozljivosti: meje po zmoznosti naprave, svezina, zdruzevanje, stari zapisi, sprememba dodatkov. */
fun main() {
    lastnaKnjiznicaTest()
    zaupanjeKnjizniceTest()
    val ura = 3_600_000L
    val zdaj = 1_800_000_000_000L
    val gb = 1024L * 1024 * 1024

    // ---- kaj tok zahteva od naprave (isto pravilo kot Stremio.torrentGre)
    check(P.potreba(neposreden = true, torrent = false, gb = 0.0) == 0L)
    check(P.potreba(neposreden = false, torrent = true, gb = 2.0) == 2 * gb)
    check(P.potreba(neposreden = false, torrent = true, gb = 0.0) == P.VSE) { "torrent neznane velikosti: samo naprava, ki zmore vsakega" }
    check(P.potreba(neposreden = false, torrent = false, gb = 1.0) == null) { "zunanja povezava ni predvajanje" }

    // ---- izid poizvedbe
    val neposreden = P.izTokov(listOf(5 * gb, 0L), vsiOdgovorili = false)!!
    check(neposreden == P.Izid(jeOd = 0L) && neposreden.velja(0L) == true && neposreden.velja(P.VSE) == true)
    val torrent = P.izTokov(listOf(5 * gb, 2 * gb), vsiOdgovorili = true)!!
    check(torrent.velja(P.VSE) == true && torrent.velja(2 * gb) == true && torrent.velja(2 * gb - 1) == false && torrent.velja(0L) == false) { torrent }
    val delni = P.izTokov(listOf(2 * gb), vsiOdgovorili = false)!!
    check(delni.velja(3 * gb) == true && delni.velja(gb) == null) { "delni odgovor: kjer ne zadosca, ne vemo (drug dodatek ima morda neposreden tok)" }
    check(P.izTokov(emptyList(), vsiOdgovorili = true) == P.Izid(niDo = P.VSE))
    check(P.izTokov(emptyList(), vsiOdgovorili = true)!!.velja(P.VSE) == false)
    check(P.izTokov(emptyList(), vsiOdgovorili = false) == null) { "brez tokov in brez vseh odgovorov ne sklepamo nicesar" }
    val neznanaVelikost = P.izTokov(listOf(P.VSE), vsiOdgovorili = true)!!
    check(neznanaVelikost.velja(P.VSE) == true && neznanaVelikost.velja(50 * gb) == false)

    // ---- zapis: svez, zastarel (se znan za prikaz), pozabljen
    val je = P.zapisi(null, neposreden, zdaj)
    check(P.stanje(je, 0L, zdaj) == true && P.stanje(je, P.VSE, zdaj + P.JE_VELJA) == true)
    check(P.stanje(je, 0L, zdaj + P.JE_VELJA + 1) == null) { "po tednu ni vec svez" }
    check(P.znano(je, 0L, zdaj + P.JE_VELJA + 1) == true) { "a ostane na zaslonu, medtem ko ga preverjamo" }
    check(P.znano(je, 0L, zdaj + P.JE_ZNANO + 1) == null)
    val ni = P.zapisi(null, P.Izid(niDo = P.VSE), zdaj)
    check(P.stanje(ni, P.VSE, zdaj + P.NI_VELJA) == false && P.stanje(ni, P.VSE, zdaj + P.NI_VELJA + 1) == null)
    check(P.znano(ni, P.VSE, zdaj + P.NI_VELJA + 1) == false) { "zastarel »ni« ostane skrit - seznam nanj ne caka" }
    check(P.znano(ni, P.VSE, zdaj + P.NI_ZNANO + 1) == null)
    check(P.stanje(null, P.VSE, zdaj) == null && P.znano(null, 0L, zdaj) == null)
    // Ura naprav ni povsem enaka: malo iz prihodnosti je svez, vec kot uro ni verodostojen.
    check(P.stanje(je, 0L, zdaj - 10 * 60_000L) == true && P.stanje(je, 0L, zdaj - 2 * ura) == null)

    // ---- en odgovor velja v vseh nacinih (pomocnik za torrente pride in gre, prosti prostor se spreminja)
    val z = P.zapisi(null, torrent, zdaj)
    check(P.stanje(z, P.VSE, zdaj) == true && P.stanje(z, 3 * gb, zdaj) == true && P.stanje(z, gb, zdaj) == false && P.stanje(z, 0L, zdaj) == false)

    // ---- nov odgovor
    // Popoln odgovor nadomesti vse.
    check(P.zapisi(je, P.Izid(niDo = P.VSE), zdaj + ura) == P.Zapis(niDo = P.VSE, casNi = zdaj + ura))
    check(P.zapisi(ni, neposreden, zdaj + ura) == P.Zapis(jeOd = 0L, casJe = zdaj + ura))
    // Delni odgovor (prvi dodatek s torrentom) ne povozi nizje, se sveze meje - in njenega casa ne podaljsa.
    val poDelnem = P.zapisi(z, P.Izid(jeOd = P.VSE), zdaj + ura)
    check(poDelnem.jeOd == 2 * gb && poDelnem.casJe == zdaj && poDelnem.niDo == 2 * gb - 1) { poDelnem }
    // Ko nizja meja ni vec sveza, obvelja novi dokaz; meja »ni« ostane (njen dokaz je svoj).
    val kasneje = P.zapisi(z, P.Izid(jeOd = P.VSE), zdaj + P.JE_VELJA + ura)
    check(kasneje.jeOd == P.VSE && kasneje.casJe == zdaj + P.JE_VELJA + ura && kasneje.niDo == 2 * gb - 1 && kasneje.casNi == zdaj) { kasneje }
    // Nizja meja iz delnega odgovora obvelja takoj; meja »ni«, ki ji nasprotuje, se umakne.
    val nizja = P.zapisi(z, P.Izid(jeOd = gb), zdaj + ura)
    check(nizja.jeOd == gb && nizja.casJe == zdaj + ura && nizja.niDo == gb - 1) { nizja }
    // Predvajanje je uspelo na napravi, ki zmore vse: vemo samo, da gre tam.
    val uspelo = P.zapisi(null, P.Izid(jeOd = P.VSE), zdaj)
    check(P.stanje(uspelo, P.VSE, zdaj) == true && P.stanje(uspelo, 4 * gb, zdaj) == null)

    // ---- zdruzevanje z drugo napravo: novejsi dokaz obvelja v celoti, cas iz prihodnosti se pristrize
    check(P.zdruzi(null, je, zdaj) == je)
    check(P.zdruzi(ni, P.Zapis(jeOd = 0L, casJe = zdaj + 5), zdaj + 10) == P.Zapis(jeOd = 0L, casJe = zdaj + 5))
    check(P.zdruzi(P.Zapis(jeOd = 0L, casJe = zdaj + 5), ni, zdaj + 10) == P.Zapis(jeOd = 0L, casJe = zdaj + 5)) { "starejsi tuji zapis ne povozi nasega" }
    val izPrihodnosti = P.zdruzi(null, P.Zapis(niDo = P.VSE, casNi = zdaj + 9 * ura), zdaj)
    check(izPrihodnosti.casNi == zdaj && P.stanje(izPrihodnosti, P.VSE, zdaj) == false)
    check(P.zdruzi(je, je, zdaj) == je)

    // ---- dodatki so se spremenili: odgovori ostanejo za prikaz, a niso vec svezi
    val star = P.zastaraj(z, zdaj + ura)
    check(P.stanje(star, P.VSE, zdaj + ura) == null && P.znano(star, P.VSE, zdaj + ura) == true)
    check(P.stanje(star, 0L, zdaj + ura) == null && P.znano(star, 0L, zdaj + ura) == false)
    check(P.zastaraj(star, zdaj + ura) == star) { "ze zastarel zapis se ne stara naprej" }

    // ---- zapis v niz in nazaj
    for (v in listOf(je, ni, z, uspelo, star, P.Zapis(jeOd = 5 * gb, casJe = 7, niDo = 5 * gb - 1, casNi = 7))) check(P.izNiza(P.vNiz(v)) == v) { P.vNiz(v) }
    check(P.vNiz(je) == "0;$zdaj;;0" && P.vNiz(ni) == ";0;M;$zdaj")
    for (slab in listOf(null, "", "a;b;c;d", "0;1;2", ";0;;0", "-5;1;;0", "0;-1;;0", "0;1;;0;9")) check(P.izNiza(slab) == null) { slab ?: "null" }
    check(P.izNiza("3;10;7;20") == P.Zapis(niDo = 7, casNi = 20)) { "nasprotujoci meji: obvelja novejsi dokaz" }
    check(P.izNiza("3;20;7;10") == P.Zapis(jeOd = 3, casJe = 20, niDo = 2, casNi = 10))

    // ---- zapisi pred 4. 10. 2026
    check(P.izStarega("movie|tt1", zdaj) == ("movie|tt1" to P.Zapis(jeOd = P.VSE, casJe = zdaj)))
    check(P.izStarega("movie|tt1", -zdaj) == ("movie|tt1" to P.Zapis(niDo = P.VSE, casNi = zdaj)))
    check(P.izStarega("series|tt2:1:1|brez", zdaj) == ("series|tt2:1:1" to P.Zapis(jeOd = 0L, casJe = zdaj)))
    check(P.izStarega("movie|tt1|brez", -zdaj) == ("movie|tt1" to P.Zapis(niDo = 0L, casNi = zdaj)))
    val blok = 256L * 1024 * 1024
    check(P.izStarega("movie|tt1|do37", zdaj)!!.second == P.Zapis(jeOd = 38 * blok, casJe = zdaj))
    check(P.izStarega("movie|tt1|do37", -zdaj)!!.second == P.Zapis(niDo = 37 * blok, casNi = zdaj))
    for (slab in listOf("movie", "|tt1", "movie|tt1|cudno", "movie|tt1|doX", "a|b|c|d")) check(P.izStarega(slab, zdaj) == null) { slab }
    check(P.izStarega("movie|tt1", 0L) == null)
    // »Ni« v nacinu, ki zmore vse, velja povsod; »je« brez torrentov velja povsod.
    check(P.stanje(P.izStarega("movie|tt1", -zdaj)!!.second, 0L, zdaj) == false)
    check(P.stanje(P.izStarega("movie|tt1|brez", zdaj)!!.second, P.VSE, zdaj) == true)
    check(P.stanje(P.izStarega("movie|tt1", zdaj)!!.second, 5 * gb, zdaj) == null) { "»je« z vsemi torrenti za omejeno napravo ne pove nicesar" }

    // ---- dodatki: kaj spremeni svezino odgovorov
    val a = P.odtisNaslova("https://dodatek-a.example/abc"); val b = P.odtisNaslova("https://dodatek-b.example")
    val c = P.odtisNaslova("https://podnapisi.example")
    check(a.length == 12 && a != b && a == P.odtisNaslova(" https://dodatek-a.example/abc "))
    check(!P.dodatki(null, mapOf(a to 's')).zastaraj) { "prvi zagon: nic ne sklepamo" }
    check(!P.dodatki(mapOf(a to 's'), mapOf(a to 's')).zastaraj)
    check(P.dodatki(mapOf(a to 's'), mapOf(a to 's', b to 's')).zastaraj) { "nov dodatek s tokovi" }
    check(P.dodatki(mapOf(a to 's'), mapOf(a to 's', b to 'u')).zastaraj) { "nov dodatek, za katerega se ne vemo" }
    check(!P.dodatki(mapOf(a to 's'), mapOf(a to 's', c to 'n')).zastaraj) { "dodatek s podnapisi ne spremeni nicesar" }
    check(P.dodatki(mapOf(a to 's', b to 's'), mapOf(a to 's')).zastaraj) { "odstranjen dodatek s tokovi" }
    check(!P.dodatki(mapOf(a to 's', c to 'n'), mapOf(a to 's')).zastaraj) { "odstranjen dodatek brez tokov" }
    // Manifest se ni nalozen (zagon): obdrzimo, kar smo vedeli - brez tega bi vsak zagon postaral vse.
    val poZagonu = P.dodatki(mapOf(a to 's', c to 'n'), mapOf(a to 'u', c to 'u'))
    check(!poZagonu.zastaraj && poZagonu.dodatki == mapOf(a to 's', c to 'n'))
    // Neznan dodatek se izkaze za dodatek s tokovi ali brez: steli smo ga ze ob dodajanju.
    check(!P.dodatki(mapOf(a to 's', b to 'u'), mapOf(a to 's', b to 's')).zastaraj)
    check(!P.dodatki(mapOf(a to 's', b to 'u'), mapOf(a to 's', b to 'n')).zastaraj)
    check(P.dodatki(mapOf(a to 's', c to 'n'), mapOf(a to 's', c to 's')).zastaraj) { "dodatek je zacel dajati tokove" }
    check(P.dodatkiIzNiza(P.dodatkiVNiz(mapOf(a to 's', c to 'n', b to 'u'))) == mapOf(a to 's', c to 'n', b to 'u'))
    check(P.dodatkiIzNiza(null) == null && P.dodatkiIzNiza("")!!.isEmpty() && P.dodatkiIzNiza("x:q,:s,y")!!.isEmpty())
    // Odtis za delitev: samo dodatki s tokovi, vrstni red ni pomemben; brez njih ni kaj deliti.
    check(P.odtis(mapOf(a to 's', b to 's', c to 'n')) == P.odtis(mapOf(b to 's', a to 's')))
    check(P.odtis(mapOf(a to 's')) != P.odtis(mapOf(a to 's', b to 's')))
    check(P.odtis(mapOf(c to 'n', b to 'u')) == "" && P.odtis(mapOf(a to 's')).length == 16)

    // ---- kaj smemo deliti z drugimi napravami: samo javne id-je (IMDb)
    check(P.zaDelitev("movie|tt0111161") && P.zaDelitev("series|tt0944947:1:2"))
    for (zasebno in listOf("movie|zp:123", "series|kitsu:5", "Posebno|tt0111161", "movie|tt0111161|brez", "movie|tt12", "movie|" + "tt1".repeat(40)))
        check(!P.zaDelitev(zasebno)) { zasebno }

    println("RazpolozljivostPravilaTest OK")
}
