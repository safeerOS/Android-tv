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
    println("InternetPotiTest OK")
}
