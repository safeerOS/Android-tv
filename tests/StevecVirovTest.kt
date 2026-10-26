package si.safeer.tv.os

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

// Preizkus brez knjiznice JSON (naloga 69): JVM preizkusi je nimajo, zato JSON
// skupnih primerov beremo z istim bralnikom, kot ga uporablja StevecVirov.
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

// Skupni primeri so v repozitoriju; pot je relativna na koren (cwd), kot pri Pythonu.
private const val POT_PRIMEROV = "tests/skupni-primeri/stevec-virov.json"

private val ZDAJ: LocalDateTime = LocalDateTime.of(2026, 10, 20, 20, 0, 0)

// Datoteka primerov je vecja od meje za datoteko stevca, zato ji damo vec prostora.
private const val NAJVEC_ZNAKOV_PRIMEROV = 16 * 1024 * 1024

// Prebere JSON z bralnikom iz StevecVirov; neveljaven vhod je napaka preizkusa.
private fun beriJson(besedilo: String, najvecZnakov: Int = NAJVEC_ZNAKOV_PRIMEROV): Any? {
    val v = StevecVirov.Json.beri(besedilo, najvecZnakov = najvecZnakov)
    if (v === StevecVirov.Json.NEVELJAVNO) throw IllegalStateException("neveljaven JSON")
    return v
}

// Pot po slovarjih (npr. "viri", "webtor", "predvajal"); manjkajoce je null.
private fun polje(o: Any?, vararg kljuci: String): Any? {
    var v = o
    for (k in kljuci) v = (v as? Map<*, *>)?.get(k)
    return v
}

private fun dolg(o: Any?, vararg kljuci: String): Long? = polje(o, *kljuci) as? Long

// Bralnik da cela stevila kot Long/BigInteger in decimalke kot Double; primerjamo
// vrednost IN vrsto, kot Python (type(dejansko) is type(pricakovano)), da 1 in 1.0 nista enaka.
private fun list(v: Any?): Any? = when (v) {
    null -> null
    is Int, is Long, is Short, is Byte, is BigInteger -> "celo " + v.toString()
    is Double -> "dec $v"
    is Float -> "dec " + v.toDouble()
    else -> v
}

// Zbere razlike med pricakovano podmnozico in dejanskim povzetkom.
private fun razlike(pot: String, pricakovano: Any?, dobljeno: Any?, izid: MutableList<String>) {
    if (pricakovano is Map<*, *>) {
        if (dobljeno !is Map<*, *>) {
            izid.add("$pot ni objekt")
            return
        }
        for ((k, v) in pricakovano) {
            if (!dobljeno.containsKey(k)) {
                izid.add("$pot.$k manjka")
            } else {
                razlike("$pot.$k", v, dobljeno[k], izid)
            }
        }
    } else if (list(pricakovano) != list(dobljeno)) {
        izid.add("$pot: pricakovano ${list(pricakovano)}, dobljeno ${list(dobljeno)}")
    }
}

private fun zacasnaMapa(): File = Files.createTempDirectory("stevec-test-").toFile()

private fun nizAliNull(o: Map<*, *>, kljuc: String): String? = o[kljuc] as? String

private fun pozeniPrimer(primer: Map<*, *>): List<String> {
    val izid = ArrayList<String>()
    val s = StevecVirov(File(zacasnaMapa(), "stevec.json"))
    val zapisi = primer["zapisi"] as List<*>
    for ((i, element) in zapisi.withIndex()) {
        val z = element as Map<*, *>
        val ok = s.zabelezi(
            nizAliNull(z, "vir"),
            z["uspeh"] as Boolean,
            z["cas_do_slike_ms"] as Long?,
            z["trajanje_s"] as Long,
            nizAliNull(z, "storitev"),
            zdaj = LocalDateTime.parse(z["zdaj"] as String),
        )
        if (!ok) izid.add("zapis $i vrnil false")
    }
    val klic = primer["povzetek"] as Map<*, *>
    val dejansko = s.povzetek((klic["dni"] as Long).toInt(), zdaj = LocalDateTime.parse(klic["zdaj"] as String))
    razlike("povzetek", primer["pricakovano"], dejansko, izid)
    return izid
}

private fun skupniPrimeri() {
    println("Skupni primeri ($POT_PRIMEROV)")
    val datoteka = File(POT_PRIMEROV)
    if (!datoteka.isFile) {
        preveriEnako("datoteka s primeri obstaja (cwd = koren repozitorija)", true, false)
        return
    }
    val dokument = beriJson(datoteka.readText(Charsets.UTF_8)) as Map<*, *>
    preveriEnako("verzija primerov", 1L, dokument["verzija"])
    val primeri = dokument["primeri"] as List<*>
    val imena = HashSet<String>()
    var uspesnih = 0
    for (element in primeri) {
        val primer = element as Map<*, *>
        val ime = primer["ime"] as String
        imena.add(ime)
        val izid = pozeniPrimer(primer)
        if (izid.isEmpty()) uspesnih++
        preveriEnako("primer $ime", emptyList<String>(), izid)
    }
    preveriEnako("imena primerov so enolicna", primeri.size, imena.size)
    println("  skupni primeri: $uspesnih/${primeri.size} uspesnih")
}

private fun formatNaDisku() {
    println("Format datoteke (enak kot json.dumps sort_keys v Pythonu)")
    val pot = File(zacasnaMapa(), "podatki/stevec.json")
    val s = StevecVirov(pot)
    preveriEnako("zapis uspe", true, s.zabelezi("webtor", true, 1000, 3600, zdaj = ZDAJ))
    preveriEnako("zapis torbox", true, s.zabelezi("debrid_takoj", true, 1500, 30, " TorBox ", zdaj = ZDAJ))
    preveriEnako("neuspeh p2p", true, s.zabelezi("p2p", false, 500, 3600, zdaj = ZDAJ))
    val pricakovano = "{\"dnevi\":{\"2026-10-20\":{" +
        "\"debrid_takoj+torbox\":{\"casi\":[1500],\"kratki\":1,\"neuspehi\":0,\"predvajal\":0,\"sekunde\":0}," +
        "\"p2p\":{\"casi\":[],\"kratki\":0,\"neuspehi\":1,\"predvajal\":0,\"sekunde\":0}," +
        "\"webtor\":{\"casi\":[1000],\"kratki\":0,\"neuspehi\":0,\"predvajal\":1,\"sekunde\":3600}}}," +
        "\"verzija\":1,\"vrsta\":\"safeer-stevec-virov\"}"
    preveriEnako("bajti datoteke", pricakovano, pot.readText(Charsets.UTF_8))
    val ostanki = pot.parentFile.listFiles()?.map { it.name }?.sorted()
    preveriEnako("brez zacasnih datotek", listOf("stevec.json"), ostanki)
    try {
        val pravice = PosixFilePermissions.toString(Files.getPosixFilePermissions(pot.toPath()))
        preveriEnako("pravice samo lastnik", "rw-------", pravice)
        val mapa = PosixFilePermissions.toString(Files.getPosixFilePermissions(pot.parentFile.toPath()))
        preveriEnako("nova mapa samo lastnik", "rwx------", mapa)
    } catch (e: UnsupportedOperationException) {
        println("  (POSIX pravice niso na voljo, preskoceno)")
    }
    val znova = StevecVirov(pot).povzetek(7, zdaj = ZDAJ)
    preveriEnako("ponovno nalaganje: webtor", 1L, dolg(znova, "viri", "webtor", "predvajal"))
    preveriEnako("ponovno nalaganje: kratki debrid", 1L, dolg(znova, "viri", "debrid_takoj", "kratki"))
}

private fun casVMs() {
    println("Cas kot ms in LocalDate")
    val pot = File(zacasnaMapa(), "stevec.json")
    val s = StevecVirov(pot)
    val ms = ZDAJ.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    preveriEnako("zapis z ms", true, s.zabelezi("iptv", true, 800, 120, zdajMs = ms))
    preveriEnako("zapis z LocalDate", true, s.zabelezi("iptv", true, 900, 120, zdaj = LocalDate.of(2026, 10, 20)))
    val p = s.povzetek(1, zdaj = LocalDate.of(2026, 10, 20))
    preveriEnako("oba na isti lokalni dan", 2L, dolg(p, "viri", "iptv", "predvajal"))
    preveriEnako("povzetek z ms", 2L, dolg(s.povzetek(1, zdajMs = ms), "skupaj_predvajal"))
    preveriEnako("percentil prazen", null, StevecVirov.percentil(emptyList(), 50))
    preveriEnako("zaokrozi 0.025 -> 0.03", 0.03, StevecVirov.zaokrozi(90, 3600, 2))
    preveriEnako("zaokrozi skupaj 0", 0.0, StevecVirov.zaokrozi(5, 0, 4))
}

private fun niti() {
    println("Niti: 8 x 500")
    val pot = File(zacasnaMapa(), "stevec.json")
    val s = StevecVirov(pot)
    val neuspelih = AtomicInteger(0)
    val niti = ArrayList<Thread>()
    for (n in 0 until 8) {
        val vir = if (n % 2 == 0) "webtor" else "usenet_torbox"
        val t = Thread {
            for (i in 0 until 500) {
                if (!s.zabelezi(vir, true, (i % 97).toLong(), 3600, zdaj = ZDAJ)) neuspelih.incrementAndGet()
            }
        }
        niti.add(t)
        t.start()
    }
    for (t in niti) t.join()
    preveriEnako("vsi zapisi uspeli", 0, neuspelih.get())
    preveriEnako("v pomnilniku 4000", 4000L, dolg(s.povzetek(7, zdaj = ZDAJ), "skupaj_predvajal"))
    val znova = StevecVirov(pot).povzetek(7, zdaj = ZDAJ)
    preveriEnako("z diska 4000", 4000L, dolg(znova, "skupaj_predvajal"))
    preveriEnako("torbox 2000", 2000L, dolg(znova, "narocnine", "torbox", "predvajal"))
    val vzorec = (polje(beriJson(pot.readText()), "dnevi", "2026-10-20", "webtor", "casi") as List<*>).size
    preveriEnako("vzorec omejen na 50", 50, vzorec)
}

private fun pokvarjenaDatoteka() {
    println("Pokvarjena ali tuja datoteka")
    val mapa = zacasnaMapa()
    val pot = File(mapa, "stevec.json")
    pot.writeText("{ni json")
    val s = StevecVirov(pot)
    preveriEnako("pokvarjena premaknjena", true, File(mapa, "stevec.json.pokvarjeno").isFile)
    preveriEnako("vsebina ohranjena", "{ni json", File(mapa, "stevec.json.pokvarjeno").readText())
    preveriEnako("zacne prazen", 0L, dolg(s.povzetek(7, zdaj = ZDAJ), "skupaj_predvajal"))
    preveriEnako("zapis po pokvarjeni", true, s.zabelezi("webtor", true, 1000, 3600, zdaj = ZDAJ))
    preveriEnako("nova datoteka veljavna", "safeer-stevec-virov", polje(beriJson(pot.readText()), "vrsta"))

    pot.writeText("{\"vrsta\":\"tuja\",\"verzija\":1,\"dnevi\":{}}")
    StevecVirov(pot)
    preveriEnako("druga gre v .pokvarjeno.1", true, File(mapa, "stevec.json.pokvarjeno.1").isFile)

    // Vsaka od teh mora biti zavrnjena, ker je Python tudi zavrne.
    val v = "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1,\"dnevi\":"
    val slabe = listOf(
        "[]",
        "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":2,\"dnevi\":{}}",
        v + "[]}",
        v + "{\"2026-02-30\":{}}}",
        v + "{\"2026-10-20\":{\"neznan\":{\"predvajal\":0,\"kratki\":0,\"neuspehi\":0,\"sekunde\":0,\"casi\":[]}}}}",
        v + "{\"2026-10-20\":{\"webtor\":{\"predvajal\":-1,\"kratki\":0,\"neuspehi\":0,\"sekunde\":0,\"casi\":[]}}}}",
        v + "{\"2026-10-20\":{\"webtor\":{\"predvajal\":1.5,\"kratki\":0,\"neuspehi\":0,\"sekunde\":0,\"casi\":[]}}}}",
        v + "{\"2026-10-20\":{\"webtor\":{\"predvajal\":true,\"kratki\":0,\"neuspehi\":0,\"sekunde\":0,\"casi\":[]}}}}",
        v + "{}} odvec",
        // Naloga 69: strogi bralnik zavrne, kar zavrne json.loads.
        "{vrsta:\"safeer-stevec-virov\",verzija:1,dnevi:{}}",
        "\uFEFF" + v + "{}}",
        v + "{},}",
        v + "{\"2026-10-20\":{\"webtor\":{\"predvajal\":01,\"kratki\":0,\"neuspehi\":0,\"sekunde\":0,\"casi\":[]}}}}",
        v + "{\"0000-01-01\":{}}}",
        "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1,\"dnevi\":{},\"x\":" + "[".repeat(100000) + "]".repeat(100000) + "}",
        v + "{}, \"x\":1" + "0".repeat(4300) + "}",
        "{\"vrsta\":\"safeer-stevec-virov\t\",\"verzija\":1,\"dnevi\":{}}",
        v + "{}} // komentar",
        "",
    )
    for ((i, vsebina) in slabe.withIndex()) {
        val m = zacasnaMapa()
        val p = File(m, "s.json")
        p.writeText(vsebina)
        StevecVirov(p)
        preveriEnako("zavrnjena slaba datoteka $i", true, File(m, "s.json.pokvarjeno").isFile && !p.exists())
    }

    // Neveljaven UTF-8 (tudi nadomestni znak, zapisan v treh bajtih) je pokvarjena datoteka.
    val slabiBajti = listOf(
        byteArrayOf(0xFF.toByte()),
        (v + "{}, \"x\":\"").toByteArray() + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "\"}".toByteArray(),
        (v + "{}, \"x\":\"").toByteArray() + byteArrayOf(0xC0.toByte(), 0xAF.toByte()) + "\"}".toByteArray(),
    )
    for ((i, bajti) in slabiBajti.withIndex()) {
        val m = zacasnaMapa()
        val p = File(m, "s.json")
        p.writeBytes(bajti)
        StevecVirov(p)
        preveriEnako("zavrnjen slab UTF-8 $i", true, File(m, "s.json.pokvarjeno").isFile && !p.exists())
    }

    // Veljavna datoteka ostane na mestu.
    val m = zacasnaMapa()
    val p = File(m, "s.json")
    p.writeText("{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1,\"dnevi\":{\"2026-10-20\":{\"webtor\":{\"predvajal\":3,\"kratki\":0,\"neuspehi\":0,\"sekunde\":10800,\"casi\":[5,6]}}}}\n")
    val z = StevecVirov(p).povzetek(7, zdaj = ZDAJ)
    preveriEnako("veljavna prebrana", 3L, dolg(z, "skupaj_predvajal"))
    preveriEnako("veljavna ni premaknjena", false, File(m, "s.json.pokvarjeno").exists())

    // Napaka pri pisanju (mapa je v resnici datoteka): false, brez izjeme.
    val ovira = File(zacasnaMapa(), "ovira")
    ovira.writeText("x")
    val nemogoc = StevecVirov(File(ovira, "stevec.json"))
    preveriEnako("pisanje ne uspe, vrne false", false, nemogoc.zabelezi("webtor", true, 1, 3600, zdaj = ZDAJ))
    preveriEnako("povzetek vseeno dela", 1L, dolg(nemogoc.povzetek(7, zdaj = ZDAJ), "skupaj_predvajal"))
}

// Robne datoteke, ki jih Python sprejme; Kotlin mora dati enake bajte po enem zapisu.
private fun kotPython() {
    println("Robne veljavne datoteke (kot Python)")
    val b = "{\"predvajal\":%s,\"kratki\":0,\"neuspehi\":0,\"sekunde\":3600,\"casi\":[5]}"
    val primeri = listOf(
        // -0 je v Pythonu celo stevilo 0
        Triple("minus nic", "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1,\"dnevi\":{\"2026-10-19\":{\"webtor\":" + b.format("-0") + "}}}",
            "{\"dnevi\":{\"2026-10-19\":{\"webtor\":{\"casi\":[5],\"kratki\":0,\"neuspehi\":0,\"predvajal\":0,\"sekunde\":3600}},\"2026-10-20\":{\"webtor\":{\"casi\":[1],\"kratki\":0,\"neuspehi\":0,\"predvajal\":1,\"sekunde\":3600}}},\"verzija\":1,\"vrsta\":\"safeer-stevec-virov\"}"),
        // verzija 1.0 in true ostaneta, kot ju ohrani Python
        Triple("verzija 1.0", "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1.0,\"dnevi\":{}}",
            "{\"dnevi\":{\"2026-10-20\":{\"webtor\":{\"casi\":[1],\"kratki\":0,\"neuspehi\":0,\"predvajal\":1,\"sekunde\":3600}}},\"verzija\":1.0,\"vrsta\":\"safeer-stevec-virov\"}"),
        Triple("verzija true", "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":true,\"dnevi\":{}}",
            "{\"dnevi\":{\"2026-10-20\":{\"webtor\":{\"casi\":[1],\"kratki\":0,\"neuspehi\":0,\"predvajal\":1,\"sekunde\":3600}}},\"verzija\":true,\"vrsta\":\"safeer-stevec-virov\"}"),
        // neznani kljuci na vrhu in v vedru ostanejo (urejeni, ensure_ascii, repr decimalk)
        Triple("dodatni kljuci", "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1,\"opomba\":{\"\u017e\":[0.0001,1e-5,1E16,-0.0,NaN]},\"dnevi\":{\"2026-10-20\":{\"webtor\":{\"predvajal\":0,\"kratki\":0,\"neuspehi\":0,\"sekunde\":0,\"casi\":[],\"a\":\"\\ud800\"}}}}",
            "{\"dnevi\":{\"2026-10-20\":{\"webtor\":{\"a\":\"\\ud800\",\"casi\":[1],\"kratki\":0,\"neuspehi\":0,\"predvajal\":1,\"sekunde\":3600}}},\"opomba\":{\"\\u017e\":[0.0001,1e-05,1e+16,-0.0,NaN]},\"verzija\":1,\"vrsta\":\"safeer-stevec-virov\"}"),
        // podvojen kljuc: velja zadnji, kot v Pythonu
        Triple("podvojen kljuc", "{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":2,\"verzija\":1,\"dnevi\":{}}",
            "{\"dnevi\":{\"2026-10-20\":{\"webtor\":{\"casi\":[1],\"kratki\":0,\"neuspehi\":0,\"predvajal\":1,\"sekunde\":3600}}},\"verzija\":1,\"vrsta\":\"safeer-stevec-virov\"}"),
    )
    for ((ime, vhod, izhod) in primeri) {
        val m = zacasnaMapa()
        val p = File(m, "s.json")
        p.writeText(vhod)
        val s = StevecVirov(p)
        preveriEnako("$ime: ni pokvarjena", false, File(m, "s.json.pokvarjeno").exists())
        preveriEnako("$ime: zapis uspe", true, s.zabelezi("webtor", true, 1, 3600, zdaj = ZDAJ))
        preveriEnako("$ime: bajti kot Python", izhod, p.readText())
    }
    // Povzetek kot besedilo: 0.0 ostane 0.0 (prejsnja knjiznica je izpisala 0).
    val prazen = StevecVirov(File(zacasnaMapa(), "s.json")).povzetek(1, zdaj = ZDAJ)
    preveriEnako("vJson 0.0", true, StevecVirov.vJson(prazen).contains("\"delez\":0.0,"))
    preveriEnako("vJson decimalke", "[0.03,0.0001,1e-05,1e+16,1234.5,5e-324,1.7976931348623157e+308,0.1]",
        StevecVirov.vJson(listOf(0.03, 0.0001, 0.00001, 1e16, 1234.5, Double.MIN_VALUE, Double.MAX_VALUE, 0.1)))
}

// Strogi bralnik na sovraznem vhodu: vedno NEVELJAVNO ali vrednost, nikoli izjema.
private fun sovrazniVhod() {
    println("Bralnik JSON na sovraznem vhodu")
    val slabi = listOf(
        "[".repeat(1_000_000), "{\"a\":".repeat(100_000), "[" .repeat(65) + "]".repeat(65),
        "1" + "0".repeat(4300), "-", "01", "1.", ".5", "+1", "1e", "--1", "\"\\u12\"", "\"\\uZZZZ\"",
        "\"\u0001\"", "\"abc", "\uFEFF{}", "{} x", "{,}", "[1,]", "{\"a\" 1}", "nul", "tru", "\"\\x\"",
        "\u0663", "1\u0663", "{'a':1}", "[1 2]", "", " ",
    )
    var izjem = 0
    for (vhod in slabi) {
        try {
            if (StevecVirov.Json.beri(vhod) !== StevecVirov.Json.NEVELJAVNO) {
                napak++
                println("  NAPAKA  sprejet neveljaven vhod: ${vhod.take(20)}")
            }
        } catch (e: Throwable) {
            izjem++
        }
    }
    preveriEnako("${slabi.size} neveljavnih vhodov zavrnjenih brez izjeme", 0, izjem)
    preveriEnako("globina 64 se sprejme", true, StevecVirov.Json.beri("[".repeat(64) + "]".repeat(64)) is List<*>)
    preveriEnako("ogromna decimalka je Infinity", Double.POSITIVE_INFINITY, StevecVirov.Json.beri("1e999999"))
    preveriEnako("4300 stevk je celo stevilo", true, StevecVirov.Json.beri("9".repeat(4300)) is BigInteger)
    preveriEnako("osamljen nadomestni znak", "\uD800", StevecVirov.Json.beri("\"\\ud800\""))
    preveriEnako("prevelik vhod", true, StevecVirov.Json.beri(" ".repeat(StevecVirov.MAX_BRANJE) + "1") === StevecVirov.Json.NEVELJAVNO)
}

// Velika a veljavna datoteka: 40 dni s polnimi vedri, vec kot 64 KiB in manj kot 256 KiB.
// Kljuci namenoma niso urejeni (vrsta najprej), kot bi jih zapisal kdo drug.
private fun velikaDatoteka(): String {
    val kljuci = StevecVirov.VIRI + listOf("debrid_takoj+torbox", "debrid_cakanje+torbox")
    val casi = (0 until 50).joinToString(",", "[", "]") { (100000 + it).toString() }
    val sb = StringBuilder("{\"vrsta\":\"safeer-stevec-virov\",\"verzija\":1,\"dnevi\":{")
    var dan = LocalDate.of(2026, 9, 11)
    var prviDan = true
    while (!dan.isAfter(LocalDate.of(2026, 10, 20))) {
        if (!prviDan) sb.append(',')
        prviDan = false
        sb.append('"').append(dan).append("\":{")
        sb.append(kljuci.joinToString(",") {
            "\"$it\":{\"predvajal\":7,\"kratki\":1,\"neuspehi\":2,\"sekunde\":25200,\"casi\":$casi}"
        })
        sb.append('}')
        dan = dan.plusDays(1)
    }
    sb.append("}}")
    return sb.toString()
}

private fun meja64KiB() {
    println("Meja 64 KiB")
    val pot = File(zacasnaMapa(), "stevec.json")
    pot.writeText(velikaDatoteka())
    val vhod = pot.length()
    preveriEnako("vhod je med 64 in 256 KiB", true, vhod > 65536 && vhod <= 4L * 65536)
    val s = StevecVirov(pot)
    preveriEnako("velika veljavna ni pokvarjena", false, File(pot.path + ".pokvarjeno").exists())
    preveriEnako("zapis uspe", true, s.zabelezi("webtor", true, 1000, 3600, zdaj = ZDAJ))
    preveriEnako("datoteka <= 64 KiB", true, pot.length() <= 65536)
    val dnevi = polje(beriJson(pot.readText()), "dnevi") as Map<*, *>
    val ostali = dnevi.keys.map { it as String }.sorted()
    preveriEnako("danasnji dan ostane", true, "2026-10-20" in ostali)
    preveriEnako("najnovejsi so ohranjeni (strnjeno do danes)", LocalDate.of(2026, 10, 20).minusDays((ostali.size - 1).toLong()).toString(), ostali.first())
    preveriEnako("najstarejsi je odstranjen", false, "2026-09-11" in ostali)
    // Ena dan vec bi presegel mejo: obrezano je ravno dovolj.
    println("  ostalo dni: ${ostali.size}, velikost ${pot.length()} B")
}

fun main() {
    println("Preizkus stevca virov (StevecVirov)")
    skupniPrimeri()
    formatNaDisku()
    casVMs()
    pokvarjenaDatoteka()
    kotPython()
    sovrazniVhod()
    meja64KiB()
    niti()
    if (napak > 0) {
        println("NAPAK: $napak")
        exitProcess(1)
    }
    println("Vse v redu.")
}
