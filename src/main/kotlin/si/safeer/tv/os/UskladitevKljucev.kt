package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Samodejno usklajevanje kljucev in dodatkov v ozjem krogu (lastnik, 25. 10. 2026; pravila: [UskladitevKljucevPravila],
 * protokol za namizje: outputs/nacrti/uskladitev-kljucev.md). Debrid kljuci, indeksator Usenet, dodatki s poverilnico in
 * (od 26. 10. 2026) viri z racunom IPTV, vpisani enkrat, gredo sami na vse naprave v ozjem krogu (tudi spremembe in izbrisi) - po sifrirani poti od naprave do
 * naprave (Link), na prejemni napravi sifrirani (Keystore). Naprava zunaj ozjega kroga nicesar ne dobi (preveri se ob
 * vsakem posiljanju in prejemu; odstranitev iz kroga v Nastavitvah takoj ustavi usklajevanje in pozabi potrditev).
 * Brisanja na daljavo ni. Vrednosti nikoli v dnevnik.
 *
 * Neodvisni pregled 25. 10. 2026 (Webtor rezerva): potrditev na obeh straneh in povabilo brez vrednosti (U1, U3), samo
 * lastni zapisi z izvorom posiljatelja (U2), zamenjava ali izbris kljuca z druge naprave se pokaze in se da razveljaviti
 * (U1), cakajoce z rokom in brez stanja po odstranitvi iz kroga (U11).
 */
object UskladitevKljucev {
    private const val TAG = "SafeerKljuciSinh"
    private const val PREFS = "safeer_kljuci_sinh"
    private const val K_META = "meta"
    private const val K_NAPRAVE = "naprave"
    private const val K_VKLOP = "vklopljeno"
    private const val K_UVOZ = "uvozeno"
    private val P = UskladitevKljucevPravila

    private val zaklep = Any()
    /** Pregled Grok 33, F2: pod tem zaklepom ni omrezja ([Debrid.smeOmrezje]) - drzi ga lahko tudi glavna nit (okno). */
    internal fun drziZaklep(): Boolean = Thread.holdsLock(zaklep)
    private val delavec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-kljuci-sinh").apply { isDaemon = true } }
    @Volatile private var nacrtovano: ScheduledFuture<*>? = null

    /** Naprava, s katero se ta naprava usklajuje ([kljuc] = fizicna naprava; [id] = zadnji id v Linku). */
    class Naprava(val kljuc: String, val id: String, val ime: String, val stanje: UskladitevKljucevPravila.Stanje, val zadnjic: Long)

    /** Zapisi z naprave, ki tu se ni potrjena - cakajo na potrditev tu (samo v pomnilniku, [P.CAKA_VELJA_MS]). */
    class Cakajoca(val kljuc: String, val id: String, val ime: String, val zapisi: List<UskladitevKljucevPravila.Zapis>, val prejeto: Long)
    private val cakajoce = ConcurrentHashMap<String, Cakajoca>()

    /**
     * Zamenjava ali izbris kljuca, ki ga je povzrocila druga naprava (pregled U1): pokazemo jo; razveljavitev vrne
     * [prejsnje] vrednosti (samo v pomnilniku, [P.CAKA_VELJA_MS]).
     */
    class Sprememba(val kljuc: String, val id: String, val ime: String, val kljuci: List<String>, val prejsnje: Map<String, String?>, val ob: Long)
    private val spremembe = ConcurrentHashMap<String, Sprememba>()

    /** Zaslon, ki zna vprasati za potrditev (GlasbaActivity), se prijavi; poklice se na glavni niti. */
    val poslusalci = CopyOnWriteArraySet<() -> Unit>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** »Samodejno usklajuj kljuce in dodatke v ozjem krogu« (privzeto vklopljeno - ozji krog so uporabnikove naprave). */
    fun vklopljeno(c: Context): Boolean = prefs(c).getBoolean(K_VKLOP, true)
    fun nastaviVklopljeno(c: Context, da: Boolean) {
        prefs(c).edit().putBoolean(K_VKLOP, da).apply()
        if (da) sprozi(c, 500)
    }

    private fun jaz(c: Context): String = try { Identiteta.id(c.applicationContext) } catch (_: Throwable) { "" }

    /** Fizicna naprava (brskalnik in Safeer OS istega televizorja sta ena) - stanje naprave je vezano nanjo. */
    private fun kljucNaprave(c: Context, id: String): String = try { si.safeer.tv.cast.Dostop.jedro(c, id) } catch (_: Throwable) { "" }.ifBlank { id }

    // ------------------------------------------------------------------ shramba (brez vrednosti)

    private fun beriMeta(c: Context): MutableMap<String, UskladitevKljucevPravila.Meta> = try {
        val o = JSONObject(prefs(c).getString(K_META, "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { k ->
            o.optJSONObject(k)?.let { m -> k to UskladitevKljucevPravila.Meta(m.optLong("c"), m.optString("i"), m.optBoolean("d"), m.optString("h")) }
        }.toMap(LinkedHashMap())
    } catch (_: Exception) { LinkedHashMap() }

    private fun pisiMeta(c: Context, m: Map<String, UskladitevKljucevPravila.Meta>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONObject().put("c", v.cas).put("i", v.izvor).put("d", v.izbrisan).put("h", v.odtis))
        prefs(c).edit().putString(K_META, o.toString()).commit()
    }

    private fun beriNaprave(c: Context): MutableMap<String, Naprava> = try {
        val o = JSONObject(prefs(c).getString(K_NAPRAVE, "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { k ->
            val n = o.optJSONObject(k) ?: return@mapNotNull null
            val s = UskladitevKljucevPravila.Stanje.values().firstOrNull { it.name == n.optString("s") } ?: return@mapNotNull null
            k to Naprava(k, n.optString("id"), n.optString("ime"), s, n.optLong("t"))
        }.toMap(LinkedHashMap())
    } catch (_: Exception) { LinkedHashMap() }

    private fun pisiNaprave(c: Context, vse: Map<String, Naprava>) {
        val o = JSONObject()
        for ((kk, n) in vse) o.put(kk, JSONObject().put("id", n.id).put("ime", n.ime).put("s", n.stanje.name).put("t", n.zadnjic))
        prefs(c).edit().putString(K_NAPRAVE, o.toString()).apply()
    }

    private fun zapomni(c: Context, id: String, ime: String, stanje: UskladitevKljucevPravila.Stanje, usklajeno: Boolean) = synchronized(zaklep) {
        val k = kljucNaprave(c, id)
        val vse = beriNaprave(c)
        val prej = vse[k]
        vse[k] = Naprava(k, id, ime.ifBlank { prej?.ime.orEmpty() }.take(80), stanje, if (usklajeno) System.currentTimeMillis() else prej?.zadnjic ?: 0L)
        pisiNaprave(c, vse)
    }

    /**
     * Pregled U11: naprava, ki ni vec v ozjem krogu, izgubi stanje (tudi potrditev) - ce jo uporabnik doda znova, se
     * potrdi znova. Zavrnitev ostane (uporabnik jo preklice v seznamu naprav).
     */
    private fun pocistiZunajKroga(c: Context) {
        synchronized(zaklep) {
            val vse = beriNaprave(c)
            val zunaj = vse.values.filter { it.stanje != UskladitevKljucevPravila.Stanje.ZAVRNJENA &&
                // Grok 15, N16: napaka pri preverbi ni »zunaj« - potrditve zaradi zacasne napake ne pozabimo.
                !(try { OzjiKrog.vKrogu(c, it.id) } catch (_: Throwable) { true }) }.map { it.kljuc }
            if (zunaj.isEmpty()) return
            for (k in zunaj) { vse.remove(k); cakajoce.remove(k); spremembe.remove(k) }
            pisiNaprave(c, vse)
            Log.i(TAG, "naprav zunaj ozjega kroga pozabljenih: ${zunaj.size}")
        }
    }

    /** Naprave, s katerimi se ta naprava usklajuje (za seznam v »Debrid storitve«). */
    fun naprave(c: Context): List<Naprava> = beriNaprave(c).values.sortedBy { it.ime.lowercase() }

    /** Zavrnjeno napravo spet dovoli (uporabnik jo izbere v seznamu): naslednja uskladitev jo povabi znova. */
    fun dovoliZnova(c: Context, kljuc: String) {
        synchronized(zaklep) {
            val vse = beriNaprave(c)
            if (vse.remove(kljuc) == null) return
            pisiNaprave(c, vse)
        }
        sprozi(c, 500)
    }

    // ------------------------------------------------------------------ vrednosti te naprave

    private fun odtisVrednosti(c: Context, v: String) = SkrivnostiPravila.odtisSKljucem(lokalniKljuc(c), "safeer-sinh|$v")

    /**
     * Pregled U8: odtis vrednosti v nastavitvah s skrivnim kljucem te naprave (nakljucen, nastane ob prvi rabi) - iz odtisa
     * se kratkega kljuca ne da preveriti brez naprave. Odtis je samo lokalen (ne gre med naprave).
     */
    private fun lokalniKljuc(c: Context): String = synchronized(zaklep) {
        val p = prefs(c)
        p.getString("k_odtisa", null)?.takeIf { it.length >= 32 } ?: run {
            val b = ByteArray(32); java.security.SecureRandom().nextBytes(b)
            val k = b.joinToString("") { "%02x".format(it) }
            p.edit().putString("k_odtisa", k).commit()
            k
        }
    }

    /** Trenutne vrednosti (kljuc -> vrednost) in kljuci, ki so shranjeni, a ta trenutek neberljivi (Keystore). */
    private fun trenutne(c: Context): Pair<Map<String, String>, Set<String>> {
        val v = LinkedHashMap<String, String>()
        val neznani = HashSet<String>()
        for (p in DebridPravila.VSI) {
            val k = P.kljucDebrid(p)
            val kljuc = Debrid.kljuc(c, p)
            // Lastnik 25. 10. 2026: med naprave gre samo kljuc, ki ga je storitev potrdila; nepreverjen ali zavrnjen ni ne
            // vrednost ne izbris (neznan) - drugim napravam ne povozi in ne izbrise veljavnega.
            when (P.oddaja(kljuc != null, Debrid.kljucShranjen(c, p), Debrid.kljucVelja(c, p))) {
                UskladitevKljucevPravila.Oddaja.VREDNOST -> if (kljuc != null) v[k] = kljuc
                UskladitevKljucevPravila.Oddaja.NEZNAN -> neznani += k
                UskladitevKljucevPravila.Oddaja.BREZ -> Unit
            }
        }
        // Enako indeksator Usenet: samo potrjen (Usenet.vpisi / preveriNepreverjenega) gre z vrednostjo.
        val u = Usenet.indeksator(c)
        when (P.oddaja(u != null, Usenet.shranjen(c), Usenet.velja(c))) {
            UskladitevKljucevPravila.Oddaja.VREDNOST -> if (u != null) v[P.KLJUC_USENET] = JSONObject().put("url", u.osnova).put("key", u.kljuc).toString()
            UskladitevKljucevPravila.Oddaja.NEZNAN -> neznani += P.KLJUC_USENET
            UskladitevKljucevPravila.Oddaja.BREZ -> Unit
        }
        SkrivniDodatki.nalozi(c)
        for (vir in MedijskiViri.vsi(c)) {
            if (!vir.jeStremio) continue
            val z = SkrivnostiPravila.nadomestnaOsnova(vir.naslov) ?: continue
            val k = P.kljucDodatka(z.substringAfterLast(SkrivnostiPravila.OZNAKA))
            val pravi = SkrivniDodatki.dejanski(vir.naslov)
            if (pravi != vir.naslov && SkrivnostiPravila.veljavenSkrivniDodatek(pravi)) v[k] = pravi else neznani += k
        }
        // Lastnik, 26. 10. 2026: viri z racunom IPTV (seznam Xtream, tok z geslom) - v nastavitvah nadomestek, pravi naslov
        // sifriran. Prej so sli z navadnim usklajevanjem virov vsem napravam v Linku. Neberljiva skrivnost = neznan (brez groba).
        for (vir in MedijskiViri.vsi(c)) {
            if (!IptvPravila.jeVrstaRacuna(vir.tip)) continue
            val z = SkrivnostiPravila.nadomestnaOsnova(vir.naslov) ?: continue
            val k = P.kljucIptv(z.substringAfterLast(SkrivnostiPravila.OZNAKA))
            val pravi = SkrivniDodatki.dejanski(vir.naslov)
            val berljiv = pravi != vir.naslov && IptvPravila.skrivnostVira(vir.tip, pravi) == pravi
            // Grok 52, Ñ4: z vrednostjo samo vir, dodan na tej napravi - prejet ali podedovan je tuj (neznan: ne gre naprej, brez groba).
            // Grok 60, Ů2: »dodan tu« samo z dokazom (tudi TV podedovanih virov izpred pravila ne vzame za svoje, dokler jih uporabnik ne potrdi).
            when (P.oddajaIptv(berljiv, MedijskiViri.dodanTuZDokazom(c, vir.naslov))) {
                UskladitevKljucevPravila.Oddaja.VREDNOST -> v[k] = JSONObject().put("url", pravi).put("tip", vir.tip).put("ime", vir.ime).toString()
                else -> neznani += k
            }
        }
        return v to neznani
    }

    /** Lastne spremembe v zapise (pod zaklepom). Vrne zapise in vrednosti za posiljanje. */
    private fun posodobi(c: Context): Pair<Map<String, UskladitevKljucevPravila.Meta>, Map<String, String>> = synchronized(zaklep) {
        val (vr, neznani) = trenutne(c)
        val meta = beriMeta(c)
        val odtisi = vr.mapValues { odtisVrednosti(c, it.value) }
        // Pregled U8: prehod na odtis s kljucem - zapis, katerega stari odtis se ujema s trenutno vrednostjo, samo
        // preoznacimo (enak cas in izvor); kar se ne ujema, je prava sprememba in jo najde lokalneSpremembe.
        if (!prefs(c).getBoolean("odtisi_s_kljucem", false)) {
            for ((k, m) in meta.toMap()) {
                val v = vr[k] ?: continue
                if (!m.izbrisan && m.odtis == SkrivnostiPravila.odtis("safeer-sinh|$v")) meta[k] = m.copy(odtis = odtisi.getValue(k))
            }
            pisiMeta(c, meta)
            // Grok 15, N9: zastavica sele, ko so pretvorjeni vsi zapisi - neberljiv kljuc pride na vrsto ob naslednji preverbi
            // (sicer bi kasneje veljal za lastno spremembo te naprave).
            if (P.prehodKoncan(meta.filterValues { !it.izbrisan }.keys, neznani)) prefs(c).edit().putBoolean("odtisi_s_kljucem", true).apply()
        }
        val uvoz = !prefs(c).getBoolean(K_UVOZ, false)
        val spr = P.lokalneSpremembe(meta, odtisi, neznani, jaz(c), System.currentTimeMillis(), uvoz)
        if (spr.isNotEmpty()) { meta.putAll(spr); pisiMeta(c, meta) }
        if (uvoz) prefs(c).edit().putBoolean(K_UVOZ, true).apply()
        meta to vr
    }

    private fun veljavnaVrednost(k: String, v: String): Boolean = when {
        k.startsWith(P.PREDPONA_DEBRID) -> DebridPravila.prejetiKljuci(mapOf(k.removePrefix(P.PREDPONA_DEBRID) to v)).isNotEmpty()
        k == P.KLJUC_USENET -> try { JSONObject(v).let { UsenetPravila.prejetIndeksator(it.optString("url"), it.optString("key")) != null } } catch (_: Exception) { false }
        k.startsWith(P.PREDPONA_DODATKA) -> SkrivnostiPravila.veljavenSkrivniDodatek(v) &&
            SkrivnostiPravila.skrivnaPredponaDodatka(v)?.let { SkrivnostiPravila.odtis(it) } == k.removePrefix(P.PREDPONA_DODATKA)
        k.startsWith(P.PREDPONA_IPTV) -> prejetRacun(k, v) != null
        else -> false
    }

    /**
     * Gumb »Odstrani« (pregled Grok 33, F1): [brisi] izbrise kljuc [kljuc] na tej napravi. Ce izbris ne gre v krog ([vKrog]
     * false - kljuc tu ni veljal), se pod istim zaklepom pozabi tudi njegov zapis ([UskladitevKljucevPravila.poOdstranitvi]):
     * drugim napravam ne gre grob, ki bi izbrisal njihov veljaven kljuc. Brez omrezja.
     */
    fun odstrani(c: Context, kljuc: String, vKrog: Boolean, brisi: () -> Unit) {
        synchronized(zaklep) {
            if (!vKrog) {
                val meta = beriMeta(c)
                val potem = P.poOdstranitvi(meta, kljuc, false)
                if (potem.size != meta.size) pisiMeta(c, potem)
            }
            brisi()
        }
        Log.i(TAG, "kljuc odstranjen ${if (vKrog) "na vseh napravah ozjega kroga" else "samo na tej napravi (tu ni veljal)"}")
        sprozi(c)
    }

    /** Vir z racunom iz zapisa »iptv:<odtis>« (JSON url, tip, ime), preverjen z [IptvPravila.prejetRacun]; null = neveljaven. */
    private fun prejetRacun(k: String, v: String): IptvPravila.ZapisVira? = try {
        JSONObject(v).let { IptvPravila.prejetRacun(k.removePrefix(P.PREDPONA_IPTV), it.optString("tip"), it.optString("url"), it.optString("ime")) }
    } catch (_: Exception) { null }

    /** Gostitelj vira z racunom iz vrednosti zapisa (za okno potrditve in obvestilo - brez uporabnika in gesla). */
    private fun gostiteljRacuna(v: String): String = try { IptvPravila.gostitelj(JSONObject(v).optString("url")) } catch (_: Exception) { "" }

    /** Prejet zapis, pripravljen za uporabo ([pripravi]): [shrani] ga shrani sifrirano ali izbrise - brez omrezja. */
    private class Pripravljen(val z: UskladitevKljucevPravila.Zapis, val shrani: () -> Boolean)

    /**
     * Pregled Grok 33, F2: prejeti kljuc debrid in indeksator se tu preverita pri storitvi s te naprave (lastnik 25. 10.
     * 2026: zavrnjen ne prepise veljavnega) - omrezje, do ~16 s na kljuc. Zato samo z delovne niti [delavec] in BREZ [zaklep]
     * (ta ga drzi tudi glavna nit). null = zapis se ne uporabi (zavrnjen ali ni odgovora - poskusi se ob naslednji uskladitvi).
     * Vir z racunom IPTV in dodatek se tu ne preverjata pri storitvi (vsak klic je prijava v racun) - samo shranita.
     */
    private fun pripravi(c: Context, z: UskladitevKljucevPravila.Zapis): Pripravljen? = try {
        when {
            z.kljuc.startsWith(P.PREDPONA_DEBRID) -> {
                val p = z.kljuc.removePrefix(P.PREDPONA_DEBRID)
                if (z.izbrisan) Pripravljen(z) { Debrid.pozabiKljuc(c, p); true }
                else Debrid.preveriPrejetega(c, p, z.vrednost).second?.let { pr -> Pripravljen(z) { Debrid.shraniPrejetega(c, pr) } }
            }
            z.kljuc == P.KLJUC_USENET ->
                // Lastnik 25. 10. 2026: prejeti indeksator se pred uporabo preveri s te naprave (zavrnjen ne prepise veljavnega).
                if (z.izbrisan) Pripravljen(z) { Usenet.pozabi(c); true }
                else JSONObject(z.vrednost).let { Usenet.preveriPrejetega(c, it.optString("url"), it.optString("key")) }.second
                    ?.let { pr -> Pripravljen(z) { Usenet.shraniPrejetega(c, pr) } }
            // Lastnik, 26. 10. 2026: prejeti dodatek (s poverilnico) ne sprozi pregleda kanalov - prej ga je zacela vsaka
            // naprava kroga hkrati z istim racunom.
            z.kljuc.startsWith(P.PREDPONA_DODATKA) -> Pripravljen(z) { uporabiDodatek(c, z, prejeto = true) }
            z.kljuc.startsWith(P.PREDPONA_IPTV) ->
                if (z.izbrisan) Pripravljen(z) { MedijskiViri.odstraniRacun(c, z.kljuc.removePrefix(P.PREDPONA_IPTV)); true }
                // Lastnik, 26. 10. 2026: prejet vir z racunom se samo shrani (sifrirano) - ne prenese in ne preverja (vsak prenos
                // je prijava v racun); seznam se prenese, ko ga uporabnik na tej napravi odpre.
                else prejetRacun(z.kljuc, z.vrednost)?.let { r -> Pripravljen(z) { MedijskiViri.dodajRacun(c, r.tip, r.naslov, r.ime, prejeto = true) != null } }
            else -> null
        }
    } catch (e: Exception) { Log.w(TAG, "pripravi: ${e.javaClass.simpleName}"); null }

    /**
     * Dodatek s poverilnico: shrani sifrirano ali izbrise (brez omrezja). [prejeto]: z druge naprave v ozjem krogu - ni dodan
     * tu in ne sprozi pregleda kanalov (sicer bi ga zacela vsaka naprava kroga hkrati z istim racunom).
     */
    private fun uporabiDodatek(c: Context, z: UskladitevKljucevPravila.Zapis, prejeto: Boolean = false): Boolean {
        val odtis = z.kljuc.removePrefix(P.PREDPONA_DODATKA)
        if (z.izbrisan) { MedijskiViri.odstraniSkrivniDodatek(c, odtis); return true }
        return MedijskiViri.dodajDodatek(c, MedijskiViri.STREMIO, z.vrednost, "", prejeto = prejeto)?.also {
            if (!prejeto) try { PregledKanalov.zacni(c.applicationContext, it.naslov, rocno = false) } catch (_: Throwable) { }
        } != null
    }

    /**
     * Razveljavitev (pregled Grok 33, F2): prejsnja vrednost te naprave nazaj BREZ omrezja - kljuc in indeksator kot
     * nepreverjena (uskladitev ju takoj preveri in sele potrjena gresta spet drugim napravam); null = kljuca prej ni bilo.
     * Vir z racunom IPTV se vrne brez dokaza »dodan tu« (prejeto) in brez prenosa seznama - geslo ne gre naprej.
     */
    private fun obnovi(c: Context, k: String, v: String?): Boolean = try {
        when {
            k.startsWith(P.PREDPONA_DEBRID) -> {
                val p = k.removePrefix(P.PREDPONA_DEBRID)
                if (v == null) { Debrid.pozabiKljuc(c, p); true } else Debrid.obnoviKljuc(c, p, v)
            }
            k == P.KLJUC_USENET ->
                if (v == null) { Usenet.pozabi(c); true }
                else JSONObject(v).let { Usenet.obnovi(c, it.optString("url"), it.optString("key")) }
            k.startsWith(P.PREDPONA_DODATKA) -> uporabiDodatek(c, UskladitevKljucevPravila.Zapis(k, 0L, "", v == null, v.orEmpty()))
            k.startsWith(P.PREDPONA_IPTV) ->
                if (v == null) { MedijskiViri.odstraniRacun(c, k.removePrefix(P.PREDPONA_IPTV)); true }
                else prejetRacun(k, v)?.let { r -> MedijskiViri.dodajRacun(c, r.tip, r.naslov, r.ime, prejeto = true) } != null
            else -> false
        }
    } catch (e: Exception) { Log.w(TAG, "obnovi: ${e.javaClass.simpleName}"); false }

    /** Za sporocilo: pri dodatkih tudi gostitelj (okno potrditve na drugi napravi ga pokaze tudi v povabilu brez vrednosti). */
    private fun zGostitelji(zapisi: List<UskladitevKljucevPravila.Zapis>): List<UskladitevKljucevPravila.Zapis> = zapisi.map {
        when {
            it.izbrisan -> it
            it.kljuc.startsWith(P.PREDPONA_DODATKA) -> it.copy(gostitelj = Zasebno.gostitelj(it.vrednost))
            it.kljuc.startsWith(P.PREDPONA_IPTV) -> it.copy(gostitelj = gostiteljRacuna(it.vrednost))
            else -> it
        }
    }

    private fun vJson(zapisi: List<UskladitevKljucevPravila.Zapis>): JSONArray {
        val a = JSONArray()
        for (z in zapisi) a.put(JSONObject().put("k", z.kljuc).put("c", z.cas).put("i", z.izvor).put("d", z.izbrisan).apply {
            if (!z.izbrisan && z.vrednost.isNotEmpty()) put("v", z.vrednost)
            if (z.gostitelj.isNotBlank()) put("g", z.gostitelj)
        })
        return a
    }

    private fun izJson(a: JSONArray?): List<UskladitevKljucevPravila.Zapis> = if (a == null) emptyList() else
        (0 until minOf(a.length(), P.NAJVEC_ZAPISOV)).mapNotNull { a.optJSONObject(it) }.map {
            UskladitevKljucevPravila.Zapis(it.optString("k"), it.optLong("c"), it.optString("i"), it.optBoolean("d"),
                if (it.optBoolean("d")) "" else it.optString("v"), P.samoImeGostitelja(it.optString("g")))
        }

    /**
     * Prejeti zapisi s potrjene naprave [od]: novejse uporabi - samo zapise, katerih izvor je ta naprava sama (pregled U1).
     * Zamenjave in izbrise obstojecih kljucev si zapomni za prikaz z razveljavitvijo. Naprej jih NE poslje (U2). Vrne
     * stevilo uporabljenih.
     */
    private fun prejmiZapise(c: Context, od: String, ime: String, zapisi: List<UskladitevKljucevPravila.Zapis>): Int {
        val odNaprava = kljucNaprave(c, od)
        val jaz = jaz(c)
        var zamenjani: List<String> = emptyList()
        var prejsnje: Map<String, String?> = emptyMap()
        // Pregled Grok 33, F2 - trije koraki: (1) pod zaklepom, brez omrezja: lastne neusklajene spremembe dobijo cas (sicer
        // bi jih starejsi zapis z druge naprave povozil), nato kaj je novejse; (2) BREZ zaklepa: preverba prejetih kljucev
        // pri storitvi (omrezje); (3) pod zaklepom, brez omrezja: shrani se samo, kar je se vedno novejse.
        val kandidati = synchronized(zaklep) {
            posodobi(c)
            P.zaUporabo(beriMeta(c), zapisi, System.currentTimeMillis(), DebridPravila.VSI.toSet(), ::veljavnaVrednost) { izvor ->
                izvor != jaz && P.izvorPosiljatelja(kljucNaprave(c, izvor), odNaprava)
            }
        }
        if (kandidati.isEmpty()) return 0
        val pripravljeni = kandidati.mapNotNull { pripravi(c, it) }.associateBy { it.z }
        if (pripravljeni.isEmpty()) return 0
        val uporabljenih = synchronized(zaklep) {
            posodobi(c)
            val meta = beriMeta(c)
            val vsiZaUporabo = P.seVednoNovejsi(meta, pripravljeni.keys.toList())
            // Grok 15, N3: kljuca, ki ga ta hip ni mogoce prebrati (Keystore), ne zamenjamo - razveljavitev ga ne bi mogla
            // vrniti. Zapis pride znova ob naslednji uskladitvi.
            val (vr, neberljivi) = if (vsiZaUporabo.isEmpty()) emptyMap<String, String>() to emptySet<String>() else trenutne(c)
            val zaUporabo = P.brezNeberljivih(vsiZaUporabo, neberljivi)
            val zam = P.zamenjave(meta, zaUporabo)
            if (zam.isNotEmpty()) prejsnje = zam.associate { it.kljuc to vr[it.kljuc] }
            var n = 0
            val uspeli = ArrayList<String>()
            for (z in zaUporabo) {
                if (!(try { pripravljeni.getValue(z).shrani() } catch (e: Exception) { Log.w(TAG, "shrani: ${e.javaClass.simpleName}"); false })) continue
                meta[z.kljuc] = UskladitevKljucevPravila.Meta(z.cas, z.izvor, z.izbrisan, if (z.izbrisan) "" else odtisVrednosti(c, z.vrednost))
                uspeli += z.kljuc
                n++
            }
            if (n > 0) pisiMeta(c, meta)
            zamenjani = zam.map { it.kljuc }.filter { it in uspeli }
            n
        }
        if (uporabljenih > 0) {
            Log.i(TAG, "prejetih sprememb: $uporabljenih")
            if (zamenjani.isNotEmpty()) {
                // Grok 15, N4: druga sprememba iste naprave se zdruzi s prvo (ki se velja) - razveljavitev vrne vrednost pred prvo.
                val zdaj = System.currentTimeMillis()
                spremembe.compute(odNaprava) { _, prej ->
                    val stara = prej?.takeIf { P.cakaVelja(it.ob, zdaj) }
                    val (kljuci, vrednosti) = P.zdruziSpremembi(stara?.kljuci.orEmpty(), stara?.prejsnje.orEmpty(), zamenjani,
                        prejsnje.filterKeys { it in zamenjani })
                    Sprememba(odNaprava, od, ime, kljuci, vrednosti, zdaj)
                }
                Handler(Looper.getMainLooper()).post {
                    if (poslusalci.isEmpty()) try {
                        android.widget.Toast.makeText(c.applicationContext, c.getString(si.safeer.tv.R.string.os_sinh_spremenjeno,
                            DatotekeActivity.lepoIme(ime).ifBlank { ime }, opisKljucev(zamenjani, prejsnje).joinToString(", ")),
                            android.widget.Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) { }
                }
            }
            obvestiZaslone()
        }
        return uporabljenih
    }

    private fun obvestiZaslone() = Handler(Looper.getMainLooper()).post { for (p in poslusalci) try { p() } catch (_: Throwable) { } }

    // ------------------------------------------------------------------ posiljanje

    /** Uskladitev z vsemi napravami v ozjem krogu (v ozadju; vec sprememb v [zamikMs] gre v eno). */
    fun sprozi(c: Context, zamikMs: Long = 3_000) {
        val a = c.applicationContext
        if (!vklopljeno(a)) return
        synchronized(this) {
            nacrtovano?.cancel(false)
            nacrtovano = try {
                delavec.schedule({ try { uskladiZdaj(a) } catch (e: Exception) { Log.w(TAG, "uskladitev: ${e.javaClass.simpleName}") } }, zamikMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) { null }
        }
    }

    private fun uskladiZdaj(c: Context) {
        if (!vklopljeno(c)) return
        val link = try { LinkUpravitelj.pridobi(c) } catch (_: Throwable) { return }
        if (link.jeKrajevni() || !link.povezan) return
        pocistiZunajKroga(c)
        // Kljuci brez stanja ali nepreverjeni se najprej preverijo - poslje se samo potrjen.
        try { Debrid.preveriNepreverjene(c) } catch (e: Exception) { Log.w(TAG, "preverba kljucev: ${e.javaClass.simpleName}") }
        val (meta, vr) = posodobi(c)
        // Samo lastni zapisi (U2); nepotrjena naprava dobi povabilo brez vrednosti (U3).
        val lastni = zGostitelji(P.zaPoslati(meta, vr, jaz(c)))
        val povabilo = P.zaPovabilo(lastni)
        val stanja = beriNaprave(c)
        val videne = HashSet<String>()
        var poslanih = 0
        // Naprave z zmoznostjo najprej: pri dveh identitetah iste naprave (brskalnik, Safeer OS) gre na tisto, ki jo zna.
        for (n in link.naprave.sortedByDescending { P.ZMOZNOST in it.zmoznosti }) {
            if (link.jeTaNaprava(n)) continue
            // Ozji krog TE naprave - ob vsakem posiljanju (odstranjena naprava nic vec ne dobi).
            if (!OzjiKrog.vKrogu(c, n.id)) continue
            val k = kljucNaprave(c, n.id)
            if (!videne.add(k)) continue
            val zmoznost = P.ZMOZNOST in n.zmoznosti
            val prej = stanja[k]?.stanje?.takeIf { it != UskladitevKljucevPravila.Stanje.STARA }
            if (!zmoznost) { if (stanja[k] == null || stanja[k]?.stanje == UskladitevKljucevPravila.Stanje.STARA) zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.STARA, false); continue }
            if (!P.smePoslati(true, true, link.odjemalec.znaZascito(n.id), link.povezan, true, prej)) continue
            if (prej == null) zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.POVABLJENA, false)
            posljiNapravi(c, link, n, if (P.vrednostiZa(prej)) lastni else povabilo, racuni = P.vrednostiZa(prej))
            poslanih++
        }
        if (poslanih > 0) Log.i(TAG, "uskladitev: zapisov ${lastni.size}, naprav $poslanih")
    }

    private fun posljiNapravi(c: Context, link: LinkUpravitelj, n: LinkOdjemalec.Naprava, zapisi: List<UskladitevKljucevPravila.Zapis>,
                              racuni: Boolean) {
        val par = JSONObject().put("v", P.VERZIJA).put("items", vJson(zapisi))
        // Varovalo racunov: stanja racunov (brez kljucev) samo potrjeni napravi; starejsa razlicica polje prezre.
        if (racuni) try { par.put("racuni", Debrid.stanjaZaKrog(c)) } catch (e: Exception) { Log.w(TAG, "racuni: ${e.javaClass.simpleName}") }
        link.ukaz(n.id, P.DEJANJE, par, 12_000, LinkOdjemalec.Odgovor { izid, _ ->
            try { delavec.execute {
                val data = izid?.optJSONObject("data")
                val koda = izid?.optString("code").orEmpty()
                when {
                    // U11: medtem izklopljeno ali naprava odstranjena iz kroga - odgovora ne uporabimo.
                    !vklopljeno(c) || !OzjiKrog.vKrogu(c, n.id) -> { }
                    izid?.optBoolean("ok") == true && data?.optBoolean("accepted") == true -> {
                        val prejeti = izJson(data.optJSONArray("items"))
                        // U1: odgovor uporabimo sami samo od naprave, ki jo je uporabnik TU potrdil; sicer caka na potrditev.
                        if (beriNaprave(c)[kljucNaprave(c, n.id)]?.stanje == UskladitevKljucevPravila.Stanje.POTRJENA) {
                            zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.POTRJENA, true)
                            if (Debrid.prejmiStanja(c, data.optJSONArray("racuni")) > 0) obvestiZaslone()
                            prejmiZapise(c, n.id, n.ime, prejeti)
                        } else vprasaj(c.applicationContext, n.id, n.ime, prejeti)
                    }
                    data?.optBoolean("pending") == true -> { }       // caka na potrditev tam (POVABLJENA)
                    data?.optString("reason") == "zavrnjeno" -> zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.ZAVRNJENA, false)
                    koda == "neznano_dejanje" -> zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.STARA, false)
                }
            } } catch (_: java.util.concurrent.RejectedExecutionException) { }
        })
    }

    // ------------------------------------------------------------------ prejem (Daljinec, dejanje secrets.sync)

    /** Zapisi nepotrjene naprave cakajo na potrditev tu: okno v Medijskem centru ali obvestilo, ce ga ni odprtega. */
    private fun vprasaj(a: Context, od: String, ime: String, zapisi: List<UskladitevKljucevPravila.Zapis>) {
        val k = kljucNaprave(a, od)
        cakajoce[k] = Cakajoca(k, od, ime, zapisi, System.currentTimeMillis())
        zapomni(a, od, ime, UskladitevKljucevPravila.Stanje.CAKA, false)
        Log.i(TAG, "naprava v ozjem krogu zeli uskladiti kljuce (caka na potrditev), zapisov ${zapisi.size}")
        Handler(Looper.getMainLooper()).post {
            if (poslusalci.isEmpty()) try {
                android.widget.Toast.makeText(a, a.getString(si.safeer.tv.R.string.os_sinh_prejeto_odpri, DatotekeActivity.lepoIme(ime).ifBlank { ime }),
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
            for (p in poslusalci) try { p() } catch (_: Throwable) { }
        }
    }

    /**
     * `secrets.sync` z druge naprave - samo po preverjeni seji od naprave do naprave (CastReceiverService) in samo od naprave
     * v ozjem krogu (preveri se tu se enkrat). Nepotrjena naprava (tudi povabljena, U1): zapisi cakajo na potrditev tu.
     * Potrjena: novejse lastne zapise posiljatelja uporabi, odgovor nosi lastne zapise te naprave.
     */
    fun prejmi(c: Context, od: String, ime: String, parametri: JSONObject): JSONObject {
        val a = c.applicationContext
        val vKrogu = od.isNotBlank() && OzjiKrog.vKrogu(a, od)
        if (!vKrogu) pocistiZunajKroga(a)
        val k = kljucNaprave(a, od)
        val stanje = beriNaprave(a)[k]?.stanje
        val zapisi = izJson(parametri.optJSONArray("items"))
        return when (P.sprejem(vklopljeno(a), vKrogu, stanje)) {
            UskladitevKljucevPravila.Odziv.ZAVRNI -> JSONObject().put("accepted", false)
                .put("reason", if (!vklopljeno(a)) "izklopljeno" else if (!vKrogu) "ni_krog" else "zavrnjeno")
            UskladitevKljucevPravila.Odziv.VPRASAJ -> {
                vprasaj(a, od, ime, zapisi)
                JSONObject().put("accepted", false).put("pending", true)
            }
            UskladitevKljucevPravila.Odziv.SPREJMI -> {
                zapomni(a, od, ime, UskladitevKljucevPravila.Stanje.POTRJENA, true)
                // Pregled Grok 33, F2: Daljinec to klice na glavni niti - prejeti kljuci se preverijo pri storitvi (omrezje) na
                // delovni niti. Odgovor nosi lastne zapise te naprave; prejeti tega ne spremenijo (tujih ne posiljamo, U2).
                val racuni = parametri.optJSONArray("racuni")
                try { delavec.execute {
                    try {
                        if (vklopljeno(a) && OzjiKrog.vKrogu(a, od)) {
                            // Varovalo racunov: stanje racuna (ne velja, zaklenjen) najprej - nato kljuci.
                            if (Debrid.prejmiStanja(a, racuni) > 0) obvestiZaslone()
                            prejmiZapise(a, od, ime, zapisi)
                        }
                    } catch (e: Exception) { Log.w(TAG, "prejem: ${e.javaClass.simpleName}") }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { }
                val (meta, vr) = posodobi(a)
                JSONObject().put("accepted", true).put("items", vJson(zGostitelji(P.zaPoslati(meta, vr, jaz(a)))))
                    .apply { try { put("racuni", Debrid.stanjaZaKrog(a)) } catch (_: Exception) { } }
            }
        }
    }

    /** Naprava, ki caka na potrditev (najstarejsa, se veljavna - U3), ali null. */
    fun cakajoca(): Cakajoca? {
        val zdaj = System.currentTimeMillis()
        cakajoce.values.removeIf { !P.cakaVelja(it.prejeto, zdaj) }
        return cakajoce.values.minByOrNull { it.prejeto }
    }

    private fun imeKljuca(kljuc: String, vrednost: String?, gostitelj: String = ""): String = when {
        kljuc.startsWith(P.PREDPONA_DEBRID) -> DebridPravila.ime(kljuc.removePrefix(P.PREDPONA_DEBRID))
        kljuc == P.KLJUC_USENET -> "Usenet"
        // Grok 15, N10: z vrednostjo velja gostitelj iz nje; trditev posiljatelja (g) samo brez vrednosti in samo kot ime.
        kljuc.startsWith(P.PREDPONA_IPTV) -> "IPTV · " + P.prikazGostitelja(vrednost.orEmpty(), gostitelj, gostiteljRacuna(vrednost.orEmpty()))
        else -> Debrid.PREDPONA_DODATKA + P.prikazGostitelja(vrednost.orEmpty(), gostitelj, Zasebno.gostitelj(vrednost.orEmpty()))
    }

    private fun opisKljucev(kljuci: List<String>, vrednosti: Map<String, String?>): List<String> =
        kljuci.map { imeKljuca(it, vrednosti[it]) }.distinct()

    /** Imena prejetih kljucev za vprasanje (brez vrednosti): storitve, »Usenet«, gostitelji dodatkov. */
    fun opisZapisov(z: Cakajoca): List<String> = z.zapisi.filter { !it.izbrisan }.map { imeKljuca(it.kljuc, it.vrednost, it.gostitelj) }.distinct()

    /** Uporabnik je odlocil: [da] = naprava je potrjena (tudi vse prihodnje spremembe), sicer zavrnjena. */
    fun potrdi(c: Context, kljuc: String, da: Boolean) {
        val a = c.applicationContext
        val z = cakajoce.remove(kljuc) ?: return
        if (!da) { zapomni(a, z.id, z.ime, UskladitevKljucevPravila.Stanje.ZAVRNJENA, false); return }
        // U11: medtem izklopljeno ali odstranjena iz kroga - nic.
        if (!vklopljeno(a) || !OzjiKrog.vKrogu(a, z.id)) return
        zapomni(a, z.id, z.ime, UskladitevKljucevPravila.Stanje.POTRJENA, true)
        val sveze = P.cakaVelja(z.prejeto, System.currentTimeMillis())
        try { delavec.execute {
            // Prepozno potrjeni zapisi ne veljajo (U3) - prinese jih naslednja uskladitev.
            if (sveze && OzjiKrog.vKrogu(a, z.id)) prejmiZapise(a, z.id, z.ime, z.zapisi)
            sprozi(a, 200)        // tisti napravi takoj se nase stanje
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** Zamenjava ali izbris z druge naprave, ki je uporabnik se ni videl (najstarejsa, se veljavna), ali null. */
    fun sprememba(): Sprememba? {
        val zdaj = System.currentTimeMillis()
        spremembe.values.removeIf { !P.cakaVelja(it.ob, zdaj) }
        return spremembe.values.minByOrNull { it.ob }
    }

    fun opisSpremembe(s: Sprememba): List<String> = opisKljucev(s.kljuci, s.prejsnje)

    /**
     * Uporabnik je spremembo videl. [razveljavi]: prejsnje vrednosti se vrnejo kot lastna sprememba te naprave (gredo nazaj
     * tudi na tisto napravo); kljuc, ki ga prej ni bilo (prejsnja null), se izbrise.
     */
    fun potrdiSpremembo(c: Context, s: Sprememba, razveljavi: Boolean) {
        spremembe.remove(s.kljuc)
        if (!razveljavi) return
        val a = c.applicationContext
        try { delavec.execute {
            // Pregled Grok 33, F2: brez omrezja pod zaklepom - prejsnje vrednosti nazaj kot nepreverjene ([obnovi]).
            synchronized(zaklep) {
                for (k in s.kljuci) obnovi(a, k, s.prejsnje[k])
            }
            Log.i(TAG, "razveljavljenih sprememb z druge naprave: ${s.kljuci.size}")
            sprozi(a, 200)
            obvestiZaslone()
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
