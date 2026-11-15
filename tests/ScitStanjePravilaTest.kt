// Pregled Nastavitev in Scita (5. 11. 2026): dejansko stanje Scita (prekinjen ni »Vklopljeno«), ponovni vklop na
// zaslonu Scita, SERVFAIL za poizvedbe brez odgovora in nabor grozenj, odprt enkrat. Staticne preverbe izvorne kode
// padejo na kodi pred popravkom.  tests/run_scit_tests.sh (argument: pot do src/main/kotlin)
package si.safeer.tv.scit

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess
import si.safeer.tv.scit.ScitStanjePravila.Gumb
import si.safeer.tv.scit.ScitStanjePravila.Neodgovorjena
import si.safeer.tv.scit.ScitStanjePravila.Stanje

private var padloStanje = 0

private fun preveriStanje(pogoj: Boolean, opis: String) {
    if (pogoj) println("OK   $opis") else { padloStanje++; println("PADE $opis") }
}

fun main(args: Array<String>) {
    val P = ScitStanjePravila
    // Lastnik 15. 11. 2026: brskalnik ne filtrira oglasnih zahtev, ko to dokazano dela Scit (DNS).
    preveriStanje(!P.brskalnikFiltriraOglase(blokiranjeVklopljeno = true, dnsFiltrira = true), "Scit filtrira: brskalnik ne")
    preveriStanje(P.brskalnikFiltriraOglase(blokiranjeVklopljeno = true, dnsFiltrira = false), "Scit ne tece: brskalnik filtrira (rezerva)")
    preveriStanje(!P.brskalnikFiltriraOglase(blokiranjeVklopljeno = false, dnsFiltrira = false), "uporabnik je izklopil blokiranje: nihce v brskalniku")
    preveriStanje(!P.brskalnikFiltriraOglase(blokiranjeVklopljeno = false, dnsFiltrira = true), "izklopljeno in Scit: brskalnik ne")
    preveriStanje(P.dnsFiltrira(true, "tece", 12000, true, 1_000L), "DNS filtrira: tece, nabor, VPN")
    preveriStanje(!P.dnsFiltrira(true, "tece", 12000, false, 1_000L), "zapis tece, a VPN omrezja ni (ubit proces): ne filtrira")
    preveriStanje(!P.dnsFiltrira(true, "tece", 0, true, 1_000L), "nabor se ni nalozen: ne filtrira")
    preveriStanje(!P.dnsFiltrira(true, "ustavljen", 12000, true, 1_000L), "ustavljen: ne filtrira")
    preveriStanje(!P.dnsFiltrira(false, "tece", 12000, true, 1_000L), "uporabnik je izklopil Scit: ne filtrira")
    preveriStanje(!P.dnsFiltrira(true, "tece", 12000, true, 3_600_000L), "zapis tece in tuj VPN, a utrip star 1 h (ubit proces): ne filtrira (XX1)")
    preveriStanje(!P.dnsFiltrira(true, "tece", 12000, true, Long.MAX_VALUE), "utripa ni: ne filtrira")
    preveriStanje(!P.dnsFiltrira(true, "tece", 12000, true, -5_000L), "utrip iz prihodnosti (ura nazaj): ne filtrira")
    preveriStanje(P.dnsFiltrira(true, "tece", 12000, true, P.UTRIP_NAJVEC_MS), "utrip tocno na meji: se filtrira")
    preveriStanje(!P.dnsFiltrira(true, "tece", 12000, true, P.UTRIP_NAJVEC_MS + 1), "utrip za 1 ms prek meje: ne filtrira (ZZ7)")
    preveriStanje(P.dnsFiltrira(true, "tece", 12000, true, 0L), "utrip tocno zdaj (starost 0): filtrira (ZZ7)")
    preveriStanje(!P.dnsFiltrira(true, "tece", 12000, true, -1L), "starost -1: ne filtrira (ZZ7)")
    // ZZ4: starost iz casa od zagona + stevec zagonov
    preveriStanje(P.starostUtripaMs("1000|7", "7", 4000L) == 3000L, "starost: isti zagon = razlika")
    preveriStanje(P.starostUtripaMs("1000|7", "8", 4000L) == Long.MAX_VALUE, "starost: drug zagon = ne filtrira")
    preveriStanje(P.starostUtripaMs(null, "7", 4000L) == Long.MAX_VALUE, "starost: manjkajoc zapis")
    preveriStanje(P.starostUtripaMs("abc|7", "7", 4000L) == Long.MAX_VALUE, "starost: pokvarjen cas")
    preveriStanje(P.starostUtripaMs("0|7", "7", 4000L) == Long.MAX_VALUE, "starost: cas 0")
    preveriStanje(P.starostUtripaMs("1000", "7", 4000L) == Long.MAX_VALUE, "starost: brez stevca zagonov")
    preveriStanje(P.starostUtripaMs("9000|7", "7", 4000L) == -5000L && !P.dnsFiltrira(true, "tece", 1, true, -5000L), "starost: iz prihodnosti = ne filtrira")
    // 1: drug VPN prevzame tunel (onRevoke) - zastavica ostane, storitev ne tece.
    preveriStanje(P.stanje(vklopljen = true, tece = false) == Stanje.PREKINJEN, "vklopljen, a ne tece = prekinjen (ne Vklopljeno)")
    preveriStanje(P.stanje(true, true) == Stanje.TECE, "vklopljen in tece")
    preveriStanje(P.stanje(false, false) == Stanje.IZKLOPLJEN && P.stanje(false, true) == Stanje.IZKLOPLJEN, "izklopljen")
    preveriStanje(P.gumbi(Stanje.PREKINJEN) == listOf(Gumb.VKLOPI_ZNOVA, Gumb.IZKLOPI), "prekinjen: najprej ponovni vklop, izklop ostane")
    preveriStanje(P.gumbi(Stanje.TECE) == listOf(Gumb.IZKLOPI) && P.gumbi(Stanje.IZKLOPLJEN) == listOf(Gumb.VKLOPI), "tece / izklopljen: en gumb")

    // 10: poizvedba brez odgovora. Dva streznika, ponovitev 1200 ms, potek 8000 ms.
    fun n(zdaj: Long, zadnjic: Long, naslednji: Int) = P.neodgovorjena(zdaj, 0L, zadnjic, naslednji, 2, 1_200L, 8_000L)
    preveriStanje(n(500, 0, 1) == Neodgovorjena.CAKAJ, "prvi vprasan pred 0,5 s: caka")
    preveriStanje(n(1_200, 0, 1) == Neodgovorjena.PONOVI, "prvi molci 1,2 s: naslednji streznik")
    preveriStanje(n(2_400, 1_200, 2) == Neodgovorjena.CAKAJ, "vsi vprasani, zadnji pred 1,2 s: se caka (pocasen pravi odgovor ima prednost)")
    preveriStanje(n(1_200 + P.ROK_ZADNJEGA_MS - 1, 1_200, 2) == Neodgovorjena.CAKAJ, "tik pred rokom zadnjega: caka")
    preveriStanje(n(1_200 + P.ROK_ZADNJEGA_MS, 1_200, 2) == Neodgovorjena.SERVFAIL, "vsi vprasani in rok minil: SERVFAIL (prej tiho opusceno)")
    preveriStanje(n(8_001, 7_900, 1) == Neodgovorjena.SERVFAIL, "potek 8 s: SERVFAIL, ne molk")
    preveriStanje(P.ROK_ZADNJEGA_MS in 2_000L..4_000L, "rok zadnjega je med 2 in 4 s (manj od 5 s Androida)")
    preveriStanje(P.neodgovorjena(1_000, 0, 0, 1, 1, 1_200, 8_000) == Neodgovorjena.CAKAJ, "en streznik, 1 s: caka")

    // 3: nabor grozenj odprt enkrat, znova samo ob spremembi.
    val mapa = Files.createTempDirectory("scit-stanje").toFile()
    try {
        val f = File(mapa, "grozne.bin")
        DomenskiNabor.zapisi(f, sequenceOf(sequenceOf("zlo.example", "slabo.example")))
        val prvi = DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, null)
        preveriStanje(prvi != null && prvi.jeBlokirana("zlo.example"), "prvo odprtje")
        preveriStanje(DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, prvi) === prvi, "nespremenjena datoteka: isti nabor, brez ponovnega mapiranja")
        DomenskiNabor.zapisi(f, sequenceOf(sequenceOf("drugo.example")))
        f.setLastModified(prvi!!.spremenjena + 5_000)
        val drugi = DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, prvi)
        preveriStanje(drugi !== prvi && drugi?.jeBlokirana("drugo.example") == true, "spremenjena datoteka: odprta znova")
        f.delete()
        preveriStanje(DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, drugi) == null, "izbrisana datoteka: ni nabora")
    } finally { mapa.deleteRecursively() }

    // Drugi krog pregleda 5. 11. 2026: potekle poizvedbe (polna vrsta, vzdrzevanje) dobijo SERVFAIL; pravi odgovor zmaga.
    run {
        val vrsta = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        vrsta[1] = 0L; vrsta[2] = 5_000L; vrsta[3] = 100L
        val servfail = ArrayList<Long>()
        val n = P.opustiPotekle(vrsta, 9_000L, 8_000L, { it }) { servfail.add(it) }
        preveriStanje(n == 2 && servfail.sorted() == listOf(0L, 100L) && vrsta.keys == setOf(2), "potekle: SERVFAIL za vsako, sveza ostane")
        // Pravi odgovor je poizvedbo vzel med pregledom (remove(id, c) ne uspe): SERVFAIL se ne poslje.
        val tekma = object : java.util.concurrent.ConcurrentHashMap<Int, Long>() {
            override fun remove(key: Int, value: Long): Boolean { super.remove(key); return false }
        }
        tekma[7] = 0L
        val poslano = ArrayList<Long>()
        P.opustiPotekle(tekma, 9_000L, 8_000L, { it }) { poslano.add(it) }
        preveriStanje(poslano.isEmpty(), "potekla, a pravi odgovor jo je vzel prej: brez SERVFAIL")
    }
    // Drugi krog pregleda 5. 11. 2026: osvezevanje zaslona Scita - najvec eno branje, nujna zahteva se ponovi enkrat.
    run {
        val b = ScitStanjePravila.EnoBranje()
        preveriStanje(b.zacni(false), "prvo branje se zacne")
        preveriStanje(!b.zacni(false) && !b.zacni(false), "osvezitev med branjem se ne doda v vrsto")
        preveriStanje(!b.konec(), "konec brez nujne zahteve: nic vec")
        preveriStanje(b.zacni(false), "po koncu se novo branje zacne")
        preveriStanje(!b.zacni(true), "nujna zahteva med branjem se ne zacne vzporedno")
        preveriStanje(b.konec(), "... a se izvede po koncu")
        preveriStanje(!b.konec() && b.zacni(true), "enkrat; potem prosto")
    }

    // Staticne preverbe izvorne kode (padejo pred popravkom 5. 11. 2026).
    val src = File(args.getOrNull(0) ?: error("pot do src/main/kotlin"))
    val storitev = File(src, "si/safeer/tv/scit/ScitStoritev.kt").readText()
    val ponovi = storitev.substringAfter("private fun ponoviNeodgovorjene()").substringBefore("private fun zankaOdgovorov()")
    preveriStanje("odgovorNapakaZa" in ponovi && "cakajoce.remove(id, c)" in ponovi, "ScitStoritev: neodgovorjena dobi SERVFAIL, odstranitev atomarna")
    val zaslon = File(src, "si/safeer/tv/scit/ScitActivity.kt").readText()
    preveriStanje("Gumb.VKLOPI_ZNOVA" in zaslon, "ScitActivity: prekinjen ponudi ponovni vklop")
    val preberi = zaslon.substringAfter("private fun preberi(): Pogled").substringBefore("private fun danes()")
    preveriStanje("Poizvedba.odpri(" !in preberi && "zasebniDnsObide()" !in preberi, "ScitActivity.preberi: brez mapiranja ob vsakem branju in brez poizvedb omrezij")
    val narisi = zaslon.substringAfter("private fun narisi(prisilno: Boolean").substringBefore("private fun izrisi(")
    preveriStanje("vOzadju" in narisi, "ScitActivity.narisi: branje na delovni niti")
    preveriStanje("branje.zacni(prisilno)" in narisi && "branje.konec()" in narisi, "ScitActivity.narisi: najvec eno branje hkrati (drugi krog)")
    preveriStanje("removeIf" !in storitev && storitev.split("opustiPotekle(zdaj)").size == 3, "ScitStoritev: potekle ob polni vrsti in v vzdrzevanju dobijo SERVFAIL (drugi krog)")
    val nastavitve = File(src, "si/safeer/tv/os/NastavitveActivity.kt").readText()
    preveriStanje("vklop(Scit.jeVklopljen(this))" !in nastavitve && "ScitStanjePravila.stanje(" in nastavitve, "Nastavitve: vrstica Scita kaze dejansko stanje")
    // CodeRabbit 5. 11. 2026: po preklopu se stanje Scita prebere znova (prej narisi() s starim scitStanje).
    val preklop = nastavitve.substringAfter("private fun preklopiScit()").substringBefore("private fun izhod()")
    preveriStanje("Scit.izklopi(this) { osveziScit() }" in preklop && "else osveziScit()" in preklop && "narisi()" !in preklop,
        "Nastavitve.preklopiScit: po izklopu in vklopu novo stanje Scita")
    preveriStanje(nastavitve.contains("private fun osveziScit()") && nastavitve.substringAfter("override fun onResume()").substringBefore("override fun ").contains("osveziScit()"),
        "Nastavitve.onResume: stanje Scita prek osveziScit")

    // XX2: YouTube oglasi so na istem gostitelju kot vsebina: DNS jih ne pokrije, brskalnik filtrira sam.
    preveriStanje(P.dnsNePokrije("https://rr1---sn-abc.googlevideo.com/videoplayback?oad=1"), "googlevideo: DNS ne pokrije")
    preveriStanje(P.dnsNePokrije("https://www.YouTube.com/youtubei/v1/player"), "youtube.com: DNS ne pokrije")
    preveriStanje(!P.dnsNePokrije("https://www.24ur.com/static/x.js"), "navaden gostitelj: DNS pokrije")
    preveriStanje(P.dnsNePokrije("https://www.youtube-nocookie.com/embed/x"), "FFF6: youtube-nocookie ni pokrit z DNS")
    preveriStanje(P.dnsNePokrije("https://i.ytimg.com/vi/x/a.jpg") && P.dnsNePokrije("https://youtu.be/x"), "FFF6: ytimg in youtu.be")
    preveriStanje(!P.dnsNePokrije("https://tuja.si/a?r=youtube.com"), "FFF6: podniz v poizvedbi ni gostitelj")
    preveriStanje(P.dnsNePokrije("https://api.tuja.si/youtubei/v1/player"), "FFF6: pot /youtubei/")
    // ZZ1/ZZ2: utrip samo, ce tece storitev IN obe zanki; sicer ne (zombi: urnik zivi, zanka mrtva).
    preveriStanje(P.utripSmeZapisati(tece = true, zankaPaketovZiva = true, zankaOdgovorovZiva = true), "utrip: vse tece")
    preveriStanje(!P.utripSmeZapisati(tece = true, zankaPaketovZiva = false, zankaOdgovorovZiva = true), "utrip: zanka paketov mrtva = brez utripa")
    preveriStanje(!P.utripSmeZapisati(tece = true, zankaPaketovZiva = true, zankaOdgovorovZiva = false), "utrip: zanka odgovorov mrtva = brez utripa")
    preveriStanje(!P.utripSmeZapisati(tece = false, zankaPaketovZiva = true, zankaOdgovorovZiva = true), "utrip: storitev ustavljena = brez utripa")
    // CCC1: milost - ustavitev sele po vec zaporednih neuspehih
    preveriStanje(!P.ustaviZaradiZank(0) && !P.ustaviZaradiZank(1) && !P.ustaviZaradiZank(2), "zanke: 1-2 neuspeha = se ne ustavi (CCC1)")
    preveriStanje(P.ustaviZaradiZank(P.NAJVEC_SLABIH) && P.ustaviZaradiZank(P.NAJVEC_SLABIH + 5), "zanke: po meji neuspehov se ustavi (CCC1)")
    // CCC2/CCC6: kljucavnica stanja, ustaviVse izkljucujoc, polja nit/nitOdgovorov @Volatile
    preveriStanje(storitev.contains("private fun ustaviVse(stanje: String) = synchronized(kljucStanja)"), "ustaviVse je izkljucujoc (CCC2)")
    preveriStanje(storitev.contains("@Volatile private var nit: Thread?") && storitev.contains("@Volatile private var nitOdgovorov: Thread?"), "nit in nitOdgovorov @Volatile (CCC6)")
    preveriStanje(storitev.substringAfter("private fun utripNaloga()").substringBefore("private fun ").contains("synchronized(kljucStanja)"), "utripNaloga pod kljucavnico stanja (CCC2)")
    // ZZ3: utrip v loceni nalogi (utripNaloga), preverba zanke PRED Scit.utrip, vzdrzuj utripa ne pise.
    val utripNaloga = storitev.substringAfter("private fun utripNaloga()").substringBefore("private fun ")
    preveriStanje(utripNaloga.indexOf("utripSmeZapisati(tece, nit?.isAlive == true, nitOdgovorov?.isAlive == true)") in 0 until utripNaloga.indexOf("Scit.utrip(this)"),
        "utripNaloga: preverba zanke PRED Scit.utrip")
    val vzdrzuj = storitev.substringAfter("private fun vzdrzuj()").substringBefore("private fun ")
    preveriStanje(!vzdrzuj.contains("Scit.utrip(this)"), "vzdrzuj ne pise utripa (ZZ3: loceno od pocasnega dela)")
    preveriStanje(vzdrzuj.contains("if (!tece) return"), "vzdrzuj: po zaustavitvi ne zapise statistike (ZZ2)")
    preveriStanje(storitev.contains("scheduleWithFixedDelay({ utripNaloga() }, 10, 10, TimeUnit.SECONDS)"), "utripNaloga se razporedi vsakih 10 s")

    if (padloStanje > 0) { println("ScitStanjePravila: $padloStanje padlo"); exitProcess(1) }
    println("ScitStanjePravila: vse v redu")
}
