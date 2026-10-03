package si.safeer.tv.os

private fun preveri(pogoj: Boolean, kaj: String) { if (!pogoj) throw AssertionError(kaj) }

fun main() {
    val p = ZasebniDodatki
    // Med naprave gre samo, kar ni dodatek, ali dodatek, za katerega vemo, da ni zaseben.
    preveri(p.smeMedNaprave(false, null), "spletni vir")
    preveri(p.smeMedNaprave(true, false), "obicajen dodatek")
    preveri(!p.smeMedNaprave(true, true), "zaseben dodatek")
    preveri(!p.smeMedNaprave(true, null), "neznan dodatek pocaka")

    // Odstranimo samo prevzetega (cas 1); dodanega tukaj (0 ali prava ura) pustimo.
    preveri(p.prevzetOdstranimo(true, 1L), "prevzet zaseben")
    preveri(!p.prevzetOdstranimo(true, 0L), "dodan pred usklajevanjem")
    preveri(!p.prevzetOdstranimo(true, 1_790_000_000_000L), "dodan tukaj")
    preveri(!p.prevzetOdstranimo(false, 1L), "obicajen prevzet")
    preveri(!p.prevzetOdstranimo(null, 1L), "neznan")
    println("ZasebniDodatkiTest: OK")
}
