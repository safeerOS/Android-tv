package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Priljubljene aplikacije televizorja. Na domacem zaslonu je bila prej vrsta z **vsemi**
 * aplikacijami: na tem televizorju jih je toliko, da se je vrsta lomila cez rob zaslona in med
 * njimi ni bilo mogoce nic najti. Zato na domacem zaslonu stojijo samo tiste, ki jih je uporabnik
 * izbral, in v vrstnem redu, ki si ga sam doloci; vse ostalo je za karticio "Ostalo".
 *
 * Shranimo imena paketov, ne ikon: ce uporabnik aplikacijo odstrani, preprosto izpade.
 */
object Priljubljene {
    private const val TAG = "SafeerOsPriljubljene"
    private const val PREFS = "safeer_os"
    private const val KLJUC = "priljubljene_tv"
    private const val KLJUC_PRVIC = "priljubljene_zacetne"
    private const val KLJUC_OCISCENO = "priljubljene_prazne"
    private const val KLJUC_RED_DOMOV = "domov_vrstni_red"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seznam(c: Context): List<String> {
        val surovo = prefs(c).getString(KLJUC, null) ?: return emptyList()
        return try {
            val polje = JSONArray(surovo)
            (0 until polje.length()).map { polje.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Throwable) { Log.w(TAG, "Seznama ni bilo mogoce prebrati: ${e.message}"); emptyList() }
    }

    private fun shrani(c: Context, paketi: List<String>) {
        val polje = JSONArray()
        for (p in paketi.distinct()) polje.put(p)
        prefs(c).edit().putString(KLJUC, polje.toString()).apply()
    }

    fun je(c: Context, paket: String): Boolean = seznam(c).contains(paket)

    /**
     * Ena sama otoplitev za nazaj: prva razlicica je priljubljene izbrala sama (prvih nekaj
     * aplikacij televizorja). To ni bila uporabnikova izbira, zato jo enkrat pobrisemo - domaci
     * zaslon zacne prazen in aplikacije nanj da uporabnik sam, iz kartice "Ostalo".
     */
    fun pocistiSamodejne(c: Context) {
        val p = prefs(c)
        if (p.getBoolean(KLJUC_OCISCENO, false)) return
        p.edit().putBoolean(KLJUC_OCISCENO, true).apply()
        if (p.getBoolean(KLJUC_PRVIC, false)) shrani(c, emptyList())
    }

    fun dodaj(c: Context, paket: String) {
        val s = seznam(c)
        if (s.contains(paket)) return
        shrani(c, s + paket)
    }

    fun odstrani(c: Context, paket: String) = shrani(c, seznam(c).filterNot { it == paket })

    /**
     * Lastnik 28. 10. 2026: vrstni red vrstice na novem domacem zaslonu (Pokrajina) za namescene in spletne aplikacije
     * skupaj (id: paket ali »splet:<naslov>«). Brez zapisa velja privzeti red (PokrajinaPravila.vrstaDomov).
     */
    fun redDomov(c: Context): List<String> = try {
        val polje = JSONArray(prefs(c).getString(KLJUC_RED_DOMOV, "[]") ?: "[]")
        (0 until polje.length()).map { polje.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Throwable) { Log.w(TAG, "Vrstnega reda ni bilo mogoce prebrati: ${e.message}"); emptyList() }

    fun shraniRedDomov(c: Context, red: List<String>) {
        val polje = JSONArray()
        for (id in red.distinct()) polje.put(id)
        prefs(c).edit().putString(KLJUC_RED_DOMOV, polje.toString()).apply()
    }

    /** Premik za [zamik] mest (-1 levo, +1 desno); vrne true, ce se je vrstni red res spremenil. */
    fun premakni(c: Context, paket: String, zamik: Int): Boolean {
        val s = seznam(c).toMutableList()
        val i = s.indexOf(paket)
        if (i < 0) return false
        val j = i + zamik
        if (j < 0 || j >= s.size) return false
        s.add(j, s.removeAt(i))
        shrani(c, s)
        return true
    }

    private const val KLJUC_PONUDBA = "ponudba_dom"

    /**
     * Naloga 45 G: ob tretjem zagonu aplikacije, ki ni na domacem zaslonu, se enkrat ponudi »Dodam na dom?«. Pregled
     * 2. 11. 2026: aplikacija se zazene TAKOJ; ponudba pocaka do vrnitve na domaci zaslon ([ponudiCakajoco]) - prej je okno
     * zadrzalo zagon, dokler ga uporabnik ni zaprl.
     */
    fun preveriZagonInZazeni(a: android.app.Activity, paket: String, ime: String, zazeni: () -> Unit) {
        zazeni()
        if (paket == a.packageName || je(a, paket)) return
        val p = prefs(a)
        val zagonov = p.getInt("zagon_st_$paket", 0) + 1
        val e = p.edit().putInt("zagon_st_$paket", zagonov)
        if (PokrajinaPravila.dodajNaDomDialog(zagonov, p.getBoolean("zagon_vprasan_$paket", false), false))
            e.putBoolean("zagon_vprasan_$paket", true).putString(KLJUC_PONUDBA, paket)
        e.apply()
    }

    /** Ob vrnitvi na domaci zaslon: cakajoca ponudba (najvec enkrat), ce aplikacija medtem ni ze na domu ali odstranjena. */
    fun ponudiCakajoco(a: android.app.Activity, poDodaji: () -> Unit) {
        val p = prefs(a)
        val paket = p.getString(KLJUC_PONUDBA, null) ?: return
        p.edit().remove(KLJUC_PONUDBA).apply()
        if (je(a, paket)) return
        val ime = try {
            a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(paket, 0)).toString()
        } catch (_: Throwable) { return }
        val o = android.app.AlertDialog.Builder(a, si.safeer.tv.R.style.Os_Okno)
            .setTitle(a.getString(si.safeer.tv.R.string.pk_dodaj_na_dom_naslov))
            .setMessage(ime + "\n" + a.getString(si.safeer.tv.R.string.pk_dodaj_na_dom_opis))
            .setPositiveButton(a.getString(si.safeer.tv.R.string.os_spletne_dodaj_gumb)) { _, _ ->
                dodaj(a, paket)
                // Na domaci zaslon dodajamo na konec: redDomov.
                val red = redDomov(a).toMutableList()
                if (paket !in red) red.add(paket)
                shraniRedDomov(a, red)
                poDodaji()
            }
            .setNegativeButton(a.getString(si.safeer.tv.R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(o)
        o.show()
    }
}
