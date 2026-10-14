package si.safeer.tv.os

import si.safeer.tv.os.ZasloniPravila.Naprava
import si.safeer.tv.os.ZasloniPravila.Vrsta

private var napak = 0

private fun preveri(kaj: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $kaj") else { println("FAIL   $kaj"); napak++ }
}

fun main() {
    val p = ZasloniPravila
    println("== povezani zasloni: vse naprave, katerih zaslon lahko gledas ==")
    val jaz = "n-aaaaaaaaaaaaaaaa-os"
    val android = listOf("url", "media", "control", "screen", "remote", "e2e")
    val naprave = listOf(
        Naprava("n-aaaaaaaaaaaaaaaa", "Ta telefon (sprejemnik)", "receiver", "phone", android),      // ista naprava, druga oznaka
        Naprava(jaz, "Ta telefon", "receiver", "phone", android),                                   // ta naprava
        Naprava("n-1111111111111111-control", "Prenosnik", "receiver", "linux", listOf("desktop", "apps", "files")),
        Naprava("n-2222222222222222", "Televizor", "receiver", "tv", android),
        Naprava("n-2222222222222222-os", "Safeer OS (televizor)", "receiver", "tv", android),      // isti televizor, drug program
        Naprava("n-3333333333333333-os", "Tablica", "receiver", "tablet", android),
        Naprava("n-4444444444444444-control", "Stari racunalnik", "receiver", "windows", listOf("apps", "files")),
        Naprava("n-5555555555555555", "Mamin telefon", "receiver", "phone", android),
        Naprava("n-6666666666666666", "Zvocnik", "receiver", "tv", listOf("url", "media")),         // brez zaslona
        Naprava("n-7777777777777777", "Sredisce", "hub", "linux", listOf("mesh")),
        Naprava("star-sprejemnik", "Star TV", "receiver", "", listOf("screen", "remote")),
    )
    val k = p.kandidati(naprave, jaz)
    val ids = k.map { it.id }
    preveri("ta naprava in njen sorodnik (isto jedro) nista na seznamu", "n-aaaaaaaaaaaaaaaa" !in ids && jaz !in ids)
    preveri("racunalnik z deljenjem je prvi (NAMIZJE)", k.first().id == "n-1111111111111111-control" && k.first().vrsta == Vrsta.NAMIZJE)
    preveri("telefoni, tablica, televizor (screen + remote) so na seznamu", listOf("n-2222222222222222", "n-3333333333333333-os",
        "n-5555555555555555").all { it in ids } && k.filter { it.vrsta == Vrsta.ANDROID }.size == 4)
    preveri("isti televizor pod dvema oznakama je na seznamu enkrat", ids.count { it.startsWith("n-2222222222222222") } == 1)
    preveri("racunalnik brez deljenja je na seznamu (izklopljen, zadnji)", k.last().id == "n-4444444444444444-control" &&
        k.last().vrsta == Vrsta.NAMIZJE_IZKLOPLJENO)
    preveri("naprava brez zaslona in sredisce nista na seznamu", "n-6666666666666666" !in ids && "n-7777777777777777" !in ids)
    preveri("star sprejemnik brez platforme (screen + remote) je Android", k.any { it.id == "star-sprejemnik" && it.vrsta == Vrsta.ANDROID })
    preveri("vrstni red: namizje, android, izklopljeno", k.map { it.vrsta.ordinal } == k.map { it.vrsta.ordinal }.sorted())
    preveri("opis kartice: brez izklopljenih racunalnikov", p.opis(k) == k.filter { it.vrsta != Vrsta.NAMIZJE_IZKLOPLJENO }.map { it.ime } &&
        "Stari racunalnik" !in p.opis(k) && "Prenosnik" in p.opis(k) && "Mamin telefon" in p.opis(k))

    // Ista fizicna naprava: deljenje racunalnika (desktop) zmaga nad oznako brez njega.
    val pc = p.kandidati(listOf(
        Naprava("n-8888888888888888-os", "PC (Safeer OS)", "receiver", "linux", listOf("apps")),
        Naprava("n-8888888888888888-control", "PC", "receiver", "linux", listOf("desktop"))), jaz)
    preveri("isti racunalnik: obdrzi oznako z deljenjem", pc.size == 1 && pc[0].id == "n-8888888888888888-control" && pc[0].vrsta == Vrsta.NAMIZJE)

    // Samo en kandidat (npr. telefon z enim prenosnikom): odpre se takoj - en sam element.
    preveri("en kandidat", p.kandidati(listOf(naprave[1], naprave[2]), jaz).size == 1)
    preveri("prazen lastni id ne izkljuci nicesar po pomoti", p.kandidati(naprave, "").size == k.size + 1)
    preveri("ime: prazno ime -> id", p.kandidati(listOf(Naprava("n-9999999999999999", "", "receiver", "phone", android)), jaz)[0].ime == "n-9999999999999999")

    // Pricakovani zaslon: samo naprava, ki smo jo prosili, v 3 minutah, enkrat; sorodnik z istim jedrom velja.
    val pr = ZasloniPravila.Pricakovani()
    val t = 1_800_000_000_000L
    pr.pricakuj("n-2222222222222222-os", t)
    preveri("nepricakovana naprava ne sme", !pr.sprejmi("n-5555555555555555", t + 1000))
    preveri("pricakovana naprava (oznaka sredisca z istim jedrom) sme", pr.sprejmi("n-2222222222222222", t + 1000))
    preveri("samo enkrat", !pr.sprejmi("n-2222222222222222", t + 2000))
    pr.pricakuj("n-3333333333333333-os", t)
    preveri("po 3 minutah ne vec", !pr.sprejmi("n-3333333333333333-os", t + ZasloniPravila.PRICAKUJ_MS))
    preveri("prazen posiljatelj ne sme", !pr.sprejmi("", t))

    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
