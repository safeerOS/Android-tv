import si.safeer.tv.link.*
import java.net.InetAddress

private fun ip(s: String) = InetAddress.getByName(s)

fun main() {
    val wifi = InternetPot("wifi", VrstaInternetPoti.WIFI, true, false)
    val cell = InternetPot("5g", VrstaInternetPoti.CELLULAR, true, true)
    val roam = InternetPot("roam", VrstaInternetPoti.CELLULAR, true, true, true)
    check(InternetPolitika().dovoljena(wifi))
    check(!InternetPolitika().dovoljena(cell))
    check(InternetPolitika(dovoliMobilne = true).dovoljena(cell))
    check(!InternetPolitika(dovoliMobilne = true).dovoljena(roam))
    check(InternetPolitika(dovoliMobilne = true, dovoliRoaming = true).dovoljena(roam))
    check(InternetPolitika(dovoliMobilne = true, omejitevBajtov = 100, porabljenoBajtov = 99).dovoljena(cell))
    check(!InternetPolitika(dovoliMobilne = true, omejitevBajtov = 100, porabljenoBajtov = 100).dovoljena(cell))

    // Samo javni internet: lokalno, rezervirano in testno odpade (SSRF).
    for (s in listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")) check(varenInternetniNaslov(ip(s))) { s }
    for (s in listOf("127.0.0.1", "10.0.0.1", "172.16.5.4", "192.168.0.77", "169.254.1.1", "100.64.0.1",
        "0.0.0.0", "0.1.2.3", "198.18.0.1", "198.19.255.1", "192.0.0.8", "192.0.2.1", "198.51.100.7",
        "203.0.113.9", "240.0.0.1", "255.255.255.255", "224.0.0.1", "::1", "fe80::1", "fd00::1", "2001:db8::1",
        "::ffff:192.168.0.1")) check(!varenInternetniNaslov(ip(s))) { s }
    check(dovoljenaVrata(443) && dovoljenaVrata(80))
    for (p in listOf(0, 25, 465, 587, 70000)) check(!dovoljenaVrata(p)) { "$p" }

    // ---- izbira poti (protokol 2): odjemalec pove, KATERO omrezje telefona hoce
    val vse = InternetPolitika(dovoliMobilne = true)
    fun izbira(poti: List<InternetPot>, politika: InternetPolitika, zahteva: String) =
        izberiPot(poti, politika, zahteva).let { (it.pot?.id ?: "") + "|" + it.razlog }
    // mobile: samo mobilno omrezje, tudi ko ima telefon delujoc Wi-Fi (to je namen prehoda)
    check(izbira(listOf(wifi, cell), vse, "mobile") == "5g|")
    check(izbira(listOf(wifi, cell), InternetPolitika(), "mobile") == "|mobile_off")
    check(izbira(listOf(wifi), vse, "mobile") == "|no_mobile")
    check(izbira(listOf(wifi, roam), vse, "mobile") == "|roaming")
    check(izbira(listOf(wifi, roam), vse.copy(dovoliRoaming = true), "mobile") == "roam|")
    check(izbira(listOf(wifi, cell), vse.copy(omejitevBajtov = 100, porabljenoBajtov = 100), "mobile") == "|limit")
    check(izbira(listOf(cell), vse.copy(omejitevBajtov = 100, porabljenoBajtov = 99), "cellular") == "5g|")
    // any: Wi-Fi z internetom, sicer mobilno. Wi-Fi brez interneta (izpad doma) se ne izbere.
    val wifiBrez = InternetPot("wifi-brez", VrstaInternetPoti.WIFI, true, false, preverjena = false)
    check(izbira(listOf(wifi, cell), vse, "any") == "wifi|")
    check(izbira(listOf(wifi, cell), vse, "") == "wifi|")
    check(izbira(listOf(wifiBrez, cell), vse, "any") == "5g|")
    check(izbira(listOf(wifiBrez), vse, "any") == "|no_path")
    check(izbira(listOf(wifiBrez, cell), InternetPolitika(), "any") == "|no_path")
    check(izbira(listOf(wifiBrez, cell), vse.copy(omejitevBajtov = 5, porabljenoBajtov = 9), "any") == "|limit")
    check(izbira(listOf(wifiBrez, roam), vse, "any") == "|roaming")
    check(izbira(emptyList(), vse, "any") == "|no_path")
    // wifi: nikoli mobilno
    check(izbira(listOf(wifi, cell), vse, "wifi") == "wifi|")
    check(izbira(listOf(wifiBrez, cell), vse, "wifi") == "|no_path")
    // tocen id poti; VPN in neznana omrezja samo tako
    val vpn = InternetPot("vpn", VrstaInternetPoti.VPN, true, false)
    check(izbira(listOf(vpn, cell), vse, "any") == "5g|")
    check(izbira(listOf(vpn), vse, "any") == "|no_path")
    check(izbira(listOf(vpn, wifi), vse, "vpn") == "vpn|")
    check(izbira(listOf(wifi, cell), vse, "5g") == "5g|")
    check(izbira(listOf(wifi, cell), InternetPolitika(), "5g") == "|mobile_off")
    check(izbira(listOf(wifi, cell), vse, "ni-te-poti") == "|no_path")
    // nedosegljiva pot ne obstaja
    check(izbira(listOf(InternetPot("mrtva", VrstaInternetPoti.CELLULAR, false, true)), vse, "mobile") == "|no_mobile")
    println("InternetPotiTest OK")
}
