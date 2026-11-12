// Predprevedeni nabor EasyList na disku (12. 11. 2026): zapis + branje mora dati NATANKO isti nabor kot prevod (stevila pravil,
// kozmeticni selektorji, odlocitve), pokvarjena/napacna datoteka mora dati null (prevod iz seznama kot prej).
// Brez poti tece na sinteticnem seznamu; s polnim EasyList (SAFEER_EASYLIST v run_filter_predpomnilnik_tests.sh) tudi na njem in z
// izmerjenim casom prevoda proti branju.
//   java -cp ... com.safeer.threatfeed.FilterPredpomnilnikTestKt [pot/do/easylist.txt]
package com.safeer.threatfeed

import java.io.File
import kotlin.system.exitProcess

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun sinteticni(): Pair<List<String>, List<String>> {
    val l = ArrayList<String>()
    val sonde = ArrayList<String>()
    l.add("[Adblock Plus 2.0]"); l.add("! naslov"); l.add("")
    for (i in 0 until 3000) {
        l.add("||oglas$i.example^")
        l.add("/banner$i.")
        l.add("||sled$i.example^\$third-party,script")
        if (i % 7 == 0) l.add("@@||oglas$i.example^\$script")
        l.add("dom$i.si##.reklama$i")
        l.add("dom$i.si,drugi$i.si##.skupno")
        if (i % 11 == 0) l.add("dom$i.si#@#.skupno")
        if (i % 13 == 0) l.add("~dom$i.si##.negirano$i")
        if (i % 17 == 0) l.add("dom$i.si#?#.razsirjeno:has(a)")
        if (i % 23 == 0) l.add("##.splosno$i")
        if (i % 29 == 0) l.add("/regex$i[0-9]+/")
        if (i % 31 == 0) l.add("||oglas$i.example^\$redirect=nic")
        if (i % 41 == 0) l.add("@@||dom$i.si^\$domain=stran.example|~drugo.example,image,important")
        if (i % 43 == 0) l.add("|https://tocno$i.example/pot*.js^|")
        if (i % 47 == 0) { l.add("||vazno$i.example^\$important"); l.add("@@||vazno$i.example^"); sonde.add("https://vazno$i.example/x.js") }
        sonde.add("https://oglas$i.example/x.js"); sonde.add("https://cdn.example/banner$i.png"); sonde.add("https://sled$i.example/a.js")
    }
    return l to sonde
}

private fun primerjaj(ime: String, lines: List<String>, sonde: List<String>, gostitelji: List<String>, mapa: File) {
    val a = FilterListEngine.compile(lines)
    val d = File(mapa, "predprevedeno-$ime.bin")
    val kljuc = FilterSetPredpomnilnik.kljuc(listOf("v1", ime))
    preveri("$ime: zapis uspe", FilterSetPredpomnilnik.zapisi(a, kljuc, d) && d.length() > 0)
    println("  velikost datoteke $ime: ${d.length() / 1024} KiB")
    preveri("$ime: glava velja za pravi kljuc, ne za drug kljuc", FilterSetPredpomnilnik.glavaVelja(d, kljuc) && !FilterSetPredpomnilnik.glavaVelja(d, kljuc + "x"))
    val b = FilterSetPredpomnilnik.preberi(d, kljuc)
    preveri("$ime: branje vrne nabor", b != null)
    if (b == null) return
    preveri("$ime: stevilo omreznih pravil in preskocenih", a.size == b.size && a.skipped == b.skipped && a.size > 0)
    preveri("$ime: stevilo kozmeticnih pravil", a.cosmetic.size == b.cosmetic.size && a.cosmetic.size > 0)
    var razlik = 0
    for (h in gostitelji) if (a.cosmetic.selectorsFor(h) != b.cosmetic.selectorsFor(h)) razlik++
    preveri("$ime: kozmeticni selektorji za ${gostitelji.size} gostiteljev (razlik $razlik)", razlik == 0)
    var odl = 0; var blok = 0
    for (u in sonde) for (tip in listOf(ResourceType.SCRIPT, ResourceType.IMAGE, ResourceType.XHR)) {
        for (stran in listOf("https://stran.example/", "https://dom1.si/", null)) {
            val ra = a.decide(FilterRequest(u, stran, tip)); val rb = b.decide(FilterRequest(u, stran, tip))
            if (ra?.block != rb?.block || ra?.filter?.text != rb?.filter?.text) odl++
            if (ra?.block == true) blok++
        }
    }
    preveri("$ime: odlocitve za ${sonde.size * 9} zahtevkov (razlik $odl, blokiranih $blok)", odl == 0 && blok > 0)
    // Pokvarjena datoteka: sprememba enega bajta, okrnjena datoteka, napacen kljuc, ni datoteke.
    val bajti = d.readBytes()
    // Sprememba katerega koli bajta (20 mest po datoteki) mora dati null: CRC ujame tudi spremembe, ki bi sicer dale veljavne nize.
    val fk = File(mapa, "kvar1.bin")
    var ujetih = 0
    for (n in 1..20) {
        val k1 = bajti.copyOf(); val poz = (bajti.size.toLong() * n / 21).toInt()
        k1[poz] = (k1[poz].toInt() xor 0x5a).toByte(); fk.writeBytes(k1)
        if (FilterSetPredpomnilnik.preberi(fk, kljuc) == null) ujetih++
    }
    preveri("$ime: sprememba bajta na 20 mestih -> vedno null, CRC (ujetih $ujetih od 20)", ujetih == 20)
    fk.writeBytes(bajti.copyOf(bajti.size - 7))
    preveri("$ime: okrnjena datoteka -> null", FilterSetPredpomnilnik.preberi(fk, kljuc) == null)
    fk.writeBytes(ByteArray(3))
    preveri("$ime: prekratka datoteka -> null", FilterSetPredpomnilnik.preberi(fk, kljuc) == null)
    preveri("$ime: napacen kljuc -> null", FilterSetPredpomnilnik.preberi(d, kljuc + "x") == null)
    preveri("$ime: ni datoteke -> null", FilterSetPredpomnilnik.preberi(File(mapa, "ni-je.bin"), kljuc) == null && !FilterSetPredpomnilnik.glavaVelja(File(mapa, "ni-je.bin"), kljuc))
    // N1: okrnjena datoteka z VELJAVNO glavo (konec odrezan, glava cela) ne velja in se z osvezi() popravi.
    fk.writeBytes(bajti.copyOf(bajti.size - 7))
    preveri("$ime: okrnjena datoteka z veljavno glavo -> glavaVelja false (N1)", !FilterSetPredpomnilnik.glavaVelja(fk, kljuc))
    // Pokvarjeno telo z veljavno glavo in dolzino: glava velja, branje ne; osvezi(branjeNeuspelo=true) jo zamenja z veljavno.
    val k2 = bajti.copyOf(); k2[k2.size - 9] = (k2[k2.size - 9].toInt() xor 0x33).toByte(); fk.writeBytes(k2)
    preveri("$ime: pokvarjeno telo: glava velja, branje null", FilterSetPredpomnilnik.glavaVelja(fk, kljuc) && FilterSetPredpomnilnik.preberi(fk, kljuc) == null)
    preveri("$ime: osvezi po neuspelem branju popravi datoteko (N1)",
        FilterSetPredpomnilnik.osvezi(a, kljuc, fk, true) && FilterSetPredpomnilnik.preberi(fk, kljuc)?.size == a.size)
    preveri("$ime: osvezi ne prepise ze veljavne datoteke",
        fk.lastModified().let { m -> FilterSetPredpomnilnik.osvezi(a, kljuc, fk, false) && fk.lastModified() == m })
    preveri("$ime: po zapisu ni zacasne datoteke", !File(mapa, "kvar1.bin.tmp").exists())
    // N5: odtis razclenjevalnika je v kljucu; drug odtis -> drug kljuc, stara datoteka ne velja.
    FilterSetPredpomnilnik.odtisPrepis = "drug-razclenjevalnik"
    val kNov = FilterSetPredpomnilnik.kljuc(listOf("v1", ime))
    FilterSetPredpomnilnik.odtisPrepis = null
    preveri("$ime: spremenjen odtis razclenjevalnika -> drug kljuc in stara datoteka ne velja (N5)", kNov != kljuc && !FilterSetPredpomnilnik.glavaVelja(d, kNov) && FilterSetPredpomnilnik.preberi(d, kNov) == null)
    fk.delete()
}

private fun prazen() {
    val mapa = java.nio.file.Files.createTempDirectory("filter-prazen").toFile()
    try {
        val d = File(mapa, "prazen.bin")
        // N2: prazen nabor se nikoli ne zapise (sicer bi poskodovan seznam z veljavnim kljucem izklopil EasyList).
        preveri("zapisi(FilterSet.EMPTY) vrne false in ne ustvari datoteke (N2)", !FilterSetPredpomnilnik.zapisi(FilterSet.EMPTY, "k", d) && !d.exists() && !File(mapa, "prazen.bin.tmp").exists())
        preveri("osvezi(FilterSet.EMPTY) ne ustvari datoteke (N2)", !FilterSetPredpomnilnik.osvezi(FilterSet.EMPTY, "k", d, false) && !d.exists())
        val src = PlainListSource("easylist", "EasyList", "https://x.example/e.txt", "ads", "[Adblock", raw = true)
        val src2 = PlainListSource("drug", "Drug", "https://x.example/d.txt", "ads", "[Adblock", raw = true)
        val ok = PlainList(src, listOf("||a.example^"), 1L, "aaa")
        val info = PlainListInfo(src, 1, "aaa", 1L)
        preveri("kljucZaZapis iz prevedenih seznamov v skladu z .meta (N2)", FilterSetPredpomnilnik.kljucZaZapis("1.0", listOf(ok), listOf(info)) != null)
        preveri("kljucZaZapis: prazen vnos -> null (N2)", FilterSetPredpomnilnik.kljucZaZapis("1.0", emptyList(), listOf(info)) == null)
        preveri("kljucZaZapis: drug SHA prevedenega seznama kot v .meta -> null (N2)", FilterSetPredpomnilnik.kljucZaZapis("1.0", listOf(PlainList(src, listOf("||a.example^"), 1L, "bbb")), listOf(info)) == null)
        preveri("kljucZaZapis: manjkajoc SHA -> null (N2)", FilterSetPredpomnilnik.kljucZaZapis("1.0", listOf(PlainList(src, listOf("x"), 1L, "")), listOf(info)) == null)
        preveri("kljucZaZapis: seznam v .meta, ki ni bil preveden -> null (N2)", FilterSetPredpomnilnik.kljucZaZapis("1.0", listOf(ok), listOf(info, PlainListInfo(src2, 1, "ccc", 1L))) == null)
    } finally { mapa.deleteRecursively() }
}

fun main(args: Array<String>) {
    val mapa = java.nio.file.Files.createTempDirectory("filter-predp").toFile()
    try {
        prazen()
        val (lines, sonde) = sinteticni()
        primerjaj("sinteticni", lines, sonde, (0 until 3000).flatMap { listOf("dom$it.si", "drugi$it.si", "a.dom$it.si") }, mapa)
        val pot = args.firstOrNull()
        if (pot != null) {
            val vse = File(pot).readLines()
            val gost = vse.asSequence().filter { it.contains("##") && !it.startsWith("##") && !it.startsWith("!") }
                .map { it.substringBefore("##").split(',').first().removePrefix("~") }.filter { it.isNotEmpty() }.distinct().take(4000).toList()
            val sonde2 = vse.asSequence().filter { it.startsWith("||") && !it.contains('$') }.map { it.removePrefix("||").substringBefore('^').substringBefore('/') }
                .filter { it.isNotEmpty() && !it.contains('*') }.distinct().take(8000).map { "https://$it/a.js" }.toList()
            primerjaj("EasyList", vse, sonde2, gost, mapa)
            val kljuc = FilterSetPredpomnilnik.kljuc(listOf("v1", "EasyList"))
            val d = File(mapa, "predprevedeno-EasyList.bin")
            val p = ArrayList<Long>(); val c = ArrayList<Long>()
            for (i in 0 until 5) {
                var t = System.nanoTime(); FilterListEngine.compile(vse); p.add((System.nanoTime() - t) / 1_000_000)
                t = System.nanoTime(); FilterSetPredpomnilnik.preberi(d, kljuc); c.add((System.nanoTime() - t) / 1_000_000)
            }
            println("  IZMENICNO EasyList ${vse.size} vrstic (JVM, ni TV): prevod ms=$p branje predpomnilnika ms=$c")
        }
        // Povezava v aplikacijo (iskanje nizov v izvoru; tek v aplikaciji je mogoc samo na napravi).
        val src = File(System.getProperty("predp.src") ?: "src/main/kotlin/si/safeer/tv")
        val tfu = File(src, "ThreatFeedsUpdater.kt").readText(); val abe = File(src, "AdBlockEngine.kt").readText()
        preveri("ThreatFeedsUpdater bere predpomnilnik ob zagonu in ne bere seznama", tfu.contains("FilterSetPredpomnilnik.preberi(") &&
            tfu.contains("emptySet()") && tfu.contains("FilterSetPredpomnilnik.glavaVelja("))
        preveri("ThreatFeedsUpdater ob neveljavnem predpomnilniku prebere sezname kot prej", tfu.contains("agent?.loadSaved("))
        preveri("ThreatFeedsUpdater zapise predpomnilnik po prevodu", tfu.contains("zapisiPredpomnilnik(app, vsebina, branjeNeuspelo)") && tfu.contains("FilterSetPredpomnilnik.osvezi(") &&
            tfu.contains("FilterSetPredpomnilnik.kljucZaZapis("))
        // Zapis po preverbi kazala (N3) in kljuc ne iz .meta (N2).
        preveri("zapis predpomnilnika je po preveriKazalo (N3)", tfu.indexOf("ThreatBlockEngine.preveriKazalo()") in 0 until tfu.indexOf("zapisiPredpomnilnik(app, vsebina, branjeNeuspelo)"))
        preveri("zapis ne jemlje kljuca iz .meta (N2)", !tfu.contains("kljucPredpomnilnika("))
        preveri("AdBlockEngine namesti predprevedeni nabor", abe.contains("fun installPrecompiled(") && abe.contains("fun trenutniNabor("))
    } finally { mapa.deleteRecursively() }
    if (napak > 0) { println("FilterPredpomnilnikTest: $napak napak"); exitProcess(1) }
    println("FilterPredpomnilnikTest: OK")
}
