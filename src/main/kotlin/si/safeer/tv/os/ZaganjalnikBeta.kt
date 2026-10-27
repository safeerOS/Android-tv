package si.safeer.tv.os

import android.content.Context

/**
 * Naloga 42 (27. 10. 2026): nov TV zaganjalnik (tema Pokrajina, [PokrajinaActivity]) je beta - vklopi ga uporabnik sam,
 * stari domaci zaslon ([DomovActivity]) ostane in je vedno en preklop stran. Izbira je ena vrednost v isti shrambi kot
 * tema; nobena druga nastavitev se ob preklopu ne spremeni ali izbrise.
 */
object ZaganjalnikBeta {
    private const val SHRAMBA = "safeer_os"
    const val KLJUC = "zaganjalnik_beta"

    /** Samo na televizorju: telefon in tablica imata svoj domaci zaslon (ZaganjalnikActivity). */
    fun vklopljen(c: Context): Boolean =
        Naprava.jeTelevizor(c) && c.getSharedPreferences(SHRAMBA, Context.MODE_PRIVATE).getBoolean(KLJUC, false)

    fun nastavi(c: Context, da: Boolean) {
        c.getSharedPreferences(SHRAMBA, Context.MODE_PRIVATE).edit().putBoolean(KLJUC, da).apply()
    }
}
