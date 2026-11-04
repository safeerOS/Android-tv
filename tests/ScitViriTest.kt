// Proces :scit gradi samo nabor Scita (4. 11. 2026): kateri seznami gredo v nabor, svoja datoteka stanja agenta in
// staticna preverba, da ScitStoritev ne zazene celotnega agenta brskalnika (EasyList, drevo grozenj, kazalo).
//   tests/run_scit_tests.sh (argument: pot do src/main/kotlin)
package si.safeer.tv.scit

import com.safeer.threatfeed.PlainListSource
import com.safeer.threatfeed.ThreatListAgent
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

private var padloVir = 0

private fun preveriVir(pogoj: Boolean, opis: String) {
    if (pogoj) println("OK   $opis") else { padloVir++; println("PADE $opis") }
}

fun main(args: Array<String>) {
    val src = File(args.getOrNull(0) ?: error("pot do src/main/kotlin"))

    // Lastnosti kot v ThreatFeedsUpdater.SOURCES.
    val feodo = PlainListSource("feodo", "Feodo", "", "", "feodo", minEntries = 0, ipv4 = true)
    val urlhaus = PlainListSource("urlhaus", "URLhaus", "", "", "urlhaus")
    val pro = PlainListSource("hagezi-pro", "PRO", "", "", "hagezi", minEntries = 10000, dnsOnly = true)
    val easylist = PlainListSource("easylist", "EasyList", "", "", "easylist", minEntries = 1000, raw = true)
    val sicert = PlainListSource("si-cert", "SI-CERT", "", "", "", headerless = true)
    val vsi = listOf(feodo, urlhaus, pro, easylist, sicert)

    preveriVir(ScitViri.viri(vsi).map { it.id } == listOf("urlhaus", "hagezi-pro", "si-cert"), "viri Scita: brez EasyList in IPv4, vrstni red ostane")
    preveriVir(!ScitViri.zaNabor(easylist) && !ScitViri.zaNabor(feodo), "EasyList in Feodo nista v naboru")
    preveriVir(ScitViri.zaNabor(pro) && !ScitViri.zaGrozne(pro), "HaGeZi PRO v domene.bin, ne v grozne.bin")
    preveriVir(ScitViri.zaGrozne(urlhaus) && ScitViri.zaGrozne(sicert), "seznami grozenj v grozne.bin")
    // TV 4. 11. 2026: glavni proces brskalnika je ob osvezitvi bral HaGeZi PRO (247 757 domen, GC 98/122 MB), ki ga ne rabi.
    preveriVir(vsi.filter(ScitViri::zaBrskalnik).map { it.id } == listOf("feodo", "urlhaus", "easylist", "si-cert"),
        "brskalnik ne bere seznamov samo za DNS (HaGeZi PRO)")

    // Svoja datoteka stanja: preverba Scita ne zadrzi preverbe brskalnika (omejitev 15 min v refresh()).
    val mapa = Files.createTempDirectory("scit-viri").toFile()
    try {
        File(mapa, "agent.state").writeText("lastCheck=123\n")
        val brskalnik = ThreatListAgent(mapa, vsi) { }
        val scit = ThreatListAgent(mapa, ScitViri.viri(vsi), stateName = "agent-scit.state") { }
        preveriVir(brskalnik.lastCheck() == 123L && scit.lastCheck() == 0L, "agent Scita bere svojo datoteko stanja")
    } finally { mapa.deleteRecursively() }

    // Staticno: ScitStoritev zazene samo agenta nabora, ta pa ne odpre nicesar iz brskalnika.
    val storitev = File(src, "si/safeer/tv/scit/ScitStoritev.kt").readText()
    preveriVir(!storitev.contains("ThreatFeedsUpdater.start(") && storitev.contains("ThreatFeedsUpdater.startSamoScit("),
        "ScitStoritev zazene startSamoScit, ne celotnega agenta")
    for (ime in listOf("ThreatBlockEngine", "AdBlockEngine", "ZascitaZagona", "SignedThreatIntel")) {
        preveriVir(!Regex("\\b$ime\\b").containsMatchIn(storitev), "ScitStoritev ne uporablja $ime")
    }
    val updater = File(src, "si/safeer/tv/ThreatFeedsUpdater.kt").readText()
    val zac = updater.indexOf("fun startSamoScit(")
    val telo = if (zac < 0) "" else updater.substring(zac).let { t -> t.substring(0, Regex("\n    (/\\*\\*|fun |@)").find(t, 1)?.range?.first ?: t.length) }
    preveriVir(telo.contains("ScitViri.viri(SOURCES)") && telo.contains("stateName = \"agent-scit.state\"") && telo.contains("Scit.osveziNabor("),
        "startSamoScit: viri Scita, svoja datoteka stanja, gradi nabor")
    for (ime in listOf("ThreatBlockEngine", "AdBlockEngine", "ZascitaZagona", "SignedThreatIntel", "kazalo", "installFilterLists", "start(context")) {
        preveriVir(telo.isNotEmpty() && !telo.contains(ime), "startSamoScit ne uporablja $ime")
    }

    // Agent brskalnika (start) ne bere PRO in ne gradi nabora Scita; nabor gradi samo :scit, ob vsakem zagonu Scita.
    val zs = updater.indexOf("fun start(")
    val start = if (zs < 0) "" else updater.substring(zs, updater.indexOf("fun startSamoScit(").takeIf { it > zs } ?: updater.length)
    preveriVir(start.contains("ThreatListAgent(File(app.filesDir, \"threat-lists\"), BRSKALNIK_VIRI") &&
        Regex("BRSKALNIK_VIRI\\s*=\\s*SOURCES\\.filter\\(si\\.safeer\\.tv\\.scit\\.ScitViri::zaBrskalnik\\)").containsMatchIn(updater),
        "agent brskalnika ima vire brez seznamov samo za DNS")
    preveriVir(start.isNotEmpty() && !start.contains("Scit.osveziNabor(") && !start.contains("naborVeljaven"),
        "brskalnik ne gradi nabora Scita")
    val klic = storitev.indexOf("ThreatFeedsUpdater.startSamoScit(")
    val pred = if (klic < 0) "" else storitev.substring(storitev.lastIndexOf("if (", klic), klic)
    preveriVir(pred.startsWith("if (!zahtevaniSeznami)"), "ScitStoritev zazene agenta nabora ob vsakem zagonu (ne samo brez grozne.bin)")

    if (padloVir > 0) { println("PADLO: $padloVir"); exitProcess(1) }
    println("ScitViriTest: vse OK")
}
