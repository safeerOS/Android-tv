package si.safeer.tv.cast

import android.content.Context
import android.util.Log
import org.json.JSONArray
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
    private const val KLJUC_ZASCITA = "zascita"

    private var zascita: HashSet<String> = HashSet()
    private var zascitaBrana = false

    /** Klice se ob vsaki spremembi dovoljenj (zaslon naprav, objava stanja). */
    @Volatile var naSpremembo: (() -> Unit)? = null

    init {
        // Umik iz kroga zbrise zapis dovoljenj (DostopShramba); TV samo javi spremembo naprej.
        DostopShramba.poCiscenju = { naSpremembo?.invoke() }
    }

    /** Zapis »zascita« (jedra naprav, ki so dokazale kljuc) se prebere enkrat; klicatelj drzi kljucavnico. */
    private fun zascitaNalozena(ctx: Context) {
        if (zascitaBrana) return
        val prefs = ctx.applicationContext.getSharedPreferences(DostopShramba.PREFS, Context.MODE_PRIVATE)
        val z = HashSet<String>()
        try {
            val a = JSONArray(prefs.getString(KLJUC_ZASCITA, "[]") ?: "[]")
            for (i in 0 until a.length()) (a.opt(i) as? String)?.takeIf { DostopPravila.jeJedro(it) }?.let { z.add(it) }
        } catch (_: Throwable) { }
        zascita = z
        zascitaBrana = true
    }

    fun pocistiUmaknjene(ctx: Context) = DostopShramba.pocistiUmaknjene(ctx)

    fun jedro(ctx: Context, id: String): String = DostopShramba.jedro(ctx, id)

    /** Ali je [id] ta naprava sama (isti kljuc; npr. sprejemnik in Safeer OS iste aplikacije). */
    fun jeTaNaprava(ctx: Context, id: String): Boolean = DostopShramba.jeTaNaprava(ctx, id)

    /** Kaj je napravi [id] na tej napravi odprto. */
    fun zmoznosti(ctx: Context, id: String): Set<Zmoznost> = DostopShramba.zmoznosti(ctx, id)

    /** Kaj je odprto napravi s tem jedrom (pri zascitenem sporocilu pride jedro iz PREVERJENEGA kljuca). */
    fun zmoznostiJedra(ctx: Context, jedro: String): Set<Zmoznost> = DostopShramba.zmoznostiJedra(ctx, jedro)

    /**
     * Ali je naprava s tem jedrom kljuc ze dokazala (vzpostavila zascito) - ali pa je to ta naprava sama: njeni programi
     * zascito znajo vedno.
     */
    fun znaZascito(ctx: Context, jedro: String): Boolean {
        if (jedro.isBlank()) return false
        if (jedro == DostopShramba.lastnoJedro(ctx)) return true
        return synchronized(this) { zascitaNalozena(ctx); jedro in zascita }
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
        if (!DostopPravila.jeJedro(jedro) || jedro == DostopShramba.lastnoJedro(ctx)) return
        synchronized(this) {
            zascitaNalozena(ctx)
            if (!zascita.add(jedro)) return
            ctx.applicationContext.getSharedPreferences(DostopShramba.PREFS, Context.MODE_PRIVATE).edit()
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
        if (!sme) Log.i(TAG, "Zavrnjeno: ${si.safeer.tv.link.LinkVarnostPravila.oznakaZaDnevnik(posiljatelj)} nima dostopa za $dejanje ($zahteva)")
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
        return DostopPravila.clanKroga(jedro, { it == DostopShramba.lastnoJedro(ctx) }, { it in jedra }, { krog.jeUmaknjen(it) })
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
        if (!sme) Log.i(TAG, "Zavrnjeno: ${si.safeer.tv.link.LinkVarnostPravila.oznakaZaDnevnik(posiljatelj)} nima dostopa za $tip ($zahteva)")
        return sme
    }

    /**
     * Uporabnik je napravi [id] dolocil dostop (privolitev v Nastavitvah ali Linku); prazen nabor = brez dostopa (naprava
     * samo pomaga pri povezavi). Zapis in pravila so v [DostopShramba]; tu ostane samo, kar je vezano na zaslon TV.
     */
    fun nastavi(ctx: Context, id: String, dano: Set<Zmoznost>) {
        val (j, novo) = DostopShramba.nastavi(ctx, id, dano) ?: return
        // Pregled dex-a U7: brez zaslona (umik iz ozjega kroga) zaupanje za deljenje zaslona ne ostane.
        if (Zmoznost.ZASLON !in novo) {
            try { si.safeer.tv.link.ZaupanjeZaslona.pocisti(ctx) } catch (_: Throwable) { }
            // Kot na racunalniku: umik iz ozjega kroga takoj konca deljenje zaslona tej napravi.
            try { si.safeer.tv.link.DeljenjeZaslonaStoritev.ustaviBrezDostopa(ctx, j) } catch (_: Throwable) { }
        }
        try { naSpremembo?.invoke() } catch (_: Throwable) { }
    }

    /** Ali je uporabnik napravi [id] datoteke odprl s privolitvijo po uvedbi `fs:` in naprava ni umaknjena. Napaka = ne. */
    fun privolitevVsehDatotek(ctx: Context, id: String): Boolean = DostopShramba.privolitevVsehDatotek(ctx, id)

    /** Uporabnik se je dotaknil obvestila »naprava [id] prosi za vse datoteke« (link/DostopDatotekActivity). */
    fun zabeleziPrivolitevVsehDatotek(ctx: Context, id: String) = DostopShramba.zabeleziPrivolitevVsehDatotek(ctx, id)

    /** Jedra naprav z zaslonom, odprtim s privolitvijo uporabnika (ne umaknjena); sprozijo samodejni vklop Safeer Vnosa. */
    fun napraveSPrivolitvijoZaslona(ctx: Context): Set<String> = DostopShramba.napraveSPrivolitvijoZaslona(ctx)

    /** Jedra naprav, ki na tej napravi izpolnjujejo [zahteva] (VSE = ozji krog: komu sme iti usklajevanje). */
    fun napraveZ(ctx: Context, zahteva: Zahteva): Set<String> = DostopShramba.napraveZ(ctx, zahteva)

    /** Jedra naprav, ki jim je na tej napravi odprta [zmoznost] (npr. komu sme iti stanje predvajanja). */
    fun napraveZ(ctx: Context, zmoznost: Zmoznost): Set<String> = DostopShramba.napraveZ(ctx, zmoznost)
}
