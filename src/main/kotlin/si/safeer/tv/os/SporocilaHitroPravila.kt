package si.safeer.tv.os

/**
 * Naloga 42 C (27. 10. 2026): Sporocila na hitro na domacem zaslonu novega zaganjalnika. Zadnja sporocila; ko fokus
 * pride na sporocilo, se pokazejo dejanja: kopiraj, odgovori, poslji kopirano. Novo sporocilo pokaze tiho opozorilo, ki
 * ne vzame fokusa in ne ustavi predvajanja. Ciste odlocitve brez Androida (tests/SporocilaHitroPravilaTest.kt).
 */
object SporocilaHitroPravila {
    /** Na domacem zaslonu najvec toliko pogovorov (zaslon ostane zaganjalnik, ne predal sporocil). */
    const val NAJVEC = 3
    /** Tiho opozorilo je na zaslonu toliko casa, nato izgine samo. */
    const val OPOZORILO_MS = 6_000L
    const val IZSEK = 80
    const val NAJVEC_ODGOVORA = 16 * 1024

    const val KOPIRAJ = "kopiraj"
    const val ODGOVORI = "odgovori"
    const val POSLJI_KOPIRANO = "poslji_kopirano"

    data class Pogovor(val id: String, val kanalId: String, val ime: String, val zadnje: String, val neprebrano: Int, val cas: String)

    /** Najnovejsi pogovori z besedilom (prazen predogled ni sporocilo), najnovejsi prvi. */
    fun zadnja(pogovori: List<Pogovor>, najvec: Int = NAJVEC): List<Pogovor> =
        pogovori.filter { it.zadnje.isNotBlank() && it.id.isNotBlank() }.sortedByDescending { it.cas }.take(najvec)

    /**
     * Ena vrstica za zaslon: brez kontrolnih znakov (tudi smeri besedila, ki bi zamenjale vrstni red), presledki
     * strnjeni, predolgo skrajsano s tropicjem.
     */
    fun izsek(besedilo: String, najvec: Int = IZSEK): String {
        val cisto = besedilo.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim()
        return if (cisto.length <= najvec) cisto else cisto.take(najvec - 1).trimEnd() + "…"
    }

    /**
     * Dejanja za sporocilo s fokusom. [odgovor]: pogovor zna odgovor s tega zaslona (Safeer Link). Poslji kopirano samo,
     * ce je v odlozisu kaj in to ni ze to isto sporocilo (sicer bi ga poslali nazaj).
     */
    fun dejanja(besedilo: String, odgovor: Boolean, odlozisce: String?): List<String> {
        if (besedilo.isBlank()) return emptyList()
        val d = mutableListOf(KOPIRAJ)
        if (odgovor) d.add(ODGOVORI)
        val kopirano = odlozisce?.trim().orEmpty()
        if (odgovor && kopirano.isNotEmpty() && kopirano != besedilo.trim()) d.add(POSLJI_KOPIRANO)
        return d
    }

    /** Besedilo za posiljanje (odgovor ali kopirano) ali null, ce ga ni ali je predolgo. */
    fun zaPosiljanje(besedilo: String?): String? {
        val t = besedilo?.trim().orEmpty()
        return if (t.isEmpty() || t.length > NAJVEC_ODGOVORA) null else t
    }

    /**
     * Tiho opozorilo ob novem sporocilu: samo, ce je zaslon Safeer OS v ospredju in ta pogovor ni odprt (v odprtem
     * pogovoru je sporocilo ze na zaslonu). Predvajanje se nikoli ne ustavi - [ustaviPredvajanje] je vedno false.
     */
    fun opozorilo(vOspredju: Boolean, odprtPogovor: Boolean): Boolean = vOspredju && !odprtPogovor

    fun ustaviPredvajanje(): Boolean = false
}
