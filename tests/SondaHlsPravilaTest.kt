package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Sonda kanala HLS do konca verige ([SondaHlsPravila]) - lastnik 25. 10. 2026: »ABC kanali, ki ne delujejo, niso skriti«. */
fun main() {
    println("Preizkus sonde HLS")
    val P = SondaHlsPravila
    val Z = OsPravila.IzidSonde.ZIV
    val M = OsPravila.IzidSonde.MRTEV
    val N = OsPravila.IzidSonde.NEGOTOVO
    val X = OsPravila.IzidSonde.NEZNANO

    // Glavni seznam ABC News Live (iptv-org, izmerjeno 25. 10. 2026: seznam 200, vse razlicice 404) - relativne poti.
    val abc = "#EXTM3U\n#EXT-X-VERSION:3\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=410386,RESOLUTION=416x234\nindex_1.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=4058752,RESOLUTION=1920x1080\nindex_5.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=1219881,RESOLUTION=960x540\nindex_3.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=815134\nindex_2.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=2535309\nindex_4.m3u8\n"
    val osnova = "https://abcnews-streams.akamaized.net/hls/live/2023563/abcnewshudson4/master.m3u8"
    preveriEnako("je seznam", true, P.jeSeznam(abc))
    preveriEnako("je seznam z BOM in presledki", true, P.jeSeznam("\uFEFF \n#EXTM3U\n"))
    preveriEnako("stran z napako ni seznam", false, P.jeSeznam("<html>404</html>"))
    preveriEnako("je glavni", true, P.jeGlavni(abc))
    preveriEnako("seznam kosov ni glavni", false, P.jeGlavni("#EXTM3U\n#EXTINF:6,\na.ts\n"))
    val r = P.razlicice(abc, osnova)
    preveriEnako("najvec razlicic", P.NAJVEC_RAZLICIC, r.size)
    // N3: Media3 zacne s prvo nasteto razlicico - sonda tudi; nato najvisja in najnizja, nato ostale po vrsti.
    preveriEnako("prva: prva nasteta (kot Media3)", "https://abcnews-streams.akamaized.net/hls/live/2023563/abcnewshudson4/index_1.m3u8", r[0])
    preveriEnako("druga: najvisja pasovna sirina", "https://abcnews-streams.akamaized.net/hls/live/2023563/abcnewshudson4/index_5.m3u8", r[1])
    preveriEnako("tretja: naslednja nasteta (najnizja je ze prva)", "https://abcnews-streams.akamaized.net/hls/live/2023563/abcnewshudson4/index_3.m3u8", r[2])
    preveriEnako("brez podvojenih", r.size, r.distinct().size)

    // Preusmeritev: relativna pot se razresi pri naslovu PO preusmeritvah (jmp2.uk -> Pluto; prej 414 pri jmp2.uk).
    val pluto = "https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv/v2/stitch/hls/channel/6508/master.m3u8?appName=web&sid=1"
    val rp = P.razlicice("#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1042180,AUDIO=\"audio\"\n1042180/playlist.m3u8?sid=abc&x=1\n", pluto)
    preveriEnako("Pluto: razlicica pri pravem strezniku", "https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv/v2/stitch/hls/channel/6508/1042180/playlist.m3u8?sid=abc&x=1", rp.single())
    preveriEnako("absolutna pot ostane", "https://drug.cdn/a.m3u8", P.razresi(osnova, "https://drug.cdn/a.m3u8"))
    preveriEnako("pot od korena", "https://abcnews-streams.akamaized.net/x/y.ts", P.razresi(osnova, "/x/y.ts"))
    preveriEnako("ni http", null, P.razresi(osnova, "skd://kljuc"))
    preveriEnako("brez sirine: po vrsti", listOf("https://a.b/1.m3u8", "https://a.b/2.m3u8"),
        P.razlicice("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1x1\n1.m3u8\n#EXT-X-STREAM-INF:RESOLUTION=2x2\n2.m3u8\n", "https://a.b/m.m3u8"))
    preveriEnako("AVERAGE-BANDWIDTH ni BANDWIDTH", "https://a.b/hi.m3u8",
        P.razlicice("#EXTM3U\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9000000,BANDWIDTH=100\nlo.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000\nhi.m3u8\n", "https://a.b/m.m3u8")[1])

    // N2: razresitev kot Media3 UriUtil (RFC 3986), ne java.net.URI. Primeri iz pregleda:
    preveriEnako("N2: samo poizvedba - isti seznam", "http://h/a/b.m3u8?t=1", P.razresi("http://h/a/b.m3u8", "?t=1"))
    preveriEnako("N2: ../ nad korenom", "http://h/x.m3u8", P.razresi("http://h/a/b.m3u8", "../../../x.m3u8"))
    preveriEnako("N2: | { } brez izjeme", "http://h/a/k|1{2}.ts", P.razresi("http://h/a/b.m3u8", "k|1{2}.ts"))
    preveriEnako("N2: poizvedba osnove se ne prenese", "https://c/p/v.m3u8", P.razresi("https://c/p/m.m3u8?hdnea=st~exp", "v.m3u8"))
    preveriEnako("N2: osnova brez poti", "http://h/v.m3u8", P.razresi("http://h", "v.m3u8"))
    // RFC 3986, 5.4.1 (obicajni) in 5.4.2 (nenavadni) primeri z osnovo http://a/b/c/d;p?q
    val b = "http://a/b/c/d;p?q"
    val rfc = listOf("g:h" to "g:h", "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/",
        "/g" to "http://a/g", "//g" to "http://g", "?y" to "http://a/b/c/d;p?y", "g?y" to "http://a/b/c/g?y",
        "#s" to "http://a/b/c/d;p?q#s", "g#s" to "http://a/b/c/g#s", "g?y#s" to "http://a/b/c/g?y#s", ";x" to "http://a/b/c/;x",
        "g;x" to "http://a/b/c/g;x", "g;x?y#s" to "http://a/b/c/g;x?y#s", "" to "http://a/b/c/d;p?q", "." to "http://a/b/c/",
        "./" to "http://a/b/c/", ".." to "http://a/b/", "../" to "http://a/b/", "../g" to "http://a/b/g", "../.." to "http://a/",
        "../../" to "http://a/", "../../g" to "http://a/g", "../../../g" to "http://a/g", "../../../../g" to "http://a/g",
        "/./g" to "http://a/g", "/../g" to "http://a/g", "g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g", "g.." to "http://a/b/c/g..",
        "..g" to "http://a/b/c/..g", "./../g" to "http://a/b/g", "./g/." to "http://a/b/c/g/", "g/./h" to "http://a/b/c/g/h",
        "g/../h" to "http://a/b/c/h", "g;x=1/./y" to "http://a/b/c/g;x=1/y", "g;x=1/../y" to "http://a/b/c/y")
    for ((ref, cilj) in rfc) preveriEnako("RFC 3986: »$ref«", cilj, P.razresiUri(b, ref))
    preveriEnako("glavni brez poti", emptyList<String>(), P.razlicice("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "https://a.b/m.m3u8"))

    // Seznam kosov: zadnji kos (v zivo najnovejsi), kljuc AES, DRM.
    val kosi = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXT-X-MEDIA-SEQUENCE:37135951\r\n" +
        "#EXT-X-KEY:METHOD=AES-128,URI=\"https://kljuc.test/k1\"\r\n#EXTINF:6,\r\nseg1.ts\r\n" +
        "#EXT-X-KEY:METHOD=AES-128,URI=\"k2.key\",KEYFORMAT=\"identity\"\r\n#EXTINF:6,\r\nseg2.ts\r\n"
    val ko = "https://cdn.test/live/index_4.m3u8?hdnea=st=1~exp=2"
    preveriEnako("zadnji kos (CRLF)", "https://cdn.test/live/seg2.ts", P.zadnjiKos(kosi, ko))
    preveriEnako("zadnji kljuc, relativen", "https://cdn.test/live/k2.key", P.kljuc(kosi, ko))
    preveriEnako("AES-128 ni DRM", false, P.drm(kosi))
    preveriEnako("METHOD=NONE ni kljuc", null, P.kljuc("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\na.ts\n", ko))
    val wv = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,AAAA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:6,\na.mp4\n"
    preveriEnako("Widevine je DRM", true, P.drm(wv))
    preveriEnako("kljuca DRM ne prenasamo", null, P.kljuc(wv, ko))
    preveriEnako("FairPlay je DRM", true, P.drm("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://abc\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"))
    preveriEnako("seznam brez kosov", null, P.zadnjiKos("#EXTM3U\n#EXT-X-TARGETDURATION:6\n", ko))
    // Prirezano branje: zadnja (odrezana) vrstica ne sme dati laznega 404.
    preveriEnako("prirezano: odrezana vrstica izpade", "https://cdn.test/live/seg1.ts",
        P.zadnjiKos("#EXTM3U\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.t", ko, prirezano = true))
    preveriEnako("prirezano s koncem vrstice: cela ostane", "https://cdn.test/live/seg2.ts",
        P.zadnjiKos("#EXTM3U\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.ts\n", ko, prirezano = true))

    // Izid razlicic: ena ziva dovolj; vse mrtve - mrtev; negotova ostane negotova.
    preveriEnako("vse 404 - mrtev", M, P.izidRazlicic(listOf(M, M, M, M), 4))
    preveriEnako("ena ziva - ziv", Z, P.izidRazlicic(listOf(M, Z), 4))
    preveriEnako("mrtva in negotova - negotovo", N, P.izidRazlicic(listOf(M, N), 2))
    preveriEnako("mrtva in neznana - ne vemo", X, P.izidRazlicic(listOf(M, X), 2))
    preveriEnako("nic preverjenega - ne vemo", X, P.izidRazlicic(emptyList(), 4))
    // S1: rok ali napaka pred zadnjo razlicico - nepreverjena je lahko ziva.
    preveriEnako("S1: 1 od 4 preverjena (404), rok - ne vemo", X, P.izidRazlicic(listOf(M), 4))
    preveriEnako("S1: 3 od 4 mrtve - ne vemo", X, P.izidRazlicic(listOf(M, M, M), 4))
    preveriEnako("kos 404 - naslednja razlicica", true, P.naslednjaRazlicicaPoKosu(404))
    preveriEnako("kos 403 (geo) - cel kanal", false, P.naslednjaRazlicicaPoKosu(403))
    preveriEnako("N1: 403 z Range - ponovi brez", true, P.ponoviBrezRange(403))
    preveriEnako("N1: 400 z Range - ponovi brez", true, P.ponoviBrezRange(400))
    preveriEnako("N1: 404 - ne", false, P.ponoviBrezRange(404))
    preveriEnako("N1: 416 - ne (ziv)", false, P.ponoviBrezRange(416))
    preveriEnako("N1: 503 - ne", false, P.ponoviBrezRange(503))

    // Kos: 206 (Range) ziv, 403 (geografsko zaprt) mrtev, 416 (Range ni sprejet) ziv, 5xx negotovo.
    preveriEnako("kos 206", Z, P.izidKosa(206))
    preveriEnako("kos 200", Z, P.izidKosa(200))
    preveriEnako("kos 403 (geo)", M, P.izidKosa(403))
    preveriEnako("kos 404", M, P.izidKosa(404))
    preveriEnako("kos 416", Z, P.izidKosa(416))
    preveriEnako("kos 503", N, P.izidKosa(503))
    preveriEnako("kos 429 ne vemo", X, P.izidKosa(429))

    println(if (napak == 0) "Sonda HLS: vse v redu" else "Sonda HLS: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
