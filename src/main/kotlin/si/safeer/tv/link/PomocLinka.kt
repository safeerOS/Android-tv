package si.safeer.tv.link

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import si.safeer.tv.R

/**
 * »Pomagaj drugim napravam v Linku pri povezavi« na tej napravi (isto kot core/link_mesh.py na racunalniku). Pomoc je
 * v kodi natanko dvoje (docs/LINK-MESH.md, »Dostava prek vmesnega vozlisca«): Hub te naprave sosedom predstavi naprave
 * svojih neposrednih sosedov (`relay` v mesh.devices) in sporocilo `mesh.route` z `relay: true` preda naprej (en skok,
 * samo med clani kroga, vsebina ostane sifrirana do cilja). Privzeto vklopljeno; uporabnika enkrat vprasamo ob
 * seznanitvi (fokus na »Da, pomagaj«), stikalo je v Nastavitvah. Izklop velja takoj (HubUsmerjevalnik.osveziPomoc).
 */
object PomocLinka {
    private const val TAG = "SafeerLinkPomoc"
    private const val PREFS = "safeer_link_pomoc"
    private const val KLJUC = "zapis"

    /** Hub te naprave (HubKrmilnik) ob spremembi sosedom takoj poslje nov seznam. */
    @Volatile var naSpremembo: (() -> Unit)? = null

    fun stanje(c: Context): PomocPravila.Stanje = PomocPravila.izZapisa(
        try { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC, null) } catch (_: Throwable) { null })

    fun vklopljena(c: Context): Boolean = stanje(c).vklopljeno

    /** Uporabnikova izbira (Nastavitve ali vprasanje ob seznanitvi): velja takoj, vprasanje se ne ponovi. */
    fun shrani(c: Context, vklop: Boolean) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KLJUC, PomocPravila.vZapis(vklop)).apply()
        Log.i(TAG, "Pomoc drugim napravam pri povezavi: ${if (vklop) "vklopljena" else "izklopljena"}")
        // Hub poslje sosedom po omrezju: ne na glavni niti.
        naSpremembo?.let { f -> Thread { try { f() } catch (e: Throwable) { Log.w(TAG, "Osvezitev Huba: ${e.message}") } }.start() }
    }

    /**
     * Ob seznanitvi enkrat vprasamo (privzeto da, fokus na »Da, pomagaj« - en pritisk na daljincu). Vrne okno, ce je
     * vprasanje odprto (sicer null); [potem] se poklice po odgovoru ali zaprtju okna.
     */
    fun vprasajEnkrat(a: Activity, potem: (() -> Unit)? = null): AlertDialog? {
        if (a.isFinishing || stanje(a).vprasano) return null
        var odgovor = false
        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.getString(R.string.os_pomoc_vprasanje))
            .setMessage(a.getString(R.string.os_pomoc_opis))
            .setPositiveButton(a.getString(R.string.os_pomoc_da)) { _, _ -> odgovor = true; shrani(a, true) }
            .setNegativeButton(a.getString(R.string.os_pomoc_ne)) { _, _ -> odgovor = true; shrani(a, false) }
            .setOnDismissListener { potem?.invoke() }
            .create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        return try {
            si.safeer.tv.os.Kontroler.pokazi(okno.also { it.show() })
            okno
        } catch (e: Throwable) {
            Log.w(TAG, "Vprasanja ni bilo mogoce pokazati: ${e.message}")
            if (!odgovor) potem?.invoke()
            null
        }
    }
}
