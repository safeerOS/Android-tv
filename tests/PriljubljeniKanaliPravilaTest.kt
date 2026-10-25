package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Priljubljeni kanali v TV v zivo ([PriljubljeniKanaliPravila]) - lastnik 25. 10. 2026. */
fun main() {
    println("Preizkus priljubljenih kanalov")
    val P = PriljubljeniKanaliPravila
    val jeK: (String) -> Boolean = { P.jeKanal(it) }
    preveriEnako("vgrajeni je kanal", true, P.jeKanal("tv:rtv1"))
    preveriEnako("IPTV je kanal", true, P.jeKanal("iptv:https://a.b/c.m3u8"))
    preveriEnako("skladba ni kanal", false, P.jeKanal("jamendo:123"))
    preveriEnako("kanal dodatka po klicatelju", true, P.jeKanal("tt123", dodatekVZivo = true))

    // Mreza: priljubljeni spredaj po uporabnikovem redu, ostali za njimi; priljubljen, ki ga v mrezi ni, ne.
    val vsi = listOf("tv:a", "iptv:b", "tv:c", "iptv:d", "tv:e")
    preveriEnako("priljubljeni spredaj", listOf("iptv:d", "tv:c", "tv:a", "iptv:b", "tv:e"), P.prikaz(listOf("iptv:d", "tv:x", "tv:c"), vsi, false))
    preveriEnako("samo priljubljeni", listOf("iptv:d", "tv:c"), P.prikaz(listOf("iptv:d", "tv:x", "tv:c"), vsi, true))
    preveriEnako("brez priljubljenih: isti red", vsi, P.prikaz(emptyList(), vsi, false))
    preveriEnako("samo priljubljeni, ni jih", emptyList<String>(), P.prikaz(emptyList(), vsi, true))
    preveriEnako("podvojen priljubljen enkrat", listOf("tv:c", "tv:a", "iptv:b", "iptv:d", "tv:e"), P.prikaz(listOf("tv:c", "tv:c"), vsi, false))

    // Premik med kanali; skladbe ostanejo na svojih mestih.
    val prilj = listOf("tv:a", "jamendo:1", "iptv:b", "tv:c", "jamendo:2")
    preveriEnako("mesto", 1 to 3, P.mesto(prilj, jeK, "iptv:b"))
    preveriEnako("mesto ne-kanala", -1 to 3, P.mesto(prilj, jeK, "jamendo:1"))
    preveriEnako("levo", listOf("iptv:b", "jamendo:1", "tv:a", "tv:c", "jamendo:2"), P.premakni(prilj, jeK, "iptv:b", -1))
    preveriEnako("desno", listOf("tv:a", "jamendo:1", "tv:c", "iptv:b", "jamendo:2"), P.premakni(prilj, jeK, "iptv:b", 1))
    preveriEnako("na zacetek", listOf("tv:c", "jamendo:1", "tv:a", "iptv:b", "jamendo:2"), P.premakni(prilj, jeK, "tv:c", 0, naZacetek = true))
    preveriEnako("prvi levo ne gre", null, P.premakni(prilj, jeK, "tv:a", -1))
    preveriEnako("zadnji desno ne gre", null, P.premakni(prilj, jeK, "tv:c", 1))
    preveriEnako("prvi na zacetek ne gre", null, P.premakni(prilj, jeK, "tv:a", 0, naZacetek = true))
    preveriEnako("skladbe ne premikamo", null, P.premakni(prilj, jeK, "jamendo:1", 1))
    preveriEnako("neznan id", null, P.premakni(prilj, jeK, "tv:z", 1))
    // Premik in mreza skupaj: premaknjeni kanal je v mrezi na novem mestu.
    val novi = P.premakni(prilj, jeK, "tv:c", 0, naZacetek = true)!!
    preveriEnako("mreza po premiku", listOf("tv:c", "tv:a", "iptv:b", "iptv:d", "tv:e"), P.prikaz(novi.filter(jeK), vsi, false))

    // Nov priljubljeni kanal za zadnjim kanalom (red ostane); brez kanalov na zacetek; ze priljubljen ostane.
    preveriEnako("nov kanal za zadnjim kanalom", listOf("tv:a", "jamendo:1", "iptv:b", "tv:c", "iptv:n", "jamendo:2"), P.dodaj(prilj, jeK, "iptv:n"))
    preveriEnako("prvi kanal na zacetek", listOf("iptv:n", "jamendo:1"), P.dodaj(listOf("jamendo:1"), jeK, "iptv:n"))
    preveriEnako("prazni priljubljeni", listOf("iptv:n"), P.dodaj(emptyList(), jeK, "iptv:n"))
    preveriEnako("ze priljubljen", prilj, P.dodaj(prilj, jeK, "tv:c"))

    // Meni ponudi samo mogoce premike.
    preveriEnako("en kanal: nic", Triple(false, false, false), P.moznostiPremika(0, 1))
    preveriEnako("prvi: desno", Triple(false, true, false), P.moznostiPremika(0, 3))
    preveriEnako("drugi: levo, desno (na zacetek = levo)", Triple(true, true, false), P.moznostiPremika(1, 3))
    preveriEnako("zadnji: levo in na zacetek", Triple(true, false, true), P.moznostiPremika(2, 3))
    preveriEnako("ni priljubljen", Triple(false, false, false), P.moznostiPremika(-1, 3))

    println(if (napak == 0) "Priljubljeni kanali: vse v redu" else "Priljubljeni kanali: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
