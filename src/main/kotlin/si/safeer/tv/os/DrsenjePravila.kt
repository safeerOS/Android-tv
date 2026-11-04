package si.safeer.tv.os

/**
 * Navpicno drsenje Medijskega centra z daljincem (preverba TV 4. 11. 2026: v TV v zivo in mrezah je bila ob drsenju prva
 * vrstica kartic zgoraj odrezana pod glavo). ScrollView se pomakne najmanj, kolikor je treba - zgoraj ostane kos vrste.
 * Ciste odlocitve, brez Androida (preizkus: tests/DrsenjePravilaTest.kt).
 */
object DrsenjePravila {
    /**
     * Nov odmik drsnika: med vrhovi vrst (in naslovov) [vrhovi], 0 in koncem [najvec] izbere tistega, pri katerem je
     * izbrana kartica ([vrh]..[dno]) cela na zaslonu visine [visina], najblizje odmiku, ki ga je predlagal ScrollView
     * ([zelen]). Zgoraj je tako vedno zacetek vrste. Ce noben ne gre (vrsta visja od zaslona), ostane [zelen].
     */
    fun poravnajNaVrsto(vrhovi: List<Int>, zelen: Int, vrh: Int, dno: Int, visina: Int, najvec: Int): Int {
        if (visina <= 0) return zelen
        val kandidati = (vrhovi + 0 + najvec).filter { it in 0..najvec && it <= vrh && it + visina >= dno }
        return kandidati.minByOrNull { kotlin.math.abs(it - zelen) } ?: zelen
    }
}
