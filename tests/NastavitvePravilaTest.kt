// Pregled Nastavitev in Scita (5. 11. 2026): meje branja, preverba vsakega skoka preusmeritve, fokus seznama, predpomnilnik
// diagnostike, OK na Safeer Vnosu. Staticne preverbe izvorne kode padejo na kodi pred popravkom.
//   tests/run_os_tests.sh (argument: pot do src/main/kotlin)
package si.safeer.tv.os

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.system.exitProcess

private var padloNast = 0

private fun preveriNast(pogoj: Boolean, opis: String) {
    if (pogoj) println("OK   $opis") else { padloNast++; println("PADE $opis") }
}

/** Tok brez konca (zlonamerni streznik ali naprava): beriZMejo se mora ustaviti. */
private class Neskoncen : InputStream() {
    var prebrano = 0L
    override fun read(): Int { prebrano++; return 0 }
    override fun read(b: ByteArray, off: Int, len: Int): Int { prebrano += len; return len }
}

fun main(args: Array<String>) {
    val P = NastavitvePravila

    // 5 in 16: branje z mejo, steto sproti.
    preveriNast(P.beriZMejo(ByteArrayInputStream(ByteArray(1000)), 1000)?.size == 1000, "natanko meja: prebrano")
    preveriNast(P.beriZMejo(ByteArrayInputStream(ByteArray(1001)), 1000) == null, "meja + 1: nic")
    val n = Neskoncen()
    preveriNast(P.beriZMejo(n, 256 * 1024) == null && n.prebrano < 256 * 1024 + 128 * 1024, "neskoncen tok: ustavi se pri meji")
    preveriNast(P.beriZMejo(ByteArrayInputStream(ByteArray(0)), 10)?.size == 0, "prazen tok")
    preveriNast(P.MEJA_SLIKE_OZADJA == 20L * 1024 * 1024 && P.MEJA_MANIFESTA == 256L * 1024, "meji 20 MB in 256 kB")

    // 16: vsak skok preusmeritve.
    preveriNast(P.naslednjiSkok("https://safeer.si/os/a.apk", "/os/b.apk") == "https://safeer.si/os/b.apk", "relativna preusmeritev ostane https")
    preveriNast(P.naslednjiSkok("https://safeer.si/os/a.apk", "https://objects.example/x.apk") == "https://objects.example/x.apk", "https drugam: dovoljeno (vsota SHA-256 preveri vsebino)")
    preveriNast(P.naslednjiSkok("https://safeer.si/os/a.apk", "http://safeer.si/os/a.apk") == null, "https -> http: zavrnjeno")
    preveriNast(P.naslednjiSkok("https://safeer.si/os/a.apk", "//napad.example/a.apk") == "https://napad.example/a.apk", "brez sheme podeduje https")
    preveriNast(P.naslednjiSkok("https://safeer.si/a", "file:///data/x") == null, "file: zavrnjeno")
    preveriNast(P.naslednjiSkok("https://safeer.si/a", "https://uporabnik:geslo@x.example/a") == null, "uporabnik v naslovu: zavrnjeno")
    preveriNast(P.naslednjiSkok("https://safeer.si/a", null) == null && P.naslednjiSkok("https://safeer.si/a", " ") == null, "brez Location: konec")
    preveriNast(P.naslednjiSkok("https://safeer.si/a", "https://") == null, "brez gostitelja: zavrnjeno")
    preveriNast(listOf(301, 302, 303, 307, 308).all { P.jePreusmeritev(it) } && !P.jePreusmeritev(200) && !P.jePreusmeritev(304), "kode preusmeritev")

    // 11: fokus seznama Nastavitev.
    preveriNast(P.seznamVzameFokus(prviIzris = true, kdoImaFokus = true, iskanjeImaFokus = false), "prvi izris: seznam dobi fokus")
    preveriNast(!P.seznamVzameFokus(prviIzris = false, kdoImaFokus = true, iskanjeImaFokus = false), "poznejsi izris: fokus ostane, kjer je (stranska vrstica)")
    preveriNast(P.seznamVzameFokus(prviIzris = false, kdoImaFokus = false, iskanjeImaFokus = false), "nihce nima fokusa: seznam")
    preveriNast(!P.seznamVzameFokus(prviIzris = true, kdoImaFokus = true, iskanjeImaFokus = true), "iskanje ima fokus: nikoli")

    // 8: predpomnilnik diagnostike (60 s).
    val pp = NastavitvePravila.PredpomnjenaLastnost(60_000)
    preveriNast(pp.preberi("debug.safeer.x", 1_000) == (null to true), "prvic: ni vrednosti, beri v ozadju")
    preveriNast(pp.preberi("debug.safeer.x", 1_001) == (null to false), "branje ze tece: ne se enkrat")
    pp.shrani("debug.safeer.x", "123", 1_010)
    preveriNast(pp.preberi("debug.safeer.x", 30_000) == ("123" to false), "v roku: zadnja vrednost brez procesa")
    preveriNast(pp.preberi("debug.safeer.x", 61_010) == ("123" to true), "po 60 s: stara vrednost in novo branje")
    preveriNast(pp.preberi("debug.safeer.y", 61_010) == (null to true), "druga lastnost posebej")

    // 2: OK na vrstici Safeer Vnos.
    val D = DovoljenjaPravila
    preveriNast(D.dejanjeVnosa(DovoljenjaPravila.Stanje.DA, smePisati = true) == DovoljenjaPravila.DejanjeVnosa.ODPRI_STRAN,
        "Vnos vklopljen, z WRITE_SECURE_SETTINGS: OK samo odpre stran (prej tih izklop)")
    preveriNast(D.dejanjeVnosa(DovoljenjaPravila.Stanje.NE, smePisati = true) == DovoljenjaPravila.DejanjeVnosa.VKLOPI_PREK_VNOSVKLOP,
        "Vnos izklopljen, z dovoljenjem: vklop prek VnosVklop")
    preveriNast(D.dejanjeVnosa(DovoljenjaPravila.Stanje.NE, smePisati = false) == DovoljenjaPravila.DejanjeVnosa.ODPRI_STRAN, "brez dovoljenja: stran")

    // Staticne preverbe izvorne kode (padejo pred popravkom 5. 11. 2026).
    val src = File(args.getOrNull(0) ?: error("pot do src/main/kotlin"))
    fun beri(pot: String) = File(src, pot).readText()
    val dov = beri("si/safeer/tv/os/DovoljenjaActivity.kt")
    preveriNast("Settings.Secure.putString" !in dov && "Settings.Secure.putInt" !in dov, "DovoljenjaActivity ne pise v Settings.Secure (samo VnosVklop)")
    val pripeti = beri("si/safeer/tv/os/PripetiVir.kt").substringAfter("fun odjemalecZaStreznik(").substringBefore(".build()")
    preveriNast(".followRedirects(false)" in pripeti && ".followSslRedirects(false)" in pripeti, "PripetiVir: zeton ne sledi preusmeritvam")
    val pos = beri("si/safeer/tv/os/Posodobitve.kt")
    preveriNast("instanceFollowRedirects = true" !in pos && "NastavitvePravila.naslednjiSkok" in pos, "Posodobitve: vsak skok preverjen")
    preveriNast("readText()" !in pos.substringAfter("fun preberiManifest(").substringBefore("fun odpriPrenos("), "Posodobitve: manifest z mejo")
    preveriNast("URL(Posodobitve.MANIFEST)" !in beri("si/safeer/tv/os/KompletNamestitev.kt"), "KompletNamestitev: ista pot do manifesta")
    val videz = beri("si/safeer/tv/os/VidezActivity.kt")
    preveriNast("readBytes()" !in videz && ".bytes()" !in videz, "VidezActivity: slika ozadja z mejo")
    val diag = beri("si/safeer/tv/os/Diagnostika.kt")
    preveriNast("::getprop)" !in diag, "Diagnostika: getprop ne na glavni niti")
    val os = beri("si/safeer/tv/os/OsActivity.kt")
    preveriNast("Tema.izbrana(this).id != temaOb" in os, "OsActivity: nova tema velja tudi za zaslone za Videzom")
    val nast = beri("si/safeer/tv/os/NastavitveActivity.kt")
    preveriNast("seznam.selectedItemPosition < 0 && !iskanje.hasFocus()" !in nast, "Nastavitve: asinhroni izris fokusa ne jemlje")
    preveriNast("Scit.stanje(this) { narisi() }" !in nast, "Nastavitve: povratni klic Scita preveri isFinishing")
    preveriNast(".setNegativeButton(android.R.string.cancel, null).show()" !in nast && "setPadding(40, 30, 40, 30)" !in nast, "Nastavitve: okna z Os_Okno in Kontroler, odmiki v dp")
    preveriNast("android:listSelector=\"@android:color/transparent\"" in File(src, "../../../res/layout/os_activity_nastavitve.xml").readText(), "Nastavitve: en okvir izbora")

    if (padloNast > 0) { println("NastavitvePravila: $padloNast padlo"); exitProcess(1) }
    println("NastavitvePravila: vse v redu")
}
