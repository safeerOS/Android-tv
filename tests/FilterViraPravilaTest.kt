package si.safeer.tv.os

import si.safeer.tv.os.FilterViraPravila.Izdaja
import si.safeer.tv.os.FilterViraPravila.Izid
import si.safeer.tv.os.FilterViraPravila.Izidi
import si.safeer.tv.os.FilterViraPravila.Odgovor
import si.safeer.tv.os.FilterViraPravila.Pogoj
import si.safeer.tv.os.FilterViraPravila.Pogoji

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Filter vira (lastnik 5. 11. 2026 19:34: Abbott Elementary pod filtrom Usenet brez izdaje na Usenetu). */
fun main() {
    println("Preizkus filtra vira (Usenet)")
    val p = Pogoji(FilterViraPravila.VIR_USENET)
    val z = TokIzbira.Zmoznosti()
    val dobra = Izdaja("Abbott.Elementary.S01E01.1080p.WEB.h264-GRP", 1.2)
    val racun = Izid.NA_VOLJO

    // Primer iz prijave: dodatki (TorBox, torrenti) imajo tokove, indeksator Usenet je odgovoril brez zadetkov.
    preveriEnako("debrid/torrent da, Usenet 0 zadetkov -> ni v filtru Usenet", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(emptyList())), z))
    preveriEnako("Usenet 0 zadetkov je izid NI (negativno v predpomnilnik)", Izid.NI,
        FilterViraPravila.izid(Izidi(racun, Odgovor(emptyList())), p, z))
    preveriEnako("Usenet z ustrezno izdajo -> v filtru", true, FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(dobra))), z))

    // Vsak pogoj posebej.
    preveriEnako("RACUN: ponudnik ni nastavljen -> ni v filtru", false, FilterViraPravila.lahkoGleda(p, Izidi(Izid.NI, Odgovor(listOf(dobra))), z))
    preveriEnako("RACUN: neznano -> ne vemo", Izid.NE_VEMO, FilterViraPravila.izid(Izidi(Izid.NE_VEMO, Odgovor(listOf(dobra))), p, z))
    preveriEnako("ODGOVOR: napaka/rok -> ne vemo (ne NI)", Izid.NE_VEMO, FilterViraPravila.izid(Izidi(racun, Odgovor(null)), p, z))
    preveriEnako("ODGOVOR: se ni preverjeno -> ni v filtru", false, FilterViraPravila.lahkoGleda(p, Izidi(racun, null), z))
    preveriEnako("GESLO: indeksator pove geslo -> ni", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(dobra.copy(geslo = true)))), z))
    preveriEnako("GESLO: indeksator ne pove -> pogoj izpolnjen", Izid.NA_VOLJO, FilterViraPravila.pogojiIzdaje(dobra, p, z)[Pogoj.GESLO])
    preveriEnako("MANJKA: oznacen manjkajoc -> ni", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(dobra.copy(manjka = true)))), z))
    val hevc10 = Izdaja("Film.2023.2160p.UHD.HDR.10bit.x265-GRP", 12.0)
    preveriEnako("PREDVAJALNIK: 10-bitni HEVC brez Main10 -> ni", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(hevc10))), z.copy(hevc10 = false)))
    preveriEnako("PREDVAJALNIK: 10-bitni HEVC z Main10 -> da", true,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(hevc10))), z))
    preveriEnako("PREDVAJALNIK: TrueHD brez dekodirnika -> ni", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.1080p.BluRay.TrueHD.7.1.x264", 9.0)))), z))
    preveriEnako("PREDVAJALNIK: slika diska (ISO) -> ni", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.COMPLETE.BLURAY-GRP", 40.0)))), z))
    preveriEnako("PREDVAJALNIK: AV1 brez dekoderja -> ni", false,
        FilterViraPravila.lahkoGleda(p, Izidi(racun, Odgovor(listOf(Izdaja("Film.2024.1080p.WEB.AV1", 2.0)))), z))
    val pJezik = p.copy(jezikZvoka = "de")
    preveriEnako("JEZIK_ZVOKA: zahtevan, ime ga ne pove -> ne vemo", Izid.NE_VEMO, FilterViraPravila.pogojiIzdaje(dobra, pJezik, z)[Pogoj.JEZIK_ZVOKA])
    preveriEnako("JEZIK_ZVOKA: zahtevan nemski, izdaja GERMAN -> da", true,
        FilterViraPravila.lahkoGleda(pJezik, Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.German.1080p.WEB.h264", 2.0)))), z))
    preveriEnako("JEZIK_ZVOKA: ni zahtevan -> pogoja ni", null, FilterViraPravila.pogojiIzdaje(dobra, p, z)[Pogoj.JEZIK_ZVOKA])
    val pPodnapisi = p.copy(jezikPodnapisov = "sl")
    preveriEnako("PODNAPISI: zahtevani, ime jih ne navede -> ni v filtru", false,
        FilterViraPravila.lahkoGleda(pPodnapisi, Izidi(racun, Odgovor(listOf(dobra))), z))
    preveriEnako("PODNAPISI: SLSUBS -> da", true,
        FilterViraPravila.lahkoGleda(pPodnapisi, Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.1080p.WEB.SLSUBS.h264", 2.0)))), z))
    val pLoc = p.copy(najmanjVisina = 2160)
    preveriEnako("LOCLJIVOST: zahtevan 4K, izdaja 1080p -> ni", false, FilterViraPravila.lahkoGleda(pLoc, Izidi(racun, Odgovor(listOf(dobra))), z))
    preveriEnako("LOCLJIVOST: neznana -> ne vemo", Izid.NE_VEMO,
        FilterViraPravila.pogojiIzdaje(Izdaja("Film.2020.WEB.h264", 2.0), pLoc, z)[Pogoj.LOCLJIVOST])
    val pGb = p.copy(najvecGb = 15.0)   // UsenetPravila.NAJVEC_GB_SAMODEJNO
    preveriEnako("VELIKOST: samo REMUX 60 GB (samodejna izbira ga ne vzame) -> ni", false,
        FilterViraPravila.lahkoGleda(pGb, Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.2160p.REMUX.x265", 60.0)))), z))
    preveriEnako("VELIKOST: neznana -> ne vemo", Izid.NE_VEMO, FilterViraPravila.pogojiIzdaje(Izdaja("Film.1080p", 0.0), pGb, z)[Pogoj.VELIKOST])
    preveriEnako("AND: ena izdaja zadosca, ce izpolni vse (druga ne)", true,
        FilterViraPravila.lahkoGleda(pGb, Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.2160p.REMUX", 60.0), dobra))), z))
    preveriEnako("AND: pogoja izpolnita RAZLICNI izdaji -> ni", false,
        FilterViraPravila.lahkoGleda(pGb.copy(jezikZvoka = "de"), Izidi(racun, Odgovor(listOf(
            Izdaja("Film.2020.German.2160p.REMUX", 60.0), dobra))), z))

    // Znacka: samo iz ustreznih izdaj vira filtra.
    preveriEnako("znacka iz Usenet izdaj (720p), ne iz torrentov", KakovostPravila.Oznaka.HD,
        FilterViraPravila.oznaka(listOf(Izdaja("Film.2020.720p.WEB.h264", 1.0)), p, z))
    preveriEnako("znacka: neustrezna 4K izdaja ne steje", KakovostPravila.Oznaka.FULL_HD,
        FilterViraPravila.oznaka(listOf(Izdaja("Film.2020.2160p.10bit.x265", 10.0), dobra), p, z.copy(hevc10 = false)))
    preveriEnako("znacka: brez izdaj nic", null, FilterViraPravila.oznaka(emptyList(), p, z))

    // Epizoda za preverbo serije.
    preveriEnako("serija: naslednja za gledanje", "tt1:2:3", FilterViraPravila.epizodaZaPreverbo("tt1:2:3", "tt1:1:1"))
    preveriEnako("serija: brez napredka S1E1", "tt1:1:1", FilterViraPravila.epizodaZaPreverbo(null, "tt1:1:1"))
    preveriEnako("serija: katalog epizod ne pozna", null, FilterViraPravila.epizodaZaPreverbo("", null))

    // Predpomnilnik in ponovitve (lastnik 19:44: rok/napaka ne sme naslova skriti za ure).
    val t0 = 1_000_000_000L
    val min = 60_000L
    val ni = FilterViraPravila.zapisi(null, Izid.NI, null, t0)
    preveriEnako("NI se zapise kot negativen", Izid.NI, ni.izid)
    preveriEnako("NI: 5 h ne vprasamo znova", false, FilterViraPravila.vprasati(ni, t0 + 5 * 60 * min))
    preveriEnako("NI: po 6 h znova", true, FilterViraPravila.vprasati(ni, t0 + 6 * 60 * min + 1))
    val napaka1 = FilterViraPravila.zapisi(null, Izid.NE_VEMO, null, t0)
    preveriEnako("NE_VEMO se ne zapise kot NI", Izid.NE_VEMO, napaka1.izid)
    preveriEnako("NE_VEMO: naslov ni prikazan", false, FilterViraPravila.pokazi(napaka1, t0))
    preveriEnako("NE_VEMO: se iscemo (napis Iscem)", true, FilterViraPravila.seIscemo(napaka1, t0))
    preveriEnako("NE_VEMO: po 1 min se ne", false, FilterViraPravila.vprasati(napaka1, t0 + min))
    preveriEnako("NE_VEMO: po 2 min znova (ne cez ure)", true, FilterViraPravila.vprasati(napaka1, t0 + 2 * min))
    val napaka2 = FilterViraPravila.zapisi(napaka1, Izid.NE_VEMO, null, t0 + 2 * min)
    preveriEnako("NE_VEMO 2x: zamik 4 min", false to true,
        FilterViraPravila.vprasati(napaka2, t0 + 5 * min) to FilterViraPravila.vprasati(napaka2, t0 + 6 * min))
    var z3 = napaka2
    z3 = FilterViraPravila.zapisi(z3, Izid.NE_VEMO, null, t0 + 6 * min)
    preveriEnako("NE_VEMO 3x: konec ponovitev (zanka brez meje ni dovoljena)", false, FilterViraPravila.vprasati(z3, t0 + 60 * min))
    preveriEnako("NE_VEMO 3x: ne iscemo vec (napis se spremeni)", false, FilterViraPravila.seIscemo(z3, t0 + 60 * min))
    preveriEnako("NE_VEMO 3x: po 6 h nova preverba", true, FilterViraPravila.vprasati(z3, t0 + 6 * min + 6 * 60 * min + 1))
    val je = FilterViraPravila.zapisi(null, Izid.NA_VOLJO, KakovostPravila.Oznaka.FULL_HD, t0)
    preveriEnako("NA_VOLJO: prikazan", true, FilterViraPravila.pokazi(je, t0 + min))
    preveriEnako("NE_VEMO ne povozi svezega NA_VOLJO", je, FilterViraPravila.zapisi(je, Izid.NE_VEMO, null, t0 + min))
    preveriEnako("NA_VOLJO po 6 h: ne prikazemo, dokler ni znova potrjen", false, FilterViraPravila.pokazi(je, t0 + 6 * 60 * min + 1))
    preveriEnako("zapis iz prihodnosti ni svez", false, FilterViraPravila.pokazi(je, t0 - min))
    preveriEnako("zapis v niz in nazaj", je, FilterViraPravila.izNiza(FilterViraPravila.vNiz(je)))
    preveriEnako("pokvarjen niz", null, FilterViraPravila.izNiza("NA_VOLJO;x;;0"))
    preveriEnako("kljuc: vir + racun + naslov", "usenet|ab12|series|tt1", FilterViraPravila.kljuc("usenet", "ab12", "series|tt1"))

    // Zakon solidarnosti (lastnik 5. 11. 2026): kodek zna TV SAM ALI naprava v Linku, ki tok pretvori.
    val samoPomoc = Izidi(racun, Odgovor(listOf(hevc10)))
    val brezMain10 = z.copy(hevc10 = false)
    preveriEnako("POMOC: TV kodeka ne zna, pomocnika ni -> skrit", false, FilterViraPravila.lahkoGleda(p, samoPomoc, brezMain10))
    preveriEnako("POMOC: TV kodeka ne zna, pomocnik v Linku -> v filtru", true,
        FilterViraPravila.lahkoGleda(p, samoPomoc, brezMain10, pomocnik = true))
    preveriEnako("POMOC: TrueHD prek pomocnika", true, FilterViraPravila.lahkoGleda(p,
        Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.1080p.BluRay.TrueHD.7.1.x264", 9.0)))), z, pomocnik = true))
    preveriEnako("POMOC: slike diska ne pretvori nihce", false, FilterViraPravila.lahkoGleda(p,
        Izidi(racun, Odgovor(listOf(Izdaja("Film.2020.COMPLETE.BLURAY-GRP", 40.0)))), z, pomocnik = true))
    preveriEnako("POMOC: pomocnik ne odpravi drugih pogojev (geslo)", false, FilterViraPravila.lahkoGleda(p,
        Izidi(racun, Odgovor(listOf(hevc10.copy(geslo = true)))), brezMain10, pomocnik = true))
    preveriEnako("POMOC: pot TV / POMOCNIK / NE", listOf(FilterViraPravila.Pot.TV, FilterViraPravila.Pot.POMOCNIK, FilterViraPravila.Pot.NE),
        listOf(dobra, hevc10, Izdaja("Film.BDMV", 1.0)).map { FilterViraPravila.pot(it, brezMain10) })
    val pv = FilterViraPravila.preverba(samoPomoc, p, brezMain10)
    preveriEnako("POMOC: preverba - TV sam NI, s pomocnikom da, znacka 4K", Triple(Izid.NI, true, KakovostPravila.Oznaka.UHD),
        Triple(pv.izid, pv.pomoc, pv.oznaka))
    preveriEnako("POMOC: preverba brez izdaj - ni pomoci", false, FilterViraPravila.preverba(Izidi(racun, Odgovor(emptyList())), p, z).pomoc)
    preveriEnako("POMOC: preverba - TV sam zna, pomoc ni potrebna", Izid.NA_VOLJO to false,
        FilterViraPravila.preverba(Izidi(racun, Odgovor(listOf(dobra, hevc10))), p, brezMain10).let { it.izid to it.pomoc })
    preveriEnako("POMOC: napaka indeksatorja ostane NE_VEMO", Izid.NE_VEMO to false,
        FilterViraPravila.preverba(Izidi(racun, Odgovor(null)), p, brezMain10).let { it.izid to it.pomoc })
    val zp = FilterViraPravila.zapisi(null, pv.izid, pv.oznaka, t0, pv.pomoc)
    preveriEnako("POMOC: zapis brez pomocnika skrit", false, FilterViraPravila.pokazi(zp, t0 + min, pomocnik = false))
    preveriEnako("POMOC: zapis s pomocnikom prikazan (brez nove zahteve indeksatorju)", true, FilterViraPravila.pokazi(zp, t0 + min, pomocnik = true))
    preveriEnako("POMOC: pomocnik odide -> takoj skrit", false, FilterViraPravila.pokazi(zp, t0 + 2 * min, pomocnik = false))
    preveriEnako("POMOC: navaden NI s pomocnikom ostane skrit", false, FilterViraPravila.pokazi(ni, t0 + min, pomocnik = true))
    preveriEnako("POMOC: zapis po 6 h ni svez tudi s pomocnikom", false, FilterViraPravila.pokazi(zp, t0 + 6 * 60 * min + 1, pomocnik = true))
    preveriEnako("POMOC: zapis v niz in nazaj", zp, FilterViraPravila.izNiza(FilterViraPravila.vNiz(zp)))
    preveriEnako("POMOC: zapis prejsnje razlicice (4 polja) brez pomoci", FilterViraPravila.Zapis(Izid.NI, 5L, null, 0, false),
        FilterViraPravila.izNiza("NI;5;;0"))
    preveriEnako("POMOC: neveljavna oznaka pomoci", null, FilterViraPravila.izNiza("NI;5;;0;x"))
    preveriEnako("POMOC: pomoc pri NA_VOLJO ni zapisana", false, FilterViraPravila.izNiza("NA_VOLJO;5;;0;1")?.pomoc)

    // Kvota indeksatorja.
    val om = FilterViraPravila.Omejevalnik(3, prviPremorMs = 10 * min, najvecPremorMs = 10 * min)
    preveriEnako("omejevalnik: 3 na minuto", listOf(true, true, true, false), (0 until 4).map { om.smem(t0 + it) })
    preveriEnako("omejevalnik: po minuti spet", true, om.smem(t0 + min))
    om.meja(t0 + min)
    preveriEnako("omejevalnik: meja (429) ustavi", false, om.smem(t0 + 2 * min))
    preveriEnako("omejevalnik: po premoru spet", true, om.smem(t0 + 12 * min))

    // Pregled 6. 11. 2026 (U1): pogled ne porabi kvote; kvoto porabi samo poslana zahteva.
    val om2 = FilterViraPravila.Omejevalnik(2)
    preveriEnako("U1: lahko ne porabi", listOf(true, true, true), (0 until 3).map { om2.lahko(t0) })
    preveriEnako("U1: po treh pogledih se 2 zahtevi", listOf(true, true, false), (0 until 3).map { om2.smem(t0) })
    preveriEnako("U1: polna kvota - lahko ne", false, om2.lahko(t0 + 1))
    preveriEnako("U1: brez racuna ne posljemo", false, FilterViraPravila.poslji(Izid.NE_VEMO, "tt1", true))
    preveriEnako("U1: brez id ne posljemo", false, FilterViraPravila.poslji(Izid.NA_VOLJO, null, true))
    preveriEnako("U1: naslova ne iscemo", false, FilterViraPravila.poslji(Izid.NA_VOLJO, "tt1", false))
    preveriEnako("U1: racun, id, iscemo -> poslji", true, FilterViraPravila.poslji(Izid.NA_VOLJO, "tt1", true))
    // U2: zagon naslednje epizode se steje (tudi ce je kvota polna), preverbe filtra v isti minuti pocakajo.
    val om3 = FilterViraPravila.Omejevalnik(2)
    om3.zabelezi(t0); om3.zabelezi(t0 + 1)
    preveriEnako("U2: dva zagona zapolnita minuto", false, om3.smem(t0 + 2))
    om3.zabelezi(t0 + 3)
    preveriEnako("U2: po minuti od zadnjih spet", true, om3.smem(t0 + 3 + min))

    preveriEnako("prazno: iscemo", FilterViraPravila.Prazno.ISCEM, FilterViraPravila.prazno(true, true))
    preveriEnako("prazno: ne odgovarja", FilterViraPravila.Prazno.NE_ODGOVARJA, FilterViraPravila.prazno(false, true))
    preveriEnako("prazno: ni naslovov", FilterViraPravila.Prazno.NI_NASLOVOV, FilterViraPravila.prazno(false, false))

    // Lastnik 6. 11. 2026 04:19 (Abbott Elementary znova v filtru): filter preveri epizodo, ki se bo predvajala.
    val E = FilterViraPravila::Epizoda
    val ep = listOf(E("tt9:1:1", 1, 1), E("tt9:1:2", 1, 2), E("tt9:1:3", 1, 3), E("tt9:2:1", 2, 1))
    preveriEnako("ogled: brez ogleda S1E1", "tt9:1:1", FilterViraPravila.epizodaZaOgled(ep, null)?.id)
    preveriEnako("ogled: gleda S1E3 (ni odgledana) -> S1E3, ne S1E1", "tt9:1:3",
        FilterViraPravila.epizodaZaOgled(ep, FilterViraPravila.Ogled(1, 3, false))?.id)
    preveriEnako("ogled: S1E3 odgledana -> S2E1", "tt9:2:1", FilterViraPravila.epizodaZaOgled(ep, FilterViraPravila.Ogled(1, 3, true))?.id)
    preveriEnako("ogled: zadnja epizoda serije odgledana -> S1E1", "tt9:1:1",
        FilterViraPravila.epizodaZaOgled(ep, FilterViraPravila.Ogled(2, 1, true))?.id)
    preveriEnako("ogled: prazen seznam epizod", null, FilterViraPravila.epizodaZaOgled(emptyList(), FilterViraPravila.Ogled(1, 3, false)))
    val kb = FilterViraPravila.kljuc(FilterViraPravila.VIR_USENET, "odtis", "series|tt9")
    preveriEnako("kljuc: brez ogleda kot prej", kb, FilterViraPravila.kljucOgleda(kb, null))
    preveriEnako("kljuc: ogled S1E3 je drug zapis kot S1E1", true,
        FilterViraPravila.kljucOgleda(kb, FilterViraPravila.Ogled(1, 3, false)) != FilterViraPravila.kljucOgleda(kb, FilterViraPravila.Ogled(1, 1, false)))
    preveriEnako("kljuc: odgledana ni ista kot zaceta", true,
        FilterViraPravila.kljucOgleda(kb, FilterViraPravila.Ogled(1, 3, true)) != FilterViraPravila.kljucOgleda(kb, FilterViraPravila.Ogled(1, 3, false)))

    // Ista poizvedba kot predvajanje: id dodatka, nato IMDb:S:E.
    preveriEnako("id: epizoda dodatka brez IMDb -> po IMDb (kot predvajanje)", "tt123:1:3",
        FilterViraPravila.idIskanjaEpizode(E("tmdb:55:1:3", 1, 3), "tt123"))
    preveriEnako("id: epizoda z IMDb ostane", "tt123:1:3", FilterViraPravila.idIskanjaEpizode(E("tt123:1:3", 1, 3), "tt123"))
    preveriEnako("id: brez IMDb ni iskanja", null, FilterViraPravila.idIskanjaEpizode(E("kitsu:7:3", 1, 3), null))
    preveriEnako("id: prvi iskalni", "tt1:1:2", FilterViraPravila.idIskanja(listOf("kitsu:1", "tt1:1:2", "tt2")))
    preveriEnako("rok: isti kot predvajanje", UsenetPravila.ISKANJE_ROK_MS, FilterViraPravila.ROK_MS)

    // Tocka d: razlog »ne vemo« v dnevniku.
    preveriEnako("razlog: brez racuna", "ni_racuna", FilterViraPravila.razlogNeVemo(Izid.NE_VEMO, "tt1", true, false, null))
    preveriEnako("razlog: brez id", "ni_iden", FilterViraPravila.razlogNeVemo(racun, null, false, false, null))
    preveriEnako("razlog: id ni IMDb", "ni_imdb", FilterViraPravila.razlogNeVemo(racun, "kitsu:1", false, false, null))
    preveriEnako("razlog: HTTP", "http_503", FilterViraPravila.razlogNeVemo(racun, "tt1", true, true, "http_503"))

    // Lastnik 6. 11. 2026 04:22: Nalozi vec s filtrom Usenet ni sprozil nobene preverbe (proracun je ostal 0).
    val zacetni = FilterViraPravila.proracun(null, true, 0)
    preveriEnako("proracun: odprtje mreze", FilterViraPravila.PRORACUN_MREZE, zacetni.ostalo)
    val vnaprej = FilterViraPravila.proracun(zacetni.copy(ostalo = 0), true, 0)
    preveriEnako("proracun: porabljen -> prednalaganje naslednje strani", FilterViraPravila.VNAPREJ_MREZE to true, vnaprej.ostalo to vnaprej.vnaprej)
    preveriEnako("proracun: prednalaganje enkrat na zahtevo", 0, FilterViraPravila.proracun(vnaprej.copy(ostalo = 0), true, 0).ostalo)
    preveriEnako("proracun: Nalozi vec (zahteva 1) da nov proracun", FilterViraPravila.PRORACUN_MREZE,
        FilterViraPravila.proracun(vnaprej.copy(ostalo = 0), true, 1).ostalo)
    preveriEnako("proracun: nova mreza da nov proracun", FilterViraPravila.PRORACUN_MREZE,
        FilterViraPravila.proracun(vnaprej.copy(ostalo = 0), false, 0).ostalo)
    preveriEnako("proracun: med krogom ostane", 7, FilterViraPravila.proracun(zacetni.copy(ostalo = 7), true, 0).ostalo)

    // Lastnik 6. 11. 2026 06:29-06:35: indeksator najvec 2 hkrati, razmik pod 1 s, nizka stopnja; premor ob meji raste od 5 s.
    preveriEnako("meja: 60 na minuto (lastnik 6. 11. 11:08)", 60, FilterViraPravila.NA_MINUTO)
    preveriEnako("meja: 2 hkrati", 2, FilterViraPravila.HKRATI)
    preveriEnako("razmik pod 1 s", true, FilterViraPravila.RAZMIK_MS in 1L..999L)
    val om4 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO)
    preveriEnako("razmik: prva brez cakanja", 0L, om4.razmik(t0))
    preveriEnako("sunek: 60 v minuti", true, (0 until 60).all { om4.smem(t0 + it) })
    preveriEnako("sunek: 61. v isti minuti ne", false, om4.smem(t0 + 140))
    preveriEnako("razmik: takoj po zahtevi pocakaj", true, om4.razmik(t0 + 99) in 1L..FilterViraPravila.RAZMIK_MS)
    val om5 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO)
    om5.meja(t0)
    preveriEnako("meja: prvi premor 5 s (ne 15 min)", false to true, om5.lahko(t0 + 4_999) to om5.lahko(t0 + 5_000))
    om5.meja(t0 + 5_000)
    preveriEnako("meja: drugi zaporedni 10 s", false to true, om5.lahko(t0 + 14_999) to om5.lahko(t0 + 15_000))
    om5.uspeh(t0 + 20_000); om5.meja(t0 + 20_000)
    preveriEnako("uspeh ponastavi premor na 5 s", true, om5.lahko(t0 + 25_000))
    val om5b = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO)
    om5b.meja(t0)
    om5b.meja(t0 + 300)
    preveriEnako("meja istega sunka ne podaljsa", true, om5b.lahko(t0 + 5_000))
    om5b.meja(t0 + 5_000)
    preveriEnako("naslednja meja po koncu premora da 10 s", false to true, om5b.lahko(t0 + 14_999) to om5b.lahko(t0 + 15_000))
    val om6 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO)
    var casPremora = t0
    repeat(30) {
        om6.meja(casPremora)
        casPremora += om6.premorSe(casPremora)
    }
    preveriEnako("premor najvec 15 min", 15 * min, om6.premorSe(casPremora - 15 * min))
    // Pregled Gemini: dve niti hkrati ne gresta v sunku - druga dobi rezervacijo z razmikom.
    val om7 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO)
    preveriEnako("rezervacija: prva takoj", 0L, om7.rezerviraj(t0))
    preveriEnako("rezervacija: druga ob istem casu pocaka razmik", FilterViraPravila.RAZMIK_MS, om7.rezerviraj(t0))
    preveriEnako("rezervacija: tretja dvojni razmik", 2 * FilterViraPravila.RAZMIK_MS, om7.rezerviraj(t0))
    val om8 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO)
    om8.meja(t0); om8.uspeh(t0 + 1); om8.meja(t0 + 5_000)
    preveriEnako("zapozneli uspeh med premorom ne ponastavi", false, om8.lahko(t0 + 14_999))
    preveriEnako("rezervacija med premorom ne", null, om8.rezerviraj(t0 + 6_000))

    // Del A (lastnik 7. 11. 2026 03:51): klik uporabnika ima prednost pred preverbami filtra. Zadnjih 5 mest na minuto je
    // rezerviranih za iskanje ob kliku - preverbe jih ne morejo porabiti; ob zacetku predvajanja preverbe pocakajo 60 s.
    val om9 = FilterViraPravila.Omejevalnik(10, zadrzano = 5)
    preveriEnako("prednost: preverbe porabijo le 5 od 10 mest", listOf(true, true, true, true, true, false),
        (0 until 6).map { om9.smem(t0 + it) })
    preveriEnako("prednost: rezervirano mesto za klik ostane", true, om9.rezervirajUporabniku(t0 + 10) != null)
    val om10 = FilterViraPravila.Omejevalnik(10, zadrzano = 5)
    repeat(5) { om10.smem(t0 + it) }
    preveriEnako("prednost: klik dobi vseh 5 rezerviranih mest", 5,
        (0 until 5).count { om10.rezervirajUporabniku(t0 + 20 + it) != null })
    preveriEnako("prednost: nad rezervo tudi klik ne", null, om10.rezervirajUporabniku(t0 + 30))
    val om11 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO, zadrzano = FilterViraPravila.REZERVIRANO_UPORABNIKU)
    om11.ustaviPreverbe(t0, FilterViraPravila.PREMOR_PREVERB_MS)
    preveriEnako("prednost: med predvajanjem preverbe ne vprasajo", false to false,
        om11.smem(t0 + 1_000) to om11.lahko(t0 + 1_000))
    preveriEnako("prednost: klik med premorom preverb dobi mesto", true, om11.rezervirajUporabniku(t0 + 1_000) != null)
    preveriEnako("prednost: po premoru preverbe spet", true,
        om11.smem(t0 + FilterViraPravila.PREMOR_PREVERB_MS + 1))
    // Tocka 1+ (7. 11. 2026): premor se konca s koncem iskanja ob kliku, ne sele po 60 s.
    val om12 = FilterViraPravila.Omejevalnik(FilterViraPravila.NA_MINUTO, zadrzano = FilterViraPravila.REZERVIRANO_UPORABNIKU)
    om12.ustaviPreverbe(t0, FilterViraPravila.PREMOR_PREVERB_MS)
    preveriEnako("prednost: med iskanjem klika preverbe stojijo", false, om12.smem(t0 + 1_000))
    om12.sprostiPreverbe()
    preveriEnako("prednost: po koncu iskanja klika preverbe takoj spet", true, om12.smem(t0 + 2_000))

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
