package si.safeer.tv.cast

import android.content.Context
import android.util.Log
import org.json.JSONObject
import si.safeer.tv.cast.DostopPravila.Zahteva
import si.safeer.tv.cast.DostopPravila.Zmoznost

/**
 * Kaj sme vsaka druga naprava v Safeer Linku na TEJ napravi (pravila: [DostopPravila]). Zapis je samo na tej napravi
 * (zasebne nastavitve aplikacije) - o svojih vsebinah odloca vsaka naprava sama.
 *
 * Zapis: {"<jedro naprave>": "dpvz"} - crke odprtih zmoznosti; naprava brez zapisa nima dostopa. Ob prvem zagonu po
 * uvedbi dovoljenj se enkrat vpisejo naprave, ki so bile s to napravo v krogu ze prej ([DostopPravila.podedovani]);
 * pozneje se nic vec ne podeduje - nov vnos v krogu s starim datumom dostopa ne dobi.
 */
object Dostop {
    private const val TAG = "SafeerDostop"
    private const val PREFS = "safeer_dostop"
    private const val KLJUC_NAPRAVE = "naprave"
    private const val KLJUC_PODEDOVANO = "podedovano_ob"

    private var zapis: HashMap<String, Set<Zmoznost>>? = null

    /** Klice se ob vsaki spremembi dovoljenj (zaslon naprav, objava stanja). */
    @Volatile var naSpremembo: (() -> Unit)? = null

    @Synchronized
    private fun nalozen(ctx: Context): HashMap<String, Set<Zmoznost>> {
        zapis?.let { return it }
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val m = HashMap<String, Set<Zmoznost>>()
        try {
            val o = JSONObject(prefs.getString(KLJUC_NAPRAVE, "{}") ?: "{}")
            for (k in o.keys()) m[k] = DostopPravila.izNiza(o.optString(k))
        } catch (_: Throwable) { }
        if (!prefs.contains(KLJUC_PODEDOVANO)) {
            // Prvi zagon z dovoljenji: dosedanje naprave obdrzijo, kar so imele (vse); pozneje dodane zacnejo brez dostopa.
            val krog = KrogNaprave.krog(ctx)
            val podedovani = try {
                DostopPravila.podedovani(krog.clani().map { DostopPravila.Clan(it.id, it.kljuc, it.dodano) }, krog.lastniKljuc,
                    KrogZaupanja::idIzKljuca)
            } catch (_: Throwable) { emptySet() }
            for (j in podedovani) if (j !in m) m[j] = DostopPravila.VSE_ZMOZNOSTI
            prefs.edit().putString(KLJUC_NAPRAVE, vJson(m)).putLong(KLJUC_PODEDOVANO, System.currentTimeMillis()).apply()
            Log.i(TAG, "Dovoljenja uvedena: dostop obdrzi ${podedovani.size} dosedanjih naprav; nove zacnejo brez dostopa.")
        }
        zapis = m
        return m
    }

    private fun vJson(m: Map<String, Set<Zmoznost>>): String =
        JSONObject().apply { for ((k, v) in m) put(k, DostopPravila.vNiz(v)) }.toString()

    /** Stalna oznaka naprave za zapis dovoljenj (id iz kljuca brez pripone sorodnika). */
    fun jedro(ctx: Context, id: String): String =
        DostopPravila.jedro(id, { i -> try { KrogNaprave.krog(ctx).clanZaId(i)?.kljuc } catch (_: Throwable) { null } }, KrogZaupanja::idIzKljuca)

    /** Ali je [id] ta naprava sama (isti kljuc; npr. sprejemnik in Safeer OS iste aplikacije). */
    fun jeTaNaprava(ctx: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val nas = try { KrogNaprave.krog(ctx).lastniKljuc?.let { KrogZaupanja.idIzKljuca(it) } } catch (_: Throwable) { null } ?: return false
        return jedro(ctx, id) == nas
    }

    /** Kaj je napravi [id] na tej napravi odprto. */
    fun zmoznosti(ctx: Context, id: String): Set<Zmoznost> {
        if (id.isBlank()) return emptySet()
        if (jeTaNaprava(ctx, id)) return DostopPravila.VSE_ZMOZNOSTI
        val j = jedro(ctx, id)
        return synchronized(this) { nalozen(ctx)[j] } ?: emptySet()
    }

    fun sme(ctx: Context, posiljatelj: String, zahteva: Zahteva): Boolean =
        zahteva == Zahteva.PROSTO || DostopPravila.sme(zmoznosti(ctx, posiljatelj), zahteva)

    fun sme(ctx: Context, posiljatelj: String, zmoznost: Zmoznost): Boolean = zmoznost in zmoznosti(ctx, posiljatelj)

    /** Ali sme [posiljatelj] izvesti ukaz [dejanje]; zavrnitev gre v dnevnik (brez vsebine ukaza). */
    fun smeDejanje(ctx: Context, posiljatelj: String, dejanje: String): Boolean {
        val zahteva = DostopPravila.zahtevaDejanja(dejanje)
        val sme = sme(ctx, posiljatelj, zahteva)
        if (!sme) Log.i(TAG, "Zavrnjeno: ${posiljatelj.ifBlank { "(neznan posiljatelj)" }} nima dostopa za $dejanje ($zahteva)")
        return sme
    }

    /**
     * Ali sme gledalec zaslona, ki ga TA naprava pravkar deli, izvesti [dejanje] (dotik, poteg, tipka). Glej
     * DostopPravila.smeGledalec; zavrnitev gre v dnevnik.
     */
    fun smeGledalec(ctx: Context, dejanje: String, znakUkaza: String): Boolean {
        val deli = si.safeer.tv.link.DeljenjeZaslonaStoritev.tece
        val cilj = si.safeer.tv.link.DeljenjeZaslonaStoritev.cilj
        val sme = DostopPravila.smeGledalec(dejanje, deli, if (deli) zmoznosti(ctx, cilj) else emptySet(),
            si.safeer.tv.link.DeljenjeZaslonaStoritev.znakGledalca, znakUkaza)
        if (!sme) Log.i(TAG, "Zavrnjeno: gledalec ne sme $dejanje (zaslon deljen: $deli)")
        return sme
    }

    fun smeSporocilo(ctx: Context, posiljatelj: String, tip: String, dejanje: String = ""): Boolean {
        val zahteva = DostopPravila.zahtevaSporocila(tip, dejanje)
        val sme = sme(ctx, posiljatelj, zahteva)
        if (!sme) Log.i(TAG, "Zavrnjeno: ${posiljatelj.ifBlank { "(neznan posiljatelj)" }} nima dostopa za $tip ($zahteva)")
        return sme
    }

    /** Uporabnik je napravi [id] dolocil dostop; prazen nabor = brez dostopa (naprava samo pomaga pri povezavi). */
    fun nastavi(ctx: Context, id: String, dano: Set<Zmoznost>) {
        if (id.isBlank() || jeTaNaprava(ctx, id)) return
        val j = jedro(ctx, id)
        synchronized(this) {
            val m = nalozen(ctx)
            m[j] = dano.toSet()
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC_NAPRAVE, vJson(m)).apply()
        }
        Log.i(TAG, "Dostop naprave $j: ${DostopPravila.vNiz(dano).ifEmpty { "brez" }}")
        try { naSpremembo?.invoke() } catch (_: Throwable) { }
    }

    /** Jedra naprav, ki na tej napravi izpolnjujejo [zahteva] (VSE = ozji krog: komu sme iti usklajevanje). */
    fun napraveZ(ctx: Context, zahteva: Zahteva): Set<String> =
        synchronized(this) { nalozen(ctx).filterValues { DostopPravila.sme(it, zahteva) }.keys.toSet() }

    /** Jedra naprav, ki jim je na tej napravi odprta [zmoznost] (npr. komu sme iti stanje predvajanja). */
    fun napraveZ(ctx: Context, zmoznost: Zmoznost): Set<String> =
        synchronized(this) { nalozen(ctx).filterValues { zmoznost in it }.keys.toSet() }
}
