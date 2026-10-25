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
    /** Jedra naprav, ki jim je uporabnik zaslon odprl s privolitvijo (DostopPravila.zaslonSPrivolitvijo). */
    private const val KLJUC_PRIVOLITEV_ZASLONA = "privolitev_zaslona"
    /** Jedra naprav s privolitvijo za datoteke po uvedbi `fs:` (link/DatotekeStreznik.smeVseDatoteke). */
    private const val KLJUC_PRIVOLITEV_DATOTEK = "privolitev_datotek"

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
        val dano = synchronized(this) { nalozen(ctx)[jedro] } ?: emptySet()
        return DostopPravila.veljavne(dano, umaknjena(ctx, jedro))
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
        // Vnos (input.*, tudi input.enable) samo clan kroga zaupanja (pregled tv-vnos, tocka 1): odloca jedro iz
        // preverjenega kljuca, ce je ukaz prisel zasciten, sicer jedro oznake.
        val sme = DostopPravila.smeUkaz(dejanje, smePosiljatelj(ctx, posiljatelj, zahteva, zascita, true)) {
            clanKroga(ctx, if (zascita.isNotEmpty()) zascita else jedro(ctx, posiljatelj))
        }
        if (!sme) Log.i(TAG, "Zavrnjeno: ${posiljatelj.ifBlank { "(neznan posiljatelj)" }} nima dostopa za $dejanje ($zahteva)")
        return sme
    }

    /** Jedra clanov kroga zaupanja (id in jedro iz kljuca) - vkljucno z umaknjenimi; umik preveri [clanKroga]. */
    fun jedraKroga(ctx: Context): Set<String> {
        val izid = HashSet<String>()
        for (cl in KrogNaprave.krog(ctx).clani()) {
            izid.add(cl.id)
            try { izid.add(KrogZaupanja.idIzKljuca(cl.kljuc)) } catch (_: Throwable) { }
        }
        return izid
    }

    /** Ali je naprava z jedrom [jedro] ta naprava ali clan kroga zaupanja, ki ni umaknjen. Napaka = ne. */
    fun clanKroga(ctx: Context, jedro: String): Boolean {
        val krog = try { KrogNaprave.krog(ctx) } catch (_: Throwable) { return false }
        val jedra = try { jedraKroga(ctx) } catch (_: Throwable) { return false }
        return DostopPravila.clanKroga(jedro, { it == lastnoJedro(ctx) }, { it in jedra }, { krog.jeUmaknjen(it) })
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

    /**
     * Uporabnik je napravi [id] dolocil dostop (privolitev v Nastavitvah ali Linku); prazen nabor = brez dostopa (naprava
     * samo pomaga pri povezavi). Ozji krog ([DostopPravila.ozjiKrog], lastnik 25. 10. 2026 10:49): vsak neprazen nabor
     * vkljucuje datoteke in zaslon z upravljanjem; zaslon, shranjen tu, je privolitev za Safeer Vnos.
     */
    fun nastavi(ctx: Context, id: String, dano: Set<Zmoznost>) {
        if (id.isBlank() || jeTaNaprava(ctx, id)) return
        val j = jedro(ctx, id)
        // Oznaka, pod katero je v krogu drug kljuc, kot ga izkazuje: ni naprava, ki ji je mogoce kaj odpreti.
        if (j.isBlank()) return
        val novo = DostopPravila.ozjiKrog(dano)
        synchronized(this) {
            val m = nalozen(ctx)
            m[j] = novo
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val zaslon = DostopPravila.privolitvePo(privolitve(prefs), j, novo)
            val datoteke = DostopPravila.privolitvePo(privolitve(prefs, KLJUC_PRIVOLITEV_DATOTEK), j, novo, Zmoznost.DATOTEKE)
            prefs.edit().putString(KLJUC_NAPRAVE, vJson(m))
                .putString(KLJUC_PRIVOLITEV_ZASLONA, JSONArray(zaslon.sorted()).toString())
                .putString(KLJUC_PRIVOLITEV_DATOTEK, JSONArray(datoteke.sorted()).toString()).apply()
        }
        Log.i(TAG, "Dostop naprave $j: ${DostopPravila.vNiz(novo).ifEmpty { "brez" }}")
        // Pregled dex-a U7: brez zaslona (umik iz ozjega kroga) zaupanje za deljenje zaslona ne ostane.
        if (Zmoznost.ZASLON !in novo) {
            try { si.safeer.tv.link.ZaupanjeZaslona.pocisti(ctx) } catch (_: Throwable) { }
            // Kot na racunalniku: umik iz ozjega kroga takoj konca deljenje zaslona tej napravi.
            try { si.safeer.tv.link.DeljenjeZaslonaStoritev.ustaviBrezDostopa(ctx, j) } catch (_: Throwable) { }
        }
        try { naSpremembo?.invoke() } catch (_: Throwable) { }
    }

    private fun privolitve(prefs: android.content.SharedPreferences, kljuc: String = KLJUC_PRIVOLITEV_ZASLONA): Set<String> = try {
        val a = JSONArray(prefs.getString(kljuc, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf { s -> DostopPravila.jeJedro(s) } }.toSet()
    } catch (_: Throwable) { emptySet() }

    /** Ali je uporabnik napravi [id] datoteke odprl s privolitvijo po uvedbi `fs:` in naprava ni umaknjena. Napaka = ne. */
    fun privolitevVsehDatotek(ctx: Context, id: String): Boolean = try {
        val j = jedro(ctx, id)
        j.isNotBlank() && j in synchronized(this) {
            privolitve(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KLJUC_PRIVOLITEV_DATOTEK)
        } && !umaknjena(ctx, j)
    } catch (_: Throwable) { false }

    /** Uporabnik se je dotaknil obvestila »naprava [id] prosi za vse datoteke« (link/DostopDatotekActivity). */
    fun zabeleziPrivolitevVsehDatotek(ctx: Context, id: String) {
        if (id.isBlank() || jeTaNaprava(ctx, id)) return
        val j = jedro(ctx, id)
        if (j.isBlank()) return
        synchronized(this) {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val nove = privolitve(prefs, KLJUC_PRIVOLITEV_DATOTEK) + j
            prefs.edit().putString(KLJUC_PRIVOLITEV_DATOTEK, JSONArray(nove.sorted()).toString()).apply()
        }
        Log.i(TAG, "Vse datoteke: privolitev za napravo $j")
    }

    /**
     * Jedra naprav, ki jim je zaslon odprt s privolitvijo uporabnika in niso umaknjene - samo te sprozijo samodejni vklop
     * Safeer Vnosa (pregled tv-vnos, tocka 5; podedovane naprave ne).
     */
    fun napraveSPrivolitvijoZaslona(ctx: Context): Set<String> {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(this) { DostopPravila.zaslonSPrivolitvijo(nalozen(ctx), privolitve(prefs, KLJUC_PRIVOLITEV_ZASLONA)) }
            .filterNot { umaknjena(ctx, it) }.toSet()
    }

    /**
     * Umik iz kroga takoj preklice dostop (lastnik 25. 10. 2026; pravilo AGENTS.md »umik naprave iz kroga takoj
     * preklice njene zetone in seje«): zapis dovoljenj ostane (ponovno dodana naprava ga dobi nazaj), a ne velja.
     * Napaka pri branju kroga = umaknjena (»ne vem« ni »da«).
     */
    private fun umaknjena(ctx: Context, jedro: String): Boolean =
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
