package si.safeer.tv

import com.safeer.threatfeed.PlainList
import com.safeer.threatfeed.PlainListSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

// Zascita od prve zahteve hladnega zagona (raziskava 4. 11. 2026): tests/run_threat_policy_tests.sh.
// Brez cakanja v ThreatBlockEngine.handleThreatIntercept ta preizkus pade (zahteva pred koncem nalaganja ni ustavljena).
fun main() {
    // 1. Cisto pravilo
    val Z = ZascitaZagona
    check(Z.kolikoCakati(pripravljeno = false, zacetoOb = 1_000, zdaj = 1_000, glavnaNit = false) == Z.CAKAJ_NA_ZAHTEVO_MS)
    check(Z.kolikoCakati(false, 1_000, 1_000, glavnaNit = true) == 0L) { "glavna nit nikoli ne caka (ANR)" }
    check(Z.kolikoCakati(true, 1_000, 1_000, false) == 0L) { "po prvem nalaganju ni cakanja" }
    check(Z.kolikoCakati(false, -1, 1_000, false) == 0L) { "brez agenta seznamov (drug proces) ni cakanja" }
    check(Z.kolikoCakati(false, 1_000, 1_000 + Z.NAJDLJE_OD_ZAGONA_MS - 100, false) == 100L) { "do roka od zagona" }
    check(Z.kolikoCakati(false, 1_000, 1_000 + Z.NAJDLJE_OD_ZAGONA_MS, false) == 0L) { "zataknjeno nalaganje: ne v nedogled" }
    check(Z.kolikoCakati(false, 1_000, 1_000 + 10 * Z.NAJDLJE_OD_ZAGONA_MS, false) == 0L)

    val zlo = "https://evil-cold-start.example/beacon.js"
    check(ThreatBlockEngine.handleThreatIntercept(zlo, false) == null) { "domena je samo na seznamu, ne v semenu" }
    Z.zacni()

    // 2. Glavna nit ne caka, tudi ce seznami se niso nalozeni.
    android.os.Looper.glavnaNit = Thread.currentThread().id
    var t = System.nanoTime()
    ThreatBlockEngine.handleThreatIntercept("https://www.example.org/", false)
    val glavnaMs = (System.nanoTime() - t) / 1_000_000
    android.os.Looper.glavnaNit = -1
    check(glavnaMs < 100) { "glavna nit je cakala $glavnaMs ms" }
    check(!Z.pripravljeno)

    // 3. Zahteva iz nitnega bazena WebView med nalaganjem pocaka in je ustavljena po seznamu.
    val odgovor = AtomicReference<Any?>("ni koncano")
    var cakalaMs = -1L
    val nit = Thread {
        val zac = System.nanoTime()
        odgovor.set(ThreatBlockEngine.handleThreatIntercept(zlo, false))
        cakalaMs = (System.nanoTime() - zac) / 1_000_000
    }
    nit.start()
    Thread.sleep(200)
    val urlhaus = PlainListSource("urlhaus", "abuse.ch URLhaus", "https://urlhaus.abuse.ch/downloads/hostfile/", "Zlonamerna koda (Malware)", "urlhaus")
    ThreatBlockEngine.rebuildFromLists(listOf(PlainList(urlhaus, listOf("evil-cold-start.example"), 0)))
    Z.seznamiKoncani()
    check(!Z.pripravljeno) { "podpisani vir se ni nalozen" }
    Z.podpisaniKoncan()
    nit.join(5_000)
    check(odgovor.get() != null && odgovor.get() != "ni koncano") { "prva zahteva hladnega zagona ni bila filtrirana (cakala $cakalaMs ms)" }
    check(cakalaMs in 150..Z.CAKAJ_NA_ZAHTEVO_MS + 200) { "cakala $cakalaMs ms" }
    check(Z.pripravljeno && Z.pripravljenoPoMs >= 0)

    // 4. Po prvem nalaganju zahteve ne cakajo vec.
    t = System.nanoTime()
    repeat(1_000) { ThreatBlockEngine.handleThreatIntercept("https://www.example.org/a$it", false) }
    val po = (System.nanoTime() - t) / 1_000_000
    check(po < 1_000) { "1000 zahtev po nalaganju: $po ms" }
    check(ThreatBlockEngine.handleThreatIntercept(zlo, false) != null)
    println("ZascitaZagona: OK (prva zahteva cakala $cakalaMs ms, glavna nit $glavnaMs ms)")
    exitProcess(0)
}
