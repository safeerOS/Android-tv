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
    println("DiagnostikaPravilaTest: OK")
}
