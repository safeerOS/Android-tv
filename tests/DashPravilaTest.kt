package si.safeer.tv

import si.safeer.tv.DashPravila as D

/**
 * Zunanji pregled 2. 11. 2026: mimo filtra oglasov samo prave zahteve predvajanja - po poti (ne poizvedbi), znanem strezniku
 * licenc ali gostitelju manifesta, ki je res prisel po omrezju; oglasni gostitelj nikoli.
 */
fun main() {
    // Predvajanje DASH in Widevine ostane mimo filtra (YouTube, ponudniki TV).
    for (u in listOf("https://cdn.primer.si/live/kanal/manifest.mpd", "https://cdn.primer.si/live/kanal/manifest.mpd?token=abc",
            "https://cdn.primer.si/live/kanal/seg-00012.m4s", "https://cdn.primer.si/dash/kanal/init.mp4",
            "https://lic.drmtoday.com/license-proxy-widevine/cenc/", "https://primer.castlabs.com/x",
            "https://api.primer.si/drm/widevine", "https://api.primer.si/license?x=1", "https://widevine-proxy.primer.si/widevine/x",
            "https://license-proxy.primer.si/x")) {
        check(D.prepusti(u, "", false)) { "predvajanje: $u" }
    }
    // Niz v poizvedbi, sidru ali tujem gostitelju ni medijska zahteva.
    for (u in listOf("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?x=.mpd",
            "https://oglasi.zlo.example/a.js?u=/license", "https://oglasi.zlo.example/a.js#x.m4s",
            "https://oglasi.zlo.example/t.gif?widevine=1", "https://oglasi.zlo.example/t.gif?p=/drm/",
            "https://oglasi.zlo.example/castlabs", "https://drmtoday.com.zlo.example/x.js", "https://widevine.zlo.example/x.js",
            "https://oglasi.zlo.example/a.js?dash=/dash/", "about:blank", "")) {
        check(!D.prepusti(u, "", false)) { "ni predvajanje: $u" }
    }
    // Gostitelj manifesta (prisel po omrezju): vse njegove zahteve gredo mimo, drugi gostitelji ne.
    check(D.prepusti("https://cdn.primer.si/x/chunk?id=5", "cdn.primer.si", false)) { "gostitelj manifesta" }
    check(D.prepusti("https://CDN.primer.si./x", "cdn.primer.si", false)) { "gostitelj manifesta, velike crke in pika" }
    check(!D.prepusti("https://cdn.primer.si.zlo.example/x", "cdn.primer.si", false)) { "podobno ime ni isti gostitelj" }
    check(!D.prepusti("https://zlo.example/x", "cdn.primer.si", false)) { "drug gostitelj" }
    // Oglasni gostitelj nikoli - tudi s potjo .mpd ali kot gostitelj manifesta.
    check(!D.prepusti("https://pagead2.googlesyndication.com/x.mpd", "", true)) { "oglasni gostitelj s potjo .mpd" }
    check(!D.prepusti("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
        "pagead2.googlesyndication.com", true)) { "oglasni gostitelj kot gostitelj manifesta" }
    println("DashPravilaTest: OK")
}
