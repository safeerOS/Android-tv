package si.safeer.tv.os

/**
 * Videi YouTube kanala po vrsti (lastnik 21. 11. 2026): glasba, ostalo in kratki posnetki (Shorts) se uporabniku
 * pokazejo loceno, glasba kanala pa se predvaja zapored (konec skladbe -> naslednja skladba istega kanala).
 * Ciste odlocitve brez Androida; preizkus: tests/YoutubeKanalVrstaTest.kt. Isto pravilo naj uporabijo vse naprave
 * (Linux: core/youtube_kanal_vrsta.py).
 *
 * YouTube kategorije videa v seznamu kanala ne da, zato odlocajo trajanje in znacilnosti naslova ter kanala. Ob dvomu
 * je posnetek OSTALO: napacna skladba v seznamu glasbe moti bolj kot skladba med ostalim.
 */
object YoutubeKanalVrsta {
    enum class Vrsta { GLASBA, OSTALO, KRATKI }

    /** Shorts so dolgi do 3 minut, a pogosto navedeni kot do 60 s; vse do tu je kratek posnetek. */
    const val KRATKI_NAJVEC_S = 60
    /** »Izvajalec - Naslov« brez druge znacilnosti je skladba samo, ce traja vsaj toliko (kratki zapisi so zabava). */
    const val GLASBA_PO_VZORCU_NAJMANJ_S = 100

    /** Skladba je daljsa od kratkega posnetka in krajsa od [GLASBA_NAJVEC_S] (daljse so zbirke, setlisti, podcasti). */
    const val GLASBA_NAJVEC_S = 12 * 60

    private val NI_GLASBA = Regex(
        """\b(podcast|interview|intervju|vlog|reaction|reakcija|trailer|tutorial|review|unboxing|episode|ep\.?\s*\d+|behind the scenes|""" +
            """making of|track by track|how to|q&a|livestream|stream highlights|news|documentary|dokumentar|oddaja|epizoda|tour|what'?s in|fridge|challenge|day in the life|diary|answers|asks|talks|""" +
            """meet and greet|announcement|obvestilo)\b""",
        RegexOption.IGNORE_CASE)
    private val JE_GLASBA = Regex(
        """\b(official (music )?video|official audio|lyric(s)? video|lyrics|audio|live (at|from|in)|acoustic|remix|""" +
            """feat\.?|ft\.?|music video|videospot|single|album|cover)\b""",
        RegexOption.IGNORE_CASE)
    private val IZVAJALEC_NASLOV = Regex("""^\S.{0,60}\s[-–—]\s\S.{0,100}$""")

    /** Trajanje iz napisa `m:ss` ali `h:mm:ss`; null, ce ni v tej obliki. */
    fun trajanje(napis: String?): Int? {
        val d = napis?.trim()?.split(':') ?: return null
        if (d.size !in 2..3) return null
        if (!d.first().all { it.isDigit() } || d.first().length !in 1..3) return null
        if (d.drop(1).any { it.length != 2 || !it.all { c -> c.isDigit() } }) return null
        val n = d.map { it.toInt() }
        if (n.drop(1).any { it > 59 }) return null
        return if (n.size == 2) n[0] * 60 + n[1] else n[0] * 3600 + n[1] * 60 + n[2]
    }

    /**
     * Vrsta videa. [jeShorts]: video je iz zavihka Shorts ali ima povezavo /shorts/. [trajanjeS]: znano trajanje ali
     * null. [kanal]: ime kanala (»Izvajalec - Topic« in VEVO sta glasbena kanala).
     */
    fun vrsta(naslov: String, kanal: String, trajanjeS: Int?, jeShorts: Boolean = false): Vrsta {
        if (jeShorts || naslov.contains("#shorts", ignoreCase = true) || (trajanjeS != null && trajanjeS <= KRATKI_NAJVEC_S)) return Vrsta.KRATKI
        if (trajanjeS != null && trajanjeS > GLASBA_NAJVEC_S) return Vrsta.OSTALO
        if (NI_GLASBA.containsMatchIn(naslov)) return Vrsta.OSTALO
        val glasbeniKanal = kanal.trim().endsWith("- Topic", ignoreCase = true) || kanal.trim().endsWith("VEVO", ignoreCase = true)
        val znacilnost = glasbeniKanal || JE_GLASBA.containsMatchIn(naslov)
        // Brez znanega trajanja odloca samo izrazita znacilnost; z znanim je dovolj »Izvajalec - Naslov«.
        return when {
            znacilnost -> Vrsta.GLASBA
            trajanjeS != null && trajanjeS >= GLASBA_PO_VZORCU_NAJMANJ_S && IZVAJALEC_NASLOV.matches(naslov.trim()) -> Vrsta.GLASBA
            else -> Vrsta.OSTALO
        }
    }
}
