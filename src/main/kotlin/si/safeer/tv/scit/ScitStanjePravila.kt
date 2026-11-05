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
     * Drugi krog pregleda 5. 11. 2026: poizvedbe, starejse od [potekMs], ob polni vrsti (dodeliId) in v vzdrzevanju niso
     * vec tiho izginile - aplikacija dobi [servfail] (prej je cakala 5 s na svoj rok). Odstranitev je atomarna
     * (remove(id, c)): pravi odgovor, ki je poizvedbo vzel prej, vedno zmaga in SERVFAIL se ne poslje. Vrne stevilo opuscenih.
     */
    fun <T> opustiPotekle(cakajoce: java.util.concurrent.ConcurrentMap<Int, T>, zdaj: Long, potekMs: Long,
                          poslana: (T) -> Long, servfail: (T) -> Unit): Int {
        var n = 0
        for ((id, c) in cakajoce) {
            if (zdaj - poslana(c) > potekMs && cakajoce.remove(id, c)) { servfail(c); n++ }
        }
        return n
    }

    /**
     * Drugi krog pregleda 5. 11. 2026 (ScitActivity): osvezevanje vsaki 2 s je dodalo novo branje, tudi ce prejsnje se ni
     * koncalo - pri pocasnem branju so se naloge kopicile. Hkrati tece najvec eno; nujna zahteva (dejanje uporabnika) med
     * branjem se izvede enkrat po njem, navadna osvezitev se izpusti.
     */
    class EnoBranje {
        private val tece = java.util.concurrent.atomic.AtomicBoolean(false)
        private val znova = java.util.concurrent.atomic.AtomicBoolean(false)
        /** true = zacni branje; false = branje ze tece (ce [nujno], se po njem ponovi). */
        fun zacni(nujno: Boolean): Boolean {
            if (tece.compareAndSet(false, true)) return true
            if (nujno) znova.set(true)
            return false
        }
        /** Konec branja; true = med branjem je prisla nujna zahteva, beri se enkrat. */
        fun konec(): Boolean { tece.set(false); return znova.getAndSet(false) }
    }

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
