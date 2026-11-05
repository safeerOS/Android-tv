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
    val om = FilterViraPravila.Omejevalnik(3, premorMs = 10 * min)
    preveriEnako("omejevalnik: 3 na minuto", listOf(true, true, true, false), (0 until 4).map { om.smem(t0 + it) })
    preveriEnako("omejevalnik: po minuti spet", true, om.smem(t0 + min))
    om.meja(t0 + min)
    preveriEnako("omejevalnik: meja (429) ustavi", false, om.smem(t0 + 2 * min))
    preveriEnako("omejevalnik: po premoru spet", true, om.smem(t0 + 12 * min))

    preveriEnako("prazno: iscemo", FilterViraPravila.Prazno.ISCEM, FilterViraPravila.prazno(true, true))
    preveriEnako("prazno: ne odgovarja", FilterViraPravila.Prazno.NE_ODGOVARJA, FilterViraPravila.prazno(false, true))
    preveriEnako("prazno: ni naslovov", FilterViraPravila.Prazno.NI_NASLOVOV, FilterViraPravila.prazno(false, false))

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
