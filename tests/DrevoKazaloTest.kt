package si.safeer.tv

import com.safeer.threatfeed.SortedStringTable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Drevo grozenj iz datoteke (DomenskoKazalo, mmap, 4. 11. 2026) mora blokirati NATANKO isto kot drevo v kopici
 * (DomainSuffixTrie; ta je v DomenTest primerjan z referencno izvedbo). Primerjava na 300 000 domenah: vsaka domena,
 * poddomene, nadrejene pripone, nakljucni gostitelji, velike crke, pike - 0 razlik. Poleg tega: cas odprtja (mora biti ms),
 * napacen kljuc, prekinjen zapis in poskodovana datoteka.
 *
 *   tests/run_domene_tests.sh
 */
private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private val KATEGORIJE = listOf("Botnet C2 Server", "Zlonamerna koda (Malware)", "Spletno ribarjenje (Phishing)", null, "Lažne trgovine in prevare")
private val VIRI = listOf("abuse.ch URLhaus", "Phishing Army Extended", "HaGeZi TIF", null, "SI-CERT")

private fun nakljucnaDomena(r: java.util.Random): String {
    val crke = "abcdefghijklmnopqrstuvwxyz0123456789-"
    val deli = 2 + r.nextInt(3)
    return (0 until deli).joinToString(".") { d ->
        val n = if (d == deli - 1) 2 + r.nextInt(4) else 1 + r.nextInt(12)
        (0 until n).map { crke[r.nextInt(if (d == deli - 1) 26 else crke.length)] }.joinToString("")
    }
}

private fun enako(a: DomainSuffixTrie.MatchResult?, b: DomainSuffixTrie.MatchResult?) = a == b

private fun uporabljenaKopica(): Long {
    val rt = Runtime.getRuntime()
    repeat(3) { System.gc(); Thread.sleep(50) }
    return rt.totalMemory() - rt.freeMemory()
}

fun main() {
    println("Preizkus kazala drevesa grozenj (mmap)")
    val mapa = Files.createTempDirectory("safeer-kazalo").toFile()
    try {
        val r = java.util.Random(20261104)
        val domene = ArrayList<String>(300_000)
        while (domene.size < 300_000) domene.add(nakljucnaDomena(r))
        val kopicaPred = uporabljenaKopica()   // brez seznama nizov: meri samo drevo
        val drevo = DomainSuffixTrie()
        for ((n, d) in domene.withIndex()) {
            val i = (d.hashCode() and 0x7fffffff) % KATEGORIJE.size
            drevo.insert(d, KATEGORIJE[i], VIRI[(i + n) % VIRI.size])
        }
        // Dvojniki z drugo kategorijo (obvelja prvi vstavljeni) in nadrejene domene obstojecih (obvelja najkrajsa).
        for (k in 0 until 2_000) drevo.insert(domene[k * 7].uppercase() + ".", "Druga kategorija", "Drug vir")
        for (k in 0 until 2_000) drevo.insert(domene[k * 11].substringAfter('.'), "Nadrejena", "Vir nadrejene")
        drevo.pripravi()
        val kopicaDrevo = uporabljenaKopica() - kopicaPred
        val kljuc = SortedStringTable.keyOf(listOf("preizkus", "1"))
        val datoteka = File(mapa, "drevo-grozenj.bin")
        var t = System.nanoTime()
        drevo.zapisiKazalo(datoteka, kljuc)
        val zapisMs = (System.nanoTime() - t) / 1_000_000
        println("  drevo: ${drevo.size} domen, kopica ~${kopicaDrevo / 1024 / 1024} MB; zapis kazala $zapisMs ms, ${datoteka.length() / 1024} KiB")

        // Cas odprtja: hladno (prvo) in nato petkrat.
        t = System.nanoTime()
        val kazalo = DomenskoKazalo.odpri(datoteka, kljuc)
        val prvoMs = (System.nanoTime() - t) / 1e6
        preveri("kazalo se odpre", kazalo != null)
        kazalo!!
        val casi = (1..5).map { val z = System.nanoTime(); DomenskoKazalo.odpri(datoteka, kljuc); (System.nanoTime() - z) / 1e6 }
        println("  odprtje kazala: prvo ${"%.2f".format(prvoMs)} ms, nato ${casi.joinToString { "%.2f".format(it) }} ms")
        preveri("odprtje kazala pod 50 ms v JVM (TV ~5x pocasnejsi: < 300 ms)", prvoMs < 50 && casi.all { it < 50 })
        preveri("isto stevilo domen", kazalo.size == drevo.size)

        // Enakost: 0 razlik na vseh vrstah gostiteljev.
        val gostitelji = ArrayList<String>()
        for (d in domene) { gostitelji.add(d); gostitelji.add("www.$d"); gostitelji.add("a.b.$d"); gostitelji.add(d.substringAfter('.')) }
        for (k in 0 until 50_000) gostitelji.add(nakljucnaDomena(r))
        for (k in 0 until 2_000) { gostitelji.add(domene[k].uppercase()); gostitelji.add(" ${domene[k]}. "); gostitelji.add(".." + domene[k]) }
        gostitelji.addAll(listOf("", ".", "#komentar", "com", "x", "a".repeat(300), "localhost", "1.2.3.4"))
        var razlik = 0
        var ujemanj = 0
        for (g in gostitelji) {
            val a = drevo.findMatch(g)
            val b = kazalo.findMatch(g)
            if (a != null) ujemanj++
            if (!enako(a, b)) { if (razlik < 5) println("    razlika: '$g' drevo=$a kazalo=$b"); razlik++ }
        }
        preveri("drevo in kazalo: 0 razlik na ${gostitelji.size} gostiteljih ($ujemanj ujemanj)", razlik == 0)
        preveri("ujemanja so (preizkus ni prazen)", ujemanj > 300_000)

        // Hitrost iskanja
        t = System.nanoTime()
        var n = 0
        for (k in 0 until 200_000) if (kazalo.matches(gostitelji[k])) n++
        val kazaloNs = (System.nanoTime() - t) / 200_000
        t = System.nanoTime()
        for (k in 0 until 200_000) if (drevo.matches(gostitelji[k])) n++
        val drevoNs = (System.nanoTime() - t) / 200_000
        println("  iskanje: kazalo $kazaloNs ns, drevo $drevoNs ns na gostitelja")

        // Kopica: kazalo nosi samo glavo in besede.
        val zDrevesom = uporabljenaKopica()
        println("  kopica procesa ~${zDrevesom / 1024 / 1024} MB, od tega drevo ~${kopicaDrevo / 1024 / 1024} MB; kazalo v kopici samo glava in besede (${kazalo.size} domen v datoteki)")

        // Nesmiselni vhodi
        preveri("napacen kljuc: kazalo ni v uporabi", DomenskoKazalo.odpri(datoteka, SortedStringTable.keyOf(listOf("drug"))) == null)
        val prekinjena = File(mapa, "prekinjena.bin")
        datoteka.copyTo(prekinjena)
        RandomAccessFile(prekinjena, "rw").use { it.setLength(it.length() - 4) }
        preveri("prekinjen zapis (krajsa datoteka) ni v uporabi", DomenskoKazalo.odpri(prekinjena, kljuc) == null)
        preveri("neobstojeca datoteka", DomenskoKazalo.odpri(File(mapa, "ni.bin"), kljuc) == null)
        preveri("cela datoteka: CRC v redu", kazalo.neposkodovano())
        val poskodovana = File(mapa, "poskodovana.bin")
        datoteka.copyTo(poskodovana)
        RandomAccessFile(poskodovana, "rw").use { f ->
            // Pokvari odmike in del bazena: iskanje ne sme vreci izjeme, CRC mora pasti.
            for (p in listOf(200L, 4_000L, f.length() / 2, f.length() - 10)) { f.seek(p); f.write(0x7f) }
        }
        val pk = DomenskoKazalo.odpri(poskodovana, kljuc)
        preveri("poskodovana datoteka: glava je cela, odpre se", pk != null)
        var izjem = 0
        for (k in 0 until 20_000) try { pk!!.findMatch(gostitelji[k]) } catch (e: Exception) { izjem++ }
        preveri("poskodovana datoteka: iskanje brez izjem ($izjem)", izjem == 0)
        preveri("poskodovana datoteka: CRC pade (zgradi se znova)", pk?.neposkodovano() == false)

        // Znaki nad 0xFF (UTF-16 v datoteki) - redko, a ujemanje mora ostati enako.
        val siroko = DomainSuffixTrie()
        val posebne = listOf("bücher.example", "пример.рф", "例え.テスト", "abĀ.example", "zz.example", "a.zz.example")
        for ((i, d) in posebne.withIndex()) siroko.insert(d, "k$i", "v$i")
        val sirokaDatoteka = File(mapa, "siroko.bin")
        siroko.zapisiKazalo(sirokaDatoteka, kljuc)
        val sk = DomenskoKazalo.odpri(sirokaDatoteka, kljuc)!!
        val sondi = posebne + posebne.map { "x.$it" } + listOf("рф", "テスト", "example", "y.zz.example")
        preveri("znaki nad 0xFF: 0 razlik", sondi.all { enako(siroko.findMatch(it), sk.findMatch(it)) } && sk.matches("x.пример.рф"))

        // Prazen seznam
        val prazno = DomainSuffixTrie()
        val praznaDatoteka = File(mapa, "prazno.bin")
        prazno.zapisiKazalo(praznaDatoteka, kljuc)
        val pkz = DomenskoKazalo.odpri(praznaDatoteka, kljuc)
        preveri("prazno kazalo: nic ne ujame", pkz != null && pkz.findMatch("evil.example") == null)
        println("  ($n)")
    } finally {
        mapa.deleteRecursively()
    }
    println()
    if (napak == 0) { println("DrevoKazalo: OK"); exitProcess(0) } else { println("Napak: $napak"); exitProcess(1) }
}
