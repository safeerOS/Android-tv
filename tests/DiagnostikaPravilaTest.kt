package si.safeer.tv.os

fun main() {
    val zdajS = 1_792_960_000L
    check(DiagnostikaPravila.velja("${zdajS + 3600}", zdajS)) { "ura v prihodnosti" }
    check(DiagnostikaPravila.velja(" ${zdajS + DiagnostikaPravila.NAJVEC_S} ", zdajS)) { "natanko 6 h" }
    check(!DiagnostikaPravila.velja("${zdajS + DiagnostikaPravila.NAJVEC_S + 1}", zdajS)) { "vec kot 6 h naprej (pozabljena za vedno)" }
    check(!DiagnostikaPravila.velja("${zdajS - 1}", zdajS)) { "potekla" }
    check(!DiagnostikaPravila.velja("$zdajS", zdajS)) { "zdaj ze ne velja" }
    check(!DiagnostikaPravila.velja("1", zdajS)) { "stara oblika 1/true" }
    check(!DiagnostikaPravila.velja("DEBUG", zdajS)) { "stara oblika DEBUG" }
    check(!DiagnostikaPravila.velja("", zdajS) && !DiagnostikaPravila.velja(null, zdajS)) { "prazno ali ni lastnosti" }
    check(DiagnostikaPravila.dovoljenaLastnost(DiagnostikaPravila.P2P_SAMO))
    check(!DiagnostikaPravila.dovoljenaLastnost("persist.safeer.p2p_samo")) { "persist ostane po zagonu" }
    check(!DiagnostikaPravila.dovoljenaLastnost("debug.drug.p2p")) { "tuja lastnost" }
    check(!DiagnostikaPravila.dovoljenaLastnost("debug.safeer.x; rm")) { "brez posebnih znakov" }
    // Pregled Grok 32, G4/G6: getprop (nov proces) samo pri vklopljenem razvijalskem adb in dovoljenem imenu.
    var branj = 0
    val beri: (String) -> String? = { branj++; "${zdajS + 3600}" }
    check(!DiagnostikaPravila.vklopljena(DiagnostikaPravila.P2P_SAMO, zdajS, { false }, beri) && branj == 0) { "brez adb: takoj false, brez procesa" }
    check(!DiagnostikaPravila.vklopljena("persist.safeer.p2p_samo", zdajS, { true }, beri) && branj == 0) { "nedovoljeno ime: brez procesa" }
    var adbVprasan = false
    check(!DiagnostikaPravila.vklopljena("debug.drug.x", zdajS, { adbVprasan = true; true }, beri) && !adbVprasan) { "ime najprej" }
    check(DiagnostikaPravila.vklopljena(DiagnostikaPravila.P2P_SAMO, zdajS, { true }, beri) && branj == 1) { "adb in veljaven rok: vklopljena" }
    check(!DiagnostikaPravila.vklopljena(DiagnostikaPravila.P2P_SAMO, zdajS, { true }, { null })) { "getprop brez odgovora (rok): false" }
    check(!DiagnostikaPravila.velja("-1", zdajS) && !DiagnostikaPravila.velja("${Long.MAX_VALUE}", zdajS)) { "G7: -1 in Long.MAX_VALUE" }
    check(!DiagnostikaPravila.dovoljenaLastnost("debug.safeer.P2P") && !DiagnostikaPravila.dovoljenaLastnost("debug.safeer." + "x".repeat(41))) { "G7: velike crke, predolgo ime" }
    println("DiagnostikaPravilaTest: OK")
}
