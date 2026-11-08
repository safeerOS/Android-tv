package si.safeer.tv.os

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Revizija RAM 8. 11. 2026 (#8): bazen s stalnimi nitmi po mirovanju sprosti vse niti; brez popravka jih drzi. */
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

private fun zaposli(b: ThreadPoolExecutor, n: Int) {
    val start = CountDownLatch(n)
    repeat(n) { b.execute { start.countDown(); start.await(2, TimeUnit.SECONDS) } }
    start.await(2, TimeUnit.SECONDS)
}

private fun pocakajNaNiti(b: ThreadPoolExecutor, cilj: Int, najvecMs: Long): Int {
    val do_ = System.currentTimeMillis() + najvecMs
    while (b.poolSize != cilj && System.currentTimeMillis() < do_) Thread.sleep(50)
    return b.poolSize
}

fun main() {
    // Brez popravka: 2 jedrni niti ostaneta tudi po 1,5 s brez dela.
    val stari = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    zaposli(stari, 2)
    preveriEnako("brez popravka: jedrni niti ostaneta", 2, pocakajNaNiti(stari, 0, 1_500))
    preveriEnako("brez popravka: keepAlive 0", 0L, stari.getKeepAliveTime(TimeUnit.SECONDS))
    stari.shutdownNow()

    // S popravkom (1 s za test): po mirovanju bazen nima niti; nova naloga spet dela.
    val novi = Niti.sprostiMirujoce(Executors.newFixedThreadPool(2), sekund = 1) as ThreadPoolExecutor
    preveriEnako("popravek: keepAlive nastavljen", 1L, novi.getKeepAliveTime(TimeUnit.SECONDS))
    preveriEnako("popravek: jedrne niti smejo mirovati", true, novi.allowsCoreThreadTimeOut())
    zaposli(novi, 2)
    preveriEnako("popravek: po mirovanju 0 niti", 0, pocakajNaNiti(novi, 0, 4_000))
    val konec = CountDownLatch(1); novi.execute { konec.countDown() }
    preveriEnako("popravek: nova naloga po sprostitvi tece", true, konec.await(2, TimeUnit.SECONDS))
    novi.shutdownNow()

    // Privzeto 30 s; bazen z keepAlive 0 (slikeDelavec) dobi 30 s; sekund <= 0 ne spreminja nicesar.
    val tretji = Niti.sprostiMirujoce(ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue()))
    preveriEnako("privzeto 30 s", Niti.MIROVANJE_S, tretji.getKeepAliveTime(TimeUnit.SECONDS))
    tretji.shutdownNow()
    val cetrti = Niti.sprostiMirujoce(Executors.newFixedThreadPool(1), sekund = 0) as ThreadPoolExecutor
    preveriEnako("sekund 0: brez spremembe", false, cetrti.allowsCoreThreadTimeOut())
    cetrti.shutdownNow()
    // Enonitni bazen (newSingleThreadExecutor) ni ThreadPoolExecutor: vrne se nespremenjen, brez izjeme.
    val peti = Niti.sprostiMirujoce(Executors.newSingleThreadExecutor())
    preveriEnako("enonitni bazen nespremenjen", false, peti is ThreadPoolExecutor)
    peti.shutdownNow()
    if (napak == 0) println("NitiTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
