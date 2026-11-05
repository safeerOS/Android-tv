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
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
