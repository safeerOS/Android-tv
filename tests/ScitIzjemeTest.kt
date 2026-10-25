package si.safeer.tv.scit

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Webtor rezerva: izjeme Scita ([ScitIzjeme]) - stalne domene Webtor in gostitelji tokov iz preusmeritve. */
fun main() {
    println("Preizkus izjem Scita")
    val I = ScitIzjeme
    preveriEnako("webtor.io", true, I.dovoljena("webtor.io", emptyList()))
    preveriEnako("api.webtor.io", true, I.dovoljena("API.webtor.io.", emptyList()))
    preveriEnako("gostitelj toka", true, I.dovoljena("abra--x1.api.cosmic-crab.buzz", emptyList()))
    preveriEnako("podobno ime ni izjema", false, I.dovoljena("evilwebtor.io", emptyList()))
    preveriEnako("pripona drugje ni izjema", false, I.dovoljena("cosmic-crab.buzz.evil.com", emptyList()))
    preveriEnako("dodatna", true, I.dovoljena("x.tok.example.net", listOf("tok.example.net")))
    preveriEnako("prazno", false, I.dovoljena("", emptyList()))

    val ura = 1_000_000L
    val m = I.dodaj(emptyMap(), "Tok.Example.net", ura)
    preveriEnako("dodaj nov", mapOf("tok.example.net" to ura + I.VELJA_MS), m)
    preveriEnako("ze stalna: brez spremembe", null, I.dodaj(emptyMap(), "abra--1.api.cosmic-crab.buzz", ura))
    preveriEnako("nedavno dodan: brez zapisa", null, I.dodaj(m!!, "tok.example.net", ura + 1000))
    preveriEnako("naslov IP ni domena", null, I.dodaj(emptyMap(), "192.168.1.1", ura))
    preveriEnako("neveljavno ime", null, I.dodaj(emptyMap(), "a..b", ura))
    var vec: Map<String, Long> = emptyMap()
    for (i in 1..25) vec = I.dodaj(vec, "t$i.example.net", ura + i)!!
    preveriEnako("najvec 20", 20, vec.size)
    preveriEnako("najstarejsi izpadejo", false, "t1.example.net" in vec)
    val besedilo = I.zapis(mapOf("a.example.net" to ura + 10, "b.example.net" to ura - 10)) + "\nsmeti\nc..x\t99999999999"
    preveriEnako("preberi: poteklo in neveljavno izpade", mapOf("a.example.net" to ura + 10), I.preberi(besedilo, ura))
    preveriEnako("preberi prazno", emptyMap<String, Long>(), I.preberi(null, ura))

    if (napak == 0) println("ScitIzjemeTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
