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
private const val ODTIS_VEKTORJEV = "01885cf95a16f67e08d0d0d3c385e53805d1dc1f89e2bcb875c355a6cdda091b"

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
    preveriEnako("prevec zapiskov zavrnjeno", null, z("{\"verzija\":1,\"zapiski\":$vecZapiskov}"))

    // Meje shrambe (en Binder kos): prevec zapiskov ali prevelika celotna shramba se ne shrani.
    preveriEnako("shramba: zmerna velikost sme", true, P.smeShraniti(listOf(P.Zapisek("a", "x", 1, false))))
    preveriEnako("shramba: prevec zapiskov ne", false, P.smeShraniti((0..P.NAJVEC_ZAPISKOV).map { P.Zapisek("i$it", "", 1, false) }))
    preveriEnako("shramba: prevelika vsebina ne", false, P.smeShraniti((0 until 20).map { P.Zapisek("i$it", "x".repeat(P.NAJVEC_BESEDILO), 1, false) }))
    preveriEnako("shramba: komaj znotraj meje sme", true, P.smeShraniti((0 until 12).map { P.Zapisek("i$it", "x".repeat(P.NAJVEC_BESEDILO), 1, false) }))

    if (napak == 0) println("LinaVmesnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
