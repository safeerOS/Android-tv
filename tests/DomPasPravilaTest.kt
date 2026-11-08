package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Pas na vrhu Doma Medijskega centra ([DomPasPravila]): vrste, napis glavnega gumba, cas napredka, odstotek in mere. */
fun main() {
    println("Preizkus pasu na vrhu Doma (DomPas)")
    val P = DomPasPravila

    // Vrste, ki jih Dom poda v DomPasStanje.vrsta; MC_NIC pomeni skrit pas (zunaj Doma, na podstrani).
    preveriEnako("vrsta igra", "igra", P.IGRA)
    preveriEnako("vrsta nadaljuj", "nadaljuj", P.NADALJUJ)
    preveriEnako("vrsta izbor", "izbor", P.IZBOR)
    preveriEnako("vrsta nic = pas skrit", "nic", P.MC_NIC)

    // Glavni gumb: kar igra -> Premor, nedokoncana vsebina -> Nadaljuj, izbor -> Predvajaj.
    preveriEnako("igra -> premor", "premor", P.napisGlavnega(P.IGRA, igra = true))
    preveriEnako("vrsta igra brez zastavice -> premor", "premor", P.napisGlavnega(P.IGRA, igra = false))
    preveriEnako("nadaljuj -> nadaljuj", "nadaljuj", P.napisGlavnega(P.NADALJUJ, igra = false))
    preveriEnako("izbor -> predvajaj", "predvajaj", P.napisGlavnega(P.IZBOR, igra = false))
    preveriEnako("neznana vrsta -> predvajaj", "predvajaj", P.napisGlavnega("neznano", igra = false))
    // Po preklopu iz seznama vsebina ze igra, vrsta pa se ni osvezena - gumb mora kazati Premor.
    preveriEnako("nadaljuj, a ze igra -> premor", "premor", P.napisGlavnega(P.NADALJUJ, igra = true))

    // Cas napredka: »m:ss / m:ss«, nad uro »h:mm:ss / h:mm:ss«.
    preveriEnako("3:05 / 4:12", "3:05 / 4:12", P.casNapredka(185_000L, 252_000L))
    preveriEnako("z urami", "1:16:05 / 2:10:00", P.casNapredka(4_565_000L, 7_800_000L))
    preveriEnako("zacetek", "0:00 / 1:00", P.casNapredka(0L, 60_000L))
    preveriEnako("negativni polozaj = 0", "0:00 / 1:00", P.casNapredka(-5_000L, 60_000L))
    preveriEnako("natanko ura: oba casa z urami", "0:59:59 / 1:00:00", P.casNapredka(3_599_000L, 3_600_000L))
    preveriEnako("polozaj cez trajanje se izpise", "1:10 / 1:00", P.casNapredka(70_000L, 60_000L))
    preveriEnako("brez trajanja ni casa", "", P.casNapredka(10_000L, 0L))
    preveriEnako("negativno trajanje ni casa", "", P.casNapredka(10_000L, -1L))

    // Odstotek za crto: 0..100, brez trajanja 0.
    preveriEnako("cetrtina", 25, P.odstotek(30_000L, 120_000L))
    preveriEnako("zacetek", 0, P.odstotek(0L, 120_000L))
    preveriEnako("negativni polozaj = 0", 0, P.odstotek(-500L, 120_000L))
    preveriEnako("konec", 100, P.odstotek(120_000L, 120_000L))
    preveriEnako("cez konec = 100", 100, P.odstotek(999_000L, 120_000L))
    preveriEnako("brez trajanja = 0", 0, P.odstotek(50L, 0L))
    preveriEnako("tretjina (odrezano)", 33, P.odstotek(1L, 3L))
    preveriEnako("dve tretjini (odrezano)", 66, P.odstotek(2L, 3L))

    // Mere: ozek zaslon 170 dp / 20 sp, TV in tablica 230 dp / 28 sp.
    preveriEnako("telefon 320 dp: 170", 170, P.visinaDp(320))
    preveriEnako("599 dp: se ozek", 170, P.visinaDp(599))
    preveriEnako("600 dp: sirok", 230, P.visinaDp(600))
    preveriEnako("TV 1920 dp: 230", 230, P.visinaDp(1920))
    preveriEnako("neznana sirina 0: ozek", 170, P.visinaDp(0))
    preveriEnako("naslov 320 dp: 20 sp", 20f, P.velikostNaslovaSp(320))
    preveriEnako("naslov 599 dp: 20 sp", 20f, P.velikostNaslovaSp(599))
    preveriEnako("naslov 600 dp: 40 sp (zgled KefinTweaks)", 40f, P.velikostNaslovaSp(600))
    preveriEnako("naslov 1920 dp: 40 sp", 40f, P.velikostNaslovaSp(1920))
    // TV 8. 11. 2026: na TV je pas 38 % visine zaslona, najmanj 200 in najvec 400 dp (najvec 55 %); ozek ostane 170 dp.
    preveriEnako("TV 960x540 dp: 205 (kartice Nadaljuj na prvem zaslonu)", 205, P.visinaDp(960, 540))
    preveriEnako("telefon lezece 800x360 dp: 198 (ne 83 % zaslona)", 198, P.visinaDp(800, 360))
    preveriEnako("zelo nizko okno: najmanj 170", 170, P.visinaDp(800, 200))
    preveriEnako("opis na 453 dp", true, P.prikaziOpis(true, 453, true))
    preveriEnako("brez opisa na TV pasu 205 dp", false, P.prikaziOpis(true, 205, true))
    preveriEnako("brez opisa na lezecem telefonu 198 dp", false, P.prikaziOpis(true, 198, true))
    preveriEnako("naslov na nizkem sirokem pasu 198 dp: 24 sp", 24f, P.velikostNaslovaSp(800, 198))
    preveriEnako("naslov na TV pasu 205 dp: 24 sp", 24f, P.velikostNaslovaSp(960, 205))
    preveriEnako("naslov na ozkem: 20 sp tudi z visino", 20f, P.velikostNaslovaSp(400, 170))
    preveriEnako("brez opisa na ozkem", false, P.prikaziOpis(false, 453, true))
    preveriEnako("prazen opis ne", false, P.prikaziOpis(true, 453, false))
    preveriEnako("TV 1280x720 dp: 273", 273, P.visinaDp(1280, 720))
    preveriEnako("TV 1920x1080 dp: 400 (zgornja meja)", 400, P.visinaDp(1920, 1080))
    preveriEnako("visok zaslon 1200 dp: 400 (zgornja meja)", 400, P.visinaDp(1600, 1200))
    preveriEnako("ozek ostane 170 tudi z visino", 170, P.visinaDp(400, 900))
    preveriEnako("brez visine: staro vedenje 230", 230, P.visinaDp(1920, 0))
    preveriEnako("visok zaslon 1280x1080 dp: 400 (zgornja meja)", 400, P.visinaDp(1280, 1080))
    preveriEnako("960x360 dp: 198", 198, P.visinaDp(960, 360))
    preveriEnako("960x360 dp: najvec 55 % visine", true, P.visinaDp(960, 360) <= 360 * 55 / 100)
    preveriEnako("TV 960x540: najvec 55 % visine", true, P.visinaDp(960, 540) <= 540 * 55 / 100)
    // Nizek siroki pas (TV 205 dp): ena vrstica naslova in logotip 44 dp, da gumbi in crta ne odrezejo vrha.
    preveriEnako("nizek TV pas: ena vrstica naslova", 1, P.vrsticNaslova(960, 205))
    preveriEnako("visok pas: dve vrstici", 2, P.vrsticNaslova(1280, 400))
    preveriEnako("ozek: dve vrstici", 2, P.vrsticNaslova(400, 170))
    preveriEnako("logotip na nizkem TV pasu 44 dp", 44, P.visinaLogotipaDp(960, 205))
    preveriEnako("logotip na visokem pasu 120 dp", 120, P.visinaLogotipaDp(1280, 400))
    preveriEnako("logotip na ozkem 64 dp", 64, P.visinaLogotipaDp(400, 170))
    preveriEnako("logotip: navadna pisava", 44, P.visinaLogotipaDp(960, 205, 1f))
    preveriEnako("logotip: pisava 1,3 na nizkem pasu manjsi", 33, P.visinaLogotipaDp(960, 205, 1.3f))
    preveriEnako("logotip: pisava 2,0 najmanj 24", 24, P.visinaLogotipaDp(960, 205, 2f))
    preveriEnako("logotip: visok pas ne manjsa", 120, P.visinaLogotipaDp(1280, 400, 1.3f))
    preveriEnako("slika ob strani 274 px", 274, P.pikslovSlike(274, 274, 1280))
    preveriEnako("slika cez pas (MATCH_PARENT) polna", 1280, P.pikslovSlike(-1, -1, 1280))
    preveriEnako("slika ob strani najmanj 160", 160, P.pikslovSlike(90, 90, 1280))
    preveriEnako("z diska premajhna", true, P.premajhnaZDiska(274, 1280))
    preveriEnako("z diska dovolj velika", false, P.premajhnaZDiska(1280, 1280))
    preveriEnako("stara zacasna", true, P.jeStaraZacasna("ab.123.tmp", 2L * 60 * 60 * 1000))
    preveriEnako("sveza zacasna ostane", false, P.jeStaraZacasna("ab.123.tmp", 1000L))
    preveriEnako("prava datoteka ni zacasna", false, P.jeStaraZacasna("ab", 99L * 60 * 60 * 1000))
    // Podnaslov, ko igra: film leto in trajanje (prej samo leto), zvok kot doslej.
    preveriEnako("igra film: leto in trajanje", "2006 · 2 h 10 min", P.podnaslovIgra(true, 2006, "Nolan", 7_800_000L))
    preveriEnako("igra film brez leta: izvajalec in trajanje", "Kanal · 45 min", P.podnaslovIgra(true, 0, "Kanal", 2_700_000L))
    preveriEnako("igra film brez trajanja: samo leto", "2006", P.podnaslovIgra(true, 2006, "", 0L))
    preveriEnako("igra glasba: izvajalec brez trajanja", "Avsenik", P.podnaslovIgra(false, 0, "Avsenik", 180_000L))
    // Ambient: mocnejsi preliv, uporabnikovo ozadje ne proseva za vrsticami.
    val pr = P.ambientPreliv(0xFF112233.toInt(), 0xFF000000.toInt())
    preveriEnako("ambient zgoraj alfa F0", 0xF0, (pr[0] ushr 24) and 0xFF)
    preveriEnako("ambient spodaj alfa FA", 0xFA, (pr[1] ushr 24) and 0xFF)
    preveriEnako("ambient barva ostane", 0x112233, pr[0] and 0xFFFFFF)
    // Zvok v pasu: oster kvadrat ob strani, ista slika zamegljena cez pas.
    preveriEnako("zvok sirok: zamegljeno cez pas in kvadrat", "https://x/logo.png#zamegli" to "https://x/logo.png", P.slikePasu("", "https://x/logo.png", true, false))
    preveriEnako("film sirok brez ozadja: samo plakat", "" to "https://x/p.jpg", P.slikePasu("", "https://x/p.jpg", true, true))
    preveriEnako("ozadje filma cez pas", "https://o/bg" to "", P.slikePasu("https://o/bg", "https://x/p.jpg", true, true))
    preveriEnako("ozek zaslon: slika cez pas kot doslej", "https://x/logo.png" to "", P.slikePasu("", "https://x/logo.png", false, false))
    preveriEnako("brez slike nic", "" to "", P.slikePasu("", "", true, false))
    preveriEnako("oznaka se ne podvoji", "a#zamegli", P.zamegljeno("a#zamegli"))
    preveriEnako("prazen naslov brez oznake", "", P.zamegljeno(""))
    preveriEnako("oznaka prepoznana", true, P.jeZamegljeno("a#zamegli"))
    preveriEnako("brez oznake ni zamegljeno", false, P.jeZamegljeno("a"))
    preveriEnako("oznaka odstranjena", "https://x/a.png", P.brezOznake("https://x/a.png#zamegli"))
    preveriEnako("plakat filma 2:3", 91 to 136, P.meraPlakata(205, true))
    preveriEnako("zvok kvadrat 1:1", 136 to 136, P.meraPlakata(205, false))
    // Nadaljuj s pasu: brez ponovnega vprasanja samo za isto vsebino in v roku.
    val pasV = DomPasPravila.Vsebina("kartica-prestige", "tt0482571", 0, 0)
    preveriEnako("nadaljuj s pasu: tok istega filma (IMDb)", true, P.nadaljujBrezVprasanja(pasV, 5_000L, DomPasPravila.Vsebina("tok-x", "tt0482571", 0, 0), null))
    preveriEnako("nadaljuj s pasu: izvirnik je isti", true, P.nadaljujBrezVprasanja(pasV, 5_000L, DomPasPravila.Vsebina("tok-y", "", 0, 0), pasV))
    preveriEnako("nadaljuj s pasu: drug film vprasa", false, P.nadaljujBrezVprasanja(pasV, 5_000L, DomPasPravila.Vsebina("dune", "tt1160419", 0, 0), null))
    preveriEnako("kartica vrstice (brez klika s pasu) vprasa", false, P.nadaljujBrezVprasanja(null, 5_000L, pasV, null))
    preveriEnako("po roku vprasa", false, P.nadaljujBrezVprasanja(pasV, P.ROK_NADALJUJ_IZ_PASU_MS + 1, pasV, null))
    preveriEnako("neznan cas vprasa", false, P.nadaljujBrezVprasanja(pasV, -1L, pasV, null))
    preveriEnako("druga epizoda vprasa", false, P.nadaljujBrezVprasanja(DomPasPravila.Vsebina("s", "tt0903747", 1, 2), 1_000L,
        DomPasPravila.Vsebina("t", "tt0903747", 1, 3), null))
    // Disk slik pasu: najstarejse ven do meje.
    preveriEnako("disk pod mejo: nic ven", emptyList<Int>(), P.zavrziZaMejo(listOf(1L to 10L, 2L to 10L), 20L))
    preveriEnako("disk nad mejo: najstarejsa ven", listOf(1), P.zavrziZaMejo(listOf(5L to 10L, 1L to 10L, 3L to 10L), 20L))
    preveriEnako("disk: vec ven po starosti", listOf(1, 2), P.zavrziZaMejo(listOf(9L to 10L, 1L to 10L, 3L to 10L), 10L))
    preveriEnako("disk prazen", emptyList<Int>(), P.zavrziZaMejo(emptyList(), 0L))
    // Kritika 8. 11.: ostro ozadje, logotip, trajanje, ambient.
    preveriEnako("imdb iz polja", "tt31193180", P.imdbId("tt31193180", ""))
    preveriEnako("imdb iz id epizode", "tt0944947", P.imdbId("", "tt0944947:1:2"))
    preveriEnako("imdb iz id dodatka", "tt1234567", P.imdbId("", "stremio:movie:tt1234567"))
    preveriEnako("brez imdb prazno", "", P.imdbId("", "jamendo:123"))
    preveriEnako("ozadje url", "https://images.metahub.space/background/medium/tt1/img".replace("tt1", "tt1234567"), P.ozadjeUrl("tt1234567"))
    preveriEnako("brez imdb ni ozadja", "", P.ozadjeUrl(""))
    preveriEnako("logo url", "https://images.metahub.space/logo/medium/tt1234567/img", P.logoUrl("tt1234567"))
    preveriEnako("trajanje 2 h 17 min", "2 h 17 min", P.trajanje(8_251_000L))
    preveriEnako("trajanje 45 min", "45 min", P.trajanje(2_700_000L))
    preveriEnako("brez trajanja prazno", "", P.trajanje(0L))
    preveriEnako("podnaslov brez praznih in 0", "2025 · 2 h 17 min", P.podnaslov("2025", "", "2 h 17 min", "0"))
    preveriEnako("ambient: crno ozadje + bela slika -> 35 %", 0xFF595959.toInt(), P.ambient(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
    preveriEnako("povprecje: pol belo pol crno -> 127", 0xFF7F7F7F.toInt(), P.povprecje(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())))
    preveriEnako("povprecje: ne sredina (rdec rob, crna sredina)", 0xFF550000.toInt(), P.povprecje(intArrayOf(0xFFFF0000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())))
    preveriEnako("povprecje prazno -> crna", 0xFF000000.toInt(), P.povprecje(IntArray(0)))
    // Krozenje izbora.
    preveriEnako("naslednji 0 -> 1", 1, P.naslednji(0, 3))
    preveriEnako("naslednji zadnji -> 0", 0, P.naslednji(2, 3))
    preveriEnako("naslednji prazen -> 0", 0, P.naslednji(5, 0))
    preveriEnako("krozi izbor brez fokusa", true, P.smeKroziti(P.IZBOR, imaFokus = false, zmanjsanoGibanje = false, n = 3))
    preveriEnako("ne krozi s fokusom (klik ne sme odpreti druge vsebine)", false, P.smeKroziti(P.IZBOR, imaFokus = true, zmanjsanoGibanje = false, n = 3))
    preveriEnako("ne krozi pri zmanjsanem gibanju", false, P.smeKroziti(P.IZBOR, imaFokus = false, zmanjsanoGibanje = true, n = 3))
    preveriEnako("ne krozi z eno vsebino", false, P.smeKroziti(P.IZBOR, imaFokus = false, zmanjsanoGibanje = false, n = 1))
    preveriEnako("ne krozi, ko igra", false, P.smeKroziti(P.IGRA, imaFokus = false, zmanjsanoGibanje = false, n = 3))
    preveriEnako("ne krozi nedokoncane", false, P.smeKroziti(P.NADALJUJ, imaFokus = false, zmanjsanoGibanje = false, n = 3))
    preveriEnako("naslov 0 dp: 20 sp", 20f, P.velikostNaslovaSp(0))

    if (napak == 0) println("DomPasPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
