package si.safeer.tv.os

import java.security.MessageDigest

/**
 * Vec YouTube racunov (lastnik 21. 11. 2026): uporabnik se prijavi z vec Google racuni, seznami in narocnine vseh pridejo v Glasbo.
 * Ciste odlocitve (brez Androida; preizkus: tests/YouTubeRacuniPravilaTest.kt).
 */
object YouTubeRacuniPravila {
    const val NAJVEC_RACUNOV = 6

    /** Stabilen id racuna iz piskotka SAPISID (ne hrani vrednosti): ista prijava ne naredi dvojnika. null = piskotkov prijave ni. */
    fun idRacuna(piskotki: String): String? {
        val v = piskotki.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }
            ?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Imena piskotkov iz niza »a=1; b=2« (za brisanje iz skupne zbirke ob dodajanju novega racuna). */
    fun imenaPiskotkov(piskotki: String): List<String> =
        piskotki.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() && it.all { c -> c.code in 33..126 && c != ',' && c != ';' } }.distinct()

    /** Predpona imena seznama: racun s prvim imenom brez (kot doslej, seznami se ne podvojijo), ostali »Ime racuna · «. */
    fun predponaSeznama(ime: String, prvoIme: String): String = if (ime == prvoIme) "" else "$ime · "

    /** Ime racuna po zaporedni stevilki (1-based); [predlog] vsebuje »%d«. */
    fun privzetoIme(zaporedna: Int, predlog: String): String = predlog.replace("%d", zaporedna.toString())

    /** Naslednja zaporedna stevilka: najvecja doslej + 1 (po odstranitvi racuna se imena ne ponovijo). */
    fun naslednja(imena: List<String>, predlog: String): Int {
        val rx = Regex("^" + Regex.escape(predlog).replace("%d", "\\E(\\d+)\\Q") + "$")
        return (imena.mapNotNull { rx.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
    }
}
