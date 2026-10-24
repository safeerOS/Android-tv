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

    /**
     * Meni sporocila: povezava se da odpreti; kopiranje in »Izbrisi pri meni« vedno. Prazno sporocilo: samo brisanje.
     * [naVseh] (naloga 116): lastno poslano sporocilo v Safeer Linku s skupnim id-jem - se »Izbrisi na vseh mojih napravah«.
     */
    fun dejanjaSporocila(besedilo: String, jePovezava: Boolean, naVseh: Boolean = false): List<String> {
        val osnova = when {
            besedilo.isBlank() -> listOf(IZBRISI)
            jePovezava -> listOf(ODPRI, KOPIRAJ, IZBRISI)
            else -> listOf(KOPIRAJ, IZBRISI)
        }
        return if (naVseh) osnova + IZBRISI_VSE else osnova
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

    /** Izbrisan pogovor velja do najnovejsega casa njegovih sporocil ali pogovora (ISO 8601, primerjava kot niz). */
    fun izbrisanDo(casiSporocil: List<String>, casPogovora: String): String =
        (casiSporocil + casPogovora).filter { it.isNotBlank() }.maxOrNull().orEmpty()

    /**
     * Ali uskladitev kanala pogovor znova pokaze: samo novo sporocilo ([casNovega] po [izbrisanDo]). Ponovno prenesena stara
     * sporocila (e-posta znova prebere nabiralnik) izbrisanega pogovora ne vrnejo.
     */
    fun pogovorSeVrne(casNovega: String, izbrisanDo: String): Boolean = izbrisanDo.isBlank() || casNovega > izbrisanDo
}
