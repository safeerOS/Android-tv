package si.safeer.tv.os

/**
 * Naloga 106 (lastnik: »Sporocila med napravami - Izbrisi«): ciste odlocitve za krajevno brisanje sporocil in pogovorov
 * ([SporocilaShramba]). Brez Androida (preizkus: tests/SporocilaPravilaTest.kt). Brisanje je SAMO na tej napravi - druga
 * stran (e-posta, Matrix, Safeer Link) sporocila obdrzi. Naloga 116: lastno poslano sporocilo v Safeer Linku se da izbrisati
 * tudi na napravi, ki ga je prejela (»Izbrisi na vseh mojih napravah«).
 */
object SporocilaPravila {
    /** Dejanja ob dolgem pritisku na sporocilo, po vrsti v meniju. */
    const val ODPRI = "odpri"
    const val KOPIRAJ = "kopiraj"
    const val IZBRISI = "izbrisi"
    const val IZBRISI_VSE = "izbrisi_vse"
    /** Pregled 25. 10. 2026 (D5): namesto »Izbrisi na vseh« opomba, da druga naprava brisanja ne podpira. */
    const val IZBRISI_VSE_NI = "izbrisi_vse_ni"

    /**
     * Meni sporocila: povezava se da odpreti; kopiranje in »Izbrisi pri meni« vedno. Prazno sporocilo: samo brisanje.
     * [naVseh] (naloga 116): lastno poslano sporocilo v Safeer Linku s skupnim id-jem - se »Izbrisi na vseh mojih napravah«,
     * ce ga naprava, ki ga je prejela, zna izbrisati ([naVsehPodprto]); sicer opomba [IZBRISI_VSE_NI] (pregled D5).
     */
    fun dejanjaSporocila(besedilo: String, jePovezava: Boolean, naVseh: Boolean = false, naVsehPodprto: Boolean = true): List<String> {
        val osnova = when {
            besedilo.isBlank() -> listOf(IZBRISI)
            jePovezava -> listOf(ODPRI, KOPIRAJ, IZBRISI)
            else -> listOf(KOPIRAJ, IZBRISI)
        }
        return when {
            !naVseh -> osnova
            naVsehPodprto -> osnova + IZBRISI_VSE
            else -> osnova + IZBRISI_VSE_NI
        }
    }

    // ------------------------------------------------------------------ izbris na vseh napravah (naloga 116)
    /** Lastno poslano sporocilo Linka: »chat:ven:<id>«, prejeto: »chat:<id>«; <id> je id sporocila chat.send (UUID). */
    const val PREDPONA_POSLANO = "chat:ven:"
    const val PREDPONA_PREJETO = "chat:"
    private val UUID_VZOREC = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Skupni id lastnega poslanega sporocila (ga pozna tudi prejemnik) ali null. Sporocila, poslana pred 116, imajo
     * krajevni id (cas v ns) - prejemnik jih hrani pod drugim id-jem, zato zanje izbrisa na vseh napravah ni.
     */
    fun skupniId(lokalniId: String, smer: String): String? {
        if (smer != "ven" || !lokalniId.startsWith(PREDPONA_POSLANO)) return null
        return lokalniId.removePrefix(PREDPONA_POSLANO).takeIf { UUID_VZOREC.matches(it) }
    }

    /** Id, pod katerim prejemnik hrani sporocilo s skupnim id-jem [skupni] (KlepetLinka.prejmi). */
    fun prejetiId(skupni: String): String = PREDPONA_PREJETO + skupni

    /**
     * Ali zahteva za izbris velja: izbrise se samo prejeto sporocilo ([smer] »noter«) v pogovoru naprave, ki ga je poslala
     * ([pogovorSporocila] == [posiljatelj]). Tuja naprava tako ne more izbrisati sporocil drugih ali nasih lastnih.
     */
    fun zahtevaZaIzbrisVelja(smer: String, pogovorSporocila: String, posiljatelj: String): Boolean =
        smer == "noter" && posiljatelj.isNotBlank() && pogovorSporocila == posiljatelj

    const val BRISANJE_KONCANO = 0
    const val BRISANJE_PONOVI = 1
    const val BRISANJE_OPUSTI = 2

    /** Najdlje cakajoci izbris (naprava, ki se en mesec ne poveze, ga ne dobi vec). */
    const val BRISANJE_NAJDLJE_MS = 30L * 24 * 3600 * 1000

    /**
     * Kaj z zahtevo za izbris po odgovoru sredisca ([izid] iz LinkOdjemalec.posljiKlepet): »accepted« (dostavljeno) in
     * »queued« (sredisce jo hrani do povezave naprave) - koncano; brez povezave, potek ali napaka sredisca - ponovi ob
     * naslednji uskladitvi; naprave ni vec v Linku ali je to ista naprava - opusti. Prestar zapis se opusti.
     */
    fun izidBrisanja(izid: String, zapisanoMs: Long, zdajMs: Long): Int = when {
        izid == "accepted" || izid == "queued" -> BRISANJE_KONCANO
        izid == "ni_naprave" || izid == "isti_naprava" -> BRISANJE_OPUSTI
        zdajMs - zapisanoMs > BRISANJE_NAJDLJE_MS -> BRISANJE_OPUSTI
        else -> BRISANJE_PONOVI
    }

    /**
     * Zadnje sporocilo pogovora po izbrisu: [ostala] = (besedilo, cas) preostalih sporocil od najstarejsega do najnovejsega.
     * Brez preostalih: prazno besedilo, cas pogovora ostane ([casPogovora]) - pogovor ostane na istem mestu v seznamu.
     */
    fun zadnjePoIzbrisu(ostala: List<Pair<String, String>>, casPogovora: String): Pair<String, String> =
        ostala.lastOrNull()?.let { (b, c) -> b.replace('\n', ' ').trim().take(240) to c } ?: ("" to casPogovora)

    /** Id-ji sporocil, ki jih izbris pogovora oznaci kot izbrisane (brez praznih in dvojnikov). */
    fun zaIzbrisPogovora(idji: List<String>): List<String> = idji.filter { it.isNotBlank() }.distinct()

    /**
     * Izbrisan pogovor velja do najnovejsega casa njegovih sporocil ali pogovora (ISO 8601, primerjava kot niz), a najvec do
     * krajevnega casa izbrisa [zdaj] (pregled D3): cas sporocil je ura POSILJATELJA - ce je bila ta naprej, bi sicer nova
     * sporocila do tistega casa tiho izginila. Ali se pogovor vrne, odloci predvsem tabela izbrisanih id-jev
     * (SporocilaShramba.shraniSporocilo: novo, se neznano sporocilo vrne pogovor ne glede na uro).
     */
    fun izbrisanDo(casiSporocil: List<String>, casPogovora: String, zdaj: String = ""): String {
        val najkasneje = (casiSporocil + casPogovora).filter { it.isNotBlank() }.maxOrNull().orEmpty()
        return if (zdaj.isNotBlank() && najkasneje > zdaj) zdaj else najkasneje
    }

    /**
     * Ali uskladitev kanala pogovor znova pokaze: samo novo sporocilo ([casNovega] po [izbrisanDo]). Ponovno prenesena stara
     * sporocila (e-posta znova prebere nabiralnik) izbrisanega pogovora ne vrnejo.
     */
    fun pogovorSeVrne(casNovega: String, izbrisanDo: String): Boolean = izbrisanDo.isBlank() || casNovega > izbrisanDo

    /** Pregled 8: id sporocila e-poste (IMAP UID) - po spremembi UIDVALIDITY dobijo ista stara sporocila nove id-je. */
    fun jeEposta(id: String): Boolean = id.startsWith("uid:")

    /**
     * Pregled 8: do katerega casa velja izbris pogovora. E-posta: trenutek izbrisa (zdaj) - odloca cas prejema na strezniku
     * ([novoVIzbrisanem]), ne ura posiljatelja; drugi kanali kot doslej ([izbrisanDo]).
     */
    fun izbrisanDoZaKanal(casiSporocil: List<String>, casPogovora: String, zdaj: String, eposta: Boolean): String =
        if (eposta && zdaj.isNotBlank()) zdaj else izbrisanDo(casiSporocil, casPogovora, zdaj)

    /**
     * Pregled 8: ali se novo (se neznano) sporocilo shrani in vrne izbrisan pogovor. [izbrisanDo] null = pogovor ni izbrisan.
     * [prejeto] null (Safeer Link, Chatwoot: id je stalen) - novo sporocilo vrne pogovor (pregled D3). E-posta: samo, ce ga
     * je streznik prejel po izbrisu - ponovno prenesena stara sporocila z novimi UID (UIDVALIDITY) pogovora ne obudijo.
     */
    fun novoVIzbrisanem(prejeto: String?, izbrisanDo: String?): Boolean = when {
        izbrisanDo == null -> true
        prejeto == null -> true
        else -> pogovorSeVrne(prejeto, izbrisanDo)
    }

    // ------------------------------------------------------------------ izbris pred izvirnikom (pregled D4)
    /**
     * Zahteva za izbris lahko pride pred sporocilom (sredisce ju dostavi v drugem vrstnem redu, ali sporocilo caka v vrsti).
     * Takrat ostane zapis (naprava + skupni id), ki velja [VNAPREJ_VELJA_MS] po krajevni uri; izvirnik, ki pride kasneje,
     * se ne shrani. Najvec [VNAPREJ_NAJVEC] zapisov, da jih tuja naprava ne more nakopiciti.
     */
    const val VNAPREJ_VELJA_MS = 7L * 24 * 3600 * 1000
    const val VNAPREJ_NAJVEC = 500

    /** Ali sme zahteva naprave [od] za neznano sporocilo [skupni] pustiti zapis: znana naprava in pravi skupni id (UUID). */
    fun smeZapisatiVnaprej(od: String, skupni: String): Boolean = od.isNotBlank() && UUID_VZOREC.matches(skupni)

    /** Do kdaj velja zapis, narejen ob [zdajMs] (krajevna ura). */
    fun potekVnaprej(zdajMs: Long): Long = zdajMs + VNAPREJ_VELJA_MS

    /**
     * Ali zapis s potekom [potekaMs] ob [zdajMs] se velja. Zapis z vec kot [VNAPREJ_VELJA_MS] do poteka (ura je bila
     * medtem prestavljena nazaj) ne velja - tak bi sicer ostal predolgo.
     */
    fun vnaprejVelja(potekaMs: Long, zdajMs: Long): Boolean = zdajMs < potekaMs && potekaMs - zdajMs <= VNAPREJ_VELJA_MS

    // ------------------------------------------------------------------ zmoznost izbrisa na drugi napravi (pregled D5)
    /** Zmoznost, s katero naprava v Linku pove, da zna zahtevo za izbris (payload.delete, naloga 116). */
    const val ZMOZNOST_IZBRIS = "chat.delete"
    const val NA_DRUGI_DA = 0
    const val NA_DRUGI_NE = 1
    const val NA_DRUGI_NEZNANO = 2

    /**
     * Ali naprava pogovora zna izbris: [prijave] so zmoznosti vseh njenih prijav v seznamu Linka. Ni je v seznamu (brez
     * povezave) - neznano: izbris pocaka in se ob povezavi preveri znova (KlepetLinka.posljiBrisanje).
     */
    /**
     * Pregled 4: naprava je v seznamu, a zmoznosti [ZMOZNOST_IZBRIS] ne javi (prejsnja izdaja izbris zna - naloga 116 - a ga
     * ne javi). Izbris ostane v cakanju (ne posljemo ga, dokler je ne javi), dokler ni prestar - nikoli ga ne zavrzemo takoj.
     */
    fun brisanjeBrezZmoznosti(zapisanoMs: Long, zdajMs: Long): Int =
        if (zdajMs - zapisanoMs > BRISANJE_NAJDLJE_MS) BRISANJE_OPUSTI else BRISANJE_PONOVI

    /**
     * Pregled 4: ali meni ponudi »Izbrisi na vseh«. Signala, da naprava izbrisa izrecno ne zna (razlicica protokola), ni -
     * neznano in »ne javi« pomenita »morda zna«: izbris se ponudi in pocaka v vrsti ([brisanjeBrezZmoznosti]).
     */
    fun izbrisNaVsehPonudi(stanje: Int): Boolean = stanje == NA_DRUGI_DA || stanje == NA_DRUGI_NEZNANO || stanje == NA_DRUGI_NE

    fun izbrisNaDrugi(prijave: List<List<String>>): Int = when {
        prijave.isEmpty() -> NA_DRUGI_NEZNANO
        prijave.any { ZMOZNOST_IZBRIS in it } -> NA_DRUGI_DA
        else -> NA_DRUGI_NE
    }
}
