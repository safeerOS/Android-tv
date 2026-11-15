// Slepi pregled TV3 (15. 11. 2026), N4: gola imena brez poddomene mimo napaka(). IME_KRAJEVNO ima vodilno piko in je zadelo samo
// poddomene (localtest.me in lvh.me razresita na povratno zanko). Vsaka vrstica »zavrnjen« brez popravka PADE.
//   tests/run_scit_tests.sh
package si.safeer.tv.scit

import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

fun main() {
    val zavrnjeni = listOf("https://localtest.me/x", "https://lvh.me/x", "https://LOCALTEST.ME./x", "https://localtest.me:8443/x", "https://a.localtest.me/x",
        "https://nip.io/x", "https://sslip.io/x", "https://xip.io/x", "https://traefik.me/x", "https://home.arpa/x", "https://mshome.net/x", "https://pc.mshome.net/x",
        "https://my.router/x", "https://MY.ROUTER/x", "https://kubernetes.default.svc/x", "https://kubernetes.default.svc.cluster.local/x", "https://api.default.svc/x",
        "https://foo.svc/x")
    for (n in zavrnjeni) p(ScitPoMeriPravila.napaka(n) == "gostitelj", "zavrnjen gostitelj $n")
    // Ne pretiravamo: javna imena, ki samo vsebujejo ime, so sprejeta.
    val sprejeti = listOf("https://example.com/x", "https://mylocaltest.me/x", "https://notlvh.me/x", "https://xmy.router.example/x", "https://my.routers.example/x",
        "https://localtest.me.example.com/x", "https://svc.example.com/x", "https://raw.githubusercontent.com/hosts", "https://filters.adtidy.org/x")
    for (n in sprejeti) p(ScitPoMeriPravila.napaka(n) == null, "sprejet $n")
    println("ScitPoMeriGolaImenaTest: $preverb preverb, $padlo padlo")
    if (padlo > 0) exitProcess(1)
}
