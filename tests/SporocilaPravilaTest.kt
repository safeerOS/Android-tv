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

    // Pregled 25. 10. 2026 D3: cas izbrisa pogovora ni po krajevni uri - ura posiljatelja naprej ne skrije novih sporocil.
    val zdaj = "2026-10-25T10:00:00+00:00"
    preveriEnako("izbrisan do: ura posiljatelja naprej - omejeno na krajevni cas izbrisa", zdaj,
        P.izbrisanDo(listOf("2026-11-30T10:00:00+00:00"), "2026-10-24T10:00:00+00:00", zdaj))
    preveriEnako("izbrisan do: pravilna ura ostane najnovejse sporocilo", "2026-10-25T09:00:00+00:00",
        P.izbrisanDo(listOf("2026-10-25T09:00:00+00:00"), "2026-10-24T10:00:00+00:00", zdaj))
    preveriEnako("novo sporocilo po izbrisu z uro posiljatelja naprej vrne pogovor", true,
        P.pogovorSeVrne("2026-10-25T10:05:00+00:00", P.izbrisanDo(listOf("2026-11-30T10:00:00+00:00"), "", zdaj)))

    // D4: izbris, ki pride pred izvirnikom, pusti zapis za 7 dni (krajevna ura).
    preveriEnako("vnaprej: znana naprava in skupni id", true, P.smeZapisatiVnaprej("n-tel", uuid))
    preveriEnako("vnaprej: brez naprave ne", false, P.smeZapisatiVnaprej("", uuid))
    preveriEnako("vnaprej: id, ki ni UUID, ne", false, P.smeZapisatiVnaprej("n-tel", "123456789"))
    val t1 = 5_000_000_000L
    val poteka = P.potekVnaprej(t1)
    preveriEnako("vnaprej: velja 7 dni", 7L * 24 * 3600 * 1000, poteka - t1)
    preveriEnako("vnaprej: takoj velja", true, P.vnaprejVelja(poteka, t1 + 1))
    preveriEnako("vnaprej: tik pred potekom velja", true, P.vnaprejVelja(poteka, poteka - 1))
    preveriEnako("vnaprej: po poteku ne velja", false, P.vnaprejVelja(poteka, poteka))
    preveriEnako("vnaprej: ura prestavljena nazaj - zapis ne velja predolgo", false, P.vnaprejVelja(poteka, t1 - 3600_000))

    // D5: »Izbrisi na vseh« samo napravi z zmoznostjo chat.delete, drugim opomba.
    preveriEnako("na drugi: zna izbris", P.NA_DRUGI_DA, P.izbrisNaDrugi(listOf(listOf("url"), listOf("chat", P.ZMOZNOST_IZBRIS))))
    preveriEnako("na drugi: samo chat (namizje) ne zna", P.NA_DRUGI_NE, P.izbrisNaDrugi(listOf(listOf("chat", "url"))))
    preveriEnako("na drugi: ni v seznamu - neznano", P.NA_DRUGI_NEZNANO, P.izbrisNaDrugi(emptyList()))
    preveriEnako("meni: izbris na drugi ni podprt - opomba", listOf(P.KOPIRAJ, P.IZBRISI, P.IZBRISI_VSE_NI),
        P.dejanjaSporocila("zivjo", jePovezava = false, naVseh = true, naVsehPodprto = false))
    preveriEnako("meni: tuje sporocilo brez opombe", listOf(P.KOPIRAJ, P.IZBRISI),
        P.dejanjaSporocila("zivjo", jePovezava = false, naVseh = false, naVsehPodprto = false))

    // Pregled 8: e-posta (IMAP UID) - izbrisan pogovor obudi samo sporocilo, ki ga je streznik prejel po izbrisu.
    preveriEnako("8: uid je e-posta", true, P.jeEposta("uid:42"))
    preveriEnako("8: Link ni e-posta", false, P.jeEposta("a1b2"))
    preveriEnako("8: e-posta - izbris velja do zdaj", "2026-10-25T10:00:00",
        P.izbrisanDoZaKanal(listOf("2026-10-20T10:00:00"), "2026-10-20T10:00:00", "2026-10-25T10:00:00", eposta = true))
    preveriEnako("8: drug kanal - kot doslej", "2026-10-20T10:00:00",
        P.izbrisanDoZaKanal(listOf("2026-10-20T10:00:00"), "2026-10-20T10:00:00", "2026-10-25T10:00:00", eposta = false))
    preveriEnako("8: pogovor ni izbrisan - shrani", true, P.novoVIzbrisanem("2026-10-01T00:00:00", null))
    preveriEnako("8: Link - novo sporocilo vrne pogovor ne glede na uro", true, P.novoVIzbrisanem(null, "2026-10-25T10:00:00"))
    preveriEnako("8: e-posta prejeta pred izbrisom (nov UID) - ne obudi", false, P.novoVIzbrisanem("2026-10-20T10:05:00", "2026-10-25T10:00:00"))
    preveriEnako("8: e-posta prejeta po izbrisu - vrne", true, P.novoVIzbrisanem("2026-10-25T10:00:01", "2026-10-25T10:00:00"))
    // Pregled 4: naprava, ki izbrisa ne javi - izbris pocaka (ne zavrzemo ga takoj), meni ga ponudi.
    preveriEnako("4: brez zmoznosti - pocakaj", P.BRISANJE_PONOVI, P.brisanjeBrezZmoznosti(0L, 1_000L))
    preveriEnako("4: brez zmoznosti, prestar - opusti", P.BRISANJE_OPUSTI, P.brisanjeBrezZmoznosti(0L, P.BRISANJE_NAJDLJE_MS + 1))
    preveriEnako("4: ne javi - meni ponudi", true, P.izbrisNaVsehPonudi(P.NA_DRUGI_NE))
    preveriEnako("4: neznano - meni ponudi", true, P.izbrisNaVsehPonudi(P.NA_DRUGI_NEZNANO))

    if (napak == 0) println("SporocilaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
