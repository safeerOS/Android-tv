package si.safeer.tv

import si.safeer.tv.GoogleTipke as T

/**
 * Lastnik 2. 11. 2026 (TV, prijava na accounts.google.com): OK na »Prikazi geslo« ni naredil nicesar, povezave
 * »Ste pozabili geslo?« ni bilo mogoce sprozit, GOR pa je fokus vedno odnesel v naslovno vrstico.
 */
private var stevilo = 0
private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "PADLO: $opis" }
    stevilo++
    println("  ok  $opis")
}

fun main() {
    preveri("dotik s koordinatami (evaluateJavascript vrne niz v narekovajih)", T.ok("\"dotik:640,412\"") == T.Ok.Dotik(640f, 412f))
    preveri("dotik z decimalkami", T.ok("dotik:10.5,20.25") == T.Ok.Dotik(10.5f, 20.25f))
    preveri("potrditveno polje brez dotika: Presledek", T.ok("\"space\"") == T.Ok.Presledek)
    preveri("povezava: Enter", T.ok("\"enter\"") == T.Ok.Enter)
    preveri("brez odgovora: Enter (kot prej)", T.ok(null) == T.Ok.Enter && T.ok("null") == T.Ok.Enter)
    preveri("stari odgovor 'f'/0: Enter", T.ok("\"f\"") == T.Ok.Enter && T.ok("0") == T.Ok.Enter)
    preveri("pokvarjen dotik ni dotik", T.ok("dotik:abc,1") == T.Ok.Enter && T.ok("dotik:1") == T.Ok.Enter)
    preveri("NaN in neskoncno nista dotik", T.ok("dotik:NaN,1") == T.Ok.Enter && T.ok("dotik:Infinity,1") == T.Ok.Enter)
    preveri("negativna tocka ni dotik", T.ok("dotik:-5,10") == T.Ok.Enter)

    preveri("GOR s fokusom na elementu strani: ostane v strani (Shift+Tab)", !T.gorVNaslov("\"stran\""))
    preveri("GOR brez fokusa na strani: naslovna vrstica", T.gorVNaslov("\"vrh\""))
    preveri("GOR, ce skript ne odgovori: naslovna vrstica (ne obtici)", T.gorVNaslov(null) && T.gorVNaslov("null"))
    preveri("Nazaj z odprtim tujim oknom: Escape", T.jeTuje("\"tuje\""))
    preveri("Nazaj brez tujega okna ali brez odgovora: zgodovina", !T.jeTuje("\"ne\"") && !T.jeTuje(null) && !T.jeTuje("null"))
    println("GoogleTipkeTest: OK ($stevilo)")
}
