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

fun main(args: Array<String>) {
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

    // TV 4. 11. 2026 (Philips brez DocumentsUI): OPEN_DOCUMENT -> GET_CONTENT -> lasten seznam -> okno »ni izbirnika«.
    val I = IzbiraDatotek.Izbirnik.values().associateBy { it.name }
    preveri(IzbiraDatotek.naslednji(null), I["OPEN_DOCUMENT"], "najprej sistemski SAF")
    preveri(IzbiraDatotek.naslednji(I["OPEN_DOCUMENT"]), I["GET_CONTENT"], "brez SAF: upravitelj datotek")
    preveri(IzbiraDatotek.naslednji(I["GET_CONTENT"]), I["LASTEN"], "brez obeh: lasten izbirnik")
    preveri(IzbiraDatotek.naslednji(I["LASTEN"]), I["SPOROCILO"], "nazadnje sporocilo")
    preveri(IzbiraDatotek.naslednji(I["SPOROCILO"]), I["SPOROCILO"], "sporocilo ostane")
    preveri(IzbiraDatotek.poSeznamu(3), I["LASTEN"], "lasten seznam z datotekami")
    preveri(IzbiraDatotek.poSeznamu(0), I["SPOROCILO"], "prazen seznam: okno, da izbirnika ni")
    preveri(IzbiraDatotek.ustreza("image/png", listOf("image/*")), true, "image/* sprejme png")
    preveri(IzbiraDatotek.ustreza("IMAGE/PNG", listOf("image/png")), true, "tocen tip, velike crke")
    preveri(IzbiraDatotek.ustreza("application/pdf", listOf("image/*", "video/*")), false, "pdf ni slika")
    preveri(IzbiraDatotek.ustreza("imagex/png", listOf("image/*")), false, "imagex ni image")
    preveri(IzbiraDatotek.ustreza(null, listOf("image/*")), false, "brez tipa ne ustreza tocnim tipom")
    preveri(IzbiraDatotek.ustreza(null, listOf("*/*")), true, "*/* sprejme vse")
    preveri(IzbiraDatotek.skritaMapa("Android/data/com.x/"), true, "Android/data skrita")
    preveri(IzbiraDatotek.skritaMapa("/android/obb/"), true, "Android/obb skrita")
    preveri(IzbiraDatotek.skritaMapa("Download/"), false, "Download vidna")

    // TV 4. 11. 2026 20:06 (Philips): OPEN_DOCUMENT je imel samo Googlov nadomestek, ki se takoj zapre.
    preveri(IzbiraDatotek.jeNadomestek("com.google.android.tv.frameworkpackagestubs"), true, "Googlov TV nadomestek")
    preveri(IzbiraDatotek.jeNadomestek("com.android.frameworkpackagestubs"), true, "drug nadomestek *.frameworkpackagestubs")
    preveri(IzbiraDatotek.jeNadomestek("com.android.documentsui"), false, "DocumentsUI je pravi izbirnik")
    preveri(IzbiraDatotek.jeNadomestek("com.google.android.documentsui"), false, "Googlov DocumentsUI je pravi")
    preveri(IzbiraDatotek.jeNadomestek("com.x.frameworkpackagestubsplus"), false, "samo konec imena")
    preveri(IzbiraDatotek.jeNadomestek(null), false, "brez paketa ni nadomestek")
    preveri(IzbiraDatotek.pravi(listOf("com.google.android.tv.frameworkpackagestubs")), emptyList<Int>(), "samo nadomestek: preskoci")
    preveri(IzbiraDatotek.pravi(listOf("com.google.android.tv.frameworkpackagestubs", "com.ghisler.android.TotalCommander")), listOf(1), "nadomestek in upravitelj: upravitelj")
    preveri(IzbiraDatotek.pravi(listOf(null, "")), emptyList<Int>(), "prazni paketi niso izbirnik")
    preveri(IzbiraDatotek.poHitremPreklicu(IzbiraDatotek.Izbirnik.OPEN_DOCUMENT, false, 120), IzbiraDatotek.Izbirnik.GET_CONTENT, "zaprl se je sam: GET_CONTENT")
    preveri(IzbiraDatotek.poHitremPreklicu(IzbiraDatotek.Izbirnik.GET_CONTENT, false, 300), IzbiraDatotek.Izbirnik.LASTEN, "GET_CONTENT se je zaprl sam: lasten seznam")
    preveri(IzbiraDatotek.poHitremPreklicu(IzbiraDatotek.Izbirnik.OPEN_DOCUMENT, false, 4000), null, "uporabnik je preklical: velja")
    preveri(IzbiraDatotek.poHitremPreklicu(IzbiraDatotek.Izbirnik.OPEN_DOCUMENT, true, 120), null, "izbira velja tudi hitro")
    preveri(IzbiraDatotek.poHitremPreklicu(IzbiraDatotek.Izbirnik.OPEN_DOCUMENT, false, -5), null, "neveljaven cas: velja")
    preveri(IzbiraDatotek.poHitremPreklicu(null, false, 100), null, "brez zagnanega izbirnika: velja")
    // Obe aktivnosti preverita odgovor izbirnika (nadomestek, ki ga nismo prepoznali, ni uporabnikov preklic).
    for (pot in listOf("si/safeer/tv/MainActivity.kt", "si/safeer/tv/os/SpletniKlepetActivity.kt")) {
        val k = java.io.File(args.getOrNull(0) ?: error("pot do src/main/kotlin"), pot).readText()
        preveri(k.contains("IzbirnikDatotekTv.poOdgovoru("), true, "$pot: hiter samodejni preklic gre na naslednji izbirnik")
    }

    // Staticno: brskalnik in spletni klepeti ne vrnejo vec false, ko SAF ni (prej tihi propad), ampak gredo po vrsti.
    val src = java.io.File(args.getOrNull(0) ?: error("pot do src/main/kotlin"))
    for (pot in listOf("si/safeer/tv/MainActivity.kt", "si/safeer/tv/os/SpletniKlepetActivity.kt")) {
        val k = java.io.File(src, pot).readText()
        preveri(k.contains("IzbirnikDatotekTv.zazeniSistemski(") && k.contains("IzbirnikDatotekTv.pokazi("), true, "$pot: sistemski, nato lasten izbirnik")
        preveri(k.contains("Intent.ACTION_OPEN_DOCUMENT)"), false, "$pot: brez lastne namere OPEN_DOCUMENT mimo vrstnega reda")
    }

    if (padli > 0) {
        println("IzbiraDatotekTest: $padli od $presteti padlo")
        kotlin.system.exitProcess(1)
    }
    println("IzbiraDatotekTest: vseh $presteti uspesnih")
}
