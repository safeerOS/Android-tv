package si.safeer.tv.os

import si.safeer.tv.os.IptvPravila.ZapisVira
import si.safeer.tv.os.UskladitevKljucevPravila.Odziv
import si.safeer.tv.os.UskladitevKljucevPravila.Stanje

/**
 * Viri z racunom IPTV - sifrirano (lastnik, 26. 10. 2026): v navadnih nastavitvah samo nadomestek, v navadni krog Linka
 * nic (ne vir, ne grob, ne tok v seznamu predvajanja), prejem z navadnim usklajevanjem se zavrne, prehod obstojecih
 * zapisov. Sifrirano shrambo (Keystore) tu nadomesti slovar; pravila so [IptvPravila], [SkrivnostiPravila],
 * [UskladitevKljucevPravila]. Izmisljeni naslovi (*.primer.test).
 */
private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pricakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private const val UPORABNIK = "uporabnik7"
private const val GESLO = "geslo123"
private const val XTREAM = "http://ponudnik.primer.test:8080/get.php?username=$UPORABNIK&password=$GESLO&type=m3u_plus"
private const val TOK = "http://ponudnik.primer.test:8080/live/$UPORABNIK/$GESLO/101.m3u8"
private const val TVH = "http://$UPORABNIK:$GESLO@tvh.primer.test:9981/playlist"
private const val ZETON = "https://seznam.primer.test/moj.m3u?token=Zx81kqP0aa"
private const val JAVNI = "https://iptv-org.github.io/iptv/countries/si.m3u"

/** Ali besedilo (kljuc ali vrednost nastavitev, sporocilo Linka) vsebuje kateri del racuna. */
private fun imaRacun(s: String) = s.contains(GESLO) || s.contains(UPORABNIK) || s.contains("Zx81kqP0aa")

/** Sifrirana shramba (Keystore) v preizkusu: nadomestek -> pravi naslov. */
private class Shramba {
    val vsebina = HashMap<String, String>()
    var deluje = true
    val zavaruj: (String) -> String? = { s -> if (!deluje) null else SkrivnostiPravila.nadomestek(s)?.also { vsebina[it] = s } }
}

fun main() {
    println("== zapis vira z racunom: v navadnih nastavitvah ni gesla ==")
    val sh = Shramba()
    val vnosi = listOf(ZapisVira("seznam", "get (5000)", XTREAM), ZapisVira("tok-hls-video", GESLO, TOK),
        ZapisVira("seznam", "playlist (40)", TVH), ZapisVira("seznam", "moj (12)", ZETON), ZapisVira("seznam", "si (90)", JAVNI))
    val zapisi = vnosi.map { IptvPravila.zaShrambo(it, zeShranjen = false, zavaruj = sh.zavaruj) }
    val nastavitve = zapisi.joinToString("\n") { "${it?.tip}|${it?.ime}|${it?.naslov}" }
    preveri("nastavitve virov brez uporabnika, gesla in zetona", !imaRacun(nastavitve))
    preveriEnako("javni seznam ostane, kot je", ZapisVira("seznam", "si (90)", JAVNI), zapisi[4])
    preveri("Xtream: nadomestek z gostiteljem in oznako", zapisi[0]!!.naslov.startsWith("http://ponudnik.primer.test:8080/safeer-skrito-"))
    preveriEnako("Xtream: pravi naslov je v sifrirani shrambi", XTREAM, sh.vsebina[zapisi[0]!!.naslov])
    preveriEnako("tok: pravi naslov je v sifrirani shrambi", TOK, sh.vsebina[zapisi[1]!!.naslov])
    preveriEnako("u:g@: pravi naslov je v sifrirani shrambi", TVH, sh.vsebina[zapisi[2]!!.naslov])
    preveriEnako("zeton seznama: pravi naslov je v sifrirani shrambi", ZETON, sh.vsebina[zapisi[3]!!.naslov])
    preveriEnako("ime = geslo -> gostitelj", "ponudnik.primer.test", zapisi[1]!!.ime)
    preveriEnako("samodejno ime -> gostitelj, stevilo kanalov ostane", "ponudnik.primer.test (5000)", zapisi[0]!!.ime)
    preveriEnako("uporabnikovo ime ostane", "Moj IPTV", IptvPravila.varnoIme("Moj IPTV", XTREAM))
    preveriEnako("na zaslonu nadomestka samo gostitelj", "ponudnik.primer.test", IptvPravila.zaPrikaz(zapisi[0]!!.naslov))
    preveriEnako("nadomestek se ne zavaruje znova", zapisi[0], IptvPravila.zaShrambo(zapisi[0]!!, false, sh.zavaruj))
    preveriEnako("odtis kljuca ozjega kroga = odtis nadomestka", zapisi[0]!!.naslov.substringAfterLast(SkrivnostiPravila.OZNAKA),
        IptvPravila.odtisRacuna(XTREAM))
    val pokvarjena = Shramba().apply { deluje = false }
    preveriEnako("sifriranje ne uspe: nov vir se ne shrani", null, IptvPravila.zaShrambo(vnosi[0], false, pokvarjena.zavaruj))
    preveriEnako("sifriranje ne uspe: ze shranjen ostane (ne izgubi se)", vnosi[0], IptvPravila.zaShrambo(vnosi[0], true, pokvarjena.zavaruj))

    println()
    println("== uskladitev v navadni krog (lists.get) ne vsebuje gesla ==")
    // Moji viri te naprave po shranjevanju + star zapis, ki ga ni bilo mogoce sifrirati (pravi naslov ostal).
    val moji = zapisi.filterNotNull() + ZapisVira("seznam", "star", XTREAM.replace("ponudnik", "drugi"))
    val sources = moji.filter { IptvPravila.smeZNavadnoUskladitvijo(it.tip, it.naslov) }
    val sporocilo = sources.joinToString("\n") { "${it.tip}|${it.ime}|${it.naslov}" }
    preveri("sources brez racuna", !imaRacun(sporocilo))
    preveri("sources brez nadomestka (vir z racunom gre samo v ozji krog)", !sporocilo.contains(SkrivnostiPravila.OZNAKA))
    preveriEnako("sources: samo javni seznam", listOf(JAVNI), sources.map { it.naslov })
    val grobovi = mapOf("seznam|$XTREAM" to 5L, "seznam|${zapisi[0]!!.naslov}" to 6L, "seznam|$JAVNI" to 7L,
        "url|$TVH" to 8L, "stremio|https://dodatek.primer.test/realdebrid=ABCDEFGHIJ1234567890xyz/manifest.json" to 9L,
        "stremio|https://v3-cinemeta.strem.io/manifest.json" to 10L)
    val sourcesDeleted = grobovi.filterKeys { IptvPravila.smeZNavadnoUskladitvijo(it.substringBefore('|'), it.substringAfter('|')) }
    preveriEnako("sources_deleted: brez virov z racunom in dodatkov s poverilnico",
        setOf("seznam|$JAVNI", "stremio|https://v3-cinemeta.strem.io/manifest.json"), sourcesDeleted.keys)
    val kanal = SeznamiPravila.Zapis("Kanal 101", "", url = TOK, video = true, izvirnik = """{"zvok":"$TOK"}""")
    val zunaj = IptvPravila.zapisZaNavadniKrog(kanal, listOf(TOK, TOK, zapisi[0]!!.naslov))
    preveri("seznam predvajanja: kanal z racunom brez toka", !imaRacun(zunaj.toString()) && zunaj.url.isEmpty() && zunaj.izvirnik.isEmpty())
    preveriEnako("seznam predvajanja: ime kanala ostane", "Kanal 101", zunaj.naslov)
    val javniKanal = SeznamiPravila.Zapis("TV SLO 1", "", url = "https://cdn.primer.test/slo1/index.m3u8?token=podpis", video = true)
    preveriEnako("seznam predvajanja: javni kanal (podpis CDN) ostane", javniKanal,
        IptvPravila.zapisZaNavadniKrog(javniKanal, listOf(javniKanal.url, javniKanal.url, JAVNI)))
    preveriEnako("prevzem od naprave brez toka ne izbrise nasega kanala", listOf(kanal),
        SeznamiPravila.prevzemi(listOf(kanal), listOf(SeznamiPravila.Zapis("Kanal 101", "", video = true)), 400))
    preveri("Nadaljuj na drugi napravi: kanal z racunom", IptvPravila.skladbaZRacunom(listOf(TOK, TOK, "")))
    preveri("Nadaljuj na drugi napravi: nadomestek", IptvPravila.skladbaZRacunom(listOf(zapisi[0]!!.naslov)))
    preveri("Nadaljuj na drugi napravi: javni kanal ne", !IptvPravila.skladbaZRacunom(listOf(javniKanal.url, JAVNI)))

    println()
    println("== prejem od naprave zunaj ozjega kroga se zavrne ==")
    for (v in listOf(ZapisVira("seznam", "x", XTREAM), ZapisVira("url", "x", XTREAM), ZapisVira("tok-hls-video", "x", TOK),
        ZapisVira("tok", "x", TVH), ZapisVira("seznam", "x", zapisi[0]!!.naslov)))
        preveri("navadno usklajevanje ne sprejme: ${v.tip} ${IptvPravila.zaPrikaz(v.naslov)}", !IptvPravila.smeZNavadnoUskladitvijo(v.tip, v.naslov))
    preveri("navadno usklajevanje sprejme javni seznam", IptvPravila.smeZNavadnoUskladitvijo("seznam", JAVNI))
    preveriEnako("secrets.sync: naprava zunaj ozjega kroga - zavrni (tudi potrjena)", Odziv.ZAVRNI,
        UskladitevKljucevPravila.sprejem(true, false, Stanje.POTRJENA))
    preveriEnako("secrets.sync: ozji krog, potrjena - sprejmi", Odziv.SPREJMI, UskladitevKljucevPravila.sprejem(true, true, Stanje.POTRJENA))
    val odtis = IptvPravila.odtisRacuna(XTREAM)
    preveri("kljuc iptv:<odtis> je veljaven", UskladitevKljucevPravila.veljavenKljuc(UskladitevKljucevPravila.kljucIptv(odtis), emptySet()))
    preveri("kljuc iptv brez odtisa ni veljaven", !UskladitevKljucevPravila.veljavenKljuc("iptv:abc", emptySet()))
    preveriEnako("prejet racun iz ozjega kroga", ZapisVira("seznam", "ponudnik.primer.test (5000)", XTREAM),
        IptvPravila.prejetRacun(odtis, "seznam", XTREAM, "get (5000)"))
    preveriEnako("prejet racun: napacen odtis", null, IptvPravila.prejetRacun("0123456789abcdef", "seznam", XTREAM, ""))
    preveriEnako("prejet racun: nadomestek", null, IptvPravila.prejetRacun(odtis, "seznam", zapisi[0]!!.naslov, ""))
    preveriEnako("prejet racun: naslov brez racuna", null, IptvPravila.prejetRacun(IptvPravila.odtisRacuna(JAVNI), "seznam", JAVNI, ""))
    preveriEnako("prejet racun: vrsta brez racuna", null, IptvPravila.prejetRacun(odtis, "splet", XTREAM, ""))
    preveriEnako("prejet racun: fragment", null, IptvPravila.prejetRacun(IptvPravila.odtisRacuna("$XTREAM#x"), "seznam", "$XTREAM#x", ""))

    println()
    println("== prehod obstojecih nesifriranih zapisov ==")
    // Nastavitve aplikacije pred prehodom (kot SharedPreferences.getAll; org.json pise »/« kot »\/«).
    fun js(s: String) = s.replace("/", "\\/")
    fun vJson(v: List<ZapisVira>) = v.joinToString(",", "[", "]") { """{"tip":"${it.tip}","ime":"${it.ime}","naslov":"${js(it.naslov)}"}""" }
    val star = listOf(ZapisVira("seznam", "get (5000)", XTREAM), ZapisVira("tok-hls-video", GESLO, TOK), ZapisVira("seznam", "si (90)", JAVNI))
    val prej: Map<String, Any?> = mapOf(
        "viri" to vJson(star),
        "viri_casi" to """{"seznam|${js(XTREAM)}":1761400000000,"tok-hls-video|${js(TOK)}":1761400000001,"seznam|${js(JAVNI)}":1761400000002}""",
        "viri_dodani_tu" to setOf(XTREAM, JAVNI),
        "pripeti_viri" to """["u:${js(XTREAM)}","tv"]""",
        "vir:$XTREAM" to true,
        "seznam:$XTREAM" to """[{"id":"seznam:x","povezava":"${js(XTREAM)}"}]""",
        "nedavno" to """[{"id":"vir-seznam:${js(XTREAM)}","zvok":"${js(XTREAM)}"}]""")
    val sh2 = Shramba()
    val zamenjave = IptvPravila.zamenjaveRacunov(star, sh2.zavaruj)
    preveriEnako("prehod: dva vira z racunom", setOf(XTREAM, TOK), zamenjave.keys)
    // Kot MedijskiViri.preseli: Moji viri prek preseliZapise (nadomestek in varno ime), vse ostale nastavitve prek preseliVnose.
    val noviViri = IptvPravila.preseliZapise(star, zamenjave)
    val potem = (SkrivnostiPravila.preseliVnose(prej - "viri", zamenjave) ?: emptyMap()) + ("viri" to vJson(noviViri))
    val vse = potem.entries.joinToString("\n") { "${it.key}=${it.value}" }
    preveri("prehod: v nobeni nastavitvi (kljuc ali vrednost) ni uporabnika ali gesla", potem.isNotEmpty() && !imaRacun(vse))
    preveri("prehod: Moji viri imajo nadomestek", (potem["viri"] as String).contains(js((zamenjave[XTREAM] ?: "-"))))
    preveri("prehod: javni seznam ostane", (potem["viri"] as String).contains(js(JAVNI)))
    preveri("prehod: kljuci pod nadomestkom", potem.containsKey("seznam:" + (zamenjave[XTREAM] ?: "-")) && potem.containsKey("vir:" + (zamenjave[XTREAM] ?: "-")))
    preveriEnako("prehod: pravi naslovi v sifrirani shrambi", setOf(XTREAM, TOK), sh2.vsebina.values.toSet())
    preveriEnako("prehod: ime = geslo dobi gostitelja", "ponudnik.primer.test", noviViri[1].ime)
    preveriEnako("prehod: javni vir nespremenjen", star[2], noviViri[2])
    preveriEnako("prehod drugic: nic vec", emptyMap<String, String>(), IptvPravila.zamenjaveRacunov(noviViri, sh2.zavaruj))
    preveriEnako("prehod: sifriranje ne uspe - ni zamenjav (vir ostane, ne izgubi se)", emptyMap<String, String>(),
        IptvPravila.zamenjaveRacunov(star, Shramba().apply { deluje = false }.zavaruj))
    val grobovi2 = mapOf("seznam|$XTREAM" to 1L, "url|$TOK" to 2L, "seznam|$JAVNI" to 3L, "seznam|${(zamenjave[XTREAM] ?: "-")}" to 4L)
    preveriEnako("prehod: grobovi s pravim naslovom z racunom izginejo",
        setOf("seznam|$JAVNI", "seznam|${(zamenjave[XTREAM] ?: "-")}"), IptvPravila.brezGrobovZRacunom(grobovi2).keys)

    if (napak == 0) println("IptvRacunTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
