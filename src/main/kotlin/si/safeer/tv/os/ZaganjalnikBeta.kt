package si.safeer.tv.os

import android.content.Context

/**
 * Novi TV zaganjalnik (tema Pokrajina, [PokrajinaActivity]). Od glavne izdaje 0.5.111 (lastnik 28. 10. 2026: »samo
 * novi«) je na televizorju vedno vklopljen; [DomovActivity] ga odpre takoj. Zapis [KLJUC] ostane le zaradi zdruzljivosti.
 */
object ZaganjalnikBeta {
    private const val SHRAMBA = "safeer_os"
    const val KLJUC = "zaganjalnik_beta"

    /** Samo na televizorju: telefon in tablica imata svoj domaci zaslon (ZaganjalnikActivity). */
    fun vklopljen(c: Context): Boolean = ZaganjalnikBetaPravila.vklopljen(Naprava.jeTelevizor(c))

    fun nastavi(c: Context, da: Boolean) {
        c.getSharedPreferences(SHRAMBA, Context.MODE_PRIVATE).edit().putBoolean(KLJUC, da).apply()
    }
}
