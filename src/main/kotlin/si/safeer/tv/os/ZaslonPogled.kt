package si.safeer.tv.os

/**
 * Povrsina, na kateri bo slika oddaljenega zaslona. Naprava jo pove racunalniku (polje `view` v `screen.start`),
 * ta pa po njej oblikuje loceni zaslon za programe: slika zapolni cel zaslon naprave, brez crnih robov in brez
 * prevzorcenja. Pravi zaslon racunalnika se zaradi tega ne spremeni. Brez Androida, da ga preveri JVM preizkus.
 */
object ZaslonPogled {
    const val NAJMANJ_DOLGA = 640
    const val NAJMANJ_KRATKA = 360
    const val NAJVEC_DOLGA = 3840
    const val NAJVEC_KRATKA = 2160

    /** Zmanjsanja, ki jih poskusimo, ce dekodirnik naprave polne velikosti pri polni hitrosti ne zmore. */
    private val KORAKI = floatArrayOf(1f, 0.9f, 0.8f, 0.75f, 2f / 3f, 0.6f, 0.5f)

    /**
     * Velikost slike za povrsino [w] x [h] (tocke): najvecja z istim razmerjem in sodima stranicama, ki jo dekodirnik
     * [zmore]. Null, ce povrsina ni smiselna ali dekodirnik ne zmore nobene - takrat `view` ne posljemo in racunalnik
     * uporabi svojo privzeto velikost (kot pri starejsi napravi).
     */
    fun velikost(w: Int, h: Int, zmore: (Int, Int) -> Boolean): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        for (k in KORAKI) {
            val sw = (w * k).toInt() / 2 * 2
            val sh = (h * k).toInt() / 2 * 2
            val dolga = maxOf(sw, sh)
            val kratka = minOf(sw, sh)
            if (dolga > NAJVEC_DOLGA || kratka > NAJVEC_KRATKA) continue
            if (dolga < NAJMANJ_DOLGA || kratka < NAJMANJ_KRATKA) return null
            if (zmore(sw, sh)) return sw to sh
        }
        return null
    }

    /**
     * Povrsina za sliko v celozaslonskem nacinu: okno [sirina] x [visina] brez izreza kamere (robovi [levo],
     * [zgoraj], [desno], [spodaj] v trenutni legi naprave). Vrne (daljsa, krajsa) stranica - gledalec je lezec.
     * Sistemskih vrstic ne odstevamo: gledalec jih skrije; ob zacetku seje so lahko se vidne in mere postavitve
     * bi bile premajhne.
     */
    fun povrsina(sirina: Int, visina: Int, levo: Int, zgoraj: Int, desno: Int, spodaj: Int): Pair<Int, Int> {
        val w = sirina - maxOf(0, levo) - maxOf(0, desno)
        val h = visina - maxOf(0, zgoraj) - maxOf(0, spodaj)
        return maxOf(w, h) to minOf(w, h)
    }

    /** Merilo vsebine locenega zaslona, ki ga sme izbrati uporabnik: po cetrtinah med 1 in 3 (kot racunalnik). */
    const val KORAK_MERILA = 0.25f
    const val NAJVECJE_MERILO = 3f

    fun merilo(zeljeno: Float): Float =
        if (zeljeno.isNaN()) 1f else (Math.round(zeljeno / KORAK_MERILA) * KORAK_MERILA).coerceIn(1f, NAJVECJE_MERILO)

    /** Vrsta naprave za racunalnik: televizor gledamo od dalec (brez merila), telefon ima gost majhen zaslon. */
    fun vrsta(naDotik: Boolean, najmanjsaSirinaDp: Int): String =
        if (!naDotik) "tv" else if (najmanjsaSirinaDp >= 600) "tablet" else "phone"
}
