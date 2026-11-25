package si.safeer.tv.os

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Glava toka zaslona racunalnika (ena vrstica JSON do \n, core/link_zaslon.py glava_toka).
 *
 * 25. 11. 2026: prejsnja meja 512 znakov je odrezala glavo racunalnika (profil + opis + »kazalec« = vec kot 512 bajtov)
 * -> »Unterminated string at character 512« in seja se ni nikoli zacela (TV: »Povezava je padla. Poskusam znova«).
 * Zdaj: meja NAJVEC (zascita pred pretokom brez \n), vrstica brez konca v meji je napaka (ostanek bi se bral kot
 * slika), bajti se dekodirajo kot UTF-8 (opis profila ima »×« in »·«; prej je bil vsak bajt svoj znak).
 */
object ZaslonGlava {
    const val NAJVEC = 16_384

    fun preberi(vhod: InputStream, najvec: Int = NAJVEC): String {
        val b = ByteArrayOutputStream(512)
        while (true) {
            val z = vhod.read()
            if (z < 0 || z == '\n'.code) break
            if (b.size() >= najvec) throw IOException("glava toka daljsa od $najvec bajtov")
            b.write(z)
        }
        return String(b.toByteArray(), Charsets.UTF_8)
    }
}
