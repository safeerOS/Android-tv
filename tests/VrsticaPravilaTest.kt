package si.safeer.tv.os

import si.safeer.tv.os.VrsticaPravila.Nacin

/**
 * Stranska vrstica in lupa (vsebini ne jemljeta prostora, a sta hitro dosegljivi) ter iskanje po nastavitvah v vseh
 * jezikih aplikacije.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private fun nacini() {
    println("\n== nacin vrstice po sirini okna in vnosu ==")
    enako("telefon pokonci (384 dp): predal", Nacin.PREDAL, VrsticaPravila.nacin(384, daljinec = false))
    enako("razdeljen zaslon tablice (400 dp): predal", Nacin.PREDAL, VrsticaPravila.nacin(400, daljinec = false))
    enako("telefon lezece (777 dp): tirnica", Nacin.TIRNICA, VrsticaPravila.nacin(777, daljinec = false))
    enako("tablica pokonci (800 dp): tirnica", Nacin.TIRNICA, VrsticaPravila.nacin(800, daljinec = false))
    enako("tablica lezece (1280 dp): tirnica", Nacin.TIRNICA, VrsticaPravila.nacin(1280, daljinec = false))
    enako("televizor: daljinec ne glede na sirino", Nacin.DALJINEC, VrsticaPravila.nacin(960, daljinec = true))
    enako("neznana sirina ne skrije vrstice", Nacin.TIRNICA, VrsticaPravila.nacin(0, daljinec = false))
    preveri("predal ne zaseda prostora vsebine", !VrsticaPravila.zasedaProstor(Nacin.PREDAL))
    preveri("tirnica zaseda prostor", VrsticaPravila.zasedaProstor(Nacin.TIRNICA))
}

private fun imena() {
    println("\n== imena ob ikonah ==")
    preveri("odprt predal kaze imena", VrsticaPravila.zImeni(Nacin.PREDAL, true, false, false))
    preveri("tirnica privzeto samo ikone", !VrsticaPravila.zImeni(Nacin.TIRNICA, false, false, false))
    preveri("tirnica z izbranimi imeni", VrsticaPravila.zImeni(Nacin.TIRNICA, false, true, false))
    preveri("TV: imena samo s fokusom v meniju", VrsticaPravila.zImeni(Nacin.DALJINEC, false, true, true) &&
        !VrsticaPravila.zImeni(Nacin.DALJINEC, false, true, false))
    enako("predal na telefonu 384 dp: 280 dp", 280, VrsticaPravila.sirinaPredalaDp(384))
    enako("predal na ozkem oknu 300 dp: 85 %", 255, VrsticaPravila.sirinaPredalaDp(300))
    preveri("predal nikoli sirsi od okna", VrsticaPravila.sirinaPredalaDp(180) <= 180)
}

private fun potegi() {
    println("\n== potegi ==")
    preveri("z roba v desno odpre", VrsticaPravila.potegOdpre(10f, 80f, 10f))
    preveri("sredi zaslona ne odpre (drsenje vsebine)", !VrsticaPravila.potegOdpre(120f, 80f, 10f))
    preveri("navpicen poteg ob robu ne odpre", !VrsticaPravila.potegOdpre(10f, 50f, 60f))
    preveri("kratek premik ne odpre", !VrsticaPravila.potegOdpre(10f, 20f, 0f))
    preveri("v levo zapre", VrsticaPravila.potegZapre(-70f, 5f))
    preveri("v desno ne zapre", !VrsticaPravila.potegZapre(70f, 5f))
}

private fun lupa() {
    println("\n== iskalna lupa ==")
    preveri("prazna lupa brez fokusa je kompaktna", !VrsticaPravila.lupaRazsirjena(false, "", false))
    preveri("fokus razsiri", VrsticaPravila.lupaRazsirjena(true, "", false))
    preveri("besedilo ostane razsirjeno", VrsticaPravila.lupaRazsirjena(false, "Sintel na TV", false))
    preveri("samo presledki ne drzijo lupe odprte", !VrsticaPravila.lupaRazsirjena(false, "   ", false))
    preveri("izbira »vedno odprta«", VrsticaPravila.lupaRazsirjena(false, "", true))
    preveri("crka zacne iskanje", VrsticaPravila.znakZaLupo('s'.code) && VrsticaPravila.znakZaLupo('Š'.code))
    preveri("stevka s tipkovnice zacne iskanje", VrsticaPravila.znakZaLupo('7'.code))
    preveri("stevka z daljinca ne (kanali)", !VrsticaPravila.znakZaLupo('7'.code, polnaTipkovnica = false))
    preveri("crka z daljinca s tipkami zacne", VrsticaPravila.znakZaLupo('a'.code, polnaTipkovnica = false))
    preveri("presledek, Enter in brez znaka ne", !VrsticaPravila.znakZaLupo(' '.code) &&
        !VrsticaPravila.znakZaLupo('\n'.code) && !VrsticaPravila.znakZaLupo(0))
}

private fun iskanje() {
    println("\n== iskanje po nastavitvah ==")
    enako("normalizacija sumnikov in locil", "zvok in vibriranje", VrsticaPravila.normaliziraj("  Zvok in vibriranje!"))
    enako("normalizacija stresic", "ecran et luminosite", VrsticaPravila.normaliziraj("Écran et luminosité"))
    val wifi = listOf("Wi-Fi", "Brezžična omrežja in gesla", "Wi-Fi", "Wireless networks and passwords", "WLAN")
    preveri("»wifi« najde Wi-Fi", VrsticaPravila.ujema("wi fi", wifi))
    preveri("»brezzicna« brez sumnikov najde", VrsticaPravila.ujema("brezzicna", wifi))
    preveri("»WLAN« (nemsko) najde", VrsticaPravila.ujema("wlan", wifi))
    preveri("»zaslon« ne najde Wi-Fi", !VrsticaPravila.ujema("zaslon", wifi))
    val bt = listOf("Bluetooth in povezane naprave", "Bluetooth and connected devices")
    preveri("del besede (3+ znaki) najde", VrsticaPravila.ujema("tooth", bt))
    preveri("vse besede morajo ustrezati", !VrsticaPravila.ujema("bluetooth tiskalnik", bt))
    preveri("besede iz razlicnih jezikov skupaj", VrsticaPravila.ujema("povezane devices", bt))
    preveri("prazna poizvedba pokaze vse", VrsticaPravila.ujema("  ", bt))
    preveri("ena crka sredi besede ne najde", !VrsticaPravila.ujema("o", listOf("Zvok")))
}

private fun nadomestki() {
    println("\n== mrtvi sistemski vnosi ==")
    preveri("Philips IgnoreIntents", VrsticaPravila.jeNadomestek("org.droidtv.settings", "org.droidtv.settings.ignoreintents.IgnoreIntents"))
    preveri("Googlovi nadomestki TV", VrsticaPravila.jeNadomestek("com.google.android.tv.frameworkpackagestubs",
        "com.google.android.tv.frameworkpackagestubs.Stubs\$SettingsStub"))
    preveri("prazna dejavnost TvSettings", VrsticaPravila.jeNadomestek("com.android.tv.settings", "com.android.tv.settings.EmptyStubActivity"))
    preveri("prave nastavitve niso nadomestek", !VrsticaPravila.jeNadomestek("com.android.settings",
        "com.android.settings.Settings\$WifiSettingsActivity"))
    preveri("TvSettings zvok ni nadomestek", !VrsticaPravila.jeNadomestek("com.android.tv.settings",
        "com.android.tv.settings.device.displaysound.DisplaySoundActivity"))
}

/** Naloga 127: tirnico se da na dotik lezece popolnoma skriti in s potegom z roba spet pokazati. */
private fun skritaTirnica() {
    val p = VrsticaPravila
    enako("skrita lezece", p.TIRNICA_SKRITA, p.stanjeTirnice(skrita = true, imena = true, lezece = true))
    enako("skrita velja samo lezece (pokonci ikone)", p.TIRNICA_IKONE, p.stanjeTirnice(skrita = true, imena = false, lezece = false))
    enako("imena", p.TIRNICA_IMENA, p.stanjeTirnice(skrita = false, imena = true, lezece = true))
    enako("proti sredini: skrita -> ikone", p.TIRNICA_IKONE, p.tirnicaPoPotegu(p.TIRNICA_SKRITA, protiSredini = true, lezece = true))
    enako("proti sredini: ikone -> imena", p.TIRNICA_IMENA, p.tirnicaPoPotegu(p.TIRNICA_IKONE, protiSredini = true, lezece = true))
    enako("proti sredini: imena ostanejo", p.TIRNICA_IMENA, p.tirnicaPoPotegu(p.TIRNICA_IMENA, protiSredini = true, lezece = true))
    enako("proti robu: imena -> ikone", p.TIRNICA_IKONE, p.tirnicaPoPotegu(p.TIRNICA_IMENA, protiSredini = false, lezece = true))
    enako("proti robu: ikone -> skrita (lezece)", p.TIRNICA_SKRITA, p.tirnicaPoPotegu(p.TIRNICA_IKONE, protiSredini = false, lezece = true))
    enako("proti robu pokonci: ikone ostanejo", p.TIRNICA_IKONE, p.tirnicaPoPotegu(p.TIRNICA_IKONE, protiSredini = false, lezece = false))
    enako("skrita ostane skrita", p.TIRNICA_SKRITA, p.tirnicaPoPotegu(p.TIRNICA_SKRITA, protiSredini = false, lezece = true))
    enako("skrito pokaze samo poteg z roba", true, p.potegZRobaTirnice(skrita = true, zacetekXDp = 10f, sirinaTirniceDp = 0f))
    enako("skrite ne pokaze poteg sredi zaslona", false, p.potegZRobaTirnice(skrita = true, zacetekXDp = 300f, sirinaTirniceDp = 0f))
    enako("vidna: poteg po tirnici", true, p.potegZRobaTirnice(skrita = false, zacetekXDp = 50f, sirinaTirniceDp = 68f))
    enako("vidna: poteg zunaj tirnice ne", false, p.potegZRobaTirnice(skrita = false, zacetekXDp = 120f, sirinaTirniceDp = 68f))
}

fun main() {
    nacini(); imena(); potegi(); lupa(); iskanje(); nadomestki(); skritaTirnica()
    if (napak > 0) { println("\nVrsticaPravilaTest: $napak napak"); kotlin.system.exitProcess(1) }
    println("\nVrsticaPravilaTest: vse v redu")
}
