package si.safeer.tv.os

object AplikacijePravila {
    enum class DejanjeOdstranitve { ODSTRANI, ONEMOGOCI }

    /**
     * Določi, katero dejanje ponuditi za paket:
     * - ONEMOGOCI za sistemske aplikacije, ki niso bile posodobljene.
     * - ODSTRANI za vse ostale (uporabniške ali posodobljene sistemske).
     */
    fun dejanjeOdstranitve(jeSistemska: Boolean, jePosodobljenaSistemska: Boolean): DejanjeOdstranitve {
        return if (jeSistemska && !jePosodobljenaSistemska) DejanjeOdstranitve.ONEMOGOCI else DejanjeOdstranitve.ODSTRANI
    }

    /**
     * Mera slike ikone ali pasice: najvec [meja] px po daljsi stranici, razmerje ostane (preverba TV 4. 11. 2026: pasica
     * F-Droid 640x360 je postala 512x360 in se obrezala). Nikoli 0 px.
     */
    /**
     * Preverba TV 4. 11. 2026 (drugic): pasica F-Droid je prilagodljiva ikona (adaptive-icon v mipmap-anydpi-v26, bela
     * podlaga + vektor 320x180 dp z napisom). Kot celota je kvadrat 108 dp z masko - risana tako je dala »F-Droi«. Ce je
     * ospredje siroko, narisemo pasico v meri ospredja; vrne mero ali null (ni siroko ospredje, uporabi obicajno pot).
     */
    fun meraPrilagodljivePasice(ospredjeSirina: Int, ospredjeVisina: Int, meja: Int): Pair<Int, Int>? =
        if (ospredjeSirina <= 0 || ospredjeVisina <= 0 || ospredjeSirina <= ospredjeVisina) null
        else meraSlike(ospredjeSirina, ospredjeVisina, meja)

    fun meraSlike(sirina: Int, visina: Int, meja: Int): Pair<Int, Int> {
        val s = sirina.coerceAtLeast(1)
        val v = visina.coerceAtLeast(1)
        if (s <= meja && v <= meja) return s to v
        val k = meja.toDouble() / maxOf(s, v)
        return Math.round(s * k).toInt().coerceAtLeast(1) to Math.round(v * k).toInt().coerceAtLeast(1)
    }
}
