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
    fun izbrane(ctx: Context): List<DomovVrsticePravila.Vrstica> = DomovVrsticePravila.izbrane(
        try { ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC, null) } catch (_: Exception) { null })

    fun shrani(ctx: Context, izbrane: List<DomovVrsticePravila.Vrstica>) =
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, DomovVrsticePravila.vZapis(izbrane)).apply()

    /** Nazaj na privzete vrstice. */
    fun ponastavi(ctx: Context) = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().remove(KLJUC).apply()
}
