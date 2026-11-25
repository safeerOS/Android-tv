import si.safeer.tv.os.ZaslonGlava
import java.io.ByteArrayInputStream
import java.io.IOException

// Preizkus glave toka zaslona (25. 11. 2026): glava, daljsa od 512 bajtov, se prebere cela in v UTF-8.
var napake = 0
fun preveri(ime: String, pogoj: Boolean) { if (pogoj) println("OK  $ime") else { println("NAPAKA $ime"); napake++ } }

fun main() {
    val opis = "LAN · 100 Mb/s · neposredno · 1920×1080 · 60 fps"
    val dolga = "{\"v\": 2, \"profil\": {\"opis\": \"$opis\", \"polnilo\": \"" + "x".repeat(700) + "\"}, \"kazalec\": true}"
    val vhod = ByteArrayInputStream((dolga + "\nSLIKA").toByteArray(Charsets.UTF_8))
    val prebrano = ZaslonGlava.preberi(vhod)
    preveri("glava nad 512 bajtov prebrana cela", prebrano == dolga)
    preveri("opis v UTF-8 (×, ·)", prebrano.contains(opis))
    preveri("ostanek toka ostane za sliko", String(vhod.readBytes()) == "SLIKA")
    val brezKonca = ByteArrayInputStream("a".repeat(100).toByteArray())
    var vrglo = false
    try { ZaslonGlava.preberi(brezKonca, 50) } catch (_: IOException) { vrglo = true }
    preveri("vrstica brez konca nad mejo -> napaka", vrglo)
    preveri("konec toka brez \\n vrne prebrano", ZaslonGlava.preberi(ByteArrayInputStream("abc".toByteArray())) == "abc")
    if (napake > 0) { println("ZaslonGlavaTest: PADLO $napake"); kotlin.system.exitProcess(1) }
    println("ZaslonGlavaTest: OK")
}
