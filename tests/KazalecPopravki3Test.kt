// Popravki slepega pregleda TV3 (15. 11. 2026), N1 in N2: gostitelj in stran za ogled brez java.net.URI.
// Vsaka vrstica brez popravka PADE (isti preizkus na osnovi 117d3e67: surov izpis v ogled/tv-popravki3-1511/izpisi/osnova/).
//   tests/run_splet_tests.sh
package si.safeer.tv

import si.safeer.tv.KazalecPravila as K
import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

fun main() {
    // N1: znaki, ki jih java.net.URI zavrne, v poizvedbi, sidru ali poti NE smejo spremeniti gostitelja ne izbire nacina.
    val znaki = listOf("{", "|", "^", "\\", " ", "}", "<", "\"", "`", "[0]", "%", "%zz")
    for (z in znaki) {
        for (u in listOf("https://evil.example/?a=$z", "https://evil.example/#$z", "https://evil.example/p$z/x", "https://evil.example/?a=b${z}c&d=1#e")) {
            val vidno = u.replace(" ", "<PRESLEDEK>")
            p(K.gostitelj(u) == "evil.example", "gostitelj evil.example iz $vidno")
            p(K.privzetoKazalec(true, u, zaTv = false, zapomnjeno = null), "privzeto kazalec (brez izbire): $vidno")
            p(K.privzetoKazalec(true, u, zaTv = false, zapomnjeno = true), "zapomnjeno kazalec ima prednost: $vidno")
            p(!K.privzetoKazalec(true, u, zaTv = false, zapomnjeno = false), "zapomnjene puscice ostanejo: $vidno")
        }
    }
    // Stran ne more preklopiti nacina: isti kljuc strani pred in po location.replace (ni ogled, isti gostitelj).
    p(K.kljucStrani(K.gostitelj("https://evil.example/"), K.jeStranOgleda("https://evil.example/")) ==
        K.kljucStrani(K.gostitelj("https://evil.example/?a={"), K.jeStranOgleda("https://evil.example/?a={")), "location.replace s { ne spremeni kljuca strani")
    // YouTube z neizbeznim znakom v poizvedbi je se vedno ogled (prej: gostitelj null, ogled false, bliznjice izgubljene).
    p(K.jeStranOgleda("https://www.youtube.com/watch?v=a|b"), "youtube watch z | v poizvedbi je ogled")
    p(K.jeStranOgleda("https://www.youtube.com/watch?v=a{b}"), "youtube watch z { } v poizvedbi je ogled")
    p(K.jeStranOgleda("https://www.youtube.com/watch?v=a b"), "youtube watch s presledkom v poizvedbi je ogled")
    p(K.jeStranOgleda("https://www.youtube.com/watch#x y"), "youtube watch s presledkom v sidru je ogled")
    p(!K.jeStranOgleda("https://evil.example/watch?x=|"), "tuj gostitelj z | ni ogled")
    p(!K.jeStranOgleda("https://youtube.com@evil.example/watch?x={"), "youtube.com@evil.example (gostitelj evil.example) ni ogled")
    p(K.jeStranOgleda("https://evil.example@youtube.com/watch?x={"), "evil.example@youtube.com (gostitelj youtube.com, kot brskalnik) je ogled")
    // Razclemba oblasti: velike crke, vrata, uporabnik, koncna pika, IPv6, backslash (Chromium bere kot /).
    p(K.gostitelj("HTTPS://WWW.24UR.COM:443/x") == "www.24ur.com", "velike crke in vrata")
    p(K.gostitelj("https://u:p@a.example/x") == "a.example", "uporabnik in geslo")
    p(K.gostitelj("https://a.example./x") == "a.example", "koncna pika")
    p(K.gostitelj("https://[::1]:8080/x") == "[::1]", "IPv6 z vrati")
    p(K.gostitelj("https://[::1/x") == null, "IPv6 brez zaklepaja je neznan gostitelj")
    p(K.gostitelj("https://a.example\\@evil.example/x") == "a.example", "backslash konca oblast")
    p(K.gostitelj("https://a.example?x@evil.example/") == "a.example", "vprasaj konca oblast")
    p(K.gostitelj("https://a.example#x@evil.example/") == "a.example", "lojtra konca oblast")
    // Neznan gostitelj ostane fail-safe (puscice), tudi z zapomnjeno izbiro (ni gostitelja, na katerega bi jo vezali).
    p(K.gostitelj("https://a b.example/x") == null, "presledek v gostitelju: neznan")
    p(K.gostitelj("https://a{b}.example/x") == null, "oklepaj v gostitelju: neznan")
    p(K.gostitelj("https:///x") == null, "prazen gostitelj: neznan")
    p(K.gostitelj("https://./x") == null, "samo pika: neznan")
    p(K.gostitelj("about:blank") == null && K.gostitelj("file:///x") == null && K.gostitelj("javascript:alert(1)") == null && K.gostitelj(null) == null && K.gostitelj("") == null, "ne-spletni naslovi: null")
    p(!K.privzetoKazalec(true, "https://a b.example/x", zaTv = false, zapomnjeno = true), "neznan gostitelj: puscice (fail-safe)")
    p(!K.privzetoKazalec(true, "about:blank", zaTv = false, zapomnjeno = true), "about:blank: puscice")
    p(!K.privzetoKazalec(true, "file:///android_asset/home.html", zaTv = false, zapomnjeno = true), "nasa domaca stran: puscice")

    // Neveljavna vrata: Chromium naslova ne odpre; neznan gostitelj (fail-safe), ne ogled.
    p(K.gostitelj("https://www.youtube.com:evil/watch") == null && !K.jeStranOgleda("https://www.youtube.com:evil/watch"), "neveljavna vrata (besedilo): neznan gostitelj")
    p(K.gostitelj("https://a.example:99999/x") == null && K.gostitelj("https://a.example:65535/x") == "a.example" && K.gostitelj("https://a.example:/x") == "a.example", "vrata: meja 65535, prazna vrata veljajo")
    p(K.gostitelj("https://[::1]x/y") == null && K.gostitelj("https://[::1]:80/y") == "[::1]" && K.gostitelj("https://[::1]:z/y") == null, "IPv6: vrata za zaklepajem")
    // N2: bliznjice OK/levo/desno/nazaj na stranih za ogled drugih gostiteljev (prej, pred F1, je zadostoval /watch kjerkoli).
    for (u in listOf("https://www.netflix.com/watch/80100172", "https://www.netflix.com/watch/80100172?trackId=1", "https://netflix.com/watch",
        "https://www.facebook.com/watch/?v=1", "https://m.facebook.com/watch", "https://www.twitch.tv/watch", "https://www.dailymotion.com/watch/x1",
        "https://WWW.NETFLIX.COM/WATCH/1", "https://www.netflix.com./watch/1", "https://www.netflix.com:443/watch/1")) {
        p(K.jeStranOgleda(u), "stran za ogled: $u")
        p(!K.privzetoKazalec(true, u, zaTv = K.stranZaTv(K.jeStranOgleda(u), false, false, false, false), zapomnjeno = null), "ogled na puscicah: $u")
    }
    for (u in listOf("https://evil.example/watch", "https://netflix.com.evil.example/watch/1", "https://evilnetflix.com/watch/1", "https://notfacebook.com/watch",
        "https://evil.example/netflix.com/watch/1", "https://evil.example/?u=netflix.com/watch/1", "https://evil.example/#netflix.com/watch/1",
        "https://netflix.com@evil.example/watch/1", "https://www.netflix.com/browse", "https://www.netflix.com/watchlist", "https://www.netflix.com/?next=/watch/1",
        "https://www.netflix.com/#/watch/1", "https://www.facebook.com/", "https://www.facebook.com/watching", "https://www.twitch.tv/directory",
        "https://www.netflix.com/watch/../browse", "https://www.netflix.com/watch/%2e%2e/browse", "https://www.dailymotion.com/")) {
        p(!K.jeStranOgleda(u), "NI stran za ogled: $u")
    }
    p(K.jeStranOgleda("https://www.youtube.com/shorts/abc") && K.jeStranOgleda("https://www.youtube.com/embed/abc") && !K.jeStranOgleda("https://www.netflix.com/shorts/abc"),
        "shorts in embed samo na YouTubu")

    println("KazalecPopravki3Test: $preverb preverb, $padlo padlo")
    if (padlo > 0) exitProcess(1)
}
