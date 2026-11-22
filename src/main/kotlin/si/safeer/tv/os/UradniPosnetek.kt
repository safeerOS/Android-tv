package si.safeer.tv.os

/**
 * Izbira uradnega posnetka skladbe med zadetki iskanja YouTube (lastnik 21. 11. 2026, ideja iz projekta Musike,
 * licenca MIT, avtor Ernani1234: ocenjevanje zadetkov po izvajalcu, naslovu, trajanju in rdecih zastavicah).
 * Napisano na novo, brez prevzete kode. Ciste odlocitve brez Androida; preizkus: tests/UradniPosnetekTest.kt.
 * Isto pravilo in iste primere uporabi Linux (core/uradni_posnetek.py).
 *
 * Nacelo: raje »ni posnetka« kot napacen posnetek (priredba, koncert, obozevalski nalog), zato mora biti nalagalec
 * izvajalcev lastni kanal (tudi »<Izvajalec>VEVO«, »<Izvajalec> Official«, »<Izvajalec> - Topic«).
 */
object UradniPosnetek {
    data class Zadetek(val id: String, val naslov: String, val kanal: String, val sekund: Int)

    private val NI_POSNETEK = Regex(
        """\b(cover|karaoke|nightcore|8d|slowed|reverb|sped up|lyrics?|besedilo|tutorial|reaction|live|koncert|remix|mashup|""" +
            """parody|parodija|instrumental|version|verzija|razlicica)\b""")
    private val URADNI_VIDEO = Regex("""\b(official (music )?video|music video|videospot|uradni (video|videospot|posnetek))\b""")
    // Oklepaji, ki ne spremenijo posnetka (»(Official Video)«, »(feat. X)«, »[4K]«).
    private val NEVTRALEN_DODATEK = Regex("""official|uradni|video|audio|visualizer|visualiser|feat|ft|with|hd|4k|remaster""")
    private val ZVOCNI = Regex("""\baudio\b""")
    private val V_OKLEPAJIH = Regex("\\((.*?)\\)|\\[(.*?)\\]")

    /** Male crke brez stresic, samo crke in stevke (tudi necirilske/nelatinske: »Кино«), en presledek. */
    fun ravno(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Kot [ravno], a brez oklepajev (»Trevo (Tu) (feat. X)« -> »trevo«). */
    fun golo(s: String): String = ravno(s.replace(V_OKLEPAJIH, " "))

    private fun strnjeno(s: String) = ravno(s).replace(" ", "")

    /** Nalagalec je izvajalcev lasten kanal; [kanal] je lahko seznam, locen z vejicami. */
    fun jeKanalIzvajalca(kanal: String, izvajalec: String): Boolean {
        val iskan = strnjeno(izvajalec)
        if (iskan.isEmpty()) return false
        val dovoljeni = setOf(iskan, iskan + "vevo", iskan + "oficial", iskan + "official", iskan + "tv", iskan + "music", iskan + "topic")
        return kanal.split(',').any { strnjeno(it) in dovoljeni }
    }

    /** Ocena zadetka za skladbo ali null, ce ni uradni posnetek te skladbe. */
    internal fun ocena(z: Zadetek, naslov: String, izvajalec: String, sekund: Int): Int? {
        val glavni = izvajalec.split(',').first().trim()
        if (!jeKanalIzvajalca(z.kanal, glavni)) return null
        val iskanNaslov = golo(naslov)
        val golNaslov = golo(z.naslov)
        // Cele besede: »One« ni v »Stone Cold Crazy« (CodeRabbit 22. 11. 2026, isto kot Linux).
        if (iskanNaslov.isEmpty() || !" $golNaslov ".contains(" $iskanNaslov ")) return null
        val vse = ravno(z.naslov)
        // Vsaka oznaka priredbe posebej: oznaka v naslovu skladbe (»Live Forever«) dovoli samo sebe, ne vseh drugih;
        // »Live and Let Die (Live)« ima »live« enkrat vec kot skladba - koncert (CodeRabbit 22. 11. 2026).
        val dovoljeno = oznake(ravno(naslov))
        var o = 0
        // »Izvajalec - Naslov (Official Video)« brez izvajalca je natancno ime skladbe.
        if (golNaslov.replace(golo(glavni), "").trim() == iskanNaslov) o += 2
        if (sekund > 0 && z.sekund > 0) {
            val razlika = kotlin.math.abs(z.sekund - sekund)
            o += when { razlika <= 10 -> 3; razlika <= 30 -> 1; razlika > 90 -> -2; else -> 0 }
        }
        // Koncert, priredba ali remix je drug posnetek (ne le slabsi): ce skladba sama tega ni, zadetek ne pride v postev.
        if (oznake(vse).any { (k, n) -> n > (dovoljeno[k] ?: 0) }) return null
        if (URADNI_VIDEO.containsMatchIn(vse)) o += 3
        // Zvocni nalozek je mirujoca slika: dopusten, a pravi videospot ga premaga.
        if (ZVOCNI.containsMatchIn(vse)) o -= 1
        for (dodatek in V_OKLEPAJIH.findAll(z.naslov)) {
            val d = ravno(dodatek.value)
            if (!NEVTRALEN_DODATEK.containsMatchIn(d) && !ravno(naslov).contains(d)) o -= 2
        }
        return o
    }

    private fun oznake(s: String): Map<String, Int> = NI_POSNETEK.findAll(s).groupingBy { it.groupValues[1] }.eachCount()

    /** Id najboljsega zadetka (med prvimi 10) ali null; pri enakem izidu ostane prvi zadetek. */
    fun izberi(zadetki: List<Zadetek>, naslov: String, izvajalec: String, sekund: Int): String? {
        var najboljsi: Pair<Zadetek, Int>? = null
        for (z in zadetki.take(10)) {
            val o = ocena(z, naslov, izvajalec, sekund) ?: continue
            if (najboljsi == null || o > najboljsi.second) najboljsi = z to o
        }
        return najboljsi?.first?.id
    }
}
