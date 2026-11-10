package si.safeer.tv.os

/**
 * Filter vira »TorBox« v mrezi Filmi/Serije - cista logika brez Androida (preizkus tests/FilterTorBoxPravilaTest.kt).
 *
 * Lastnik 10. 11. 2026: TorBox ima v mrezi svoj filter kot Usenet. TorBox lahko pripravi tudi naslov, ki ga se nima, a
 * uporabnik mora PRED izbiro vedeti, da to traja najdlje; ce ima naslov takoj predvajljiv tok drug vir (neposredna
 * povezava dodatka), ponudimo najprej tega in oznake »Priprava« ne pokazemo (uporabnik lahko gleda takoj).
 * Vse tri podatke da ISTI odgovor dodatkov in en klic checkcached - brez dodatnih poizvedb drugim virom.
 */
object FilterTorBoxPravila {
    const val VIR_TORBOX = "torbox"

    /** Kaj lahko uporabnik z naslovom naredi pri izbiri TorBox. */
    enum class Stanje {
        /** TorBox ima torrent pripravljen: OK predvaja takoj. */
        TAKOJ,
        /** TorBox ga nima, drug vir ima tok, ki ga naprava zmore: OK ponudi tega, priprava je druga moznost. */
        TAKOJ_DRUGJE,
        /** Predvajanje je mozno samo po pripravi pri TorBoxu: oznaka na plakatu, nikoli samodejno. */
        PRIPRAVA,
        /** Viri so odgovorili, toka ni: naslov se ne pokaze. */
        NI,
        /** Dodatki ali TorBox niso odgovorili: naslov se ne pokaze in se NE zapise kot negativen. */
        NE_VEMO,
    }

    /**
     * [torrentov]: koliko torrentov so dali dodatki (null = dodatki niso odgovorili); [pripravljenih]: koliko jih ima
     * TorBox pripravljenih (null = TorBox ni odgovoril); [takojDrugje]: dodatki so dali neposredni tok, ki ga naprava zmore.
     */
    fun stanje(torrentov: Int?, pripravljenih: Int?, takojDrugje: Boolean): Stanje = when {
        torrentov == null -> Stanje.NE_VEMO
        torrentov > 0 && pripravljenih != null && pripravljenih > 0 -> Stanje.TAKOJ
        takojDrugje -> Stanje.TAKOJ_DRUGJE
        torrentov == 0 -> Stanje.NI
        pripravljenih == null -> Stanje.NE_VEMO
        else -> Stanje.PRIPRAVA
    }

    /** V mrezi so naslovi, ki se dajo predvajati takoj ali po pripravi. */
    fun pokazi(s: Stanje): Boolean = s == Stanje.TAKOJ || s == Stanje.TAKOJ_DRUGJE || s == Stanje.PRIPRAVA

    /** Oznaka »Priprava« samo tam, kjer je priprava edina pot. */
    fun oznakaPriprave(s: Stanje): Boolean = s == Stanje.PRIPRAVA

    /** Vrstni red v mrezi: takoj predvajljivi pred tistimi, ki cakajo pripravo; znotraj skupine ostane vrstni red kataloga. */
    fun <T> uredi(naslovi: List<T>, stanje: (T) -> Stanje): List<T> =
        naslovi.filter { pokazi(stanje(it)) }.sortedBy { if (stanje(it) == Stanje.PRIPRAVA) 1 else 0 }

    /** Samo potrjen »ni« gre v predpomnilnik kot negativen; »ne vemo« se preveri znova. */
    fun zapisiNegativno(s: Stanje): Boolean = s == Stanje.NI

    /** Gumbi okna ob OK po vrsti; prvi je izbran. Priprava se nikoli ne zacne brez potrditve. */
    enum class Gumb { PREDVAJAJ, PREDVAJAJ_DRUGJE, PRIPRAVI_IN_OBVESTI, PREKLICI }
    fun gumbi(s: Stanje): List<Gumb> = when (s) {
        Stanje.TAKOJ -> listOf(Gumb.PREDVAJAJ)
        Stanje.TAKOJ_DRUGJE -> listOf(Gumb.PREDVAJAJ_DRUGJE, Gumb.PRIPRAVI_IN_OBVESTI, Gumb.PREKLICI)
        Stanje.PRIPRAVA -> listOf(Gumb.PRIPRAVI_IN_OBVESTI, Gumb.PREKLICI)
        Stanje.NI, Stanje.NE_VEMO -> emptyList()
    }
}
