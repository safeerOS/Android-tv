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
    preveriEnako("TV 960x540 dp: 300 (spodnja meja)", 300, P.visinaDp(960, 540))
    preveriEnako("TV 1280x720 dp: 302", 302, P.visinaDp(1280, 720))
    preveriEnako("TV 1920x1080 dp: 453", 453, P.visinaDp(1920, 1080))
    preveriEnako("visok zaslon 1200 dp: 460 (zgornja meja)", 460, P.visinaDp(1600, 1200))
    preveriEnako("ozek ostane 170 tudi z visino", 170, P.visinaDp(400, 900))
    preveriEnako("brez visine: staro vedenje 230", 230, P.visinaDp(1920, 0))
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
