package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Vodja 5. 11. 2026: pomocnik je dobil nadomestek SkrivniDodatki namesto pravega naslova (in to vsaka naprava). */
fun main() {
    println("Preizkus vira za pomocnika sprotne pretvorbe")
    val P = SprotnaPomocPravila
    val nadomestek = "https://webtor.io/safeer-skrito-" + SkrivnostiPravila.odtis("https://webtor.io/x?token=t")
    val pravi = "https://webtor.io/x?token=t"
    val dejanski = { n: String -> if (n == nadomestek) pravi else n }
    preveriEnako("nadomestek: ozji krog dobi PRAVI naslov", pravi, P.naslov(nadomestek, true, dejanski))
    preveriEnako("nadomestek: naprava zunaj ozjega kroga nic", null, P.naslov(nadomestek, false, dejanski))
    preveriEnako("nadomestek, ki ga ni mogoce razresiti: nic (ne lazen naslov)", null, P.naslov(nadomestek, true) { it })
    preveriEnako("navaden vir: nespremenjen vsem", "https://cdn.example/f.mkv", P.naslov("https://cdn.example/f.mkv", false, dejanski))
    preveriEnako("skrivnost: nadomestek", true, P.nosiSkrivnost(nadomestek, false))
    preveriEnako("skrivnost: glave toka", true, P.nosiSkrivnost("https://cdn.example/f.mkv", true))
    preveriEnako("skrivnost: navaden vir brez glav", false, P.nosiSkrivnost("https://cdn.example/f.mkv", false))
    preveriEnako("sme: skrivnost samo ozji krog", listOf(true, false, true, true),
        listOf(P.sme(true, true), P.sme(true, false), P.sme(false, false), P.sme(false, true)))
    preveriEnako("glave: ozji krog", mapOf("X-Api-Key" to "k"), P.glave(mapOf("X-Api-Key" to "k"), true))
    preveriEnako("glave: zunaj ozjega kroga nic", emptyMap<String, String>(), P.glave(mapOf("X-Api-Key" to "k"), false))

    // Pregled 6. 11. 2026 (H1): vir na napravi v Linku - fp+token streznika datotek je nosilni zeton te naprave.
    val naNapravi = "https://192.168.1.20:8443/d/abc"
    preveriEnako("H1: streznik datotek je skrivnost", true, P.nosiSkrivnost(naNapravi, false, imaStreznik = true))
    preveriEnako("H1: streznik -> pomocnik zunaj ozjega kroga ne", false, P.sme(P.nosiSkrivnost(naNapravi, false, imaStreznik = true), false))
    preveriEnako("H1: streznik -> ozji krog da", true, P.sme(P.nosiSkrivnost(naNapravi, false, imaStreznik = true), true))
    // H2: surov naslov z zetonom (brez nadomestka) - samo ozji krog, tudi ce ga kdo poda mimo izbire kandidatov.
    for (surov in listOf("https://webtor.io/token/eyJhbGciOiJIUzI1NiJ9abcdefghij1234567890/stremio/manifest.json",
            "https://torrentio.strem.fun/realdebrid=Q2xE9f8kLmN0pR5sT7uVwXyZ1234/stream/movie/tt1.json",
            "https://cdn.example/f.mkv?token=Q2xE9f8kLmN0pR5sT7uVwXyZ1234")) {
        preveriEnako("H2: surov skrivni naslov je skrivnost: $surov", true, P.nosiSkrivnost(surov, false))
        preveriEnako("H2: surov skrivni naslov zunaj ozjega kroga nic", null, P.naslov(surov, false, dejanski))
        preveriEnako("H2: surov skrivni naslov ozji krog dobi", surov, P.naslov(surov, true, dejanski))
    }
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
