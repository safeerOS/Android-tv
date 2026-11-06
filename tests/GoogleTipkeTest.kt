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
    preveri("N1: brez odgovora ali izjema v skriptu: nic (prej pravi Enter)", T.ok(null) == T.Ok.Nic && T.ok("null") == T.Ok.Nic && T.ok("") == T.Ok.Nic)
    preveri("N1: neznan odgovor 'f'/0: nic", T.ok("\"f\"") == T.Ok.Nic && T.ok("0") == T.Ok.Nic)
    preveri("pokvarjen dotik ni dotik", T.ok("dotik:abc,1") == T.Ok.Nic && T.ok("dotik:1") == T.Ok.Nic)
    preveri("NaN in neskoncno nista dotik", T.ok("dotik:NaN,1") == T.Ok.Nic && T.ok("dotik:Infinity,1") == T.Ok.Nic)
    preveri("negativna tocka ni dotik", T.ok("dotik:-5,10") == T.Ok.Nic)

    preveri("GOR s fokusom na elementu strani: ostane v strani (Shift+Tab)", !T.gorVNaslov("\"stran\""))
    preveri("GOR brez fokusa na strani: naslovna vrstica", T.gorVNaslov("\"vrh\""))
    preveri("GOR, ce skript ne odgovori: naslovna vrstica (ne obtici)", T.gorVNaslov(null) && T.gorVNaslov("null"))
    preveri("Nazaj z odprtim tujim oknom: Escape", T.jeTuje("\"tuje\""))
    preveri("Nazaj brez tujega okna ali brez odgovora: zgodovina", !T.jeTuje("\"ne\"") && !T.jeTuje(null) && !T.jeTuje("null"))
    // Cetrti slepi pregled 5. 11. 2026 (N-A): okvir v fokusu ali na tocki - samo namig kazalca.
    preveri("okvir: kazalec (ne Enter, ne dotik)", T.ok("\"kazalec\"") == T.Ok.Kazalec && T.ok("kazalec") == T.Ok.Kazalec)
    preveri("kazalec z dodatki ni kazalec (nic)", T.ok("kazalec:1") == T.Ok.Nic && T.ok("enter:1") == T.Ok.Nic)
    val js = "x=window['__safeer_izvirniki_'+'__SAFEER_NONCE_IZVIRNIKOV__']"
    preveri("izvirniki: veljaven nakljucni del pogleda", T.zIzvirniki(js, "8796a5b4c3d2e1f0") == "x=window['__safeer_izvirniki_'+'8796a5b4c3d2e1f0']")
    preveri("izvirniki: brez registracije ali neveljaven del - ime, ki ga ni (ni vrivanja v skript)",
        T.zIzvirniki(js, null).endsWith("'brez']") && T.zIzvirniki(js, "x'];alert(1);//").endsWith("'brez']") &&
            T.zIzvirniki(js, "ABCDEF0123456789").endsWith("'brez']"))
    val z = "z".repeat(48)
    preveri("N1: ista stran in se Google: dejanje velja", T.istaStran(z, z, seNaGooglu = true))
    preveri("N1: nova stran (drug zeton): nic", !T.istaStran(z, "y".repeat(48), seNaGooglu = true))
    preveri("N1: stran je odsla z Googla: nic", !T.istaStran(z, z, seNaGooglu = false))
    preveri("N1: prekratek zeton: nic", !T.istaStran("z", "z", seNaGooglu = true))
    println("GoogleTipkeTest: OK ($stevilo)")
}
