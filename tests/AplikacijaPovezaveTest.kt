package si.safeer.tv

import kotlin.system.exitProcess

private var napak = 0

private fun preveri(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus povratka v aplikacijo, ki je odprla zavihek (App Links)")
    val mi = "si.safeer.tv"
    val claude = "com.anthropic.claude"
    val view = AplikacijaPovezave.AKCIJA_VIEW

    // Izvor zavihka
    preveri("referrer android-app je izvor", claude,
        AplikacijaPovezave.izvor(view, "https://claude.ai/login", "android-app", claude, null, mi))
    preveri("application_id, ce referrerja ni", claude,
        AplikacijaPovezave.izvor(view, "https://claude.ai/login", null, null, claude, mi))
    preveri("spletni referrer (https) ni aplikacija", null,
        AplikacijaPovezave.izvor(view, "https://claude.ai/login", "https", "evil.example", null, mi))
    preveri("samo VIEW", null,
        AplikacijaPovezave.izvor("android.intent.action.SEND", "https://claude.ai/login", "android-app", claude, null, mi))
    preveri("samo http(s) naslov", null,
        AplikacijaPovezave.izvor(view, "intent://x#Intent;end", "android-app", claude, null, mi))
    preveri("sami sebe ne vracamo", null,
        AplikacijaPovezave.izvor(view, "https://a.b/", "android-app", mi, null, mi))
    preveri("Safeer OS ima svoja pravila", null,
        AplikacijaPovezave.izvor(view, "https://a.b/", "android-app", "si.safeer.os", null, mi))
    preveri("neveljaven paket", null,
        AplikacijaPovezave.izvor(view, "https://a.b/", null, null, "../x y", mi))

    // Samodejna predaja (TV 2. 11. 2026 19:35). izvorOdpre = VIEW+BROWSABLE+setPackage(izvor) najde dejavnost.
    // Koda v poizvedbi je izmisljena (ni prava prijava).
    val sso = "https://claude.ai/sso-callback/android?code=PREIZKUS&state=PREIZKUS"
    val magic = "https://claude.ai/magic-link/android?token=PREIZKUS"
    fun predaj(n: String, odpre: Boolean = true, izvor: String? = claude, brskalnik: Boolean = false, glavni: Boolean = true) =
        AplikacijaPovezave.predajNavigacijo(izvor, odpre, brskalnik, glavni, n, mi)
    preveri("povratni naslov prijave (sso-callback) -> aplikacija", claude, predaj(sso))
    preveri("magic-link -> aplikacija", claude, predaj(magic))
    preveri("koren / se ne preda (prijava se ni koncana)", null, predaj("https://claude.ai/"))
    preveri("koren brez poti se ne preda", null, predaj("https://claude.ai"))
    preveri("koren s poizvedbo se ne preda", null, predaj("https://claude.ai/?x=1"))
    preveri("/new, ki ga izvor ne odpre, ostane v brskalniku", null, predaj("https://claude.ai/new", odpre = false))
    preveri("povezava druge aplikacije (izvor je ne odpre) ne", null, predaj("https://zlobna.example/sso-callback/android?c=1", odpre = false))
    preveri("http ne (samo https)", null, predaj("http://claude.ai/sso-callback/android?code=PREIZKUS"))
    preveri("podokvir nikoli", null, predaj(sso, glavni = false))
    preveri("zavihek brez izvora ostane v brskalniku", null, predaj(sso, izvor = null))
    preveri("izvor, ki je brskalnik, ne prevzame", null, predaj(sso, izvor = "org.mozilla.firefox", brskalnik = true))
    preveri("ne-http shema ni stvar tega pravila", null, predaj("intent://claude.ai/sso-callback/android#Intent;end"))
    preveri("nase aplikacije ne", null, predaj(sso, izvor = "si.safeer.os"))

    // Dnevnik nikoli ne vsebuje poizvedbe (enkratna koda)
    val d = AplikacijaPovezave.zaDnevnik(sso)
    preveri("dnevnik brez poizvedbe", false, d.contains("PREIZKUS") || d.contains("code="))
    preveri("dnevnik pove gostitelja in prvi del poti", "https://claude.ai/sso-callback/…?…", d)

    // Rocni gumb (rezerva): trenutni naslov ali zadnji naslov aplikacije
    preveri("gumb: naslov odpre izvor", claude, AplikacijaPovezave.ponudiAplikacijo(claude, true, false, "https://claude.ai/new", mi))
    preveri("gumb: brez izvora ni gumba", null, AplikacijaPovezave.ponudiAplikacijo(null, true, false, "https://claude.ai/new", mi))
    preveri("gumb: http ne", null, AplikacijaPovezave.ponudiAplikacijo(claude, true, false, "http://claude.ai/new", mi))

    napak += preveriNazaj()
    if (napak > 0) { println("NAPAK: $napak"); exitProcess(1) }
    println("Vse v redu.")
}

fun preveriNazaj(): Int {
    var n = 0
    fun p(o: String, a: Boolean, b: Boolean) { if (a == b) println("  ok  $o") else { n++; println("  NAPAKA  $o") } }
    p("Nazaj z gumbom vrne v aplikacijo", true, AplikacijaPovezave.nazajVAplikacijo("com.anthropic.claude", false))
    p("Nazaj brez gumba gre po zgodovini", false, AplikacijaPovezave.nazajVAplikacijo(null, false))
    p("Nazaj v spletni aplikaciji ne", false, AplikacijaPovezave.nazajVAplikacijo("com.anthropic.claude", true))
    return n
}
