package si.safeer.tv

import kotlin.system.exitProcess

private var napak = 0

private fun preveri(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus povratka v aplikacijo, ki je odprla zavihek (App Links)")
    val mi = "si.safeer.tv"
    val claude = "com.anthropic.claude"
    val brskalniki = setOf("com.android.chrome", "org.mozilla.firefox", mi)
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

    // Samodejna predaja: claude.ai/new po prijavi gre nazaj v Claude
    val razreseni = setOf("com.android.chrome", claude, mi)
    preveri("po prijavi: claude.ai/new -> aplikacija Claude", claude,
        AplikacijaPovezave.predajNavigacijo(claude, razreseni, brskalniki, true, false, "https://claude.ai/new", mi))
    preveri("zacetno nalaganje (in preusmeritve) ostane v brskalniku", null,
        AplikacijaPovezave.predajNavigacijo(claude, razreseni, brskalniki, true, true, "https://claude.ai/new", mi))
    preveri("podokvir nikoli", null,
        AplikacijaPovezave.predajNavigacijo(claude, razreseni, brskalniki, false, false, "https://claude.ai/new", mi))
    preveri("naslova, ki ga aplikacija ne odpre (Google prijava), ne predamo", null,
        AplikacijaPovezave.predajNavigacijo(claude, setOf("com.android.chrome", mi), brskalniki, true, false,
            "https://accounts.google.com/o/oauth2/v2/auth", mi))
    preveri("nikoli drugi aplikaciji kot izvoru (ni ugrabitve domene)", null,
        AplikacijaPovezave.predajNavigacijo(claude, setOf("com.zlobna.aplikacija", mi), brskalniki, true, false,
            "https://claude.ai/new", mi))
    preveri("zavihek brez izvora ostane v brskalniku", null,
        AplikacijaPovezave.predajNavigacijo(null, razreseni, brskalniki, true, false, "https://claude.ai/new", mi))
    preveri("izvor, ki je brskalnik, ne prevzame", null,
        AplikacijaPovezave.predajNavigacijo("org.mozilla.firefox", setOf("org.mozilla.firefox"), brskalniki, true, false,
            "https://claude.ai/new", mi))
    preveri("ne-http shema ni stvar tega pravila", null,
        AplikacijaPovezave.predajNavigacijo(claude, setOf(claude), brskalniki, true, false, "intent://claude.ai/new#Intent;end", mi))

    // Rocni gumb
    preveri("gumb: trenutni naslov odpre izvor", claude,
        AplikacijaPovezave.ponudiAplikacijo(claude, razreseni, brskalniki, "https://claude.ai/new", mi))
    preveri("gumb: brez izvora ni gumba", null,
        AplikacijaPovezave.ponudiAplikacijo(null, razreseni, brskalniki, "https://claude.ai/new", mi))

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
