package si.safeer.tv.scit

import com.safeer.threatfeed.PlainListParser
import com.safeer.threatfeed.ThreatListNetwork
import java.io.File

/**
 * Seznam naslovov IPv4 groznj za blokado po IP odgovora (26. 11. 2026, po zgledu blocky »block by response IP«).
 * Cista funkcija brez Androida (preizkus v JVM).
 *
 * Seznam (Feodo Tracker: botnet C2) prenasa in shranjuje agent brskalnika (ThreatFeedsUpdater, mapa threat-lists,
 * datoteka »<id>.list«, en naslov v vrstici). Proces :scit ga samo BERE: odlocitev 4. 11. 2026 ostane (:scit seznamov IPv4
 * ne prenasa), seznama sta majhna (do ok. 8000 naslovov), zato je branje poceni; datoteka je omejena na 1 MiB.
 * Neveljavne vrstice in ne-javni naslovi (zasebni, krajevni, rezervirani) se zavrzejo: zastrupljen vnos 127.0.0.1 ali
 * 192.168.x.x ne sme ustaviti krajevnih imen.
 */
object ScitIpSeznam {
    const val NAJVEC_BAJTOV = 1 shl 20

    /** Seznami IPv4 brskalnikovega agenta (id seznama + ».list«). */
    val DATOTEKE: List<String> = listOf("feodo.list", "feodo-aggressive.list")

    class Nabor(private val urejeni: IntArray, val spremenjena: Long) {
        val stevilo: Int get() = urejeni.size
        fun vsebuje(naslov: String): Boolean {
            val v = vInt(naslov) ?: return false
            return java.util.Arrays.binarySearch(urejeni, v) >= 0
        }
    }

    /** a.b.c.d (strogo, brez vodilnih nicel) v stevilo; null, ce ni veljaven naslov IPv4. */
    fun vInt(s: String): Int? {
        if (!PlainListParser.isIpv4(s)) return null
        var r = 0
        for (del in s.split('.')) r = (r shl 8) or del.toInt()
        return r
    }

    private fun jeJavni(v: Int): Boolean =
        !ThreatListNetwork.isNonPublicAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    /** Naslovi iz besedila seznama (vrstice; presledki ob robovih se odrezejo), brez neveljavnih in ne-javniH. */
    fun razclani(besedilo: String): IntArray =
        besedilo.lineSequence().mapNotNull { vInt(it.trim()) }.filter(::jeJavni).distinct().sorted().toList().toIntArray()

    /** Nabor iz datotek v [mapa] (threat-lists); null, ce nobene datoteke ni. Prevelika datoteka se preskoci. */
    fun odpri(mapa: File): Nabor? {
        val datoteke = DATOTEKE.map { File(mapa, it) }.filter { it.isFile }
        if (datoteke.isEmpty()) return null
        val vsi = java.util.TreeSet<Int>()
        for (d in datoteke) {
            if (d.length() > NAJVEC_BAJTOV) continue
            for (v in razclani(d.readText(Charsets.UTF_8))) vsi.add(v)
        }
        return Nabor(vsi.toIntArray(), spremenjena(mapa))
    }

    /** Najnovejsi cas spremembe datotek seznama (0, ce jih ni): ob spremembi se nabor znova odpre. */
    fun spremenjena(mapa: File): Long = DATOTEKE.maxOfOrNull { File(mapa, it).lastModified() } ?: 0L
}
