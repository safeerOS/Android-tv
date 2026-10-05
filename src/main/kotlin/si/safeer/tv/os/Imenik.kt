package si.safeer.tv.os

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Sveze vprasanje imeniku (DNS), mimo predpomnilnika naprave: ali ime obstaja. Rabi ga predvajalnik, da loci kanal,
 * katerega streznika ni, od izpada omrezja te naprave ([OsPravila.mrtevGostitelj]). Zato zraven vedno vprasa se za
 * kontrolno ime: brez predpomnilnika nanj odgovori samo imenik, ki ta hip res dela.
 */
object Imenik {
    /** Kontrolno ime: nasa domena (vprasanje gre imeniku uporabnikove mreze, ne nasemu strezniku). */
    const val KONTROLA = "safeer.si"

    /**
     * Za vsako ime: true = ima naslov, false = imenik je odgovoril, da ga ni, null = imenik ni odgovoril (ali naprava
     * tega ne zna: Android 9). Odgovori pridejo po glavni niti, zato se tega NE klice z glavne niti - caka najvec [rokMs].
     */
    fun obstaja(imena: List<String>, rokMs: Long): List<Boolean?> {
        if (Build.VERSION.SDK_INT < 29) return imena.map { null }
        val izidi = arrayOfNulls<Boolean>(imena.size)
        val konec = CountDownLatch(imena.size)
        val preklic = CancellationSignal()
        val takoj = Executor { it.run() }
        for ((i, ime) in imena.withIndex()) {
            try {
                DnsResolver.getInstance().query(null, ime, DnsResolver.FLAG_NO_CACHE_LOOKUP, takoj, preklic,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(odgovor: List<InetAddress>, rcode: Int) {
                            izidi[i] = when {
                                odgovor.isNotEmpty() -> true
                                rcode == 0 || rcode == 3 -> false       // brez naslovov ali »imena ni« (NXDOMAIN)
                                else -> null                             // imenik sam ne ve (SERVFAIL, REFUSED ...)
                            }
                            konec.countDown()
                        }
                        override fun onError(napaka: DnsResolver.DnsException) { konec.countDown() }
                    })
            } catch (_: Throwable) { konec.countDown() }
        }
        try { konec.await(rokMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        try { preklic.cancel() } catch (_: Throwable) { }
        return izidi.toList()
    }
}
