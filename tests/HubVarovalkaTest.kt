package si.safeer.tv.cast

/**
 * Varovalka kode za povezavo (HubVarovalka): ista pravila in isti primeri kot tests/test_link_varovalka.py na
 * racunalniku. Cista logika z lazno uro.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun <T> enako(opis: String, pricakovano: T, dobljeno: T) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private const val A = "192.168.0.66"
private const val B = "192.168.0.67"
private const val C = "192.168.0.68"
private const val D = "192.168.0.69"
private const val URA = 3_600_000L
private const val DAN = 86_400_000L

private class Okolje(stanje: String? = null, var t: Long = 1_700_000_000_000L) {
    val zapore = ArrayList<HubVarovalka.Zapora>()
    val shranjeno = ArrayList<String>()
    val v = HubVarovalka(ura = { t }, stanje = stanje, shrani = { shranjeno.add(it) }).also { it.obZapori = { z -> zapore.add(z) } }

    fun zapri() {
        var n = 0
        while (!v.zaprto() && n++ < 200) v.poskus(A)
    }
}

private fun pravila() {
    println("Pravila")
    Okolje().apply {
        val prvih = (0 until 19).map { v.poskus(if (it % 3 != 0) A else B) }
        preveri("devetnajst poskusov je izvedenih", prvih.all { it })
        preveri("pod mejo ni zapore", !v.zaprto() && zapore.isEmpty())
        preveri("dvajsetega poskusa sredisce ne izvede", !v.poskus(C))
        preveri("meja zapre povezovanje s kodo", v.zaprto())
        enako("dogodek ob zapori", listOf(Triple(URA, "kode", 1)), zapore.map { Triple(it.trajanjeMs, it.razlog, it.ponovitev) })
        enako("najpogostejsi vir je prvi", listOf(A to 12, B to 7, C to 1), zapore[0].viri)
        val s = v.stanje()
        enako("stanje ob zapori", Triple(true, URA, HubVarovalka.KREDIT_VABILA), Triple(s.zaprto, s.seMs, s.kredit))
        preveri("med zaporo ni zacetka", !v.smeZaceti() && !v.zacetek(D))
        preveri("med zaporo ni poskusa", !v.poskus(D))
        enako("med zaporo ni novih dogodkov", 1, zapore.size)
    }
    Okolje().apply {
        repeat(19) { v.poskus(A) }
        t += URA + 1
        val drugih = (0 until 19).map { v.poskus(A) }
        preveri("poskusi, starejsi od ure, ne stejejo", drugih.all { it } && zapore.isEmpty())
        enako("v oknu jih je devetnajst", 19, v.stanje().poskusov)
    }
    Okolje().apply {
        // Pet kod na minuto na naslov je pod pragom obrambe po viru; skupna meja ga vseeno ustavi.
        var izvedenih = 0
        repeat(60) {
            t += 61_000L
            for (vir in listOf(A, B, C, D)) {
                if (v.zacetek(vir)) repeat(5) { if (v.poskus(vir)) izvedenih++ }
            }
        }
        preveri("pocasen napadalec z vec naslovi: najvec ${HubVarovalka.POSKUSOV} poskusov (izvedenih $izvedenih)", izvedenih <= HubVarovalka.POSKUSOV)
        preveri("pocasen napadalec z vec naslovi je ustavljen", v.zaprto())
    }
    Okolje().apply {
        repeat(100) { i ->
            val vir = "192.168.0.${10 + i % 5}"
            v.zacetek(vir)
            v.poskus(vir)
            v.uspeh(vir)
        }
        val s = v.stanje()
        enako("uspesna seznanitev ne steje", Triple(false, 0, 0), Triple(s.zaprto, s.poskusov, s.zacetkov))
        enako("domace povezovanje ne zapre", 0, zapore.size)
    }
    Okolje().apply {
        repeat(10) { v.poskus(A) }
        v.zacetek(B)
        v.poskus(B)
        v.uspeh(B)
        v.uspeh(B)
        val s = v.stanje()
        enako("uspeh vrne samo svoj poskus", 10 to 0, s.poskusov to s.zacetkov)
    }
    Okolje().apply {
        val prvih = (0 until 29).map { v.zacetek(A) }
        preveri("devetindvajset zacetkov je sprejetih", prvih.all { it })
        preveri("trideseti zacetek zapre", !v.zacetek(A) && v.zaprto())
        enako("razlog in vir zapore", "zacetki" to listOf(A to 30), zapore[0].razlog to zapore[0].viri)
    }
}

private fun vabilo() {
    println("Vabilo zaupane naprave")
    Okolje().apply {
        zapri()
        preveri("z vabilom se prijava sme zaceti", v.smeZaceti(vabilo = true) && v.zacetek(A, vabilo = true))
        val poskusi = (0 until HubVarovalka.KREDIT_VABILA).map { v.poskus(A, vabilo = true) }
        preveri("deset poskusov z vabilom", poskusi.all { it })
        preveri("enajstega ni", !v.poskus(A, vabilo = true))
        preveri("kredit je porabljen: do konca zapore samo koda QR", !v.zacetek(A, vabilo = true) && !v.smeZaceti(vabilo = true))
        enako("zapora je ena", 1, zapore.size)
    }
    Okolje().apply {
        zapri()
        repeat(4) {
            v.poskus(A, vabilo = true)
            v.uspeh(A)
        }
        enako("uspeh med zaporo vrne kredit", HubVarovalka.KREDIT_VABILA, v.stanje().kredit)
    }
    Okolje().apply {
        repeat(19) { v.poskus(A) }
        preveri("uporabnikov poskus ob sprozitvi zapore velja", v.poskus(B, vabilo = true))
        preveri("zapora je vseeno sprozena", v.zaprto())
        enako("porabljen je en kredit", HubVarovalka.KREDIT_VABILA - 1, v.stanje().kredit)
    }
}

private fun ponovitve() {
    println("Ponovitve")
    Okolje().apply {
        zapri()
        enako("prva zapora traja eno uro", URA, zapore.last().trajanjeMs)
        t += URA
        preveri("po uri je povezovanje spet odprto", !v.zaprto())
        enako("po zapori velja manjsa meja", HubVarovalka.POSKUSOV_PO_ZAPORI, v.stanje().mejaPoskusov)
        val stirje = (0 until 4).map { v.poskus(A) }
        preveri("stirje poskusi po zapori", stirje.all { it })
        preveri("peti zapre", !v.poskus(A))
        enako("druga zapora traja en dan", DAN to 2, zapore.last().trajanjeMs to zapore.last().ponovitev)
        t += DAN
        val devet = (0 until 9).map { v.zacetek(A) }
        preveri("devet zacetkov po zapori", devet.all { it })
        preveri("deseti zacetek zapre", !v.zacetek(A))
        enako("tretja zapora traja en teden", 7 * DAN to "zacetki", zapore.last().trajanjeMs to zapore.last().razlog)
        t += 7 * DAN
        zapri()
        enako("cetrta zapora traja en teden", 7 * DAN to 4, zapore.last().trajanjeMs to zapore.last().ponovitev)
    }
    Okolje().apply {
        // Stevilka iz opomb izdaje: koliko kod lahko v enem letu poskusi napadalec, ki nikoli ne odneha.
        var izvedenih = 0
        val konec = t + 365 * DAN
        while (t < konec) {
            if (v.poskus(A)) izvedenih++ else t += 60_000L
        }
        preveri("vztrajen napadalec v enem letu: pod 250 poskusov (izvedenih $izvedenih)", izvedenih < 250)
        preveri("verjetnost zadetka v enem letu je pod 0,03 %", izvedenih / 900000.0 < 0.0003)
    }
    Okolje().apply {
        zapri()
        t += URA + HubVarovalka.POZABI_MS - 10_000L
        enako("tik pred tridesetimi dnevi se manjsa meja", HubVarovalka.POSKUSOV_PO_ZAPORI, v.stanje().mejaPoskusov)
        t += 20_000L
        val s = v.stanje()
        enako("po tridesetih dneh miru se ponovitve pozabijo", HubVarovalka.POSKUSOV to 0, s.mejaPoskusov to s.ponovitev)
        zapri()
        enako("potem je zapora spet ena ura", URA, zapore.last().trajanjeMs)
    }
    Okolje().apply {
        zapri()
        v.odpri()
        preveri("uporabnik sam konca zaporo", !v.zaprto())
        enako("po njej velja manjsa meja", HubVarovalka.POSKUSOV_PO_ZAPORI, v.stanje().mejaPoskusov)
    }
}

private fun shramba() {
    println("Stanje v shrambi")
    val prvo = Okolje()
    repeat(7) { prvo.v.poskus(A) }
    prvo.v.zacetek(B)
    Okolje(stanje = prvo.shranjeno.last(), t = prvo.t).apply {
        val s = v.stanje()
        enako("stetje prezivi ponovni zagon", Triple(7, 1, false), Triple(s.poskusov, s.zacetkov, s.zaprto))
    }
    prvo.zapri()
    prvo.t += 600_000L
    Okolje(stanje = prvo.shranjeno.last(), t = prvo.t).apply {
        val s = v.stanje()
        enako("zapora prezivi ponovni zagon", Triple(true, 3_000_000L, 1), Triple(s.zaprto, s.seMs, s.ponovitev))
        preveri("ponovni zagon sredisca napadalcu ne vrne meje", !v.zacetek(A))
        enako("izvoz je enak shranjenemu", prvo.shranjeno.last(), v.izvozi())
    }
    Okolje().apply {
        repeat(10) { v.poskus(A) }
        t -= DAN
        enako("ura nazaj: zapisi veljajo za pravkar narejene", 10, v.stanje().poskusov)
        val devet = (0 until 9).map { v.poskus(A) }
        preveri("ura nazaj: se devet poskusov", devet.all { it })
        preveri("ura nazaj: dvajseti zapre", !v.poskus(A))
        t -= 3650 * DAN
        preveri("zapora ne more trajati dlje od najdaljse", v.stanje().seMs <= HubVarovalka.ZAPORE_MS.last())
    }
    for (pokvarjeno in listOf("x", "1\nabc\n0\n0\n\n", "1\n0\n0\n0\nx@y,@,12\n", "2\n0\n0\n0\n\n", "1\n0\n-5\n99\n\n", "")) {
        Okolje(stanje = pokvarjeno).apply {
            preveri("pokvarjeno stanje se prezre (${pokvarjeno.replace("\n", "|")})", !v.zaprto() && v.poskus(A))
        }
    }
    var t = 1_700_000_000_000L
    val pada = HubVarovalka(ura = { t }, shrani = { throw java.io.IOException("shramba je polna") })
    preveri("napaka pri pisanju ne ustavi", pada.zacetek(A) && pada.poskus(A))
    enako("vir v zapisu nima locil", "192.1680.1x", HubVarovalka.cist("192.168,0@.1\nx"))
    Okolje().apply {
        v.poskus("a,b@c\nd")
        Okolje(stanje = shranjeno.last(), t = t).apply {
            enako("vir z locili ne pokvari zapisa", 1, v.stanje().poskusov)
        }
    }
}

fun main() {
    pravila()
    vabilo()
    ponovitve()
    shramba()
    if (napak > 0) {
        println("\nNAPAK: $napak")
        kotlin.system.exitProcess(1)
    }
    println("\nVSE V REDU")
}
