package si.safeer.tv

/**
 * Vedenje blokiranja oglasov, pribito s primeri.
 *
 * Namen: pred ciscenjem posnamemo, kaj brskalnik danes blokira in kaj spusti, da po
 * ciscenju lahko dokazemo, da se ni spremenilo nic, kar steje. Posebej so pribiti
 * pojavna in podtaknjena okna (popunder, in-page push), ker jih ne smemo izgubiti.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private data class Primer(
    val naslov: String,
    val blokiran: Boolean,
    val opis: String,
    val glavniOkvir: Boolean = false
)

private val PRIMERI = listOf(
    // --- oglasni in sledilni strezniki ---
    Primer("https://doubleclick.net/ad.js", true, "oglasni streznik doubleclick"),
    Primer("https://static.doubleclick.net/slika.png", true, "poddomena oglasnega streznika"),
    Primer("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", true, "googlesyndication"),
    Primer("https://criteo.com/beacon", true, "criteo"),
    Primer("https://taboola.com/widget", true, "taboola"),
    Primer("https://scorecardresearch.com/beacon.js", true, "scorecardresearch"),
    Primer("https://mc.yandex.ru/watch/1", true, "yandex metrika"),

    // --- pojavna in podtaknjena okna: tega ne smemo izgubiti ---
    Primer("https://popads.net/pop.js", true, "popads"),
    Primer("https://popcash.net/p.js", true, "popcash"),
    Primer("https://propu.sh/ntfc.php", true, "propu.sh (in-page push)"),
    Primer("https://onclickmega.com/x", true, "onclickmega"),
    Primer("https://onclickalgo.com/x", true, "onclickalgo"),
    Primer("https://highperformancegate.com/x", true, "highperformancegate"),
    Primer("https://rollerads.com/x", true, "rollerads"),
    Primer("https://pushground.com/x", true, "pushground"),
    Primer("https://neznanastran.si/js/popunder.min.js", true, "pot s popunder"),
    Primer("https://neznanastran.si/monetag/loader.js", true, "pot z monetag"),

    // --- vsebinska blokada: stavnice in odrasle vsebine ---
    Primer("https://bet365.com/", true, "stavnica bet365 (glavni okvir)", glavniOkvir = true),
    Primer("https://1xbet.com/si", true, "stavnica 1xbet (glavni okvir)", glavniOkvir = true),
    Primer("https://vulkanvegas.com/", true, "igralnica", glavniOkvir = true),
    Primer("https://chaturbate.com/", true, "stran za odrasle", glavniOkvir = true),

    // --- zascita pred odkrivanjem razhroscevalnika ---
    Primer("https://nekastran.si/disable-devtool.min.js", true, "disable-devtool"),
    Primer("https://nekastran.si/js/devtools-detector.js", true, "devtools-detector"),

    // --- splosne oglasne poti pri nezaupanja vrednih gostiteljih ---
    Primer("https://neznanastran.si/ads.js", true, "pot /ads.js"),
    Primer("https://neznanastran.si/pixel.gif", true, "sledilna pika"),
    Primer("https://neznanastran.si/adservice.php", true, "adservice"),

    // --- kar mora ostati dosegljivo ---
    Primer("https://www.youtube.com/watch?v=abc", false, "youtube"),
    Primer("https://m.youtube.com/watch?v=abc", false, "mobilni youtube"),
    Primer("https://music.youtube.com/", false, "youtube music"),
    Primer("https://klik.nlb.si/login", false, "banka NLB"),
    Primer("https://www.rtvslo.si/slika.jpg", false, "rtvslo"),
    Primer("https://24ur.com/clanek", false, "24ur"),
    Primer("https://wikipedia.org/wiki/Slovenija", false, "wikipedija"),
    Primer("https://tvvzivo.example/watch.js", false, "predvajalnik strani"),
    Primer("https://image.tmdb.org/t/p/w500/a.jpg", false, "plakati TMDB"),
    Primer("https://core.example-cdn.net/seg1.ts", false, "segment videa"),
    Primer("https://rr1---sn-abc.googlevideo.com/videoplayback?id=1", false, "video tok"),

    // --- video tok, ki je v resnici oglas ---
    Primer("https://rr1---sn-abc.googlevideo.com/videoplayback?id=1&oad=1", true, "oglasni video tok"),
    Primer("https://www.youtube.com/pagead/adview", true, "oglasna pot na zaupanja vrednem gostitelju")
)

fun main() {
    println("== vedenje blokiranja oglasov ==")
    AdBlockEngine.isEnabled = true

    for (p in PRIMERI) {
        val odgovor = AdBlockEngine.handleIntercept(p.naslov, null, null, p.glavniOkvir)
        val blokiran = odgovor != null
        preveri(
            "%-46s %s".format(p.opis, if (p.blokiran) "blokiran" else "spuščen"),
            blokiran == p.blokiran
        )
    }

    // Izjema za strezniki licenc (DRM) velja po domeni, ne po podnizu v imenu gostitelja.
    println()
    println("== DRM izjema po domeni ==")
    fun blokiran(u: String) = AdBlockEngine.handleIntercept(u, null, null, false) != null
    preveri("license.widevine.com spuščen", !blokiran("https://license.widevine.com/ads.js"))
    preveri("poddomena drmtoday.com spuščena", !blokiran("https://lic.drmtoday.com/ads.js"))
    preveri("widevine.napadalec.example blokiran", blokiran("https://widevine.napadalec.example/ads.js"))
    preveri("fake-widevine-ads.com blokiran", blokiran("https://fake-widevine-ads.com/ads.js"))
    preveri("notdrmtoday.evil blokiran", blokiran("https://notdrmtoday.evil/ads.js"))

    // Blokirana zahteva po podatkih propade (kot pri vsakem blokatorju); stran, slika, slog in skripta dobijo prazen odgovor.
    println()
    println("== odgovor na blokirano zahtevo ==")
    fun vrsta(naslov: String, accept: String?, glavni: Boolean = false) = AdBlockEngine.vrstaBlokade(naslov.lowercase(), accept, glavni)
    preveri("fetch po podatkih propade", vrsta("https://www.example.com/_xa/ads_batch?ads=true&x=1", "*/*") == AdBlockEngine.Blokada.NAPAKA)
    preveri("XMLHttpRequest (jQuery) propade", vrsta("https://ads.example.net/serve?zone=3", "application/json, text/javascript, */*; q=0.01") == AdBlockEngine.Blokada.NAPAKA)
    preveri("merilni zahtevek propade", vrsta("https://stats.example.net/g/collect?v=2", "*/*") == AdBlockEngine.Blokada.NAPAKA)
    preveri("oglasni tok videa propade", vrsta("https://rr1.example.com/videoplayback?ctier=l&x=1", "*/*") == AdBlockEngine.Blokada.NAPAKA)
    preveri("skripta dobi prazen odgovor", vrsta("https://static.example.net/embeddedads.es6.min.js?v=3", "*/*") == AdBlockEngine.Blokada.PRAZNO)
    preveri("slika dobi prazen odgovor", vrsta("https://ads.example.net/pixel?id=1", "image/avif,image/webp,image/apng,*/*;q=0.8") == AdBlockEngine.Blokada.PRAZNO)
    preveri("slika po koncnici", vrsta("https://ads.example.net/b/1.GIF", "*/*") == AdBlockEngine.Blokada.PRAZNO)
    preveri("slog dobi prazen odgovor", vrsta("https://ads.example.net/slog", "text/css,*/*;q=0.1") == AdBlockEngine.Blokada.PRAZNO)
    preveri("okvir dobi prazen odgovor", vrsta("https://ads.example.net/frame?x=1", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") == AdBlockEngine.Blokada.PRAZNO)
    preveri("glavna stran dobi prazen odgovor", vrsta("https://ads.example.net/landing", "*/*", glavni = true) == AdBlockEngine.Blokada.PRAZNO)
    preveri("neznana vrsta zahteve ostane po starem", vrsta("https://ads.example.net/serve?zone=3", null) == AdBlockEngine.Blokada.PRAZNO && vrsta("https://ads.example.net/serve", "  ") == AdBlockEngine.Blokada.PRAZNO)
    preveri("oglasne poti YouTuba dobijo prazen JSON", vrsta("https://www.youtube.com/pagead/interaction/?x=1", "*/*") == AdBlockEngine.Blokada.PRAZEN_JSON &&
        vrsta("https://www.youtube.com/api/stats/ads?x=1", "*/*") == AdBlockEngine.Blokada.PRAZEN_JSON && vrsta("https://www.youtube.com/get_midroll_info?x=1", "*/*") == AdBlockEngine.Blokada.PRAZEN_JSON)
    preveri("naslov z json pri Googlu dobi prazen JSON", vrsta("https://www.youtube.com/youtubei/v1/log_event?alt=json", "*/*") == AdBlockEngine.Blokada.PRAZEN_JSON &&
        vrsta("https://googleads.g.doubleclick.net/x?format=json", "*/*") == AdBlockEngine.Blokada.PRAZEN_JSON)
    preveri("naslov z json drugod propade (izmisljen odgovor YouTuba bi stran zmedel)", vrsta("https://ads.example.net/config.json", "*/*") == AdBlockEngine.Blokada.NAPAKA &&
        vrsta("https://www.example.com/_xa/ads?zone=3&data=%7B%22json%22%3A1%7D", "*/*") == AdBlockEngine.Blokada.NAPAKA)
    preveri("ime strezaja, ki se le konca kot Googlov, ni Googlov", vrsta("https://notgoogle.com.example.net/a?alt=json", "*/*") == AdBlockEngine.Blokada.NAPAKA &&
        vrsta("https://fakeyoutube.com/a?alt=json", "*/*") == AdBlockEngine.Blokada.NAPAKA)
    // Odgovor sam: propadla zahteva ima tok, ki ob branju javi napako; prazen odgovor se prebere do konca.
    val propadla = AdBlockEngine.handleIntercept("https://doubleclick.net/serve?zone=3", "https://www.example.com/", "*/*", false)
    preveri("propadla zahteva: branje javi napako", propadla != null && try { propadla.data.read(); false } catch (_: java.io.IOException) { true })
    preveri("propadla zahteva: branje v polje javi napako", propadla != null && try { propadla.data.read(ByteArray(8), 0, 8); false } catch (_: java.io.IOException) { true })
    // WebView pred glavami odgovora vprasa, koliko bajtov je na voljo: napaka tu konca zahtevo, preden stran dobi »200 OK«.
    preveri("propadla zahteva: napako javi ze vprasanje po velikosti (pred glavami odgovora)",
        propadla != null && try { propadla.data.available(); false } catch (_: java.io.IOException) { true })
    val prazna = AdBlockEngine.handleIntercept("https://doubleclick.net/tag.js", "https://www.example.com/", "*/*", false)
    preveri("skripta: prazen odgovor 200", prazna != null && prazna.statusCode == 200 && prazna.data.read() == -1 && prazna.mimeType == "application/javascript")
    val brezGlave = AdBlockEngine.handleIntercept("https://doubleclick.net/serve?zone=3")
    preveri("brez glave Accept: prazen odgovor kot doslej", brezGlave != null && brezGlave.data.read() == -1)

    // Kozmeticni filter: predvajalnika ne skrije; prazna oglasna mesta skrije samo ob vklopljenem blokiranju.
    println()
    println("== kozmeticni filter ==")
    val zBlokiranjem = CosmeticFilterEngine.splosnaPravila(true)
    val brezBlokiranja = CosmeticFilterEngine.splosnaPravila(false)
    preveri("v pravilih ni notranjosti predvajalnika (stanje oglasa je razred na njegovem glavnem vsebniku)", zBlokiranjem.none { it.contains("mgp_") })
    preveri("prazna oglasna mesta so skrita ob vklopljenem blokiranju",
        CosmeticFilterEngine.PRAZNA_OGLASNA_MESTA.isNotEmpty() && zBlokiranjem.containsAll(CosmeticFilterEngine.PRAZNA_OGLASNA_MESTA))
    preveri("brez blokiranja ostanejo vidna (v njih je oglas, ki ga je uporabnik dovolil)", brezBlokiranja.none { it in CosmeticFilterEngine.PRAZNA_OGLASNA_MESTA })
    val bilo = AdBlockEngine.isEnabled
    AdBlockEngine.isEnabled = false
    val slogBrez = CosmeticFilterEngine.buildCosmeticCss("https://www.example.com/")
    AdBlockEngine.isEnabled = true
    val slogZ = CosmeticFilterEngine.buildCosmeticCss("https://www.example.com/")
    AdBlockEngine.isEnabled = bilo
    preveri("slog strani sledi stikalu blokiranja", !slogBrez.contains("adsbytrafficjunky") && slogZ.contains("ins.adsbytrafficjunky"))

    // Seznami sami: noben vnos ne sme biti odvecen ali podvojen.
    println()
    println("== higiena seznamov ==")
    val poddomene = AdBlockEngine.vgrajeneOglasneDomene().filter { d ->
        val deli = d.split(".")
        (1 until deli.size - 1).any { k -> deli.drop(k).joinToString(".") in AdBlockEngine.vgrajeneOglasneDomene() }
    }
    preveri("v oglasnem seznamu ni odvečnih poddomen (${poddomene.joinToString()})", poddomene.isEmpty())

    val beleOdvecne = AdBlockEngine.vgrajenaBelaLista().filter { d ->
        val deli = d.split(".")
        (1 until deli.size - 1).any { k -> deli.drop(k).joinToString(".") in AdBlockEngine.vgrajenaBelaLista() }
    }
    preveri("na beli listi ni odvečnih poddomen (${beleOdvecne.joinToString()})", beleOdvecne.isEmpty())

    val vObeh = AdBlockEngine.vgrajeneOglasneDomene().intersect(AdBlockEngine.vgrajenaBelaLista())
    preveri("nobena domena ni hkrati blokirana in na beli listi", vObeh.isEmpty())

    println()
    if (napak == 0) println("VSE V REDU") else { println("NAPAK: $napak"); System.exit(1) }
}
