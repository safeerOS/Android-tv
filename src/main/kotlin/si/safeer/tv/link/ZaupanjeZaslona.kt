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
        // Pregled dex-a U4: »Vedno dovoli« samo napravi iz ozjega kroga, nikoli tej napravi.
        if (da && !DeljenjePravila.smeZaupati(vKrogu(ctx, id), try { si.safeer.tv.cast.Dostop.jeTaNaprava(ctx, id) } catch (_: Throwable) { true })) return
        synchronized(this) {
            val novo = HashSet(zapis(ctx))
            val spremenjeno = if (da) novo.add(j) else novo.remove(j)
            if (!spremenjeno) return
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KLJUC, novo).apply()
        }
        Log.i(TAG, "Deljenje zaslona napravi ${si.safeer.tv.cast.E2e.zakrij(j)}: ${if (da) "vedno dovoli" else "vsakic vprasaj"}")
        // Naloga 42 D: izbira velja na vseh napravah ozjega kroga (usklajevanje kljucev, sifrirano).
        try { si.safeer.tv.os.UskladitevKljucev.sprozi(ctx.applicationContext) } catch (_: Throwable) { }
    }

    /** Naloga 42 D: jedra naprav z »Vedno dovoli« (za usklajevanje v ozjem krogu; brez preverbe kroga - to naredi klicatelj). */
    fun vsaJedra(ctx: Context): Set<String> = zapis(ctx)

    /** Naprava z jedrom [jedro] je v ozjem krogu te naprave (ta naprava sama ne steje). */
    fun jedroVKrogu(ctx: Context, jedro: String): Boolean = try {
        jedro.isNotBlank() && !jeTaNapravaJedro(ctx, jedro) && OzjiKrogPravila.vKrogu(si.safeer.tv.cast.Dostop.zmoznostiJedra(ctx, jedro), false)
    } catch (_: Throwable) { false }

    /** Jedro je ta naprava (zaupanja si sama ne da). Napaka pri preverbi = ta naprava (nic se ne zapise). */
    fun jeTaNapravaJedro(ctx: Context, jedro: String): Boolean =
        try { si.safeer.tv.cast.Dostop.jeTaNaprava(ctx, jedro) } catch (_: Throwable) { true }

    /**
     * Naloga 42 D: zaupanje, prejeto od druge naprave ozjega kroga (UskladitevKljucev, preverjeno z
     * NapraveKrogaPravila.sprejmiZaupanje), se zapise tu po jedru - brez ponovne potrditve, kot ce bi ga dal uporabnik tu.
     */
    fun nastaviJedro(ctx: Context, jedro: String, da: Boolean) {
        if (jedro.isBlank()) return
        if (da && !jedroVKrogu(ctx, jedro)) return
        synchronized(this) {
            val novo = HashSet(zapis(ctx))
            val spremenjeno = if (da) novo.add(jedro) else novo.remove(jedro)
            if (!spremenjeno) return
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KLJUC, novo).apply()
        }
        Log.i(TAG, "Deljenje zaslona napravi ${si.safeer.tv.cast.E2e.zakrij(jedro)}: ${if (da) "vedno dovoli" else "vsakic vprasaj"} (ozji krog)")
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
        // Pregled dex-a U1: napaka ali neznan nacin = kot da trajno dovoljenje je (Safeer vprasa sam in zahteva viden znak);
        // pod API 29 checkOpNoThrow (od API 19), ne vec takojsnji »ne«.
        val nacin: Int? = try {
            val ao = ctx.getSystemService(android.app.AppOpsManager::class.java)
            if (ao == null) null
            else if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), ctx.packageName)
            else @Suppress("DEPRECATION") ao.checkOpNoThrow("android:project_media", android.os.Process.myUid(), ctx.packageName)
        } catch (e: IllegalArgumentException) {
            // Grok 15, N14: pod API 29 sistem, ki opa ne pozna, trajnega dovoljenja nima - sistemsko okno pride vedno (ne dve okni).
            if (Build.VERSION.SDK_INT < 29) android.app.AppOpsManager.MODE_DEFAULT else null
        } catch (_: Throwable) { null }
        return DeljenjePravila.projekcijaPoNacinu(nacin)
    }

    /** Pregled dex-a U7: zaupanje napravam, ki niso vec v ozjem krogu, se izbrise (po spremembi kroga ali dostopa). */
    fun pocisti(ctx: Context) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val prej = zapis(ctx)
            if (prej.isEmpty()) return
            val ostane = DeljenjePravila.zaupanjaVKrogu(prej) { j ->
                OzjiKrogPravila.vKrogu(si.safeer.tv.cast.Dostop.zmoznostiJedra(ctx, j), false)
            }
            if (ostane == prej) return
            prefs.edit().putStringSet(KLJUC, HashSet(ostane)).apply()
        }
        Log.i(TAG, "Zaupanje za deljenje zaslona izbrisano napravam zunaj ozjega kroga.")
    }
}
