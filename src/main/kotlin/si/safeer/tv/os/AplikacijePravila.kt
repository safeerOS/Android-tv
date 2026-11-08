package si.safeer.tv.os

object AplikacijePravila {
    enum class DejanjeOdstranitve { ODSTRANI, ONEMOGOCI, ODSTRANI_POSODOBITVE }

    /**
     * Dejanja za paket v meniju dolgega pritiska (lastnik 8. 11. 2026, TV: Trgovina Play - posodobljena sistemska - je
     * ponudila samo »Odstrani aplikacijo«, ki pri sistemski odstrani le posodobitve; onemogociti je ni bilo mogoce):
     * - uporabniska: ODSTRANI;
     * - sistemska brez posodobitev: ONEMOGOCI (sistemske nastavitve aplikacije, Android je ne odstrani);
     * - posodobljena sistemska: ONEMOGOCI in ODSTRANI_POSODOBITVE (Android odstrani samo posodobitev, tovarniska ostane).
     */
    fun dejanjaPaketa(jeSistemska: Boolean, jePosodobljenaSistemska: Boolean): List<DejanjeOdstranitve> = when {
        !jeSistemska -> listOf(DejanjeOdstranitve.ODSTRANI)
        !jePosodobljenaSistemska -> listOf(DejanjeOdstranitve.ONEMOGOCI)
        else -> listOf(DejanjeOdstranitve.ONEMOGOCI, DejanjeOdstranitve.ODSTRANI_POSODOBITVE)
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
