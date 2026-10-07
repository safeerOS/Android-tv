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

    fun poslji(od: String, tip: String, cilj: String, oznaka: String, tovor: String): Boolean {
        var s: Sporocilo? = Sporocilo(tip, od, cilj, oznaka, tovor)
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

private class Naprava(val omrezje: Omrezje, pripona: String = "") {
    private val par = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val kljuc: String = Base64.getEncoder().encodeToString(par.public.encoded)
    val jedro: String = idIzKljuca(kljuc)
    var id: String = jedro + pripona
    val krog = HashMap<String, String>()
    val prejeta = ArrayList<Triple<String, String, String>>()       // (notranje sporocilo, id posiljatelja, jedro iz kljuca)
    val seje = ArrayList<String>()
    val zavrnjena = ArrayList<Pair<String, String>>()
    val sprejeta = ArrayList<String>()
    var zdaj = 1_000_000L
    /** Obdelava prejetega sporocila vrze izjemo (napaka v programu, ki sporocilo obdela). */
    var pade = false
    val e2e = E2e(
        mojId = { id },
        poslji = { tip, cilj, oznaka, tovor -> omrezje.poslji(id, tip, cilj, oznaka, tovor) },
        podpisi = { b -> Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(par.private); update(b); sign() }) },
        kljucZa = { krog[it] },
        preveri = ::preveriPodpis,
        idIzKljuca = ::idIzKljuca,
        obSporocilu = { s, od, j -> if (pade) throw IllegalStateException("napaka v obdelavi"); prejeta.add(Triple(s, od, j)) },
        obSeji = { seje.add(it) },
        obZavrnitvi = { s, _, koda -> zavrnjena.add(s to koda) },
        obSprejemu = { sprejeta.add(it) },
        ura = { zdaj },
    )

    init { omrezje.naprave[id] = this }

    fun pozna(vararg druge: Naprava): Naprava { for (d in druge) krog[d.id] = d.kljuc; return this }

    /** Veljavno podpisana ponudba te naprave z izbrano oznako seje (to lahko naredi vsaka naprava v krogu). */
    fun ponudba(za: Naprava, sessionId: String): String {
        val epk = E2e.novPar().second
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16) { 'n'.code.toByte() })
        val sig = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(par.private); update(E2e.podatkiPonudbe(sessionId, id, za.id, nonce, epk)); sign() })
        return """{"session_id":"$sessionId","purpose":"link","v":2,"from":"$id","to":"${za.id}","nonce":"$nonce","epk":"$epk","sig":"$sig"}"""
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
        a.e2e.poslji(b.id, UKAZ)
        preveri("naslednji ukaz zacne nov dogovor takoj", o.cakajo.map { it.tip } == listOf("data.offer"))
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
        preveri("»nadaljuj na napravi« je zasciten tip", "handoff.request" in E2e.ZASCITENI_TIPI && E2e.ZASCITENI_TIPI.size == 5)

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
        a2.e2e.poslji(b2.id, dolgo)
        val kosi = o2.cakajo.toList()
        for (delDolgega in kosi) a2.e2e.prejmi("data.ack", "", potrditev(delDolgega.oznaka, stanje = "accepted", koda = ""))
        preveri("dolgo sporocilo (3 deli) javi sprejem enkrat", kosi.size == 3 && a2.sprejeta == listOf(dolgo))
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

    if (napak > 0) { println("\nNAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("E2eTest: OK")
}
