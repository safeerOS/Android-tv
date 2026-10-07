package si.safeer.tv.cast

import android.content.Context
import android.util.Log
import org.json.JSONArray
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
 *
 * »zascita«: jedra naprav, ki so s to napravo ze vzpostavile zascito od naprave do naprave ([E2e], [ZascitaLinka]) -
 * torej dokazale kljuc. Od take naprave nezascitenega ukaza ne sprejmemo vec: sicer bi se kdorkoli z njeno oznako (ali
 * sredisce samo) predstavil kot starejsa razlicica. Zapis se samo dopolnjuje.
 */
object Dostop {
    private const val TAG = "SafeerDostop"
    private const val PREFS = "safeer_dostop"
    private const val KLJUC_NAPRAVE = "naprave"
    private const val KLJUC_PODEDOVANO = "podedovano_ob"
    private const val KLJUC_ZASCITA = "zascita"

    private var zapis: HashMap<String, Set<Zmoznost>>? = null
    private var zascita: HashSet<String> = HashSet()

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
        val z = HashSet<String>()
        try {
            val a = JSONArray(prefs.getString(KLJUC_ZASCITA, "[]") ?: "[]")
            for (i in 0 until a.length()) (a.opt(i) as? String)?.takeIf { DostopPravila.jeJedro(it) }?.let { z.add(it) }
        } catch (_: Throwable) { }
        zascita = z
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

    /**
     * Stalna oznaka naprave za zapis dovoljenj (id iz kljuca brez pripone sorodnika). Prazna, ce je pod to oznako v krogu
     * vpisan drug kljuc, kot ga oznaka izkazuje ([DostopPravila.jedro]) - taka oznaka nima nicesar.
     */
    fun jedro(ctx: Context, id: String): String =
        DostopPravila.jedro(id, { i -> try { KrogNaprave.krog(ctx).clanZaId(i)?.kljuc } catch (_: Throwable) { null } }, KrogZaupanja::idIzKljuca)

    /** Jedro TE naprave (iz njenega kljuca) ali null, ce kljuca ni. */
    private fun lastnoJedro(ctx: Context): String? =
        try { KrogNaprave.krog(ctx).lastniKljuc?.let { KrogZaupanja.idIzKljuca(it) } } catch (_: Throwable) { null }

    /** Ali je [id] ta naprava sama (isti kljuc; npr. sprejemnik in Safeer OS iste aplikacije). */
    fun jeTaNaprava(ctx: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val nas = lastnoJedro(ctx) ?: return false
        return jedro(ctx, id) == nas
    }

    /** Kaj je napravi [id] na tej napravi odprto. */
    fun zmoznosti(ctx: Context, id: String): Set<Zmoznost> {
        if (id.isBlank()) return emptySet()
        return zmoznostiJedra(ctx, jedro(ctx, id))
    }

    /** Kaj je odprto napravi s tem jedrom (pri zascitenem sporocilu pride jedro iz PREVERJENEGA kljuca). */
    fun zmoznostiJedra(ctx: Context, jedro: String): Set<Zmoznost> {
        if (jedro.isBlank()) return emptySet()
        if (jedro == lastnoJedro(ctx)) return DostopPravila.VSE_ZMOZNOSTI
        return synchronized(this) { nalozen(ctx)[jedro] } ?: emptySet()
    }

    /**
     * Ali je naprava s tem jedrom kljuc ze dokazala (vzpostavila zascito) - ali pa je to ta naprava sama: njeni programi
     * zascito znajo vedno.
     */
    fun znaZascito(ctx: Context, jedro: String): Boolean {
        if (jedro.isBlank()) return false
        if (jedro == lastnoJedro(ctx)) return true
        return synchronized(this) { nalozen(ctx); jedro in zascita }
    }

    /**
     * Ali od naprave s to oznako sprejmemo samo zascitena sporocila (ker vemo, da jih zna poslati). Pri oznaki iz kljuca
     * odloca jedro iz OBLIKE oznake - vnos v krogu z drugim kljucem pod to oznako zahteve ne ugasne
     * ([DostopPravila.zahtevaZascito]).
     */
    fun zahtevaZascito(ctx: Context, id: String): Boolean =
        DostopPravila.zahtevaZascito(id, { j -> znaZascito(ctx, j) }, { i -> jedro(ctx, i) })

    /** Z napravo je vzpostavljena preverjena seja: odslej od nje (in v njenem imenu) ne sprejmemo nezascitenega. */
    fun zabeleziZascito(ctx: Context, jedro: String) {
        if (!DostopPravila.jeJedro(jedro) || jedro == lastnoJedro(ctx)) return
        synchronized(this) {
            nalozen(ctx)
            if (!zascita.add(jedro)) return
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KLJUC_ZASCITA, JSONArray(zascita.sorted()).toString()).apply()
        }
        Log.i(TAG, "Naprava ${E2e.zakrij(jedro)} je dokazala kljuc: odslej od nje samo zasciteni ukazi.")
    }

    fun sme(ctx: Context, posiljatelj: String, zahteva: Zahteva): Boolean =
        zahteva == Zahteva.PROSTO || DostopPravila.sme(zmoznosti(ctx, posiljatelj), zahteva)

    fun sme(ctx: Context, posiljatelj: String, zmoznost: Zmoznost): Boolean = zmoznost in zmoznosti(ctx, posiljatelj)

    private fun smePosiljatelj(ctx: Context, posiljatelj: String, zahteva: Zahteva, zascita: String, zascitljivo: Boolean): Boolean =
        DostopPravila.smePosiljatelj(zahteva, zascita, zascitljivo,
            zahtevaZascito = { zahtevaZascito(ctx, posiljatelj) },
            zmoznostiJedra = { j -> zmoznostiJedra(ctx, j) },
            zmoznostiOznake = { zmoznosti(ctx, posiljatelj) })

    /**
     * Ali sme [posiljatelj] izvesti ukaz [dejanje]; zavrnitev gre v dnevnik (brez vsebine ukaza). [zascita] je jedro iz
     * preverjenega kljuca, kadar je ukaz prisel zasciten (ZascitaLinka.POLJE); brez nje velja oznaka, ki jo je vpisalo
     * sredisce - a samo za napravo, ki zascite (se) ne zna.
     */
    fun smeDejanje(ctx: Context, posiljatelj: String, dejanje: String, zascita: String = ""): Boolean {
        val zahteva = DostopPravila.zahtevaDejanja(dejanje)
        val sme = smePosiljatelj(ctx, posiljatelj, zahteva, zascita, true)
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

    /**
     * Kot [smeDejanje], za sporocila, ki niso ukaz. Pravilo »od naprave z zascito samo zasciteno« velja za tipe, ki jih
     * naprave z zascito res posiljajo zascitene (E2e.ZASCITENI_TIPI); usklajevanje in zaslon gresta se po starem.
     */
    fun smeSporocilo(ctx: Context, posiljatelj: String, tip: String, dejanje: String = "", zascita: String = ""): Boolean {
        val zahteva = DostopPravila.zahtevaSporocila(tip, dejanje)
        val sme = smePosiljatelj(ctx, posiljatelj, zahteva, zascita, tip in E2e.ZASCITENI_TIPI)
        if (!sme) Log.i(TAG, "Zavrnjeno: ${posiljatelj.ifBlank { "(neznan posiljatelj)" }} nima dostopa za $tip ($zahteva)")
        return sme
    }

    /** Uporabnik je napravi [id] dolocil dostop; prazen nabor = brez dostopa (naprava samo pomaga pri povezavi). */
    fun nastavi(ctx: Context, id: String, dano: Set<Zmoznost>) {
        if (id.isBlank() || jeTaNaprava(ctx, id)) return
        val j = jedro(ctx, id)
        // Oznaka, pod katero je v krogu drug kljuc, kot ga izkazuje: ni naprava, ki ji je mogoce kaj odpreti.
        if (j.isBlank()) return
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
