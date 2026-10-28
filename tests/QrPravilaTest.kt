package si.safeer.tv.link

/** Koda QR za prijavo in pridruzitev (zunanji pregled kode 8. 10. 2026): cel odtis potrdila, okteti namesto predpone. */
fun main() {
    val o = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
    check(o.length == 64)
    check(QrPravila.veljavenOdtis(o))
    check(!QrPravila.veljavenOdtis(o.take(16))) { "predpona 16 znakov ni vec dovolj" }
    check(!QrPravila.veljavenOdtis(o.take(63)))
    check(!QrPravila.veljavenOdtis(o + "0"))
    check(!QrPravila.veljavenOdtis(o.uppercase())) { "v kodi samo male crke (razcleni jih ze zmanjsa)" }
    check(!QrPravila.veljavenOdtis(o.replaceFirst('a', 'g')))
    check(!QrPravila.veljavenOdtis(""))

    check(QrPravila.odtisUstreza(o, o))
    check(QrPravila.odtisUstreza(o.uppercase(), o)) { "videni odtis je lahko z velikimi crkami" }
    check(!QrPravila.odtisUstreza(o, o.take(16))) { "predpona se ne ujema vec" }
    check(!QrPravila.odtisUstreza(o.take(16), o.take(16)))
    check(!QrPravila.odtisUstreza(null, o))
    check(!QrPravila.odtisUstreza(o.dropLast(1) + "3", o))
    check(!QrPravila.odtisUstreza(o + "00", o)) { "daljsi videni z isto predpono" }

    for (h in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.0.9", "192.168.1.10", "192.168.255.1")) {
        check(QrPravila.jeZasebniIpv4(h)) { "zasebni $h" }
    }
    for (h in listOf(null, "", "10.evil.com", "192.168.evil.com", "10.0.0", "10.0.0.1.2", "172.15.0.1", "172.32.0.1",
                     "8.8.8.8", "010.0.0.1", "10.0.0.256", "1o.0.0.1", "10.0.0.1 ", "127.0.0.1", "169.254.0.1")) {
        check(!QrPravila.jeZasebniIpv4(h)) { "ni zasebni $h" }
    }

    // Pregled 28. 10. 2026: naslov sredisca v kodi samo zaseben, opozorilo ob preselitvi iz drugega Safeer Linka.
    for (n in listOf("192.168.0.77:8443", "10.0.0.5:1", "172.20.1.2:65535")) {
        check(QrPravila.zasebniNaslovSredisca(n)) { "zasebni naslov $n" }
    }
    for (n in listOf("8.8.8.8:8443", "93.184.216.34:443", "192.168.0.77", "192.168.0.77:", "192.168.0.77:0", "192.168.0.77:65536",
                     "192.168.0.77:08443", "10.evil.com:443", ":443", "", "192.168.0.77:84a3", "192.168.0.77:123456")) {
        check(!QrPravila.zasebniNaslovSredisca(n)) { "ni zasebni naslov $n" }
    }
    check(!QrPravila.preselitev(false, o, o.reversed())) { "brez zetona ni preselitve" }
    check(!QrPravila.preselitev(true, null, o)) { "brez pripetega odtisa ni preselitve" }
    check(!QrPravila.preselitev(true, o.uppercase(), o)) { "isti Link ni preselitev" }
    check(QrPravila.preselitev(true, o.reversed(), o)) { "drug odtis je preselitev" }
    check(QrPravila.kratekOdtis(o) == "a1b2 c3d4 e5f6 a7b8")
    println("QrPravilaTest: OK")
}
