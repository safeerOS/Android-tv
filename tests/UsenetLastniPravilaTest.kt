package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis (pricakovano=$pricakovano, dobljeno=$dobljeno)") }
}

/** Lastnik 27. 10. 2026: filmi z Useneta prek lastnega ponudnika v Medijskem centru (most UsenetLastni). */
fun main() {
    val L = UsenetLastniPravila
    val P = UsenetPonudnikPravila
    println("== pot toka ==")
    preveriEnako("ponudnik vpisan: lasten pogon (tudi brez izbire)", true, L.lastni(null, true))
    preveriEnako("brez ponudnika: TorBox kot prej", false, L.lastni(null, false))
    preveriEnako("izrecno TorBox (drugi korak): ne lasten", false, L.lastni(false, true))
    preveriEnako("izrecno lasten", true, L.lastni(true, false))
    println("== Usenet v Medijskem centru ==")
    preveriEnako("indeksator + lasten ponudnik brez TorBoxa: da", true, L.naVoljo(true, torBox = false, lastni = true))
    preveriEnako("indeksator + TorBox: da (kot prej)", true, L.naVoljo(true, torBox = true, lastni = false))
    preveriEnako("brez indeksatorja: ne (NZB ni od kod)", false, L.naVoljo(false, torBox = true, lastni = true))
    preveriEnako("indeksator brez poti predvajanja: ne", false, L.naVoljo(true, torBox = false, lastni = false))
    println("== Preveri v oknu (odjemalec Grok -> pravila okna) ==")
    preveriEnako("prijava uspela", P.NNTP_OK, L.prijavaIz(null).izid)
    preveriEnako("zavrnjeno geslo je v fazi prijave", P.FAZA_PRIJAVA, L.prijavaIz(UsenetNntp.Vrsta.PRIJAVA).faza)
    preveriEnako("zavrnjeno geslo + zapah = ne velja", P.NE_VELJA,
        P.izidNapake(L.prijavaIz(UsenetNntp.Vrsta.PRIJAVA).izid, L.prijavaIz(UsenetNntp.Vrsta.PRIJAVA).faza, true))
    preveriEnako("meja povezav = prevec povezav", P.PREVEC_POVEZAV,
        P.izidNapake(L.prijavaIz(UsenetNntp.Vrsta.ZASEDEN).izid, L.prijavaIz(UsenetNntp.Vrsta.ZASEDEN).faza, false))
    preveriEnako("omrezje brez zapaha = ni odgovora", P.NI_ODGOVORA,
        P.izidNapake(L.prijavaIz(UsenetNntp.Vrsta.OMREZJE).izid, L.prijavaIz(UsenetNntp.Vrsta.OMREZJE).faza, false))
    for (v in UsenetNntp.Vrsta.values()) preveriEnako("vsaka napaka ima izid ($v)", true, L.prijavaIz(v).izid.isNotBlank())
    println(if (napak == 0) "UsenetLastniPravilaTest: OK" else "UsenetLastniPravilaTest: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
