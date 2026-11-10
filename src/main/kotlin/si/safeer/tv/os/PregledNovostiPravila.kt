package si.safeer.tv.os

/**
 * Pravila za pregled novosti v ozadju.
 *
 * Lastnik 10. 11. 2026: Medijski center naj v ozadju preveri, ali je na voljo NOV film ali serija
 * in ali kateri naslov NI VEC na voljo.
 * Vsak vir ima svoj filter; pregled preverja samo trenutno izbrani vir brez dodatnih poizvedb drugim virom.
 */
object PregledNovostiPravila {
    const val VSAKIH_MS = 6 * 60 * 60 * 1000L
    const val PONOVI_MS = 30 * 60 * 1000L
    const val NAJVEC_NA_KROG = 40
    const val NAJVEC_ZNANIH = 500
    const val NAJVEC_V_OBVESTILU = 3

    /**
     * Preveri, ali je na vrsti za nov krog pregleda.
     */
    fun naVrsti(zadnjiPoln: Long, zadnjiPoskus: Long, zdaj: Long, predvaja: Boolean, omrezje: Boolean, pritisk: Boolean): Boolean {
        // Nikoli med predvajanjem, brez omrezja ali ob pritisku pomnilnika
        if (predvaja || !omrezje || pritisk) return false
        // Vsak cas posebej: 0 = se ga ni bilo, ura nazaj = na vrsti. Pregled 10. 11. 2026: prej je »poln == 0« preskocil
        // razmik med poskusi - pregled, ki se nikoli ne konca (dodatek ne odgovarja), bi se ponavljal ob vsakem klicu.
        fun starVsaj(cas: Long, ms: Long) = cas <= 0L || zdaj - cas !in 0 until ms
        val polnStar = starVsaj(zadnjiPoln, VSAKIH_MS)
        val poskusStar = starVsaj(zadnjiPoskus, PONOVI_MS)
        return polnStar && poskusStar
    }

    /**
     * Pripravi naslove za pregled - brez praznih, ponovitev, ze znanih.
     */
    fun kandidati(katalog: List<String>, znani: Set<String>, najvec: Int = NAJVEC_NA_KROG): List<String> {
        val rezultat = mutableListOf<String>()
        val videno = mutableSetOf<String>()
        for (k in katalog) {
            if (rezultat.size >= najvec) break
            if (k.isNotEmpty() && !znani.contains(k) && videno.add(k)) {
                rezultat.add(k)
            }
        }
        return rezultat
    }

    enum class Izid { NA_VOLJO, NI, NE_VEMO }

    /**
     * Uveljavi izide po krogu: NA_VOLJO se doda, NI se odstrani, NE_VEMO ne vpliva.
     */
    fun novoStanje(prej: Set<String>, izidi: Map<String, Izid>): Set<String> {
        val novo = prej.toMutableSet()
        for ((naslov, izid) in izidi) {
            when (izid) {
                Izid.NA_VOLJO -> novo.add(naslov)
                Izid.NI -> novo.remove(naslov)
                Izid.NE_VEMO -> {} // ostane kot je bilo
            }
        }
        return novo
    }

    /**
     * Po abecedi urejeni res novi naslovi (ob prvem pregledu prazno).
     */
    fun novosti(prej: Set<String>, potem: Set<String>, prvic: Boolean): List<String> {
        if (prvic) return emptyList()
        return potem.filter { !prej.contains(it) }.sorted()
    }

    /**
     * Naslovi, ki imajo potrjen izid NI.
     */
    fun izginuli(prej: Set<String>, izidi: Map<String, Izid>): Set<String> {
        return prej.filter { izidi[it] == Izid.NI }.toSet()
    }

    data class Obvestilo(val naslovi: List<String>, val seVec: Int)

    /**
     * Omejeno obvestilo o novostih za uporabnika (null, ce ni nicesar).
     */
    fun obvestilo(novi: List<String>, najvec: Int = NAJVEC_V_OBVESTILU): Obvestilo? {
        if (novi.isEmpty()) return null
        val naslovi = novi.take(najvec)
        val seVec = novi.size - naslovi.size
        return Obvestilo(naslovi, seVec)
    }

    data class Stanje(
        val zadnjiPoln: Long = 0L,
        val zadnjiPoskus: Long = 0L,
        val naVoljo: Set<String> = emptySet(),
        val prvic: Boolean = true
    )

    /**
     * Zapis stanja, ki zdrzi posebne znake v kljucih (uporabi dolzino pred kljucem).
     */
    fun vNiz(s: Stanje): String {
        val n = s.naVoljo.sorted().takeLast(NAJVEC_ZNANIH)
        val sb = StringBuilder()
        sb.append("${s.zadnjiPoln}\n${s.zadnjiPoskus}\n${s.prvic}\n")
        for (k in n) {
            sb.append("${k.length} $k")
        }
        return sb.toString()
    }

    /**
     * Branje stanja iz zapisa (obvaruje pred pokvarjenimi nizi).
     */
    fun izNiza(n: String?): Stanje {
        if (n.isNullOrEmpty()) return Stanje()
        try {
            val prvi = n.indexOf('\n')
            if (prvi == -1) return Stanje()
            val drugi = n.indexOf('\n', prvi + 1)
            if (drugi == -1) return Stanje()
            val tretji = n.indexOf('\n', drugi + 1)
            if (tretji == -1) return Stanje()

            val zadnjiPoln = n.substring(0, prvi).toLong()
            val zadnjiPoskus = n.substring(prvi + 1, drugi).toLong()
            val prvic = n.substring(drugi + 1, tretji) == "true"

            var ostanek = n.substring(tretji + 1)
            val naVoljo = mutableSetOf<String>()
            while (ostanek.isNotEmpty()) {
                val presledek = ostanek.indexOf(' ')
                if (presledek == -1) break
                val dolzinaStr = ostanek.substring(0, presledek)
                val dolzina = dolzinaStr.toInt()
                val zacetek = presledek + 1
                if (zacetek + dolzina > ostanek.length) break
                val kljuc = ostanek.substring(zacetek, zacetek + dolzina)
                naVoljo.add(kljuc)
                ostanek = ostanek.substring(zacetek + dolzina)
            }
            
            val konco = naVoljo.sorted().takeLast(NAJVEC_ZNANIH).toSet()
            return Stanje(zadnjiPoln, zadnjiPoskus, konco, prvic)
        } catch (e: Exception) {
            return Stanje()
        }
    }
}
