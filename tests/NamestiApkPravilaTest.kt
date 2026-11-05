package si.safeer.tv.os

import si.safeer.tv.os.NamestiApkPravila.Skok
import java.io.File

/**
 * Pregled zaslonov 5. 11. 2026 (os/NamestiApkPravila): preusmeritve prenosa APK - vsak skok samo https, najvec 5 skokov,
 * piskotki samo za naslov skoka; APK ponudnika samo s pricakovanim paketom; Aptoide samo z njihovih domen.
 * Argumenta: pot do NamestiApk.kt in TrgovineAplikacij.kt (preverimo, da pot prenosa res klice ta pravila).
 */
fun main(args: Array<String>) {
    fun naprej(s: Skok): String = (s as? Skok.Naprej)?.url ?: error("pricakovan skok, dobil $s")
    fun napaka(s: Skok): String = (s as? Skok.Napaka)?.razlog ?: error("pricakovana napaka, dobil $s")

    // 2xx na https: beremo; na http nikoli.
    check(NamestiApkPravila.naslednji("https://f-droid.org/F-Droid.apk", 200, null, 0) is Skok.Koncaj)
    check(napaka(NamestiApkPravila.naslednji("http://f-droid.org/F-Droid.apk", 200, null, 0)) == "ni_https")
    // GitHub: github.com -> release-assets.githubusercontent.com (drug streznik, https) je dovoljen skok.
    check(naprej(NamestiApkPravila.naslednji("https://github.com/o/r/releases/download/v1/a.apk", 302,
        "https://release-assets.githubusercontent.com/x/a.apk?sig=1", 0)) == "https://release-assets.githubusercontent.com/x/a.apk?sig=1")
    // Relativna lokacija se razresi proti trenutnemu naslovu.
    check(naprej(NamestiApkPravila.naslednji("https://f-droid.org/repo/a.apk", 301, "/repo/b.apk", 0)) == "https://f-droid.org/repo/b.apk")
    check(naprej(NamestiApkPravila.naslednji("https://f-droid.org/repo/a.apk", 307, "//mirror.example/a.apk", 1)) == "https://mirror.example/a.apk")
    // Preusmeritev na http (ali drugo shemo) ustavi prenos - APK bi lahko zamenjal kdorkoli na poti.
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 302, "http://f-droid.org/a.apk", 0)) == "ni_https")
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 302, "file:///sdcard/a.apk", 0)) == "ni_https")
    // Neznana shema (javascript:) je za java.net.URL neveljaven naslov - prav tako napaka, nikoli skok.
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 302, "javascript:alert(1)", 0)) in setOf("ni_https", "neveljavna_lokacija"))
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 302, "https://a:b@zlo.example/a.apk", 0)) == "neveljavna_lokacija")
    // Brez lokacije, druge kode in prevec skokov: napaka (ne neskoncna zanka).
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 302, null, 0)) == "brez_lokacije")
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 302, "  ", 0)) == "brez_lokacije")
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 404, null, 0)) == "HTTP 404")
    check(napaka(NamestiApkPravila.naslednji("https://f-droid.org/a.apk", 304, "https://x.example/", 0)) == "HTTP 304")
    for (k in listOf(301, 302, 303, 307, 308)) check(NamestiApkPravila.naslednji("https://a.example/", k, "https://b.example/", 4) is Skok.Naprej)
    check(napaka(NamestiApkPravila.naslednji("https://a.example/", 302, "https://b.example/", NamestiApkPravila.NAJVEC_SKOKOV)) == "prevec_preusmeritev")
    check(NamestiApkPravila.NAJVEC_SKOKOV == 5)

    // Paket ponudnika: natanko pricakovani; brez pricakovanja (brskalnik) odloci uporabnik v oknu.
    check(NamestiApkPravila.pravPaket("org.fdroid.fdroid", "org.fdroid.fdroid"))
    check(!NamestiApkPravila.pravPaket("org.fdroid.fdroid", "org.fdroid.fdroid.zlo"))
    check(!NamestiApkPravila.pravPaket("org.fdroid.fdroid", ""))
    check(!NamestiApkPravila.pravPaket("org.fdroid.fdroid", null))
    check(NamestiApkPravila.pravPaket(null, "com.karkoli"))
    check(NamestiApkPravila.pravPaket("", "com.karkoli"))

    // Aptoide: samo https na aptoide.com ali poddomeni.
    val d = NamestiApkPravila.DOMENE_APTOIDE
    check(NamestiApkPravila.naDomeni("https://pool.apk.aptoide.com/x/cm-aptoidetv-pt-542.apk", d))
    check(NamestiApkPravila.naDomeni("https://aptoide.com/a.apk", d))
    check(NamestiApkPravila.naDomeni("https://POOL.APK.Aptoide.com./a.apk", d))
    check(!NamestiApkPravila.naDomeni("http://pool.apk.aptoide.com/a.apk", d))
    check(!NamestiApkPravila.naDomeni("https://zloaptoide.com/a.apk", d))
    check(!NamestiApkPravila.naDomeni("https://aptoide.com.zlo.example/a.apk", d))
    check(!NamestiApkPravila.naDomeni("https://zlo.example/aptoide.com/a.apk", d))
    check(!NamestiApkPravila.naDomeni("ni naslov", d))

    // Pot prenosa res uporablja pravila (brez tega bi preizkus pravil zelenel ob stari kodi).
    if (args.size >= 2) {
        val namesti = File(args[0]).readText()
        check(!namesti.contains("instanceFollowRedirects = true")) { "NamestiApk: samodejne preusmeritve" }
        check(namesti.contains("instanceFollowRedirects = false")) { "NamestiApk: preusmeritvam ne sledi sam" }
        check(namesti.contains("NamestiApkPravila.naslednji(")) { "NamestiApk: skok brez pravil" }
        check(namesti.contains("getCookie(naslov)")) { "NamestiApk: piskotki niso za naslov skoka" }
        check(namesti.contains("NamestiApkPravila.pravPaket(")) { "NamestiApk: paket ponudnika ni preverjen" }
        check(!namesti.contains("velikostZadnjega")) { "NamestiApk: velikost v skupnem polju" }
        check(!Regex("AlertDialog\\.Builder\\(a\\)").containsMatchIn(namesti)) { "NamestiApk: okno brez Os_Okno" }
        val trgovine = File(args[1]).readText()
        check(trgovine.contains("p.ime + \".apk\", p.paket)")) { "TrgovineAplikacij: brez pricakovanega paketa" }
        check(trgovine.contains("NamestiApkPravila.naDomeni(it, NamestiApkPravila.DOMENE_APTOIDE)")) { "TrgovineAplikacij: Aptoide brez domen" }
        check(!Regex("AlertDialog\\.Builder\\(a\\)").containsMatchIn(trgovine)) { "TrgovineAplikacij: okno brez Os_Okno" }
    } else error("manjkata poti do NamestiApk.kt in TrgovineAplikacij.kt")
    println("NamestiApkPravilaTest: OK")
}
