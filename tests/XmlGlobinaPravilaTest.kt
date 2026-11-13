package si.safeer.tv.os

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

/** Neodvisni pregled 12. 11. 2026 (N1): globoko gnezden XML ne sme na DOM (StackOverflowError ni Exception). */
fun main(args: Array<String>) {
    val G = XmlGlobinaPravila
    preveri(G.globina("<rss><channel><item><title>x</title></item></channel></rss>") == 4, "navaden RSS ima globino 4")
    preveri(G.globina("<a><b/><c x=\"/>\"/></a>") == 1, "samozaprte oznake in > v atributu")
    preveri(G.globina("<?xml version=\"1.0\"?><!-- <a><a><a> --><r><![CDATA[<a><a><a>]]></r>") == 1, "komentar, CDATA, PI se preskocijo")
    val globoko = "<a>".repeat(100_000) + "</a>".repeat(100_000)
    preveri(G.globina(globoko) == 100_000, "100000 gnezdenj se prestejejo v enem prehodu")
    preveri(G.globina(globoko) > G.NAJVEC_GLOBINA, "globoko je nad mejo")
    // Povezava: UsenetPravila.zadetki zavrne globoko gnezdenje (brez StackOverflowError), navaden RSS deluje
    val rssGloboko = "<rss><channel>" + "<a>".repeat(200_000) + "<item><title>T</title><enclosure url=\"https://x.example/a\"/></item></channel></rss>"
    preveri(UsenetPravila.zadetki(rssGloboko, "https://x.example").isEmpty(), "globok RSS: prazen izid, brez izjeme")
    val rssNavaden = "<rss><channel><item><title>T</title><enclosure url=\"https://x.example/a\"/></item></channel></rss>"
    preveri(UsenetPravila.zadetki(rssNavaden, "https://x.example").map { it.naslov } == listOf("T"), "navaden RSS ostane")
    println("XmlGlobinaPravilaTest: OK ($stevilo preverb)")
}
