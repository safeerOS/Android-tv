package si.safeer.tv

import com.safeer.threatfeed.PlainList
import com.safeer.threatfeed.PlainListSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

// Zascita od prve zahteve hladnega zagona (raziskava 4. 11. 2026): tests/run_threat_policy_tests.sh.
// Brez cakanja v ThreatBlockEngine.handleThreatIntercept ta preizkus pade (zahteva pred koncem nalaganja ni ustavljena).
// Brez popravka 4. 11. 2026 (rok od zacetka namesto 500 ms na zahtevo) pade korak 3: TV je pokazal zahteve brez seznamov
// po 501 ms cakanja.

private fun zahtevaVNiti(url: String, glavniOkvir: Boolean): Pair<AtomicReference<Any?>, Thread> {
    val odgovor = AtomicReference<Any?>("ni koncano")
    val nit = Thread {
        val zac = System.nanoTime()
        val r = ThreatBlockEngine.handleThreatIntercept(url, glavniOkvir)
        odgovor.set(r to (System.nanoTime() - zac) / 1_000_000)
    }
    nit.start()
    return odgovor to nit
}

@Suppress("UNCHECKED_CAST")
private fun izid(r: AtomicReference<Any?>): Pair<Any?, Long> = r.get() as Pair<Any?, Long>

fun main() {
    val Z = ZascitaZagona
    // 1. Cisto pravilo: caka do roka od zacetka, ne vec 500 ms na zahtevo.
    check(Z.kolikoCakati(pripravljeno = false, zacetoOb = 1_000, zdaj = 1_000, glavnaNit = false) == Z.NAJDLJE_OD_ZAGONA_MS)
    check(Z.kolikoCakati(false, 1_000, 1_000, glavnaNit = true) == 0L) { "glavna nit nikoli ne caka (ANR)" }
    check(Z.kolikoCakati(true, 1_000, 1_000, false) == 0L) { "po prvem nalaganju ni cakanja" }
    check(Z.kolikoCakati(false, -1, 1_000, false) == 0L) { "brez agenta seznamov (drug proces) ni cakanja" }
    check(Z.kolikoCakati(false, 1_000, 1_000 + Z.NAJDLJE_OD_ZAGONA_MS - 100, false) == 100L) { "do roka od zagona" }
    check(Z.kolikoCakati(false, 1_000, 1_000 + Z.NAJDLJE_OD_ZAGONA_MS, false) == 0L) { "zataknjeno nalaganje: ne v nedogled" }

    // 2. Cas vsakega dela posebej (TV 4. 11. 2026: »podpisani vir nalozen, pripravljena=false po -1 ms«).
    Z.ponastaviZaPreizkus()
    Z.zacni(10_000)
    check(Z.koncan(ZascitaZagona.Del.PODPISANI, 10_040) == 40L) { "podpisani vir: cas od zacetka" }
    check(Z.koncan(ZascitaZagona.Del.PODPISANI, 10_090) == null) { "drugic ni porocila" }
    check(Z.casDela(ZascitaZagona.Del.PODPISANI) == 40L && !Z.pripravljeno && Z.pripravljenoPoMs == -1L)
    check(Z.koncan(ZascitaZagona.Del.DREVO, 10_005) == 5L)
    check(Z.grozenjPripravljeno && !Z.easyListPripravljen)
    check(Z.koncan(ZascitaZagona.Del.EASYLIST, 11_500) == 1_500L)
    check(Z.pripravljeno && Z.pripravljenoPoMs == 1_500L) { Z.porocilo() }

    val zlo = "https://evil-cold-start.example/beacon.js"
    val urlhaus = PlainListSource("urlhaus", "abuse.ch URLhaus", "https://urlhaus.abuse.ch/downloads/hostfile/", "Zlonamerna koda (Malware)", "urlhaus")
    ThreatBlockEngine.rebuildFromLists(emptyList())
    check(ThreatBlockEngine.handleThreatIntercept(zlo, false) == null) { "domena je samo na seznamu, ne v semenu" }

    // 3. Drevo se gradi 800 ms (vec kot stari rok 500 ms na zahtevo): glavni okvir (caka samo na drevo in podpisani vir)
    // pocaka in dobi opozorilo; s starim rokom 500 ms bi sel brez seznamov.
    Z.ponastaviZaPreizkus()
    Z.zacni()
    android.os.Looper.glavnaNit = Thread.currentThread().id
    var t = System.nanoTime()
    ThreatBlockEngine.handleThreatIntercept("https://www.example.org/", false)
    val glavnaMs = (System.nanoTime() - t) / 1_000_000
    android.os.Looper.glavnaNit = -1
    check(glavnaMs < 100) { "glavna nit je cakala $glavnaMs ms" }
    val (o3, n3) = zahtevaVNiti("https://evil-cold-start.example/", true)
    Thread.sleep(800)
    ThreatBlockEngine.rebuildFromLists(listOf(PlainList(urlhaus, listOf("evil-cold-start.example"), 0)))
    Z.seznamiKoncani()
    Z.podpisaniKoncan()
    Z.easyListKoncan()
    n3.join(5_000)
    val (r3, ms3) = izid(o3)
    check(r3 != null) { "prva zahteva hladnega zagona ni bila filtrirana (cakala $ms3 ms)" }
    check(Z.casDela(ZascitaZagona.Del.DREVO) >= 700) { Z.porocilo() }
    check(ms3 in 700..2_000) { "cakala $ms3 ms" }

    // 4. Drevo in podpisani vir pripravljena (kazalo), EasyList se ne: glavni okvir ne caka, podvir caka na EasyList.
    Z.ponastaviZaPreizkus()
    Z.zacni()
    Z.seznamiKoncani(); Z.podpisaniKoncan()
    t = System.nanoTime()
    check(ThreatBlockEngine.handleThreatIntercept(zlo, true) != null) { "glavni okvir: drevo velja takoj" }
    val glavniMs = (System.nanoTime() - t) / 1_000_000
    check(glavniMs < 100) { "glavni okvir je cakal na EasyList $glavniMs ms" }
    val (o4, n4) = zahtevaVNiti("https://www.example.org/ad.js", false)
    Thread.sleep(400)
    check(o4.get() == "ni koncano") { "podvir ni cakal na EasyList" }
    Z.easyListKoncan()
    n4.join(5_000)
    val (_, ms4) = izid(o4)
    check(ms4 in 300..1_500) { "podvir cakal $ms4 ms" }

    // 5. Zataknjeno nalaganje: najdlje do roka od zacetka.
    Z.ponastaviZaPreizkus()
    Z.zacni(System.currentTimeMillis() - Z.NAJDLJE_OD_ZAGONA_MS + 300)
    val (o5, n5) = zahtevaVNiti("https://www.example.org/x.js", false)
    n5.join(5_000)
    val (_, ms5) = izid(o5)
    check(ms5 in 200..800) { "do roka: cakala $ms5 ms" }

    // 6. Po pripravi zahteve ne cakajo vec.
    Z.ponastaviZaPreizkus()
    Z.zacni()
    Z.seznamiKoncani(); Z.podpisaniKoncan(); Z.easyListKoncan()
    t = System.nanoTime()
    repeat(1_000) { ThreatBlockEngine.handleThreatIntercept("https://www.example.org/a$it", false) }
    val po = (System.nanoTime() - t) / 1_000_000
    check(po < 1_000) { "1000 zahtev po nalaganju: $po ms" }
    check(ThreatBlockEngine.handleThreatIntercept(zlo, false) != null)
    println("ZascitaZagona: OK (glavni okvir med gradnjo cakal $ms3 ms, glavni okvir $glavniMs ms, podvir na EasyList $ms4 ms, glavna nit $glavnaMs ms)")
    exitProcess(0)
}
