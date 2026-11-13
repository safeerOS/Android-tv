package si.safeer.tv.os

/**
 * Ujemanje osvezevanja zaslona s filmom (lastnik 13. 11. 2026, ideja iz IPVIO Stream; kode nismo prevzeli). Film na
 * 23,976/24 sl./s na 60 Hz zaslonu se trza (vzorec 3:2). Media3 sam poklice Surface.setFrameRate samo, ce je preklop
 * neviden (ONLY_IF_SEAMLESS) - vecina TV med 60 in 24 Hz ne preklopi brez kratke teme, zato preklop naredimo z izbiro
 * nacina zaslona (WindowManager.LayoutParams.preferredDisplayModeId, kot Kodi) ob isti locljivosti. Cista logika.
 */
object OsvezevanjePravila {
    const val KLJUC_OSVEZEVANJE = "ujemanje_osvezevanja"

    data class Nacin(val id: Int, val sirina: Int, val visina: Int, val hz: Float)

    /** Hitrost slik, ki jo sploh ujemamo (neznana = Format.NO_VALUE -1, nenavadne ne). Velja tudi za tok v zivo. */
    fun veljavnaHitrost(fps: Float): Boolean = fps in 10f..121f

    /** Tocno ujemanje (23,976 Hz za 23,976 fps) in priblizno (24 Hz za 23,976: ena ponovljena slika na ~42 s). */
    private const val TOCNO = 0.0005f
    private const val PRIBLIZNO = 0.0011f

    private fun napaka(hz: Float, fps: Float): Pair<Int, Float>? {
        val k = Math.round(hz / fps)
        if (k < 1 || k > 5) return null
        return k to Math.abs(hz - k * fps) / hz
    }

    /**
     * Najboljsi nacin za [fps] pri locljivosti [izvirni]: najprej tocno ujemanje, sicer priblizno; med njimi najnizji
     * veckratnik. Null = izvirni nacin ze ustreza (ni trzanja) ali ni boljsega (neodvisni pregled 13. 11. 2026, N8).
     */
    fun izberi(nacini: List<Nacin>, izvirni: Nacin, fps: Float): Nacin? {
        if (!veljavnaHitrost(fps)) return null
        val enaki = nacini.filter { it.sirina == izvirni.sirina && it.visina == izvirni.visina }
        val izvirnaNapaka = napaka(izvirni.hz, fps)?.second
        for (meja in floatArrayOf(TOCNO, PRIBLIZNO)) {
            // Izvirni nacin ze ustreza na tej ravni (30 na 60 Hz): preklop bi le zatemnil zaslon.
            if (izvirnaNapaka != null && izvirnaNapaka <= meja) return null
            val kandidati = enaki.mapNotNull { n -> napaka(n.hz, fps)?.takeIf { it.second <= meja }?.let { n to it } }
            if (kandidati.isNotEmpty())
                return kandidati.minWith(compareBy<Pair<Nacin, Pair<Int, Float>>>({ it.second.first }, { it.second.second })).first
        }
        return null
    }
}
