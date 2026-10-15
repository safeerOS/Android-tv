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

fun main() {
    println("Zaganjalnik Safeer OS: pravila")
    postavitve(); padci(); znacke(); vsebina()
    println(if (napak == 0) "\nVSE OK" else "\nNAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
