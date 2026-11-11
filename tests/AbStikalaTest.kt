package si.safeer.tv

import java.io.File

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    val brez = AbStikala.Stikala()
    preveri("null = vse izklopljeno", AbStikala.razclenji(null) == brez)
    preveri("prazno = vse izklopljeno", AbStikala.razclenji("") == brez && AbStikala.razclenji("  ") == brez)
    preveri("neznano = vse izklopljeno", AbStikala.razclenji("foo=bar,x,=y,ua=windows,css=on,wide=1") == brez)
    preveri("privzeto nima vedenjskega stikala", !brez.karkoli && brez.ua == "" && !brez.wideIzklop && !brez.cssIzklop &&
        !brez.spatialIzklop && !brez.scitIzklop && !brez.animIzklop)
    val s = AbStikala.razclenji(" UA=android , css=off,wide=off,spatial=off,scit=off,anim=off ")
    preveri("vsa stikala", s.ua == "android" && s.cssIzklop && s.wideIzklop && s.spatialIzklop && s.scitIzklop && s.animIzklop && s.karkoli)
    preveri("ua=tv", AbStikala.razclenji("ua=tv").ua == "tv")
    preveri("samo css", AbStikala.razclenji("css=off") == brez.copy(cssIzklop = true))
    preveri("predolg niz se prezre", AbStikala.razclenji("css=off," + "a=b,".repeat(100)) == brez)
    // Varnost: vir je samo sistemska lastnost, nikoli intent, nastavitve ali stran.
    val vir = File(System.getProperty("ab.vir") ?: "src/main/kotlin/si/safeer/tv/AbStikala.kt").readText()
    val izvenKomentarja = vir.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/**") || it.trim().startsWith("//") }.joinToString("\n")
    preveri("vir ne bere intent/nastavitev", listOf("Intent", "SharedPreferences", "getExtra", "WebView", "Settings").none { izvenKomentarja.contains(it) })
    preveri("vir bere samo SystemProperties", izvenKomentarja.contains("android.os.SystemProperties") && izvenKomentarja.contains("debug.safeer.ab"))
    if (napak > 0) { println("AbStikalaTest: $napak napak"); System.exit(1) }
    println("AbStikalaTest: OK")
}
