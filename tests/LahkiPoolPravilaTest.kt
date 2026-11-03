package si.safeer.tv.os

/** Zunanji pregled 3. 11. 2026: v procesu najvec en WebView, ob predvajanju Media3 nobenega ([LahkiPoolPravila]). */
fun main() {
    val P = LahkiPoolPravila
    check(!P.obdrziObNativnem(lahkaStranIgra = false)) { "polni pogon ali nic: pogled poola stran" }
    check(P.obdrziObNativnem(lahkaStranIgra = true)) { "LahkaStran igra v pogledu poola" }
    check(P.smeNajeti(zasedenPriDrugem = false, polniPogonIgra = false))
    check(!P.smeNajeti(zasedenPriDrugem = false, polniPogonIgra = true)) { "med polnim pogonom ne najemamo" }
    check(!P.smeNajeti(zasedenPriDrugem = true, polniPogonIgra = false))
    check(P.koncajIzrisovalnik(zivih = 0, api = 30))
    check(!P.koncajIzrisovalnik(zivih = 1, api = 30))
    check(!P.koncajIzrisovalnik(zivih = 0, api = 28)) { "Android 9 nima WebViewRenderProcess" }
    println("LahkiPoolPravilaTest: OK")
}
