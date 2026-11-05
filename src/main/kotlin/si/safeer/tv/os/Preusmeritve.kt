package si.safeer.tv.os

import java.net.URL

/**
 * En skok preusmeritve pri prenosih (NamestiApkPravila.naslednji za APK, NastavitvePravila.naslednjiSkok za posodobitve).
 * Drugi krog pregleda 5. 11. 2026: prej dve izvedbi (URL in URI), ki sta se razlikovali (npr. presledek v Location: APK je
 * sledil, posodobitev ne). Samo https z imenom gostitelja in brez uporabnika v naslovu; relativna lokacija se razresi
 * proti trenutnemu naslovu. Preizkus: tests/NastavitvePravilaTest.kt, tests/NamestiApkPravilaTest.kt.
 */
object Preusmeritve {
    sealed class Skok {
        /** Absoluten naslov https naslednjega skoka. */
        class Naprej(val url: String) : Skok()
        /** [razlog]: brez_lokacije, neveljavna_lokacija, ni_https. */
        class Zavrnjeno(val razlog: String) : Skok()
    }

    fun naslednji(trenutni: String, lokacija: String?): Skok {
        val l = lokacija?.trim().orEmpty()
        if (l.isEmpty()) return Skok.Zavrnjeno("brez_lokacije")
        val cilj = try { URL(URL(trenutni), l) } catch (_: Exception) { return Skok.Zavrnjeno("neveljavna_lokacija") }
        if (!cilj.protocol.equals("https", ignoreCase = true) || cilj.host.isNullOrBlank()) return Skok.Zavrnjeno("ni_https")
        // Uporabnik in geslo v naslovu (https://a:b@...) nista del prenosa.
        if (cilj.userInfo != null) return Skok.Zavrnjeno("neveljavna_lokacija")
        return Skok.Naprej(cilj.toString())
    }
}
