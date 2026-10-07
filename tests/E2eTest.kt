package si.safeer.tv.cast

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Zascita ukazov od naprave do naprave (cast/E2e.kt): dogovor, sifriranje, ponarejanje in ponovitve.
 * Vrednosti v »vektorjih« so enake kot v tests/test_link_e2e.py na racunalniku: izvedbi se morata ujemati v bajt.
 * »Sredisce« v preizkusih vpise posiljatelja in sporocilo dostavi - in ga tudi bere, spreminja in ponavlja.
 */
private var napak = 0

private fun preveri(kaj: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $kaj") else { println("FAIL   $kaj"); napak++ }
}

private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
private fun izHex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

private fun idIzKljuca(kljucB64: String): String = "n-" + sha256Hex(Base64.getDecoder().decode(kljucB64)).take(16)

private fun preveriPodpis(kljucB64: String, podatki: ByteArray, podpisB64: String): Boolean = try {
    val javni = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(kljucB64)))
    Signature.getInstance("SHA256withECDSA").run { initVerify(javni); update(podatki); verify(Base64.getDecoder().decode(podpisB64)) }
} catch (_: Throwable) { false }

private class Polja(private val p: JsonLahki.Pogled) : E2e.Polja {
    override fun niz(ime: String): String? = if (p.vrsta(ime) == JsonLahki.Vrsta.NIZ) p.niz(ime) else null
    override fun celo(ime: String): Long? =
        if (p.vrsta(ime) == JsonLahki.Vrsta.STEVILO) p.stevilo(ime)?.takeIf { it == Math.floor(it) }?.toLong() else null
}

private class Sporocilo(val tip: String, val od: String, val cilj: String, val oznaka: String, val tovor: String)

/** Pot prek sredisca: vpise posiljatelja, sporocilo zapise (»sredisce vidi vse«) in ga dostavi takoj ali na roko. */
private class Omrezje(var takoj: Boolean = true) {
    val naprave = HashMap<String, Naprava>()
    val videno = ArrayList<Sporocilo>()
    val cakajo = ArrayList<Sporocilo>()
    var spremeni: ((Sporocilo) -> Sporocilo?)? = null
    /** Posiljanje ne uspe (povezave s srediscem ni vec): funkcija vrne true za sporocilo, ki ne gre. */
    var neGre: ((Sporocilo) -> Boolean)? = null

    fun poslji(od: String, tip: String, cilj: String, oznaka: String, tovor: String): Boolean {
        var s: Sporocilo? = Sporocilo(tip, od, cilj, oznaka, tovor)
        if (neGre?.invoke(s!!) == true) return false
        videno.add(s!!)
        spremeni?.let { s = it(s!!) }
        val koncno = s ?: return true
        if (takoj) dostavi(koncno) else cakajo.add(koncno)
        return true
    }

    fun dostavi(s: Sporocilo) {
        val cilj = naprave[s.cilj] ?: return
        cilj.e2e.prejmi(s.tip, s.od, Polja(JsonLahki.objekt(s.tovor)!!))
    }

    fun dostaviVse() { while (cakajo.isNotEmpty()) dostavi(cakajo.removeAt(0)) }

    fun vse(): String = videno.joinToString("\n") { it.tip + " " + it.tovor }
}

private class Naprava(val omrezje: Omrezje, pripona: String = "", meje: E2e.Meje = E2e.Meje(), parNaprave: java.security.KeyPair? = null) {
    /** [parNaprave]: kljuc druge naprave v preizkusu - tako nastane drug PROGRAM iste naprave (isti kljuc, druga pripona). */
    val par: java.security.KeyPair = parNaprave ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val kljuc: String = Base64.getEncoder().encodeToString(par.public.encoded)
    val jedro: String = idIzKljuca(kljuc)
    var id: String = jedro + pripona
    val krog = HashMap<String, String>()
    val prejeta = ArrayList<Triple<String, String, String>>()       // (notranje sporocilo, id posiljatelja, jedro iz kljuca)
    val seje = ArrayList<String>()
    val zavrnjena = ArrayList<Pair<String, String>>()
    val sprejeta = ArrayList<String>()
    var zdaj = 1_000_000L
    /** Kolikokrat je naprava kaj podpisala s kljucem naprave (dogovor ne sme podpisati nicesar za neveljavno ponudbo). */
    var podpisov = 0
    fun podpisi(b: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(par.private); update(b); sign() })
    /** Obdelava prejetega sporocila vrze izjemo (napaka v programu, ki sporocilo obdela). */
    var pade = false
    val e2e = E2e(
        mojId = { id },
        poslji = { tip, cilj, oznaka, tovor -> omrezje.poslji(id, tip, cilj, oznaka, tovor) },
        podpisi = { b -> podpisov++; podpisi(b) },
        kljucZa = { krog[it] },
        preveri = ::preveriPodpis,
        idIzKljuca = ::idIzKljuca,
        obSporocilu = { s, od, j -> if (pade) throw IllegalStateException("napaka v obdelavi"); prejeta.add(Triple(s, od, j)) },
        obSeji = { seje.add(it) },
        obZavrnitvi = { s, _, koda -> zavrnjena.add(s to koda) },
        obSprejemu = { sprejeta.add(it) },
        ura = { zdaj },
        meje = meje,
    )

    init { omrezje.naprave[id] = this }

    fun pozna(vararg druge: Naprava): Naprava { for (d in druge) krog[d.id] = d.kljuc; return this }

    /** Veljavno podpisana ponudba te naprave z izbrano oznako seje (to lahko naredi vsaka naprava v krogu). */
    fun ponudba(za: Naprava, sessionId: String): String {
        val epk = E2e.novPar().second
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16) { 'n'.code.toByte() })
        val sig = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(par.private); update(E2e.podatkiPonudbe(sessionId, id, za.id, nonce, epk)); sign() })
        return """{"session_id":${E2e.niz(sessionId)},"purpose":"link","v":2,"from":"$id","to":"${za.id}","nonce":"$nonce","epk":"$epk","sig":"$sig"}"""
    }
}

private const val UKAZ = """{"id":"u1","type":"control.command","payload":{"action":"files.list","args":{"path":"share:0:"}}}"""

private fun zasebniIzSkalarja(hexSkalar: String): PrivateKey {
    val parametri = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(hexSkalar, 16), parametri))
}

private fun par(o: Omrezje = Omrezje()): Triple<Omrezje, Naprava, Naprava> {
    val a = Naprava(o, "-control")
    val b = Naprava(o, "-os")
    a.pozna(b); b.pozna(a)
    return Triple(o, a, b)
}

fun main() {
    println("== zascita od naprave do naprave (E2E v1) ==")

    // ---- vektorji (iste vrednosti kot tests/test_link_e2e.py, razred Vektorji) ----
    val sid = "AAECAwQFBgcICQoLDA0ODw"
    val na = "AAECAwQFBgcICQoLDA0ODw=="
    val nb = "EBESExQVFhcYGRobHB0eHw=="
    val idA = "n-00112233445566aa-control"
    val idB = "n-8899aabbccddeeff-os"
    val epkA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhfmF/C2RDkoJ4+WmZ5pojpPLBUr321s32bluAKC1O0ZSn3ry5dxLS3aPKhaqHZaVvRfx1hZllLyiXxlMG5XlA=="
    val epkB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1lqTl3yqPRsIGFL/V6eeRl8WYFdzBLrq1QXdOkhYnPNQGF6JU3LfYiHqOhN1V+Rz/dtnVfBb1QfDxTP86ckShQ=="
    val z = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6"
    preveri("HKDF-SHA256 po RFC 5869 (primer 1)",
        hex(E2e.hkdf(izHex("0b".repeat(22)), izHex("000102030405060708090a0b0c"), izHex("f0f1f2f3f4f5f6f7f8f9"), 42)) ==
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    val zasA = zasebniIzSkalarja("11".repeat(32))
    val zasB = zasebniIzSkalarja("22".repeat(32))
    preveri("ECDH s stalnima kljucema da isto skrivnost kot na racunalniku",
        hex(E2e.ecdh(zasA, epkB)) == z && hex(E2e.ecdh(zasB, epkA)) == z)
    val k = E2e.izpelji(izHex(z), sid, na, nb, idA, idB)
    preveri("gradivo seje (kljuca in predponi obeh smeri)",
        hex(k.kAb) == "0fcd326060beda1c556be1cae5472d82539f08edf6da6cdee256556a2757d52c" && hex(k.pAb) == "404c35a1" &&
            hex(k.kBa) == "8c001903c4e631f20705439f9644be041298ddb04aaf1b2265316ab8330c19b6" && hex(k.pBa) == "ecd51692")
    preveri("podpisani bajti ponudbe in odgovora",
        sha256Hex(E2e.podatkiPonudbe(sid, idA, idB, na, epkA)) == "cec043e390432a21fc765a77d328204e622a108de1f5f33281e08558df3d1b61" &&
            sha256Hex(E2e.podatkiOdgovora(sid, idB, idA, na, nb, epkA, epkB)) == "d66390d0afdbb058047c562a4a07c28501d32a846b9669346a3c076d694186bb")
    val dodatno = E2e.aad(sid, "ab", 7, 3, 0, 1)
    val cistopis = """{"type":"control.command","payload":{"action":"files.list"}}""".toByteArray(Charsets.UTF_8)
    val sifropis = E2e.sifriraj(k.kAb, k.pAb, 7, cistopis, dodatno)
    preveri("dodatni podatki sporocila", String(dodatno, Charsets.UTF_8) == "safeer-link-e2e-msg-v1\nAAECAwQFBgcICQoLDA0ODw\nab\n7\n3\n0\n1")
    preveri("sifropis je enak kot na racunalniku",
        hex(sifropis) == "49885b611b9612dcb3db420031fa11c1fbb39d066a57a69f1d33d8f1b9b8de1d0f52b92f3a91fe0e0a3762020eb87d53" +
            "71d86c460291ff5304d400e68a4595f748e67b9db5f94af2c8d666db")
    preveri("desifriranje vrne cistopis", E2e.desifriraj(k.kAb, k.pAb, 7, sifropis, dodatno).contentEquals(cistopis))
    preveri("kljuc druge smeri ali drug stevec ne desifrira",
        runCatching { E2e.desifriraj(k.kBa, k.pBa, 7, sifropis, dodatno) }.exceptionOrNull() is E2e.Napaka &&
            runCatching { E2e.desifriraj(k.kAb, k.pAb, 8, sifropis, dodatno) }.exceptionOrNull() is E2e.Napaka)
    preveri("niz v JSON ubezi narekovaje, posevnice in nadzorne znake",
        E2e.niz("a\"b\\c\nd\u0001") == "\"a\\\"b\\\\c\\nd\\u0001\"")

    // ---- seja ----
    run {
        val (o, a, b) = par()
        preveri("ukaz gre zasciteno", a.e2e.poslji(b.id, UKAZ))
        preveri("ukaz pride z jedrom iz kljuca", b.prejeta == listOf(Triple(UKAZ, a.id, a.jedro)))
        val odgovor = """{"id":"r1","type":"control.result","ref_id":"u1","payload":{"ok":true,"data":{"url":"https://10.0.0.5:8791/d/x?k=SKRIVNOST"}}}"""
        preveri("odgovor gre po isti seji nazaj", b.e2e.poslji(a.id, odgovor) && a.prejeta == listOf(Triple(odgovor, b.id, b.jedro)))
        preveri("en dogovor za obe smeri; obe strani vesta, da druga zascito zna",
            o.videno.count { it.tip == "data.offer" } == 1 && a.seje == listOf(b.jedro) && b.seje == listOf(a.jedro) &&
                a.e2e.imaSejo(b.id) && b.e2e.imaSejo(a.id) && a.e2e.jedroSeje(b.id) == b.jedro)
        preveri("sredisce ne vidi vsebine",
            listOf("files.list", "share:0:", "control.command", "control.result", "SKRIVNOST", "10.0.0.5").none { it in o.vse() } &&
                o.videno.map { it.tip }.toSet() == setOf("data.offer", "data.answer", "data.chunk"))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        for (i in 0 until 3) a.e2e.poslji(b.id, """{"type":"control.command","payload":{"n":$i}}""")
        val samoPonudba = o.cakajo.size == 1
        o.dostaviVse()
        preveri("sporocila pred odgovorom pocakajo in pridejo po vrsti",
            samoPonudba && b.prejeta.map { it.first } == (0 until 3).map { """{"type":"control.command","payload":{"n":$it}}""" })
    }
    run {
        val (o, a, b) = par()
        val dolgo = """{"type":"control.result","payload":{"data":"""" + "č".repeat(150_000) + """"}}"""
        a.e2e.poslji(b.id, UKAZ)
        preveri("dolgo sporocilo gre v vec delih in se sestavi",
            b.e2e.poslji(a.id, dolgo) && a.prejeta.last().first == dolgo &&
                o.videno.count { it.tip == "data.chunk" && it.od == b.id } > 2 && o.videno.all { it.tovor.length < 200_000 })
        preveri("prevec dolgo sporocilo ne gre", !a.e2e.poslji(b.id, "x".repeat(E2e.DOLZINA_DELA * E2e.NAJVEC_DELOV + 10)))
    }
    run {
        val (o, a, b) = par()
        val tuja = Naprava(o, "-os")
        preveri("naprave brez kljuca v krogu ne naslovimo", !a.e2e.poslji(tuja.id, UKAZ) && o.videno.isEmpty())
        tuja.pozna(b)
        tuja.e2e.poslji(b.id, UKAZ)
        preveri("ponudbe neznane naprave ne sprejmemo", b.prejeta.isEmpty() && o.videno.none { it.tip == "data.answer" })
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, """{"type":"control.command","payload":{"od":"a"}}""")
        b.e2e.poslji(a.id, """{"type":"control.command","payload":{"od":"b"}}""")
        o.dostaviVse()
        a.e2e.poslji(b.id, """{"type":"control.result","payload":{"od":"a2"}}""")
        b.e2e.poslji(a.id, """{"type":"control.result","payload":{"od":"b2"}}""")
        o.dostaviVse()
        preveri("hkratna dogovora: obe smeri delujeta",
            b.prejeta.map { it.first } == listOf("""{"type":"control.command","payload":{"od":"a"}}""", """{"type":"control.result","payload":{"od":"a2"}}""") &&
                a.prejeta.map { it.first } == listOf("""{"type":"control.command","payload":{"od":"b"}}""", """{"type":"control.result","payload":{"od":"b2"}}"""))
    }
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        b.e2e.pozabiVse()                     // b se je znova zagnal: sej ne pozna vec
        b.prejeta.clear()
        val drugi = """{"id":"u2","type":"control.command","payload":{"action":"apps.list"}}"""
        val poslano = a.e2e.poslji(b.id, drugi)             // gre po seji, ki je b ne pozna vec ...
        preveri("po ponovnem zagonu prejemnika se ukaz NE poslje se enkrat; klicatelj izve takoj",
            poslano && b.prejeta.isEmpty() && a.zavrnjena == listOf(drugi to "ni_seje") && !a.e2e.imaSejo(b.id))
        val tretji = """{"id":"u3","type":"control.command","payload":{"action":"apps.list"}}"""
        preveri("naslednji ukaz se dogovori znova in pride",
            a.e2e.poslji(b.id, tretji) && b.prejeta.map { it.first } == listOf(tretji) &&
                o.videno.count { it.tip == "data.offer" } == 2 && o.videno.count { it.tip == "data.error" } == 1)
    }
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        b.e2e.pozabiVse()
        b.prejeta.clear()
        o.takoj = false
        a.e2e.poslji(b.id, """{"id":"u2","type":"control.command","payload":{}}""")
        a.zdaj += E2e.NEPOTRJENA_VELJAJO_MS + 1000
        o.dostaviVse()
        preveri("po izgubljeni seji se zavrnitev javi samo za sveza sporocila",
            b.prejeta.isEmpty() && a.zavrnjena.isEmpty() && !a.e2e.imaSejo(b.id))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, """{"type":"control.command","payload":{"n":1}}""")
        o.cakajo.clear()                      // ponudba se je izgubila
        a.zdaj += E2e.DOGOVOR_CAKA_MS + 1000
        a.e2e.poslji(b.id, """{"type":"control.command","payload":{"n":2}}""")
        o.dostaviVse()
        preveri("dogovor brez odgovora se zacne znova, stari ukazi odpadejo",
            b.prejeta.map { it.first } == listOf("""{"type":"control.command","payload":{"n":2}}"""))
    }
    run {
        val (_, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        a.e2e.pozabi(b.id)
        val pozabljena = !a.e2e.imaSejo(b.id)
        a.e2e.poslji(b.id, UKAZ)
        a.zdaj += E2e.SEJA_VELJA_MS + 1000
        preveri("pozabljena in potekla seja ne veljata", pozabljena && !a.e2e.imaSejo(b.id))
    }
    run {
        val (_, a, b) = par()
        fun p(json: String) = Polja(JsonLahki.objekt(json)!!)
        preveri("tuj prenos (datoteka) ni nas",
            !a.e2e.prejmi("data.offer", b.id, p("""{"session_id":"x","purpose":"file"}""")) &&
                !a.e2e.prejmi("data.chunk", b.id, p("""{"session_id":"x","index":0,"data":"AA=="}""")) &&
                !a.e2e.prejmi("control.command", b.id, p("{}")) &&
                !a.e2e.prejmi("data.error", b.id, p("""{"session_id":"x"}""")))
    }

    // ---- zlonamerno sredisce ----
    run {
        val (o, a, b) = par()
        val tujEpk = E2e.novPar().second
        o.spremeni = { s -> if (s.tip == "data.offer") Sporocilo(s.tip, s.od, s.cilj, s.oznaka, s.tovor.replace(Regex("\"epk\":\"[^\"]*\""), "\"epk\":\"$tujEpk\"")) else s }
        a.e2e.poslji(b.id, UKAZ)
        preveri("zamenjan enkratni kljuc v ponudbi: podpis pade", b.prejeta.isEmpty() && !b.e2e.imaSejo(a.id))
    }
    run {
        val (o, a, b) = par()
        val tujEpk = E2e.novPar().second
        o.spremeni = { s -> if (s.tip == "data.answer") Sporocilo(s.tip, s.od, s.cilj, s.oznaka, s.tovor.replace(Regex("\"epk\":\"[^\"]*\""), "\"epk\":\"$tujEpk\"")) else s }
        a.e2e.poslji(b.id, UKAZ)
        preveri("zamenjan enkratni kljuc v odgovoru: podpis pade", b.prejeta.isEmpty() && !a.e2e.imaSejo(b.id))
    }
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        b.prejeta.clear()
        val kos = o.videno.first { it.tip == "data.chunk" }
        b.e2e.prejmi(kos.tip, kos.od, Polja(JsonLahki.objekt(kos.tovor.replace("\"seq\":0", "\"seq\":5"))!!))      // isti sifropis pod drugim stevcem
        val izmisljen = Base64.getEncoder().encodeToString(ByteArray(80) { 'x'.code.toByte() })
        b.e2e.prejmi(kos.tip, kos.od, Polja(JsonLahki.objekt(kos.tovor.replace("\"seq\":0", "\"seq\":6").replace(Regex("\"data\":\"[^\"]*\""), "\"data\":\"$izmisljen\""))!!))
        preveri("sredisce ne more sestaviti ukaza", b.prejeta.isEmpty())
        o.dostavi(kos)
        preveri("ponovljen kos se ne izvede se enkrat", b.prejeta.isEmpty())
    }
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        val ponudba = o.videno.first { it.tip == "data.offer" }
        val kos = o.videno.first { it.tip == "data.chunk" }
        b.e2e.pozabiVse()
        b.prejeta.clear()
        o.dostavi(ponudba)        // sredisce znova poda staro ponudbo: b odgovori z NOVIM kljucem ...
        o.dostavi(kos)            // ... zato stari kos pod novo sejo ne pomeni nicesar
        preveri("ponovljena stara ponudba ne da uporabne seje", b.prejeta.isEmpty())
    }
    run {
        val (o, a, b) = par()
        val c = Naprava(o, "-os").pozna(a)
        a.pozna(c)
        a.e2e.poslji(b.id, UKAZ)
        val ponudba = o.videno.first { it.tip == "data.offer" }
        val kos = o.videno.first { it.tip == "data.chunk" }
        b.prejeta.clear()
        b.e2e.prejmi(kos.tip, c.id, Polja(JsonLahki.objekt(kos.tovor.replace("\"seq\":0", "\"seq\":9"))!!))
        c.e2e.prejmi(ponudba.tip, ponudba.od, Polja(JsonLahki.objekt(ponudba.tovor)!!))     // ponudba je podpisana za b, ne za c
        b.e2e.pozabiVse()
        b.e2e.prejmi(ponudba.tip, c.id, Polja(JsonLahki.objekt(ponudba.tovor)!!))           // sredisce trdi, da jo je poslal c
        preveri("kos ali ponudba od druge naprave, kot pise v podpisu, ne velja",
            b.prejeta.isEmpty() && !c.e2e.imaSejo(a.id) && !b.e2e.imaSejo(a.id))
    }

    // ---- podobna oznaka ----
    run {
        val o = Omrezje()
        val moja = Naprava(o, "-control")
        val racunalnik = Naprava(o, "-control")
        val gost = Naprava(o)
        o.naprave.remove(gost.id)
        gost.id = moja.jedro + "-x"          // oznaka je videti kot moja naprava, kljuc je gostov
        o.naprave[gost.id] = gost
        racunalnik.krog[moja.id] = moja.kljuc
        racunalnik.krog[gost.id] = gost.kljuc
        gost.pozna(racunalnik)
        gost.e2e.poslji(racunalnik.id, UKAZ)
        preveri("vnos s podobno oznako in svojim kljucem dobi SVOJE jedro",
            racunalnik.prejeta.size == 1 && racunalnik.prejeta[0].second == gost.id && racunalnik.prejeta[0].third == gost.jedro &&
                gost.jedro != moja.jedro)
    }
    run {
        val o = Omrezje()
        val moja = Naprava(o, "-control")
        val racunalnik = Naprava(o, "-control")
        val gost = Naprava(o)
        o.naprave.remove(gost.id)
        gost.id = moja.jedro + "-x"
        o.naprave[gost.id] = gost
        racunalnik.krog[moja.id] = moja.kljuc
        racunalnik.krog[gost.id] = moja.kljuc          // tako oznako brez svojega vnosa razresi krog (po jedru)
        gost.pozna(racunalnik)
        gost.e2e.poslji(racunalnik.id, UKAZ)
        preveri("podobna oznaka brez svojega vnosa ne more podpisati", racunalnik.prejeta.isEmpty() && racunalnik.seje.isEmpty())
    }

    // ---- zavrnitev sredisca ----
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        fun potrditev(oznaka: String, stanje: String = "rejected", koda: String = "ni_naprave") =
            Polja(JsonLahki.objekt("""{"id":"1","type":"data.ack","ref_id":"$oznaka","status":"$stanje","error":"Naprave ni.","error_code":"$koda"}""")!!)
        a.e2e.poslji(b.id, UKAZ)
        val drugi = """{"id":"u2","type":"control.command","payload":{}}"""
        a.e2e.poslji(b.id, drugi)
        val ponudba = o.cakajo.removeAt(0)
        val nase = a.e2e.prejmi("data.ack", "", potrditev(ponudba.oznaka))
        preveri("zavrnjena ponudba zavrne cakajoce ukaze", nase && a.zavrnjena == listOf(UKAZ to "ni_naprave", drugi to "ni_naprave"))
        // Po zavrnjeni ponudbi nekaj sekund ne podpisemo nove (peti pregled: tipka daljinca ob napravi, ki je ni, je
        // porabila mejo podpisov); naslednji ukaz po premoru zacne nov dogovor.
        val vPremoru = !a.e2e.poslji(b.id, UKAZ) && o.cakajo.isEmpty()
        a.zdaj += E2e.NEUSPEL_DOGOVOR_CAKA_MS + 100
        a.e2e.poslji(b.id, UKAZ)
        preveri("po zavrnjeni ponudbi kratek premor, potem nov dogovor", vPremoru && o.cakajo.map { it.tip } == listOf("data.offer"))
        o.dostaviVse()
        a.zavrnjena.clear()
        a.e2e.poslji(b.id, drugi)
        val kos = o.cakajo.removeAt(0)
        a.e2e.prejmi("data.ack", "", potrditev(kos.oznaka, koda = "naprava_ni_povezana"))
        preveri("zavrnjen kos zavrne svoje sporocilo, seja ostane", a.zavrnjena == listOf(drugi to "naprava_ni_povezana") && a.e2e.imaSejo(b.id))
        a.zavrnjena.clear()
        preveri("sprejeta potrditev in tuje potrditve",
            a.e2e.prejmi("data.ack", "", potrditev(kos.oznaka, stanje = "accepted", koda = "")) && a.zavrnjena.isEmpty() &&
                !a.e2e.prejmi("data.ack", "", potrditev("drugo-1")) && !a.e2e.prejmi("data.ack", b.id, potrditev(kos.oznaka)))

        // ---- dogovor brez sporocila (dokaz kljuca takoj, ko napravo zagledamo) ----
        val (o3, a3, b3) = par()
        preveri("dogovor brez sporocila dokaze kljuc obema",
            a3.e2e.dogovoriSe(b3.id) && a3.seje == listOf(b3.jedro) && b3.seje == listOf(a3.jedro) && b3.prejeta.isEmpty() &&
                o3.videno.map { it.tip } == listOf("data.offer", "data.answer"))
        preveri("ob obstojeci seji novega dogovora ni", a3.e2e.dogovoriSe(b3.id) && o3.videno.size == 2)
        a3.e2e.poslji(b3.id, UKAZ)
        preveri("ukaz gre po ze dogovorjeni seji",
            o3.videno.map { it.tip }.drop(2) == listOf("data.chunk") && b3.prejeta.size == 1 && b3.prejeta[0].first == UKAZ)
        val tujec = Naprava(o3, "-x")
        preveri("dogovora z neznano napravo ali brez oznake ni", !a3.e2e.dogovoriSe(tujec.id) && !a3.e2e.dogovoriSe("") && o3.videno.size == 3)
        val (o4, a4, b4) = par(Omrezje(takoj = false))
        preveri("ukaz med dogovorom brez sporocila pocaka nanj",
            a4.e2e.dogovoriSe(b4.id) && a4.e2e.dogovoriSe(b4.id) && a4.e2e.poslji(b4.id, UKAZ) && o4.cakajo.map { it.tip } == listOf("data.offer"))
        o4.dostaviVse()
        preveri("po dogovoru ukaz pride", b4.prejeta.map { it.first } == listOf(UKAZ))
        preveri("»nadaljuj na napravi« je zasciten tip", "handoff.request" in E2e.ZASCITENI_TIPI)

        // ---- sprejem sredisca in omejen spomin ----
        val (o2, a2, b2) = par(Omrezje(takoj = false))
        a2.e2e.poslji(b2.id, UKAZ)
        val ponudba2 = o2.cakajo[0]
        a2.e2e.prejmi("data.ack", "", potrditev(ponudba2.oznaka, stanje = "accepted", koda = ""))
        o2.dostaviVse()
        preveri("sprejeta ponudba se ni sprejet ukaz (caka na odgovor naprave)", a2.sprejeta.isEmpty() && b2.prejeta.size == 1)
        val stran = """{"id":"u2","type":"cast.url","payload":{"url":"https://primer.si/"}}"""
        a2.e2e.poslji(b2.id, stran)
        val kos2 = o2.cakajo[0]
        a2.e2e.prejmi("data.ack", "", potrditev(kos2.oznaka, stanje = "accepted", koda = ""))
        a2.e2e.prejmi("data.ack", "", potrditev(kos2.oznaka, stanje = "accepted", koda = ""))
        preveri("sprejeto sporocilo javi sprejem enkrat", a2.sprejeta == listOf(stran) && a2.zavrnjena.isEmpty())
        o2.dostaviVse()
        a2.sprejeta.clear()
        val dolgo = """{"id":"u3","type":"control.result","payload":{"data":"${"x".repeat(2 * E2e.DOLZINA_DELA + 10)}"}}"""
        a2.e2e.poslji(b2.id, dolgo, "opis-u3")              // klicatelj dobi nazaj OPIS, ne (dolgega) sporocila
        val kosi = o2.cakajo.toList()
        for (delDolgega in kosi) a2.e2e.prejmi("data.ack", "", potrditev(delDolgega.oznaka, stanje = "accepted", koda = ""))
        preveri("dolgo sporocilo (3 deli) javi sprejem enkrat", kosi.size == 3 && a2.sprejeta == listOf("opis-u3"))
        o2.dostaviVse()
        a2.sprejeta.clear()
        a2.e2e.poslji(b2.id, drugi)
        val stariKos = o2.cakajo.removeAt(0)
        a2.zdaj += E2e.IZHODNA_VELJAJO_MS + 1000
        a2.e2e.poslji(b2.id, UKAZ)
        a2.e2e.prejmi("data.ack", "", potrditev(stariKos.oznaka))
        preveri("pozna zavrnitev starega kosa ne javi nicesar", a2.zavrnjena.isEmpty())
    }

    // ---- neodvisni pregled pred izdajo (7. 10. 2026): ti preizkusi so na prejsnji kodi padli ----
    fun zadnjiKos(o: Omrezje) = o.videno.last { it.tip == "data.chunk" }
    fun sidIz(tovor: String) = Regex("\"session_id\":\"([^\"]*)\"").find(tovor)!!.groupValues[1]
    fun obvestilo(sid: String, koda: String = "ni_seje") = Polja(JsonLahki.objekt("""{"session_id":"$sid","code":"$koda","seq":0}""")!!)
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        repeat(3) { a.e2e.prejmi("data.error", b.id, obvestilo(sidIz(zadnjiKos(o).tovor))) }
        preveri("ponarejeno obvestilo »seje ni« ne ponovi ze izvedenega ukaza", b.prejeta.size == 1)
        preveri("klicatelj izve, seja je zavrzena", a.zavrnjena == listOf(UKAZ to "ni_seje") && !a.e2e.imaSejo(b.id))
        val tretji = """{"id":"u3","type":"control.command","payload":{}}"""
        preveri("naslednji ukaz po obvestilu pride", a.e2e.poslji(b.id, tretji) && b.prejeta.map { it.first } == listOf(UKAZ, tretji))
    }
    run {
        val (o, a, b) = par()
        val c = Naprava(o, "-tv")
        a.e2e.poslji(b.id, UKAZ)
        val sidS = sidIz(zadnjiKos(o).tovor)
        a.e2e.prejmi("data.error", c.id, obvestilo(sidS))
        a.e2e.prejmi("data.error", b.id, obvestilo(sidS, koda = "ni_kljuca"))
        preveri("obvestilo druge naprave ali z drugo kodo seje ne zavrze", a.e2e.imaSejo(b.id))
    }
    run {
        // Trk oznake seje: a caka na odgovor b (oznaka je v ponudbi vidna srediscu); c poslje svojo ponudbo z ISTO oznako.
        val o = Omrezje(takoj = false)
        val a = Naprava(o, "-control")
        val b = Naprava(o, "-os")
        val c = Naprava(o, "-tv")
        a.pozna(b, c); b.pozna(a); c.pozna(a)
        val zaB = """{"type":"control.command","payload":{"za":"b"}}"""
        a.e2e.poslji(b.id, zaB)
        val sidS = sidIz(o.cakajo[0].tovor)
        a.e2e.prejmi("data.offer", c.id, Polja(JsonLahki.objekt(c.ponudba(a, sidS))!!))
        o.dostaviVse()
        a.e2e.poslji(c.id, """{"type":"control.command","payload":{"za":"c"}}""")
        o.dostaviVse()
        preveri("ponudba z oznako nasega cakajocega dogovora ne preusmeri posiljanja", b.prejeta.map { it.first } == listOf(zaB))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ)
        a.zdaj += E2e.V_VRSTI_VELJA_MS + 1000         // sredisce je odgovor zadrzalo
        o.dostaviVse()
        val drugi = """{"id":"u2","type":"control.command","payload":{}}"""
        a.e2e.poslji(b.id, drugi)
        o.dostaviVse()
        preveri("sporocilo, ki je predolgo cakalo na dogovor, ne gre; seja nastane in naslednji ukaz pride",
            a.zavrnjena == listOf(UKAZ to "cas") && b.prejeta.map { it.first } == listOf(drugi))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.dogovoriSe(b.id)
        a.zdaj += E2e.DOGOVOR_VELJA_MS + 1000
        o.dostaviVse()
        preveri("prepozen odgovor ne ustvari seje", !a.e2e.imaSejo(b.id) && a.seje.isEmpty())
    }
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        b.prejeta.clear()
        b.zdaj += E2e.SEJA_VELJA_MS + 1000
        a.e2e.poslji(b.id, """{"id":"u2","type":"control.command","payload":{}}""")
        preveri("seja s poteklim rokom tudi pri prejemu ne velja", b.prejeta.isEmpty() && o.videno.count { it.tip == "data.error" } == 1)
    }
    run {
        val (_, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        b.prejeta.clear()
        b.krog.remove(a.id)                   // uporabnik je napravo a na napravi b odstranil iz Linka
        a.e2e.poslji(b.id, """{"id":"u2","type":"control.command","payload":{}}""")
        preveri("naprava, odstranjena iz kroga, izgubi sejo",
            b.prejeta.isEmpty() && !b.e2e.imaSejo(a.id) && !b.e2e.poslji(a.id, """{"type":"control.result","payload":{}}"""))
    }
    run {
        val (o, a, b) = par()
        val c = Naprava(o, "-tv")
        a.e2e.poslji(b.id, UKAZ)
        b.prejeta.clear()
        b.krog[a.id] = c.kljuc                // pod oznako a je zdaj drug kljuc
        a.e2e.poslji(b.id, """{"id":"u2","type":"control.command","payload":{}}""")
        preveri("seja ne velja, ko je pod oznako v krogu drug kljuc", b.prejeta.isEmpty())
    }
    run {
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        val sidS = sidIz(zadnjiKos(o).tovor)
        fun p(json: String) = Polja(JsonLahki.objekt(json)!!)
        val dolg = Base64.getEncoder().encodeToString(ByteArray(E2e.DOLZINA_DELA + 17))
        val brezIzjeme = runCatching {
            b.e2e.prejmi("data.offer", "", p("""{"purpose":"link","session_id":["x"],"from":{"a":1},"to":5,"nonce":null,"epk":1.5,"sig":[],"v":2}"""))
            b.e2e.prejmi("data.answer", b.id, p("""{"purpose":"link","session_id":{"x":1},"from":[],"to":null}"""))
            b.e2e.prejmi("data.chunk", a.id, p("""{"session_id":"$sidS","seq":"1","m":[],"i":{},"n":null,"data":7}"""))
            b.e2e.prejmi("data.chunk", a.id, p("""{"session_id":"$sidS","seq":5,"m":0,"i":4294967296,"n":4294967297,"data":"AAAA"}"""))
            b.e2e.prejmi("data.chunk", a.id, p("""{"session_id":"$sidS","seq":5,"m":0,"i":0,"n":1,"data":"$dolg"}"""))
            b.e2e.prejmi("data.error", a.id, p("""{"session_id":["$sidS"],"code":["ni_seje"],"seq":"x"}"""))
            b.e2e.prejmi("data.ack", "", p("""{"ref_id":["e2e-x"],"status":{}}"""))
        }.isSuccess
        preveri("nobeno sporocilo ne vrze izjeme in ne pokvari seje", brezIzjeme && b.prejeta.size == 1 && b.e2e.imaSejo(a.id))
        b.prejeta.clear()
        preveri("notranje sporocilo, ki ni objekt JSON, se zavrze", a.e2e.poslji(b.id, "[1,2,3]") && b.prejeta.isEmpty() && b.e2e.imaSejo(a.id))
        b.pade = true
        val poslano = runCatching { a.e2e.poslji(b.id, UKAZ) }.getOrDefault(false)
        preveri("napaka v obdelavi pri prejemniku ne vrze izjeme iz prejema", poslano && b.e2e.imaSejo(a.id))
    }
    run {
        // b se znova zazene, sredisce mu ponovi staro ponudbo naprave a: b ima potem pod ISTO oznako sejo z drugim kljucem.
        val (o, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        val ponudba = o.videno.first { it.tip == "data.offer" }
        b.e2e.pozabiVse()
        b.prejeta.clear()
        o.dostavi(ponudba)
        a.e2e.poslji(b.id, """{"id":"u2","type":"control.command","payload":{}}""")
        val zavrzeni = b.prejeta.isEmpty() && !b.e2e.imaSejo(a.id) && !a.e2e.imaSejo(b.id)
        val tretji = """{"id":"u3","type":"control.command","payload":{}}"""
        a.e2e.poslji(b.id, tretji)
        preveri("ponovljena stara ponudba po ponovnem zagonu se razresi", zavrzeni && b.prejeta.map { it.first } == listOf(tretji))
    }
    run {
        val (o, a, b) = par()
        val c = Naprava(o, "-tv")
        b.pozna(c)
        a.e2e.poslji(b.id, UKAZ)                   // seja a-b; na b jo potrdi prvi kos
        repeat(E2e.NAJVEC_SEJ + 5) { i -> b.e2e.prejmi("data.offer", c.id, Polja(JsonLahki.objekt(c.ponudba(b, "S-$i"))!!)) }
        val drugi = """{"id":"u2","type":"control.command","payload":{}}"""
        a.e2e.poslji(b.id, drugi)
        preveri("ponovljene ponudbe ne izrinejo delujoce seje (nepotrjene izpadejo prve)",
            b.e2e.imaSejo(a.id) && b.prejeta.map { it.first } == listOf(UKAZ, drugi))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ)
        o.dostaviVse()
        val dolgo = """{"type":"control.result","payload":{"data":"${"x".repeat(E2e.DOLZINA_DELA + 10)}"}}"""      // dva dela
        repeat(E2e.NAJVEC_NEDOKONCANIH + 2) { a.e2e.poslji(b.id, dolgo) }
        val prviDeli = o.cakajo.filter { "\"i\":0," in it.tovor }
        o.cakajo.clear()
        for (s in prviDeli) o.dostavi(s)                // drugi deli nikoli ne pridejo
        preveri("nedokoncanih sporocil je omejeno", prviDeli.size == E2e.NAJVEC_NEDOKONCANIH + 2 && b.e2e.nedokoncanih(a.id) == E2e.NAJVEC_NEDOKONCANIH)
    }
    run {
        val (o, _, b) = par()
        val tuja = Naprava(o, "-os").pozna(b)       // tuja pozna b, b tuje ne
        val poslano = tuja.e2e.poslji(b.id, UKAZ)
        preveri("naprava, ki nas nima v krogu, to pove takoj",
            poslano && b.prejeta.isEmpty() && tuja.zavrnjena == listOf(UKAZ to "ni_kljuca") &&
                o.videno.map { it.tip } == listOf("data.offer", "data.error"))
    }

    // ---- drugi neodvisni pregled (7. 10. 2026, po popravkih prvega): isti primeri kot DrugiPregled na racunalniku ----
    fun ukazZ(id: String) = """{"id":"$id","type":"control.command","payload":{}}"""
    fun vrednost(tovor: String, ime: String) = Regex("\"$ime\":\"([^\"]*)\"").find(tovor)!!.groupValues[1]
    fun zamenjano(tovor: String, ime: String, v: String) = tovor.replace(Regex("\"$ime\":\"[^\"]*\"")) { "\"$ime\":${E2e.niz(v)}" }
    preveri("veljavna oznaka naprave: 1-128 vidnih znakov ASCII",
        listOf("n-0123456789abcdef", "n-0123456789abcdef-os", "stara-naprava_1.2:3", "a".repeat(128)).all { E2e.veljavnaOznaka(it) } &&
            listOf("", "a b", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007fb", "a\u0085b", "a b", "č", "\ud800", "a".repeat(129))
                .none { E2e.veljavnaOznaka(it) })
    preveri("base64 strogo: abeceda in polnilo (kot na racunalniku)",
        listOf("AAAA", "AA==", "AAA=").all { E2e.veljavenB64(it, 64) } &&
            listOf("", "AAA", "AA=A", "A===", "====", "AAAA\n", " AAA", "AA-_", "AAAAA").none { E2e.veljavenB64(it, 64) } &&
            !E2e.veljavenB64("AAAAAAAA", 4) && runCatching { E2e.izB64("AAA", "x") }.isFailure && runCatching { E2e.izB64("AAAA", "x") }.isSuccess)
    preveri("oznaka seje: crke, stevke, vezaj in podcrtaj, najvec 64",
        listOf("AAECAwQFBgcICQoLDA0ODw", "S-1", "a_b").all { E2e.veljavnaOznakaSeje(it) } &&
            listOf("", "a b", "a\nb", "a+b", "a/b", "a=", "a".repeat(65)).none { E2e.veljavnaOznakaSeje(it) })
    run {
        // N1: sredisce M v seznam naprav vpise vnos X = »<oznaka M>\n<oznaka B>« (kljuc po jedru: M). Bajti ponudbe, ki bi jih
        // C podpisal za X, so isti kot bajti ponudbe »od <C>\n<M> za B«. Pred popravkom je napad uspel (N1Napad.kt).
        val o = Omrezje(takoj = false)
        val c = Naprava(o, "-control"); val b = Naprava(o, "-os"); val m = Naprava(o, "-tv")
        val x = m.id + "\n" + b.id
        val f = c.id + "\n" + m.id
        c.krog[x] = m.kljuc            // iskanje kljuca po jedru (prvih 18 znakov) je v izdelku dalo isto
        b.krog[f] = c.kljuc
        preveri("oznaka s prelomom vrstice: podpisani bajti bi bili dvoumni",
            E2e.podatkiPonudbe("s", c.id, x, "n", "e").contentEquals(E2e.podatkiPonudbe("s", f, b.id, "n", "e")))
        preveri("oznaka s prelomom vrstice: dogovor se ne zacne in sporocilo ne gre",
            !c.e2e.dogovoriSe(x) && !c.e2e.poslji(x, UKAZ) && o.cakajo.isEmpty() && c.podpisov == 0)
        val epk = E2e.novPar().second
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
        val sidX = "A".repeat(22)
        val ponudba = """{"session_id":"$sidX","purpose":"link","v":2,"from":${E2e.niz(f)},"to":"${b.id}","nonce":"$nonce","epk":"$epk",""" +
            """"sig":"${c.podpisi(E2e.podatkiPonudbe(sidX, c.id, x, nonce, epk))}"}"""
        b.e2e.prejmi("data.offer", f, Polja(JsonLahki.objekt(ponudba)!!))
        preveri("oznaka s prelomom vrstice: tudi podpisane ponudbe naprava ne sprejme",
            b.seje.isEmpty() && o.cakajo.isEmpty() && !b.e2e.imaSejo(f) && b.podpisov == 0)
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        // Veljavno PODPISANA ponudba z oznako seje, ki ne sme v podpisane bajte: zavrnjena, preden ta naprava kaj podpise.
        for (slabSid in listOf("abc\ndef", "abc def", "abc+def", "a".repeat(65)))
            b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, slabSid))!!))
        preveri("podpisana ponudba z oznako seje napacne oblike je zavrnjena pred podpisom",
            b.seje.isEmpty() && o.cakajo.isEmpty() && b.podpisov == 0)
        a.e2e.dogovoriSe(b.id)
        val p = o.cakajo.removeAt(0)
        var cisto = true
        for ((ime, v) in listOf("session_id" to vrednost(p.tovor, "session_id") + "\n", "session_id" to vrednost(p.tovor, "session_id") + "+",
            "nonce" to vrednost(p.tovor, "nonce").dropLast(2) + "\n=", "nonce" to vrednost(p.tovor, "nonce").dropLast(1),
            "epk" to vrednost(p.tovor, "epk") + "\n", "epk" to " " + vrednost(p.tovor, "epk"), "sig" to vrednost(p.tovor, "sig") + "\n")) {
            b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(zamenjano(p.tovor, ime, v))!!))
            if (b.seje.isNotEmpty() || o.cakajo.isNotEmpty()) cisto = false
        }
        for (slabOd in listOf(a.id + "\t", a.id + " ", a.id + "č")) {
            b.krog[slabOd] = a.kljuc      // tudi ce bi krog tako oznako poznal
            b.e2e.prejmi("data.offer", slabOd, Polja(JsonLahki.objekt(zamenjano(p.tovor, "from", slabOd))!!))
            if (b.seje.isNotEmpty() || o.cakajo.isNotEmpty()) cisto = false
        }
        o.dostavi(p)
        preveri("polja ponudbe sprejmejo samo svojo abecedo; nespremenjena ponudba se sprejme", cisto && b.seje == listOf(a.jedro))
        val odg = o.cakajo.removeAt(0)
        var cistoOdg = true
        for ((ime, v) in listOf("nonce" to vrednost(odg.tovor, "nonce") + "\n", "epk" to vrednost(odg.tovor, "epk") + " ", "sig" to "\n" + vrednost(odg.tovor, "sig"))) {
            a.e2e.prejmi("data.answer", b.id, Polja(JsonLahki.objekt(zamenjano(odg.tovor, ime, v))!!))
            if (a.seje.isNotEmpty()) cistoOdg = false
        }
        o.dostavi(odg)
        preveri("polja odgovora sprejmejo samo svojo abecedo; nespremenjen odgovor se sprejme", cistoOdg && a.seje == listOf(b.jedro))
    }
    run {
        // N4: c odpira sejo za sejo z b in vsako potrdi s sporocilom - izriva lahko samo SVOJE seje, ne seje a-b.
        val o = Omrezje()
        val a = Naprava(o, "-control"); val b = Naprava(o, "-os", E2e.Meje(sej = 6, sejNaNapravo = 3)); val c = Naprava(o, "-tv")
        a.pozna(b); b.pozna(a, c); c.pozna(b)
        a.e2e.poslji(b.id, UKAZ)
        repeat(10) { i -> c.e2e.pozabi(b.id); c.e2e.poslji(b.id, ukazZ("c$i")) }
        val poC = b.e2e.imaSejo(a.id) to b.e2e.stSej(c.jedro)
        a.e2e.poslji(b.id, ukazZ("u2"))
        preveri("ena naprava ne izrine sej drugih (meja sej na napravo)",
            poC == (true to 3) && b.prejeta.filter { it.second == a.id }.map { it.first } == listOf(UKAZ, ukazZ("u2")) &&
                o.videno.none { it.tip == "data.error" })
    }
    run {
        val o = Omrezje()
        val a = Naprava(o, "-control"); val b = Naprava(o, "-os", E2e.Meje(sejNaNapravo = 3))
        a.pozna(b); b.pozna(a)
        a.e2e.poslji(b.id, UKAZ)
        val potrjena = a.e2e.oznakaSejeZa(b.id)
        repeat(8) { i -> b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, "ponovljena-$i"))!!)) }
        preveri("ponovljene ponudbe iste naprave ne izrinejo njene potrjene seje",
            potrjena.isNotEmpty() && b.e2e.imaSejoZOznako(potrjena) && b.e2e.stSej() == 3)
    }
    run {
        val o = Omrezje(takoj = false)
        val en = 2L * E2e.DOLZINA_DELA
        val a = Naprava(o, "-control"); val c = Naprava(o, "-tv")
        val b = Naprava(o, "-os", E2e.Meje(nedokoncanih = 16, rezerviranoNaNapravo = 3 * en, rezervirano = 4 * en))
        a.pozna(b); c.pozna(b); b.pozna(a, c)
        val dolgo = """{"type":"control.result","payload":{"data":"${"x".repeat(E2e.DOLZINA_DELA + 10)}"}}"""      // dva dela
        fun prviDeli(od: Naprava, koliko: Int) {
            repeat(koliko) { od.e2e.poslji(b.id, dolgo) }
            while (o.cakajo.isNotEmpty()) {
                val s = o.cakajo.removeAt(0)
                if (!(s.tip == "data.chunk" && "\"n\":2," in s.tovor && "\"i\":1," in s.tovor)) o.dostavi(s)     // drugi deli ne pridejo
            }
        }
        prviDeli(c, 6)                          // c: 6 nedokoncanih, a sme drzati samo 3
        val poC = b.e2e.rezervirano(c.jedro)
        prviDeli(a, 2)                          // a: 2 -> skupaj bi bilo 5, meja je 4: izpade najstarejse (od c)
        preveri("pomnilnik za nedokoncana sporocila je omejen na napravo in skupaj",
            poC == 3 * en && b.e2e.rezervirano(a.jedro) == 2 * en && b.e2e.rezervirano() == 4 * en)
    }
    run {
        // N3: dogovor brez sporocila (dokaz kljuca) ne izrine dogovora, na katerega caka ukaz.
        val o = Omrezje(takoj = false)
        val a = Naprava(o, "-control", E2e.Meje(dogovorov = 2))
        val d = List(6) { Naprava(o, "-n$it") }
        a.pozna(*d.toTypedArray())
        val k1 = a.e2e.poslji(d[0].id, ukazZ("u0")) && a.e2e.dogovoriSe(d[1].id) && a.e2e.dogovoriSe(d[2].id) &&
            a.e2e.cakajociDogovori().toSet() == setOf(d[0].id, d[2].id)
        val k2 = a.e2e.poslji(d[3].id, ukazZ("u3")) && a.e2e.cakajociDogovori().toSet() == setOf(d[0].id, d[3].id)
        val k3 = !a.e2e.dogovoriSe(d[4].id) && a.zavrnjena.isEmpty()
        val k4 = a.e2e.poslji(d[5].id, ukazZ("u5")) && a.zavrnjena == listOf(ukazZ("u0") to "cas") &&
            a.e2e.cakajociDogovori().toSet() == setOf(d[3].id, d[5].id)
        preveri("dokaz kljuca ne izrine dogovora, na katerega caka ukaz (1: izrine dokaz kljuca)", k1)
        preveri("dokaz kljuca ne izrine dogovora, na katerega caka ukaz (2: ukaz izrine dokaz kljuca)", k2)
        preveri("dokaz kljuca ne izrine dogovora, na katerega caka ukaz (3: dokaz kljuca ne izrine ukaza)", k3)
        preveri("ukaz izrine najstarejsi dogovor in klicatelj izrinjenega ukaza to izve", k4)
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, ukazZ("star"))
        a.zdaj += E2e.DOGOVOR_CAKA_MS - 500
        a.e2e.poslji(b.id, ukazZ("svez"))
        o.cakajo.clear()                        // sredisce prve ponudbe ni dostavilo
        a.zdaj += 1000
        val znova = a.e2e.dogovoriSe(b.id)      // (seznam naprav) dogovor se zacne znova
        val zavrnjenaPrej = a.zavrnjena.toList()
        o.dostaviVse()
        preveri("svez ukaz prezivi nov dogovor z isto napravo, star se zavrne",
            znova && zavrnjenaPrej == listOf(ukazZ("star") to "cas") && b.prejeta.map { it.first } == listOf(ukazZ("svez")))
    }
    run {
        // Na dogovor brez odgovora caka polna vrsta SVEZIH sporocil; nov dogovor z novim sporocilom jih prevzame. Kar ne
        // gre vec v vrsto (najstarejse), klicatelj izve - ne izgine tiho.
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.dogovoriSe(b.id)
        o.cakajo.clear()
        a.zdaj += E2e.DOGOVOR_CAKA_MS - 200
        val vsa = (0 until E2e.NAJVEC_V_VRSTI).map { ukazZ("s$it") }
        val sprejeta = vsa.all { a.e2e.poslji(b.id, it) }
        val prevec = a.e2e.poslji(b.id, ukazZ("prevec"))
        a.zdaj += 400
        val novo = a.e2e.poslji(b.id, ukazZ("novo"))
        val zavrnjenaPrej = a.zavrnjena.toList()
        o.dostaviVse()
        preveri("polna vrsta ob novem dogovoru: najstarejse sporocilo klicatelj izve, druga pridejo po vrsti",
            sprejeta && !prevec && novo && zavrnjenaPrej == listOf(ukazZ("s0") to "cas") &&
                b.prejeta.map { it.first } == vsa.drop(1) + ukazZ("novo"))
    }
    run {
        // Tretji pregled (7. 10. 2026): naprava ima dva programa z istim kljucem - »control« (seja od zacetka, ziva, redko
        // rabljena) in »os«, ki se pogosto znova zazene (vsakic nova, potrjena seja). Ko je meja sej na napravo dosezena,
        // izpadejo stare seje programa »os«, ki jih je nadomestila novejsa - ne ziva seja programa »control«.
        val o = Omrezje()
        val b = Naprava(o, "-tv", E2e.Meje(sejNaNapravo = 4))
        val control = Naprava(o, "-control")
        val os = Naprava(o, "-os", parNaprave = control.par)
        b.pozna(control, os); control.pozna(b); os.pozna(b)
        control.e2e.poslji(b.id, ukazZ("c1"))
        repeat(8) { i -> b.zdaj += 60_000; os.e2e.pozabiVse(); os.e2e.poslji(b.id, ukazZ("o$i")) }
        val sej = b.e2e.stSej(control.jedro)
        control.e2e.poslji(b.id, ukazZ("c2"))
        preveri("meja na napravo najprej izrine nadomescene seje, ne zive seje drugega programa iste naprave",
            os.jedro == control.jedro && sej == 4 && b.prejeta.filter { it.second == control.id }.map { it.first } == listOf(ukazZ("c1"), ukazZ("c2")) &&
                b.prejeta.count { it.second == os.id } == 8 && o.videno.none { it.tip == "data.error" })
    }
    run {
        // Sporocilo caka na dogovor; ko dogovor uspe, ga ni mogoce poslati (povezave s srediscem ni vec): klicatelj mora
        // izvedeti - prej je cakal na iztek casa. (Predolgo sporocilo poslji zavrne ze prej - cetrti pregled spodaj.)
        val (o, a, b) = par(Omrezje(takoj = false))
        val vVrsto = a.e2e.poslji(b.id, ukazZ("prvi")) && a.e2e.poslji(b.id, ukazZ("drugi"))
        var padlo = false
        o.neGre = { s -> if (s.tip == "data.chunk" && s.od == a.id && !padlo) { padlo = true; true } else false }
        o.dostaviVse()
        preveri("sporocilo, ki po dogovoru ne gre, klicatelj izve; drugo pride",
            vVrsto && a.zavrnjena == listOf(ukazZ("prvi") to "ni_poslano") && b.prejeta.map { it.first } == listOf(ukazZ("drugi")))
    }
    run {
        val (_, a, b) = par()
        a.krog["z\nprelomom"] = b.kljuc
        preveri("poznaKljuc: veljavna oznaka s kljucem v krogu",
            a.e2e.poznaKljuc(b.id) && listOf("neznana", "z\nprelomom", "", "a b").none { a.e2e.poznaKljuc(it) })
    }
    preveri("oznaka za dnevnik je samo vidni ASCII", E2e.zakrij("\ud800".repeat(20)).all { it in ' '..'~' } && E2e.zakrij("a\nb") == "a\\u000ab")
    preveri("notranje sporocilo: neveljaven UTF-8 ni besedilo (brez tihega nadomescanja)",
        E2e.utf8Strogo(byteArrayOf(0x7b, 0xC3.toByte(), 0x28)) == null && E2e.utf8Strogo("{\"č\":1}".toByteArray(Charsets.UTF_8)) == "{\"č\":1}")
    preveri("pregloboko gnezden JSON se prepozna pred razclenjevanjem",
        JsonLahki.pregloboko("{\"a\":" + "[".repeat(100_000)) && !JsonLahki.pregloboko("""{"a":[{"b":"[[[[{{{{"}]}""", 3) &&
            !JsonLahki.pregloboko("{\"a\":\"\\\"[[[[\"}", 1) && JsonLahki.pregloboko("[[[]]]", 2) && !JsonLahki.pregloboko("[[[]]]", 3))

    // ---- cetrti neodvisni pregled (7. 10. 2026, koncno stanje po tretjem): isti primeri kot CetrtiPregled na racunalniku.
    // Seznanjena naprava s predelanim programom (ali sredisce) drugo napravo preobremeni - s podpisi in s pomnilnikom.
    // Meritev na kodi pred popravki: krog120/CetrtiMeritev.kt.
    fun potrditevSredisca(oznaka: String, stanje: String, koda: String = "naprava_ni_povezana", napaka: String = "Naprave ni.") =
        Polja(JsonLahki.objekt("""{"id":"1","type":"data.ack","ref_id":"$oznaka","status":"$stanje","error":"$napaka","error_code":"$koda"}""")!!)
    run {
        // Sredisce isto (veljavno podpisano) ponudbo dostavi veckrat: b zanjo podpise EN odgovor.
        val (o, a, b) = par()
        val ponudba = a.ponudba(b, "ponovljena-1")
        repeat(20) { b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(ponudba)!!)) }
        preveri("ponovljena ponudba ne sprozi novega podpisa", b.podpisov == 1 && o.videno.count { it.tip == "data.answer" } == 1)
    }
    run {
        val (_, a, b) = par()
        repeat(E2e.NAJVEC_PODPISOV_NA_NAPRAVO + 40) { i -> b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, "p-$i"))!!)) }
        val vOknu = b.podpisov
        b.zdaj += E2e.OKNO_PODPISOV_MS + 1000
        b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, "po-oknu"))!!))
        preveri("ponudbe ene naprave imajo mejo podpisov; po izteku okna spet",
            vOknu == E2e.NAJVEC_PODPISOV_NA_NAPRAVO && b.podpisov == vOknu + 1)
    }
    run {
        val (o, a, b) = par()
        val c = Naprava(o, "-tv"); b.pozna(c); c.pozna(b)
        repeat(E2e.NAJVEC_PODPISOV_NA_NAPRAVO + 5) { i -> b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, "p-$i"))!!)) }
        val pred = b.podpisov
        val poslano = c.e2e.poslji(b.id, UKAZ)
        preveri("meja podpisov je na napravo, ne skupna",
            poslano && b.podpisov == pred + 1 && b.prejeta.any { it.second == c.id && it.first == UKAZ })
    }
    run {
        // Ukazi (ali odgovori) za veliko oznak iste naprave, ki ne odgovarja: vsak nov dogovor je podpis.
        val o = Omrezje(takoj = false)
        val a = Naprava(o, "-control"); val b = Naprava(o, "-os")
        val programi = List(E2e.NAJVEC_PODPISOV_NA_NAPRAVO + 10) { Naprava(o, "-p$it", parNaprave = b.par) }
        a.pozna(*programi.toTypedArray())
        val izidi = programi.mapIndexed { i, p -> a.e2e.poslji(p.id, ukazZ("u$i")) }
        preveri("meja podpisov steje vse programe iste naprave in tudi nase ponudbe",
            a.podpisov == E2e.NAJVEC_PODPISOV_NA_NAPRAVO && izidi.count { it } == E2e.NAJVEC_PODPISOV_NA_NAPRAVO && !izidi.last() &&
                o.cakajo.size == E2e.NAJVEC_PODPISOV_NA_NAPRAVO)
    }
    run {
        val (_, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        val veliko = """{"id":"r-velik","type":"control.result","ref_id":"${"x".repeat(200_000)}","payload":{"data":"${"y".repeat(400_000)}"}}"""
        val poslano = a.e2e.poslji(b.id, veliko, """{"id":"r-velik","type":"control.result"}""")
        preveri("poslano sporocilo se ne hrani - samo opis", poslano && b.prejeta.last().first == veliko && a.e2e.hranjenihZnakov() < 4000)
        a.e2e.poslji(b.id, veliko)                              // brez opisa (preizkusi): dolgo sporocilo se ne hrani niti kot opis
        val brezOpisa = a.e2e.hranjenihZnakov() < 4000
        a.e2e.poslji(b.id, UKAZ, "o".repeat(5000))              // predolg opis se ne hrani
        preveri("dolgo sporocilo brez opisa in predolg opis se ne hranita", brezOpisa && a.e2e.hranjenihZnakov() < 4000)
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        val predolgo = """{"id":"dolg","type":"control.result","payload":{"data":"${"x".repeat(E2e.DOLZINA_DELA * E2e.NAJVEC_DELOV + 10)}"}}"""
        preveri("predolgo sporocilo je zavrnjeno takoj, tudi brez seje, in ne zacne dogovora",
            !a.e2e.poslji(b.id, predolgo) && o.cakajo.isEmpty() && a.podpisov == 0 && a.e2e.cakajocihBajtov() == 0L)
    }
    run {
        // Cakajoca sporocila drzijo pomnilnik: meja bajtov na napravo (vsi njeni programi) in skupaj.
        val o = Omrezje(takoj = false)
        fun sporocilo(i: Int) = """{"id":"s$i","type":"control.result","payload":{"data":"${"x".repeat(1000)}"}}"""
        val en = sporocilo(0).toByteArray(Charsets.UTF_8).size.toLong()
        val a = Naprava(o, "-control", E2e.Meje(cakajocihNaNapravo = 3 * en + 10, cakajocih = 5 * en + 10))
        val b1 = Naprava(o, "-os"); val b2 = Naprava(o, "-tv", parNaprave = b1.par)       // dva programa iste naprave
        val c = Naprava(o, "-os"); val d = Naprava(o, "-os")
        a.pozna(b1, b2, c, d)
        fun poslji(komu: Naprava, i: Int) = a.e2e.poslji(komu.id, sporocilo(i), "s$i")
        val izidi = listOf(poslji(b1, 0), poslji(b1, 1), poslji(b2, 2), poslji(b2, 3), poslji(b1, 4), poslji(c, 5), poslji(c, 6), poslji(d, 7))
        val brezZavrnitev = a.zavrnjena.isEmpty()
        val drzi = a.e2e.cakajocihBajtov(b1.jedro) == 3 * en && a.e2e.cakajocihBajtov() == 5 * en
        a.zdaj += E2e.V_VRSTI_VELJA_MS + 1000                   // ko sporocila zastarajo, klicatelji izvejo in prostor je prost
        val poCasu = poslji(d, 8)
        preveri("cakajoca sporocila imajo mejo bajtov na napravo in skupaj",
            izidi == listOf(true, true, true, false, false, true, true, false) && brezZavrnitev && drzi && poCasu &&
                a.zavrnjena.sortedBy { it.first } == listOf("s0", "s1", "s2", "s5", "s6").map { it to "cas" } && a.e2e.cakajocihBajtov() == en)
    }
    run {
        // Sredisce kos dostavi, potem pa ga »zavrne« s kodo, ki pri nas pomeni »ni bilo poslano«: take kode klicatelj ne dobi.
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ); o.dostaviVse(); a.zavrnjena.clear()
        val kode = listOf("ni_poslano", "cas", "ni_seje", "ni_kljuca", "zascita", "naprava_ni_povezana")
        for ((i, koda) in kode.withIndex()) {
            a.e2e.poslji(b.id, ukazZ("k$i"))
            val kos = o.cakajo.removeAt(0)
            a.e2e.prejmi("data.ack", "", potrditevSredisca(kos.oznaka, "rejected", koda, "x".repeat(5000)))
        }
        preveri("koda zavrnitve sredisca ni nikoli nasa koda",
            a.zavrnjena == kode.indices.map { ukazZ("k$it") to (if (it < 5) "zavrnjeno" else "naprava_ni_povezana") })
    }
    run {
        // Dolgo sporocilo: sprejem sele z zadnjim kosom, zavrnitev prvega ali zadnjega - enkrat. Prej samo prvi kos.
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ); o.dostaviVse(); a.sprejeta.clear(); a.zavrnjena.clear()
        fun dolgo(oznaka: String): List<Sporocilo> {
            a.e2e.poslji(b.id, """{"id":"$oznaka","type":"control.result","payload":{"data":"${"x".repeat(2 * E2e.DOLZINA_DELA + 10)}"}}""", oznaka)
            val kosi = o.cakajo.toList()
            o.cakajo.clear()
            return kosi
        }
        var kosiD = dolgo("d1")
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosiD[0].oznaka, "accepted")); a.e2e.prejmi("data.ack", "", potrditevSredisca(kosiD[1].oznaka, "accepted"))
        val brezSprejema = a.sprejeta.isEmpty()                 // sprejet prvi kos se ni sprejeto sporocilo
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosiD[2].oznaka, "rejected"))
        val prvi = kosiD.size == 3 && brezSprejema && a.sprejeta.isEmpty() && a.zavrnjena == listOf("d1" to "naprava_ni_povezana")
        kosiD = dolgo("d2")
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosiD[0].oznaka, "rejected")); a.e2e.prejmi("data.ack", "", potrditevSredisca(kosiD[2].oznaka, "rejected"))
        val drugi = a.zavrnjena == listOf("d1" to "naprava_ni_povezana", "d2" to "naprava_ni_povezana")
        kosiD = dolgo("d3")
        for (kos in kosiD) a.e2e.prejmi("data.ack", "", potrditevSredisca(kos.oznaka, "accepted"))
        preveri("dolgo sporocilo: sprejem sele z zadnjim kosom, zavrnitev katerega koli - enkrat", prvi && drugi && a.sprejeta == listOf("d3"))
    }
    preveri("cast.media je med zascitenimi tipi", "cast.media" in E2e.ZASCITENI_TIPI && E2e.ZASCITENI_TIPI.size == 6)
    run {
        val (_, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        val sidSeje = a.e2e.oznakaSejeZa(b.id)
        b.e2e.prejmi("data.chunk", a.id, Polja(JsonLahki.objekt(
            """{"session_id":"$sidSeje","seq":5,"m":5,"i":0,"n":1,"data":"${"A".repeat(4 * E2e.DOLZINA_DELA)}"}""")!!))
        a.e2e.poslji(b.id, ukazZ("u2"))
        preveri("predolg zapis kosa se zavrne (pred dekodiranjem), seja dela naprej",
            E2e.NAJVEC_ZAPISA_KOSA == 65560 && b.prejeta.map { it.first } == listOf(UKAZ, ukazZ("u2")))
    }
    run {
        // Notranje (desifrirano) sporocilo poslje naprava s kljucem v krogu - ne nujno z nasim programom.
        val (_, a, b) = par()
        a.e2e.poslji(b.id, UKAZ)
        a.e2e.poslji(b.id, "{\"type\":\"control.command\",\"payload\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}")
        a.e2e.poslji(b.id, "{\"type\":\"control.command\",a\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}")
        a.e2e.poslji(b.id, ukazZ("u2"))
        preveri("notranje sporocilo: pregloboko gnezdeno ali nestrogo ne pride do programa, seja dela naprej",
            b.prejeta.map { it.first } == listOf(UKAZ, ukazZ("u2")))
    }
    preveri("pregloboko: zapis, ki ni strogi JSON, globine ne skrije (gola beseda, enojni narekovaji, komentar, seznam na vrhu)",
        JsonLahki.pregloboko("{a\":" + "[".repeat(100_000)) && JsonLahki.pregloboko("{'a\"':" + "[".repeat(100_000)) &&
            JsonLahki.pregloboko("{/*\"*/\"a\":" + "[".repeat(100_000)) && JsonLahki.pregloboko("[".repeat(100_000)) &&
            JsonLahki.pregloboko("{\"a\":" + "[".repeat(65) + "]".repeat(65) + "}") &&
            !JsonLahki.pregloboko("{\"a\":" + "[".repeat(63) + "]".repeat(63) + "}") &&
            !JsonLahki.pregloboko("{a:1}") && !JsonLahki.pregloboko("ni json") && !JsonLahki.pregloboko(""))
    preveri("strogObjekt: en sam objekt strogega JSON do dane globine, brez popustljivosti",
        JsonLahki.strogObjekt("""{"a":[1,2,{"b":null}],"c":"é\n\"\\"}""", 3) && !JsonLahki.strogObjekt("""{"a":[1,2,{"b":null}]}""", 2) &&
            JsonLahki.strogObjekt(""" {"a":-1.5e+3,"b":true,"c":{}} """) && !JsonLahki.strogObjekt("[1]") && !JsonLahki.strogObjekt("{a:1}") &&
            !JsonLahki.strogObjekt("{'a':1}") && !JsonLahki.strogObjekt("""{"a":1} x""") && !JsonLahki.strogObjekt("""{"a":"\x"}""") &&
            !JsonLahki.strogObjekt("""{"a":"\u12g4"}""") && !JsonLahki.strogObjekt("{\"a\":\"\t\"}") && !JsonLahki.strogObjekt("""{"a":NaN}""") &&
            !JsonLahki.strogObjekt("""{"a":1,}""") && !JsonLahki.strogObjekt("""{"a":"x""") && !JsonLahki.strogObjekt(""))

    // ---- peti neodvisni pregled (7. 10. 2026, stanje po cetrtem): isti primeri kot PetiPregled na racunalniku. Nobene
    // najdbe, ki bi izdajo ustavila - utrditve meje podpisov, vrst in zapisov o poslanem.
    // Meritev na kodi pred popravki: krog120/PetiMeritev.kt.
    fun dolgoSporocilo(oznaka: String, delov: Int = 3) =
        """{"id":"$oznaka","type":"control.result","payload":{"data":"${"x".repeat((delov - 1) * E2e.DOLZINA_DELA + 10)}"}}"""
    /** Dogovor med a in b do konca (omrezje z rocno dostavo); vrne oznako seje na strani a. */
    fun vzpostaviSejo(o: Omrezje, a: Naprava, b: Naprava): String {
        a.e2e.poslji(b.id, UKAZ); o.dostaviVse(); a.sprejeta.clear(); a.zavrnjena.clear()
        return a.e2e.oznakaSejeZa(b.id)
    }
    /** Poslje sporocilo v [delov] kosih in vrne kose (sredisce jih se ni dostavilo). */
    fun kosiSporocila(o: Omrezje, a: Naprava, b: Naprava, oznaka: String, delov: Int = 3): List<Sporocilo> {
        a.e2e.poslji(b.id, dolgoSporocilo(oznaka, delov), oznaka)
        val kosi = o.cakajo.toList()
        o.cakajo.clear()
        return kosi
    }
    fun stevecKosa(kos: Sporocilo): Long = JsonLahki.objekt(kos.tovor)!!.stevilo("seq")!!.toLong()
    fun sejeNi(sid: String, stevec: Long) = Polja(JsonLahki.objekt("""{"session_id":"$sid","code":"ni_seje","seq":$stevec}""")!!)
    run {
        // Tipka daljinca ob napravi, ki je ni v Linku: sredisce vsako ponudbo zavrne (20 s, ukaz na 0,1 s). Prej je
        // vsak ukaz zacel nov dogovor (podpis) - po 32 ukazih je bila meja polna se minuto po vrnitvi naprave.
        val (o, a, b) = par(Omrezje(takoj = false))
        repeat(200) { i ->
            a.zdaj += 100
            a.e2e.poslji(b.id, ukazZ("t$i"))
            while (o.cakajo.isNotEmpty()) a.e2e.prejmi("data.ack", "", potrditevSredisca(o.cakajo.removeAt(0).oznaka, "rejected"))
        }
        val podpisov = a.podpisov
        val brezDvojnih = a.zavrnjena.size == a.zavrnjena.toSet().size
        a.zdaj += E2e.NEUSPEL_DOGOVOR_CAKA_MS + 100
        o.takoj = true
        val gre = a.e2e.poslji(b.id, ukazZ("po"))
        preveri("ukazi napravi, ki je ni, ne porabijo meje podpisov; ko se vrne, ukaz gre",
            podpisov <= 8 && brezDvojnih && gre && b.prejeta.map { it.first } == listOf(ukazZ("po")))
    }
    run {
        val o = Omrezje()
        val a = Naprava(o, "-control"); val b = Naprava(o, "-os")
        a.pozna(b)                                              // b naprave a NIMA v krogu (»ni_kljuca«)
        repeat(100) { i -> a.zdaj += 100; a.e2e.poslji(b.id, ukazZ("t$i")) }
        preveri("naprava, ki nas nima v krogu, ne porabi meje podpisov", a.podpisov <= 5)
    }
    run {
        // Meja je locena za dogovore, ki jih zacnemo mi, in za odgovore na ponudbe druge naprave.
        val o = Omrezje()
        val a = Naprava(o, "-control"); val b = Naprava(o, "-os"); val a2 = Naprava(o, "-tv", parNaprave = a.par)
        a.pozna(b); a2.pozna(b); b.pozna(a, a2)
        repeat(E2e.NAJVEC_PODPISOV_NA_NAPRAVO + 5) { i -> b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, "p-$i"))!!)) }
        val polna = b.podpisov == E2e.NAJVEC_PODPISOV_NA_NAPRAVO
        val gre = b.e2e.poslji(a2.id, UKAZ)
        preveri("odgovori na ponudbe ne porabijo meje za nase ponudbe", polna && gre && a2.prejeta.map { it.first } == listOf(UKAZ))
    }
    run {
        // Program ob novi povezavi s srediscem naredi nov primerek E2e: meja velja za program, ne za primerek.
        val (o, a, b) = par()
        repeat(E2e.NAJVEC_PODPISOV_NA_NAPRAVO) { i -> b.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b, "p-$i"))!!)) }
        val b2 = Naprava(o, "-os", parNaprave = b.par); b2.pozna(a)
        repeat(10) { i -> b2.e2e.prejmi("data.offer", a.id, Polja(JsonLahki.objekt(a.ponudba(b2, "q-$i"))!!)) }
        preveri("nov primerek istega programa nima nove meje podpisov",
            b.podpisov == E2e.NAJVEC_PODPISOV_NA_NAPRAVO && b2.id == b.id && b2.podpisov == 0)
    }
    run {
        // Prej smo spremljali samo prvi in zadnji kos: zavrnjen srednji je ostal neopazen, klicatelj je dobil »sprejeto«.
        val (o, a, b) = par(Omrezje(takoj = false))
        vzpostaviSejo(o, a, b)
        var kosi = kosiSporocila(o, a, b, "d1")
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[0].oznaka, "accepted"))
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[1].oznaka, "rejected"))
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[2].oznaka, "accepted"))
        val srednji = kosi.size == 3 && a.sprejeta.isEmpty() && a.zavrnjena == listOf("d1" to "naprava_ni_povezana")
        kosi = kosiSporocila(o, a, b, "d2")
        var prezgodaj = false
        for (kos in kosi) {
            if (a.sprejeta.isNotEmpty()) prezgodaj = true
            a.e2e.prejmi("data.ack", "", potrditevSredisca(kos.oznaka, "accepted"))
        }
        preveri("zavrnjen srednji kos ni sprejeto sporocilo; vsi sprejeti = en sprejem ob zadnjem",
            srednji && !prezgodaj && a.sprejeta == listOf("d2") && a.zavrnjena == listOf("d1" to "naprava_ni_povezana"))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        val sejaPeti = vzpostaviSejo(o, a, b)
        val kosi = kosiSporocila(o, a, b, "d1")
        a.e2e.prejmi("data.error", b.id, sejeNi(sejaPeti, stevecKosa(kosi[1])))
        preveri("»seje ni« sredi dolgega sporocila javi to sporocilo", a.zavrnjena == listOf("d1" to "ni_seje"))
    }
    run {
        // Varovalo novega vodenja (vsak kos ima svoj zapis); na prejsnji kodi je ta preizkus uspel.
        val (o, a, b) = par(Omrezje(takoj = false))
        vzpostaviSejo(o, a, b)
        val prvo = kosiSporocila(o, a, b, "d0", E2e.NAJVEC_DELOV)
        for (i in 1 until 20) kosiSporocila(o, a, b, "d$i", E2e.NAJVEC_DELOV)
        for (kos in prvo) a.e2e.prejmi("data.ack", "", potrditevSredisca(kos.oznaka, "accepted"))
        preveri("zapisov o poslanih kosih je dovolj za dolga sporocila",
            prvo.size == E2e.NAJVEC_DELOV && a.sprejeta == listOf("d0") && a.zavrnjena.isEmpty())
    }
    run {
        // Povezava pade sredi sporocila v vec delih: klicatelj dobi false; poznejsa zavrnitev ze poslanega kosa ali
        // »seje ni« sporocila ne javi se enkrat.
        val (o, a, b) = par(Omrezje(takoj = false))
        val sejaPeti = vzpostaviSejo(o, a, b)
        var kosov = 0
        o.neGre = { s -> s.tip == "data.chunk" && ++kosov == 2 }
        val poslano = a.e2e.poslji(b.id, dolgoSporocilo("d1"), "d1")
        o.neGre = null
        val prvi = o.cakajo.first()
        o.cakajo.clear()
        a.e2e.prejmi("data.ack", "", potrditevSredisca(prvi.oznaka, "rejected"))
        val poZavrnitvi = a.zavrnjena.isEmpty()
        a.e2e.prejmi("data.error", b.id, sejeNi(sejaPeti, stevecKosa(prvi)))
        preveri("sporocilo, ki med posiljanjem ne gre, ne pusti zapisov", !poslano && poZavrnitvi && a.zavrnjena.isEmpty())
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        val sejaPeti = vzpostaviSejo(o, a, b)
        a.e2e.poslji(b.id, ukazZ("u2"), "u2")
        val kos = o.cakajo.removeAt(0)
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kos.oznaka, "rejected"))
        val enkratJavljeno = a.zavrnjena == listOf("u2" to "naprava_ni_povezana")
        a.e2e.prejmi("data.error", b.id, sejeNi(sejaPeti, stevecKosa(kos)))
        preveri("zavrnjeno sporocilo se ob »seje ni« ne javi se enkrat", enkratJavljeno && a.zavrnjena == listOf("u2" to "naprava_ni_povezana"))
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        vzpostaviSejo(o, a, b)
        a.e2e.poslji(b.id, ukazZ("u2"), "u2")
        a.e2e.prejmi("data.ack", "", potrditevSredisca(o.cakajo.removeAt(0).oznaka, "rejected", "k".repeat(5000)))
        preveri("predolga koda sredisca postane splosna", a.zavrnjena == listOf("u2" to "zavrnjeno") && E2e.NAJVEC_KODE_SREDISCA == 64)
    }
    run {
        val (_, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ, "u1")                          // caka na dogovor
        a.e2e.pozabi(b.id)
        preveri("pozabi javi cakajoca sporocila in ne drzi pomnilnika",
            a.zavrnjena == listOf("u1" to "cas") && a.e2e.hranjenihZnakov() == 0L && a.e2e.cakajocihBajtov() == 0L)
    }
    run {
        val (o, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ, "u1")
        a.zdaj += E2e.DOGOVOR_VELJA_MS + 1000
        o.dostaviVse()                                          // ponudba pride do b, njegov odgovor nazaj - prepozno
        preveri("prepozen odgovor javi cakajoce sporocilo in pospravi zapis",
            !a.e2e.imaSejo(b.id) && a.zavrnjena == listOf("u1" to "cas") && a.e2e.cakajociDogovori().isEmpty() &&
                a.e2e.hranjenihZnakov() == 0L)
    }
    run {
        // Sporocilo caka na dogovor, ki ne uspe, program pa nicesar vec ne poslje: pospravi ga ze vsako prejeto sporocilo
        // prenosa in klic pospravi() (klicatelj ga poklice ob vsakem prejetem sporocilu sredisca).
        val (_, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ, "u1")
        a.zdaj += E2e.V_VRSTI_VELJA_MS + 1000
        a.e2e.prejmi("data.ack", "", potrditevSredisca("e2e-neznano", "accepted"))
        val obPrejemu = a.zavrnjena == listOf("u1" to "cas") && a.e2e.cakajocihBajtov() == 0L
        val drugi = a.e2e.poslji(b.id, ukazZ("u2"), "u2")
        a.zdaj += E2e.V_VRSTI_VELJA_MS + 1000
        a.e2e.pospravi()
        preveri("zastarelo cakajoce sporocilo se javi tudi, ko nic ne posiljamo",
            obPrejemu && drugi && a.zavrnjena == listOf("u1" to "cas", "u2" to "cas"))
    }

    // ---- sesti (ozki) neodvisni pregled sprememb po petem (7. 10. 2026): isti primeri kot SestiPregled na racunalniku.
    // Nobene najdbe, ki bi izdajo ustavila. Meritev na kodi pred popravki: krog120/SestiMeritev.kt.
    run {
        // Prejemnik sejo izgubi; njegov »seje ni« pride PRED potrditvami sredisca za kose istega sporocila. Sporocilo je
        // ze javljeno kot zavrnjeno - potrditve ga ne smejo javiti se kot sprejeto (ali zavrnjeno drugic).
        val (o, a, b) = par(Omrezje(takoj = false))
        val sejaSesti = vzpostaviSejo(o, a, b)
        var kosi = kosiSporocila(o, a, b, "d1")
        a.e2e.prejmi("data.error", b.id, sejeNi(sejaSesti, stevecKosa(kosi[0])))
        val javljeno = a.zavrnjena == listOf("d1" to "ni_seje")
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[0].oznaka, "accepted"))
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[1].oznaka, "rejected"))
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[2].oznaka, "accepted"))
        val enkrat = a.sprejeta.isEmpty() && a.zavrnjena == listOf("d1" to "ni_seje")
        // ... in enako, kadar sredisce potrdi vse kose.
        val (o2, a2, b2) = par(Omrezje(takoj = false))
        val sejaDruga = vzpostaviSejo(o2, a2, b2)
        kosi = kosiSporocila(o2, a2, b2, "d2")
        a2.e2e.prejmi("data.error", b2.id, sejeNi(sejaDruga, stevecKosa(kosi[0])))
        for (kos in kosi) a2.e2e.prejmi("data.ack", "", potrditevSredisca(kos.oznaka, "accepted"))
        preveri("po »seje ni« potrditve kosov sporocila ne javijo vec",
            javljeno && enkrat && a2.sprejeta.isEmpty() && a2.zavrnjena == listOf("d2" to "ni_seje"))
    }
    run {
        // Sredisce ponudbo zavrne in prekine povezavo; program se poveze znova (nov primerek) in uporabnik takoj spet
        // poslje ukaz. Premor velja za program, ne za primerek - sicer bi vsak tak krog pomenil nov podpis.
        val o = Omrezje(takoj = false)
        val a = Naprava(o, "-control"); val b = Naprava(o, "-os")
        a.pozna(b); b.pozna(a)
        val prvi = a.e2e.poslji(b.id, UKAZ)
        a.e2e.prejmi("data.ack", "", potrditevSredisca(o.cakajo.removeAt(0).oznaka, "rejected"))
        val nov = Naprava(o, "-control", parNaprave = a.par); nov.pozna(b)      // isti program, nov primerek
        val takoj = nov.e2e.poslji(b.id, ukazZ("u2"))
        val brezPodpisa = nov.podpisov == 0 && o.cakajo.isEmpty()
        nov.zdaj += E2e.NEUSPEL_DOGOVOR_CAKA_MS + 100
        val pozneje = nov.e2e.poslji(b.id, ukazZ("u3"))
        preveri("premor po neuspelem dogovoru velja tudi za nov primerek",
            prvi && nov.id == a.id && !takoj && brezPodpisa && pozneje && nov.podpisov == 1)
    }
    run {
        // Zapisov o poslanih kosih je omejeno stevilo. Ce izpade zapis ENEGA kosa, njegove zavrnitve ne opazimo vec -
        // zato z njim izpadejo vsi kosi sporocila: klicatelj ne dobi izida (iztek casa), ne pa napacnega »sprejeto«.
        val (o, a, b) = par(Omrezje(takoj = false))
        vzpostaviSejo(o, a, b)                                  // en zapis (kos ukaza u1)
        val kosi = kosiSporocila(o, a, b, "d1")                 // trije zapisi
        repeat(E2e.NAJVEC_IZHODNIH - 2) { i -> a.e2e.poslji(b.id, ukazZ("k$i"), "k$i") }    // izrine u1, nato prvi kos d1
        o.cakajo.clear()
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[0].oznaka, "rejected"))        // kos, katerega zapis je izpadel
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[1].oznaka, "accepted"))
        a.e2e.prejmi("data.ack", "", potrditevSredisca(kosi[2].oznaka, "accepted"))
        preveri("izrinjen zapis kosa izrine vse kose sporocila",
            kosi.size == 3 && "d1" !in a.sprejeta && a.zavrnjena.none { it.first == "d1" })
    }
    run {
        // pospravi() klice bralna nit ob VSAKEM prejetem sporocilu sredisca: kadar v vrstah ne caka nic, ne sme cakati na
        // nit, ki ravno podpisuje ali posilja.
        val (o, a, b) = par(Omrezje(takoj = false))
        vzpostaviSejo(o, a, b)
        val drzi = java.util.concurrent.CountDownLatch(1)
        val spusti = java.util.concurrent.CountDownLatch(1)
        o.neGre = { s -> if (s.tip == "data.chunk") { drzi.countDown(); spusti.await(5, java.util.concurrent.TimeUnit.SECONDS) }; false }
        val posiljatelj = kotlin.concurrent.thread(isDaemon = true) { a.e2e.poslji(b.id, ukazZ("u2"), "u2") }
        val drzal = drzi.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val bralna = kotlin.concurrent.thread(isDaemon = true) { a.e2e.pospravi() }
        bralna.join(1000)
        val zastala = bralna.isAlive
        spusti.countDown(); posiljatelj.join(2000); bralna.join(2000)
        o.neGre = null
        // Ko nekaj caka, pospravi() se vedno dela: sporocilo, ki caka predolgo, javi.
        val (_, c, d) = par(Omrezje(takoj = false))
        c.e2e.poslji(d.id, UKAZ, "u1")
        c.zdaj += E2e.V_VRSTI_VELJA_MS + 1000
        c.e2e.pospravi()
        preveri("pospravi() ne caka na zaklep, kadar v vrstah ne caka nic", drzal && !zastala && c.zavrnjena == listOf("u1" to "cas"))
    }
    run {
        // Tri sporocila cakajo na dogovor. Ko odgovor pride, posiljanje drugega vrze izjemo: prvo in tretje gresta, za
        // drugo klicatelj izve. Prej sta drugo in tretje izginili brez sledu (vrsta je bila ze izpraznjena).
        val (o, a, b) = par(Omrezje(takoj = false))
        for (i in 1..3) a.e2e.poslji(b.id, ukazZ("u$i"), "u$i")
        var kosov = 0
        o.neGre = { s -> if (s.tip == "data.chunk" && ++kosov == 2) throw java.io.IOException("vticnica je padla"); false }
        o.dostaviVse()
        o.neGre = null
        preveri("izjema pri posiljanju enega cakajocega sporocila ne izgubi drugih",
            b.prejeta.map { it.first } == listOf(ukazZ("u1"), ukazZ("u3")) && a.zavrnjena == listOf("u2" to "ni_poslano"))
    }
    run {
        val (_, a, b) = par(Omrezje(takoj = false))
        a.e2e.poslji(b.id, UKAZ, "u1")
        a.e2e.pozabiVse()
        preveri("pozabiVse javi cakajoca sporocila", a.zavrnjena == listOf("u1" to "cas") && a.e2e.cakajocihBajtov() == 0L)
    }

    if (napak > 0) { println("\nNAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("E2eTest: OK")
}
