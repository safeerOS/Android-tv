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
    // Zgled KefinTweaks: na TV je pas 42 % visine zaslona, najmanj 300 in najvec 460 dp; ozek ostane 170 dp.
    preveriEnako("TV 960x540 dp: 297 (najvec 55 % visine)", 297, P.visinaDp(960, 540))
    preveriEnako("telefon lezece 800x360 dp: 198 (ne 83 % zaslona)", 198, P.visinaDp(800, 360))
    preveriEnako("zelo nizko okno: najmanj 170", 170, P.visinaDp(800, 200))
    preveriEnako("opis na 453 dp", true, P.prikaziOpis(true, 453, true))
    preveriEnako("opis na TV 297 dp (gre, ~286 dp)", true, P.prikaziOpis(true, 297, true))
    preveriEnako("brez opisa na lezecem telefonu 198 dp", false, P.prikaziOpis(true, 198, true))
    preveriEnako("naslov na nizkem sirokem pasu 198 dp: 24 sp", 24f, P.velikostNaslovaSp(800, 198))
    preveriEnako("naslov na TV pasu 297 dp: 40 sp", 40f, P.velikostNaslovaSp(960, 297))
    preveriEnako("naslov na ozkem: 20 sp tudi z visino", 20f, P.velikostNaslovaSp(400, 170))
    preveriEnako("brez opisa na ozkem", false, P.prikaziOpis(false, 453, true))
    preveriEnako("prazen opis ne", false, P.prikaziOpis(true, 453, false))
    preveriEnako("TV 1280x720 dp: 302", 302, P.visinaDp(1280, 720))
    preveriEnako("TV 1920x1080 dp: 453", 453, P.visinaDp(1920, 1080))
    preveriEnako("visok zaslon 1200 dp: 460 (zgornja meja)", 460, P.visinaDp(1600, 1200))
    preveriEnako("ozek ostane 170 tudi z visino", 170, P.visinaDp(400, 900))
    preveriEnako("brez visine: staro vedenje 230", 230, P.visinaDp(1920, 0))
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
