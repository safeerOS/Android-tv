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
    preveri("privzeto nima vedenjskega stikala", !brez.karkoli && !brez.cssSeznamIzklop && !brez.cssYtIzklop && brez.ua == "" && !brez.wideIzklop && !brez.cssIzklop &&
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
    // 12. 11. 2026: stikali za A/B kozmetike 24ur.com (privzeto vedenje nespremenjeno).
    val sp = listOf(".ad-slot", ".ytp-ad-text", "ytm-open-app-button", "[class*='google-ad']", ".ad-slot")
    val sz = listOf("div.na-24ur", ".ad-slot", "#oglas24")
    preveri("cssseznam in cssyt razclenjena", AbStikala.razclenji("cssseznam=off,cssyt=off") == brez.copy(cssSeznamIzklop = true, cssYtIzklop = true) &&
        AbStikala.razclenji("cssseznam=off").karkoli && AbStikala.razclenji("cssyt=off").karkoli)
    preveri("prevod=enonitni razclenjen, drugo se prezre", AbStikala.razclenji("prevod=enonitni") == brez.copy(prevodEnonitni = true) &&
        AbStikala.razclenji("prevod=dvonitni,prevod=1") == brez && !brez.prevodEnonitni)
    preveri("ThreatFeedsUpdater nastavi nacin prevoda iz stikala", File((System.getProperty("ab.vir") ?: "src/main/kotlin/si/safeer/tv/AbStikala.kt")
        .replace("AbStikala.kt", "ThreatFeedsUpdater.kt")).readText().contains("FilterListEngine.vsiljenoEnonitno = AbStikala.stikala.prevodEnonitni"))
    preveri("cssseznam=on se prezre", AbStikala.razclenji("cssseznam=on,cssyt=1") == brez)
    preveri("brez stikal: splosna + seznam, brez podvojenih, isti vrstni red",
        AbStikala.izberiSelektorje(sp, sz, brez) == listOf(".ad-slot", ".ytp-ad-text", "ytm-open-app-button", "[class*='google-ad']", "div.na-24ur", "#oglas24"))
    preveri("cssseznam=off: samo splosna", AbStikala.izberiSelektorje(sp, sz, brez.copy(cssSeznamIzklop = true)) ==
        listOf(".ad-slot", ".ytp-ad-text", "ytm-open-app-button", "[class*='google-ad']"))
    preveri("cssyt=off: brez ytp-/ytm-/ytd-, seznam ostane", AbStikala.izberiSelektorje(sp, sz, brez.copy(cssYtIzklop = true)) ==
        listOf(".ad-slot", "[class*='google-ad']", "div.na-24ur", "#oglas24"))
    val cosm = File((System.getProperty("ab.vir") ?: "src/main/kotlin/si/safeer/tv/AbStikala.kt").replace("AbStikala.kt", "CosmeticFilterEngine.kt")).readText()
    preveri("CosmeticFilterEngine izbira selektorje prek stikal", cosm.contains("AbStikala.izberiSelektorje(") && cosm.contains("AbStikala.stikala"))
    if (napak > 0) { println("AbStikalaTest: $napak napak"); System.exit(1) }
    println("AbStikalaTest: OK")
}
