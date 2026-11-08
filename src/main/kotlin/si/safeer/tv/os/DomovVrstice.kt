package si.safeer.tv.os

import android.content.Context

/**
 * Shramba izbire vrstic Doma Medijskega centra (katere in v kaksnem redu) - en modul za Dom in za Nastavitve Medijskega
 * centra (lastnik 6. 11. 2026 04:56: urejanje je v Nastavitvah). Odlocitve so v [DomovVrsticePravila]. Samo na tej
 * napravi: nastavitve Medijskega centra se v ozjem krogu (6. 11. 2026) ne usklajujejo - [SeznamiSink] prenasa samo
 * sezname predvajanja in Moje vire.
 */
object DomovVrstice {
    private const val NASTAVITVE = "safeer_mediji"
    private const val KLJUC = "dom_vrstice"

    /** Izbrane vrstice v uporabnikovem redu; brez shranjene izbire privzete ([DomovVrsticePravila.PRIVZETO]). */
    private const val MOJ_SEZNAM_PONUJEN = "dom_vrstice_moj_seznam"

    fun izbrane(ctx: Context): List<DomovVrsticePravila.Vrstica> {
        val n = try { ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        val zapis = try { n?.getString(KLJUC, null) } catch (_: Exception) { null }
        val izbrane = DomovVrsticePravila.izbrane(zapis)
        // Slepi pregled 8. 11. (8): Moj seznam je nov - kdor je vrstice ze uredil, ga enkrat dobi za Nadaljuj z ogledom;
        // ce ga potem skrije, ostane skrit (zastavica).
        // Drugi pregled (1): selitev samo za ze shranjeno izbiro z vrsticami (ne »vse izklopljeno«); privzeto ga ze ima.
        if (n != null && !n.getBoolean(MOJ_SEZNAM_PONUJEN, false)) {
            if (zapis.isNullOrEmpty()) { n.edit().putBoolean(MOJ_SEZNAM_PONUJEN, true).apply(); return izbrane }
            val nove = DomovVrsticePravila.dodajNovo(izbrane, DomovVrsticePravila.Vrstica.MOJ_SEZNAM, DomovVrsticePravila.Vrstica.NADALJUJ_Z_OGLEDOM)
            n.edit().putString(KLJUC, DomovVrsticePravila.vZapis(nove)).putBoolean(MOJ_SEZNAM_PONUJEN, true).apply()
            return nove
        }
        return izbrane
    }

    /** Uporabnikova izbira: po njej selitev Mojega seznama ne velja vec (sicer bi skrito vrstico vrnila). */
    fun shrani(ctx: Context, izbrane: List<DomovVrsticePravila.Vrstica>) =
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, DomovVrsticePravila.vZapis(izbrane))
            .putBoolean(MOJ_SEZNAM_PONUJEN, true).apply()

    /** Nazaj na privzete vrstice. */
    fun ponastavi(ctx: Context) = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().remove(KLJUC).apply()
}
