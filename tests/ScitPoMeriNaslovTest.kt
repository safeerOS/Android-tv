// Preverba naslova filtra po meri (N-SCIT, 15. 11. 2026): isti nabor pravil kot Linux (core/os_scit.napaka_naslova, veja
// zdruzitev-kandidat-1411) - krajevna imena, IP-literali, imena z IP v imenu, { }, prazne in predolge oznake, vrata.
// Staro napaka() je zavrnilo samo http, presledke, @ in ime brez pike; vsaka vrstica spodaj brez popravka PADE.
//   tests/run_scit_tests.sh
package si.safeer.tv.scit

import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun preveriNaslov(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

fun main() {
    val zavrnjeni = listOf(
        // krajevna imena (Linux IME_KRAJEVNO)
        "https://localhost./hosts", "https://foo.local/hosts", "https://a.b.localhost/x", "https://x.localhost/x", "https://nas.internal/x",
        "https://fritz.box/x", "https://x.test/x", "https://router.lan/x", "https://srv.corp/x", "https://pc.home.arpa/x", "https://PC.LocalDomain/x",
        "https://intranet./x",
        // imena z IP v imenu (javni DNS jih razresi na vpisani naslov)
        "https://192-168-0-1.sslip.io/x", "https://10.0.0.1.nip.io/x", "https://www.127.0.0.1.nip.io/x", "https://x.xip.io/x",
        // IP-literali: zasebni, povratna zanka, link-local, CGNAT, 0.0.0.0, koncna pika, krajsi in sestnajstiski/osmiski zapis
        "https://127.0.0.1/x", "https://10.1.2.3/x", "https://192.168.77.9/x", "https://169.254.169.254/x", "https://172.16.0.1/x", "https://100.64.0.1/x",
        "https://0.0.0.0/x", "https://127.0.0.1./x", "https://127.1/x", "https://0x7f.0.0.1/x", "https://0x7f.1/x", "https://0177.0.0.1/x",
        "https://017700000001.1/x",
        // IPv6 s pikami (brez pik zavrne ze pravilo »ime brez pike«, kot na Linuxu)
        "https://[::1]/x", "https://[::ffff:127.0.0.1]/x", "https://[::ffff:10.0.0.1]/x", "https://[64:ff9b::10.0.0.1]/x", "https://[fe80::1:192.168.0.1]/x",
        "https://[::127.0.0.1]/x",
        // prazne in predolge oznake, vezaj na robu, vrata, kodiranje, znaki
        "https://a..b.si/x", "https://-a.si/x", "https://a-.si/x", "https://" + "a".repeat(64) + ".si/x",
        "https://" + "a".repeat(60) + "." + "b".repeat(60) + "." + "c".repeat(60) + "." + "d".repeat(60) + "." + "e".repeat(20) + ".si/x",
        "https://primer.si:99999/x", "https://primer.si:80:90/x", "https://primer.si:/x", "https://pri%6Der.si/x", "https://primer_si.example/x",
        "https://[::1/x", "https://[::1]x.si/x", "https://.primer.si/x", "https://1.2.3.4.5/x",
        // staro pravilo
        "https://uporabnik:geslo@primer.si/h", "https://lokalno/x",
    )
    for (n in zavrnjeni) preveriNaslov(ScitPoMeriPravila.napaka(n) == "gostitelj", "zavrnjen gostitelj ${n.take(60)}")

    // { } so samo za vgrajeni vir (»{KLJUC}«): zavrnjeni v vsem naslovu
    for (n in listOf("https://raw.githubusercontent.com/{KLJUC}/hosts", "https://primer.si/h?k={a}", "https://exam{ple}.si/x", "https://primer.si/}"))
        preveriNaslov(ScitPoMeriPravila.napaka(n) == "znaki", "{ } zavrnjena ${n.take(60)}")
    preveriNaslov(ScitPoMeriPravila.napaka("https://primer.si/a b") == "znaki", "presledek zavrnjen")
    preveriNaslov(ScitPoMeriPravila.napaka("http://primer.si/hosts") == "https", "samo https")
    preveriNaslov(ScitPoMeriPravila.napaka("https://a.s") == "dolzina", "prekratek naslov")

    val sprejeti = listOf(
        "https://raw.githubusercontent.com/x/y/hosts", "https://Raw.GitHubUserContent.com/x/hosts", "https://primer.si:8443/h", "https://8.8.8.8/hosts",
        "https://1.1.1.1:443/x", "https://primer.si./hosts", "https://xn--zel-ihe.si/x", "https://a-b.primer.si/x?a=1#k", "https://" + "a".repeat(63) + ".si/x",
        "https://172.32.0.1/x", "https://100.128.0.1/x", "https://192.169.0.1/x", "https://primer.si:65535/x", "https://localhostx.si/x", "https://mylocal.si/x",
    )
    for (n in sprejeti) preveriNaslov(ScitPoMeriPravila.napaka(n) == null, "sprejet ${n.take(60)}")

    // Javnost dobesednega naslova (Linux _naslov_je_javen)
    val naslovi = listOf("8.8.8.8" to true, "127.0.0.1" to false, "::1" to false, "::" to false, "2606:4700:4700::1111" to true, "::ffff:8.8.8.8" to true,
        "::ffff:10.0.0.1" to false, "::ffff:7f00:1" to false, "64:ff9b::8.8.8.8" to true, "64:ff9b::a00:1" to false, "64:ff9b:1::1" to false,
        "2002:7f00:1::" to false, "2002:808:808::" to true, "fc00::1" to false, "fd12:3456::1" to false, "fe80::1" to false, "fec0::1" to false, "ff02::1" to false,
        "2001:db8::1" to false, "100::1" to false, "3fff::1" to false, "5f00::1" to false, "::127.0.0.1" to false, "1:2:3:4:5:6:7:8:9" to false,
        "1::2::3" to false, "12345::1" to false, "gggg::1" to false, "8.8.8" to false, "08.8.8.8" to false, "256.1.1.1" to false)
    for ((ip, javen) in naslovi) preveriNaslov(ScitPoMeriPravila.naslovJeJavenIp(ip) == javen, "naslov $ip javen=$javen")

    // Ime filtra in oznaka ostaneta kot prej
    preveriNaslov(ScitPoMeriPravila.id("https://primer.si/hosts") == ScitPoMeriPravila.id(" https://primer.si/hosts "), "oznaka je neodvisna od presledkov")
    if (padlo > 0) { println("ScitPoMeriNaslovTest: PADLO $padlo od $preverb"); exitProcess(1) }
    println("ScitPoMeriNaslovTest: OK ($preverb preverb)")
}
