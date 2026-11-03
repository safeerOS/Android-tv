package si.safeer.tv

/**
 * Pomnilnik brskalnika po odhodu z zaslona (SpanjePravila). Izmerjeno na TV 3. 11. 2026: po Domov ali izhodu je
 * brskalnik drzal 270 MB + 65 MB izrisovalnika; aktivni zavihek je zaspal sele po 10 min, izrisovalnik nikoli.
 */
fun main() {
    // Televizor: aktivni zavihek zaspi v pol minute (prej 10 min), ob pomanjkanju pomnilnika skoraj takoj.
    check(SpanjePravila.zamikAktivnega(naDotik = false, maloRama = false, zvokVOzadju = false) == 30_000L)
    check(SpanjePravila.zamikAktivnega(naDotik = false, maloRama = true, zvokVOzadju = false) == 5_000L)
    // Tablica in telefon: kot doslej.
    check(SpanjePravila.zamikAktivnega(naDotik = true, maloRama = false, zvokVOzadju = false) == 10 * 60_000L)
    check(SpanjePravila.zamikAktivnega(naDotik = true, maloRama = true, zvokVOzadju = false) == 20_000L)
    // Vir iz Medijev z zvokom v ozadju: ne zaspi (glasba igra naprej).
    check(SpanjePravila.zamikAktivnega(naDotik = false, maloRama = true, zvokVOzadju = true) == null)

    // Zavihek, ki predvaja, ne zaspi nikoli.
    check(!SpanjePravila.zaspi(predvaja = true, umazanObrazec = false, odlozenoMs = Long.MAX_VALUE, naDotik = false))
    // Navaden zavihek zaspi takoj.
    check(SpanjePravila.zaspi(predvaja = false, umazanObrazec = false, odlozenoMs = 0L, naDotik = false))
    // Neoddan obrazec odlozi spanje, a ne v nedogled (prej: iskalno polje je drzalo stran budno ves cas).
    check(!SpanjePravila.zaspi(predvaja = false, umazanObrazec = true, odlozenoMs = 2 * 60_000L, naDotik = false))
    check(SpanjePravila.zaspi(predvaja = false, umazanObrazec = true, odlozenoMs = 10 * 60_000L, naDotik = false))
    check(!SpanjePravila.zaspi(predvaja = false, umazanObrazec = true, odlozenoMs = 10 * 60_000L, naDotik = true))
    check(SpanjePravila.zaspi(predvaja = false, umazanObrazec = true, odlozenoMs = 30 * 60_000L, naDotik = true))

    // Izrisovalnik: samo brez zaslona in brez zivega zavihka (prej ga ni koncal nihce do konca procesa).
    check(SpanjePravila.koncajIzrisovalnik(naZaslonu = false, zivihZavihkov = 0))
    check(!SpanjePravila.koncajIzrisovalnik(naZaslonu = false, zivihZavihkov = 1))
    check(!SpanjePravila.koncajIzrisovalnik(naZaslonu = true, zivihZavihkov = 0))
    println("SpanjePravilaTest: OK")
}
