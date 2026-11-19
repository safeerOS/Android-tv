package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki
import si.safeer.tv.os.LinaVmesnikPravila as P
import java.io.File
import java.security.MessageDigest

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Vektorji so ISTA datoteka kot v Lina repozitoriju (openline/android/skupni-vektorji); odtis je pripet. */
private const val ODTIS_VEKTORJEV = "ab39ba11abf787e1b3a15315eb637bc825341236d14243460e1469d0d4627461"

private fun zapiski(surovo: String): List<P.Zapisek>? = P.izJson("{\"verzija\":1,\"zapiski\":$surovo,\"opomniki\":[]}")

/**
 * Pravila lokalnega vmesnika Safeer OS za Lino: ISTI skupni vektorji zdruzevanja kot Lina in racunalnik (v obe smeri),
 * klicatelj samo Lina z istim podpisom, imena kljucev samo v imenskem prostoru asistenta, stroga meja vnosa.
 * Zagon: java ... LinaVmesnikPravilaTestKt <pot do zapiski-zdruzitev-v1.json> [pot do izvirnika v Lina repozitoriju]
 */
fun main(args: Array<String>) {
    val pot = args.getOrNull(0) ?: "tests/skupni-vektorji/zapiski-zdruzitev-v1.json"
    val bajti = File(pot).readBytes()
    preveriEnako("vektorji so ista datoteka kot v Lini (SHA-256)", ODTIS_VEKTORJEV, sha256(bajti))
    args.getOrNull(1)?.let { izvirnik ->
        preveriEnako("vektorji so enaki izvirniku v Lina repozitoriju", sha256(File(izvirnik).readBytes()), sha256(bajti))
    }

    val koren = JsonLahki.objekt(String(bajti, Charsets.UTF_8))!!
    val primeri = JsonLahki.elementi(koren.surovo("primeri")!!)!!
    preveriEnako("vektorji niso okrnjeni (najmanj 7 primerov)", true, primeri.size >= 7)
    for (p in primeri) {
        val o = JsonLahki.objekt(p)!!
        val ime = o.niz("ime")!!
        val a = zapiski(o.surovo("a")!!)
        val b = zapiski(o.surovo("b")!!)
        val pricakovano = zapiski(o.surovo("pricakovano")!!)
        preveriEnako("vektor se razbere: $ime", true, a != null && b != null && pricakovano != null)
        if (a == null || b == null || pricakovano == null) continue
        preveriEnako("a,b: $ime", pricakovano, P.zdruzi(a, b))
        preveriEnako("b,a: $ime", pricakovano, P.zdruzi(b, a))
        preveriEnako("izjson(vjson(x)) ohrani vse: $ime", pricakovano, P.izJson(P.vJson(pricakovano)))
    }

    // Ciscenje nagrobnikov: ISTI vektorji kot Lina (razdelek ciscenje).
    val ciscenje = JsonLahki.elementi(koren.surovo("ciscenje")!!)!!
    preveriEnako("vektorji ciscenja niso okrnjeni (najmanj 7 primerov)", true, ciscenje.size >= 7)
    for (c in ciscenje) {
        val o = JsonLahki.objekt(c)!!
        val ime = o.niz("ime")!!
        val vhod = zapiski(o.surovo("vhod")!!)
        val pricakovano = zapiski(o.surovo("pricakovano")!!)
        if (vhod == null || pricakovano == null) { preveriEnako("ciscenje se razbere: $ime", true, false); continue }
        val izid = P.pocistiNagrobnike(vhod, o.stevilo("zdaj")!!.toLong(), o.stevilo("rok")!!.toLong(),
            o.stevilo("najvecZapiskov")!!.toInt(), o.stevilo("najvecZnakov")!!.toInt())
        preveriEnako("ciscenje, zapiski: $ime", pricakovano, izid.zapiski)
        preveriEnako("ciscenje, sprejeto: $ime", o.logicno("sprejeto")!!, izid.sprejeto)
    }

    // Klicatelj: samo Lina z ISTIM podpisom (preizkus mora brez pravila pasti: drug podpis, drug paket, ne sam sebi).
    preveriEnako("Lina z istim podpisom", true, P.jeLina("si.safeer.lina", "si.safeer.os", podpisEnak = true))
    preveriEnako("Lina z drugim podpisom nikoli (ponaredek)", false, P.jeLina("si.safeer.lina", "si.safeer.os", podpisEnak = false))
    preveriEnako("sorodnik Safeer ni Lina (Predvajalnik)", false, P.jeLina("si.safeer.player", "si.safeer.os", podpisEnak = true))
    preveriEnako("tuja aplikacija nikoli", false, P.jeLina("com.tuj.app", "si.safeer.os", podpisEnak = true))
    preveriEnako("brez paketa nikoli", false, P.jeLina(null, "si.safeer.os", podpisEnak = true))
    preveriEnako("vmesnik ne sluzi Lini, ce je ta aplikacija Lina", false, P.jeLina("si.safeer.lina", "si.safeer.lina", podpisEnak = true))
    preveriEnako("Lina ni na seznamu sorodnikov za medijske kljuce", false, "si.safeer.lina" in UskladitevKljucevPravila.SORODNIKI)

    // Kljuci asistenta: samo imenski prostor `asistent.`; nikoli medijski kljuci.
    preveriEnako("ime asistent.brave", true, P.veljavnoImeKljuca("asistent.brave"))
    preveriEnako("ime asistent.twilio_sid", true, P.veljavnoImeKljuca("asistent.twilio_sid"))
    preveriEnako("ime debrid:torbox zavrnjeno", false, P.veljavnoImeKljuca("debrid:torbox"))
    preveriEnako("ime usenet zavrnjeno", false, P.veljavnoImeKljuca("usenet"))
    preveriEnako("ime brez imena zavrnjeno", false, P.veljavnoImeKljuca("asistent."))
    preveriEnako("ime z velikimi crkami zavrnjeno", false, P.veljavnoImeKljuca("asistent.Brave"))
    preveriEnako("ime z .. zavrnjeno", false, P.veljavnoImeKljuca("asistent.a/../b"))
    preveriEnako("ime s presledkom/novo vrstico zavrnjeno", false, P.veljavnoImeKljuca("asistent.a\nb"))
    preveriEnako("predolgo ime zavrnjeno", false, P.veljavnoImeKljuca("asistent." + "a".repeat(41)))
    preveriEnako("vrednost kljuca", true, P.veljavnaVrednostKljuca("sk-test"))
    preveriEnako("prazna vrednost zavrnjena", false, P.veljavnaVrednostKljuca(""))
    preveriEnako("ničelni znak zavrnjen", false, P.veljavnaVrednostKljuca("a\u0000b"))
    preveriEnako("predolga vrednost zavrnjena", false, P.veljavnaVrednostKljuca("x".repeat(P.NAJVEC_KLJUC_DOLZINA + 1)))
    // Usklajevanje (UskladitevKljucevPravila.veljavenZapis) zavrne \r in druge kontrolne znake: lokalna pravila naj bodo enako stroga,
    // sicer vmesnik kljuc sprejme, krog pa ga tiho zavrze (CodeRabbit, 19. 11. 2026).
    preveriEnako("CRLF v vrednosti zavrnjen", false, P.veljavnaVrednostKljuca("sk-test\r\n"))
    preveriEnako("nova vrstica v vrednosti zavrnjena", false, P.veljavnaVrednostKljuca("a\nb"))
    preveriEnako("tabulator v vrednosti zavrnjen", false, P.veljavnaVrednostKljuca("a\tb"))
    preveriEnako("DEL v vrednosti zavrnjen", false, P.veljavnaVrednostKljuca("a\u007fb"))

    // Stroga meja vnosa: napacen vnos ne sme tiho spremeniti shrambe (null).
    fun z(vsebina: String) = P.izJson(vsebina)
    val ok = "{\"id\":\"a\",\"besedilo\":\"x\",\"cas\":5,\"izbrisan\":false}"
    preveriEnako("veljaven vnos", 1, z("{\"verzija\":1,\"zapiski\":[$ok]}")?.size)
    preveriEnako("verzija 2 zavrnjena", null, z("{\"verzija\":2,\"zapiski\":[$ok]}"))
    preveriEnako("brez verzije zavrnjeno", null, z("{\"zapiski\":[$ok]}"))
    preveriEnako("podvojen id zavrnjen", null, z("{\"verzija\":1,\"zapiski\":[$ok,$ok]}"))
    preveriEnako("cas kot niz zavrnjen", null, z("{\"verzija\":1,\"zapiski\":[{\"id\":\"a\",\"besedilo\":\"x\",\"cas\":\"5\",\"izbrisan\":false}]}"))
    preveriEnako("izbrisan kot niz zavrnjen", null, z("{\"verzija\":1,\"zapiski\":[{\"id\":\"a\",\"besedilo\":\"x\",\"cas\":5,\"izbrisan\":\"da\"}]}"))
    preveriEnako("negativen cas zavrnjen", null, z("{\"verzija\":1,\"zapiski\":[{\"id\":\"a\",\"besedilo\":\"x\",\"cas\":-1,\"izbrisan\":false}]}"))
    preveriEnako("ulomljen cas zavrnjen", null, z("{\"verzija\":1,\"zapiski\":[{\"id\":\"a\",\"besedilo\":\"x\",\"cas\":5.5,\"izbrisan\":false}]}"))
    preveriEnako("prazen id zavrnjen", null, z("{\"verzija\":1,\"zapiski\":[{\"id\":\"\",\"besedilo\":\"x\",\"cas\":5,\"izbrisan\":false}]}"))
    preveriEnako("predolgo besedilo zavrnjeno", null, z("{\"verzija\":1,\"zapiski\":[{\"id\":\"a\",\"besedilo\":\"${"x".repeat(P.NAJVEC_BESEDILO + 1)}\",\"cas\":5,\"izbrisan\":false}]}"))
    preveriEnako("zapiski ni seznam zavrnjeno", null, z("{\"verzija\":1,\"zapiski\":{}}"))
    preveriEnako("prazno besedilo zavrnjeno", null, z(""))
    preveriEnako("nestrogi JSON zavrnjen", null, z("{verzija:1,zapiski:[]}"))
    preveriEnako("prazen seznam je veljaven", 0, z("{\"verzija\":1,\"zapiski\":[]}")?.size)
    val vecZapiskov = (0..P.NAJVEC_ZAPISKOV).joinToString(",", "[", "]") { "{\"id\":\"i$it\",\"besedilo\":\"\",\"cas\":1,\"izbrisan\":false}" }
    // Meja stevila se uporabi sele po ciscenju (vhod z nagrobniki nad mejo se mora dati pociscenega sprejeti).
    val vecZ = z("{\"verzija\":1,\"zapiski\":$vecZapiskov}")
    preveriEnako("prevec zivih zapiskov: vhod se razbere, a sprejeto=false", false, vecZ?.let { P.pocistiNagrobnike(it, 100).sprejeto })

    // Meje shrambe (en Binder kos): prevec zapiskov ali prevelika celotna shramba se ne shrani.
    preveriEnako("shramba: zmerna velikost sme", true, P.smeShraniti(listOf(P.Zapisek("a", "x", 1, false))))
    preveriEnako("shramba: prevec zapiskov ne", false, P.smeShraniti((0..P.NAJVEC_ZAPISKOV).map { P.Zapisek("i$it", "", 1, false) }))
    preveriEnako("shramba: prevelika vsebina ne", false, P.smeShraniti((0 until 20).map { P.Zapisek("i$it", "x".repeat(P.NAJVEC_BESEDILO), 1, false) }))
    preveriEnako("shramba: komaj znotraj meje sme", true, P.smeShraniti(listOf(P.Zapisek("i0", "x".repeat(P.NAJVEC_ZNAKOV), 1, false))))
    preveriEnako("shramba: en znak cez skupno mejo ne", false, P.smeShraniti(listOf(P.Zapisek("i0", "x".repeat(P.NAJVEC_ZNAKOV), 1, false), P.Zapisek("i1", "y", 1, false))))

    // Neuspel zapis ne sme pokazati zavrnjene spremembe (SharedPreferences obdrzi novo vrednost v pomnilniku kot ta lazna shramba).
    class LaznaShramba(var vrednost: String?, var uspeh: Boolean) {
        fun pisi(nov: String): Boolean { vrednost = nov; return uspeh }
    }
    val a1 = P.Zapisek("a", "prvi", 10, false)
    val sh = LaznaShramba(P.vJson(listOf(a1)), uspeh = true)
    val trajni = P.TrajniZapiski({ sh.vrednost }, sh::pisi, { 100L })
    preveriEnako("trajno: zacetno stanje", listOf(a1), trajni.zapiski())
    sh.uspeh = false
    preveriEnako("trajno: neuspel izbris vrne null", null, trajni.sprejmi(listOf(P.Zapisek("a", "prvi", 20, true))))
    preveriEnako("trajno: po neuspelem izbrisu zapisek ostane viden", listOf(a1), trajni.zapiski())
    sh.uspeh = true
    val izbrisan = P.Zapisek("a", "prvi", 20, true)
    preveriEnako("trajno: ponovitev uspe", listOf(izbrisan), trajni.sprejmi(listOf(izbrisan)))
    preveriEnako("trajno: po uspehu je izbris viden", listOf(izbrisan), trajni.zapiski())
    preveriEnako("trajno: prevelika shramba ne spremeni stanja", null, trajni.sprejmi((0..P.NAJVEC_ZAPISKOV).map { P.Zapisek("i$it", "", 1, false) }))
    preveriEnako("trajno: stanje po zavrnjeni prevelikosti nespremenjeno", listOf(izbrisan), trajni.zapiski())

    // Hitra razlicica ciscenja mora dati ISTI izid kot dobesedni prenos iz Line (en najstarejsi nagrobnik naenkrat).
    fun referencno(seznam: List<P.Zapisek>, zdaj: Long, rok: Long, nz: Int, nzn: Int): P.IzidCiscenja {
        val s = seznam.filterNot { it.izbrisan && it.cas < zdaj - rok }.toMutableList()
        while (s.size > nz || P.znakov(s) > nzn) {
            val n = s.filter { it.izbrisan }.minWithOrNull(compareBy<P.Zapisek>({ it.cas }, { it.id })) ?: break
            s.remove(n)
        }
        return P.IzidCiscenja(s.sortedBy { it.id }, s.size <= nz && P.znakov(s) <= nzn)
    }
    val nakljucje = java.util.Random(1911)
    var razlike = 0
    repeat(400) {
        val n = nakljucje.nextInt(60)
        val sez = (0 until n).map { P.Zapisek("i%03d".format(it), "x".repeat(nakljucje.nextInt(6)) + (if (nakljucje.nextInt(5) == 0) "\uD83D\uDE00" else ""), nakljucje.nextInt(20).toLong(), nakljucje.nextInt(3) != 0) }
        val nz = nakljucje.nextInt(40); val nzn = nakljucje.nextInt(120)
        if (P.pocistiNagrobnike(sez, 20, 8, nz, nzn) != referencno(sez, 20, 8, nz, nzn)) razlike++
    }
    preveriEnako("ciscenje: hitra razlicica = dobesedni prenos (400 nakljucnih vhodov)", 0, razlike)
    val veliko = (0 until 30000).map { P.Zapisek("v%05d".format(it), "a", it.toLong(), true) }
    val t0 = System.nanoTime()
    val velik = P.pocistiNagrobnike(veliko, 30000, 1_000_000, 10, P.NAJVEC_ZNAKOV)
    preveriEnako("ciscenje: 30000 nagrobnikov, meja 10 -> ostane 10 najnovejsih", 10, velik.zapiski.size)
    preveriEnako("ciscenje: hitro (pod 2 s)", true, (System.nanoTime() - t0) < 2_000_000_000L)

    // Ciscenje nagrobnikov v shrambi: rok 90 dni, mej se ne preseze z zivimi zapiski, zivi zapiski nikoli ne izginejo.
    val zdaj = 10L * P.ROK_NAGROBNIKA_MS
    val staraSh = LaznaShramba(null, uspeh = true)
    val ciscena = P.TrajniZapiski({ staraSh.vrednost }, staraSh::pisi, { zdaj })
    val starNagrobnik = P.Zapisek("t1", "x", zdaj - P.ROK_NAGROBNIKA_MS - 1, true)
    val novNagrobnik = P.Zapisek("t2", "y", zdaj - P.ROK_NAGROBNIKA_MS, true)   // tocno na roku: ostane (cas < zdaj - rok)
    val zivi = P.Zapisek("a", "zivo", 1, false)
    preveriEnako("shramba: star nagrobnik se odstrani, zivi in nov ostaneta", listOf(zivi, novNagrobnik),
        ciscena.sprejmi(listOf(zivi, starNagrobnik, novNagrobnik)))
    val polna = (0 until P.NAJVEC_ZAPISKOV).map { P.Zapisek("i%05d".format(it), "", 1, false) }
    val polnaSh = LaznaShramba(null, uspeh = true)
    val polnaTrajna = P.TrajniZapiski({ polnaSh.vrednost }, polnaSh::pisi, { zdaj })
    preveriEnako("shramba: 5000 zivih sprejeto", P.NAJVEC_ZAPISKOV, polnaTrajna.sprejmi(polna)?.size)
    preveriEnako("shramba: zivi zapisek nad mejo sprejeto=false (null)", null, polnaTrajna.sprejmi(listOf(P.Zapisek("z", "", 1, false))))
    preveriEnako("shramba: nagrobnik nad mejo odstrani sam sebe (sprejeto)", P.NAJVEC_ZAPISKOV,
        polnaTrajna.sprejmi(listOf(P.Zapisek("z", "", zdaj, true)))?.size)
    // Znaki: kodne tocke Unicode (emoji = 1), skupna meja
    val velika = P.Zapisek("v", "x".repeat(P.NAJVEC_ZNAKOV), 1, false)
    preveriEnako("shramba: komaj na meji znakov sprejeto", 1, LaznaShramba(null, true).let { s -> P.TrajniZapiski({ s.vrednost }, s::pisi, { zdaj }).sprejmi(listOf(velika))?.size })
    preveriEnako("shramba: en znak cez mejo zavrnjeno", null, LaznaShramba(null, true).let { s -> P.TrajniZapiski({ s.vrednost }, s::pisi, { zdaj }).sprejmi(listOf(velika, P.Zapisek("w", "y", 1, false)))?.size })
    preveriEnako("znakov: emoji je 1 kodna tocka", 2, P.znakov(listOf(P.Zapisek("a", "\uD83D\uDE00\uD83D\uDE00", 1, false))))

    // Stopnja 2: kategorija zapiskov v krogu.
    val dobri = P.vJson(listOf(P.Zapisek("a", "x", 1, false)))
    preveriEnako("tovor: prava kategorija", 1, P.izTovora(P.KATEGORIJA_ZAPISKI, dobri)?.size)
    preveriEnako("tovor: tuja kategorija se ne sprejme", null, P.izTovora("safeer.media.v1", dobri))
    preveriEnako("tovor: brez kategorije", null, P.izTovora(null, dobri))
    preveriEnako("tovor: brez podatkov", null, P.izTovora(P.KATEGORIJA_ZAPISKI, null))
    preveriEnako("tovor: pokvarjen JSON", null, P.izTovora(P.KATEGORIJA_ZAPISKI, "{\"verzija\":2,\"zapiski\":[]}"))
    val za = P.Zapisek("a", "x", 1, false)
    val zb = P.Zapisek("b", "y", 1, false)
    preveriEnako("objava: enako stanje ne ponovi", false, P.potrebnaObjava(listOf(za, zb), listOf(zb, za)))
    preveriEnako("objava: prejemnik ima vec", true, P.potrebnaObjava(listOf(za), listOf(za, zb)))

    // Simulacija dveh naprav: vsaka sprejme in po pravilu ponovno objavi; po koncnem stevilu korakov imata isto stanje in izmenjava
    // se ustavi (brez ping-ponga).
    // Clan se ne sme imenovati kot zunanja spremenljivka (trajni): v lokalnem razredu jo ta zasenci (ujeto 19. 11. 2026).
    class Naprava(zacetno: List<P.Zapisek>) {
        var vrednost: String? = P.vJson(zacetno)
        val hramba = P.TrajniZapiski({ vrednost }, { vrednost = it; true }, { 1000L })
        /** Sprejem; vrne zapiski za ponovno objavo ali null. */
        fun sprejmi(vhod: List<P.Zapisek>): List<P.Zapisek>? {
            val z = this.hramba.sprejmi(vhod) ?: return null
            return if (P.potrebnaObjava(vhod, z)) z else null
        }
    }
    val n1 = Naprava(listOf(P.Zapisek("a", "n1", 10, false), P.Zapisek("c", "samo n1", 5, false)))
    val n2 = Naprava(listOf(P.Zapisek("a", "n2", 20, false), P.Zapisek("b", "samo n2", 7, true)))
    var cakajoce: Pair<Naprava, List<P.Zapisek>>? = n2 to n1.hramba.zapiski() // n1 objavi n2
    var sporocil = 0
    while (cakajoce != null && sporocil < 10) {
        val (komu, kaj) = cakajoce
        sporocil++
        val nazaj = komu.sprejmi(kaj)
        cakajoce = if (nazaj == null) null else (if (komu === n1) n2 else n1) to nazaj
    }
    preveriEnako("simulacija: napravi imata isto stanje", n1.hramba.zapiski(), n2.hramba.zapiski())
    preveriEnako("simulacija: izmenjava se ustavi (najvec 3 sporocila)", true, sporocil <= 3)
    preveriEnako("simulacija: novejsi zapis zmaga, izbris ostane", listOf("a:n2:20", "b:samo n2:7", "c:samo n1:5"),
        n1.hramba.zapiski().map { "${it.id}:${it.besedilo}:${it.cas}" })

    if (napak == 0) println("LinaVmesnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
