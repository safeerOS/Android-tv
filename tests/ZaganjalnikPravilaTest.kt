package si.safeer.tv.os

import si.safeer.tv.os.ZaganjalnikPravila.Sirina
import si.safeer.tv.os.ZaganjalnikPravila.Vnos
import si.safeer.tv.os.ZaganjalnikPravila.Vrstica

/**
 * Prilagodljivi zaganjalnik: postavitev po sirini okna in nacinu upravljanja, zascita pred padci,
 * znacke samo za obvestila, ki potrebujejo uporabnika, in vsebina domacega zaslona brez dvojnikov.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private fun postavitve() {
    println("\n== postavitev po sirini okna in vnosu ==")
    // Telefon pokonci (WP28 S ~ 393 dp, OnePlus 9 ~ 411 dp)
    val telefon = ZaganjalnikPravila.izberi(393, 851, Vnos.DOTIK)
    enako("telefon pokonci je ozek", Sirina.OZKA, telefon.sirina)
    enako("telefon pokonci: tirnica z ikonami", Vrstica.IKONE, telefon.vrstica)
    enako("telefon pokonci: en stolpec", 1, telefon.stolpci)
    // Telefon lezece: sirok, a nizek - tirnica, da ostane prostor vsebini
    val telefonLezece = ZaganjalnikPravila.izberi(851, 393, Vnos.DOTIK)
    enako("telefon lezece: tirnica (nizko okno)", Vrstica.IKONE, telefonLezece.vrstica)
    // Galaxy Tab A9+ pokonci ~ 800 dp, lezece ~ 1280 dp
    val tablicaPokonci = ZaganjalnikPravila.izberi(800, 1232, Vnos.DOTIK)
    enako("tablica pokonci je srednja", Sirina.SREDNJA, tablicaPokonci.sirina)
    enako("tablica pokonci: samo ikone", Vrstica.IKONE, tablicaPokonci.vrstica)
    enako("tablica pokonci: dva stolpca", 2, tablicaPokonci.stolpci)
    val tablicaLezece = ZaganjalnikPravila.izberi(1280, 752, Vnos.DOTIK)
    enako("tablica lezece je siroka", Sirina.SIROKA, tablicaLezece.sirina)
    enako("tablica lezece: vrstica z imeni", Vrstica.Z_IMENI, tablicaLezece.vrstica)
    enako("tablica lezece: mreza s tremi stolpci", 3, tablicaLezece.stolpci)
    // Razdeljen zaslon na tablici: polovica = ozko okno, telefonska postavitev
    enako("polovica tablice (razdeljen zaslon) je ozka", Sirina.OZKA, ZaganjalnikPravila.izberi(400, 752, Vnos.DOTIK).sirina)
    // Televizor 960 x 540 dp, daljinec
    val tv = ZaganjalnikPravila.izberi(960, 540, Vnos.DALJINEC)
    enako("televizor: vrste kartic", true, tv.vrste)
    preveri("televizor: vecja pisava", tv.merilo > 1f)
    enako("televizor: vrstica z imeni", Vrstica.Z_IMENI, tv.vrstica)
    // Meje razredov
    enako("599 dp je ozko", Sirina.OZKA, ZaganjalnikPravila.sirina(599))
    enako("600 dp je srednje", Sirina.SREDNJA, ZaganjalnikPravila.sirina(600))
    enako("839 dp je srednje", Sirina.SREDNJA, ZaganjalnikPravila.sirina(839))
    enako("840 dp je siroko", Sirina.SIROKA, ZaganjalnikPravila.sirina(840))
    // Ploscice v vrsti
    enako("telefon: 4 ploscice po 76 dp", 4, ZaganjalnikPravila.ploscicVVrsti(361, 76))
    enako("ozko okno ima vsaj 3", 3, ZaganjalnikPravila.ploscicVVrsti(200, 76))
    enako("siroko okno najvec 8", 8, ZaganjalnikPravila.ploscicVVrsti(3000, 96))
}

private fun padci() {
    println("\n== zascita pred padci ==")
    val zdaj = 1_000_000L
    preveri("en padec ne vrne domacega zaslona", !ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 1000), zdaj, 1))
    preveri("dva padca v minuti ga vrneta", ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 30_000, zdaj - 1000), zdaj, 1))
    preveri("dva padca v razmiku ure ne", !ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 3_600_000, zdaj - 1000), zdaj, 1))
    preveri("brez drugega zaganjalnika (TV) ostanemo", !ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 2000, zdaj - 1000, zdaj), zdaj, 0))
    enako("stari padci se pozabijo", listOf(zdaj - 10), ZaganjalnikPravila.svezi(listOf(zdaj - 999_999, zdaj - 10), zdaj))
    preveri("FallbackHome ni pravi zaganjalnik",
        !ZaganjalnikPravila.jePraviZaganjalnik("com.android.tv.settings", "com.android.tv.settings.system.FallbackHome", -1000, "si.safeer.os"))
    preveri("One UI Home je pravi zaganjalnik",
        ZaganjalnikPravila.jePraviZaganjalnik("com.sec.android.app.launcher", "com.sec.android.app.launcher.activities.LauncherActivity", 0, "si.safeer.tablet"))
    preveri("mi nismo drug zaganjalnik",
        !ZaganjalnikPravila.jePraviZaganjalnik("si.safeer.tablet", "si.safeer.tv.os.ZaganjalnikAlias", 0, "si.safeer.tablet"))
}

private fun znacke() {
    println("\n== znacke samo za obvestila, ki potrebujejo uporabnika ==")
    fun z(trajno: Boolean = false, ospredje: Boolean = false, povzetek: Boolean = false, kat: String? = null,
          pom: Int = 3, dovoli: Boolean = true, napredek: Boolean = false) =
        ZaganjalnikPravila.potrebujeUporabnika(trajno, ospredje, povzetek, kat, pom, dovoli, napredek)
    preveri("sporocilo steje", z(kat = "msg", pom = 4))
    preveri("e-posta steje", z(kat = "email"))
    preveri("klic steje", z(kat = "call", pom = 4))
    preveri("obvestilo brez kategorije (pomembnost privzeta) steje", z())
    preveri("predvajanje (trajno) ne steje", !z(trajno = true, kat = "transport"))
    preveri("storitev v ospredju ne steje", !z(ospredje = true))
    preveri("povzetek skupine ne steje (sicer dvojno)", !z(povzetek = true, kat = "msg"))
    preveri("kategorija service ne steje", !z(kat = "service"))
    preveri("prenos z napredkom ne steje", !z(napredek = true))
    preveri("tiho obvestilo (nizka pomembnost) ne steje", !z(pom = 2))
    preveri("aplikacija znacke ne zeli", !z(dovoli = false))
    enako("brez znacke", "", ZaganjalnikPravila.besediloZnacke(0))
    enako("5", "5", ZaganjalnikPravila.besediloZnacke(5))
    enako("vec kot 9", "9+", ZaganjalnikPravila.besediloZnacke(42))
}

private fun vsebina() {
    println("\n== vsebina domacega zaslona brez dvojnikov ==")
    enako("odstranjena aplikacija izpade, dvojnik tudi", listOf("a", "c"),
        ZaganjalnikPravila.priljubljene(listOf("a", "b", "a", "c"), setOf("a", "c")))
    val pril = listOf("pc" to "gimp", "pc" to "firefox")
    val ned = listOf("pc" to "gimp", "tablica" to "si.safeer.tablet", "pc" to "vlc")
    enako("priljubljen program se med nedavnimi ne ponovi",
        listOf("pc" to "gimp", "pc" to "firefox", "tablica" to "si.safeer.tablet", "pc" to "vlc"),
        ZaganjalnikPravila.programiNaprav(pril, ned))
    enako("najvec N", 2, ZaganjalnikPravila.programiNaprav(pril, ned, najvec = 2).size)
    preveri("video je datoteka", "video" in ZaganjalnikPravila.VRSTE_DATOTEK)
    preveri("program ni datoteka", "program" !in ZaganjalnikPravila.VRSTE_DATOTEK)
}

private fun varnostnaVrata() {
    println("\n== varnostna vrata: vrnitev tovarniskega zaganjalnika ==")
    val zdaj = 10_000_000L
    val min = 60_000L
    preveri("3 padci v 5 min vrnejo domaci zaslon",
        ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 4 * min, zdaj - 2 * min, zdaj - 10_000), zdaj, 1))
    preveri("2 padca v 5 min (razmik vec kot minuta) se ne",
        !ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 4 * min, zdaj - 10_000), zdaj, 1))
    preveri("3 padci v 6 min (prvi zunaj okna) se ne",
        !ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 6 * min, zdaj - 2 * min, zdaj - 10_000), zdaj, 1))
    preveri("3 padci v 5 min brez drugega zaganjalnika: ostanemo",
        !ZaganjalnikPravila.vrniPrejsnjemu(listOf(zdaj - 4 * min, zdaj - 2 * min, zdaj - 10_000), zdaj, 0))
    enako("svezi hrani 5 minut", listOf(zdaj - 4 * min), ZaganjalnikPravila.svezi(listOf(zdaj - 6 * min, zdaj - 4 * min), zdaj))
    enako("isti padec v obeh zapisih steje enkrat", listOf(1000L, 20_000L),
        ZaganjalnikPravila.zdruziPadce(listOf(1000L, 20_000L), listOf(2500L, 21_000L)))
    enako("razlicna padca ostaneta dva", listOf(1000L, 9000L), ZaganjalnikPravila.zdruziPadce(listOf(9000L), listOf(1000L)))
    enako("samo padci po vklopu", listOf(5000L), ZaganjalnikPravila.padciPo(listOf(1000L, 5000L), 2000L))
    // Celota: sistemski zapis (ANR, padec v C) + lasten zapis, star padec pred ponovnim vklopom ne steje.
    val sistem = listOf(zdaj - 3 * min, zdaj - 90_000)
    val lastni = listOf(zdaj - 90_000 + 800, zdaj - 5_000)
    val vsi = ZaganjalnikPravila.padciPo(ZaganjalnikPravila.zdruziPadce(lastni, sistem), zdaj - 4 * min)
    enako("zdruzeni padci", 3, vsi.size)
    preveri("trije zdruzeni padci vrnejo", ZaganjalnikPravila.vrniPrejsnjemu(vsi, zdaj, 2))
    preveri("po ponovnem vklopu stari padci ne vrnejo",
        !ZaganjalnikPravila.vrniPrejsnjemu(ZaganjalnikPravila.padciPo(vsi, zdaj - 60_000), zdaj, 2))
}

private fun namizje() {
    println("\n== namizna razporeditev (tablica lezece, zunanji zaslon, DeX) ==")
    enako("televizor je daljinec, tudi z zunanjim zaslonom", Vnos.DALJINEC, ZaganjalnikPravila.vnos(true, true, true))
    enako("zunanji zaslon brez dotika: kazalec kot dotik", Vnos.DOTIK, ZaganjalnikPravila.vnos(false, true, true))
    enako("zaslon brez dotika drugje: daljinec", Vnos.DALJINEC, ZaganjalnikPravila.vnos(false, true, false))
    enako("telefon: dotik", Vnos.DOTIK, ZaganjalnikPravila.vnos(false, false, false))
    preveri("tablica lezece (1280 x 752) je namizje", ZaganjalnikPravila.namizje(1280, 752, Vnos.DOTIK, false))
    preveri("tablica pokonci (800 x 1232) ni namizje", !ZaganjalnikPravila.namizje(800, 1232, Vnos.DOTIK, false))
    preveri("telefon pokonci ni namizje", !ZaganjalnikPravila.namizje(393, 851, Vnos.DOTIK, false))
    preveri("telefon lezece (851 x 393) ni namizje - majhen zaslon", !ZaganjalnikPravila.namizje(851, 393, Vnos.DOTIK, false))
    preveri("zunanji zaslon je vedno namizje", ZaganjalnikPravila.namizje(640, 360, Vnos.DOTIK, true))
    preveri("televizor nikoli namizje", !ZaganjalnikPravila.namizje(1920, 1080, Vnos.DALJINEC, true))

    println("\n== iskanje aplikacij v lupi namizja ==")
    val apps = listOf("Brskalnik" to "com.a", "Kamera" to "com.b", "Koledar" to "com.google.calendar",
        "Google Zemljevidi" to "com.c", "Čebelica" to "si.cebelica", "YouTube" to "com.google.youtube")
    fun q(niz: String) = ZaganjalnikPravila.poisci(apps, niz, { it.first }, { it.second }).map { it.first }
    enako("prazna poizvedba: vse", apps.map { it.first }, q("  "))
    enako("zacetek imena, nato vsebovano", listOf("Kamera", "Koledar", "Brskalnik"), q("k"))
    enako("ime z zacetkom pred besedo v imenu", listOf("Google Zemljevidi"), q("zem"))
    enako("brez stresice najde stresico", listOf("Čebelica"), q("ceb"))
    enako("tudi z veliko zacetnico", listOf("YouTube"), q("YOU"))
    enako("paket kot zadnja moznost", listOf("Koledar"), q("calendar"))
    enako("zacetek imena pred vsebovanim (brsKAlnik)", listOf("Kamera", "Brskalnik"), q("ka"))
    enako("nic ne ustreza", emptyList<String>(), q("xyz"))
    enako("ime pred paketom, znotraj skupine abeceda", listOf("Google Zemljevidi", "Koledar", "YouTube"), q("goo"))

    println("\n== okna aplikacij na namizju ==")
    val o0 = ZaganjalnikPravila.oknoAplikacije(1920, 1200, 0)
    val o1 = ZaganjalnikPravila.oknoAplikacije(1920, 1200, 1)
    preveri("okno je v celoti na zaslonu", o0[0] >= 0 && o0[1] >= 0 && o0[2] <= 1920 && o0[3] <= 1200)
    enako("sirina okna 62 %", (1920 * 0.62f).toInt(), o0[2] - o0[0])
    enako("naslednje okno zamaknjeno (kaskada)", 48, o1[0] - o0[0])
    enako("po sestih spet od zacetka", o0.toList(), ZaganjalnikPravila.oknoAplikacije(1920, 1200, 6).toList())
    val majhen = ZaganjalnikPravila.oknoAplikacije(300, 200, 5)
    preveri("tudi na majhnem zaslonu ostane na zaslonu", majhen[0] >= 0 && majhen[2] <= 300 && majhen[3] <= 200)
    preveri("negativno zaporedje ne pade", ZaganjalnikPravila.oknoAplikacije(1000, 800, -1)[0] >= 0)

    println("\n== aplikacije za Safeer Link: najprej pripete ==")
    val vse = listOf("a.brskalnik", "b.kamera", "c.koledar", "d.youtube")
    enako("pripete najprej, v uporabnikovem redu", listOf("d.youtube", "b.kamera", "a.brskalnik", "c.koledar"),
        ZaganjalnikPravila.zaLink(vse, listOf("d.youtube", "b.kamera")))
    enako("odstranjena pripeta izpade, dvojnik tudi", listOf("b.kamera", "a.brskalnik", "c.koledar", "d.youtube"),
        ZaganjalnikPravila.zaLink(vse, listOf("x.ni", "b.kamera", "b.kamera")))
    enako("brez pripetih ostane red", vse, ZaganjalnikPravila.zaLink(vse, emptyList()))
}

fun main() {
    println("Zaganjalnik Safeer OS: pravila")
    postavitve(); padci(); znacke(); vsebina(); varnostnaVrata(); namizje()
    println(if (napak == 0) "\nVSE OK" else "\nNAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
