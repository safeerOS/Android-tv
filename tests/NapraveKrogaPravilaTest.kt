package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 42 D: Safeer Link - naprave ozjega kroga in sinhronizacija zaupanja ([NapraveKrogaPravila]). Lazne naprave. */
fun main() {
    println("Preizkus naprav ozjega kroga")
    val deli = "zaslon.deli"
    val n = { id: String, ime: String, z: Set<String>, ta: Boolean, krog: Boolean, pov: Boolean ->
        NapraveKrogaPravila.Naprava(id, ime, z, ta, krog, pov) }
    val vse = listOf(
        n("tv", "Ta TV", setOf(deli), true, true, true),
        n("pc", "Racunalnik", setOf("desktop"), false, true, true),
        n("tuj", "Tuj telefon", setOf(deli), false, false, true),
        n("tab", "Tablica", setOf(deli), false, true, false),
        n("ura", "Ura", emptySet(), false, true, true),
        n("pc", "Racunalnik", setOf("desktop"), false, true, true),
    )
    preveriEnako("samo druge naprave v ozjem krogu, povezane prve, brez podvojenih",
        listOf("pc", "ura", "tab"), NapraveKrogaPravila.zaPrikaz(vse).map { it.id })
    preveriEnako("naprava zunaj kroga se ne pokaze", false, NapraveKrogaPravila.zaPrikaz(vse).any { it.id == "tuj" })
    preveriEnako("ta naprava se ne pokaze", false, NapraveKrogaPravila.zaPrikaz(vse).any { it.id == "tv" })

    println("\n== pot ob kliku (ista kot kartica Zaslon) ==")
    preveriEnako("racunalnik: ZaslonActivity", "racunalnik", NapraveKrogaPravila.pot(setOf("desktop", deli), deli))
    preveriEnako("Android, ki deli zaslon: deljenje + vnos", "deli_zaslon", NapraveKrogaPravila.pot(setOf(deli), deli))
    preveriEnako("brez zaslona: Safeer Link", "naprave", NapraveKrogaPravila.pot(setOf("chat"), deli))

    println("\n== sinhronizacija zaupanja v ozjem krogu ==")
    val j = "n-0123456789abcdef"
    preveriEnako("jedro iz kljuca velja", true, NapraveKrogaPravila.veljavnoJedro(j))
    preveriEnako("stari id brez kljuca ne", false, NapraveKrogaPravila.veljavnoJedro("telefon-1"))
    preveriEnako("velike crke ne", false, NapraveKrogaPravila.veljavnoJedro("n-0123456789ABCDEF"))
    preveriEnako("predolgo ne", false, NapraveKrogaPravila.veljavnoJedro(j + "0"))
    preveriEnako("usklajevanje, posiljatelj in cilj v krogu: velja brez ponovne potrditve", true,
        NapraveKrogaPravila.sprejmiZaupanje("secrets.sync", true, j, jedroVKrogu = true, jedroJeTaNaprava = false))
    preveriEnako("posiljatelj zunaj kroga: nic", false, NapraveKrogaPravila.sprejmiZaupanje("secrets.sync", false, j, true, false))
    preveriEnako("cilj zunaj kroga te naprave: nic", false, NapraveKrogaPravila.sprejmiZaupanje("secrets.sync", true, j, false, false))
    preveriEnako("ta naprava sama sebi ne", false, NapraveKrogaPravila.sprejmiZaupanje("secrets.sync", true, j, true, true))
    preveriEnako("besedilo dohodnega sporocila NIKOLI ne podeli dostopa", false,
        NapraveKrogaPravila.sprejmiZaupanje(NapraveKrogaPravila.VIR_SPOROCILO, true, j, true, false))
    preveriEnako("neznan vir: nic", false, NapraveKrogaPravila.sprejmiZaupanje("chat.send", true, j, true, false))
    preveriEnako("neveljavno jedro: nic", false, NapraveKrogaPravila.sprejmiZaupanje("secrets.sync", true, "x", true, false))
    val j2 = "n-fedcba9876543210"
    preveriEnako("poslje se samo zaupanje napravam v krogu (veljavna jedra, urejeno)", sortedSetOf(j),
        NapraveKrogaPravila.zaPoslati(setOf(j2, j, "telefon-1")) { it == j || it == "telefon-1" })
    preveriEnako("napaka pri preverbi kroga = ne poslje", emptySet<String>(),
        NapraveKrogaPravila.zaPoslati(setOf(j)) { throw IllegalStateException("x") })

    println("\n== kljuc v usklajevanju ==")
    preveriEnako("kljuc zaupanja", "zaupanje:$j", UskladitevKljucevPravila.kljucZaupanja(j))
    preveriEnako("veljaven kljuc zaupanja", true, UskladitevKljucevPravila.veljavenKljuc("zaupanje:$j", emptySet()))
    preveriEnako("kljuc zaupanja s starim id ne", false, UskladitevKljucevPravila.veljavenKljuc("zaupanje:telefon-1", emptySet()))

    if (napak == 0) println("NapraveKrogaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
