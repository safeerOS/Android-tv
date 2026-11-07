package si.safeer.tv.os

import si.safeer.tv.os.UsenetIskanjePravila as P
import si.safeer.tv.os.UsenetIskanjePravila.Razlog as R

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/**
 * Lastnik 7. 11. 2026 03:51 (Primetime): klik je ob preverbah filtra dobil »ni odgovora (meja)«, ceprav je film imel 40
 * izdaj, in je zapis filtra pozabil. Meja ni »ni izdaj« - preizkus pravil iskanja ob kliku.
 */
fun main() {
    println("Preizkus iskanja Usenet ob kliku (meja ni »ni izdaj«)")

    // Meja (429 / Newznab 500/501): pocakamo in poskusimo znova, najvec trikrat.
    preveriEnako("meja po 1. poskusu: ponovi", true, P.ponoviPoMeji(1))
    preveriEnako("meja po 2. poskusu: ponovi", true, P.ponoviPoMeji(2))
    preveriEnako("meja po 3. poskusu: konec (3 poskusi)", false, P.ponoviPoMeji(3))
    preveriEnako("premor med poskusi je okoli 5 s", true, P.PREMOR_MS in 1_000L..10_000L)

    // Razlog izida: meja ni »ni izdaj«; 0 izdaj je pravi odgovor indeksatorja.
    preveriEnako("razlog: meja", R.MEJA, P.razlog(null, true))
    preveriEnako("razlog: ni odgovora", R.NI_ODGOVORA, P.razlog(null, false))
    preveriEnako("razlog: 0 izdaj", R.NI_IZDAJ, P.razlog(0, false))
    preveriEnako("razlog: izdaje", R.OK, P.razlog(40, false))

    // Zapis filtra se pozabi samo pri pravih 0 izdajah - meja in napaka ga pustita (kartica ne izgine).
    preveriEnako("zapis: 0 izdaj -> pozabi", true, P.pozabiZapis(R.NI_IZDAJ))
    preveriEnako("zapis: meja -> ne pozabi", false, P.pozabiZapis(R.MEJA))
    preveriEnako("zapis: ni odgovora -> ne pozabi", false, P.pozabiZapis(R.NI_ODGOVORA))
    preveriEnako("zapis: izdaje -> ne pozabi", false, P.pozabiZapis(R.OK))

    // Sporocilo: meja in ni odgovora nista »ni na voljo«.
    preveriEnako("sporocilo: meja in ni odgovora", true to true,
        P.neOdgovarja(R.MEJA) to P.neOdgovarja(R.NI_ODGOVORA))
    preveriEnako("sporocilo: 0 izdaj ima svoje besedilo", false, P.neOdgovarja(R.NI_IZDAJ))
    preveriEnako("sporocilo: izdaje so uspeh", false, P.neOdgovarja(R.OK))

    // Del C (7. 11. 2026): izdaje, ki jih je filter ze nasel, gredo ob kliku iz pomnilnika - brez nove zahteve
    // indeksatorju do 30 min; drug odtis indeksatorja ali iztek veljavnosti zahtevo spet poslje.
    val t0 = 1_000_000_000L
    val min = 60_000L
    val izdaja = UsenetPravila.Zadetek("Film.2020.1080p.WEB.h264-GRP", "https://prim.example/nzb/1?i=1", 2L * 1024 * 1024 * 1024)
    val cache = P.PredpomnilnikIzdaj()
    var zahtev = 0
    fun klik(cas: Long, odtis: String = "odtis1") = P.izPredpomnilnika(cache, "tt1", odtis, cas) { zahtev++; listOf(izdaja) }
    preveriEnako("predpomnilnik: prvi klik vprasa indeksator", false to 1, klik(t0).izPredpomnilnika to zahtev)
    preveriEnako("predpomnilnik: drugi klik za isti naslov brez zahteve", true, klik(t0 + min).izPredpomnilnika)
    preveriEnako("predpomnilnik: stevec zahtev ostane 1", 1, zahtev)
    preveriEnako("predpomnilnik: zadetek ima vse izdaje", 1, klik(t0 + min).izdaje?.size)
    preveriEnako("predpomnilnik: po 30 min znova vprasa", false, klik(t0 + 30 * min + 1).izPredpomnilnika)
    preveriEnako("predpomnilnik: drug odtis indeksatorja znova vprasa", false, klik(t0 + 31 * min, "odtis2").izPredpomnilnika)
    preveriEnako("predpomnilnik: neodgovorjenega iskanja ne zapomni", null to false,
        P.izPredpomnilnika(cache, "tt9", "odtis1", t0) { null }.izdaje to P.izPredpomnilnika(cache, "tt9", "odtis1", t0) { null }.izPredpomnilnika)
    val c2 = P.PredpomnilnikIzdaj()
    (0..P.PREDPOMNILNIK_NAJVEC).forEach { i -> c2.shrani("tt$i", "odtis1", listOf(izdaja), t0) }
    preveriEnako("predpomnilnik: najvec 200 naslovov", P.PREDPOMNILNIK_NAJVEC, c2.velikost())
    preveriEnako("predpomnilnik: najstarejsi odpade (LRU)", null, c2.poisci("tt0", "odtis1", t0 + 1))

    // Pregled US-5: klic caka na vse tri poskuse (3 x 12 s + 2 x 5 s), ne samo na prvega - sicer klik po prvi meji obupa.
    preveriEnako("cakanje s ponovitvami: 3 iskanja in 2 premora", 3 * 12_000L + 2 * 5_000L, P.cakajSPonovitvami(12_000L))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
