package si.safeer.tv.os

fun main() {
    val z = ZvokPravila
    // Imena zapisov.
    check(z.imeZapisa("audio/eac3-joc") == "Dolby Atmos")
    check(z.imeZapisa("audio/eac3") == "Dolby Digital Plus")
    check(z.imeZapisa("audio/eac3", "DDP 5.1 Atmos") == "Dolby Atmos") { "Matroska: Atmos pove le ime sledi" }
    check(z.imeZapisa("audio/true-hd", "TrueHD Atmos 7.1") == "Dolby TrueHD Atmos")
    check(z.imeZapisa("audio/true-hd") == "Dolby TrueHD")
    check(z.imeZapisa("audio/ac3") == "Dolby Digital" && z.imeZapisa("AUDIO/AC3") == "Dolby Digital")
    check(z.imeZapisa("audio/vnd.dts") == "DTS" && z.imeZapisa("audio/vnd.dts.hd") == "DTS-HD")
    check(z.imeZapisa("audio/mp4a-latm") == "AAC" && z.imeZapisa("audio/x-neznano") == "" && z.imeZapisa(null) == "")
    check(z.kanali(1) == "mono" && z.kanali(2) == "stereo" && z.kanali(6) == "5.1" && z.kanali(8) == "7.1" && z.kanali(4) == "4 ch" && z.kanali(0) == "")
    // Predano zvocniku: uporabnik vidi zapis.
    check(z.oznakaIzhoda("audio/eac3-joc", 6, false, 6) == "Dolby Atmos")
    check(z.oznakaIzhoda("audio/eac3", 6, false, 6, "DDP5.1 Atmos") == "Dolby Atmos")
    check(z.oznakaIzhoda("audio/eac3", 6, false, 6) == "Dolby Digital Plus 5.1")
    check(z.oznakaIzhoda("audio/ac3", 6, false, 6) == "Dolby Digital 5.1")
    check(z.oznakaIzhoda("audio/ac3", 2, false, 2) == "Dolby Digital")
    check(z.oznakaIzhoda("audio/vnd.dts.hd", 8, false, 8) == "DTS-HD 7.1")
    check(z.oznakaIzhoda("audio/true-hd", 8, false, 8, "Atmos") == "Dolby TrueHD Atmos")
    // Dekodirano v stereo, ceprav ima sled vec kanalov: uporabnik izve, da prostorskega zvoka ne dobi.
    check(z.oznakaIzhoda("audio/eac3-joc", 6, true, 2) == "Dolby Atmos → stereo")
    check(z.oznakaIzhoda("audio/ac3", 6, true, 2) == "Dolby Digital → stereo")
    check(z.oznakaIzhoda("audio/mp4a-latm", 6, true, 2) == "5.1 → stereo")
    check(z.oznakaIzhoda("audio/mp4a-latm", 6, true, 1) == "5.1 → mono")
    // Na telefonu in tablici mesanja v stereo ne omenjamo (pricakovano); predan tok in vec kanalov pa se vedno.
    check(z.oznakaIzhoda("audio/eac3-joc", 6, true, 2, null, povejZmesanje = false) == "")
    check(z.oznakaIzhoda("audio/mp4a-latm", 6, true, 2, null, povejZmesanje = false) == "")
    check(z.oznakaIzhoda("audio/eac3-joc", 6, false, 6, null, povejZmesanje = false) == "Dolby Atmos")
    check(z.oznakaIzhoda("audio/ac3", 6, true, 6, null, povejZmesanje = false) == "Dolby Digital 5.1")
    // Dekodirano v vec kanalov PCM (sprejemnik, ki sprejme PCM 5.1): zapis in kanali; Atmos kot predmeti tu ni vec.
    check(z.oznakaIzhoda("audio/ac3", 6, true, 6) == "Dolby Digital 5.1")
    check(z.oznakaIzhoda("audio/mp4a-latm", 6, true, 6) == "5.1")
    check(z.oznakaIzhoda("audio/eac3-joc", 6, true, 6) == "5.1") { z.oznakaIzhoda("audio/eac3-joc", 6, true, 6) }
    // Navaden stereo: nic.
    check(z.oznakaIzhoda("audio/mp4a-latm", 2, true, 2) == "")
    check(z.oznakaIzhoda("audio/opus", 2, true, 2) == "" && z.oznakaIzhoda(null, 0, true, 2) == "")
    check(z.oznakaIzhoda("audio/mp4a-latm", 2, false, 2) == "") { "neprostorski zapis, predan: nic posebnega" }
    // Izbira zvocne sledi: jezik, kanali, zapis; brez ponavljanja.
    check(z.opisSledi("Angleščina", 6, "audio/eac3-joc", null) == "Angleščina · 5.1 · Dolby Atmos")
    check(z.opisSledi("DDP 5.1 Atmos", 6, "audio/eac3", "DDP 5.1 Atmos") == "DDP 5.1 Atmos") { z.opisSledi("DDP 5.1 Atmos", 6, "audio/eac3", "DDP 5.1 Atmos") }
    check(z.opisSledi("Slovenščina", 2, "audio/mp4a-latm", null) == "Slovenščina · stereo · AAC")
    check(z.opisSledi("DTS-HD MA 7.1", 8, "audio/vnd.dts.hd", "DTS-HD MA 7.1") == "DTS-HD MA 7.1")
    check(z.opisSledi("", 6, "audio/ac3", null) == "5.1 · Dolby Digital")
    check(z.opisSledi(null, 0, null, null) == "")
    // Zapis, ki ga zunanji zvocnik ne navaja (DTS na zvocniku z Dolbyjem): dekodira naprava - povemo samo zapis.
    val zvocnik = setOf("ac3", "eac3", "atmos")                  // naprava HDMI ARC na televizorju, izmerjeno 6. 10. 2026
    check(!z.zvocnikZmore("audio/vnd.dts", zvocnik) && !z.zvocnikZmore("audio/vnd.dts.hd", zvocnik) && !z.zvocnikZmore("audio/true-hd", zvocnik))
    check(z.zvocnikZmore("audio/eac3-joc", zvocnik) && z.zvocnikZmore("audio/eac3", zvocnik) && z.zvocnikZmore("audio/ac3", zvocnik))
    check(z.zvocnikZmore("audio/eac3-joc", setOf("ac3", "eac3"))) { "Atmos v DD+ zmore tudi zvocnik z DD+" }
    check(z.zvocnikZmore("audio/vnd.dts", null) && z.zvocnikZmore("audio/mp4a-latm", zvocnik) && z.zvocnikZmore(null, zvocnik)) { "neznano: nic ne trdimo" }
    check(z.oznakaIzhoda("audio/vnd.dts", 6, false, 6, zvocnikZmore = false) == "DTS")
    check(z.oznakaIzhoda("audio/vnd.dts.hd", 8, false, 8, "DTS-HD MA 7.1", zvocnikZmore = false) == "DTS-HD")
    check(z.oznakaIzhoda("audio/vnd.dts", 6, false, 6, zvocnikZmore = true) == "DTS 5.1")
    check(z.oznakaIzhoda("audio/eac3-joc", 6, false, 6, zvocnikZmore = true) == "Dolby Atmos")
    check(z.oznakaIzhoda("audio/vnd.dts", 6, true, 2, zvocnikZmore = false) == "DTS → stereo") { "dekodirano: velja, kar gre ven" }
    // Samodejna izbira zvocne sledi: isti jezik, kar izhod res odda.
    fun s(mime: String, kanalov: Int, jezik: String? = "en", ime: String? = null, komentar: Boolean = false, podprta: Boolean = true) =
        ZvokPravila.Sled(mime, kanalov, jezik, ime, komentar, podprta)
    val tv = setOf("ac3", "eac3", "atmos", "dts")          // televizor z zvocnikom na eARC (izmerjeno 6. 10. 2026)
    val aac2 = s("audio/mp4a-latm", 2); val ddp51 = s("audio/eac3", 6); val joc = s("audio/eac3-joc", 6); val ac351 = s("audio/ac3", 6)
    check(z.oddano(ddp51, tv, 8) == (6 to false) && z.oddano(joc, tv, 8) == (6 to true) && z.oddano(aac2, tv, 8) == (2 to false))
    check(z.oddano(joc, setOf("ac3", "eac3"), 2) == (6 to false)) { "Atmos brez predaje Atmosa gre ven kot Dolby Digital Plus" }
    check(z.oddano(ddp51, emptySet(), 2) == (2 to false) && z.oddano(s("audio/mp4a-latm", 6), emptySet(), 8) == (6 to false))
    check(z.oddano(s("audio/mp4a-latm", -1), tv, 8) == (0 to false))
    // Privzeta stereo AAC, druga Dolby Digital Plus 5.1 v istem jeziku: na televizorju z zvocnikom dobi prednost 5.1.
    check(z.boljsaSled(listOf(aac2, ddp51), 0, tv, 8) == 1)
    check(z.boljsaSled(listOf(aac2, ac351, joc), 0, tv, 8) == 2) { "med 5.1 dobi prednost Atmos" }
    check(z.boljsaSled(listOf(ddp51, joc), 0, tv, 8) == 1 && z.boljsaSled(listOf(joc, ddp51), 0, tv, 8) == null)
    check(z.boljsaSled(listOf(ddp51, ac351), 0, tv, 8) == null) { "pri enakem stevilu kanalov zapisa ne menjamo" }
    check(z.boljsaSled(listOf(ddp51, aac2), 0, tv, 8) == null)
    // Telefon (zvocnik, slusalke): vse je stereo - nic se ne spremeni.
    check(z.boljsaSled(listOf(aac2, ddp51), 0, emptySet(), 2) == null)
    // Dekodiran vec kanalni zvok steje, kadar ga izhod odda (PCM 5.1 prek HDMI).
    check(z.boljsaSled(listOf(aac2, s("audio/mp4a-latm", 6)), 0, emptySet(), 8) == 1)
    // Drug jezik, komentar, opis ali sled, ki je naprava ne zmore: ne.
    check(z.boljsaSled(listOf(s("audio/mp4a-latm", 2, "sl"), ddp51), 0, tv, 8) == null) { "jezika ne menjamo" }
    check(z.boljsaSled(listOf(s("audio/mp4a-latm", 2, null), s("audio/eac3", 6, "und")), 0, tv, 8) == 1) { "oba neznana: isti" }
    check(z.boljsaSled(listOf(s("audio/mp4a-latm", 2, null), ddp51), 0, tv, 8) == null) { "neznan in znan jezik nista ista" }
    check(z.boljsaSled(listOf(s("audio/mp4a-latm", 2, "en-US"), s("audio/eac3", 6, "en-GB")), 0, tv, 8) == 1)
    check(z.boljsaSled(listOf(aac2, s("audio/eac3", 6, ime = "Director's Commentary")), 0, tv, 8) == null)
    check(z.boljsaSled(listOf(aac2, s("audio/eac3", 6, komentar = true)), 0, tv, 8) == null)
    check(z.boljsaSled(listOf(aac2, s("audio/eac3", 6, ime = "Audio description")), 0, tv, 8) == null)
    check(z.boljsaSled(listOf(aac2, s("audio/true-hd", 8, podprta = false)), 0, tv, 8) == null)
    check(z.boljsaSled(listOf(s("audio/mp4a-latm", 2, ime = "Komentar reziserja"), ddp51), 0, tv, 8) == null) { "izbran komentar ostane" }
    check(z.boljsaSled(listOf(aac2), 0, tv, 8) == null && z.boljsaSled(listOf(aac2, ddp51), 5, tv, 8) == null && z.boljsaSled(emptyList(), 0, tv, 8) == null)
    // Vec boljsih: najvec kanalov; pri enakih prva v datoteki.
    check(z.boljsaSled(listOf(aac2, ac351, s("audio/vnd.dts.hd", 8), ddp51), 0, tv, 8) == 2)
    check(z.boljsaSled(listOf(aac2, ac351, ddp51), 0, tv, 8) == 1)
    println("ZvokPravilaTest: OK")
}
