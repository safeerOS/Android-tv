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

    // Meja shrambe je v BAJTIH UTF-8 (enaka meji v Hubu), ne v znakih: dvobajtni znaki imajo se prostor, emojiji ne.
    preveriEnako("bajti: 262144 x c (2 B) sme", true, P.smeShraniti(listOf(P.Zapisek("a", "\u010D".repeat(P.NAJVEC_ZNAKOV), 1, false))))
    preveriEnako("bajti: 262144 emojijev (4 B, >1 MiB) ne", false, P.smeShraniti(listOf(P.Zapisek("a", "\uD83D\uDE00".repeat(P.NAJVEC_ZNAKOV), 1, false))))
    preveriEnako("bajti: 5000 zapiskov s 52 znaki sme (tipicno polno stanje)", true,
        P.smeShraniti((0 until P.NAJVEC_ZAPISKOV).map { P.Zapisek("id-%05d-0123456789abcdef0123456789abcd".format(it), "x".repeat(52), 1, false) }))

    // Binder: stran stanja (en odgovor ne sme preseci meje; strani skupaj dajo celo stanje).
    val vseZ = (0 until 250).map { P.Zapisek("n%04d".format((it * 7) % 250), "b".repeat(40 + it), it.toLong(), it % 5 == 0) }
    var od = 0
    val zbrano = ArrayList<P.Zapisek>()
    var strani = 0
    var najvecjaStran = 0
    while (od >= 0 && strani < 1000) {
        val s = P.stran(vseZ, od, 5000)
        najvecjaStran = maxOf(najvecjaStran, s.json.length)
        zbrano.addAll(P.izJson(s.json) ?: error("stran ni veljaven JSON"))
        strani++
        if (s.naslednji != -1) check(s.naslednji > od) { "stran ne napreduje" }
        od = s.naslednji
    }
    preveriEnako("strani: skupaj enako celemu stanju (urejeno po id)", vseZ.sortedBy { it.id }, zbrano)
    preveriEnako("strani: vec strani, vsaka znotraj meje (+ovojnica)", true, strani > 3 && najvecjaStran <= 5000 + 100)
    preveriEnako("strani: zunaj obsega je prazna in brez naslednje", P.Stran(P.vJson(emptyList()), -1), P.stran(vseZ, 250))
    preveriEnako("strani: negativen indeks je prazen", -1, P.stran(vseZ, -1).naslednji)
    val dolg = listOf(P.Zapisek("a", "x".repeat(9000), 1, false), P.Zapisek("b", "y", 1, false))
    preveriEnako("strani: dolg zapisek je sam na strani in stran napreduje", 1, P.stran(dolg, 0, 5000).naslednji)
    preveriEnako("strani: prazno stanje", P.Stran(P.vJson(emptyList()), -1), P.stran(emptyList(), 0))

    // Binder je oneway (Messenger.send): najvec polovica ~1 MB medpomnilnika, Bundle pa 2 B/znak (neodvisni pregled 19. 11. 2026).
    preveriEnako("binder: najvec znakov v enem sporocilu gre v 256 KB (2 B/znak)", true, P.BINDER_NAJVEC_ZNAKOV * 2 <= 256 * 1024)
    preveriEnako("binder: stran je manjsa od meje sporocila", true, P.BINDER_STRAN_ZNAKOV < P.BINDER_NAJVEC_ZNAKOV)
    val predolg = P.Zapisek("b", "z".repeat(P.BINDER_NAJVEC_ZNAKOV), 1, false)   // zapisek, ki nikoli ne gre cez Binder
    val sPredolg = P.stran(listOf(P.Zapisek("a", "x", 1, false), predolg, P.Zapisek("c", "y", 1, false)), 0)
    preveriEnako("binder: predolg zapisek se izpusti in javi", 1, sPredolg.izpuscenih)
    preveriEnako("binder: ostala zapiska sta na strani", listOf("a", "c"), P.izJson(sPredolg.json)?.map { it.id })
    preveriEnako("binder: stran se konca (izpuscen zapisek ne ustavi napredka)", -1, sPredolg.naslednji)
    preveriEnako("binder: stran samo s predolgim zapiskom je prazna in napreduje", Triple(0, -1, 1),
        P.stran(listOf(predolg), 0).let { Triple(P.izJson(it.json)?.size, it.naslednji, it.izpuscenih) })

    // Velikost na zici: org.json na Androidu zapise '/' kot \/ (2 B), zato mora meja shrambe steti tudi te bajte.
    preveriEnako("zica: a/b/ = 4 B + 2 posevnici", 6L, P.zicnaVelikost("a/b/"))
    val posevnice = (0 until P.NAJVEC_ZAPISKOV).map { P.Zapisek("/".repeat(100) + "%04d".format(it), "", 1, false) }   // ~0,8 MB UTF-8, ~1,3 MB na zici
    preveriEnako("zica: stanje je pod mejo v bajtih UTF-8", true, P.vJson(posevnice).toByteArray(Charsets.UTF_8).size <= P.NAJVEC_SINH_BAJTOV)
    preveriEnako("zica: a nad mejo na zici (posevnice) ne sme v shrambo", false, P.smeShraniti(posevnice))

    // Meja v obliki mesh.route (CodeRabbit st4): narekovaji in poslano crte se v ovitem sporocilu podvojijo.
    val besediloMesh = "a\"b\\c/d\u2028e\u00e8\uD83D\uDE00"
    val zapisMesh = P.vJson(listOf(P.Zapisek("id/\"1", besediloMesh, 1, false)))
    val org = zapisMesh.replace("/", "\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")   // kar naredi org.json na Androidu
    val dejansko = si.safeer.tv.cast.JsonLahki.Zapis().niz("msg", org).toString().toByteArray(Charsets.UTF_8).size.toLong()
    preveriEnako("mesh: ocena je enaka dejanski velikosti ovitega sporocila (razen {\"msg\":\"\"})", true,
        (dejansko - P.meshVelikost(zapisMesh)) in 0L..16L)
    val narekovaji = (0 until P.NAJVEC_ZAPISKOV).map { P.Zapisek("\"".repeat(40) + "%04d".format(it), "x".repeat(52), 1, false) }
    preveriEnako("mesh: ovojnica pokrije 200 dovoljenih id-jev po 64 znakov (v ovitem sporocilu 2 B na znak + narekovaji)", true,
        P.OVOJNICA_B >= 200L * (64 * 2 + 6) + 2_048)
    preveriEnako("mesh: stanje je v mejah bajtov na zici", true, P.zicnaVelikost(P.vJson(narekovaji)) <= P.NAJVEC_SINH_BAJTOV)
    preveriEnako("mesh: a v obliki mesh.route preseze okvir Huba, zato ne sme v shrambo", false, P.smeShraniti(narekovaji))
    preveriEnako("mesh: tipicno polno stanje (id-ji UUID) gre", true,
        P.smeShraniti((0 until P.NAJVEC_ZAPISKOV).map { P.Zapisek("%08x-1234-5678-9abc-def012345678".format(it), "x".repeat(52), 1, false) }))

    // Revizija za branje po straneh: stalna, neodvisna od vrstnega reda, spremeni se ob vsaki spremembi stanja.
    val r1 = P.revizija(listOf(P.Zapisek("a", "x", 1, false), P.Zapisek("b", "y", 2, false)))
    preveriEnako("revizija: neodvisna od vrstnega reda", r1, P.revizija(listOf(P.Zapisek("b", "y", 2, false), P.Zapisek("a", "x", 1, false))))
    preveriEnako("revizija: izbris zapiska jo spremeni", false, r1 == P.revizija(listOf(P.Zapisek("a", "x", 1, false))))
    preveriEnako("revizija: sprememba besedila jo spremeni", false, r1 == P.revizija(listOf(P.Zapisek("a", "x", 1, false), P.Zapisek("b", "z", 2, false))))
    preveriEnako("revizija: 16 sestnajstiskih znakov", true, Regex("^[0-9a-f]{16}$").matches(r1))

    // Najdaljse besedilo, ki ga shramba se sprejme (262144 novih vrstic = 524288 znakov JSON), ne gre cez Binder: izpusti se in javi.
    val vrstice = P.Zapisek("n", "\n".repeat(P.NAJVEC_ZNAKOV), 1, false)
    preveriEnako("binder: zapisek z 262144 novimi vrsticami je izpuscen", 1, P.stran(listOf(vrstice), 0).izpuscenih)

    // Zapisek, ki ne gre v en Binder kos (CodeRabbit st5): branje in pisanje po kosih; sestavljen zapisek je enak izvirniku.
    fun prenesi(z: P.Zapisek, ura: () -> Long = { 0L }): Pair<List<P.SestavljalecDelov.Izid>, P.Zapisek?> {
        val sest = P.SestavljalecDelov(ura)
        val izidi = ArrayList<P.SestavljalecDelov.Izid>()
        var od = 0
        var koncan: P.Zapisek? = null
        var rev: String? = null
        while (od >= 0) {
            val d = P.delZapiska(listOf(z), z.id, od, rev) ?: error("del ne obstaja")
            if (d.spremenjeno) error("revizija se je spremenila")
            rev = d.revizija
            val i = sest.dodaj(z.id, d.cas, d.izbrisan, od, d.skupaj, d.besedilo)
            izidi.add(i)
            if (i is P.SestavljalecDelov.Izid.Koncano) koncan = i.zapisek
            od = d.naslednji
        }
        return izidi to koncan
    }
    val velikASCII = P.Zapisek("velik", "q".repeat(150_000), 7, false)
    val (izidiA, sestavljenA) = prenesi(velikASCII)
    preveriEnako("deli: 150000 znakov gre v 3 kosih in se sestavi nazaj enako", Pair(3, velikASCII), Pair(izidiA.size, sestavljenA))
    val velikEmoji = P.Zapisek("emo", "😀č".repeat(131_072), 9, true)   // 262144 kodnih tock, mejo kosa prekriva nadomestni par
    preveriEnako("deli: emojiji se ne prelomijo sredi para in se sestavijo nazaj enako", velikEmoji, prenesi(velikEmoji).second)
    preveriEnako("deli: stran navede id izpuscenega zapiska", listOf("velik"), P.stran(listOf(P.Zapisek("a", "x", 1, false), velikASCII), 0).izpusceniId)
    preveriEnako("deli: neznan zapisek", null, P.delZapiska(listOf(velikASCII), "ni", 0))
    preveriEnako("deli: od zunaj besedila (z ujemajoco revizijo)", null, P.delZapiska(listOf(velikASCII), "velik", 150_001, P.delZapiska(listOf(velikASCII), "velik", 0)!!.revizija))
    // Revizija zapiska med kosi (CodeRabbit st6): sprememba med branji, enak id/cas/dolzina, se mora zaznati.
    val staro = P.Zapisek("rev", "a".repeat(150_000), 7, false)
    val novo = P.Zapisek("rev", "z".repeat(150_000), 7, false)
    val prvi = P.delZapiska(listOf(staro), "rev", 0)!!
    preveriEnako("revizija kosa: prvi kos nosi neprazno revizijo", true, prvi.revizija.isNotEmpty() && !prvi.spremenjeno)
    preveriEnako("revizija kosa: nespremenjen zapisek nadaljuje normalno", Pair(false, P.DEL_KODNIH_TOCK),
        P.delZapiska(listOf(staro), "rev", P.DEL_KODNIH_TOCK, prvi.revizija)!!.let { Pair(it.spremenjeno, it.besedilo.length) })
    preveriEnako("revizija kosa: zamenjano besedilo (isti id, cas, dolzina) javi spremenjeno", true,
        P.delZapiska(listOf(novo), "rev", P.DEL_KODNIH_TOCK, prvi.revizija)!!.spremenjeno)
    preveriEnako("revizija kosa: nadaljevanje brez revizije javi spremenjeno", true,
        P.delZapiska(listOf(staro), "rev", P.DEL_KODNIH_TOCK, null)!!.spremenjeno)
    val skrcen = P.Zapisek("rev", "a".repeat(10_000), 7, false)
    preveriEnako("revizija kosa: zapisek se je skrcil pod odmik nadaljevanja, javi spremenjeno (ne napake)", true,
        P.delZapiska(listOf(skrcen), "rev", P.DEL_KODNIH_TOCK, prvi.revizija)?.spremenjeno)
    preveriEnako("revizija kosa: odmik zunaj besedila pri ujemajoci reviziji je se napaka", null,
        P.delZapiska(listOf(staro), "rev", 150_001, P.delZapiska(listOf(staro), "rev", 0)!!.revizija))
    preveriEnako("revizija kosa: spremenjeno ne vrne besedila", "", P.delZapiska(listOf(novo), "rev", P.DEL_KODNIH_TOCK, prvi.revizija)!!.besedilo)
    // Cas nad mejo izJson (9,0e15) ne sme v shrambo prek kosov (CodeRabbit st6): izJson bi ga zavrnil in stanje postalo prazno.
    preveriEnako("cas kosa: Long.MAX_VALUE je napaka", P.SestavljalecDelov.Izid.Napaka, P.SestavljalecDelov { 0L }.dodaj("c", Long.MAX_VALUE, false, 0, 10, "a".repeat(10)))
    preveriEnako("cas kosa: NAJVEC_CAS + 1 je napaka", P.SestavljalecDelov.Izid.Napaka, P.SestavljalecDelov { 0L }.dodaj("c", P.NAJVEC_CAS + 1, false, 0, 10, "a".repeat(10)))
    val naMeji = P.SestavljalecDelov { 0L }.dodaj("c", P.NAJVEC_CAS, false, 0, 10, "a".repeat(10))
    preveriEnako("cas kosa: NAJVEC_CAS se sestavi in ga izJson prebere nazaj", 1,
        (naMeji as? P.SestavljalecDelov.Izid.Koncano)?.let { P.izJson(P.vJson(listOf(it.zapisek)))?.size })
    val sNap = P.SestavljalecDelov { 0L }
    sNap.dodaj("x", 1, false, 0, 100, "a".repeat(40))
    preveriEnako("deli: kos mimo vrstnega reda je napaka", P.SestavljalecDelov.Izid.Napaka, sNap.dodaj("x", 1, false, 50, 100, "a".repeat(10)))
    preveriEnako("deli: po napaki sestava znova zacne (kos z od>0 je napaka)", P.SestavljalecDelov.Izid.Napaka, sNap.dodaj("x", 1, false, 40, 100, "a".repeat(10)))
    val sSp = P.SestavljalecDelov { 0L }
    sSp.dodaj("x", 1, false, 0, 100, "a".repeat(40))
    preveriEnako("deli: sprememba skupne dolzine med kosi je napaka", P.SestavljalecDelov.Izid.Napaka, sSp.dodaj("x", 1, false, 40, 101, "a".repeat(10)))
    preveriEnako("deli: skupno vec kot meja zapiska je napaka", P.SestavljalecDelov.Izid.Napaka, P.SestavljalecDelov { 0L }.dodaj("x", 1, false, 0, P.NAJVEC_BESEDILO + 1, "a"))
    preveriEnako("deli: kos nad mejo kosa je napaka", P.SestavljalecDelov.Izid.Napaka,
        P.SestavljalecDelov { 0L }.dodaj("x", 1, false, 0, 100_000, "a".repeat(P.DEL_KODNIH_TOCK + 1)))
    var cas = 0L
    val sRok = P.SestavljalecDelov { cas }
    sRok.dodaj("x", 1, false, 0, 100, "a".repeat(40))
    cas = P.ROK_SESTAVE_MS + 1
    preveriEnako("deli: po roku se sestava ponastavi", P.SestavljalecDelov.Izid.Napaka, sRok.dodaj("x", 1, false, 40, 100, "a".repeat(10)))
    val zdr = P.SestavljalecDelov { 0L }
    zdr.dodaj("a", 1, false, 0, 100, "a".repeat(50))
    preveriEnako("deli: nova sestava z od=0 zamenja staro", P.SestavljalecDelov.Izid.Nadaljuj(50), zdr.dodaj("b", 2, false, 0, 100, "b".repeat(50)))

    // Meja besedila na zapisek v KODNIH TOCKAH (CodeRabbit st3): 131073 emojijev ima dolzino 262146 enot UTF-16, a le 131073 kodnih tock
    val emojiZapisek = P.Zapisek("e", "\uD83D\uDE00".repeat(131_073), 1, false)
    preveriEnako("izJson: 131073 emojijev (262146 enot UTF-16) je veljavno", 1, P.izJson(P.vJson(listOf(emojiZapisek)))?.size)
    preveriEnako("izJson: natanko 262144 kodnih tock je veljavno", 1, P.izJson(P.vJson(listOf(P.Zapisek("e", "x".repeat(P.NAJVEC_ZNAKOV), 1, false))))?.size)
    preveriEnako("izJson: 262145 kodnih tock je zavrnjeno", null, P.izJson(P.vJson(listOf(P.Zapisek("e", "x".repeat(P.NAJVEC_ZNAKOV + 1), 1, false)))))

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
    class Naprava(zacetno: List<P.Zapisek>, val urnik: Long = 1000L) {
        var vrednost: String? = P.vJson(zacetno)
        val hramba = P.TrajniZapiski({ vrednost }, { vrednost = it; true }, { urnik })
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

    // Razlika ur (CodeRabbit st3): A je pred B, nagrobnik pri A ze potece, pri B se ne. Izmenjava se mora ustaviti, ne vrteti.
    val tNagrobnik = P.Zapisek("t", "", 5, true)
    val nB = Naprava(listOf(tNagrobnik), urnik = 5 + P.ROK_NAGROBNIKA_MS - 1000)   // 1 s pred potekom
    val nA = Naprava(listOf(tNagrobnik), urnik = 5 + P.ROK_NAGROBNIKA_MS + 1000)   // 1 s po poteku
    var cak2: Pair<Naprava, List<P.Zapisek>>? = nA to nB.hramba.zapiski()   // B objavi A
    var spor2 = 0
    while (cak2 != null && spor2 < 20) {
        val (komu, kaj) = cak2
        spor2++
        val nazaj = komu.sprejmi(kaj)
        cak2 = if (nazaj == null) null else (if (komu === nA) nB else nA) to nazaj
    }
    preveriEnako("ure: izmenjava se ustavi (najvec 2 sporocili)", true, spor2 <= 2)
    preveriEnako("ure: A je nagrobnik odstranil", emptyList<P.Zapisek>(), nA.hramba.zapiski())
    preveriEnako("ure: B ga obdrzi do svojega poteka", listOf(tNagrobnik), nB.hramba.zapiski())
    preveriEnako("objava: manjse zdruzeno stanje ne sprozi objave", false, P.potrebnaObjava(listOf(tNagrobnik), emptyList()))
    preveriEnako("objava: zdruzeno z vec zapiski sprozi objavo", true, P.potrebnaObjava(emptyList(), listOf(tNagrobnik)))
    preveriEnako("objava: enako stanje ne sprozi objave", false, P.potrebnaObjava(listOf(tNagrobnik), listOf(tNagrobnik)))
    preveriEnako("objava: novejsi zapis sprozi objavo", true, P.potrebnaObjava(listOf(P.Zapisek("a", "x", 1, false)), listOf(P.Zapisek("a", "y", 2, false))))


    if (napak == 0) println("LinaVmesnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
