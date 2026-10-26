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
    val zunaj = IptvPravila.zapisZaNavadniKrog(kanal, listOf(TOK, TOK, zapisi[0]!!.naslov), izVira = true)
    preveri("seznam predvajanja: kanal z racunom brez toka", !imaRacun(zunaj.toString()) && zunaj.url.isEmpty() && zunaj.izvirnik.isEmpty())
    preveriEnako("seznam predvajanja: ime kanala ostane", "Kanal 101", zunaj.naslov)
    val javniKanal = SeznamiPravila.Zapis("TV SLO 1", "", url = "https://cdn.primer.test/slo1/index.m3u8?token=podpis", video = true)
    preveriEnako("seznam predvajanja: javni kanal (podpis CDN) ostane", javniKanal,
        IptvPravila.zapisZaNavadniKrog(javniKanal, listOf(javniKanal.url, javniKanal.url, JAVNI), izVira = true))
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

    println()
    println("== Grok 46, Ö1: fail-closed - navadnemu krogu samo pozitivno znan javni vir ==")
    val neznane = listOf(
        ZapisVira("seznam", "m3u_plus", "http://ponudnik.primer.test:8080/playlist/$UPORABNIK/$GESLO/m3u_plus"),
        ZapisVira("seznam", "m3u", "http://ponudnik.primer.test:8080/get/$UPORABNIK/$GESLO/m3u"),
        ZapisVira("seznam", "seznam", "https://ponudnik.primer.test/m3u/${GESLO}Zx81kqP0aa.m3u"),
        ZapisVira("seznam", "Ab3dE", "https://kratko.primer.test/Ab3dE"),
        ZapisVira("seznam", "$GESLO", "http://ponudnik.primer.test/$UPORABNIK/$GESLO"),
        ZapisVira("seznam", "playlist", " $XTREAM"),
        ZapisVira("seznam", "playlist", "http://tvh.primer.test:9981/playlist"),
        ZapisVira("tok", "kamera", "rtsp://$UPORABNIK:$GESLO@kamera.primer.test/live"),
        ZapisVira("tok", "radio", "https://radio.primer.test/val202.mp3"))
    for (v in neznane) {
        preveri("ne gre v navadni krog: ${v.tip} ${IptvPravila.zaPrikaz(v.naslov)}", !IptvPravila.smeZNavadnoUskladitvijo(v.tip, v.naslov))
        preveri("ne sprejme se od naprave zunaj ozjega kroga: ${IptvPravila.zaPrikaz(v.naslov)}", !IptvPravila.prevzemZNavadnoUskladitvijo(v.tip, v.naslov, false))
        preveri("na zaslonu brez racuna: ${IptvPravila.zaPrikaz(v.naslov)}", !imaRacun(IptvPravila.zaPrikaz(v.naslov)) &&
            !IptvPravila.zaPrikaz(v.naslov).contains('/'))
        val z = IptvPravila.zaShrambo(v, false, Shramba().zavaruj)
        preveri("v nastavitvah brez racuna: ${IptvPravila.zaPrikaz(v.naslov)}", z == null || !imaRacun(z.naslov + "|" + z.ime))
    }
    preveriEnako("na zaslonu Xtream v obliki poti: samo gostitelj", "ponudnik.primer.test", IptvPravila.zaPrikaz(neznane[0].naslov))
    preveriEnako("na zaslonu presledek pred naslovom: samo gostitelj", "ponudnik.primer.test", IptvPravila.zaPrikaz(neznane[5].naslov))
    preveriEnako("ne-http (rtsp) z racunom se ne shrani kot nov vir (ni nadomestka)", null, IptvPravila.zaShrambo(neznane[7], false, Shramba().zavaruj))
    preveri("javni seznam gre v navadni krog in je cel na zaslonu", IptvPravila.smeZNavadnoUskladitvijo("seznam", JAVNI) &&
        IptvPravila.zaPrikaz(JAVNI) == "iptv-org.github.io/iptv/countries/si.m3u")
    preveri("od naprave v ozjem krogu se vir z racunom sprejme (Linux 0f3f368)", IptvPravila.prevzemZNavadnoUskladitvijo("seznam", XTREAM, true) &&
        IptvPravila.prevzemZNavadnoUskladitvijo("url", XTREAM, true))
    preveri("od naprave v ozjem krogu nikoli dodatek s poverilnico ali nadomestek",
        !IptvPravila.prevzemZNavadnoUskladitvijo("stremio", "https://dodatek.primer.test/realdebrid=ABCDEFGHIJ1234567890xyz/manifest.json", true) &&
        !IptvPravila.prevzemZNavadnoUskladitvijo("seznam", zapisi[0]!!.naslov, true))
    preveri("podkast ni IPTV - gre", IptvPravila.smeZNavadnoUskladitvijo("podkast", "https://podkast.primer.test/rss.xml"))

    println()
    println("== Grok 46, Ö2: vir seznama in vsa polja skladbe ==")
    preveriEnako("vir seznama z naslovom Xtream ne gre", "", IptvPravila.virSeznamaZaNavadniKrog(XTREAM))
    preveriEnako("vir seznama YouTube ostane", "YouTube", IptvPravila.virSeznamaZaNavadniKrog("YouTube"))
    val slikaRacun = SeznamiPravila.Zapis("Pesem", "Izvajalec", "abc12345678", slika = "https://ponudnik.primer.test/images/1.png?username=$UPORABNIK&password=$GESLO")
    val slikaZunaj = IptvPravila.zapisZaNavadniKrog(slikaRacun, emptyList(), izVira = false)
    preveri("slika z racunom ne gre", !imaRacun(slikaZunaj.toString()))
    preveriEnako("posnetek YouTube ostane", "abc12345678", slikaZunaj.youtube)
    val android = SeznamiPravila.Zapis("Film", "", url = "https://cdn.primer.test/film.mp4",
        izvirnik = """{"id":"x","podnapisi":["http:\/\/ponudnik.primer.test:8080\/movie\/$UPORABNIK\/$GESLO\/5.srt"]}""")
    preveri("zapis android z naslovom z racunom ne gre", !imaRacun(IptvPravila.zapisZaNavadniKrog(android, listOf(android.url), izVira = false).toString()))
    val domaci = SeznamiPravila.Zapis("Kanal 1", "", url = "http://tvh.primer.test:9981/stream/channel/1", slika = "http://tvh.primer.test:9981/imagecache/1", video = true)
    val domaciZunaj = IptvPravila.zapisZaNavadniKrog(domaci, listOf(domaci.url, domaci.url, "http://tvh.primer.test:9981/playlist"), izVira = true)
    preveri("kanal domacega streznika (ni znan javen) brez toka in brez logotipa", domaciZunaj.url.isEmpty() && domaciZunaj.slika.isEmpty())
    val navadna = SeznamiPravila.Zapis("Pesem", "Izvajalec", url = "https://glasba.primer.test/p.mp3", slika = "https://glasba.primer.test/p.jpg")
    preveriEnako("navadna skladba (ni iz vira) brez racuna ostane", navadna, IptvPravila.zapisZaNavadniKrog(navadna, listOf(navadna.url), izVira = false))
    preveri("Nadaljuj: kanal domacega streznika ne gre zunaj ozjega kroga", !IptvPravila.skladbaCelaZaNavadniKrog(true, listOf(domaci.url, domaci.url)))
    preveri("Nadaljuj: kanal javnega seznama gre", IptvPravila.skladbaCelaZaNavadniKrog(true, listOf(javniKanal.url, javniKanal.url, JAVNI, "")))
    preveri("id iz vira: kanal, vnos seznama, kartica toka", IptvPravila.izViraSeznamaAliToka("iptv:x", false) &&
        IptvPravila.izViraSeznamaAliToka("seznam:x", false) && IptvPravila.izViraSeznamaAliToka("vir:x", false) &&
        !IptvPravila.izViraSeznamaAliToka("vir:x", true) && !IptvPravila.izViraSeznamaAliToka("yt:x", false))

    println()
    println("== Grok 46, Ö3: ze razposlane kopije - preklic in opozorilo ==")
    val oX = IptvPravila.odtisRacuna(XTREAM)
    val oN = IptvPravila.odtisRacuna(TOK)
    val sol = "00112233445566778899aabbccddeeff"
    val preklic = IptvPravila.preklicZa(listOf(Triple(oX, XTREAM, 1761400000000L), Triple(oN, TOK, 1761500000000L)), setOf(oX), sol)
    preveriEnako("preklic: samo vir, ki je bil tu ze pred prehodom", 1, preklic.size)
    preveri("preklic ne nosi naslova ne odtisa nadomestka", !imaRacun(preklic.toString()) && !preklic.toString().contains(oX))
    preveri("preklic: druga sol, drug vnos (odgovorov se ne da povezati)",
        IptvPravila.preklicZa(listOf(Triple(oX, XTREAM, 1L)), setOf(oX), "ffeeddccbbaa99887766554433221100") != IptvPravila.preklicZa(listOf(Triple(oX, XTREAM, 1L)), setOf(oX), sol))
    val nadX = zapisi[0]!!.naslov
    val lokalni = listOf(Triple(nadX, XTREAM, 1761400000000L), Triple("http://ponudnik.primer.test:8080/safeer-skrito-0123456789abcdef", TOK, 1761500000000L))
    preveriEnako("prejemnik: isti zapis (naslov in cas) - vprasaj", listOf(nadX), IptvPravila.preklicani(lokalni, sol, preklic, emptySet()))
    preveriEnako("prejemnik: isti naslov, drug cas (dodal ga je sam) - nic", emptyList<String>(),
        IptvPravila.preklicani(listOf(Triple(nadX, XTREAM, 5L)), sol, preklic, emptySet()))
    preveriEnako("prejemnik: ze rekel »obdrzi« - nic", emptyList<String>(), IptvPravila.preklicani(lokalni, sol, preklic, setOf(nadX)))
    preveriEnako("prejemnik: pokvarjena sol - nic", emptyList<String>(), IptvPravila.preklicani(lokalni, "x", preklic, emptySet()))
    preveriEnako("lastnik: opozorilo za vir izpred prehoda (brez vprasanja naprave zunaj kroga)", setOf(oX), IptvPravila.zaOpozorilo(setOf(oX), setOf(oX, oN), emptySet()))
    preveriEnako("lastnik: opozorilo samo enkrat", emptySet<String>(), IptvPravila.zaOpozorilo(setOf(oX), setOf(oX), setOf(oX)))
    preveriEnako("lastnik: vir, dodan po prehodu - brez opozorila", emptySet<String>(), IptvPravila.zaOpozorilo(emptySet(), setOf(oN), emptySet()))
    preveriEnako("lastnik: izbrisan vir - brez opozorila", emptySet<String>(), IptvPravila.zaOpozorilo(setOf(oX), emptySet(), emptySet()))

    println()
    println("== Grok 52, Ñ3: opozorilo in preklic za vse vire z racunom izpred prehoda ==")
    val zgresena = "http://ponudnik.primer.test:8080/playlist/$UPORABNIK/$GESLO/m3u_plus"      // oblika, ki je hevristika ne pozna
    val radio = "https://radio.primer.test/val202.mp3"
    val predPrehodom = IptvPravila.racuniPredPrehodom(listOf(ZapisVira("seznam", "get", XTREAM), ZapisVira("seznam", "playlist", zgresena),
        ZapisVira("tok-hls-video", "kanal", TOK), ZapisVira("tok", "radio", radio), ZapisVira("seznam", "si", JAVNI),
        ZapisVira("seznam", "nadomestek", zapisi[0]!!.naslov), ZapisVira("splet", "stran", "https://stran.primer.test/"),
        ZapisVira("seznam", "rtsp", "rtsp://$UPORABNIK:$GESLO@kamera.primer.test/live")))
    preveriEnako("pred prehodom: vsi viri z racunom (tudi neznane oblike in radio), brez javnega, nadomestka in strani",
        setOf(IptvPravila.odtisRacuna(XTREAM), IptvPravila.odtisRacuna(zgresena), IptvPravila.odtisRacuna(TOK), IptvPravila.odtisRacuna(radio)), predPrehodom)
    preveri("vir neznane oblike dobi opozorilo", IptvPravila.odtisRacuna(zgresena) in IptvPravila.zaOpozorilo(predPrehodom, predPrehodom, emptySet()))
    // Nesifriran ob prehodu (Keystore ne dela) in neznane oblike: odtis je isti kot odtis nadomestka, ki bi ga vir dobil -
    // opozorilo in preklic ga najdeta (MedijskiViri.racuni vzame odtis pravega naslova, ce nadomestka ni).
    val nesifrirani = IptvPravila.racuniPredPrehodom(listOf(ZapisVira("seznam", "playlist", zgresena)))
    val prekNes = IptvPravila.preklicZa(listOf(Triple(IptvPravila.odtisRacuna(zgresena), zgresena, 1761400000000L)), nesifrirani, sol)
    preveriEnako("nesifriran vir neznane oblike ob prehodu dobi preklic", 1, prekNes.size)
    preveriEnako("prejemnik nesifriranega vira najde preklic", listOf(zgresena), IptvPravila.preklicani(listOf(Triple(zgresena, zgresena, 1761400000000L)), sol, prekNes, emptySet()))
    // Isti odtis kot nadomestek, ki ga vir dobi ob sifriranju (preklic po prehodu sifriranja ne izgubi vira).
    preveriEnako("odtis pravega naslova = odtis nadomestka", SkrivnostiPravila.nadomestnaOsnova(SkrivnostiPravila.nadomestek(zgresena)!!)!!.substringAfterLast(SkrivnostiPravila.OZNAKA),
        IptvPravila.odtisRacuna(zgresena))

    println()
    println("== Grok 52, Ñ1: skladbe, ki niso iz vira (dodatki): fail-closed ==")
    val dodatekPosrednik = "https://iptv-posrednik.primer.test/eyJ1IjoidXBvcmFibmlrNyIsInAiOiJnZXNsbzEyMyJ9"
    val tokPosrednik = "https://iptv-posrednik.primer.test/p/eyJ1IjoidXBvcmFibmlrNyIsInAiOiJnZXNsbzEyMyJ9/123/index.m3u8"
    val kartica = SeznamiPravila.Zapis("Kanal 5", "", url = tokPosrednik, slika = "https://iptv-posrednik.primer.test/logo/5.png", video = true,
        izvirnik = """{"id":"stremio|tv|5|$dodatekPosrednik","zvok":"$tokPosrednik"}""")
    preveri("skladba iz dodatka neznane oblike ne gre cela", !IptvPravila.skladbaCelaZaNavadniKrog(false, listOf(tokPosrednik), dodatekPosrednik))
    val zunajD = IptvPravila.zapisZaNavadniKrog(kartica, listOf(tokPosrednik), izVira = false, dodatek = dodatekPosrednik)
    preveri("zapis iz dodatka: brez toka, zapisa android in slike", zunajD.url.isEmpty() && zunajD.izvirnik.isEmpty() && zunajD.slika.isEmpty())
    preveriEnako("zapis iz dodatka: ime ostane", "Kanal 5", zunajD.naslov)
    val cinemeta = SeznamiPravila.Zapis("Film", "", url = "", slika = "https://images.metahub.space/poster/small/tt0111161/img",
        izvirnik = """{"id":"stremio|movie|tt0111161|https://v3-cinemeta.strem.io"}""")
    preveriEnako("zapis iz znanega javnega dodatka (Cinemeta) ostane cel", cinemeta,
        IptvPravila.zapisZaNavadniKrog(cinemeta, emptyList(), izVira = false, dodatek = "https://v3-cinemeta.strem.io"))
    preveri("kartica dodatka z nadomestkom ne gre cela", !IptvPravila.skladbaCelaZaNavadniKrog(false, emptyList(),
        "https://v3-cinemeta.strem.io/" + SkrivnostiPravila.OZNAKA + "0123456789abcdef"))
    preveri("brez dodatka (navadna skladba) brez racuna ostane", IptvPravila.skladbaCelaZaNavadniKrog(false, listOf("https://glasba.primer.test/p.mp3"), null))
    preveri("rtsp z uporabnikom in geslom je racun", IptvPravila.skladbaZRacunom(listOf("rtsp://$UPORABNIK:$GESLO@kamera.primer.test/live")))
    preveri("ftp z uporabnikom je racun", IptvPravila.skladbaZRacunom(listOf("ftp://$UPORABNIK@datoteke.primer.test/glasba.mp3")))
    preveri("rtsp brez uporabnika ni racun", !IptvPravila.skladbaZRacunom(listOf("rtsp://kamera.primer.test/live", "magnet:?xt=urn:btih:abc", "content://media/external/audio/1")))

    println()
    println("== Grok 52, Ñ4: vir z racunom gre naprej samo z naprave, kjer je bil dodan ==")
    // Kot UskladitevKljucev.trenutne in posodobi: dodan tu -> vrednost; prejet/podedovan -> neznan; potem lokalneSpremembe in zaPoslati.
    val kTu = UskladitevKljucevPravila.kljucIptv(IptvPravila.odtisRacuna(XTREAM))
    val kTuj = UskladitevKljucevPravila.kljucIptv(IptvPravila.odtisRacuna(TOK))
    val kSync = UskladitevKljucevPravila.kljucIptv(IptvPravila.odtisRacuna(TVH))
    fun oddaj(meta: Map<String, UskladitevKljucevPravila.Meta>, dodanTu: Set<String>): Pair<Map<String, UskladitevKljucevPravila.Meta>, List<String>> {
        val v = LinkedHashMap<String, String>(); val neznani = HashSet<String>()
        for (k in listOf(kTu, kTuj, kSync)) when (UskladitevKljucevPravila.oddajaIptv(true, k in dodanTu)) { UskladitevKljucevPravila.Oddaja.VREDNOST -> v[k] = "odtis-$k"; else -> neznani += k }
        val spr = UskladitevKljucevPravila.lokalneSpremembe(meta, v, neznani, "jaz", 5000L, uvoz = false)
        val vse = meta + spr
        return vse to UskladitevKljucevPravila.zaPoslati(vse, v.mapValues { "vrednost-$it" }, "jaz").map { it.kljuc }
    }
    // kSync je bil prejet po secrets.sync od naprave B: zapis ima izvor B (ne jaz), vrednost se ne spreminja.
    val prejetoSync = mapOf(kSync to UskladitevKljucevPravila.Meta(3000L, "naprava-B", false, "odtis-$kSync"))
    val (metaPo, poslano) = oddaj(prejetoSync, setOf(kTu))
    preveriEnako("poslano: samo vir, dodan tu", listOf(kTu), poslano)
    preveri("prejet po lists.get (brez zapisa) ne dobi zapisa kot lasten", kTuj !in metaPo)
    preveriEnako("prejet po secrets.sync: zapis ostane od naprave B, brez groba", prejetoSync[kSync], metaPo[kSync])
    preveriEnako("prejet po secrets.sync ni poslan naprej (izvor ni ta naprava)", false, kSync in poslano)
    preveriEnako("neberljiva skrivnost (Keystore) ne gre, tudi ce je dodana tu", UskladitevKljucevPravila.Oddaja.NEZNAN, UskladitevKljucevPravila.oddajaIptv(false, true))

    if (napak == 0) println("IptvRacunTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
