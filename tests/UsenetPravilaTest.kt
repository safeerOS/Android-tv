package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 141: Usenet prek TorBoxa - indeksator Newznab ([UsenetPravila]). */
fun main() {
    println("Preizkus pravil Usenet")

    // Naslov indeksatorja
    preveriEnako("141: osnova brez /api in poizvedbe", "https://api.indeksator.example", UsenetPravila.osnova(" https://api.indeksator.example/api?t=caps "))
    preveriEnako("141: osnova s potjo", "https://x.example/newznab", UsenetPravila.osnova("https://x.example/newznab/api/"))
    preveriEnako("141: velika crka sheme", "https://x.example", UsenetPravila.osnova("HTTPS://x.example"))
    preveriEnako("141: http na internet ne (kljuc bi sel nesifriran)", null, UsenetPravila.osnova("http://api.indeksator.example"))
    preveriEnako("141: http v domacem omrezju da (NZBHydra)", "http://192.168.1.20:5076", UsenetPravila.osnova("http://192.168.1.20:5076/api"))
    preveriEnako("141: http 172.16-31 da", "http://172.20.0.5:9696", UsenetPravila.osnova("http://172.20.0.5:9696"))
    preveriEnako("141: http 172.32 ne", null, UsenetPravila.osnova("http://172.32.0.5"))
    preveriEnako("141: uporabnik v naslovu ne", null, UsenetPravila.osnova("https://jaz:geslo@x.example"))
    preveriEnako("141: ni naslov", null, UsenetPravila.osnova("api kljuc 123"))
    preveriEnako("141: ftp ne", null, UsenetPravila.osnova("ftp://x.example"))
    // Lastnik 25. 10. 2026: gola domena (kot jo uporabnik vpise) dobi https:// sama.
    preveriEnako("gola domena -> https", "https://nzbfinder.ws", UsenetPravila.osnova("nzbfinder.ws"))
    preveriEnako("gola domena z /api/ -> https", "https://nzbfinder.ws", UsenetPravila.osnova(" nzbfinder.ws/api/ "))
    preveriEnako("gola domena s potjo", "https://x.example/newznab", UsenetPravila.osnova("x.example/newznab/api"))
    preveriEnako("gola domena z uporabnikom ne", null, UsenetPravila.osnova("jaz:geslo@x.example"))
    preveriEnako("prazno ne", null, UsenetPravila.osnova("   "))
    preveriEnako("http na internet se vedno ne", null, UsenetPravila.osnova("http://nzbfinder.ws"))
    // Pregled Grok 31, H1: kljuc v polju za naslov ni gostitelj (sicer gre v DNS necifriran); H4: tipkarske napake.
    val heks32 = "0123456789abcdef0123456789abcdef"
    val uuid = "abcdef01-2345-6789-abcd-ef0123456789"
    preveriEnako("H1: kljuc 32 heks kot naslov ne", null, UsenetPravila.osnova(heks32))
    preveriEnako("H1: UUID (TorBox) kot naslov ne", null, UsenetPravila.osnova(uuid))
    preveriEnako("H1: kljuc z https:// ne", null, UsenetPravila.osnova("https://$heks32"))
    preveriEnako("H1: zeton RD (52) kot naslov ne", null, UsenetPravila.osnova("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST"))
    preveriEnako("H1: kljuc z vrati ne", null, UsenetPravila.osnova("$heks32:443"))
    preveriEnako("H1: kljuc kot oznaka imena ne", null, UsenetPravila.osnova("$heks32.ws"))
    preveriEnako("H1: UUID kot oznaka imena ne", null, UsenetPravila.osnova("https://$uuid.example"))
    preveriEnako("H4: ime brez pike ne", null, UsenetPravila.osnova("nzbfinder"))
    preveriEnako("H4: http//x ne", null, UsenetPravila.osnova("http//nzbfinder.ws"))
    preveriEnako("H4: https:/x ne", null, UsenetPravila.osnova("https:/nzbfinder.ws"))
    preveriEnako("H4: https://ime brez pike ne", null, UsenetPravila.osnova("https://nas:5076"))
    preveriEnako("gola domena z vrati", "https://nzbfinder.ws:8443", UsenetPravila.osnova("nzbfinder.ws:8443"))
    preveriEnako("localhost z vrati", "https://localhost:5076", UsenetPravila.osnova("localhost:5076"))
    preveriEnako("http localhost", "http://localhost:5076", UsenetPravila.osnova("http://localhost:5076"))
    preveriEnako("gol IPv4 v domacem omrezju", "https://192.168.1.10:5076", UsenetPravila.osnova("192.168.1.10:5076"))
    preveriEnako("IPv6 v oklepajih", "https://[::1]:5076", UsenetPravila.osnova("[::1]:5076"))
    preveriEnako("kratko heks ime s piko ostane", "https://abc123.example", UsenetPravila.osnova("abc123.example"))
    preveriEnako("H1: naslovJeKljuc 32 heks", true, UsenetPravila.naslovJeKljuc(" $heks32 "))
    preveriEnako("H1: naslovJeKljuc UUID", true, UsenetPravila.naslovJeKljuc(uuid))
    preveriEnako("H1: naslovJeKljuc https + kljuc", true, UsenetPravila.naslovJeKljuc("https://$heks32/api"))
    preveriEnako("H1: naslovJeKljuc ime ne", false, UsenetPravila.naslovJeKljuc("nzbfinder.ws"))
    preveriEnako("H1: naslovJeKljuc ime brez pike ne", false, UsenetPravila.naslovJeKljuc("nzbfinder"))
    preveriEnako("H1: napaka vnosa - kljuc v naslovu", UsenetPravila.NapakaVnosa.NASLOV_JE_KLJUC, UsenetPravila.napakaVnosa(heks32, heks32))
    preveriEnako("H1: napaka vnosa - naslov", UsenetPravila.NapakaVnosa.NASLOV, UsenetPravila.napakaVnosa("nzbfinder", heks32))
    preveriEnako("H1: napaka vnosa - kljuc", UsenetPravila.NapakaVnosa.KLJUC, UsenetPravila.napakaVnosa("nzbfinder.ws", "your_api_key"))
    preveriEnako("H1: napaka vnosa - nic", null, UsenetPravila.napakaVnosa("nzbfinder.ws", heks32))
    preveriEnako("H1: prejeti indeksator s kljucem kot gostiteljem ne", null, UsenetPravila.prejetIndeksator("https://$heks32", heks32))

    // Kljuc
    preveriEnako("141: kljuc 32 heks", "0123456789abcdef0123456789abcdef", UsenetPravila.ocistiKljuc(" 0123456789abcdef0123456789abcdef\n"))
    preveriEnako("141: prekratek kljuc", null, UsenetPravila.ocistiKljuc("abc"))
    preveriEnako("141: kljuc s presledkom ne", null, UsenetPravila.ocistiKljuc("abcd efgh ijkl"))
    preveriEnako("141: kljuc z & ne (naslov)", null, UsenetPravila.ocistiKljuc("abcdefgh&t=get"))

    // Iskanje
    val o = "https://x.example"
    preveriEnako("141: film", "$o/api?apikey=KLJUC123&imdbid=1375666&limit=100&extended=1&t=movie",
        UsenetPravila.naslovIskanja(o, "KLJUC123", "tt1375666"))
    preveriEnako("141: epizoda", "$o/api?apikey=KLJUC123&imdbid=0108778&limit=100&extended=1&t=tvsearch&season=1&ep=1",
        UsenetPravila.naslovIskanja(o, "KLJUC123", "tt0108778:1:1"))
    preveriEnako("141: tmdb ne", null, UsenetPravila.naslovIskanja(o, "KLJUC123", "tmdb:84773:1:1"))
    preveriEnako("141: epizoda 0 ne", null, UsenetPravila.naslovIskanja(o, "KLJUC123", "tt0108778:1:0"))
    preveriEnako("141: pokvarjen id ne", null, UsenetPravila.naslovIskanja(o, "KLJUC123", "tt12ab"))
    preveriEnako("141: iscemo film", true, UsenetPravila.iscemo("tt1375666"))
    preveriEnako("141: tmdb ne iscemo", false, UsenetPravila.iscemo("tmdb:1"))
    preveriEnako("141: dve dvopicji ne", null, UsenetPravila.naslovIskanja(o, "KLJUC123", "tt0108778:1"))

    // Odgovor RSS
    val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
<channel><title>x</title>
<item><title>Inception.2010.1080p.BluRay.x264-GRP</title>
 <guid isPermaLink="true">https://x.example/details/aaa</guid>
 <link>https://x.example/getnzb/aaa.nzb&amp;i=1&amp;r=KLJUC123</link>
 <enclosure url="https://x.example/getnzb/aaa.nzb&amp;i=1&amp;r=KLJUC123" length="999" type="application/x-nzb"/>
 <newznab:attr name="size" value="13636521984"/></item>
<item><title>Inception 2010 2160p UHD BluRay HEVC</title>
 <link>https://x.example/getnzb/bbb.nzb&amp;r=KLJUC123</link>
 <enclosure url="https://x.example/getnzb/bbb.nzb&amp;r=KLJUC123" length="2147483648" type="application/x-nzb"/></item>
<item><title>Ponovljen</title><enclosure url="https://x.example/getnzb/aaa.nzb&amp;i=1&amp;r=KLJUC123" length="1"/></item>
<item><title>Brez povezave</title></item>
<item><title>Nesifrirano</title><enclosure url="http://drug.example/getnzb/ccc.nzb" length="1"/></item>
<item><title>Javascript</title><enclosure url="javascript:alert(1)" length="1"/></item>
<item><title></title><enclosure url="https://x.example/getnzb/ddd.nzb" length="1"/></item>
</channel></rss>"""
    val z = UsenetPravila.zadetki(rss, o)
    preveriEnako("141: veljavni zadetki", listOf(
        UsenetPravila.Zadetek("Inception.2010.1080p.BluRay.x264-GRP", "https://x.example/getnzb/aaa.nzb&i=1&r=KLJUC123", 13_636_521_984L),
        UsenetPravila.Zadetek("Inception 2010 2160p UHD BluRay HEVC", "https://x.example/getnzb/bbb.nzb&r=KLJUC123", 2_147_483_648L)), z)
    preveriEnako("141: XXE zavrnjen", emptyList<UsenetPravila.Zadetek>(),
        UsenetPravila.zadetki("""<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title><enclosure url="https://x.example/a"/></item></channel></rss>""", o))
    preveriEnako("141: pokvarjen XML", emptyList<UsenetPravila.Zadetek>(), UsenetPravila.zadetki("<rss><item>", o))
    preveriEnako("141: http v domacem omrezju z istega gostitelja",
        listOf(UsenetPravila.Zadetek("Film", "http://192.168.1.20:5076/getnzb/x", 0L)),
        UsenetPravila.zadetki("""<rss><channel><item><title>Film</title><link>http://192.168.1.20:5076/getnzb/x</link></item></channel></rss>""", "http://192.168.1.20:5076"))
    val veliko = "<rss><channel>" + (1..60).joinToString("") { "<item><title>F$it</title><link>https://x.example/$it</link></item>" } + "</channel></rss>"
    preveriEnako("141: najvec zadetkov", UsenetPravila.NAJVEC_ZADETKOV, UsenetPravila.zadetki(veliko, o).size)

    // Napake Newznab
    preveriEnako("141: 100 = kljuc", TorBoxPravila.NAPAKA_KLJUC, UsenetPravila.napaka("""<error code="100" description="Incorrect user credentials"/>"""))
    preveriEnako("141: 500 = meja", TorBoxPravila.NAPAKA_MEJA, UsenetPravila.napaka("""<?xml version="1.0"?><error code="500" description="Request limit reached"/>"""))
    preveriEnako("141: 300 = indeksator", UsenetPravila.NAPAKA_INDEKSATOR, UsenetPravila.napaka("""<error code="300" description="No such item."/>"""))
    preveriEnako("141: brez napake", null, UsenetPravila.napaka(rss))

    // Tok
    val id = UsenetPravila.idToka("https://x.example/getnzb/aaa.nzb&i=1&r=KLJUC123")
    preveriEnako("141: id toka brez kljuca", false, id.contains("KLJUC123"))
    preveriEnako("141: id toka oblika", true, id.startsWith("usenet:") && id.length == "usenet:".length + 16)
    preveriEnako("141: id je stalen", id, UsenetPravila.idToka("https://x.example/getnzb/aaa.nzb&i=1&r=KLJUC123"))
    preveriEnako("141: jeUsenet", true, UsenetPravila.jeUsenet(id))
    preveriEnako("141: magnet ni Usenet", false, UsenetPravila.jeUsenet("magnet:?xt=urn:btih:abc"))
    val opis = UsenetPravila.opis(z[0])
    preveriEnako("141: opis z velikostjo", "Inception.2010.1080p.BluRay.x264-GRP\n💾 12.7 GB", opis)
    preveriEnako("141: TokIzbira razbere velikost", 12.7, TokIzbira.opisi(opis).gb)
    preveriEnako("141: TokIzbira razbere locljivost", 1080, TokIzbira.opisi(opis).visina)
    preveriEnako("141: opis brez velikosti", "Film", UsenetPravila.opis(UsenetPravila.Zadetek("Film", "https://x.example/a", 0L)))
    preveriEnako("141: NZB prepoznan", true, UsenetPravila.jeNzb("""<?xml version="1.0"?><!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "x"><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">"""))
    preveriEnako("141: stran HTML ni NZB", false, UsenetPravila.jeNzb("<html><body>Login</body></html>"))
    preveriEnako("141: NZB z ENTITY ne", false, UsenetPravila.jeNzb("<!ENTITY x SYSTEM \"file:///\"><nzb>"))

    // Ponovljena izdaja (isti naslov in velikost, druga povezava)
    preveriEnako("141: ista izdaja enkrat", 1, UsenetPravila.zadetki("""<rss><channel>
<item><title>Film.2008.1080p</title><link>https://x.example/a</link><newznab:attr name="size" value="5"/></item>
<item><title>Film.2008.1080p</title><link>https://x.example/b</link><newznab:attr name="size" value="5"/></item></channel></rss>""", o).size)
    preveriEnako("141: ista izdaja, druga velikost - dvakrat", 2, UsenetPravila.zadetki("""<rss><channel>
<item><title>Film.2008.1080p</title><link>https://x.example/a</link><newznab:attr name="size" value="5"/></item>
<item><title>Film.2008.1080p</title><link>https://x.example/b</link><newznab:attr name="size" value="6"/></item></channel></rss>""", o).size)

    // Samodejna izbira
    preveriEnako("141: samodejno do 15 GB, dva poskusa", listOf(1, 2), UsenetPravila.zaSamodejno(listOf(58.0, 2.2, 0.0, 12.0)))
    preveriEnako("141: natanko 15 GB da", listOf(0), UsenetPravila.zaSamodejno(listOf(15.0)))
    preveriEnako("141: vse prevelike - nic", emptyList<Int>(), UsenetPravila.zaSamodejno(listOf(60.0, 15.1)))

    // Prejeto z druge naprave (Link)
    preveriEnako("141: prejet indeksator", "https://x.example" to "0123456789abcdef",
        UsenetPravila.prejetIndeksator("https://x.example/api", "0123456789abcdef"))
    preveriEnako("141: prejet http na internet ne", null, UsenetPravila.prejetIndeksator("http://x.example", "0123456789abcdef"))
    preveriEnako("141: prejet brez kljuca ne", null, UsenetPravila.prejetIndeksator("https://x.example", null))
    preveriEnako("141: prejet pokvarjen kljuc ne", null, UsenetPravila.prejetIndeksator("https://x.example", "a b"))
    preveriEnako("141: nic poslano", null, UsenetPravila.prejetIndeksator(null, null))

    // TorBox
    preveriEnako("141: requestdl", "https://api.torbox.app/v1/api/usenet/requestdl?token=a%2Bb&usenet_id=7&file_id=3",
        UsenetPravila.naslovPrenosa("a+b", 7, 3))
    preveriEnako("141: mylist", "https://api.torbox.app/v1/api/usenet/mylist?id=7&bypass_cache=true", UsenetPravila.naslovSeznam(7))
    preveriEnako("141: samo takoj, ni v predpomnilniku", true, UsenetPravila.niPredpomnjeno(true, TorBoxPravila.NAPAKA_STREZNIK))
    preveriEnako("141: zavrnjen kljuc ostane kljuc", false, UsenetPravila.niPredpomnjeno(true, TorBoxPravila.NAPAKA_KLJUC))
    preveriEnako("141: paket (ni Pro) ostane paket", false, UsenetPravila.niPredpomnjeno(true, TorBoxPravila.NAPAKA_PAKET))
    preveriEnako("141: s cakanjem napaka ostane", false, UsenetPravila.niPredpomnjeno(false, TorBoxPravila.NAPAKA_STREZNIK))

    // Pregled A10: http samo do pravega naslova IP v domacem omrezju, ne do imena, ki se tako zacne.
    preveriEnako("A10: ime 10.example.com ni domace", null, UsenetPravila.osnova("http://10.example.com"))
    preveriEnako("A10: ime 192.168.evil.example ni domace", null, UsenetPravila.osnova("http://192.168.evil.example/api"))
    preveriEnako("A10: ime 127.0.0.1.nip.io ni domace", null, UsenetPravila.osnova("http://127.0.0.1.nip.io"))
    preveriEnako("A10: 10.0.0.5 je domace", "http://10.0.0.5:5076", UsenetPravila.osnova("http://10.0.0.5:5076/api"))
    preveriEnako("A10: 127.0.0.1 je domace", "http://127.0.0.1:9696", UsenetPravila.osnova("http://127.0.0.1:9696"))
    preveriEnako("A10: neveljaven IPv4 ni domace", null, UsenetPravila.osnova("http://192.168.1.300"))
    preveriEnako("A10: javni IPv4 ni domace", null, UsenetPravila.osnova("http://8.8.8.8"))
    preveriEnako("A10: .local je domace", "http://nas.local:5076", UsenetPravila.osnova("http://nas.local:5076"))
    preveriEnako("A10: localhost je domace", "http://localhost:5076", UsenetPravila.osnova("http://localhost:5076"))
    preveriEnako("A10: https za ime vedno", "https://10.example.com", UsenetPravila.osnova("https://10.example.com"))
    // Pregled A11: NZB nad mejo je napaka, ne odrezan.
    preveriEnako("A11: na meji ni prevelik", false, UsenetPravila.nzbPrevelik(UsenetPravila.NAJVEC_NZB_BAJTOV.toLong()))
    preveriEnako("A11: nad mejo prevelik", true, UsenetPravila.nzbPrevelik(UsenetPravila.NAJVEC_NZB_BAJTOV + 1L))
    preveriEnako("A11: neznana dolzina ni prevelika", false, UsenetPravila.nzbPrevelik(-1L))
    preveriEnako("A8: cakanje na Usenet pokrije povezavo in branje", true,
        UsenetPravila.ISKANJE_CAKAJ_MS >= UsenetPravila.ISKANJE_POVEZAVA_MS + UsenetPravila.ISKANJE_ROK_MS)

    // Pregled Grok 3: vrsta napake NZB gre v izid - kljuc, meja in druga napaka indeksatorja se razlikujejo.
    preveriEnako("Grok 3: HTTP 401 = kljuc", TorBoxPravila.NAPAKA_KLJUC, UsenetPravila.napakaNzb(401, ""))
    preveriEnako("Grok 3: HTTP 403 = kljuc", TorBoxPravila.NAPAKA_KLJUC, UsenetPravila.napakaNzb(403, "<html>Forbidden</html>"))
    preveriEnako("Grok 3: HTTP 429 = meja", TorBoxPravila.NAPAKA_MEJA, UsenetPravila.napakaNzb(429, ""))
    preveriEnako("Grok 3: Newznab 100 v 200 = kljuc", TorBoxPravila.NAPAKA_KLJUC,
        UsenetPravila.napakaNzb(200, """<error code="100" description="Incorrect user credentials"/>"""))
    preveriEnako("Grok 3: Newznab 500 = meja", TorBoxPravila.NAPAKA_MEJA,
        UsenetPravila.napakaNzb(200, """<error code="500" description="Request limit reached"/>"""))
    preveriEnako("Grok 3: Newznab pred kodo HTTP", TorBoxPravila.NAPAKA_MEJA,
        UsenetPravila.napakaNzb(403, """<error code="501" description="Download limit reached"/>"""))
    preveriEnako("Grok 3: HTTP 500 = indeksator", UsenetPravila.NAPAKA_INDEKSATOR, UsenetPravila.napakaNzb(500, ""))
    preveriEnako("Grok 3: 200 brez NZB = indeksator", UsenetPravila.NAPAKA_INDEKSATOR, UsenetPravila.napakaNzb(200, "<html></html>"))
    preveriEnako("Grok 3: ponudnik izida = Usenet", UsenetPravila.VRSTA, UsenetPravila.PONUDNIK)

    // Lastnik 25. 10. 2026: kljuc indeksatorja se ob vpisu preveri (kot kljuci debrid in namizje) - zavrnjen se ne shrani.
    val ix = "9b1f4c7e2a8d6053f1e9c4b7a2d8e6f0"
    var klicev = 0
    fun preverba(izid: DebridPravila.Preverba): (String, String) -> DebridPravila.Preverba = { _, _ -> klicev++; izid }
    val vzorec = UsenetPravila.vpis("nzbfinder.ws", "your-indexer-api-key", preverba(DebridPravila.Preverba.VELJA))
    preveriEnako("vpis: vzorec kljuca brez klica indeksatorja", listOf(DebridPravila.Preverba.OBLIKA, null, 0),
        listOf(vzorec.izid, vzorec.shrani, klicev))
    val http = UsenetPravila.vpis("http://nzbfinder.ws", ix, preverba(DebridPravila.Preverba.VELJA))
    preveriEnako("vpis: http na internet brez klica", listOf(DebridPravila.Preverba.OBLIKA, null, 0), listOf(http.izid, http.shrani, klicev))
    val zavrnjen = UsenetPravila.vpis("nzbfinder.ws", ix, preverba(DebridPravila.Preverba.NE_VELJA))
    preveriEnako("vpis: zavrnjen kljuc se ne shrani", listOf(DebridPravila.Preverba.NE_VELJA, null, null, 1),
        listOf(zavrnjen.izid, zavrnjen.shrani, zavrnjen.stanje, klicev))
    val izpad = UsenetPravila.vpis(" nzbfinder.ws/api ", " $ix ", preverba(DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("vpis: brez odgovora shrani kot nepreverjen", listOf("https://nzbfinder.ws" to ix, DebridPravila.StanjeKljuca.NEPREVERJEN),
        listOf(izpad.shrani, izpad.stanje))
    val meja = UsenetPravila.vpis("nzbfinder.ws", ix, preverba(DebridPravila.Preverba.MEJA))
    preveriEnako("vpis: meja shrani kot nepreverjen", DebridPravila.StanjeKljuca.NEPREVERJEN, meja.stanje)
    val velja = UsenetPravila.vpis("nzbfinder.ws", ix, preverba(DebridPravila.Preverba.VELJA))
    preveriEnako("vpis: potrjen se shrani kot veljaven", listOf("https://nzbfinder.ws" to ix, DebridPravila.StanjeKljuca.VELJA),
        listOf(velja.shrani, velja.stanje))
    var naslovPreverbe = ""
    UsenetPravila.vpis("nzbfinder.ws", ix) { o, k -> naslovPreverbe = UsenetPravila.naslovPreverbe(o, k); DebridPravila.Preverba.VELJA }
    preveriEnako("vpis: preverba z enim iskanjem na https", "https://nzbfinder.ws/api?t=search&q=safeer&limit=1&apikey=$ix", naslovPreverbe)
    preveriEnako("preverba: 401 = zavrnjen", DebridPravila.Preverba.NE_VELJA, UsenetPravila.preverbaIndeksatorja(401, ""))
    preveriEnako("preverba: Cloudflare 403 ne zavrne kljuca", DebridPravila.Preverba.NI_ODGOVORA,
        UsenetPravila.preverbaIndeksatorja(403, "<!DOCTYPE html><title>Just a moment...</title>"))
    preveriEnako("preverba: preusmeritev ni potrditev", DebridPravila.Preverba.NI_ODGOVORA, UsenetPravila.preverbaIndeksatorja(302, ""))

    // Lastnik 25. 10. 2026: prejeti indeksator (usklajevanje, »Poslji kljuce«) se pred uporabo preveri na tej napravi.
    preveriEnako("prejem: vzorec kljuca ni indeksator", null, UsenetPravila.prejetIndeksator("https://nzbfinder.ws", "your-indexer-api-key"))
    preveriEnako("prejem: veljaven zapis", "https://nzbfinder.ws" to ix, UsenetPravila.prejetIndeksator("https://nzbfinder.ws/api", ix))
    val nas = "https://nzbfinder.ws" to ix
    val drug = "https://nzbfinder.ws" to "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    klicev = 0
    preveriEnako("prejem: nic prejeto -> zavrni brez klica", listOf(UsenetPravila.Sprejem.ZAVRNI, 0),
        listOf(UsenetPravila.sprejem(null, nas, true, preverba(DebridPravila.Preverba.VELJA)), klicev))
    preveriEnako("prejem: enak potrjenemu -> brez klica", listOf(UsenetPravila.Sprejem.ENAK, 0),
        listOf(UsenetPravila.sprejem(nas, nas, true, preverba(DebridPravila.Preverba.NE_VELJA)), klicev))
    preveriEnako("prejem: zavrnjen ne prepise veljavnega", listOf(UsenetPravila.Sprejem.ZAVRNI, 1),
        listOf(UsenetPravila.sprejem(drug, nas, true, preverba(DebridPravila.Preverba.NE_VELJA)), klicev))
    preveriEnako("prejem: brez odgovora ne prepise", UsenetPravila.Sprejem.ZAVRNI,
        UsenetPravila.sprejem(drug, nas, true, preverba(DebridPravila.Preverba.NI_ODGOVORA)))
    preveriEnako("prejem: meja ne prepise", UsenetPravila.Sprejem.ZAVRNI,
        UsenetPravila.sprejem(drug, nas, true, preverba(DebridPravila.Preverba.MEJA)))
    preveriEnako("prejem: potrjen s te naprave se shrani", UsenetPravila.Sprejem.SHRANI,
        UsenetPravila.sprejem(drug, nas, true, preverba(DebridPravila.Preverba.VELJA)))
    klicev = 0
    preveriEnako("prejem: enak, a tu nepreverjen -> preveri", listOf(UsenetPravila.Sprejem.SHRANI, 1),
        listOf(UsenetPravila.sprejem(nas, nas, false, preverba(DebridPravila.Preverba.VELJA)), klicev))

    if (napak == 0) println("UsenetPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
