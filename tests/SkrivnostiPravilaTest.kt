package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
private fun b64url(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

/** Webtor rezerva: naslovi dodatkov s poverilnico ([SkrivnostiPravila]) - prepoznava v obe smeri, nadomestek, prehod. */
fun main() {
    println("Preizkus skrivnih naslovov dodatkov")
    val S = SkrivnostiPravila
    val zeton = "Ab12Cd34Ef56Gh78Ij90Kl"
    val webtor = "https://webtor.io/token/$zeton/stremio/manifest.json"
    val osnovaW = "https://webtor.io/token/$zeton/stremio"

    // --- dodatek: cela osnova
    preveriEnako("Webtor: cela osnova", osnovaW, S.skrivnaPredponaDodatka(webtor))
    preveriEnako("Webtor: kratek zeton za /token/", "https://webtor.io/token/x1/stremio", S.skrivnaPredponaDodatka("https://webtor.io/token/x1/stremio/manifest.json"))
    preveriEnako("Webtor: kratka povezava /s/<koda>", "https://webtor.io/s/AbC12", S.skrivnaPredponaDodatka("https://webtor.io/s/AbC12/manifest.json"))
    preveriEnako("/s/ drugje ni skrivnost", null, S.skrivnaPredponaDodatka("https://example.org/s/abc/manifest.json"))
    val rd = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP"
    preveriEnako("Torrentio z debrid kljucem", "https://torrentio.strem.fun/sort=qualitysize|realdebrid=$rd",
        S.skrivnaPredponaDodatka("https://torrentio.strem.fun/sort=qualitysize|realdebrid=$rd/manifest.json"))
    preveriEnako("Torrentio brez kljuca ni skrivnost", null,
        S.skrivnaPredponaDodatka("https://torrentio.strem.fun/providers=yts,eztv|qualityfilter=480p|debridoptions=nocatalog/manifest.json"))
    preveriEnako("Torrentio, odstotkovno kodirano", true, S.jeSkrivenNaslov("https://torrentio.strem.fun/providers%3Dyts%7Crealdebrid%3DK1/manifest.json"))
    preveriEnako("TorBox kljuc (UUID) kot polje", true, S.jeSkrivenNaslov("https://torrentio.strem.fun/torbox=0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/manifest.json"))
    preveriEnako("kljuc za dolgim neskrivnim odsekom: cela osnova",
        "https://a.example.org/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/realdebrid=K1",
        S.skrivnaPredponaDodatka("https://a.example.org/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/realdebrid=K1/manifest.json"))
    preveriEnako("Cinemeta ni skrivnost", null, S.skrivnaPredponaDodatka("https://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("JSON: tmdbApiKey", true, S.jeSkrivenNaslov("https://a.example.org/%7B%22tmdbApiKey%22%3A%22x1%22%7D/manifest.json"))
    preveriEnako("JSON: rpdbkey", true, S.jeSkrivenNaslov("https://a.example.org/%7B%22rpdbkey%22%3A%22t0-abc%22%7D/manifest.json"))
    preveriEnako("JSON: traktAccessToken", true, S.jeSkrivenNaslov("https://a.example.org/%7B%22traktAccessToken%22%3A%22q%22%7D/manifest.json"))
    preveriEnako("JSON: mdblistApiKey v base64", true, S.jeSkrivenNaslov("https://a.example.org/" + b64url("{\"mdblistApiKey\":\"abc123\",\"lang\":\"sl\"}") + "/manifest.json"))
    preveriEnako("JSON brez kljuca ni skrivnost", null, S.skrivnaPredponaDodatka("https://a.example.org/%7B%22language%22%3A%22sl%22%7D/manifest.json"))
    preveriEnako("JSON: prazen kljuc ni skrivnost", null, S.skrivnaPredponaDodatka("https://a.example.org/%7B%22apiKey%22%3A%22%22%7D/manifest.json"))
    preveriEnako("base64 brez kljuca ni skrivnost (S3)", null,
        S.skrivnaPredponaDodatka("https://youtubio.example/" + b64url("{\"catalogs\":[\"top\",\"new\"],\"language\":\"sl\",\"region\":\"SI\"}") + "/manifest.json"))
    val razsekan = b64("{\"debridApiKey\":\"tajno-1234567890\",\"catalogs\":[\"a\",\"b\"],\"x\":\"??>>??>>\"}")
    preveriEnako("navaden base64 s »/« (razsekan): cela osnova", true, razsekan.contains('/') &&
        S.skrivnaPredponaDodatka("https://comet.example.org/$razsekan/manifest.json") == "https://comet.example.org/$razsekan")
    preveriEnako("UUID ni skrivnost (S3)", null, S.skrivnaPredponaDodatka("https://a.example.org/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/manifest.json"))
    preveriEnako("hash 40 hex ni skrivnost", null, S.skrivnaPredponaDodatka("https://a.example.org/" + "a1b2c3d4e5".repeat(4) + "/manifest.json"))
    preveriEnako("nakljucen zeton je skrivnost", osnovaW.replace("webtor.io/token/$zeton/stremio", "a.example.org/q8Zr2LmX0pVt7WbN4kHs1D"),
        S.skrivnaPredponaDodatka("https://a.example.org/q8Zr2LmX0pVt7WbN4kHs1D/manifest.json"))
    preveriEnako("uporabnik in geslo", "https://u:g@addon.example.org", S.skrivnaPredponaDodatka("https://u:g@addon.example.org/manifest.json"))
    preveriEnako("kratek odsek s stevkami ni skrivnost", null, S.skrivnaPredponaDodatka("https://addon.example.org/v2/catalog/tt1234567/manifest.json"))
    preveriEnako("magnet ni naslov", null, S.skrivnaPredponaDodatka("magnet:?xt=urn:btih:abc"))

    // --- tok: do zadnjega skrivnega odseka, zeton v poizvedbi
    preveriEnako("tok Torrentio z kljucem v poti", "https://torrentio.strem.fun/realdebrid/$rd",
        S.skrivnaPredponaToka("https://torrentio.strem.fun/realdebrid/$rd/0123456789abcdef0123456789abcdef01234567/null/0/film.mkv"))
    preveriEnako("tok TorBox (UUID za /torbox/)", "https://t.example/torbox/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
        S.skrivnaPredponaToka("https://t.example/torbox/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/" + "ab".repeat(20) + "/1"))
    preveriEnako("tok: zeton v poizvedbi -> cel naslov", "https://cdn.example/v/1.mkv?token=abc&e=1",
        S.skrivnaPredponaToka("https://cdn.example/v/1.mkv?token=abc&e=1#t=5"))
    preveriEnako("tok: api_key v poizvedbi", true, S.skrivnaPredponaToka("https://cdn.example/v/1.mkv?api_key=abc") != null)
    preveriEnako("tok CDN z dolgim id-jem ni skrivnost za navadno kartico (S3)", false,
        S.odstraniTokObShranjevanju("https://addon.example.org", "https://cdn.example/d/ABCDEFGHIJ1234567890/film.mkv"))
    preveriEnako("tok kartice s poverilnico se odstrani", true,
        S.odstraniTokObShranjevanju(S.vNadomestek(webtor).removeSuffix("/manifest.json"), "https://cdn.example/v/1.mkv?token=abc"))
    preveriEnako("nadomestek toka ostane", false,
        S.odstraniTokObShranjevanju(S.vNadomestek(webtor).removeSuffix("/manifest.json"), S.vNadomestek(webtor).removeSuffix("/manifest.json") + "/resolve/a.b.c"))
    preveriEnako("navaden tok navadne kartice ostane", false, S.odstraniTokObShranjevanju("https://v3-cinemeta.strem.io", "https://cdn.example/a.mp4"))

    // --- nadomestek
    val z = S.nadomestek(osnovaW)!!
    preveriEnako("nadomestek: gostitelj ostane, brez zetona", "https://webtor.io/safeer-skrito-" + S.odtis(osnovaW), z)
    preveriEnako("nadomestek ne vsebuje zetona", false, z.contains(zeton))
    preveriEnako("odtis 16 hex", true, Regex("^[0-9a-f]{16}$").matches(S.odtis(osnovaW)))
    preveriEnako("manifest v nadomestek", "$z/manifest.json", S.vNadomestek(webtor))
    preveriEnako("manifest s poizvedbo v nadomestek", "$z/manifest.json", S.vNadomestek("$webtor?x=1"))
    preveriEnako("brez skrivnosti nespremenjen", "https://v3-cinemeta.strem.io/manifest.json", S.vNadomestek("https://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("nadomestek prepoznan", true, S.jeNadomestek(S.vNadomestek(webtor)))
    preveriEnako("nadomestna osnova", z, S.nadomestnaOsnova("$z/resolve/a.b.c"))
    preveriEnako("nadomestek nima skrivnosti", null, S.skrivnaPredponaDodatka("$z/manifest.json"))
    preveriEnako("ponaredek nadomestka (kratek odtis) ni nadomestek", false, S.jeNadomestek("https://webtor.io/safeer-skrito-abc/x"))
    preveriEnako("ne gre med vse naprave: pravi", false, S.smeMedVseNaprave(webtor))
    preveriEnako("ne gre med vse naprave: nadomestek", false, S.smeMedVseNaprave(S.vNadomestek(webtor)))
    preveriEnako("javni dodatek gre", true, S.smeMedVseNaprave("https://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("dodatek z UUID gre (S3)", true, S.smeMedVseNaprave("https://a.example.org/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0/manifest.json"))

    preveriEnako("prikaz pravega", "https://webtor.io/…/manifest.json", S.zaPrikaz(webtor))
    preveriEnako("prikaz nadomestka", "https://webtor.io/…/manifest.json", S.zaPrikaz(S.vNadomestek(webtor)))
    preveriEnako("prikaz uporabnika", "https://addon.example.org/…/manifest.json", S.zaPrikaz("https://u:g@addon.example.org/manifest.json"))
    preveriEnako("prikaz javnega nespremenjen", "https://v3-cinemeta.strem.io/manifest.json", S.zaPrikaz("https://v3-cinemeta.strem.io/manifest.json"))

    preveriEnako("prejeti: samo skrivni, veljavni, brez dvojnikov", listOf(webtor),
        S.prejetiDodatki(listOf(webtor, null, "https://v3-cinemeta.strem.io/manifest.json", webtor, "ftp://webtor.io/token/x/y", "https://webtor.io/token/a\nb/c")))
    preveriEnako("prejeti: najvec 20", 20, S.prejetiDodatki((1..30).map { "https://webtor.io/token/zeton$it/stremio/manifest.json" }).size)

    // --- prehod (S1)
    val zam = mapOf(osnovaW to z)
    val vnosi = mapOf<String, Any?>(
        "viri" to "[{\"tip\":\"stremio\",\"naslov\":\"" + webtor.replace("/", "\\/") + "\"}]",
        "zasebni|$osnovaW" to false,
        "ime:$osnovaW" to "Webtor",
        "nedavno" to "[{\"id\":\"stremio|movie|tt1|$osnovaW\"}]",
        "nabor" to setOf("stremio|movie|tt2|$osnovaW", "drugo"),
        "stevec" to 3L,
        "javno" to "https://v3-cinemeta.strem.io")
    val po = S.preseliVnose(vnosi, zam)!!
    preveriEnako("prehod: v nobenem kljucu ni zetona", true, po.keys.none { it.contains(zeton) })
    preveriEnako("prehod: v nobeni vrednosti ni zetona", true, po.values.none { it.toString().contains(zeton) })
    preveriEnako("prehod: kljuc zamenjan", false, po["zasebni|$z"])
    preveriEnako("prehod: escaped »\\/«", "[{\"tip\":\"stremio\",\"naslov\":\"" + "$z/manifest.json".replace("/", "\\/") + "\"}]", po["viri"])
    preveriEnako("prehod: nabor nizov", setOf("stremio|movie|tt2|$z", "drugo"), po["nabor"])
    preveriEnako("prehod: drugo ostane", 3L, po["stevec"])
    preveriEnako("prehod: brez zetona ni spremembe", null, S.preseliVnose(mapOf("a" to "b", "n" to 1), zam))
    preveriEnako("prehod je idempotenten", null, S.preseliVnose(po, zam))
    preveriEnako("vsebuje pravi (escaped)", true, S.vsebujeKatero(webtor.replace("/", "\\/"), listOf(osnovaW)))
    preveriEnako("ne vsebuje", false, S.vsebujeKatero("nic", listOf(osnovaW)))

    preveriEnako("odkodiraj", "a/b|", S.odkodiraj("a%2Fb%7C"))
    preveriEnako("odkodiraj UTF-8", "š", S.odkodiraj("%C5%A1"))
    preveriEnako("odkodiraj: neveljavno ostane", "100%%zz%+1", S.odkodiraj("100%%zz%+1"))

    // Pregled U5: dodatek s poverilnico samo po https (vnos, prejem, usklajevanje).
    preveriEnako("skrivni dodatek po http ni veljaven", false, S.veljavenSkrivniDodatek("http://webtor.io/token/zeton12345/stremio/manifest.json"))
    preveriEnako("skrivni dodatek po https je veljaven", true, S.veljavenSkrivniDodatek("https://webtor.io/token/zeton12345/stremio/manifest.json"))
    preveriEnako("prejeti: http izpade", emptyList<String>(), S.prejetiDodatki(listOf("http://webtor.io/token/zeton12345/stremio/manifest.json")))
    // Pregled U8: lokalni odtis s kljucem naprave - drug kljuc da drug odtis, isti kljuc isti; brez kljuca ga ni mogoce ponoviti.
    val k1 = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    val k2 = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
    preveriEnako("odtis s kljucem: ponovljiv", S.odtisSKljucem(k1, "x"), S.odtisSKljucem(k1, "x"))
    preveriEnako("odtis s kljucem: drug kljuc, drug odtis", false, S.odtisSKljucem(k1, "x") == S.odtisSKljucem(k2, "x"))
    preveriEnako("odtis s kljucem ni neslan odtis", false, S.odtisSKljucem(k1, "x") == S.odtis("x"))
    preveriEnako("odtis s kljucem: 16 znakov hex", true, S.odtisSKljucem(k1, "x").matches(Regex("^[0-9a-f]{16}$")))
    // Pregled U12: zapisejo se samo spremenjeni vnosi.
    preveriEnako("spremenjeni vnosi", mapOf("b" to "2x", "c" to 3), S.spremenjeniVnosi(mapOf("a" to "1", "b" to "2"), mapOf("a" to "1", "b" to "2x", "c" to 3)))

    // Pomocnik Windows, naloga 21 A1 (25. 10. 2026): lastnikov Webtor je kratka povezava /s/<koda>, tokovi pa
    // »/token/<t>/stremio/resolve/<JWT>« - ne zacnejo se z osnovo dodatka. JWT (exp, hash, idx) ni zeton: skrivnost je pred
    // /resolve/, nadomestek ohrani /resolve/<JWT> (kot SkrivniDodatki.zakrijTok: nadomestek predpone + preostanek).
    val hashA1 = "a".repeat(40)
    val jwtA1 = b64url("{\"alg\":\"HS256\"}") + "." + b64url("{\"exp\":1793000000,\"hash\":\"$hashA1\",\"idx\":0}") + ".podpis"
    val uuidA1 = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    val tokA1 = "https://webtor.io/token/$uuidA1/stremio/resolve/$jwtA1"
    val predponaA1 = S.skrivnaPredponaToka(tokA1)
    preveriEnako("Webtor tok kratke povezave: skrivnost je pred /resolve/", "https://webtor.io/token/$uuidA1", predponaA1)
    val zakritA1 = (predponaA1?.let { p -> S.nadomestek(p)?.let { it + tokA1.substring(p.length) } }).orEmpty()
    preveriEnako("Webtor tok kratke povezave: nadomestek brez zetona", false, uuidA1 in zakritA1 || zakritA1.isEmpty())
    preveriEnako("Webtor tok kratke povezave: nadomestek je /resolve/", true, WebtorPravila.jeResolveNaslov(zakritA1))
    preveriEnako("Webtor tok kratke povezave: JWT iz nadomestka", WebtorPravila.Kljuc(hashA1, 0), WebtorPravila.izJwt(zakritA1))
    preveriEnako("JWT za /resolve/ drugje ostane skrivnost", "https://cdn.example/x/resolve/$jwtA1",
        S.skrivnaPredponaToka("https://cdn.example/x/resolve/$jwtA1"))
    // Pregled 26, T6: izjema samo za JWT natanko za /stremio/resolve/; drugje na webtor.io in za JWT ostane splosno pravilo.
    preveriEnako("Webtor: JWT brez /stremio/ pred resolve ostane skrivnost", "https://webtor.io/x/resolve/$jwtA1",
        S.skrivnaPredponaToka("https://webtor.io/x/resolve/$jwtA1"))
    val zaJwt = "Q8zR2LmX0pVt7WbN4kHs1Dxx"
    preveriEnako("Webtor: zeton ZA JWT ostane skrivnost", "https://webtor.io/token/$uuidA1/stremio/resolve/$jwtA1/$zaJwt",
        S.skrivnaPredponaToka("https://webtor.io/token/$uuidA1/stremio/resolve/$jwtA1/$zaJwt"))
    preveriEnako("Webtor: velike crke RESOLVE", "https://webtor.io/token/$uuidA1", S.skrivnaPredponaToka("https://webtor.io/token/$uuidA1/Stremio/RESOLVE/$jwtA1"))
    preveriEnako("Webtor: zeton namesto JWT za resolve ostane skrivnost", "https://webtor.io/token/$uuidA1/stremio/resolve/$zaJwt",
        S.skrivnaPredponaToka("https://webtor.io/token/$uuidA1/stremio/resolve/$zaJwt"))

    if (napak == 0) println("SkrivnostiPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
