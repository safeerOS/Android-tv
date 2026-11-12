// Dvonitni prevod seznama pravil (12. 11. 2026) mora dati NATANKO isti nabor kot enonitni: stevila pravil, kozmeticni selektorji
// po gostiteljih in odlocitve za naslove. Brez poti tece na sinteticnem seznamu (> PARALELNO_OD vrstic); s polnim EasyList
// (SAFEER_EASYLIST v run_filter_prevod_tests.sh) tudi na njem in z izmerjenim casom obeh prevodov.
//   java -cp ... com.safeer.threatfeed.FilterPrevodTestKt [pot/do/easylist.txt]
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
        if (i % 19 == 0) l.add("dom$i.si##.x:has-text(oglas)")
        if (i % 23 == 0) l.add("##.splosno$i")
        if (i % 29 == 0) l.add("/regex$i[0-9]+/")
        if (i % 31 == 0) l.add("||oglas$i.example^\$redirect=nic")
        if (i % 37 == 0) l.add("  dom$i.si##.reklama$i  ")
        sonde.add("https://oglas$i.example/x.js"); sonde.add("https://cdn.example/banner$i.png"); sonde.add("https://sled$i.example/a.js")
    }
    return l to sonde
}

private fun primerjaj(ime: String, lines: List<String>, sonde: List<String>, gostitelji: List<String>) {
    FilterListEngine.vsiljenoEnonitno = true
    val t0 = System.nanoTime()
    val a = FilterListEngine.compile(lines)
    val msA = (System.nanoTime() - t0) / 1_000_000
    val nitiA = FilterListEngine.zadnjiPrevodNiti
    FilterListEngine.vsiljenoEnonitno = false
    val t1 = System.nanoTime()
    val b = FilterListEngine.compile(lines)
    val msB = (System.nanoTime() - t1) / 1_000_000
    val nitiB = FilterListEngine.zadnjiPrevodNiti
    println("  izmerjeno ($ime): enonitni $msA ms, dvonitni $msB ms (${lines.size} vrstic; en tek, brez ogrevanja - ni primerjava)")
    preveri("$ime: enonitni prevod uporabi 1 nit, dvonitni 2", nitiA == 1 && nitiB == 2)
    preveri("$ime: stevilo omreznih pravil", a.size == b.size && a.size > 0)
    preveri("$ime: preskocena pravila", a.skipped == b.skipped)
    preveri("$ime: stevilo kozmeticnih pravil", a.cosmetic.size == b.cosmetic.size && a.cosmetic.size > 0)
    var razlik = 0
    for (h in gostitelji) if (a.cosmetic.selectorsFor(h) != b.cosmetic.selectorsFor(h)) razlik++
    preveri("$ime: kozmeticni selektorji za ${gostitelji.size} gostiteljev (razlik $razlik)", razlik == 0)
    var odl = 0
    var blokiranih = 0
    for (u in sonde) {
        for (tip in listOf(ResourceType.SCRIPT, ResourceType.IMAGE)) {
            val ra = a.decide(FilterRequest(u, "https://stran.example/", tip))
            val rb = b.decide(FilterRequest(u, "https://stran.example/", tip))
            if (ra?.block != rb?.block || ra?.filter?.text != rb?.filter?.text) odl++
            if (ra?.block == true) blokiranih++
        }
    }
    preveri("$ime: odlocitve za ${sonde.size * 2} zahtevkov (razlik $odl, blokiranih $blokiranih)", odl == 0 && blokiranih > 0)
}

fun main(args: Array<String>) {
    val (lines, sonde) = sinteticni()
    primerjaj("sinteticni", lines, sonde, (0 until 3000).flatMap { listOf("dom$it.si", "drugi$it.si", "a.dom$it.si") })
    // Majhen seznam ostane enonitni.
    FilterListEngine.vsiljenoEnonitno = false
    FilterListEngine.compile(lines.take(100))
    preveri("kratek seznam ostane enonitni", FilterListEngine.zadnjiPrevodNiti == 1)
    // Napaka v prevodu ne ostane tiha (izjema gre klicatelju): prazen seznam je veljaven.
    preveri("prazen seznam je prazen nabor", FilterListEngine.compile(emptyList()).size == 0)

    val pot = args.firstOrNull()
    if (pot != null) {
        val vse = File(pot).readLines()
        val gost = vse.asSequence().filter { it.contains("##") && !it.startsWith("##") && !it.startsWith("!") }
            .map { it.substringBefore("##").split(',').first().removePrefix("~") }.filter { it.isNotEmpty() }.distinct().take(4000).toList()
        val sonde2 = vse.asSequence().filter { it.startsWith("||") && !it.contains('$') }.map { it.removePrefix("||").substringBefore('^').substringBefore('/') }
            .filter { it.isNotEmpty() && !it.contains('*') }.distinct().take(8000).map { "https://$it/a.js" }.toList()
        primerjaj("EasyList", vse, sonde2, gost)
        // Ogrejti JIT in izmenicno 5x (isti proces; vsaj priblizno primerljivo).
        val a = ArrayList<Long>(); val b = ArrayList<Long>()
        for (i in 0 until 5) {
            FilterListEngine.vsiljenoEnonitno = true; var t = System.nanoTime(); FilterListEngine.compile(vse); a.add((System.nanoTime() - t) / 1_000_000)
            FilterListEngine.vsiljenoEnonitno = false; t = System.nanoTime(); FilterListEngine.compile(vse); b.add((System.nanoTime() - t) / 1_000_000)
        }
        println("  IZMENICNO EasyList ${vse.size} vrstic: enonitni ms=$a dvonitni ms=$b")
    }
    if (napak > 0) { println("FilterPrevodTest: $napak napak"); exitProcess(1) }
    println("FilterPrevodTest: OK")
}
