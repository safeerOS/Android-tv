package si.safeer.tv.cast

/**
 * Kam po koncu deljenega zaslona (cast/PoDeljenju). Izmerjeno 5. 10. 2026 na telefonu: Domov Safeer OS je v isti
 * nalogi kot brskalnik, zato je po koncu deljenja uporabnik pristal na domacem zaslonu naprave.
 */
fun main() {
    // Telefon ali tablica, uporabnik je bil v Safeer OS, brskalnik je nastal za ta zaslon: zapremo ga.
    check(PoDeljenju.zapriBrskalnik(nastalZaTaZaslon = true, safeerNaZaslonu = true, samVNalogi = false))
    // Brskalnik je obstajal ze prej (uporabnikovi zavihki): ne zapiramo.
    check(!PoDeljenju.zapriBrskalnik(nastalZaTaZaslon = false, safeerNaZaslonu = true, samVNalogi = false))
    // Uporabnik je bil v drugi aplikaciji in je odprl obvestilo: naloga gre v ozadje, da se vrne tja.
    check(!PoDeljenju.zapriBrskalnik(nastalZaTaZaslon = true, safeerNaZaslonu = false, samVNalogi = false))
    // Televizor: brskalnik je v nalogi sam, Safeer OS je svoja naloga - kot doslej.
    check(!PoDeljenju.zapriBrskalnik(nastalZaTaZaslon = true, safeerNaZaslonu = true, samVNalogi = true))
    check(!PoDeljenju.zapriBrskalnik(nastalZaTaZaslon = false, safeerNaZaslonu = false, samVNalogi = true))
    println("PoDeljenjuTest: OK")
}
