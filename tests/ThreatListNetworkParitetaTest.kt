// Slepi pregled TV3 (N3): preverba naslovov prenosa seznamov (ThreatListNetwork, skupna datoteka agenta) se mora ujemati s
// ScitPoMeriPravila.naslovJeJavenIp (ki je prenos Linuxa _naslov_je_javen). Primerjava na mejah omrezij in na nakljucnih naslovih.
//   tests/run_scit_tests.sh
package si.safeer.tv.scit

import com.safeer.threatfeed.ThreatListNetwork
import java.net.InetAddress
import java.util.Random
import kotlin.system.exitProcess

private var razlik = 0
private var preverb = 0
private var zunaj = 0

private fun primerjaj(b: ByteArray) {
    preverb++
    val besedilo = if (b.size == 4) b.joinToString(".") { (it.toInt() and 0xFF).toString() }
    else (0 until 8).joinToString(":") { "%x".format(((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF)) }
    val moj = !ThreatListNetwork.isNonPublicAddress(b)
    val vzorec = ScitPoMeriPravila.naslovJeJavenIp(besedilo)
    // IPv6 zunaj javnega unicast obmocja (prvi trije biti 001), razen preslikanih IPv4 in NAT64: ThreatListNetwork je NAMENOMA strozji
    // (kot SondaVeriga); Linux _ip_je_javen in ScitPoMeriPravila.naslovJeJavenIp tam sprejmeta npr. 0:6ea4:91d6::, ker jih ni na seznamu.
    // Pariteta velja za IPv4 in za IPv6 v javnem obmocju; zunaj njega mora biti nejavno brez izjeme.
    if (b.size == 16 && (b[0].toInt() and 0xE0) != 0x20) {
        val preslikan = (0..9).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
        val nat64 = b[0].toInt() == 0 && b[1].toInt() == 0x64 && b[2].toInt() == -1 && b[3].toInt() == 0x9B.toByte().toInt() && (4..11).all { b[it].toInt() == 0 }
        if (!preslikan && !nat64) {
            zunaj++
            if (moj) { razlik++; println("RAZLIKA $besedilo: zunaj javnega obmocja mora biti nejavno") }
            return
        }
    }
    if (moj != vzorec) { razlik++; if (razlik <= 40) println("RAZLIKA $besedilo: ThreatListNetwork javen=$moj, ScitPoMeriPravila javen=$vzorec") }
}

fun main() {
    val v4 = ArrayList<ByteArray>()
    // meje: vsak prvi/zadnji naslov vsakega omrezja in sosednja naslova
    val osnove = listOf("0.0.0.0", "10.0.0.0", "100.64.0.0", "127.0.0.0", "169.254.0.0", "172.16.0.0", "192.0.0.0", "192.0.2.0", "192.88.99.0", "192.168.0.0",
        "198.18.0.0", "198.51.100.0", "203.0.113.0", "224.0.0.0", "240.0.0.0")
    for (o in osnove) {
        val z = InetAddress.getByName(o).address
        var n = 0L; for (x in z) n = (n shl 8) or (x.toLong() and 0xFF)
        for (d in longArrayOf(-2, -1, 0, 1, 255, 256, 65535, 65536, 1048575, 1048576, 4194303, 4194304, 16777215, 16777216)) {
            val v = (n + d) and 0xFFFFFFFFL
            v4 += byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        }
    }
    val r = Random(20261115)
    repeat(30000) { val v = r.nextInt(); v4 += byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()) }
    v4.forEach { primerjaj(it) }
    // IPv6: nakljucni naslovi z vsiljeno predpono (javni 2000::/3, dokumentacija, Teredo, NAT64, 6to4, preslikani IPv4, lokalni, vecvrstni) in cisto nakljucni
    val predpone = listOf("2001:db8::", "2001::", "2001:200::", "2001:4860::", "2606:4700::", "3fff::", "3ffe::", "2002::", "64:ff9b::", "64:ff9b:1::", "::ffff:0:0",
        "::", "fc00::", "fd12::", "fe80::", "fec0::", "ff02::", "100::", "5f00::", "2a00::", "2400::", "2620::")
    val r6 = Random(20261116)
    for (pr in predpone) {
        // ::ffff:0:0 Java vrne kot 4 bajte (IPv4); za preizkus rabimo 16 bajtov
        val osn = InetAddress.getByName(pr).address.let { if (it.size == 16) it else ByteArray(16).also { b -> b[10] = -1; b[11] = -1 } }
        val bitov = when (pr) { "2001:db8::" -> 32; "3fff::" -> 16; "64:ff9b::", "::ffff:0:0" -> 96; "2002::" -> 16; "64:ff9b:1::" -> 48; else -> 16 }
        repeat(400) {
            val b = ByteArray(16); r6.nextBytes(b)
            for (i in 0 until bitov / 8) b[i] = osn[i]
            primerjaj(b)
        }
        primerjaj(osn)
    }
    repeat(20000) { val b = ByteArray(16); r6.nextBytes(b); primerjaj(b) }
    repeat(20000) { val b = ByteArray(16); r6.nextBytes(b); b[0] = ((b[0].toInt() and 0x1F) or 0x20).toByte(); primerjaj(b) }
    println("ThreatListNetworkParitetaTest: $preverb naslovov ($zunaj IPv6 zunaj javnega unicast obmocja: strozje od Linuxa po zasnovi), $razlik razlik")
    if (razlik > 0) exitProcess(1)
}
