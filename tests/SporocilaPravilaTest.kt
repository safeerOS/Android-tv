package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 106: krajevno brisanje sporocil in pogovorov ([SporocilaPravila]). */
fun main() {
    println("Preizkus pravil brisanja sporocil")
    val P = SporocilaPravila
    preveriEnako("meni navadnega sporocila", listOf(P.KOPIRAJ, P.IZBRISI), P.dejanjaSporocila("zivjo", jePovezava = false))
    preveriEnako("meni povezave", listOf(P.ODPRI, P.KOPIRAJ, P.IZBRISI), P.dejanjaSporocila("https://safeer.si", jePovezava = true))
    preveriEnako("prazno sporocilo: samo brisanje", listOf(P.IZBRISI), P.dejanjaSporocila("  ", jePovezava = false))

    val ostala = listOf("prvo" to "2026-10-23T10:00:00", "drugo\nvrstica" to "2026-10-23T11:00:00")
    preveriEnako("zadnje po izbrisu je najnovejse preostalo", "drugo vrstica" to "2026-10-23T11:00:00", P.zadnjePoIzbrisu(ostala, "2026-10-23T12:00:00"))
    preveriEnako("brez preostalih: prazno, cas ostane", "" to "2026-10-23T12:00:00", P.zadnjePoIzbrisu(emptyList(), "2026-10-23T12:00:00"))
    preveriEnako("zadnje skrajsano na 240 znakov", 240, P.zadnjePoIzbrisu(listOf("x".repeat(500) to "c"), "c").first.length)

    preveriEnako("izbris pogovora: brez praznih in dvojnikov", listOf("a", "b"), P.zaIzbrisPogovora(listOf("a", "", "b", "a")))
    preveriEnako("izbrisan do: najnovejsi cas", "2026-10-23T12:00:00",
        P.izbrisanDo(listOf("2026-10-23T10:00:00", "2026-10-23T12:00:00"), "2026-10-23T11:00:00"))
    preveriEnako("izbrisan do: brez sporocil cas pogovora", "2026-10-23T11:00:00", P.izbrisanDo(emptyList(), "2026-10-23T11:00:00"))

    val doCas = "2026-10-23T12:00:00"
    preveriEnako("staro sporocilo (ponovna uskladitev) ne vrne pogovora", false, P.pogovorSeVrne("2026-10-23T11:59:59", doCas))
    preveriEnako("isto sporocilo znova ne vrne pogovora", false, P.pogovorSeVrne(doCas, doCas))
    preveriEnako("novo sporocilo vrne pogovor", true, P.pogovorSeVrne("2026-10-23T12:00:01", doCas))
    preveriEnako("pogovor ni bil izbrisan", true, P.pogovorSeVrne("2026-10-23T09:00:00", ""))

    // Naloga 116: izbris na vseh napravah
    val uuid = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
    preveriEnako("meni lastnega sporocila Linka: se izbris na vseh", listOf(P.KOPIRAJ, P.IZBRISI, P.IZBRISI_VSE),
        P.dejanjaSporocila("zivjo", jePovezava = false, naVseh = true))
    preveriEnako("skupni id poslanega sporocila", uuid, P.skupniId("chat:ven:$uuid", "ven"))
    preveriEnako("staro poslano sporocilo (id iz casa) nima skupnega id", null, P.skupniId("chat:ven:123456789012", "ven"))
    preveriEnako("prejeto sporocilo nima skupnega id (ni nase)", null, P.skupniId("chat:$uuid", "noter"))
    preveriEnako("e-posta nima skupnega id", null, P.skupniId(uuid, "ven"))
    preveriEnako("prejemnik hrani pod chat:<id>", "chat:$uuid", P.prejetiId(uuid))
    preveriEnako("zahteva posiljatelja izbrise njegovo sporocilo", true, P.zahtevaZaIzbrisVelja("noter", "n-tel", "n-tel"))
    preveriEnako("tuja naprava ne izbrise sporocila druge naprave", false, P.zahtevaZaIzbrisVelja("noter", "n-tel", "n-tablica"))
    preveriEnako("nihce ne izbrise nasega poslanega sporocila", false, P.zahtevaZaIzbrisVelja("ven", "n-tel", "n-tel"))
    preveriEnako("brez posiljatelja ni izbrisa", false, P.zahtevaZaIzbrisVelja("noter", "", ""))
    val t0 = 1_000_000L
    preveriEnako("dostavljeno: koncano", P.BRISANJE_KONCANO, P.izidBrisanja("accepted", t0, t0 + 1))
    preveriEnako("v vrsti sredisca: koncano", P.BRISANJE_KONCANO, P.izidBrisanja("queued", t0, t0 + 1))
    preveriEnako("brez povezave: ponovi", P.BRISANJE_PONOVI, P.izidBrisanja("ni_povezave", t0, t0 + 1))
    preveriEnako("potek: ponovi", P.BRISANJE_PONOVI, P.izidBrisanja("potek", t0, t0 + 1))
    preveriEnako("naprave ni vec: opusti", P.BRISANJE_OPUSTI, P.izidBrisanja("ni_naprave", t0, t0 + 1))
    preveriEnako("starejse od meseca: opusti", P.BRISANJE_OPUSTI, P.izidBrisanja("ni_povezave", t0, t0 + P.BRISANJE_NAJDLJE_MS + 1))

    if (napak == 0) println("SporocilaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
