package si.safeer.tv

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: String, dobljeno: String) {
    if (pricakovano == dobljeno) println("  OK   $opis") else { println("  NAPAKA $opis (pričakovano=$pricakovano, dobljeno=$dobljeno)"); napak++ }
}

fun main() {
    val san = UrlSanitizer::sanitize
    preveriEnako("utm_*", "https://a.com/?id=1", san("https://a.com/?utm_source=x&id=1"))
    preveriEnako("kodiran utm%5Fsource", "https://a.com/?id=1", san("https://a.com/?utm%5Fsource=x&id=1"))
    preveriEnako("kodiran utm%5fsource (male crke)", "https://a.com/?id=1", san("https://a.com/?utm%5fsource=x&id=1"))
    preveriEnako("kodiran fbclid", "https://a.com/", san("https://a.com/?%66bclid=abc"))
    preveriEnako("fbclid sam", "https://a.com/", san("https://a.com/?fbclid=abc"))
    preveriEnako("fragment ostane", "https://a.com/p?id=1#del", san("https://a.com/p?gclid=1&id=1#del"))
    preveriEnako("igsh, _ga, _gl, mtm_", "https://a.com/?id=2", san("https://a.com/?igsh=1&_ga=2&_gl=3&mtm_campaign=4&id=2"))
    preveriEnako("pk_campaign", "https://a.com/?id=2", san("https://a.com/?pk_campaign=1&id=2"))
    preveriEnako("si samo na YouTubu (youtu.be)", "https://youtu.be/abc?t=5", san("https://youtu.be/abc?si=XYZ&t=5"))
    preveriEnako("si samo na YouTubu (www)", "https://www.youtube.com/watch?v=abc", san("https://www.youtube.com/watch?v=abc&si=XYZ"))
    val drugje = "https://shop.example/find?si=123"
    preveriEnako("si drugje ostane", drugje, san(drugje))
    val oauth = "https://id.example/auth?code=abc&state=xyz&nonce=1"
    preveriEnako("OAuth ostane", oauth, san(oauth))
    val yt = "https://www.youtube.com/watch?v=abc&t=10&list=PL1"
    preveriEnako("video ostane", yt, san(yt))
    val skodljivo = "https://a.com/?q=nekaj_gclid_v_vrednosti"
    preveriEnako("vrednost s sledilcem ostane", skodljivo, san(skodljivo))
    val cisto = "https://a.com/?q=test"
    check(san(cisto) === cisto) { "ista referenca, ce ni sprememb" }
    val brezHttp = "ftp://a.com/?utm_source=x"
    preveriEnako("ne-HTTP se ne dotakne", brezHttp, san(brezHttp))
    val neveljavno = "https://a.com/?%zz=1&id=1"
    preveriEnako("neveljavna percent-koda ostane", neveljavno, san(neveljavno))
    if (napak > 0) { println("UrlSanitizerTest: $napak napak"); kotlin.system.exitProcess(1) }
    println("UrlSanitizerTest: OK")
}
