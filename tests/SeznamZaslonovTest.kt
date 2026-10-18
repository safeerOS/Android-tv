package si.safeer.tv.os

import si.safeer.tv.os.SeznamZaslonov.Dostop
import si.safeer.tv.os.SeznamZaslonov.Naprava
import si.safeer.tv.os.SeznamZaslonov.Prazno
import si.safeer.tv.os.SeznamZaslonov.Znana

/**
 * Preizkus [SeznamZaslonov] (lastnik, 16. 10. 2026): na seznamu »Povezani zasloni« so vse naprave z zaslonom, ki jih ta
 * naprava sme gledati; brez dovoljenja nikoli kot dostopne, ta naprava nikoli, brez povezave oznacene, prazno pojasnjeno.
 */
fun main() {
    val s = SeznamZaslonov
    val jaz = Naprava("n-aaaaaaaaaaaaaaaa-os", "OnePlus 9", "phone", listOf("remote", "zaslon.deli"), "n-aaaaaaaaaaaaaaaa")
    val jazSprejemnik = Naprava("n-aaaaaaaaaaaaaaaa", "Safeer OS (OnePlus 9)", "phone", listOf("remote", "zaslon.deli", "files"), "n-aaaaaaaaaaaaaaaa")
    val linux = Naprava("n-1111111111111111-control", "Namizje", "linux", listOf("desktop", "apps", "files"), "n-1111111111111111")
    val linuxBrskalnik = Naprava("n-1111111111111111", "Safeer (Namizje)", "linux", listOf("files"), "n-1111111111111111")
    val windows = Naprava("n-2222222222222222-control", "Prenosnik", "windows", listOf("desktop", "apps"), "n-2222222222222222")
    val tablica = Naprava("n-3333333333333333", "Tablica", "tablet", listOf("zaslon.deli", "remote"), "n-3333333333333333")
    val telefon = Naprava("n-4444444444444444", "S25", "phone", listOf("remote", "files"), "n-4444444444444444")
    val tv = Naprava("n-5555555555555555", "Philips", "tv", listOf("zaslon.deli"), "n-5555555555555555")
    val jeTa: (Naprava) -> Boolean = { it.fizicna == jaz.fizicna || it.id == jaz.id }
    val vse = listOf(jaz, jazSprejemnik, linux, linuxBrskalnik, windows, tablica, telefon, tv)

    // Zmoznost zaslona: racunalnik (desktop) ali naprava Android (zaslon.deli); telefon brez nje ni zaslon.
    val zZaslonom = s.zZaslonom(vse, jeTa).map { it.id }
    check(zZaslonom.toSet() == setOf(linux.id, windows.id, tablica.id, tv.id)) { "naprave z zaslonom: $zZaslonom" }
    // Ta naprava (in njen sorodnik z istim kljucem) ni nikoli na seznamu; sorodnik racunalnika se ne podvoji.
    check(jaz.id !in zZaslonom && jazSprejemnik.id !in zZaslonom) { "ta naprava na seznamu" }
    check(linuxBrskalnik.id !in zZaslonom) { "sorodnik racunalnika podvojen" }
    check(s.jeRacunalnik(windows.zmoznosti) && !s.jeRacunalnik(tablica.zmoznosti))

    // Stanje iz odgovora tihe preverbe (ProsnjaDostopa): uspeh, prosnja caka, zavrnjeno, brez prosnje, izklopljeno.
    check(s.izOdgovora(true, "", "", "", true) == Dostop.DOSTOPNO)
    check(s.izOdgovora(true, "", "", "", false) == Dostop.DOSTOPNO)
    check(s.izOdgovora(false, "ni_dovoljeno", "", "pending", true) == Dostop.CAKA)
    check(s.izOdgovora(false, "ni_dovoljeno", "", "declined", true) == Dostop.ZAVRNJENO)
    check(s.izOdgovora(false, "ni_dovoljeno", "", "none", true) == Dostop.PROSNJA) { "racunalnik zna prosnjo: ponudimo jo" }
    check(s.izOdgovora(false, "ni_dovoljeno", "", "", true) == Dostop.SKRITO) { "deljenje izklopljeno: skrito" }
    check(s.izOdgovora(false, "ni_dovoljeno", "", "", false) == Dostop.SKRITO) { "Android brez dovoljenja: skrito" }
    check(s.izOdgovora(false, "ni_dovoljeno", "", "pending", false) == Dostop.SKRITO) { "Android prosnje ne pozna" }
    check(s.izOdgovora(null, null, null, null, true) == Dostop.NI_ODGOVORA) { "potek" }
    check(s.izOdgovora(false, "neznano_dejanje", "", "", true) == Dostop.NI_ODGOVORA) { "starejsa razlicica brez preverbe" }

    // Seznam: brez dovoljenja ni dostopno, skrito izpade, vrstni red: dostopno, preverjam, prosnje, zavrnjeno.
    val stanja = mapOf(linux.id to Dostop.DOSTOPNO, windows.id to Dostop.PROSNJA, tablica.id to Dostop.SKRITO, tv.id to Dostop.ZAVRNJENO)
    val seznam = s.seznam(vse, jeTa, emptyList(), stanja)
    check(seznam.map { it.id } == listOf(linux.id, windows.id, tv.id)) { "seznam: ${seznam.map { it.id }}" }
    check(seznam.none { it.id == jaz.id || it.id == jazSprejemnik.id }) { "ta naprava" }
    check(seznam.none { it.id == tablica.id }) { "brez dovoljenja in brez prosnje se ne pokaze" }
    check(seznam.first { it.id == windows.id }.dostop != Dostop.DOSTOPNO) { "brez dovoljenja ni dostopno" }
    check(seznam.first { it.id == linux.id }.dotik && seznam.first { it.id == windows.id }.dotik)
    check(!seznam.first { it.id == tv.id }.dotik) { "zavrnjeno samo pojasni" }
    check(seznam.all { it.povezana })
    // Pred odgovorom preverbe: naprava je na seznamu kot »preverjam« (dovoljenje preveri naprava sama ob dotiku).
    check(s.seznam(listOf(linux), jeTa, emptyList(), emptyMap()).single().dostop == Dostop.PREVERJAM)

    // Brez povezave: zapomnjena naprava ostane oznacena, ne izgine; ta naprava se ne zapomni in ne pokaze.
    val znane = s.zapomni(emptyList(), vse, jeTa, stanja)
    check(znane.map { it.id }.toSet() == setOf(linux.id, windows.id, tv.id)) { "znane: ${znane.map { it.id }}" }
    val zdaj = s.seznam(listOf(jaz, windows), jeTa, znane, mapOf(windows.id to Dostop.DOSTOPNO))
    check(zdaj.map { it.id to it.povezana } == listOf(windows.id to true, linux.id to false, tv.id to false)) { "brez povezave: $zdaj" }
    check(zdaj.filter { !it.povezana }.none { it.dotik }) { "brez povezave ni dejanja" }
    check(zdaj.first { it.id == linux.id }.racunalnik) { "zapomnjeno, da je racunalnik" }
    // Ista fizicna naprava pod drugim id-jem je v Linku: ni »brez povezave«.
    val linuxDrugId = linux.copy(id = "n-1111111111111111-os2")
    check(s.seznam(listOf(linuxDrugId), jeTa, znane, emptyMap()).none { !it.povezana && it.id == linux.id })
    // Ponareden zapis te naprave v shrambi se ne pokaze.
    val zJaz = znane + Znana(jaz.id, "jaz", "phone", false, jaz.fizicna, Dostop.DOSTOPNO)
    check(s.seznam(emptyList(), jeTa, zJaz, emptyMap()).none { it.id == jaz.id })
    // Odvzeto dovoljenje: naprava izpade iz zapisa in s seznama.
    val odvzeto = s.zapomni(znane, listOf(linux), jeTa, mapOf(linux.id to Dostop.SKRITO))
    check(odvzeto.none { it.id == linux.id }) { "odvzeto dovoljenje ostalo v zapisu" }
    check(s.seznam(listOf(linux), jeTa, odvzeto, mapOf(linux.id to Dostop.SKRITO)).none { it.id == linux.id }) { "odvzeto dovoljenje na seznamu" }
    // Stanje »preverjam« ne prepise znanega; nikoli preverjena naprava se ne zapomni.
    check(s.zapomni(znane, listOf(linux), jeTa, mapOf(linux.id to Dostop.PREVERJAM)).first { it.id == linux.id }.dostop == Dostop.DOSTOPNO)
    check(s.zapomni(emptyList(), listOf(windows), jeTa, emptyMap()).isEmpty())
    // Najvec NAJVEC_ZNANIH.
    val mnogo = (0 until 40).map { Naprava("n-%016x".format(it + 100), "R$it", "linux", listOf("desktop"), "n-%016x".format(it + 100)) }
    val mnogoZnanih = s.zapomni(emptyList(), mnogo, jeTa, mnogo.associate { it.id to Dostop.DOSTOPNO })
    check(mnogoZnanih.size == SeznamZaslonov.NAJVEC_ZNANIH)

    // Zapis in branje (imena s tabulatorjem/novo vrstico ne pokvarijo zapisa; pokvarjene vrstice izpadejo).
    val zapis = s.vNiz(znane + Znana("n-6666666666666666", "Ime\tz\ntab", "windows", true, "", Dostop.CAKA))
    val prebrano = s.izNiza(zapis)
    check(prebrano.size == 4 && prebrano.last().dostop == Dostop.CAKA && prebrano.last().racunalnik) { "zapis: $prebrano" }
    check(prebrano.take(3) == znane) { "zapis se spremeni" }
    check(s.izNiza(null).isEmpty() && s.izNiza("").isEmpty() && s.izNiza("x\ty").isEmpty() && s.izNiza("a\tb\tc\t1\t\tNEZNANO").isEmpty())

    // Prazno stanje z razlogom: brez Linka, brez naprav z zaslonom, brez dovoljenj.
    check(s.prazno(emptyList(), emptyList(), jeTa, false) == Prazno.NI_LINKA)
    check(s.prazno(emptyList(), listOf(jaz, jazSprejemnik, telefon), jeTa, true) == Prazno.NI_NAPRAV) { "samo ta naprava in telefon brez zaslona" }
    val brezDovoljenj = mapOf(tablica.id to Dostop.SKRITO, linux.id to Dostop.SKRITO)
    val prazenSeznam = s.seznam(listOf(jaz, tablica, linux), jeTa, emptyList(), brezDovoljenj)
    check(prazenSeznam.isEmpty() && s.prazno(prazenSeznam, listOf(jaz, tablica, linux), jeTa, true) == Prazno.NI_DOVOLJENJ)
    check(s.prazno(seznam, vse, jeTa, true) == null)
    // Brez Linka, a z zapomnjenimi: seznam (brez povezave), ne prazno stanje.
    val brezLinka = s.seznam(emptyList(), jeTa, znane, emptyMap())
    check(brezLinka.size == 3 && brezLinka.none { it.povezana } && s.prazno(brezLinka, emptyList(), jeTa, false) == null)

    println("SeznamZaslonovTest: OK")
}
