package si.safeer.tv

/** Izbira datotek za <input type=file> (TV brskalnik in spletni klepeti): accept -> MIME, samo URI-ji izbirnika (4. 11. 2026). */

private var padli = 0
private var presteti = 0

private fun preveri(dobljeno: Any?, pricakovano: Any?, opis: String) {
    presteti++
    if (dobljeno != pricakovano) {
        padli++
        println("FAIL $opis: $dobljeno (pricakovano $pricakovano)")
    }
}

private val koncnice = mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg", "png" to "image/png")
private fun tipi(vararg a: String) = IzbiraDatotek.mimeTipi(arrayOf(*a)) { koncnice[it] }

fun main() {
    preveri(IzbiraDatotek.mimeTipi(null) { null }, listOf("*/*"), "brez accept")
    preveri(tipi(""), listOf("*/*"), "prazen niz")
    preveri(tipi("image/*"), listOf("image/*"), "image/*")
    preveri(tipi(".PDF"), listOf("application/pdf"), ".PDF")
    preveri(tipi("image/png,.jpg", ".png"), listOf("image/png", "image/jpeg"), "vec, brez podvajanja")
    preveri(tipi(".heic"), listOf("*/*"), "neznana koncnica: vse")
    preveri(tipi("image/*", ".heic"), listOf("*/*"), "delno neznano: vse")
    preveri(tipi("javascript:alert(1)"), listOf("*/*"), "neveljaven vnos strani")

    val p = IzbiraDatotek.LASTNA_DRUZINA
    val doc = "content://com.android.providers.media.documents/document/image%3A12"
    val doc2 = "content://com.android.externalstorage.documents/document/primary%3Aa.pdf"
    preveri(IzbiraDatotek.sprejmi(listOf(doc), p, false), listOf(doc), "content iz izbirnika")
    for (u in listOf("file:///data/data/si.safeer.tv/shared_prefs/x.xml", "FILE:///sdcard/a.pdf",
        "content://si.safeer.tv.fileprovider/x", "content://si.safeer.os.spletne/1", "content://SI.SAFEER.TV.FILEPROVIDER/x",
        "content://evil@si.safeer.tv.fileprovider/x", "content://si.safeer.tv.fileprovider:1/x", "content://si%2Esafeer.tv/x",
        "content://si.safeer", "content:///x", "https://example.org/a.pdf", "javascript:x", "")) {
        preveri(IzbiraDatotek.sprejmi(listOf(u), p, true), emptyList<String>(), "zavrnjeno: $u")
    }
    preveri(IzbiraDatotek.sprejmi(listOf("content://si.safeerx.ponudnik/x"), p, false), listOf("content://si.safeerx.ponudnik/x"), "podobno ime ni nase")
    preveri(IzbiraDatotek.sprejmi(listOf(doc, doc2, doc, null), p, true), listOf(doc, doc2), "vec datotek brez podvajanja")
    preveri(IzbiraDatotek.sprejmi(listOf(doc, doc2), p, false), listOf(doc), "en sam, ce stran ne dovoli vec")
    preveri(IzbiraDatotek.sprejmi(emptyList(), p, true), emptyList<String>(), "preklic")

    // Med odprtim izbirnikom zavihek ne zaspi (sicer se izbrana datoteka nima kam vrniti).
    preveri(SpanjePravila.zaspi(predvaja = false, umazanObrazec = false, odlozenoMs = 0L, naDotik = false, cakaIzbiro = true), false, "izbirnik: ne zaspi")
    preveri(SpanjePravila.zaspi(predvaja = false, umazanObrazec = false, odlozenoMs = 0L, naDotik = false, cakaIzbiro = false), true, "brez izbirnika zaspi")

    if (padli > 0) {
        println("IzbiraDatotekTest: $padli od $presteti padlo")
        kotlin.system.exitProcess(1)
    }
    println("IzbiraDatotekTest: vseh $presteti uspesnih")
}
