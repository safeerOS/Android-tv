package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun brezIzjeme(opis: String, f: () -> Any?) {
    try { f(); println("  ok  $opis") } catch (e: Throwable) { napak++; println("  NAPAKA  $opis: izjema $e") }
}

/** Neodvisni pregled 12. 11. 2026: S1 (izjema ob neveljavnem %) in S2 (skrajsan zapis naslova IP) v [MagnetPravila]. */
fun main(args: Array<String>) {
    println("Preizkus pravil magnet povezav")
    val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
    preveriEnako("veljaven magnet", hex, MagnetPravila.hash("magnet:?xt=urn:btih:${hex.uppercase()}&dn=Film"))
    preveriEnako("xt=%zz je neveljaven, brez izjeme", null, MagnetPravila.hash("magnet:?xt=%zz"))
    preveriEnako("xt s pustim % je neveljaven", null, MagnetPravila.hash("magnet:?xt=urn:btih:%"))
    preveriEnako("neveljaven xt, nato veljaven", hex, MagnetPravila.hash("magnet:?xt=%zz&xt=urn:btih:$hex"))
    brezIzjeme("zSledilniki s tr=% ne vrze izjeme") { MagnetPravila.zSledilniki("magnet:?xt=urn:btih:$hex&tr=%&dn=100%", listOf("udp://p.example.com:1/a")) }
    preveriEnako("zSledilniki preskoci neveljaven tr", "magnet:?xt=urn:btih:$hex&tr=udp%3A%2F%2Fp.example.com%3A1%2Fa",
        MagnetPravila.zSledilniki("magnet:?xt=urn:btih:$hex&tr=%", listOf("udp://p.example.com:1/a")))

    for (naslov in listOf("http://127.1/announce", "http://0x7f.0.0.1/a", "http://192.168.1/a", "http://10.1/a", "http://2130706433/a",
        "http://127.0.0.1/a", "http://192.168.0.1:80/a", "udp://[::1]:80/a", "http://localhost/a", "http://tracker.local/a")) {
        preveriEnako("domaci naslov zavrnjen: $naslov", false, MagnetPravila.javenSledilnik(naslov))
    }
    for (naslov in listOf("udp://tracker.opentrackr.org:1337/announce", "https://tracker.example.com/announce", "http://8.8.8.8/announce", "http://1.2.3.example.com/a")) {
        preveriEnako("javni sledilnik dovoljen: $naslov", true, MagnetPravila.javenSledilnik(naslov))
    }
    preveriEnako("shema file: zavrnjena", false, MagnetPravila.javenSledilnik("file:///etc/passwd"))

    // N2: dn brez znakov Cc/Cf (U+202E obrne smer in skrije koncnico)
    val rlo = "\u202e"
    preveriEnako("ocistiIme: Cf in Cc ven", "film gpj.exe", MagnetPravila.ocistiIme("film " + rlo + "gpj.exe"))
    preveriEnako("ocistiIme: brez U+202E", false, MagnetPravila.ocistiIme("a${rlo}b\u0000c\u200bd").any { Character.getType(it) == Character.FORMAT.toInt() || Character.isISOControl(it) })
    preveriEnako("zSledilniki: dn brez Cf", false, MagnetPravila.zSledilniki("magnet:?xt=urn:btih:$hex&dn=Film%E2%80%AEgpj.exe", emptyList()).contains("%E2%80%AE"))
    preveriEnako("DebridPravila.imeMagneta: brez Cf", "Film gpj.exe", DebridPravila.imeMagneta("magnet:?xt=urn:btih:$hex&dn=Film%E2%80%AEgpj.exe"))
    // S3: magnet iz dodatka - veljaven hash, omejeno stevilo sledilnikov
    val veliko = (1..200).map { "tracker:udp://t$it.example.com:80/announce" }
    val m = MagnetPravila.izDodatka(hex, "Film.mkv", veliko)
    preveriEnako("izDodatka: najvec 20 sledilnikov", MagnetPravila.NAJVEC_SLEDILNIKOV, m!!.split("&tr=").size - 1)
    preveriEnako("izDodatka: hash() ga prebere (pod 8192 znakov)", hex, MagnetPravila.hash(m))
    preveriEnako("izDodatka: neveljaven hash ni tok", null, MagnetPravila.izDodatka("$hex&tr=http%3A%2F%2Fzlo.example", "x", emptyList()))
    preveriEnako("izDodatka: prekratek hash", null, MagnetPravila.izDodatka("abc", "x", emptyList()))
    preveriEnako("izDodatka: base32 hash velja", true, MagnetPravila.izDodatka("YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK", "", emptyList()) != null)
    preveriEnako("izDodatka: sledilnik z presledkom ali brez oznake preskocen", 1,
        MagnetPravila.izDodatka(hex, "", listOf("tracker:udp://a.example:1/x y", "dht:abc", "tracker:udp://b.example:1/x"))!!.split("&tr=").size - 1)
    // N3: en razclenjevalnik hasha - TorBox in MagnetPravila se ujemata tudi pri kodiranem xt in predolgem magnetu
    val kodiran = "magnet:?xt=urn%3Abtih%3A$hex&dn=x"
    preveriEnako("TorBox hash = MagnetPravila.hash (kodiran xt)", MagnetPravila.hash(kodiran), TorBoxPravila.hashIzMagneta(kodiran))
    preveriEnako("kodiran xt velja v obeh", hex, TorBoxPravila.hashIzMagneta(kodiran))
    val predolg = "magnet:?xt=urn:btih:$hex&dn=" + "a".repeat(9000)
    preveriEnako("predolg magnet: oba zavrneta", null to null, MagnetPravila.hash(predolg) to TorBoxPravila.hashIzMagneta(predolg))
    val glasba = java.io.File(args.firstOrNull() ?: ".", "si/safeer/tv/os/GlasbaActivity.kt").readText()
    preveriEnako("GlasbaActivity.btih gre skozi MagnetPravila.hash", true, "private fun btih(magnet: String): String? = MagnetPravila.hash(magnet)" in glasba)

    if (napak == 0) println("MagnetPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
