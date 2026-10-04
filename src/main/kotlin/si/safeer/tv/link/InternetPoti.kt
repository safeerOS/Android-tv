package si.safeer.tv.link

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Cist model/politika internetnih poti; brez Android odvisnosti, zato ga lahko testiramo na JVM. */
data class InternetPot(
    val id: String,
    val vrsta: VrstaInternetPoti,
    val dosegljiva: Boolean,
    val merjena: Boolean,
    val roaming: Boolean = false,
    /** Android je preveril, da omrezje res pride do interneta. Wi-Fi ob izpadu doma ostane povezan, a nepreverjen. */
    val preverjena: Boolean = true
)

enum class VrstaInternetPoti { WIFI, CELLULAR, ETHERNET, VPN, DRUGO }

data class InternetPolitika(
    val dovoliMobilne: Boolean = false,
    val dovoliRoaming: Boolean = false,
    val omejitevBajtov: Long = 0L,
    val porabljenoBajtov: Long = 0L
) {
    fun dovoljena(pot: InternetPot): Boolean {
        if (!pot.dosegljiva) return false
        if (pot.vrsta != VrstaInternetPoti.CELLULAR) return true
        if (!dovoliMobilne) return false
        if (pot.roaming && !dovoliRoaming) return false
        if (omejitevBajtov > 0 && porabljenoBajtov >= omejitevBajtov) return false
        return true
    }
}

/** Izid izbire poti: [pot] ali [razlog] zavrnitve (koda protokola). */
class IzbranaPot(val pot: InternetPot?, val razlog: String)

/**
 * Katero pot telefona uporabi tok. [zahteva] je `mobile` (samo mobilno omrezje), `wifi` (samo Wi-Fi ali
 * zicno), `any` ali prazno (Wi-Fi z internetom, sicer mobilno) ali tocen id poti.
 *
 * Namen prehoda je deliti MOBILNI internet: ob izpadu domacega interneta je Wi-Fi telefona ista pokvarjena
 * povezava. Zato odjemalec privzeto zahteva `mobile`, pri `any` pa Wi-Fi velja samo, ce je preverjen.
 * VPN in druga omrezja se ne izberejo sama (samo s tocnim id-jem).
 */
fun izberiPot(vse: List<InternetPot>, politika: InternetPolitika, zahteva: String): IzbranaPot {
    val dosegljive = vse.filter { it.dosegljiva }
    val mobilne = dosegljive.filter { it.vrsta == VrstaInternetPoti.CELLULAR }
    val domace = dosegljive.filter { it.vrsta == VrstaInternetPoti.WIFI || it.vrsta == VrstaInternetPoti.ETHERNET }

    fun mobilna(): IzbranaPot {
        if (!politika.dovoliMobilne) return IzbranaPot(null, "mobile_off")
        if (politika.omejitevBajtov > 0 && politika.porabljenoBajtov >= politika.omejitevBajtov) return IzbranaPot(null, "limit")
        if (mobilne.isEmpty()) return IzbranaPot(null, "no_mobile")
        val dovoljene = mobilne.filter { politika.dovoljena(it) }
        if (dovoljene.isEmpty()) return IzbranaPot(null, "roaming")
        return IzbranaPot(dovoljene.firstOrNull { it.preverjena } ?: dovoljene.first(), "")
    }

    return when (zahteva) {
        "mobile", "cellular" -> mobilna()
        "wifi" -> domace.firstOrNull { it.preverjena }?.let { IzbranaPot(it, "") } ?: IzbranaPot(null, "no_path")
        "", "any" -> domace.firstOrNull { it.preverjena }?.let { IzbranaPot(it, "") } ?: mobilna().let {
            if (it.pot != null || it.razlog == "limit" || it.razlog == "roaming") it else IzbranaPot(null, "no_path")
        }
        else -> {
            val p = dosegljive.firstOrNull { it.id == zahteva } ?: return IzbranaPot(null, "no_path")
            if (p.vrsta != VrstaInternetPoti.CELLULAR || politika.dovoljena(p)) IzbranaPot(p, "")
            else IzbranaPot(null, mobilna().razlog.ifBlank { "roaming" })
        }
    }
}

/**
 * Cilj, ki ga gateway sme odpreti: samo javni internet. Lokalna omrezja, loopback, CGNAT,
 * rezervirani in testni bloki odpadejo, da naprava v krogu prek telefona ne doseze
 * telefonovega lokalnega omrezja (SSRF).
 */
fun varenInternetniNaslov(a: InetAddress): Boolean {
    if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
    val b = a.address
    if (a is Inet4Address) {
        val x = b[0].toInt() and 255; val y = b[1].toInt() and 255; val z = b[2].toInt() and 255
        if (x == 0 || x >= 240) return false // "ta" mreza, rezervirano, broadcast
        if (x == 100 && y in 64..127) return false // CGNAT
        if (x == 169 && y == 254) return false
        if (x == 198 && y in 18..19) return false // meritve (RFC 2544)
        if (x == 192 && y == 0 && (z == 0 || z == 2)) return false // IETF, TEST-NET-1
        if ((x == 198 && y == 51 && z == 100) || (x == 203 && y == 0 && z == 113)) return false // TEST-NET-2/3
    }
    if (a is Inet6Address) {
        val x = b[0].toInt() and 255
        if ((x and 0xfe) == 0xfc) return false // unique-local fc00::/7
        if (x == 0x20 && (b[1].toInt() and 255) == 0x01 && (b[2].toInt() and 255) == 0x0d && (b[3].toInt() and 255) == 0xb8) return false // 2001:db8::/32
    }
    return true
}

/** Posiljanje poste (SMTP) prek tujega telefona je vektor za spam; gateway ga ne odpira. */
fun dovoljenaVrata(port: Int): Boolean = port in 1..65535 && port !in setOf(25, 465, 587)
