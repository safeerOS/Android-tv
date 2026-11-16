package si.safeer.tv

import si.safeer.tv.TvTipke.Dejanje

/** Barvni gumbi v brskalniku (TV 3. 11. 2026: zeleni gumb vzame sistem, kazalca ni bilo mogoce vklopiti). */
fun main() {
    check(TvTipke.dejanje(183) == Dejanje.KAZALEC)   // rdeci: prej hitre strani
    check(TvTipke.dejanje(184) == Dejanje.KAZALEC)   // zeleni
    check(TvTipke.dejanje(165) == Dejanje.KAZALEC)   // INFO
    check(TvTipke.dejanje(82) == Dejanje.HITRE_STRANI)
    check(TvTipke.dejanje(185) == Dejanje.ZAZNAMKI)
    check(TvTipke.dejanje(19) == Dejanje.NIC)
    // Medijske tipke (TV-PP): samo 85/126/127 dobijo skript, ostale ne.
    check(TvTipke.jsMedijskaTipka(19) == null)
    check(TvTipke.jsMedijskaTipka(23) == null)
    check(TvTipke.jsMedijskaTipka(85)!!.contains("x.paused ? x.play() : ${TvTipke.PAVZA_JS}")) { "predvajaj/pavza preklaplja" }
    check(TvTipke.jsMedijskaTipka(126)!!.contains("x.play()") && !TvTipke.jsMedijskaTipka(126)!!.contains("x.pause()")) { "samo predvajaj" }
    check(TvTipke.jsMedijskaTipka(127)!!.contains("x.pause()") && !TvTipke.jsMedijskaTipka(127)!!.contains("x.play()")) { "samo pavza" }
    // Pavza mora pred pause() sprozit uporabnikov dogodek (keydown), sicer jo skript predvajanja v ozadju zavrze ali razveljavi.
    check(TvTipke.jsMedijskaTipka(127)!!.contains("dispatchEvent(new Event('keydown'))")) { "pavza sprozi keydown" }
    check(!TvTipke.jsMedijskaTipka(126)!!.contains("keydown")) { "predvajaj ne sprozi keydown" }
    // Brez videa v strani (odgovor "ni") se tipka posreduje glasbi; ok/napaka/null/prazno ne.
    check(TvTipke.medijskiOdgovorPosreduj("\"ni\""))
    check(TvTipke.medijskiOdgovorPosreduj(" \"ni\" "))
    check(!TvTipke.medijskiOdgovorPosreduj("\"ok\""))
    check(!TvTipke.medijskiOdgovorPosreduj("\"napaka\""))
    check(!TvTipke.medijskiOdgovorPosreduj("null"))
    check(!TvTipke.medijskiOdgovorPosreduj(null))
    check(!TvTipke.medijskiOdgovorPosreduj(""))
    // Drzan OK vklopi kazalec samo enkrat (ob tocno doloceni ponovitvi), samo na strani in ko ga se ni.
    check(TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = false, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, 0, kazalecViden = false, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC + 1, kazalecViden = false, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = true, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = false, vVrstici = true))
    check(!TvTipke.drzanOkVkljuciKazalec(19, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = false, vVrstici = false))
    // Lastnik 11. 11. 2026: z dna strani navzgor ne v naslovno vrstico - samo svez pritisk po premoru.
    check(!TvTipke.smeVNaslovnoVrstico(5, 10_000L)) { "drzana tipka GOR ne vstopi v vrstico" }
    check(!TvTipke.smeVNaslovnoVrstico(0, 100L)) { "hitro tipkanje GOR: stran je pravkar drsela" }
    check(TvTipke.smeVNaslovnoVrstico(0, TvTipke.PREMOR_NASLOVNA_MS)) { "namerna tipka po premoru: vrstica" }
    var t = 1_000_000L
    check(TvTipke.gorPritisk(0, t)) { "prvi pritisk na vrhu: vrstica" }
    check(!TvTipke.gorPritisk(1, t + 50)) { "ponovitev drzane tipke: ne" }
    check(!TvTipke.gorPritisk(0, t + 150)) { "takoj naslednji pritisk: ne" }
    check(TvTipke.gorPritisk(0, t + 150 + TvTipke.PREMOR_NASLOVNA_MS)) { "po premoru: da" }
    // GOR v naslovno vrstico (zunanji pregled fokusa 3. 11. 2026): v nacinu aplikacije med nalaganjem ne.
    check(TvTipke.gorVNaslovnoVrstico("-1", nacinAplikacije = true, napredek = 100))
    check(!TvTipke.gorVNaslovnoVrstico("1", nacinAplikacije = true, napredek = 100))
    check(!TvTipke.gorVNaslovnoVrstico("0", nacinAplikacije = false, napredek = 100))
    check(!TvTipke.gorVNaslovnoVrstico("null", nacinAplikacije = true, napredek = 20))    // prej: aplikacija zapuscena
    check(!TvTipke.gorVNaslovnoVrstico(null, nacinAplikacije = true, napredek = 99))
    check(TvTipke.gorVNaslovnoVrstico("null", nacinAplikacije = true, napredek = 100))    // stran brez skripte: ni ujet
    check(TvTipke.gorVNaslovnoVrstico("null", nacinAplikacije = false, napredek = 20))    // brskalnik: vrstica kot prej
    // OK na polju za datoteko (TV 4. 11. 2026): Enter iz aplikacije samo na odgovor nasega skripta.
    // Drugi krog pregleda 5. 11. 2026: golo "datoteka" lahko vrne stran, ki je funkcijo zasedla pred nami - ne velja.
    // Vrednosti spodaj je izracunal tv_spatial.js (dokazDatoteke; isti primer v tests/tv_pregled_fokusa_test.js).
    val z = "z".repeat(48)
    val izziv = "0123456789abcdef0123456789abcdef"
    check(TvTipke.odtisZetona(z, "datoteka|$izziv") == "safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99") { "odtis ni enak kot v tv_spatial.js" }
    check(TvTipke.odtisZetona("a1".repeat(24), "datoteka|ffffffffffffffff") == "safeer-3c19c611192160fa341491dbca22e1939406c7cf50540bf75368508986c7b55d") { "odtis ni enak kot v tv_spatial.js (2)" }
    check(TvTipke.okNaDatoteki("\"datoteka:safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99\"", z, izziv))      // evaluateJavascript vrne niz v narekovajih
    check(TvTipke.okNaDatoteki("datoteka:safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99", z, izziv))
    check(!TvTipke.okNaDatoteki("\"datoteka\"", z, izziv) && !TvTipke.okNaDatoteki("datoteka", z, izziv))   // stran brez zetona
    check(!TvTipke.okNaDatoteki("datoteka:safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99", z, "ffffffffffffffffffffffffffffffff"))       // odgovor drugega izziva
    check(!TvTipke.okNaDatoteki("datoteka:safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99", "y".repeat(48), izziv))                       // drug zeton (nova stran)
    check(!TvTipke.okNaDatoteki("datoteka:safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99", z.take(31), izziv))                           // prekratek zeton
    check(!TvTipke.okNaDatoteki("true", z, izziv) && !TvTipke.okNaDatoteki("false", z, izziv))   // gumb, povezava: klik skripta
    check(!TvTipke.okNaDatoteki(null, z, izziv) && !TvTipke.okNaDatoteki("null", z, izziv) && !TvTipke.okNaDatoteki("", z, izziv))
    // Pregled Groka 5. 11. 2026: dokaz tipke - iste vrednosti kot tv_spatial.js (tests/tv_pregled_groka_test.js).
    check(TvTipke.odtisHex(z, "tipka|$izziv") == "710f676402b818003612cb7ba435ae24f42c858306bc301f7b81594fd4d38d68") { "odtis tipke ni enak kot v tv_spatial.js" }
    // Neodvisni pregled 5. 11. 2026: dokaz s stevcem (isti primer kot neodvisna izvedba v node, tipka|7|izziv).
    check(TvTipke.odtisHex(z, "tipka|7|$izziv") == "5324ab093cac221be66308b2c117da1ec0c37209df048be2fd293a122f0e1188") { "odtis tipke s stevcem ni enak kot v tv_spatial.js" }
    val n = "0f1e2d3c4b5a6978"
    val ukaz = TvTipke.ukazTipke(z, n, 7L)
    val um = Regex("window\\._safeer_tipka_$n&&window\\._safeer_tipka_$n\\('([0-9a-f]{32})',7,'([0-9a-f]{64})'\\);").matchEntire(ukaz)
    check(um != null && um.groupValues[2] == TvTipke.odtisHex(z, "tipka|7|" + um.groupValues[1])) { "ukaz tipke: $ukaz" }
    check(TvTipke.ukazTipke(null, n, 1L) == "" && TvTipke.ukazTipke(z.take(31), n, 1L) == "") { "brez zetona ni dokaza" }
    check(TvTipke.ukazTipke(z, null, 1L) == "" && TvTipke.ukazTipke(z, "x');alert(1);//", 1L) == "" && TvTipke.ukazTipke(z, n, 0L) == "") {
        "brez veljavnega nakljucnega dela ali stevca ni dokaza (ni vrivanja v skript)"
    }
    check(TvTipke.izvoz(n, "_safeer_navigate_spatial") == "window._safeer_navigate_spatial_$n")
    check(TvTipke.izvoz("x');alert(1);//", "_safeer_navigate_spatial") == "window._safeer_navigate_spatial") { "neveljaven del ne gre v skript" }
    val i1 = TvTipke.novIzziv(); val i2 = TvTipke.novIzziv()
    check(i1.length == 32 && i1 != i2 && i1.all { it in "0123456789abcdef" })
    // Tretji neodvisni pregled 5. 11. 2026: druga preverba cilja (N3) in namig za kazalec - vrednosti je izracunal tv_spatial.js
    // (odtisZetona, isti zeton in izziv kot zgoraj).
    check(TvTipke.ciljVelja("\"cilj:safeer-60e2ebac5f45798f86b1c72fcd5b91e493d7a7c768f86ea0f3f8d460df7476bd\"", z, izziv, "enter")) { "cilj enter" }
    check(TvTipke.ciljVelja("cilj:safeer-d3f79ef1c47f853da0d93dab806aaa8e682aa8a02b570dd317811233a62a0ee6", z, izziv, "dotik")) { "cilj dotik" }
    check(!TvTipke.ciljVelja("cilj:safeer-60e2ebac5f45798f86b1c72fcd5b91e493d7a7c768f86ea0f3f8d460df7476bd", z, izziv, "dotik")) { "odgovor za Enter ne velja za dotik" }
    check(!TvTipke.ciljVelja("cilj:safeer-60e2ebac5f45798f86b1c72fcd5b91e493d7a7c768f86ea0f3f8d460df7476bd", z, izziv, "drugo")) { "neznan nacin" }
    check(!TvTipke.ciljVelja("cilj", z, izziv, "enter") && !TvTipke.ciljVelja("true", z, izziv, "enter") && !TvTipke.ciljVelja(null, z, izziv, "enter"))
    check(!TvTipke.ciljVelja("cilj:safeer-60e2ebac5f45798f86b1c72fcd5b91e493d7a7c768f86ea0f3f8d460df7476bd", "y".repeat(48), izziv, "enter")) { "drug zeton (nova stran)" }
    check(!TvTipke.ciljVelja("cilj:safeer-60e2ebac5f45798f86b1c72fcd5b91e493d7a7c768f86ea0f3f8d460df7476bd", z, "ffffffffffffffffffffffffffffffff", "enter")) { "drug izziv" }
    check(TvTipke.namigKazalca("\"kazalec:safeer-195fdccb7a78822b85580d8a36282d36dd9045ae847f60d64d081c5f6bad744f\"", z, izziv)) { "namig" }
    check(!TvTipke.namigKazalca("kazalec", z, izziv) && !TvTipke.namigKazalca("true", z, izziv)) { "namig brez odtisa" }
    check(!TvTipke.namigKazalca("kazalec:safeer-195fdccb7a78822b85580d8a36282d36dd9045ae847f60d64d081c5f6bad744f", z.take(31), izziv)) { "namig s prekratkim zetonom" }
    // Cetrti slepi pregled 5. 11. 2026 (odlocitev vodje 3): HMAC-SHA256 - RFC 4231 primer 2 in skupni vektorji s tests/tv_hmac_test.js.
    check(TvTipke.odtisHex("Jefe", "what do ya want for nothing?") == "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843") { "RFC 4231" }
    check(TvTipke.odtisHex("0123456789abcdef".repeat(3), "x".repeat(119)) == "e791a98eb0ecbe7168f461cd5f22123b38a92da02740e847c8aaacb02ac0325e") { "dva bloka" }
    check(TvTipke.odtisHex("z".repeat(32), "a".repeat(55)) == "bd6815b419feca963e3d1265609542dba7b87370e198466ca8a7c454cdc43dea") { "meja bloka 55" }
    check(TvTipke.odtisHex("z".repeat(64), "b".repeat(56)) == "d97602b29d0c0cdb13c57b6b8b2ab8ea4ff162802ad99eca67b5ae7eef7c0121") { "meja bloka 56, kljuc 64" }
    check(TvTipke.odtisHex("", "x") == "") { "prazen kljuc" }
    // N-B: ziva kopija prejsnje strani samo z odtisom zetona PREJSNJE strani in tega izziva.
    check(TvTipke.zivaKopijaVelja("\"ziv:safeer-457cf84fb6ca2cb9dc87aaa95c972b808782c3f791dfb07aa31ed2ef59934fb8\"", z, izziv)) { "prava kopija" }
    check(!TvTipke.zivaKopijaVelja("ziv:safeer-457cf84fb6ca2cb9dc87aaa95c972b808782c3f791dfb07aa31ed2ef59934fb8", "y".repeat(48), izziv)) { "stran brez zetona prejsnje strani" }
    check(!TvTipke.zivaKopijaVelja("ziv:safeer-457cf84fb6ca2cb9dc87aaa95c972b808782c3f791dfb07aa31ed2ef59934fb8", z, "ffffffffffffffffffffffffffffffff")) { "drug izziv" }
    check(!TvTipke.zivaKopijaVelja("ziv", z, izziv) && !TvTipke.zivaKopijaVelja("ok", z, izziv) && !TvTipke.zivaKopijaVelja(null, z, izziv))
    check(!TvTipke.zivaKopijaVelja("kazalec:safeer-195fdccb7a78822b85580d8a36282d36dd9045ae847f60d64d081c5f6bad744f", z, izziv)) { "odtis drugega namena ne velja" }
    println("TvTipkeTest: OK")
}
