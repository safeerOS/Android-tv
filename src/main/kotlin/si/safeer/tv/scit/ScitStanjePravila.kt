package si.safeer.tv.scit

/**
 * Ciste odlocitve Scita za zaslone (Nastavitve, zaslon Scita) in za poizvedbe DNS brez odgovora - pregled Nastavitev in
 * Scita 5. 11. 2026. Brez Androida; preizkus: tests/ScitStanjePravilaTest.kt (tests/run_scit_tests.sh).
 */
object ScitStanjePravila {
    enum class Stanje { IZKLOPLJEN, PREKINJEN, TECE }

    /**
     * Dejansko stanje iz zastavice in statistike: drug VPN prevzame tunel (onRevoke), zastavica »vklopljen« ostane (uporabnik
     * Scita ni izklopil), storitev pa ne tece. Prej so Nastavitve takrat kazale »Vklopljeno«, uporabnik pa ni bil zascien.
     */
    fun stanje(vklopljen: Boolean, tece: Boolean): Stanje = when {
        !vklopljen -> Stanje.IZKLOPLJEN
        tece -> Stanje.TECE
        else -> Stanje.PREKINJEN
    }

    enum class Gumb { VKLOPI, VKLOPI_ZNOVA, IZKLOPI }

    /**
     * Gumbi zaslona Scita v tem vrstnem redu (prvi dobi fokus). Prekinjen: najprej ponovni vklop (prej je bil tam samo
     * Izklopi in zascite z daljincem ni bilo mogoce vrniti), izklop ostane na voljo.
     */
    fun gumbi(s: Stanje): List<Gumb> = when (s) {
        Stanje.IZKLOPLJEN -> listOf(Gumb.VKLOPI)
        Stanje.TECE -> listOf(Gumb.IZKLOPI)
        Stanje.PREKINJEN -> listOf(Gumb.VKLOPI_ZNOVA, Gumb.IZKLOPI)
    }

    enum class Neodgovorjena { CAKAJ, PONOVI, SERVFAIL }

    /**
     * Koliko po zadnjem vprasanem strezniku se caka na odgovor, preden aplikacija dobi SERVFAIL. Vec od pocasnega pravega
     * odgovora usmerjevalnika (1-2 s), manj od 5 s, ki jih razresevalnik Androida caka na molk (AGENTS.md, Scit DNS).
     */
    const val ROK_ZADNJEGA_MS = 2_500L

    /**
     * Poizvedba, poslana navzgor ([poslana] = prvo posiljanje, [zadnjic] = zadnje), ki se nima odgovora: [naslednji] je
     * indeks se nevprasanega streznika od [strezniki]. PONOVI pri naslednjem po [ponovitevMs]; SERVFAIL, ko so vsi
     * vprasani in je od zadnjega minil [rokZadnjegaMs], ali ob [potekMs] - prej je poizvedba po 8 s tiho izginila in
     * aplikacija je cakala na svoj rok. Pravi odgovor, ki pride prej, poizvedbo odstrani in SERVFAIL se ne poslje.
     */
    fun neodgovorjena(zdaj: Long, poslana: Long, zadnjic: Long, naslednji: Int, strezniki: Int,
                      ponovitevMs: Long, potekMs: Long, rokZadnjegaMs: Long = ROK_ZADNJEGA_MS): Neodgovorjena = when {
        zdaj - poslana > potekMs -> Neodgovorjena.SERVFAIL
        naslednji < strezniki -> if (zdaj - zadnjic >= ponovitevMs) Neodgovorjena.PONOVI else Neodgovorjena.CAKAJ
        zdaj - zadnjic >= rokZadnjegaMs -> Neodgovorjena.SERVFAIL
        else -> Neodgovorjena.CAKAJ
    }
}
