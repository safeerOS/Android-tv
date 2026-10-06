package si.safeer.tv.os

/**
 * Zvok: imena zapisov in kaj uporabnik v resnici dobi iz zvocnikov. Cista pravila brez Androida
 * (tests/ZvokPravilaTest.kt).
 *
 * Ozadje (6. 10. 2026): predvajalne verige zvok Dolby Atmos pogosto tiho zmesajo v stereo - film tece,
 * uporabnik pa ne ve, da prostorskega zvoka ni dobil. Safeer tok preda zvocniku, kadar sistem to ponudi, izbere tok,
 * ki ga veriga res odda, in uporabniku POVE, kaj gre ven.
 */
object ZvokPravila {
    /** Ime zapisa zvocne sledi, kot ga pozna uporabnik; prazno za neznan zapis. [oznaka]: ime sledi v datoteki. */
    fun imeZapisa(mime: String?, oznaka: String? = null): String {
        val atmos = oznaka?.contains("atmos", ignoreCase = true) == true
        return when (mime?.lowercase()) {
            "audio/eac3-joc" -> "Dolby Atmos"
            // V datotekah Matroska je Atmos navadna sled Dolby Digital Plus; da je v njej Atmos, pove le ime sledi.
            "audio/eac3" -> if (atmos) "Dolby Atmos" else "Dolby Digital Plus"
            "audio/ac3" -> "Dolby Digital"
            "audio/true-hd" -> if (atmos) "Dolby TrueHD Atmos" else "Dolby TrueHD"
            "audio/ac4" -> "Dolby AC-4"
            "audio/vnd.dts" -> "DTS"
            "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS-HD"
            "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
            "audio/mp4a-latm" -> "AAC"
            "audio/mpeg" -> "MP3"
            "audio/mpeg-l2" -> "MP2"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            "audio/alac" -> "ALAC"
            "audio/raw" -> "PCM"
            else -> ""
        }
    }

    /** Razpored kanalov, kot ga pise na ovitkih: mono, stereo, 5.1, 7.1. */
    fun kanali(n: Int): String = when (n) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (n > 0) "$n ch" else "" }

    private fun prostorski(ime: String) = ime.startsWith("Dolby") || ime.startsWith("DTS")

    /**
     * Kaj gre iz zvocnikov, kadar je to vredno povedati; prazno za navaden stereo.
     *
     * [mimeSledi], [kanalovSledi], [oznakaSledi] opisujejo izbrano sled; [izhodPcm] pove, ali jo je naprava
     * dekodirala (true) ali predala zvocniku nedotaknjeno (false); [kanalovIzhoda] je stevilo kanalov, ki gredo ven.
     *  - predano:    »Dolby Atmos«, »Dolby Digital Plus 5.1«, »DTS 5.1«
     *  - dekodirano v manj kanalov, kot jih ima sled: »Dolby Atmos → stereo«, »5.1 → stereo« (uporabnik izve, da
     *    prostorskega zvoka NE dobi - na televizorju je vzrok navadno nastavitev digitalnega izhoda ali zvocnik)
     *  - dekodirano v vec kanalov: »Dolby Digital 5.1«, »5.1«
     * [povejZmesanje]: na telefonu in tablici je mesanje v stereo pricakovano (zvocnik, slusalke) - tam ga ne
     * omenjamo; na televizorju pove, da prostorski zvok ne pride do zvocnika.
     * [zvocnikZmore]: false, kadar zunanji zvocnik svoje zapise navede in predanega zapisa ni med njimi (npr. DTS na
     * zvocniku brez DTS): tok dekodira naprava sama in ne vemo, koliko kanalov gre ven - povemo samo zapis sledi.
     */
    fun oznakaIzhoda(mimeSledi: String?, kanalovSledi: Int, izhodPcm: Boolean, kanalovIzhoda: Int, oznakaSledi: String? = null,
                     povejZmesanje: Boolean = true, zvocnikZmore: Boolean = true): String {
        val ime = imeZapisa(mimeSledi, oznakaSledi)
        val p = prostorski(ime)
        if (!izhodPcm) {
            if (!p) return ""
            if (!zvocnikZmore) return imeZapisa(mimeSledi)
            return if (ime.endsWith("Atmos") || kanalovSledi <= 2) ime else "$ime ${kanali(kanalovSledi)}"
        }
        if (kanalovSledi > 2 && kanalovIzhoda in 1..2)
            return if (povejZmesanje) (if (p) ime else kanali(kanalovSledi)) + " → " + kanali(kanalovIzhoda) else ""
        if (kanalovIzhoda > 2) return listOf(if (p && !ime.endsWith("Atmos")) ime else "", kanali(kanalovIzhoda)).filter { it.isNotBlank() }.joinToString(" ")
        return ""
    }

    // ------------------------------------------------------------------ samodejna izbira zvocne sledi

    /**
     * Zvocna sled, kot jo vidi samodejna izbira: zapis, kanali, jezik, ime sledi v datoteki, ali je oznacena kot
     * komentar ali opis za slepe in ali jo ta naprava sploh zmore (dekoder ali predaja zvocniku).
     */
    class Sled(val mime: String?, val kanalov: Int, val jezik: String?, val ime: String?, val komentar: Boolean, val podprta: Boolean)

    private val KOMENTAR = Regex("comment|komentar|kommentar|commentaire|comentario|commento|descri|audiodes|opis za", RegexOption.IGNORE_CASE)

    private fun jeKomentar(s: Sled) = s.komentar || s.ime?.let { KOMENTAR.containsMatchIn(it) } == true

    /** Jezik brez podrocja; neznan (prazno, »und«) je svoj »jezik« - z znanim se ne ujema. */
    private fun jezikSledi(s: Sled): String = s.jezik?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }?.substringBefore('-').orEmpty()

    /** Kljuc zapisa v naboru predaje ([ZvokIzhod.predaja]); null za zapis, ki ga naprava vedno dekodira. */
    fun kljucPredaje(mime: String?): String? = when (mime?.lowercase()) {
        "audio/eac3-joc" -> "atmos"
        "audio/eac3" -> "eac3"
        "audio/ac3" -> "ac3"
        "audio/true-hd" -> "truehd"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd;profile=p2" -> "dts"
        else -> null
    }

    /**
     * Kaj izhod pri tej sledi res odda: stevilo kanalov in ali je zvok objektni (Atmos). [predaja]: zapisi, ki jih izhod
     * sprejme nedotaknjene; [kanalovPcm]: najvec kanalov dekodiranega zvoka (zvocnik telefona, slusalke, Bluetooth: 2).
     * Atmos v Dolby Digital Plus je zdruzljiv nazaj: izhod brez Atmosa ga odda kot navaden Dolby Digital Plus.
     */
    fun oddano(s: Sled, predaja: Set<String>, kanalovPcm: Int): Pair<Int, Boolean> {
        if (s.kanalov <= 0) return 0 to false
        val k = kljucPredaje(s.mime)
        if (k != null && k in predaja) return s.kanalov to (k == "atmos")
        if (k == "atmos" && "eac3" in predaja) return s.kanalov to false
        return minOf(s.kanalov, maxOf(kanalovPcm, 2)) to false
    }

    /**
     * Sled, ki jo izhod te naprave odda bolje kot [izbrana] (indeks v [sledi]), ali null, ce take ni.
     *
     * Kandidat mora biti v ISTEM jeziku (jezika in komentarja ne menjamo uporabniku za hrbtom), naprava ga mora zmoci
     * in ne sme biti komentar ali opis. Boljsi je, kadar izhod odda vec kanalov, ali enako kanalov, a kot Atmos. Pri
     * enakem izidu ostane izbrana sled (zapisa pri istem stevilu kanalov ne menjamo). Na izhodu, ki zmore le stereo, je
     * vsaka sled »stereo« - tam se ne spremeni nic.
     */
    fun boljsaSled(sledi: List<Sled>, izbrana: Int, predaja: Set<String>, kanalovPcm: Int): Int? {
        val zdaj = sledi.getOrNull(izbrana) ?: return null
        if (jeKomentar(zdaj)) return null
        var (kNaj, oNaj) = if (zdaj.podprta) oddano(zdaj, predaja, kanalovPcm) else 0 to false
        var najboljsa: Int? = null
        for ((i, s) in sledi.withIndex()) {
            if (i == izbrana || !s.podprta || jeKomentar(s) || jezikSledi(s) != jezikSledi(zdaj)) continue
            val (k, o) = oddano(s, predaja, kanalovPcm)
            if (k > kNaj || (k == kNaj && o && !oNaj)) { najboljsa = i; kNaj = k; oNaj = o }
        }
        return najboljsa
    }

    /**
     * Ali zunanji zvocnik predani zapis sam zna. [zvocnik]: zapisi, ki jih navaja naprava na HDMI/ARC/eARC (kljuci kot
     * v [kljucPredaje]); null = ni zunanjega zvocnika ali zapisov ne navaja (takrat ne trdimo nicesar - velja »zmore«).
     * Atmos v Dolby Digital Plus zmore tudi zvocnik, ki navaja le Dolby Digital Plus (odda ga brez predmetov).
     */
    fun zvocnikZmore(mime: String?, zvocnik: Set<String>?): Boolean {
        if (zvocnik == null) return true
        val k = kljucPredaje(mime) ?: return true
        return k in zvocnik || (k == "atmos" && "eac3" in zvocnik)
    }

    /** Vrstica v izbiri zvocne sledi: jezik ali ime sledi, kanali in zapis - brez ponavljanja, ce zapis ze pove ime. */
    fun opisSledi(imeAliJezik: String?, kanalov: Int, mime: String?, oznaka: String?): String {
        val zapis = imeZapisa(mime, oznaka)
        val ime = imeAliJezik?.trim().orEmpty()
        val zeVImenu = zapis.isNotBlank() && (ime.contains(zapis, ignoreCase = true) ||
            (zapis.endsWith("Atmos") && ime.contains("atmos", ignoreCase = true)) || ime.contains(zapis.substringBefore(' ') + " ", ignoreCase = true))
        val k = kanali(kanalov)
        val kanaliVImenu = k.isNotBlank() && ime.contains(k, ignoreCase = true)
        return listOf(ime, if (kanaliVImenu) "" else k, if (zeVImenu) "" else zapis).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
