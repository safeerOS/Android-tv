package si.safeer.tv.cast

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.cast.DostopPravila.Zahteva
import si.safeer.tv.cast.DostopPravila.Zmoznost

/**
 * Shramba dovoljenj naprav v Safeer Linku: kaj sme vsaka druga naprava na TEJ napravi (pravila: [DostopPravila]). Samo
 * podatkovno jedro, skupno TV in telefonu (Link Core, tools/link-core-sync.sh): zapis je samo na tej napravi, o svojih
 * vsebinah odloca vsaka naprava sama. Kar je vezano na aplikacijo (zascita od naprave do naprave, zaslon, odlocanje o ukazih), ostane v
 * [Dostop] na TV.
 *
 * Zapis: {"<jedro naprave>": "dpvz"} - crke odprtih zmoznosti; naprava brez zapisa nima dostopa. Ob prvem zagonu po
 * uvedbi dovoljenj se enkrat vpisejo naprave, ki so bile s to napravo v krogu ze prej ([DostopPravila.podedovani]).
 * Porocitev polnega dostopa (lastnik 21. 11. 2026): glej [zmoznostiJedra] in [DostopPravila.poroceno].
 */
object DostopShramba {
    private const val TAG = "SafeerDostop"
    const val PREFS = "safeer_dostop"
    private const val KLJUC_NAPRAVE = "naprave"
    private const val KLJUC_PODEDOVANO = "podedovano_ob"
    /** Jedra naprav, ki jim je uporabnik zaslon odprl s privolitvijo (DostopPravila.zaslonSPrivolitvijo). */
    private const val KLJUC_PRIVOLITEV_ZASLONA = "privolitev_zaslona"
    /** Jedra naprav s privolitvijo za datoteke po uvedbi `fs:` (link/DatotekeStreznik.smeVseDatoteke). */
    private const val KLJUC_PRIVOLITEV_DATOTEK = "privolitev_datotek"

    private var zapis: HashMap<String, Set<Zmoznost>>? = null

    /** Klice se, ko je umik iz kroga zbrisal zapis dovoljenj (Dostop na TV javi spremembo naprej). */
    @Volatile var poCiscenju: (() -> Unit)? = null

    init {
        // Umik naprave iz kroga takoj zbrise njen zapis dovoljenj (pregled 10. 11. 2026, A).
        KrogNaprave.obSpremembiKroga("dostop") { c -> pocistiUmaknjene(c) }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Zbrise zapis dovoljenj in privolitve naprav, ki so umaknjene iz kroga ([DostopPravila.umaknjenaJedra]): naprava,
     * ki se v krog vrne, zacne s praznim zapisom. Napaka pri branju kroga = nic ne brisemo (dostop umaknjene naprave
     * ze prej ne velja, glej [umaknjena]).
     */
    fun pocistiUmaknjene(ctx: Context) {
        val stevilo = synchronized(this) { odstraniUmaknjene(ctx, nalozen(ctx), prefs(ctx)) }
        if (stevilo == 0) return
        Log.i(TAG, "Umik iz kroga: zapis dovoljenj zbrisan $stevilo napravam.")
        try { poCiscenju?.invoke() } catch (_: Throwable) { }
    }

    /** Iz [m] in iz privolitev odstrani umaknjene naprave in zapise shrani; vrne stevilo. Klicatelj drzi kljucavnico. */
    private fun odstraniUmaknjene(ctx: Context, m: HashMap<String, Set<Zmoznost>>, prefs: android.content.SharedPreferences): Int {
        val krog = try { KrogNaprave.krog(ctx) } catch (_: Throwable) { return 0 }
        val zaslon = privolitve(prefs)
        val datoteke = privolitve(prefs, KLJUC_PRIVOLITEV_DATOTEK)
        val ven = DostopPravila.umaknjenaJedra(m.keys + zaslon + datoteke) { j -> try { krog.jeUmaknjen(j) } catch (_: Throwable) { false } }
        if (ven.isEmpty()) return 0
        for (j in ven) m.remove(j)
        prefs.edit().putString(KLJUC_NAPRAVE, vJson(m))
            .putString(KLJUC_PRIVOLITEV_ZASLONA, JSONArray((zaslon - ven).sorted()).toString())
            .putString(KLJUC_PRIVOLITEV_DATOTEK, JSONArray((datoteke - ven).sorted()).toString()).apply()
        return ven.size
    }

    @Synchronized
    private fun nalozen(ctx: Context): HashMap<String, Set<Zmoznost>> {
        zapis?.let { return it }
        val prefs = prefs(ctx)
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
        // Naprava, umaknjena, ko ta program ni tekel (ali pred to razlicico): zapis ne caka na njeno vrnitev.
        odstraniUmaknjene(ctx, m, prefs)
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
    fun lastnoJedro(ctx: Context): String? =
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
        val dano = synchronized(this) { nalozen(ctx)[jedro] } ?: emptySet()
        val veljavno = DostopPravila.veljavne(dano, umaknjena(ctx, jedro))
        if (veljavno.containsAll(DostopPravila.VSE_ZMOZNOSTI)) return veljavno
        return if (poroceno(ctx, jedro)) DostopPravila.VSE_ZMOZNOSTI else veljavno
    }

    /**
     * Naprava, ki jo je v krog vpisala (podpisan vnos) naprava s polnim dostopom na TEJ napravi, ima polni dostop tudi sama
     * (lastnik 21. 11. 2026: dodam jo na eni napravi, ne povezujem je na vsaki posebej). Ena stopnja; glej DostopPravila.poroceno.
     */
    private fun poroceno(ctx: Context, jedro: String): Boolean = try {
        val krog = KrogNaprave.krog(ctx)
        val vnos = krog.vnosZPodpisom(jedro) ?: krog.clani().firstOrNull { KrogZaupanja.idIzKljuca(it.kljuc) == jedro }?.let { krog.vnosZPodpisom(it.id) }
        if (vnos == null) false else {
            val porok = jedro(ctx, vnos.first)
            val polna = synchronized(this) { nalozen(ctx).filterValues { it.containsAll(DostopPravila.VSE_ZMOZNOSTI) }.keys }
            DostopPravila.poroceno(jedro, porok.ifBlank { null }, vnos.second, umaknjena(ctx, jedro), polna)
        }
    } catch (_: Throwable) { false }

    /**
     * Uporabnik je napravi [id] dolocil dostop; prazen nabor = brez dostopa. Ozji krog ([DostopPravila.ozjiKrog]): vsak
     * neprazen nabor vkljucuje datoteke in zaslon z upravljanjem. Vrne (jedro, shranjen nabor) ali null, ce ni sprememba
     * mogoca (ta naprava sama, oznaka z drugim kljucem).
     */
    fun nastavi(ctx: Context, id: String, dano: Set<Zmoznost>): Pair<String, Set<Zmoznost>>? {
        if (id.isBlank() || jeTaNaprava(ctx, id)) return null
        val j = jedro(ctx, id)
        // Oznaka, pod katero je v krogu drug kljuc, kot ga izkazuje: ni naprava, ki ji je mogoce kaj odpreti.
        if (j.isBlank()) return null
        val novo = DostopPravila.ozjiKrog(dano)
        synchronized(this) {
            val m = nalozen(ctx)
            m[j] = novo
            val prefs = prefs(ctx)
            val zaslon = DostopPravila.privolitvePo(privolitve(prefs), j, novo)
            val datoteke = DostopPravila.privolitvePo(privolitve(prefs, KLJUC_PRIVOLITEV_DATOTEK), j, novo, Zmoznost.DATOTEKE)
            prefs.edit().putString(KLJUC_NAPRAVE, vJson(m))
                .putString(KLJUC_PRIVOLITEV_ZASLONA, JSONArray(zaslon.sorted()).toString())
                .putString(KLJUC_PRIVOLITEV_DATOTEK, JSONArray(datoteke.sorted()).toString()).apply()
        }
        Log.i(TAG, "Dostop naprave $j: ${DostopPravila.vNiz(novo).ifEmpty { "brez" }}")
        return j to novo
    }

    private fun privolitve(prefs: android.content.SharedPreferences, kljuc: String = KLJUC_PRIVOLITEV_ZASLONA): Set<String> = try {
        val a = JSONArray(prefs.getString(kljuc, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf { s -> DostopPravila.jeJedro(s) } }.toSet()
    } catch (_: Throwable) { emptySet() }

    /** Ali je uporabnik napravi [id] datoteke odprl s privolitvijo po uvedbi `fs:` in naprava ni umaknjena. Napaka = ne. */
    fun privolitevVsehDatotek(ctx: Context, id: String): Boolean = try {
        val j = jedro(ctx, id)
        j.isNotBlank() && j in synchronized(this) { privolitve(prefs(ctx), KLJUC_PRIVOLITEV_DATOTEK) } && !umaknjena(ctx, j)
    } catch (_: Throwable) { false }

    /** Uporabnik se je dotaknil obvestila »naprava [id] prosi za vse datoteke« (link/DostopDatotekActivity). */
    fun zabeleziPrivolitevVsehDatotek(ctx: Context, id: String) {
        if (id.isBlank() || jeTaNaprava(ctx, id)) return
        val j = jedro(ctx, id)
        if (j.isBlank()) return
        synchronized(this) {
            val prefs = prefs(ctx)
            val nove = privolitve(prefs, KLJUC_PRIVOLITEV_DATOTEK) + j
            prefs.edit().putString(KLJUC_PRIVOLITEV_DATOTEK, JSONArray(nove.sorted()).toString()).apply()
        }
        Log.i(TAG, "Vse datoteke: privolitev za napravo $j")
    }

    /**
     * Jedra naprav, ki jim je zaslon odprt s privolitvijo uporabnika in niso umaknjene - samo te sprozijo samodejni vklop
     * Safeer Vnosa (pregled tv-vnos, tocka 5; podedovane naprave ne).
     */
    fun napraveSPrivolitvijoZaslona(ctx: Context): Set<String> =
        synchronized(this) { DostopPravila.zaslonSPrivolitvijo(nalozen(ctx), privolitve(prefs(ctx), KLJUC_PRIVOLITEV_ZASLONA)) }
            .filterNot { umaknjena(ctx, it) }.toSet()

    /**
     * Umik iz kroga takoj preklice dostop (lastnik 25. 10. 2026): zapis dovoljenj ne velja in se zbrise
     * ([pocistiUmaknjene]) - ponovno dodana naprava zacne brez dostopa. Napaka pri branju kroga = umaknjena.
     */
    fun umaknjena(ctx: Context, jedro: String): Boolean =
        try { KrogNaprave.krog(ctx).jeUmaknjen(jedro) } catch (_: Throwable) { true }

    /** Jedra naprav, ki na tej napravi izpolnjujejo [zahteva] (VSE = ozji krog: komu sme iti usklajevanje). */
    fun napraveZ(ctx: Context, zahteva: Zahteva): Set<String> =
        synchronized(this) { nalozen(ctx).filterValues { DostopPravila.sme(it, zahteva) }.keys.toSet() }
            .filterNot { umaknjena(ctx, it) }.toSet()

    /** Jedra naprav, ki jim je na tej napravi odprta [zmoznost] (npr. komu sme iti stanje predvajanja). */
    fun napraveZ(ctx: Context, zmoznost: Zmoznost): Set<String> =
        synchronized(this) { nalozen(ctx).filterValues { zmoznost in it }.keys.toSet() }
            .filterNot { umaknjena(ctx, it) }.toSet()
}
