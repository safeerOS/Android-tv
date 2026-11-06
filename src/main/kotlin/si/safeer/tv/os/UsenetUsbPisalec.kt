package si.safeer.tv.os

/** Zapis vnaprejsnjih segmentov Useneta na USB brez zavrzenih segmentov in brez zastoja predvajanja (brez Androida, preizkus v JVM). */
object UsenetUsbPisalec {
    /**
     * Pregled US-4 (6. 11. 2026): vnaprejsnji segment se ne sme zavreci (sicer ga predvajanje prenese znova - JVM meritev 2,5x),
     * a branje vnaprej tudi ne sme ustaviti predvajanja. Bralnik ima en bazen niti za oboje ([UsenetBralnik.Profil.vzporedno]):
     * ce bi vse niti cakale na pocasen USB, bi branje za predvajanje cakalo za njimi. Zato pocaka najvec [NAJVEC_CAKAJOCIH] (TV: 5 od 8, za predvajanje ostanejo 3)
     * niti, vsaka najvec [CAKAJ_MS], ostale zapis zavrzejo (z zapisom v dnevnik). Vrsta [VRSTA_VNAPREJ] segmentov (~12 MiB
     * pri 768 KiB) - prej 160 (~120 MiB kopice na TV z 192 MiB).
     */
    const val VRSTA_VNAPREJ = 16
    const val NAJVEC_CAKAJOCIH = 5
    const val CAKAJ_MS = 3_000L

    /** Izvajalec zapisov vnaprej; [tovarna] in meje so parametri zaradi preizkusa (tests/UsenetTokTest.kt pisalecVnaprejMeje, meritevVnaprejUsb). */
    fun nov(
        vrsta: Int = VRSTA_VNAPREJ, najvecCakajocih: Int = NAJVEC_CAKAJOCIH, cakajMs: Long = CAKAJ_MS,
        obZavrzenem: () -> Unit = {},
        tovarna: java.util.concurrent.ThreadFactory,
    ): java.util.concurrent.ThreadPoolExecutor {
        val cakajocih = java.util.concurrent.atomic.AtomicInteger()
        return java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(vrsta), tovarna) { r, izvajalec ->
            var sprejet = false
            if (!izvajalec.isShutdown) {
                try {
                    if (cakajocih.incrementAndGet() <= najvecCakajocih) sprejet = izvajalec.queue.offer(r, cakajMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                finally { cakajocih.decrementAndGet() }
            }
            if (!sprejet) obZavrzenem()
        }
    }

}
