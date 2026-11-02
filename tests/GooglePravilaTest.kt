package si.safeer.tv

import si.safeer.tv.GooglePravila as G

/**
 * Zunanji pregled 2. 11. 2026: Google in YouTube samo po gostitelju. Prej je zadoscal niz kjerkoli v naslovu, zato je
 * tuja stran z »signin/v2« v poti ali gostiteljem google.zlo.example dobila izjeme za Google (brez filtra oglasov,
 * piskotki tretjih strani, samodejna okna), notyoutube.com pa preusmeritev na youtube.com/tv.
 */
fun main() {
    // Pravi Googlovi gostitelji ostanejo Google (prijava mora delati - pravilo lastnika).
    for (u in listOf("https://www.google.com/search?q=x", "https://google.com/", "https://www.google.si/", "https://google.de/",
            "https://www.google.co.uk/search?q=a", "https://www.google.com.br/", "https://mail.google.com/mail/u/0/",
            "https://accounts.google.com/v3/signin/identifier", "https://ACCOUNTS.Google.COM./x", "https://www.gstatic.com/recaptcha/x.js",
            "https://fonts.googleapis.com/css", "https://www.recaptcha.net/recaptcha/api.js", "https://accounts.youtube.com/accounts/SetSID",
            "https://www.google.com/recaptcha/api2/anchor", "https://user@www.google.com:443/x")) {
        check(G.jeGoogle(u)) { "Google: $u" }
    }
    // Tuji gostitelji z Googlovimi nizi v poti, poizvedbi ali gostitelju niso Google.
    for (u in listOf("https://zlo.example/signin/v2", "https://zlo.example/v3/signin/identifier", "https://google.zlo.example/",
            "https://www.google.zlo.example/", "https://zlo.example/?u=accounts.google.com", "https://zlo.example/google.com/search?q=x",
            "https://recaptcha.zlo.example/", "https://zlo.example/recaptcha/api.js", "https://gstatic.com.zlo.example/",
            "https://notgstatic.com/", "https://googleapis.com.zlo.example/", "https://zlo.example/x?gstatic.com",
            "https://accounts.google.zlo.example/", "https://www.google.com@zlo.example/", "https://zlo.example\\@www.google.com/",
            "https://notgoogle.com/", "https://www.youtube.com/watch?v=abcdefgh", "https://rr1.googlevideo.com/videoplayback",
            "https://i.ytimg.com/vi/x.jpg", "", "about:blank", "file:///android_asset/brave_home.html")) {
        check(!G.jeGoogle(u)) { "ni Google: $u" }
    }
    // Prijava: Googlov prijavni gostitelj ali Googlov gostitelj s prijavno potjo; nikoli samo pot.
    for (u in listOf("https://accounts.google.com/ServiceLogin", "https://accounts.google.si/", "https://myaccount.google.com/",
            "https://accounts.youtube.com/accounts/SetSID", "https://www.google.com/accounts/x", "https://www.google.com/signin/v2/identifier")) {
        check(G.jePrijava(u)) { "prijava: $u" }
    }
    for (u in listOf("https://zlo.example/signin/v2", "https://zlo.example/signin/challenge", "https://zlo.example/v3/signin",
            "https://zlo.example/?next=accounts.google.com", "https://accounts.zlo.example/", "https://www.google.com/search?q=signin/v2",
            "https://mail.google.com/mail/", "https://accounts.google.zlo.example/")) {
        check(!G.jePrijava(u)) { "ni prijava: $u" }
    }
    // YouTube po gostitelju (kot UserScriptManager.isYouTubeUrl).
    for (u in listOf("https://www.youtube.com/", "https://m.youtube.com/watch?v=abcdefgh", "https://youtube.com/", "https://youtu.be/abcdefgh")) {
        check(G.jeYoutube(u)) { "YouTube: $u" }
    }
    for (u in listOf("https://notyoutube.com/watch?v=abcdefgh", "https://youtube.com.zlo.test/", "https://myyoutu.beach.com/abcdefgh",
            "https://zlo.example/youtube.com/tv", "https://zlo.example/?u=youtu.be/abcdefgh", "https://youtu.be.zlo.test/abcdefgh")) {
        check(!G.jeYoutube(u)) { "ni YouTube: $u" }
        check(G.youtubeNaTv(u) == u) { "tuja stran ostane: $u -> ${G.youtubeNaTv(u)}" }
        check(!G.youtubeZaTv(u)) { "brez TV identitete: $u" }
    }
    // Preusmeritev YouTuba na youtube.com/tv ostane, kot je bila.
    check(G.youtubeNaTv("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5") == "https://www.youtube.com/tv#/watch?v=dQw4w9WgXcQ")
    check(G.youtubeNaTv("https://youtu.be/dQw4w9WgXcQ?t=1") == "https://www.youtube.com/tv#/watch?v=dQw4w9WgXcQ")
    check(G.youtubeNaTv("https://www.youtube.com/results?search_query=safeer+tv") == "https://www.youtube.com/tv#/search?q=safeer+tv")
    check(G.youtubeNaTv("https://www.youtube.com/") == "https://www.youtube.com/tv")
    check(G.youtubeNaTv("https://www.youtube.com/tv#/browse") == "https://www.youtube.com/tv#/browse")
    check(G.youtubeNaTv("https://music.youtube.com/watch?v=dQw4w9WgXcQ") == "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
    check(G.youtubeNaTv("https://studio.youtube.com/") == "https://studio.youtube.com/")
    check(G.youtubeNaTv("https://www.youtube.com/signin?next=https%3A%2F%2Faccounts.google.com%2F") ==
        "https://www.youtube.com/signin?next=https%3A%2F%2Faccounts.google.com%2F") { "prehod prijave ostane" }
    check(G.youtubeZaTv("https://www.youtube.com/tv") && !G.youtubeZaTv("https://music.youtube.com/") &&
        !G.youtubeZaTv("https://accounts.youtube.com/")) { "TV identiteta samo za YouTube" }
    // Gostitelj: brez uporabnika, vrat, koncne pike; »\« konca oblast kot v Chromiumu.
    check(G.gostitelj("https://u:p@WWW.Google.com.:8443/x") == "www.google.com")
    check(G.gostitelj("https://zlo.example\\@www.google.com/") == "zlo.example")
    check(G.gostitelj("javascript:alert(1)") == "" && G.gostitelj(null) == "")
    // Lastnik 2. 11. 2026 (moznost b): prijava kot Chrome na Androidu z resnicno razlicico, brez oznak WebViewa.
    val wv = "Mozilla/5.0 (Linux; Android 11; 2021/22 Philips UHD Android TV Build/RTT2.211108.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.102 Mobile Safari/537.36"
    val ua = G.uaBrezWebView(wv)
    check(!ua.contains("; wv") && !ua.contains("Version/")) { "brez oznak WebViewa: " + ua }
    check(ua.contains("Chrome/142.0.7444.102 Mobile Safari/537.36") && ua.startsWith("Mozilla/5.0 (Linux; Android 11;")) { "resnicna razlicica: " + ua }
    println("GooglePravilaTest: OK")
}
