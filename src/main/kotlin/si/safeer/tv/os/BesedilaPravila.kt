package si.safeer.tv.os

/**
 * Naloga 89 (zamisel iz BitChord: besedila, sinhronizirana s posnetkom): ciste odlocitve za besedila pesmi iz LRCLIB
 * (lrclib.net, odprt vmesnik brez kljuca). Brez Androida in omrezja (preizkus: tests/BesedilaPravilaTest.kt); zahtevke dela
 * [Besedila], in to samo, ce je uporabnik LRCLIB dodal kot vir (naslov in izvajalec gresta tujemu strezniku).
 */
object BesedilaPravila {
    /** Vrstica sinhroniziranega besedila: [ms] = kdaj se zacne, [besedilo] (prazno = premor, instrumentalni del). */
    data class Vrstica(val ms: Long, val besedilo: String)

    private val RX_CAS = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val RX_ZAMIK = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * Besedilo LRC v vrstice po casu. Vrstica ima lahko vec casov (`[00:12.00][01:30.50]Refren`); oznake brez casa
     * (`[ar:…]`, `[ti:…]`) odpadejo; `[offset:+ms]` premakne vse vrstice (pozitiven = besedilo prej, kot v LRC).
     * Stotinke in tisocinke: `.5` = 500 ms, `.50` = 500 ms, `.500` = 500 ms.
     */
    fun razcleni(lrc: String): List<Vrstica> {
        var zamik = 0L
        val izhod = ArrayList<Vrstica>()
        for (surova in lrc.lines()) {
            val l = surova.trim()
            val z = RX_ZAMIK.find(l)
            if (z != null) { zamik = z.groupValues[1].toLongOrNull() ?: 0L; continue }
            val casi = ArrayList<Long>()
            var ostanek = l
            while (true) {
                val m = RX_CAS.find(ostanek)?.takeIf { it.range.first == 0 } ?: break
                val min = m.groupValues[1].toLong()
                val sek = m.groupValues[2].toLong()
                val del = m.groupValues[3]
                val ms = if (del.isEmpty()) 0L else del.padEnd(3, '0').take(3).toLong()
                casi += (min * 60 + sek) * 1000 + ms
                ostanek = ostanek.substring(m.value.length)
            }
            if (casi.isEmpty()) continue
            val besedilo = ostanek.trim()
            for (c in casi) izhod += Vrstica((c - zamik).coerceAtLeast(0L), besedilo)
        }
        return izhod.sortedBy { it.ms }
    }

    /** Indeks vrstice, ki se poje ob [polozajMs] (zadnja z zacetkom <= polozaj); -1 = pred prvo. */
    fun trenutna(vrstice: List<Vrstica>, polozajMs: Long): Int {
        var lo = 0
        var hi = vrstice.size - 1
        var izid = -1
        while (lo <= hi) {
            val sredina = (lo + hi) ushr 1
            if (vrstice[sredina].ms <= polozajMs) { izid = sredina; lo = sredina + 1 } else hi = sredina - 1
        }
        return izid
    }

    private val RX_OKLEPAJ = Regex("""\s*[(\[]([^)\]]*)[)\]]""")
    private val SMETI = Regex("""(?i)\b(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|remaster(ed)?|mv|m/v|clip|videoclip|live)\b""")
    private val RX_FEAT = Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$""")

    /**
     * (izvajalec, naslov) za iskanje iz kartice: naslov posnetka YouTube je pogosto »Izvajalec - Naslov (Official Video)«,
     * izvajalec pa ime kanala (»… - Topic«, »…VEVO«). Oklepaji z oznakami posnetka in »feat.« odpadejo; ce naslov nosi
     * izvajalca pred » - « in izvajalec manjka ali je kanal, ga vzamemo iz naslova.
     */
    fun iskalniPar(naslov: String, izvajalec: String): Pair<String, String> {
        var n = RX_OKLEPAJ.replace(naslov) { m -> if (SMETI.containsMatchIn(m.groupValues[1]) || RX_FEAT.containsMatchIn(" " + m.groupValues[1])) "" else m.value }.trim()
        var i = izvajalec.trim().removeSuffix(" - Topic").replace(Regex("(?i)vevo$"), "").trim()
        val deli = n.split(" - ", " – ", " — ", limit = 2)
        if (deli.size == 2 && deli[0].isNotBlank() && deli[1].isNotBlank() &&
            (i.isEmpty() || izvajalec.endsWith(" - Topic") || izvajalec.endsWith("VEVO", ignoreCase = true) || deli[0].equals(i, ignoreCase = true))) {
            i = deli[0].trim(); n = deli[1].trim()
        }
        n = RX_FEAT.replace(n, "").trim().trim('"', '\'', '«', '»', '“', '”')
        return i to n
    }

    /**
     * Parametri poizvedbe LRCLIB /api/get (ze kodirani za URL): izvajalec, naslov in dolzina v sekundah (LRCLIB izbere
     * posnetek z dolzino +-2 s). Brez dolzine (tok brez znane dolzine) samo izvajalec in naslov.
     */
    fun poizvedba(izvajalec: String, naslov: String, sekund: Long): String {
        fun k(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "artist_name=${k(izvajalec)}&track_name=${k(naslov)}" + (if (sekund > 0) "&duration=$sekund" else "")
    }

    /** Ali vnos v »Dodaj vir« pomeni LRCLIB (lrclib.net, tudi s potjo ali shemo; presledki s tipkovnice TV odpadejo). */
    fun jeVirLrclib(vnos: String): Boolean {
        val v = vnos.trim().lowercase(java.util.Locale.ROOT).filter { it.code in 0x21..0x7E }
        val gostitelj = v.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore(':').trimEnd('.')
        return gostitelj == "lrclib.net" || gostitelj.endsWith(".lrclib.net")
    }

    /**
     * Besedilo je smiselno samo za pesmi: ne za radio in kanal v zivo (ni dolocene pesmi) in ne za film ali serijo
     * (dialog - za to so podnapisi).
     */
    fun smiselno(radio: Boolean, vZivo: Boolean, filmAliSerija: Boolean): Boolean = !radio && !vZivo && !filmAliSerija

    /** Zapis »ni besedila« velja krajse kot najdeno besedilo: cez toliko casa vprasamo znova (LRCLIB se dopolnjuje). */
    const val NI_BESEDILA_MS = 7L * 24 * 3_600_000

    /** Ali zapis »ni besedila« iz [cas] se velja. */
    fun niBesedilaVelja(cas: Long, zdaj: Long): Boolean = zdaj - cas in 0 until NI_BESEDILA_MS

    /** Kljuc predpomnilnika besedila: izvajalec in naslov (malo), dolzina na 5 s natancno (ista pesem, drug posnetek). */
    fun kljuc(izvajalec: String, naslov: String, sekund: Long): String =
        "${izvajalec.lowercase(java.util.Locale.ROOT)}|${naslov.lowercase(java.util.Locale.ROOT)}|${(sekund + 2) / 5}"
}
