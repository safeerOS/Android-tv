package si.safeer.tv.os

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun nzb(s: String, meje: UsenetNzb.Meje = UsenetNzb.Meje()) = UsenetNzb.razcleni(s.toByteArray(Charsets.UTF_8), meje)

/** Ime napake NZB ali »OK«. */
private fun izid(s: String, meje: UsenetNzb.Meje = UsenetNzb.Meje()): String =
    try { nzb(s, meje); "OK" } catch (e: UsenetNzb.NapakaNzb) { e.vrsta.name }

private fun izidBajti(b: ByteArray): String =
    try { UsenetNzb.razcleni(b); "OK" } catch (e: UsenetNzb.NapakaNzb) { e.vrsta.name }

private const val GLAVA = "<?xml version=\"1.0\" encoding=\"iso-8859-1\" ?>\n"
private const val DOCTYPE = "<!DOCTYPE nzb PUBLIC \"-//newzBin//DTD NZB 1.1//EN\" \"http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd\">\n"

/** Datoteka NZB; narekovaji v zadevi postanejo &quot; (entitete, ki jih preizkus poda sam, ostanejo). */
private fun datoteka(zadeva: String, segmenti: String) =
    "<file poster=\"Nekdo &lt;n@example.invalid&gt;\" date=\"1071674882\" subject=\"${zadeva.replace("\"", "&quot;")}\">" +
        "<groups><group>alt.binaries.test</group></groups><segments>$segmenti</segments></file>\n"

private fun seg(st: Any, id: String, bajtov: Any = 1000) = "<segment bytes=\"$bajtov\" number=\"$st\">$id</segment>"

/**
 * Pogon Usenet, korak 1: razclenitev NZB ([UsenetNzb]). Pravila so v skupnih primerih (usenet-pogon.json); tu so
 * preizkusi, ki jih skupni primeri ne morejo: zunanji DTD se ne prenese, meje ustavijo branje, zasebnost toString.
 */
fun main() {
    println("Preizkus NZB (pogon Usenet)")

    val obicajen = GLAVA + DOCTYPE + "<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n" +
        "<head><meta type=\"title\">Preizkus</meta><meta type=\"password\">skrito-geslo</meta></head>\n" +
        datoteka("[1/2] - \"film.2020.mkv\" yEnc (1/3)", seg(2, "b2@example.invalid", 102394) + seg(1, "&lt;a1@example.invalid&gt;", 102394) +
            seg(3, "c3@example.invalid", 4000)) +
        datoteka("film.2020.nfo (1/1)", seg(1, "n1@example.invalid", 300)) + "</nzb>\n"
    val n = nzb(obicajen)
    preveriEnako("obicajen: datotek", 2, n.datoteke.size)
    preveriEnako("obicajen: imena", listOf("film.2020.mkv", "film.2020.nfo"), n.datoteke.map { it.ime })
    preveriEnako("obicajen: segmenti po stevilki", listOf(1, 2, 3), n.datoteke[0].segmenti.map { it.stevilka })
    preveriEnako("obicajen: id brez < >", "a1@example.invalid", n.datoteke[0].segmenti[0].id)
    preveriEnako("obicajen: bajti", 208788L, n.datoteke[0].bajtov)
    preveriEnako("obicajen: datum", 1071674882L, n.datoteke[0].datum)
    preveriEnako("obicajen: geslo", true, n.imaGeslo)
    preveriEnako("toString brez gesla in id", false, (n.toString() + n.datoteke + n.datoteke[0].segmenti).contains("skrito") ||
        (n.toString() + n.datoteke[0].segmenti).contains("example.invalid"))

    // Zunanji DTD se ne prenese (Xerces bi ga privzeto): lokalni streznik steje povezave.
    val posluh = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val povezav = AtomicInteger()
    val nit = Thread {
        try {
            while (true) { posluh.accept().use { povezav.incrementAndGet() } }
        } catch (_: Exception) { }
    }.apply { isDaemon = true; start() }
    val dtd = "http://127.0.0.1:${posluh.localPort}/nzb.dtd"
    preveriEnako("zunanji DTD v SYSTEM: razclenjeno", "OK", izid(obicajen.replace(DOCTYPE, "<!DOCTYPE nzb SYSTEM \"$dtd\">")))
    preveriEnako("zunanji DTD v PUBLIC: razclenjeno", "OK", izid(obicajen.replace(DOCTYPE, "<!DOCTYPE nzb PUBLIC \"-//x//EN\" '$dtd'>")))
    preveriEnako("XXE z zunanjo entiteto: zavrnjeno", "ZAVRNJENO_DTD",
        izid("<!DOCTYPE nzb [<!ENTITY x SYSTEM \"$dtd\">]><nzb>" + datoteka("&x;", seg(1, "a@x")) + "</nzb>"))
    Thread.sleep(200)
    preveriEnako("zunanji DTD: nobene povezave", 0, povezav.get())
    posluh.close()
    nit.join(1000)

    // XXE, ki ga skupni primeri nimajo: parameter-entiteta, DOCTYPE sredi dokumenta, oklepaj v nizu.
    preveriEnako("parameter-entiteta", "ZAVRNJENO_DTD", izid("<!DOCTYPE nzb [<!ENTITY % p SYSTEM \"file:///etc/hostname\"> %p;]><nzb/>"))
    preveriEnako("DOCTYPE z [ v nizu", "ZAVRNJENO_DTD", izid("<!DOCTYPE nzb SYSTEM \"a[b\"><nzb>" + datoteka("a.mkv", seg(1, "a@x")) + "</nzb>"))

    // Meje stevila ustavijo branje (SAX) - preizkus z manjsimi mejami.
    val tri = "<nzb>" + datoteka("a.mkv", seg(1, "a@x")) + datoteka("b.mkv", seg(1, "b@x")) + datoteka("c.mkv", seg(1, "c@x")) + "</nzb>"
    preveriEnako("datotek do meje", "OK", izid(tri, UsenetNzb.Meje(datotek = 3)))
    preveriEnako("datotek nad mejo", "PREVELIK", izid(tri, UsenetNzb.Meje(datotek = 2)))
    val stiri = "<nzb>" + datoteka("a.mkv", seg(1, "a@x") + seg(2, "b@x")) + datoteka("b.mkv", seg(1, "c@x") + seg(2, "d@x")) + "</nzb>"
    preveriEnako("segmentov do meje", "OK", izid(stiri, UsenetNzb.Meje(segmentov = 4)))
    preveriEnako("segmentov nad mejo", "PREVELIK", izid(stiri, UsenetNzb.Meje(segmentov = 3)))
    preveriEnako("neveljavni segmenti stejejo v mejo", "PREVELIK",
        izid("<nzb>" + datoteka("a.mkv", seg(1, "a@x") + seg(0, "b@x") + seg(0, "c@x")) + "</nzb>", UsenetNzb.Meje(segmentov = 2)))
    preveriEnako("elementov nad mejo (tudi neznani)", "PREVELIK", izid("<nzb><x/><x/><x/>" + datoteka("a.mkv", seg(1, "a@x")) + "</nzb>",
        UsenetNzb.Meje(elementov = 6)))
    val mejaStevilke = nzb("<nzb>" + datoteka("a.mkv (1/6)", seg(1, "a@x") + seg(6, "f@x")) + "</nzb>", UsenetNzb.Meje(segmentovDatoteke = 5))
    preveriEnako("stevilka nad mejo datoteke izpuscena", listOf(1), mejaStevilke.datoteke[0].segmenti.map { it.stevilka })
    preveriEnako("(1/6) nad mejo datoteke se ne uposteva", 0, mejaStevilke.datoteke[0].manjka)
    val skupine = (1..70).joinToString("") { "<group>alt.binaries.g$it</group>" }
    preveriEnako("najvec 50 skupin", 50, nzb("<nzb><file subject=\"a.mkv\"><groups>$skupine</groups><segments>" +
        seg(1, "a@x") + "</segments></file></nzb>").datoteke[0].skupine.size)
    preveriEnako("skupina nad 200 znakov izpuscena", listOf<String>(), nzb("<nzb><file subject=\"a.mkv\"><groups><group>" + "g".repeat(201) +
        "</group></groups><segments>" + seg(1, "a@x") + "</segments></file></nzb>").datoteke[0].skupine)
    preveriEnako("predolgo besedilo id izpusceno", listOf(1), nzb("<nzb>" + datoteka("a.mkv", seg(1, "a@x") + seg(2, "x".repeat(5000) + "@x")) +
        "</nzb>").datoteke[0].segmenti.map { it.stevilka })
    preveriEnako("vec kot 20 MiB", "PREVELIK", izidBajti(ByteArray(UsenetPravila.NAJVEC_NZB_BAJTOV + 1) { ' '.code.toByte() }))
    preveriEnako("UTF-16 z BOM (bajti NUL)", "NAPAKA_XML", izidBajti("<nzb></nzb>".toByteArray(Charsets.UTF_16)))

    // Imenski prostor s predpono, naslov, ime brez imena.
    val predpona = "<n:nzb xmlns:n=\"http://www.newzbin.com/DTD/2003/nzb\"><n:head><n:meta type=\"title\">  A \n  B  </n:meta></n:head>" +
        "<n:file subject=\"x.mkv\"><n:segments><n:segment number=\"1\">a@x</n:segment></n:segments></n:file></n:nzb>"
    preveriEnako("predpona imenskega prostora", "OK", izid(predpona))
    preveriEnako("naslov s strnjenimi presledki", "A B", nzb(predpona).naslov)
    preveriEnako("naslov najvec 200", 200, nzb("<nzb><head><meta type=\"title\">" + "t".repeat(300) + "</meta></head>" +
        datoteka("a.mkv", seg(1, "a@x")) + "</nzb>").naslov?.length)
    preveriEnako("zakrito ime (brez pike) -> null", null, nzb("<nzb>" + datoteka("a1b2c3d4e5f6 yEnc (1/1)", seg(1, "a@x")) + "</nzb>").datoteke[0].ime)
    preveriEnako("ime: pot z entitetami v XML", "skrito.mkv", nzb("<nzb>" +
        datoteka("[1/1] - &quot;..&#47;..&#47;skrito.mkv&quot; yEnc (1/1)", seg(1, "a@x")) + "</nzb>").datoteke[0].ime)
    preveriEnako("meta zunaj head se ne uposteva", null, nzb("<nzb><meta type=\"title\">x</meta>" + datoteka("a.mkv", seg(1, "a@x")) + "</nzb>").naslov)
    preveriEnako("geslo iz samih presledkov ni geslo", false, nzb("<nzb><head><meta type=\"password\">  </meta></head>" +
        datoteka("a.mkv", seg(1, "a@x")) + "</nzb>").imaGeslo)

    // Imena (dodatno k skupnim primerom)
    preveriEnako("varno ime: lpt9", "_lpt9.txt", UsenetNzb.varnoIme("lpt9.txt"))
    preveriEnako("varno ime: com0 ni rezervirano", "com0.txt", UsenetNzb.varnoIme("com0.txt"))
    preveriEnako("varno ime: skrito ostane", ".skrito", UsenetNzb.varnoIme(".skrito"))
    preveriEnako("varno ime: nadomestni par (emoji) je en znak", "a_b", UsenetNzb.varnoIme("a\uD83C\uDFACb"))
    preveriEnako("ime iz zadeve: (a/b) prilepljeno", "abc.r01", UsenetNzb.imeIzZadeve("abc.r01(1/2)"))

    if (napak == 0) println("UsenetNzbTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
