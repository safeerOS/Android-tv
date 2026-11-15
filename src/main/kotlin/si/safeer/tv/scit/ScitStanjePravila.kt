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

    /**
     * Ali brskalnik sam filtrira oglasne zahteve (shouldInterceptRequest). Lastnik 15. 11. 2026: Scit v ozadju nadomesti
     * brskalnikovo blokiranje, brskalnik je hitrejsi (izmerjeno 11. 11. 2026: lastno filtriranje zahtev + kozmeticni CSS ~0,3 s na 24ur).
     * Brskalnik filtrira samo, ce uporabnik blokiranje oglasov ni izklopil IN DNS Scit ne filtrira (rezerva: tunel ne tece,
     * drug VPN ga je prevzel, nabor se se nalaga). Kozmeticna pravila ostanejo (DNS ne more skriti praznih okvirjev).
     */
    fun brskalnikFiltriraOglase(blokiranjeVklopljeno: Boolean, dnsFiltrira: Boolean): Boolean = blokiranjeVklopljeno && !dnsFiltrira

    /**
     * Ali DNS Scit dokazano filtrira: storitev (proces :scit) je zapisala stanje »tece« in nalozen nabor (domen > 0), uporabnik ga
     * ni izklopil, IN na napravi dejansko obstaja VPN omrezje (zapis »tece« lahko preziveti ubit proces, tunel pa ne).
     * Brez katerega koli od teh brskalnik filtrira sam.
     */
    fun dnsFiltrira(vklopljen: Boolean, stanje: String, domen: Int, vpnOmrezje: Boolean, starostUtripaMs: Long): Boolean =
        vklopljen && stanje == "tece" && domen > 0 && vpnOmrezje && starostUtripaMs in 0..UTRIP_NAJVEC_MS

    /**
     * Utrip storitve (datoteka, ki jo :scit osvezi vsakih 30 s): brez utripa je zapis »tece« lahko ostanek ubitega procesa, tudi ce
     * kateri drug VPN drzi tunel (slepi pregled XX1, 15. 11. 2026). Dve zamudi urnika sta se dopustni; starejsi ali prihodnji utrip
     * (ura nazaj) = ne filtrira, brskalnik filtrira sam.
     */
    const val UTRIP_NAJVEC_MS = 90_000L

    /**
     * Utrip sme pisati samo storitev, ki res dela (slepi pregled ZZ1, 15. 11. 2026): urnik je loceno nit, zato je utripal tudi, ko je zanka
     * paketov ali zanka odgovorov ze izstopila (napaka branja tunela) ali je storitev ze ustavljena; brskalnik takrat ne bi filtriral,
     * DNS Scit pa tudi ne. Brez utripa brskalnik filtrira sam (rezerva).
     */
    fun utripSmeZapisati(tece: Boolean, zankaPaketovZiva: Boolean, zankaOdgovorovZiva: Boolean): Boolean =
        tece && zankaPaketovZiva && zankaOdgovorovZiva

    /** Najdlje tako dolgo (ms) velja prebrano stanje v procesu brskalnika: zahteve so stotine na sekundo, branje datoteke ni zastonj. */
    const val ROK_STANJA_MS = 5_000L

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
