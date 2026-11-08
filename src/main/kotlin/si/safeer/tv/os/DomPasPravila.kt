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

    /** TV in tablica lezece 230 dp, ozek zaslon (telefon pokoncen) 170 dp - pas ne sme vzeti pol ekrana. */
    fun visinaDp(sirinaDp: Int): Int = if (sirinaDp < 600) 170 else 230

    /** Naslov na 50" TV mora biti berljiv s kavca (StreamNexus HD): 28 sp proti 20 sp na telefonu. */
    fun velikostNaslovaSp(sirinaDp: Int): Float = if (sirinaDp < 600) 20f else 28f
}
