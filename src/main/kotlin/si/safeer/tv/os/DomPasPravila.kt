package si.safeer.tv.os

/**
 * Pas na vrhu Doma Medijskega centra ([DomPas]): kaze, kar igra (video v pasu), sicer zadnjo nedokoncano vsebino z gumbom
 * Nadaljuj, sicer izbor dneva. Cista pravila brez Androida (preizkus: tests/DomPasPravilaTest.kt) - da se postavitev,
 * napisi in mere preverijo brez naprave, kot pri [MiniPredvajalnikPravila].
 */
object DomPasPravila {
    /** V pasu igra vsebina: gumb kaze Premor, klik odpre cel zaslon. */
    const val IGRA = "igra"
    /** Nic ne igra, a obstaja nedokoncana vsebina: gumb kaze Nadaljuj. */
    const val NADALJUJ = "nadaljuj"
    /** Nic ne igra in ni nedokoncanega: pas kaze izbor dneva (Kinema hero.ts) - Dom nikoli brez pasu. */
    const val IZBOR = "izbor"
    /** Pasa ni (zunaj Doma, na podstrani): [DomPas.prikazi] ga skrije. Enako ime in vrednost kot pri [MiniPredvajalnikPravila]. */
    const val MC_NIC = "nic"

    /** Kljuc napisa glavnega gumba; [DomPas] ga preslika v obstojece nize. */
    const val NAPIS_PREDVAJAJ = "predvajaj"
    const val NAPIS_PREMOR = "premor"
    const val NAPIS_NADALJUJ = "nadaljuj"

    /**
     * Kaj pise na glavnem gumbu: kar igra -> Premor (tudi ce vrsta tega ne pove, npr. po preklopu iz seznama),
     * nedokoncana vsebina -> Nadaljuj, izbor -> Predvajaj. Neznana vrsta ne pusti gumba brez napisa.
     */
    fun napisGlavnega(vrsta: String, igra: Boolean): String = when {
        igra || vrsta == IGRA -> NAPIS_PREMOR
        vrsta == NADALJUJ -> NAPIS_NADALJUJ
        else -> NAPIS_PREDVAJAJ
    }

    /**
     * »12:03 / 45:10« ali »1:16:05 / 2:10:00«, kadar ena od vrednosti doseze uro (da sta oba casa enake oblike).
     * Negativni polozaj je 0; brez trajanja (tok v zivo, se nalaga) vrne prazen niz - crte in casa takrat ni.
     */
    fun casNapredka(polozajMs: Long, trajanjeMs: Long): String {
        if (trajanjeMs <= 0) return ""
        val p = polozajMs.coerceAtLeast(0L)
        val ure = trajanjeMs >= 3_600_000L || p >= 3_600_000L
        return if (ure) String.format(java.util.Locale.ROOT, "%d:%02d:%02d / %d:%02d:%02d",
            p / 3_600_000L, (p % 3_600_000L) / 60_000L, (p % 60_000L) / 1000L,
            trajanjeMs / 3_600_000L, (trajanjeMs % 3_600_000L) / 60_000L, (trajanjeMs % 60_000L) / 1000L)
        else String.format(java.util.Locale.ROOT, "%d:%02d / %d:%02d",
            p / 60_000L, (p % 60_000L) / 1000L, trajanjeMs / 60_000L, (trajanjeMs % 60_000L) / 1000L)
    }

    /** Delež napredka za crto 0..100; brez trajanja ali pred zacetkom 0, cez konec 100 (crta ne sme uit). */
    fun odstotek(polozajMs: Long, trajanjeMs: Long): Int {
        if (trajanjeMs <= 0 || polozajMs <= 0L) return 0
        if (polozajMs >= trajanjeMs) return 100
        return (polozajMs * 100 / trajanjeMs).toInt()
    }

    /** Ozek zaslon (telefon pokoncen) 170 dp - pas ne sme vzeti pol ekrana. Brez visine zaslona: 230 dp (staro vedenje). */
    fun visinaDp(sirinaDp: Int): Int = visinaDp(sirinaDp, 0)

    /**
     * Zgled Jellyfin + KefinTweaks (lastnik 8. 11. 2026): na TV in lezeci tablici je pas velik uvod strani - 42 % visine
     * zaslona v mejah 300-460 dp, a nikoli vec kot 55 % visine (nizek zaslon) in ne manj kot 170 dp. Ozek zaslon 170 dp.
     */
    fun visinaDp(sirinaDp: Int, visinaZaslonaDp: Int): Int = when {
        sirinaDp < 600 -> 170
        visinaZaslonaDp <= 0 -> 230
        // Slepi pregled 8. 11. (6): spodnja meja 300 dp je bila na nizkem zaslonu (telefon lezece 360 dp) 83 % zaslona -
        // pas ne sme preseci 55 % visine; najmanj pa 170 dp kot na telefonu.
        else -> (visinaZaslonaDp * 42 / 100).coerceIn(300, 460).coerceAtMost(visinaZaslonaDp * 55 / 100).coerceAtLeast(170)
    }

    /** Opis (2 vrstici) gre v pas sele od te visine - nizje bi z naslovom 40 sp in gumbi odrezal vrh naslova (pregled 7). */
    const val OPIS_OD_DP = 290   // drugi pregled (2): TV 960x540 dp ima pas 297 dp - naslov, opis, crta in gumbi gredo (~286 dp)

    fun prikaziOpis(siroko: Boolean, visinaPasuDp: Int, imaOpis: Boolean): Boolean = imaOpis && siroko && visinaPasuDp >= OPIS_OD_DP

    /** Naslov uvoda po zgledu KefinTweaks: na TV 40 sp (berljiv s kavca, kot naslov v zgledu), na telefonu 20 sp. */
    fun velikostNaslovaSp(sirinaDp: Int): Float = velikostNaslovaSp(sirinaDp, 0)

    /**
     * Drugi pregled (3): na nizkem sirokem pasu (telefon lezece, ~200 dp) dve vrstici po 40 sp z gumbi ne gresta -
     * pod 260 dp je naslov 24 sp. [visinaPasuDp] 0 = neznana (velja sirina).
     */
    fun velikostNaslovaSp(sirinaDp: Int, visinaPasuDp: Int): Float = when {
        sirinaDp < 600 -> 20f
        visinaPasuDp in 1 until 260 -> 24f
        else -> 40f
    }

    /** Izbor na pasu krozi vsakih [SEKUND_KROZENJA] s (zgled: pike desno spodaj). */
    const val SEKUND_KROZENJA = 8
    /** Najvec toliko vsebin krozi v pasu (in toliko pik) - vec bi bilo nepregledno. */
    const val NAJVEC_KROZENJA = 6

    /** Naslednji element krozenja; prazen seznam vrne 0. */
    fun naslednji(indeks: Int, n: Int): Int = if (n <= 0) 0 else Math.floorMod(indeks + 1, n)

    /**
     * Krozi samo izbor: ne, ko igra ali je nedokoncana vsebina (ta ostane), ne, ko ima pas ali gumb fokus (uporabnik
     * bere ali bo kliknil - klik ne sme odpreti druge vsebine), ne pri zmanjsanem gibanju in ne z manj kot dvema.
     */
    fun smeKroziti(vrsta: String, imaFokus: Boolean, zmanjsanoGibanje: Boolean, n: Int): Boolean =
        vrsta == IZBOR && !imaFokus && !zmanjsanoGibanje && n >= 2
}
