package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Pregled Grok 4: kljuc »Nadaljuj gledanje« - vsebina + sezona + epizoda, zdruzljivost s starimi zapisi ([NapredekKljuc]). */
fun main() {
    println("Preizkus kljuca napredka (Nadaljuj)")
    val K = NapredekKljuc
    val dodatek = "https://dodatek.test/manifest.json"
    fun film(tt: String, naslov: String, tok: String = "#123") = NapredekKljuc.Opis("stremio|movie|$tt|$dodatek$tok", naslov)
    fun ep(s: Int, e: Int, naslov: String = "Prijatelji", tok: String = "#-77") = NapredekKljuc.Opis("stremio|series|tt0108778|$dodatek$tok", naslov, s, e)

    // Vsebina: brez oznak toka in dodatka
    preveriEnako("id brez toka", "stremio|movie|tt1160419", K.vsebine("stremio|movie|tt1160419|$dodatek#123"))
    preveriEnako("id brez toka (negativen hash)", "stremio|movie|tt1160419", K.vsebine("stremio|movie|tt1160419|$dodatek#-42"))
    preveriEnako("id brez preizkusa in toka", "stremio|series|tt1", K.vsebine("stremio|series|tt1|$dodatek#preizkus#55"))
    preveriEnako("id pomocnika #t", "stremio|movie|tt1", K.vsebine("stremio|movie|tt1|$dodatek#t998"))
    preveriEnako("id pomocnika #tn", "stremio|movie|tt1", K.vsebine("stremio|movie|tt1|$dodatek#tn-5"))
    preveriEnako("krajevna pot z # ostane", "krajevno:/Movies/a#b.mkv", K.vsebine("krajevno:/Movies/a#b.mkv"))
    preveriEnako("prazen id", "", K.vsebine(""))

    // Dune (2021) in Dune (2024): razlicna vsebina, enak naslov
    val dune21 = film("tt1160419", "Dune")
    val dune24 = film("tt15239678", "Dune")
    preveriEnako("Dune 2021 in 2024: razlicna kljuca", false, K.kljuc(dune21) == K.kljuc(dune24))
    preveriEnako("Dune 2021 in 2024: se ne ujemata", false, K.ujema(dune21, dune24))
    preveriEnako("isti film, drug tok in dodatek", true, K.ujema(dune21, NapredekKljuc.Opis("stremio|movie|tt1160419|https://drug.test/manifest.json#999", "Dune")))

    // Epizode: sezona in epizoda iz polj, ne iz naslova
    preveriEnako("S1E1 in S1E2 z enakim naslovom: razlicni", false, K.ujema(ep(1, 1), ep(1, 2)))
    preveriEnako("ista epizoda, drug naslov", true, K.ujema(ep(1, 2, "Prijatelji · S1E2 · Pilot"), ep(1, 2, "Prijatelji")))
    preveriEnako("epizoda iz naslova = epizoda iz polj", K.kljuc(ep(2, 3)), K.kljuc(NapredekKljuc.Opis(ep(2, 3).id, "Prijatelji · S2E3", 0, 0)))

    // Zdruzljivost: star zapis (do 2.1.231) brez sezone in epizode
    val star = NapredekKljuc.Opis("stremio|series|tt0108778|$dodatek#11", "Prijatelji")
    preveriEnako("star zapis serije brez epizode velja za isto serijo", true, K.ujema(star, ep(1, 4)))
    preveriEnako("star zapis z SxEy v naslovu velja samo za to epizodo", true, K.ujema(NapredekKljuc.Opis(star.id, "Prijatelji · S1E4"), ep(1, 4)))
    preveriEnako("star zapis z SxEy v naslovu ne velja za drugo", false, K.ujema(NapredekKljuc.Opis(star.id, "Prijatelji · S1E4"), ep(1, 5)))
    preveriEnako("star zapis filma brez id-ja velja po naslovu", true, K.ujema(NapredekKljuc.Opis("", "Dune (2021)"), dune21))
    preveriEnako("star zapis druge vsebine z enakim naslovom ne velja", false, K.ujema(NapredekKljuc.Opis("stremio|movie|tt15239678|$dodatek#1", "Dune"), dune21))
    preveriEnako("krajevna datoteka", true, K.ujema(NapredekKljuc.Opis("krajevno:/Movies/a.mkv", "a"), NapredekKljuc.Opis("krajevno:/Movies/a.mkv", "a")))
    preveriEnako("krajevni datoteki z razlicno potjo", false, K.ujema(NapredekKljuc.Opis("krajevno:/Movies/a.mkv", "film"), NapredekKljuc.Opis("krajevno:/Movies/b.mkv", "film")))

    // Kratek naslov ne ujame drugih (odstrani prej po podnizu)
    preveriEnako("»It« ni »Little Women«", false, K.ujema(NapredekKljuc.Opis("", "Little Women"), NapredekKljuc.Opis("", "It")))
    preveriEnako("»It« ni »It Follows«", false, K.ujema(NapredekKljuc.Opis("stremio|movie|tt3235888|$dodatek", "It Follows"), NapredekKljuc.Opis("stremio|movie|tt1396484|$dodatek", "It")))
    preveriEnako("prazen naslov in prazen id se ne ujemata z vsem", false, K.ujema(NapredekKljuc.Opis("", "Film"), NapredekKljuc.Opis("", "")))

    // Odgledano: nov kljuc, stare oznake naslova se veljajo, »ne« pobrise obe
    val o1 = K.oznaci(emptyList(), dune21, true, 300)
    preveriEnako("odgledano: Dune 2021", true, K.odgledano(o1, dune21))
    preveriEnako("odgledano: Dune 2024 ne", false, K.odgledano(o1, dune24))
    preveriEnako("odgledano: stara oznaka naslova velja", true, K.odgledano(listOf("prijateljis1e2"), NapredekKljuc.Opis("", "Prijatelji · S1E2")))
    val brez = K.oznaci(listOf("dune", K.kljuc(dune21), "drugo"), dune21, false, 300)
    preveriEnako("ogled znova: oznaki pobrisani, druge ostanejo", listOf("drugo"), brez)
    preveriEnako("odgledano: najnovejsi prvi, brez podvojenih", listOf(K.kljuc(dune24), K.kljuc(dune21)), K.oznaci(K.oznaci(o1, dune24, true, 300), dune24, true, 300))
    preveriEnako("odgledano: najvec", 2, K.oznaci(listOf("a", "b", "c"), dune21, true, 2).size)

    if (napak == 0) println("NapredekKljucTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
