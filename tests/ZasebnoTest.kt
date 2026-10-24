package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 112: naslovi za dnevnik in zaslon brez zetonov in gesel ([Zasebno]). */
fun main() {
    println("Preizkus zasebnosti naslovov")
    val Z = Zasebno
    preveriEnako("zeton v poti", "http://telekomsrbije.com/…", Z.zaDnevnik("http://telekomsrbije.com:2095/play/lhpjmkfVyhON1JAiZCx7vh"))
    preveriEnako("geslo v naslovu", "http://ponudnik.test/…", Z.zaDnevnik("http://uporabnik:geslo@ponudnik.test/get.php?username=u&password=p"))
    preveriEnako("zeton v poizvedbi brez poti", "https://a.test/…", Z.zaDnevnik("https://a.test?token=abc"))
    preveriEnako("samo gostitelj", "https://a.test", Z.zaDnevnik("https://A.Test/"))
    preveriEnako("content", "content:…", Z.zaDnevnik("content://media/external/video/media/12"))
    preveriEnako("ni naslov", "…", Z.zaDnevnik("nekaj brez sheme"))
    preveriEnako("prazno", "", Z.zaDnevnik(""))
    preveriEnako("IPv6", "http://[fe80::1]/…", Z.zaDnevnik("http://[fe80::1]:8080/live/x"))
    preveriEnako("gostitelj brez vrat in uporabnika", "cdn.ex-yu.net", Z.gostitelj("http://u@CDN.ex-yu.net:2095/auth/abc"))
    preveriEnako("gostitelj ni", "", Z.gostitelj("content://media/x"))
    preveriEnako("prikaz = gostitelj", "stream.radio.test", Z.prikaz("https://stream.radio.test:8443/live?token=x"))

    if (napak == 0) println("ZasebnoTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
