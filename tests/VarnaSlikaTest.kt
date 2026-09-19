package si.safeer.tv.os

import android.graphics.BitmapFactory

/**
 * VarnaSlika z nadomestkom BitmapFactory (tests/stubs/BitmapFactory.kt): da se ikona z racunalnika
 * najprej izmeri, da se ogromna nikoli ne dekodira v polni velikosti in da navadna ostane ostra.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

/** Glava PNG z danimi merami (za mere zadostuje; vsebine slike BitmapFactory v preizkusu ne bere). */
private fun png(sirina: Int, visina: Int): ByteArray {
    val b = java.io.ByteArrayOutputStream()
    b.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val d = java.io.DataOutputStream(b)
    d.writeInt(13); d.writeBytes("IHDR"); d.writeInt(sirina); d.writeInt(visina)
    d.write(byteArrayOf(8, 6, 0, 0, 0)); d.writeInt(0)
    return b.toByteArray()
}

private fun preizkusBajtov() {
    println("\n== ikona iz odgovora racunalnika (bajti) ==")
    var s = VarnaSlika.izBajtov(png(256, 256))
    preveri("navadna ikona se dekodira", s != null)
    preveriEnako("navadna ikona ostane v polni velikosti", 256, s?.width)
    preveriEnako("brez pomanjsanja", 1, BitmapFactory.zadnjiVzorec)

    s = VarnaSlika.izBajtov(png(1024, 1024))
    preveriEnako("velika ikona se pomanjsa na 512", 512, s?.width)

    s = VarnaSlika.izBajtov(png(8192, 4096))
    preveri("8192 x 4096 se dekodira pomanjsano (najvec 512)", s != null && maxOf(s.width, s.height) <= 512)

    val pred = BitmapFactory.polnihDekodiranj
    preveri("20000 x 20000 vrne nic", VarnaSlika.izBajtov(png(20000, 20000)) == null)
    preveri("100 x 50000 vrne nic", VarnaSlika.izBajtov(png(100, 50000)) == null)
    preveri("pokvarjeni podatki vrnejo nic", VarnaSlika.izBajtov(ByteArray(64) { it.toByte() }) == null)
    preveri("prazno vrne nic", VarnaSlika.izBajtov(ByteArray(0)) == null)
    preveriEnako("nobena od teh ni bila dekodirana v polni velikosti", pred, BitmapFactory.polnihDekodiranj)
}

private fun preizkusDatoteke() {
    println("\n== shranjena ikona (datoteka) ==")
    val f = java.io.File.createTempFile("ikona", ".png")
    try {
        f.writeBytes(png(2048, 1024))
        val s = VarnaSlika.izDatoteke(f.absolutePath)
        preveriEnako("datoteka 2048 x 1024 se pomanjsa na 512 x 256", "512x256", s?.let { "${it.width}x${it.height}" })
        f.writeBytes(png(30000, 30000))
        val pred = BitmapFactory.polnihDekodiranj
        preveri("ogromna datoteka vrne nic", VarnaSlika.izDatoteke(f.absolutePath) == null)
        preveriEnako("in ni dekodirana v polni velikosti", pred, BitmapFactory.polnihDekodiranj)
        preveri("manjkajoca datoteka vrne nic", VarnaSlika.izDatoteke(f.absolutePath + ".ni") == null)
    } finally {
        f.delete()
    }
}

private fun preizkusShranjevanja() {
    println("\n== shranjevanje ikon in okno 'ni vec na voljo' ==")
    preveri("ikona 200 KB se shrani", OsPravila.shraniIkono(200 * 1024))
    preveri("ikona natanko 512 KB se shrani", OsPravila.shraniIkono(512 * 1024))
    preveri("ikona nad 512 KB se ne shrani", !OsPravila.shraniIkono(512 * 1024 + 1))
    preveri("prazna ikona se ne shrani", !OsPravila.shraniIkono(0))
    preveri("kartica v Nadaljuj: okno ponudi odstranitev",
        OsPravila.ponudiOdstranitev("program|pc|a", listOf("zaslon|pc|", "program|pc|a")))
    preveri("pripeta kartica, ki je ni v Nadaljuj: brez gumba Odstrani",
        !OsPravila.ponudiOdstranitev("program|pc|b", listOf("program|pc|a")))
}

fun main() {
    println("Preizkus varnega dekodiranja ikon")
    preizkusBajtov()
    preizkusDatoteke()
    preizkusShranjevanja()
    println()
    if (napak == 0) println("Vse v redu.") else { println("Napak: $napak"); System.exit(1) }
}
