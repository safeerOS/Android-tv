package si.safeer.tv

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private const val ISKANJE = "https://www.google.com/search?q="

private fun naslov(vnos: String, pricakovano: String, preveri: String? = null) {
    val o = SmartOmnibox.razresi(vnos, ISKANJE)
    preveri("'$vnos' -> $pricakovano", o != null && !o.iskanje && o.url == pricakovano && o.preveri == preveri)
    if (o == null || o.url != pricakovano) println("        dobil: $o")
}

private fun iskanje(vnos: String) {
    val o = SmartOmnibox.razresi(vnos, ISKANJE)
    preveri("'$vnos' -> iskanje", o != null && o.iskanje && o.url.startsWith(ISKANJE))
    if (o == null || !o.iskanje) println("        dobil: $o")
}

fun main() {
    println("SmartOmnibox: naslov ali iskanje")
    naslov("safeer.si", "https://safeer.si", "safeer.si")
    naslov("youtube.com", "https://youtube.com", "youtube.com")
    naslov("  YouTube.com/tv  ", "https://youtube.com/tv", "youtube.com")
    naslov("www.rtvslo.si/novice?x=1#a", "https://www.rtvslo.si/novice?x=1#a", "www.rtvslo.si")
    naslov("ščit.si", "https://xn--it-dma94a.si", "xn--it-dma94a.si")
    naslov("http://primer.si", "http://primer.si")
    naslov("https://primer.si/a b", "https://primer.si/a%20b")
    naslov("localhost", "http://localhost")
    naslov("localhost:8080/api", "http://localhost:8080/api")
    naslov("192.168.0.1", "http://192.168.0.1")
    naslov("10.0.0.5:8123", "http://10.0.0.5:8123")
    naslov("tiskalnik.local", "http://tiskalnik.local")
    naslov("about:blank", "about:blank")
    naslov("primer.si:443", "https://primer.si:443", "primer.si")
    iskanje("najboljši filmi 2026")
    iskanje("Breaking Bad")
    iskanje("breaking")
    iskanje("1.5")
    iskanje("3.14159")
    iskanje("e.g.")
    iskanje("javascript:alert(1)")
    iskanje("JavaScript:alert(1)")
    iskanje("data:text/html,<b>x</b>")
    iskanje("intent://x#Intent;end")
    iskanje("ime@gmail.com")
    iskanje("256.1.1.1")
    iskanje("primer.si:99999")
    iskanje("-primer.si")
    iskanje("c++")
    iskanje("https://")
    iskanje("co je.si")
    preveri("prazno -> nic", SmartOmnibox.razresi("   ", ISKANJE) == null)
    preveri("sumniki v iskanju so kodirani", SmartOmnibox.razresi("čaj", ISKANJE)?.url == ISKANJE + "%C4%8Daj")
    preveri("presledki so strnjeni", SmartOmnibox.razresi("a    b", ISKANJE)?.url == ISKANJE + "a+b")

    println("SmartOmnibox: iskani niz iz naslova strani z zadetki")
    preveri("Google", SmartOmnibox.poizvedba("https://www.google.com/search?q=defrag+windows&sca_esv=1") == "defrag windows")
    preveri("parameter ni prvi, sumniki", SmartOmnibox.poizvedba("https://www.google.com/search?hl=sl&q=%C4%8Daj#ip=1") == "čaj")
    preveri("parameter s podobnim imenom", SmartOmnibox.poizvedba("https://www.google.com/search?oq=staro&q=novo") == "novo")
    preveri("DuckDuckGo", SmartOmnibox.poizvedba("https://duckduckgo.com/?q=safeer+os&ia=web") == "safeer os")
    preveri("Brave", SmartOmnibox.poizvedba("https://search.brave.com/search?q=a%26b") == "a&b")
    preveri("zacetna stran iskalnika ni iskanje", SmartOmnibox.poizvedba("https://www.google.com/") == null)
    preveri("druga stran istega gostitelja", SmartOmnibox.poizvedba("https://www.google.com/maps?q=ljubljana") == null)
    preveri("prazen niz", SmartOmnibox.poizvedba("https://www.google.com/search?q=") == null)
    preveri("pokvarjeno kodiranje", SmartOmnibox.poizvedba("https://www.google.com/search?q=%E0%A4%A") == null)
    preveri("navadna stran", SmartOmnibox.poizvedba("https://safeer.si/?q=nekaj") == null)

    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
