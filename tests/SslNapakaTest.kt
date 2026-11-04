package si.safeer.tv

// Stran napake neveljavnega potrdila v zavihku (TV 4. 11. 2026: prej tihi preklic, na zaslonu prejsnja stran brez obvestila).
//   tests/run_ssl_napaka_tests.sh (argument: pot do src/main/kotlin)

import java.io.File
import kotlin.system.exitProcess

private var padliSsl = 0

private fun preveriSsl(dobljeno: Any?, pricakovano: Any?, opis: String) {
    if (dobljeno == pricakovano) println("OK   $opis") else { padliSsl++; println("PADE $opis: $dobljeno (pricakovano $pricakovano)") }
}

fun main(args: Array<String>) {
    // Konstante android.net.http.SslError: NOTYETVALID 0, EXPIRED 1, IDMISMATCH 2, UNTRUSTED 3, DATE_INVALID 4, INVALID 5.
    preveriSsl((0..5).map { SslNapaka.razlog(it) },
        SslNapaka.Razlog.values().toList(), "razlog iz SslError")
    preveriSsl(SslNapaka.razlog(-1), SslNapaka.Razlog.NEVELJAVNO, "neznana napaka: neveljavno")

    val fup = "https://cgi-lib.berkeley.edu/ex/fup.html"
    preveriSsl(SslNapaka.jeGlavniOkvir(fup, fup, "https://www.google.com/"), true, "glavna zahteva = napaka (vrstica se ni zamenjala)")
    preveriSsl(SslNapaka.jeGlavniOkvir(fup, "", fup), true, "trenutni naslov pogleda = napaka")
    preveriSsl(SslNapaka.jeGlavniOkvir("https://CGI-LIB.berkeley.edu/ex/fup.html#x", fup, null), true, "velike crke gostitelja in sidro")
    preveriSsl(SslNapaka.jeGlavniOkvir("https://a.si", "https://a.si:443/", null), true, "prazna pot in privzeta vrata")
    preveriSsl(SslNapaka.jeGlavniOkvir("https://cdn.tuj.si/slika.png", fup, fup), false, "slika strani ne zamenja strani")
    preveriSsl(SslNapaka.jeGlavniOkvir("https://a.si/?q=1", "https://a.si/?q=2", null), false, "drugo poizvedbo ne steje")
    preveriSsl(SslNapaka.jeGlavniOkvir("https://a.si:8443/", "https://a.si/", null), false, "druga vrata niso ista stran")
    preveriSsl(SslNapaka.jeGlavniOkvir(null, fup, fup), false, "brez naslova napake")
    preveriSsl(SslNapaka.jeGlavniOkvir("http://a.si/", "http://a.si/", null), false, "samo https")

    val stran = SslNapaka.naslovStrani(fup)
    preveriSsl(stran?.startsWith(SslNapaka.STRAN_URL + "?u="), true, "naslov strani napake")
    preveriSsl(SslNapaka.jeStran(stran), true, "je stran napake")
    preveriSsl(SslNapaka.blokiraniNaslov(stran), fup, "blokirani naslov nazaj")
    preveriSsl(SslNapaka.javniNaslov(stran!!), fup, "naslovna vrstica in zavihek: blokirani naslov")
    preveriSsl(SslNapaka.javniNaslov(fup), fup, "drug naslov ostane")
    preveriSsl(SslNapaka.jeStran("safeer://ssl-napakax"), false, "podoben naslov ni stran napake")
    preveriSsl(SslNapaka.blokiraniNaslov(SslNapaka.STRAN_URL + "?u=javascript%3Aalert(1)"), null, "v ?u= samo https")
    preveriSsl(SslNapaka.naslovStrani("javascript:alert(1)"), null, "brez strani za ne-https")

    val b = SslNapaka.Barve("#090D15", "#101821", "#F0F4F3", "#B0BDC4", "#F2B45C", "#29333D", "#57D6AD", "red;}body{x:")
    val zlo = "https://a.si/\"><script>alert(1)</script>"
    val html = SslNapaka.html("Povezava ni varna", "Potrdilo strani a.si ni veljavno", "Razlog", "Nazaj", "a.si", zlo, "sl", b)
    preveriSsl(html.contains("<script>alert(1)"), false, "naslov strani je kodiran (brez vstavljanja HTML)")
    preveriSsl(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), true, "naslov strani je viden kodiran")
    preveriSsl(html.contains("red;}body{x:"), false, "neveljavna barva se ne vstavi")
    preveriSsl(Regex("<button").findAll(html).count(), 1, "en sam gumb (Nazaj), brez »vseeno nadaljuj«")
    preveriSsl(html.contains("location.href='${SslNapaka.NAZAJ_URL}'") && html.contains("autofocus"), true, "gumb Nazaj ima fokus")
    preveriSsl(html.contains("default-src 'none'"), true, "CSP: stran ne nalaga nicesar od drugod")

    // Staticno: ChromiumEngineView ob preklicu pokaze stran in ne doda nobenega novega proceed().
    val src = File(args.getOrNull(0) ?: error("pot do src/main/kotlin"))
    val cev = File(src, "si/safeer/tv/ChromiumEngineView.kt").readText()
    val zac = cev.indexOf("override fun onReceivedSslError(")
    val ssl = if (zac < 0) "" else cev.substring(zac, cev.indexOf("private val dovoljeniSsl", zac).takeIf { it > zac } ?: cev.length)
    preveriSsl(Regex("\\.proceed\\(\\)").findAll(ssl).count(), 3, "proceed samo: Hub s pripetim odtisom, izjema seje, potrditev v domacem omrezju")
    val preklic = ssl.substringAfter("SslOdlocitev.PREKLICI -> {", "").substringBefore("SslOdlocitev.NADALJUJ")
    preveriSsl(preklic.contains("prikaziNapakoPotrdila(view, error"), true, "PREKLICI pokaze stran napake")
    preveriSsl(ssl.substringAfter("isRealBankHost(pageHost)", "").substringBefore("return").contains("prikaziNapakoPotrdila("), true,
        "banka z neveljavnim potrdilom pokaze stran napake")
    preveriSsl(cev.contains("if (SslNapaka.jeStran(urlStr)) return true"), true, "stran ne more odpreti strani napake (ponarejen naslov)")
    preveriSsl(cev.contains("if (isMainFrame) glavnaZahteva = url"), true, "glavna zahteva se belezi")

    if (padliSsl > 0) { println("PADLO: $padliSsl"); exitProcess(1) }
    println("SslNapakaTest: vse OK")
}
