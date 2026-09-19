package si.safeer.tv.os

import android.content.Context
import si.safeer.tv.cast.HubKrmilnik

/**
 * Kako Safeer OS dela na tem televizorju.
 *
 *  - Ce Safeer Link ze tece v brskalniku (uporabnik ga je vklopil), Safeer OS vanj vstopi sam,
 *    brez kode in brez novega vprasanja - to je najbolj udobno in se zgodi brez [Nacin].
 *  - Ce Safeer Link ne tece, Safeer OS enkrat vprasa: vklopi Safeer Link (naprave, datoteke z
 *    racunalnika, daljinec) ali delaj KRAJEVNO - samo z viri televizorja (splet, njegove datoteke
 *    in USB, aplikacije, Scit). Izbira se zapomni in jo je mogoce kadarkoli spremeniti.
 */
object Nacin {
    private const val PREFS = "safeer_os"
    private const val KLJUC = "nacin"

    /** Se ni izbrano: ce sredisce ne tece, uporabnika enkrat vprasamo. */
    const val VPRASAJ = "vprasaj"
    /** Uporabnik zeli Safeer Link; ce ne tece, ga Safeer OS prizge. */
    const val LINK = "link"
    /** Brez Safeer Linka: samo viri tega televizorja. */
    const val KRAJEVNI = "krajevni"

    private fun p(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beri(context: Context): String = p(context).getString(KLJUC, VPRASAJ) ?: VPRASAJ

    fun nastavi(context: Context, nacin: String) {
        p(context).edit().putString(KLJUC, nacin).apply()
    }

    fun jeKrajevni(context: Context): Boolean = beri(context) == KRAJEVNI

    fun jeLink(context: Context): Boolean = beri(context) == LINK

    /** Sredisce Safeer Linka na tem televizorju ze tece (brskalnik ga gosti). */
    fun linkZeTece(): Boolean = try { HubKrmilnik.tece() } catch (_: Throwable) { false }

    /**
     * Ali je treba uporabnika vprasati: sredisce ne tece in se ni izbral. Ce ze tece, ne vprasamo
     * nikoli - povezava je ze tu.
     */
    fun vprasamo(context: Context): Boolean = !linkZeTece() && beri(context) == VPRASAJ
}
