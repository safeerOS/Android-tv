package si.safeer.tv.os

/**
 * Enote NAL v toku zaslona racunalnika (Annex-B) za H.264 in HEVC: vrsta enote in kaj z njo naredi gledalec.
 * Brez Androida, da se da preizkusiti na JVM.
 */
object ZaslonKodek {
    /**
     * Kaj gledalec zna, za `caps` v `screen.start`: preklop na namizje racunalnika, kadar je program odprt tam
     * (program ene same instance), dolga skupina slik (enot ne izpuscamo, zato kljucna slika vsako sekundo ni
     * potrebna) in utrip `rtt` (odgovor na meritev zakasnitve, [ZaslonUtrip]). Racunalnik kaj novega poslje sele, ko
     * to potrdi se v glavi toka; starejsi racunalnik seznam prezre. [okus] je za zmoznosti, ki jih ne bodo imeli vsi
     * okusi aplikacije - zdaj jih imajo vsi enake.
     */
    fun zmoznosti(@Suppress("UNUSED_PARAMETER") okus: String): List<String> = listOf("handoff", "gop", "rtt")

    /** Kodeki slike, ki jih gledalec zna, po prednosti; HEVC samo, ce ga naprava strojno dekodira. */
    fun seznam(hevcStrojno: Boolean): List<String> = if (hevcStrojno) listOf("hevc", "h264") else listOf("h264")

    /** Vrsta enote iz prvega bajta za zacetno kodo: H.264 spodnjih 5 bitov, HEVC biti 1-6. */
    fun vrstaEnote(hevc: Boolean, prviBajt: Int): Int =
        if (hevc) (prviBajt shr 1) and 0x3f else prviBajt and 0x1f

    /**
     * HEVC: enote, ki niso slika (VPS 32, SPS 33, PPS 34, AUD 35, SEI 39/40 ...), pocakajo na naslednjo sliko in gredo
     * z njo v dekoder v enem medpomnilniku. H.264 ostane, kot je bil (vsaka enota posebej).
     */
    fun cakaNaSliko(hevc: Boolean, vrsta: Int): Boolean = hevc && vrsta >= 32

    /** H.264: SPS (7) in PPS (8) gresta v dekoder oznacena kot nastavitev. Pri HEVC gredo nastavitve s sliko. */
    fun jeNastavitev(hevc: Boolean, vrsta: Int): Boolean = !hevc && (vrsta == 7 || vrsta == 8)

    /** Toliko slik HEVC sme dekoder dobiti, ne da bi vrnil eno samo, preden recemo, da tega toka ne zna. */
    const val SLIK_BREZ_IZHODA = 60

    /**
     * Dekoder HEVC, ki po [SLIK_BREZ_IZHODA] poslanih slikah ni vrnil nobene, toka ne zna: gledalec sejo zahteva znova
     * s H.264. Stejemo slike in ne casa - na pocasni povezavi prva (kljucna) slika lahko prihaja vec sekund.
     */
    fun hevcBrezSlike(hevc: Boolean, poslanihSlik: Int, imaSliko: Boolean): Boolean =
        hevc && !imaSliko && poslanihSlik >= SLIK_BREZ_IZHODA

    /**
     * Ali HEVC na tej napravi se ponudimo: ne, ce je dekoder v tej razlicici aplikacije ze odpovedal. Po posodobitvi
     * aplikacije poskusimo znova.
     */
    fun hevcDovoljen(odpovedalVRazlicici: Long, razlicica: Long): Boolean =
        odpovedalVRazlicici == 0L || odpovedalVRazlicici != razlicica
}
