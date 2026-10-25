package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Webtor rezerva: naslovi dodatkov s poverilnico ([SkrivnostiPravila]) - prepoznava, nadomestek, prikaz. */
fun main() {
    println("Preizkus skrivnih naslovov dodatkov")
    val S = SkrivnostiPravila
    val zeton = "Ab12Cd34Ef56Gh78Ij90Kl"
    val webtor = "https://webtor.io/token/$zeton/stremio/manifest.json"
    preveriEnako("Webtor: predpona do zetona", "https://webtor.io/token/$zeton", S.skrivnaPredpona(webtor))
    preveriEnako("Webtor: kratek zeton za /token/", "https://webtor.io/token/x1", S.skrivnaPredpona("https://webtor.io/token/x1/stremio/manifest.json"))
    preveriEnako("Webtor: kratka povezava /s/<koda>", "https://webtor.io/s/AbC12", S.skrivnaPredpona("https://webtor.io/s/AbC12/manifest.json"))
    preveriEnako("/s/ drugje ni skrivnost", null, S.skrivnaPredpona("https://example.org/s/abc/manifest.json"))
    val rdKljuc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP"
    preveriEnako("Torrentio z debrid kljucem", "https://torrentio.strem.fun/sort=qualitysize|realdebrid=$rdKljuc",
        S.skrivnaPredpona("https://torrentio.strem.fun/sort=qualitysize|realdebrid=$rdKljuc/manifest.json"))
    preveriEnako("Torrentio brez kljuca ni skrivnost", null,
        S.skrivnaPredpona("https://torrentio.strem.fun/providers=yts,eztv|qualityfilter=480p/manifest.json"))
    preveriEnako("Torrentio, odstotkovno kodirano", true, S.jeSkrivenNaslov("https://torrentio.strem.fun/providers%3Dyts%7Crealdebrid%3DK1/manifest.json"))
    preveriEnako("Torrentio tok z debrid kljucem v poti", "https://torrentio.strem.fun/realdebrid/$rdKljuc",
        S.skrivnaPredpona("https://torrentio.strem.fun/realdebrid/$rdKljuc/0123456789abcdef0123456789abcdef01234567/null/0/film.mkv"))
    preveriEnako("Cinemeta ni skrivnost", null, S.skrivnaPredpona("https://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("nastavitve base64 so skrivnost", true,
        S.jeSkrivenNaslov("https://comet.example.org/eyJpbmRleGVycyI6WyJ0b3JyZW50aW8iXSwiZGVicmlkQXBpS2V5IjoiYWJjIn0=/manifest.json"))
    preveriEnako("JSON nastavitve s kljucem", true, S.jeSkrivenNaslov("https://a.example.org/%7B%22apiKey%22%3A%22x%22%7D/manifest.json"))
    preveriEnako("uporabnik in geslo", "https://u:g@addon.example.org", S.skrivnaPredpona("https://u:g@addon.example.org/manifest.json"))
    preveriEnako("kratek odsek s stevkami ni skrivnost", null, S.skrivnaPredpona("https://addon.example.org/v2/catalog/tt1234567/manifest.json"))
    preveriEnako("magnet ni naslov", null, S.skrivnaPredpona("magnet:?xt=urn:btih:abc"))

    val p = "https://webtor.io/token/$zeton"
    val z = S.nadomestek(p)!!
    preveriEnako("nadomestek: gostitelj ostane, brez zetona", "https://webtor.io/safeer-skrito-" + S.odtis(p), z)
    preveriEnako("nadomestek ne vsebuje zetona", false, z.contains(zeton))
    preveriEnako("odtis 16 hex", true, Regex("^[0-9a-f]{16}$").matches(S.odtis(p)))
    val tok = "https://webtor.io/token/$zeton/stremio/resolve/aaa.bbb.ccc"
    preveriEnako("tok v nadomestek", "$z/stremio/resolve/aaa.bbb.ccc", S.vNadomestek(tok))
    preveriEnako("manifest v nadomestek", "$z/stremio/manifest.json", S.vNadomestek(webtor))
    preveriEnako("brez skrivnosti nespremenjen", "https://v3-cinemeta.strem.io/manifest.json", S.vNadomestek("https://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("nadomestek prepoznan", true, S.jeNadomestek(S.vNadomestek(tok)))
    preveriEnako("nadomestna osnova", z, S.nadomestnaOsnova("$z/stremio/manifest.json"))
    preveriEnako("nadomestek nima skrivnosti", null, S.skrivnaPredpona("$z/stremio/manifest.json"))
    preveriEnako("ponaredek nadomestka (kratek odtis) ni nadomestek", false, S.jeNadomestek("https://webtor.io/safeer-skrito-abc/x"))
    preveriEnako("ne gre med vse naprave: pravi", false, S.smeMedVseNaprave(webtor))
    preveriEnako("ne gre med vse naprave: nadomestek", false, S.smeMedVseNaprave(S.vNadomestek(webtor)))
    preveriEnako("javni dodatek gre", true, S.smeMedVseNaprave("https://v3-cinemeta.strem.io/manifest.json"))

    preveriEnako("prikaz pravega", "https://webtor.io/…/stremio/manifest.json", S.zaPrikaz(webtor))
    preveriEnako("prikaz nadomestka", "https://webtor.io/…/stremio/manifest.json", S.zaPrikaz(S.vNadomestek(webtor)))
    preveriEnako("prikaz uporabnika", "https://addon.example.org/…/manifest.json", S.zaPrikaz("https://u:g@addon.example.org/manifest.json"))
    preveriEnako("prikaz javnega nespremenjen", "https://v3-cinemeta.strem.io/manifest.json", S.zaPrikaz("https://v3-cinemeta.strem.io/manifest.json"))

    preveriEnako("prejeti: samo skrivni, veljavni, brez dvojnikov", listOf(webtor),
        S.prejetiDodatki(listOf(webtor, null, "https://v3-cinemeta.strem.io/manifest.json", webtor, "ftp://webtor.io/token/x/y", "https://webtor.io/token/a\nb/c")))
    preveriEnako("prejeti: najvec 20", 20, S.prejetiDodatki((1..30).map { "https://webtor.io/token/zeton$it/stremio/manifest.json" }).size)

    preveriEnako("odkodiraj", "a/b|", S.odkodiraj("a%2Fb%7C"))
    preveriEnako("odkodiraj UTF-8", "š", S.odkodiraj("%C5%A1"))
    preveriEnako("odkodiraj: neveljavno ostane", "100%%zz%+1", S.odkodiraj("100%%zz%+1"))

    if (napak == 0) println("SkrivnostiPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
