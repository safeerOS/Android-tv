package si.safeer.tv.link

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * »Vedno dovoli temu racunalniku« za deljenje zaslona (nacrt »dex-upravljanje« A3) in zaznava trajnega dovoljenja
 * Androida za prikaz (AppOps PROJECT_MEDIA). Zapis je samo na tej napravi, po jedru naprave (cast/Dostop.jedro - isto
 * za vse programe iste naprave), in velja samo, dokler je naprava v ozjem krogu ([OzjiKrogPravila]). Pravila:
 * [DeljenjePravila].
 *
 * Kaj zaupanje spremeni: po odklepu se deljenje tej napravi nadaljuje samo (A4); ce ima Safeer trajno dovoljenje
 * Androida, je zaupana naprava edina, ki zaslon dobi brez vprasanja - vsako drugo vprasa Safeer sam. Sistemsko okno
 * Androida brez trajnega dovoljenja ostane: to zahteva Android, ne Safeer.
 */
object ZaupanjeZaslona {
    private const val TAG = "SafeerZaupanjeZaslona"
    private const val PREFS = "safeer_zaupanje_zaslona"
    private const val KLJUC = "vedno"

    private fun jedro(ctx: Context, id: String): String =
        if (id.isBlank()) "" else try { si.safeer.tv.cast.Dostop.jedro(ctx, id) } catch (_: Throwable) { "" }

    private fun zapis(ctx: Context): Set<String> =
        try { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KLJUC, null).orEmpty() }
        catch (_: Throwable) { emptySet() }

    /** Uporabnik je napravi [id] rekel »Vedno dovoli« (ne pove, ali je se v ozjem krogu - to pove [zaupano]). */
    fun vedno(ctx: Context, id: String): Boolean {
        val j = jedro(ctx, id)
        return j.isNotEmpty() && j in zapis(ctx)
    }

    fun nastavi(ctx: Context, id: String, da: Boolean) {
        val j = jedro(ctx, id)
        if (j.isEmpty()) return
        synchronized(this) {
            val novo = HashSet(zapis(ctx))
            val spremenjeno = if (da) novo.add(j) else novo.remove(j)
            if (!spremenjeno) return
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KLJUC, novo).apply()
        }
        Log.i(TAG, "Deljenje zaslona napravi ${si.safeer.tv.cast.E2e.zakrij(j)}: ${if (da) "vedno dovoli" else "vsakic vprasaj"}")
    }

    fun vKrogu(ctx: Context, id: String): Boolean = try {
        OzjiKrogPravila.vKrogu(si.safeer.tv.cast.Dostop.zmoznosti(ctx, id), si.safeer.tv.cast.Dostop.jeTaNaprava(ctx, id))
    } catch (_: Throwable) { false }

    fun zaupano(ctx: Context, id: String): Boolean = DeljenjePravila.zaupano(vedno(ctx, id), vKrogu(ctx, id))

    /** Ali v obvestilu deljenja ponudimo »Vedno dovoli«. */
    fun ponudi(ctx: Context, id: String): Boolean = DeljenjePravila.ponudiZaupanje(vKrogu(ctx, id), vedno(ctx, id),
        try { si.safeer.tv.cast.Dostop.jeTaNaprava(ctx, id) } catch (_: Throwable) { false })

    /**
     * Ali ima Safeer trajno dovoljenje Androida za prikaz zaslona: AppOps PROJECT_MEDIA = allow (nastavi ga lahko samo
     * lupina adb; nacrt B1). Takrat SystemUI okna za soglasje ne pokaze (MediaProjectionPermissionActivity:
     * hasProjectionPermission). Samo branje (checkOp, ne noteOp) - nic se ne zapise v zgodovino dostopov.
     */
    fun projekcijaDovoljena(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val ao = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return false
            ao.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), ctx.packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
    }
}
