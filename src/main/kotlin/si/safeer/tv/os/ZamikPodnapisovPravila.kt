package si.safeer.tv.os

import kotlin.math.abs

/**
 * Zamik podnapisov (lastnik 13. 11. 2026: »podnapisi v Medijskem centru prehitevajo sliko«).
 * Podnapis iz dodatka je narejen za drugo izdajo videa, zato se pokaze prej ali pozneje kot govor; ExoPlayer
 * zamika nima, zato ga uporabnik nastavi v meniju podnapisov in ga uporabimo pri branju podnapisov. Od 14. 11. 2026 velja
 * zamik za vsako datoteko podnapisov posebej (Podnapisi.nastaviZamikMs) in je rezerva za samodejno uskladitev
 * ([UskladitevPodnapisovPravila]).
 * Pozitiven zamik = podnapis se pokaze POZNEJE (popravek za »prehitevajo«). Čisto pravilo brez Androida.
 */
object ZamikPodnapisovPravila {
    const val KORAK_MS = 100L
    const val NAJVEC_MS = 30_000L

    /** Enako kot C.TIME_UNSET v Media3 (trajanje zadnjega napisa je lahko neznano). */
    const val BREZ_TRAJANJA = Long.MIN_VALUE + 1

    fun omeji(ms: Long): Long = ms.coerceIn(-NAJVEC_MS, NAJVEC_MS)

    fun spremeni(trenutniMs: Long, korakMs: Long): Long = omeji(trenutniMs + korakMs)

    /**
     * (začetek, trajanje) v mikrosekundah po zamiku; null = napis je v celoti pred začetkom videa in ga ne pokažemo.
     * Napis, ki bi se začel pred 0, se začne pri 0 (skrajšan), da ne izgubimo besedila.
     */
    fun zamakni(zacetekUs: Long, trajanjeUs: Long, zamikUs: Long): Pair<Long, Long>? {
        if (zamikUs == 0L) return zacetekUs to trajanjeUs
        val nov = zacetekUs + zamikUs
        if (trajanjeUs == BREZ_TRAJANJA) return maxOf(nov, 0L) to BREZ_TRAJANJA
        val konec = nov + trajanjeUs
        if (konec <= 0L) return null
        return if (nov < 0L) 0L to konec else nov to trajanjeUs
    }

    /** »+0,3 s«, »−1,5 s«, »0 s«; desetinka sekunde, kolikor je korak. */
    fun oblika(ms: Long): String {
        if (ms == 0L) return "0 s"
        val a = abs(ms)
        return (if (ms > 0) "+" else "−") + "${a / 1000},${(a % 1000) / 100} s"
    }

    /** Brez predznaka (za besedilo gumba »kasneje za …«). */
    fun trajanje(ms: Long): String = oblika(abs(ms)).removePrefix("+")

    /**
     * Naslov podnapisa iz Format.id (Media3 v zdruzenem viru doda predpono »0:«); null = vgrajen podnapis. Slepi pregled
     * 14. 11. 2026 (S3): tudi podnapis iz datoteke (SAF content://, file://) - prej samo http(s), rocni zamik zanj ni delal.
     */
    fun naslovIzId(id: String?): String? {
        var t = id?.trim() ?: return null
        while (true) {
            if (t.startsWith("https://") || t.startsWith("http://") || t.startsWith("content://") || t.startsWith("file://")) return t
            val i = t.indexOf(':')
            if (i <= 0 || !t.substring(0, i).all { it.isDigit() }) return null
            t = t.substring(i + 1)
        }
    }
}
