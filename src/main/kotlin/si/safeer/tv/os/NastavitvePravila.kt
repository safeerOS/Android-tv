package si.safeer.tv.os

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Ciste odlocitve iz pregleda Nastavitev in Scita (5. 11. 2026): meje branja (ozadje, manifest posodobitev), preverba
 * vsakega skoka preusmeritve, fokus seznama Nastavitev in predpomnilnik diagnostike. Brez Androida; preizkus:
 * tests/NastavitvePravilaTest.kt (tests/run_os_tests.sh).
 */
object NastavitvePravila {
    /** Lastna slika ozadja: vec ne preberemo (prej readBytes brez meje - OOM na TV z 2 GB). */
    const val MEJA_SLIKE_OZADJA = 20L * 1024 * 1024
    /** os/razlicice.json ima ~6 kB (izmerjeno 5. 11. 2026); 256 kB je dovolj za vse pakete. */
    const val MEJA_MANIFESTA = 256L * 1024
    /** Najvec skokov preusmeritve pri prenosu APK (safeer.si odgovori brez preusmeritve, 5. 11. 2026). */
    const val NAJVEC_SKOKOV = 5

    /**
     * Prebere tok do konca, a najvec [meja] bajtov, steje sproti (ne zaupa Content-Length). Null, ce je tok daljsi -
     * klicatelj takrat ne dobi nicesar (prej je readBytes() bral do konca pomnilnika).
     */
    fun beriZMejo(vhod: InputStream, meja: Long): ByteArray? {
        val izhod = ByteArrayOutputStream()
        val b = ByteArray(64 * 1024)
        var skupaj = 0L
        while (true) {
            val n = vhod.read(b)
            if (n < 0) break
            if (n == 0) continue
            skupaj += n
            if (skupaj > meja) return null
            izhod.write(b, 0, n)
        }
        return izhod.toByteArray()
    }

    fun jePreusmeritev(koda: Int): Boolean = koda == 301 || koda == 302 || koda == 303 || koda == 307 || koda == 308

    /**
     * Naslednji skok preusmeritve ([lokacija] iz glave Location, lahko relativna) od [trenutni]: samo https z imenom
     * gostitelja, sicer null (prenos se ustavi). Prej je HttpURLConnection sledil sam in preverjen je bil samo zadnji naslov.
     */
    fun naslednjiSkok(trenutni: String, lokacija: String?): String? =
        // Ista preverba kot prenos APK (drugi krog pregleda 5. 11. 2026, os/Preusmeritve) - prej lastna izvedba z URI.
        (Preusmeritve.naslednji(trenutni, lokacija) as? Preusmeritve.Skok.Naprej)?.url

    /**
     * Seznam Nastavitev vzame fokus samo ob prvem izrisu ali ce ga nima nihce - prej ga je vsak asinhroni izris (stanje
     * Scita, posodobitve, Link) vzel stranski vrstici ali iskanju, kamor je uporabnik ravno sel.
     */
    fun seznamVzameFokus(prviIzris: Boolean, kdoImaFokus: Boolean, iskanjeImaFokus: Boolean): Boolean =
        !iskanjeImaFokus && (prviIzris || !kdoImaFokus)

    /**
     * Zadnja prebrana vrednost lastnosti z rokom (diagnostika: getprop je nov proces, prej ob vsaki izbiri toka na glavni
     * niti). [preberi] vrne zadnjo znano vrednost (null = se ni prebrana) in pove, ali naj klicatelj zazene novo branje v
     * ozadju (rok je potekel in branje se ne tece). [shrani] zapise izid branja.
     */
    class PredpomnjenaLastnost(private val rokMs: Long) {
        private class Vnos(val vrednost: String?, val cas: Long)
        private val vrednosti = HashMap<String, Vnos>()
        private val vTeku = HashSet<String>()

        @Synchronized fun preberi(ime: String, zdaj: Long): Pair<String?, Boolean> {
            val v = vrednosti[ime]
            val beri = (v == null || zdaj - v.cas >= rokMs || zdaj < v.cas) && vTeku.add(ime)
            return (v?.vrednost) to beri
        }

        @Synchronized fun shrani(ime: String, vrednost: String?, zdaj: Long) {
            vrednosti[ime] = Vnos(vrednost, zdaj)
            vTeku.remove(ime)
        }
    }
}
