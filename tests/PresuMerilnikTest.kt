package si.safeer.tv

import java.io.File

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    val izpisi = ArrayList<String>()
    var ns = 0L
    var ms = 1_000L
    PresuMerilnik.uraNs = { ns }
    PresuMerilnik.uraMs = { ms }
    PresuMerilnik.izpis = { izpisi.add(it) }

    // Privzeto IZKLOPLJEN (pregled 12. 11. 2026, A): vroca pot je ena preverba, nobene ure in zapisa; izid klica nespremenjen.
    preveri("privzeto izklopljen", !PresuMerilnik.vklopljen)
    var uraKlicev = 0
    PresuMerilnik.uraNs = { uraKlicev++; ns }
    val izklopIzid = PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { "y" }
    preveri("izklopljen: izid nespremenjen, ura se ne bere", izklopIzid == "y" && uraKlicev == 0)
    ms += PresuMerilnik.OBDOBJE_MS * 2
    PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { }
    preveri("izklopljen: ni izpisa", izpisi.isEmpty())
    PresuMerilnik.novaStran()
    preveri("izklopljen: nova stran ne izpise", izpisi.isEmpty())
    PresuMerilnik.uraNs = { ns }
    PresuMerilnik.vklopljen = true
    ms = 1_000L

    // B: promet servisnega delavca ne zacne ure strani in ne sprozi izpisa.
    PresuMerilnik.izmeri(PresuMerilnik.Kat.SW) { ns += 1_000_000 }
    ms += PresuMerilnik.OBDOBJE_MS * 2
    PresuMerilnik.izmeri(PresuMerilnik.Kat.SW) { ns += 1_000_000 }
    preveri("SW ne sprozi izpisa in ne zacne ure", izpisi.isEmpty())
    PresuMerilnik.novaStran()
    preveri("SW sam ne sproci izpisa ob novi strani", izpisi.isEmpty())
    ms = 1_000L

    // Izid klica je nespremenjen, cas se zapise; izjema gre naprej, cas se zapise vseeno.
    val izid = PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { ns += 2_000_000; "x" }
    preveri("izid klica nespremenjen", izid == "x")
    try { PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { ns += 5_000_000; throw IllegalStateException("t") } } catch (_: IllegalStateException) { }
    PresuMerilnik.izmeri(PresuMerilnik.Kat.SCIT) { ns += 1_000_000 }
    PresuMerilnik.izmeri(PresuMerilnik.Kat.CAKANJE) { ns += 3_000_000 }
    preveri("pred obdobjem ni izpisa", izpisi.isEmpty())
    ms += PresuMerilnik.OBDOBJE_MS
    PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { ns += 1_000_000 }
    preveri("po obdobju natanko en izpis", izpisi.size == 1)
    val v = izpisi.firstOrNull() ?: ""
    preveri("vse: 3 klici, skupaj 8.0 ms, najvec 5.0 ms", v.contains("vse n=3 skupaj=8.0ms najvec=5.0ms"))
    preveri("scit: 1 klic 1.0 ms", v.contains("scit n=1 skupaj=1.0ms najvec=1.0ms"))
    preveri("cakanje: 1 klic 3.0 ms", v.contains("cakanje n=1 skupaj=3.0ms najvec=3.0ms"))
    preveri("po nitih", v.contains("niti: ${Thread.currentThread().name.take(40)} n=3"))
    PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { ns += 1_000_000 }
    ms += PresuMerilnik.OBDOBJE_MS * 3
    PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { }
    preveri("v istem obdobju se ne ponovi", izpisi.size == 1)

    // Nova stran: porocana stran se ne izpise znova, stevci se ponastavijo; kratka stran se izpise ob novi strani.
    PresuMerilnik.novaStran()
    preveri("nova stran po porocani ne izpise znova", izpisi.size == 1)
    PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { ns += 4_000_000 }
    PresuMerilnik.novaStran()
    preveri("kratka stran se izpise ob novi strani", izpisi.size == 2 && izpisi[1].contains("vse n=1 skupaj=4.0ms"))
    preveri("izpis nima naslova (samo stevila in imena niti)", izpisi.none { it.contains("http") || it.contains("://") })

    // Vec niti: vsota klicev natancna (brez izgubljenih zapisov), nic ne vrze.
    PresuMerilnik.novaStran(); izpisi.clear()
    PresuMerilnik.uraNs = { System.nanoTime() }
    val nit = (1..8).map { Thread { repeat(20_000) { PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE) { } } } }
    nit.forEach { it.start() }; nit.forEach { it.join() }
    PresuMerilnik.novaStran()
    preveri("8 niti x 20000 klicev: vsota n natancna", izpisi.any { it.contains("vse n=160000 ") })
    PresuMerilnik.vklopljen = false

    // Povezano v klice (brez tega merilnika ni): sirom src ali -Dpresu.src.
    val src = File(System.getProperty("presu.src") ?: "src/main/kotlin/si/safeer/tv")
    fun vir(ime: String) = File(src, ime).readText()
    val cev = vir("ChromiumEngineView.kt"); val tbe = vir("ThreatBlockEngine.kt"); val ma = vir("MainActivity.kt")
    preveri("ChromiumEngineView meri shouldInterceptRequest", cev.contains("PresuMerilnik.izmeri(PresuMerilnik.Kat.VSE)"))
    preveri("ChromiumEngineView meri filter oglasov", cev.contains("PresuMerilnik.izmeri(PresuMerilnik.Kat.ADBLOCK)"))
    preveri("ChromiumEngineView zacne novo stran in nastavi izpis", cev.contains("PresuMerilnik.novaStran()") && cev.contains("PresuMerilnik.izpis ="))
    preveri("ThreatBlockEngine meri Scit in cakanje", tbe.contains("PresuMerilnik.Kat.SCIT") && tbe.contains("PresuMerilnik.Kat.CAKANJE"))
    preveri("MainActivity meri service worker", ma.contains("PresuMerilnik.Kat.SW"))
    if (napak > 0) { println("PresuMerilnikTest: $napak napak"); System.exit(1) }
    println("PresuMerilnikTest: OK")
}
