package si.safeer.tv.os

/** Diagnostika predvajanja: vnos brez kljucev, zetonov, celih URL-jev in podatkov racuna ([DiagnostikaPredvajanjaPravila]). */
fun main() {
    val P = DiagnostikaPredvajanjaPravila
    val zdaj = 1_792_960_000_000L
    check(P.velja(zdaj + 3_600_000, zdaj)) { "vklop za uro" }
    check(P.velja(zdaj + P.TRAJANJE_MS, zdaj)) { "natanko 24 h" }
    check(!P.velja(zdaj + P.TRAJANJE_MS + 1, zdaj)) { "vec kot 24 h (premaknjena ura) ne velja" }
    check(!P.velja(zdaj, zdaj) && !P.velja(0L, zdaj)) { "potekel ali nikoli vklopljen" }

    check(P.gostitelj("https://user:geslo@api.torbox.app:443/v1/api/torrents?token=abc") == "api.torbox.app") { P.gostitelj("https://user:geslo@api.torbox.app:443/v1/api/torrents?token=abc") }
    check(P.gostitelj("https://torrentio.strem.fun/realdebrid=Q2xE9f8kLmN0pR5sT7uV/manifest.json") == "torrentio.strem.fun") { "pot s kljucem odpade" }
    check(P.gostitelj("https://uporabnik.elfhosted.com/stremio/abc") == "*.elfhosted.com") { "osebni primerek: ime skrito" }
    check(P.gostitelj("https://a1b2c3d4e5f6a7b8c9d0.example.org/x") == "*.example.org") { "zeton v imenu gostitelja" }
    check(P.gostitelj("http://192.168.0.10:8080/film.mkv") == "domace-omrezje") { "domaci IP ne gre v dnevnik" }
    check(P.gostitelj("nntps://news.eweka.nl:563") == "news.eweka.nl") { "Usenet streznik" }
    check(P.gostitelj("http://127.0.0.1:41234/usenet/abc?k=1") == "ta-naprava" && P.gostitelj("http://localhost:8080/x") == "ta-naprava") { "lasten pomocnik" }
    check(P.gostitelj("magnet:?xt=urn:btih:abc") == "" && P.gostitelj(null) == "") { "brez gostitelja" }

    // Podtaknjen URL s kljucem, zeton, e-naslov in par kljuc=vrednost: v vnosu ostane samo gostitelj.
    val kljuc = "sk_" + "live_9f8e7d6c5b4a39281706f5e4"
    val zeton = "eyJhbGciOiJIUzI1NiJ9abcDEF1234567890"
    val vnos = P.vrstica("2026-11-05 19:25", "Film https://evil.example/x?apikey=$kljuc", "film",
        listOf(P.poskus("usenet", "https://nzbfinder.ws/api?apikey=$kljuc&t=movie", "ni zadetka"),
            P.poskus("debrid", "https://api.torbox.app/v1/api?token=$zeton", "HTTP 403 token=$zeton"),
            P.poskus("webtor", "https://webtor.io/x", "lastnik@primer.si prekinitev")),
        "vsi viri odpovedali; password: skrivnost123 Authorization $zeton")
    for (prepovedano in listOf(kljuc, zeton, "apikey=s", "?apikey", "/api", "skrivnost123", "lastnik@primer.si", "https://", "&t=movie"))
        check(!vnos.contains(prepovedano)) { "v vnosu ostalo: $prepovedano -> $vnos" }
    for (ostane in listOf("usenet@nzbfinder.ws (ni zadetka)", "debrid@api.torbox.app", "webtor@webtor.io", "film", "Film evil.example", "2026-11-05 19:25"))
        check(vnos.contains(ostane)) { "manjka: $ostane -> $vnos" }
    check(!vnos.contains('\n') && !vnos.contains('\t') && vnos.length <= P.NAJVEC_ZNAKOV) { "ena vrstica, omejena dolzina" }
    check(P.cisti("Naslov | z | pokoncnico") == "Naslov / z / pokoncnico") { "locilo | ostane samo nase" }
    check(P.cisti("The Lord of the Rings: The Fellowship of the Ring (2001)") == "The Lord of the Rings: The Fellowship of the Ring (2001)") { "navaden naslov nespremenjen" }

    // Izklopljeno: nic se ne zapise in vnos se sploh ne sestavi.
    var sestavljen = 0
    check(P.zapis(0L, zdaj, emptyList()) { sestavljen++; "x" } == null && sestavljen == 0) { "izklopljeno: nic" }
    check(P.zapis(zdaj - 1, zdaj, listOf("star")) { sestavljen++; "x" } == emptyList<String>() && sestavljen == 0) { "potekel rok: dnevnik izbrisan" }
    check(P.zapis(zdaj + 1000, zdaj, listOf("a")) { sestavljen++; "b" } == listOf("a", "b") && sestavljen == 1) { "vklopljeno: vnos dodan" }

    var d = emptyList<String>()
    repeat(60) { d = P.dodaj(d, "v$it") }
    check(d.size == P.NAJVEC_VNOSOV && d.first() == "v10" && d.last() == "v59") { "najvec 50, najstarejsi gredo ven" }
    println("DiagnostikaPredvajanjaPravilaTest: OK")
}
