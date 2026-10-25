package si.safeer.tv.scit

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Webtor rezerva: izjeme Scita ([ScitIzjeme]) - stalne domene Webtor, naucene natanko, javne pripone, seznami, potek. */
fun main() {
    println("Preizkus izjem Scita")
    val I = ScitIzjeme
    val ura = 1_000_000L
    val nic: (String) -> Boolean = { false }
    preveriEnako("webtor.io", true, I.dovoljena("webtor.io", emptyMap(), ura))
    preveriEnako("api.webtor.io", true, I.dovoljena("API.webtor.io.", emptyMap(), ura))
    preveriEnako("gostitelj toka (stalna)", true, I.dovoljena("abra--x1.api.cosmic-crab.buzz", emptyMap(), ura))
    preveriEnako("podobno ime ni izjema", false, I.dovoljena("evilwebtor.io", emptyMap(), ura))
    preveriEnako("pripona drugje ni izjema", false, I.dovoljena("cosmic-crab.buzz.evil.com", emptyMap(), ura))
    preveriEnako("naucena: natanko", true, I.dovoljena("x.tok.example.net", mapOf("x.tok.example.net" to ura + 10), ura))
    preveriEnako("naucena: poddomena NI dovoljena", false, I.dovoljena("a.x.tok.example.net", mapOf("x.tok.example.net" to ura + 10), ura))
    preveriEnako("naucena: pretecena ne velja ob preverbi (N5)", false, I.dovoljena("x.tok.example.net", mapOf("x.tok.example.net" to ura - 1), ura))
    preveriEnako("prazno", false, I.dovoljena("", emptyMap(), ura))

    preveriEnako("registrabilna .com", "example.com", I.registrabilna("a.b.example.com"))
    preveriEnako("registrabilna co.uk", "evil.co.uk", I.registrabilna("x.evil.co.uk"))
    preveriEnako("registrabilna github.io", "abc.github.io", I.registrabilna("abc.github.io"))
    preveriEnako("primeren: tok Webtor", true, I.primerenZaUcenje("abra--1.api.new-crab.buzz"))
    preveriEnako("neprimeren: 2 oznaki", false, I.primerenZaUcenje("doubleclick.net"))
    preveriEnako("neprimeren: javna pripona co.uk", false, I.primerenZaUcenje("co.uk"))
    preveriEnako("neprimeren: registrabilna pod co.uk", false, I.primerenZaUcenje("evil.co.uk"))
    preveriEnako("neprimeren: registrabilna pod github.io", false, I.primerenZaUcenje("abc.github.io"))
    preveriEnako("neprimeren: blogspot.com", false, I.primerenZaUcenje("blogspot.com"))
    preveriEnako("neprimeren: naslov IP", false, I.primerenZaUcenje("192.168.1.10"))
    preveriEnako("primeren: poddomena pod co.uk", true, I.primerenZaUcenje("cdn.evil.co.uk"))

    val m = I.dodaj(emptyMap(), "Tok.Example.net", ura, nic)
    preveriEnako("dodaj nov", mapOf("tok.example.net" to ura + I.VELJA_MS), m)
    preveriEnako("ze stalna: brez spremembe", null, I.dodaj(emptyMap(), "abra--1.api.cosmic-crab.buzz", ura, nic))
    preveriEnako("na seznamu (oglasi/grozenje): nikoli", null, I.dodaj(emptyMap(), "ads.doubleclick.net", ura) { it.endsWith("doubleclick.net") })
    preveriEnako("napaka preverbe seznama: ne dodaj", null, I.dodaj(emptyMap(), "a.tok.example.net", ura) { error("x") })
    preveriEnako("javna pripona: ne dodaj", null, I.dodaj(emptyMap(), "co.uk", ura, nic))
    preveriEnako("2 oznaki: ne dodaj", null, I.dodaj(emptyMap(), "doubleclick.net", ura, nic))
    preveriEnako("nedavno dodan: brez zapisa", null, I.dodaj(m!!, "tok.example.net", ura + 1000, nic))
    preveriEnako("naslov IP ni domena", null, I.dodaj(emptyMap(), "192.168.1.1", ura, nic))
    preveriEnako("neveljavno ime", null, I.dodaj(emptyMap(), "a..b.c", ura, nic))
    var vec: Map<String, Long> = emptyMap()
    for (i in 1..25) vec = I.dodaj(vec, "t$i.tok.example.net", ura + i, nic)!!
    preveriEnako("najvec 20", 20, vec.size)
    preveriEnako("najstarejsi izpadejo", false, "t1.tok.example.net" in vec)
    val besedilo = I.zapis(mapOf("a.tok.example.net" to ura + 10, "b.tok.example.net" to ura - 10, "co.uk" to ura + 10)) + "\nsmeti\nc..x\t99999999999"
    preveriEnako("preberi: preteceno, neveljavno in javna pripona izpadejo", mapOf("a.tok.example.net" to ura + 10), I.preberi(besedilo, ura))
    preveriEnako("preberi prazno", emptyMap<String, Long>(), I.preberi(null, ura))

    // Pregled U10: stalne izjeme samo natancni gostitelji Webtorja in vzorec gostiteljev toka - ne vse poddomene.
    preveriEnako("x.webtor.io ni vec stalna izjema", false, I.dovoljena("x.webtor.io", emptyMap(), ura))
    preveriEnako("poljubna poddomena cosmic-crab.buzz ni izjema", false, I.dovoljena("zlo.cosmic-crab.buzz", emptyMap(), ura))
    preveriEnako("cosmic-crab.buzz sama ni izjema", false, I.dovoljena("cosmic-crab.buzz", emptyMap(), ura))
    preveriEnako("gostitelj toka po vzorcu", true, I.dovoljena("abra--1.api.cosmic-crab.buzz", emptyMap(), ura))
    preveriEnako("vzorec ne sprejme dodatne oznake", false, I.dovoljena("a.abra--1.api.cosmic-crab.buzz", emptyMap(), ura))
    // Brez nabora grozenj izjeme ne veljajo (fail-closed).
    preveriEnako("brez grozenj: na seznamu, izjema -> blokirano", true, I.blokirana(true, false, false, true))
    preveriEnako("z grozenji: izjema -> ni blokirano", false, I.blokirana(true, true, false, true))
    preveriEnako("na seznamu grozenj -> blokirano tudi z izjemo", true, I.blokirana(true, true, true, true))
    preveriEnako("ni na seznamu -> ni blokirano", false, I.blokirana(false, false, false, false))
    preveriEnako("na seznamu, ni izjema -> blokirano", true, I.blokirana(true, true, false, false))

    if (napak == 0) println("ScitIzjemeTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
