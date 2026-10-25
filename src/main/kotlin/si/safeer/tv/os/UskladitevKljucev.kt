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
 * protokol za namizje: outputs/nacrti/uskladitev-kljucev.md). Debrid kljuci, indeksator Usenet in dodatki s poverilnico,
 * vpisani enkrat, gredo sami na vse naprave v ozjem krogu (tudi spremembe in izbrisi) - po sifrirani poti od naprave do
 * naprave (Link), na prejemni napravi sifrirani (Keystore). Naprava zunaj ozjega kroga nicesar ne dobi (preveri se ob
 * vsakem posiljanju in prejemu; odstranitev iz kroga v Nastavitvah takoj ustavi usklajevanje). Brisanja na daljavo ni.
 * Vrednosti nikoli v dnevnik.
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
    private val delavec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-kljuci-sinh").apply { isDaemon = true } }
    @Volatile private var nacrtovano: ScheduledFuture<*>? = null

    /** Naprava, s katero se ta naprava usklajuje ([kljuc] = fizicna naprava; [id] = zadnji id v Linku). */
    class Naprava(val kljuc: String, val id: String, val ime: String, val stanje: UskladitevKljucevPravila.Stanje, val zadnjic: Long)

    /** Prvi zapisi z nove naprave - cakajo na potrditev tu (samo v pomnilniku). */
    class Cakajoca(val kljuc: String, val id: String, val ime: String, val zapisi: List<UskladitevKljucevPravila.Zapis>, val prejeto: Long)
    private val cakajoce = ConcurrentHashMap<String, Cakajoca>()
    /** Zaslon, ki zna vprasati za potrditev (GlasbaActivity), se prijavi; poklice se na glavni niti. */
    val poslusalci = CopyOnWriteArraySet<() -> Unit>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** »Samodejno usklajuj kljuce in dodatke v ozjem krogu« (privzeto vklopljeno). */
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

    private fun zapomni(c: Context, id: String, ime: String, stanje: UskladitevKljucevPravila.Stanje, usklajeno: Boolean) = synchronized(zaklep) {
        val k = kljucNaprave(c, id)
        val vse = beriNaprave(c)
        val prej = vse[k]
        vse[k] = Naprava(k, id, ime.ifBlank { prej?.ime.orEmpty() }.take(80), stanje, if (usklajeno) System.currentTimeMillis() else prej?.zadnjic ?: 0L)
        val o = JSONObject()
        for ((kk, n) in vse) o.put(kk, JSONObject().put("id", n.id).put("ime", n.ime).put("s", n.stanje.name).put("t", n.zadnjic))
        prefs(c).edit().putString(K_NAPRAVE, o.toString()).apply()
    }

    /** Naprave, s katerimi se ta naprava usklajuje (za seznam v »Debrid storitve«). */
    fun naprave(c: Context): List<Naprava> = beriNaprave(c).values.sortedBy { it.ime.lowercase() }

    /** Zavrnjeno napravo spet dovoli (uporabnik jo izbere v seznamu): naslednja uskladitev jo povabi znova. */
    fun dovoliZnova(c: Context, kljuc: String) {
        synchronized(zaklep) {
            val vse = beriNaprave(c)
            if (vse.remove(kljuc) == null) return
            val o = JSONObject()
            for ((kk, n) in vse) o.put(kk, JSONObject().put("id", n.id).put("ime", n.ime).put("s", n.stanje.name).put("t", n.zadnjic))
            prefs(c).edit().putString(K_NAPRAVE, o.toString()).apply()
        }
        sprozi(c, 500)
    }

    // ------------------------------------------------------------------ vrednosti te naprave

    private fun odtisVrednosti(v: String) = SkrivnostiPravila.odtis("safeer-sinh|$v")

    /** Trenutne vrednosti (kljuc -> vrednost) in kljuci, ki so shranjeni, a ta trenutek neberljivi (Keystore). */
    private fun trenutne(c: Context): Pair<Map<String, String>, Set<String>> {
        val v = LinkedHashMap<String, String>()
        val neznani = HashSet<String>()
        for (p in DebridPravila.VSI) {
            val k = P.kljucDebrid(p)
            val kljuc = Debrid.kljuc(c, p)
            if (kljuc != null) v[k] = kljuc else if (Debrid.kljucShranjen(c, p)) neznani += k
        }
        val u = Usenet.indeksator(c)
        if (u != null) v[P.KLJUC_USENET] = JSONObject().put("url", u.osnova).put("key", u.kljuc).toString()
        else if (Usenet.shranjen(c)) neznani += P.KLJUC_USENET
        SkrivniDodatki.nalozi(c)
        for (vir in MedijskiViri.vsi(c)) {
            if (!vir.jeStremio) continue
            val z = SkrivnostiPravila.nadomestnaOsnova(vir.naslov) ?: continue
            val k = P.kljucDodatka(z.substringAfterLast(SkrivnostiPravila.OZNAKA))
            val pravi = SkrivniDodatki.dejanski(vir.naslov)
            if (pravi != vir.naslov && SkrivnostiPravila.veljavenSkrivniDodatek(pravi)) v[k] = pravi else neznani += k
        }
        return v to neznani
    }

    /** Lastne spremembe v zapise (pod zaklepom). Vrne zapise in vrednosti za posiljanje. */
    private fun posodobi(c: Context): Pair<Map<String, UskladitevKljucevPravila.Meta>, Map<String, String>> = synchronized(zaklep) {
        val (vr, neznani) = trenutne(c)
        val meta = beriMeta(c)
        val uvoz = !prefs(c).getBoolean(K_UVOZ, false)
        val spr = P.lokalneSpremembe(meta, vr.mapValues { odtisVrednosti(it.value) }, neznani, jaz(c), System.currentTimeMillis(), uvoz)
        if (spr.isNotEmpty()) { meta.putAll(spr); pisiMeta(c, meta) }
        if (uvoz) prefs(c).edit().putBoolean(K_UVOZ, true).apply()
        meta to vr
    }

    private fun veljavnaVrednost(k: String, v: String): Boolean = when {
        k.startsWith(P.PREDPONA_DEBRID) -> DebridPravila.prejetiKljuci(mapOf(k.removePrefix(P.PREDPONA_DEBRID) to v)).isNotEmpty()
        k == P.KLJUC_USENET -> try { JSONObject(v).let { UsenetPravila.prejetIndeksator(it.optString("url"), it.optString("key")) != null } } catch (_: Exception) { false }
        k.startsWith(P.PREDPONA_DODATKA) -> SkrivnostiPravila.veljavenSkrivniDodatek(v) &&
            SkrivnostiPravila.skrivnaPredponaDodatka(v)?.let { SkrivnostiPravila.odtis(it) } == k.removePrefix(P.PREDPONA_DODATKA)
        else -> false
    }

    /** Uporabi prejet zapis (shrani sifrirano ali izbrise). false = ni uspelo (zapis se ne steje - poskusi se znova). */
    private fun uporabi(c: Context, z: UskladitevKljucevPravila.Zapis): Boolean = try {
        when {
            z.kljuc.startsWith(P.PREDPONA_DEBRID) -> {
                val p = z.kljuc.removePrefix(P.PREDPONA_DEBRID)
                if (z.izbrisan) { Debrid.pozabiKljuc(c, p); true } else Debrid.nastaviKljuc(c, p, z.vrednost)
            }
            z.kljuc == P.KLJUC_USENET ->
                if (z.izbrisan) { Usenet.pozabi(c); true }
                else JSONObject(z.vrednost).let { Usenet.nastavi(c, it.optString("url"), it.optString("key")) }
            z.kljuc.startsWith(P.PREDPONA_DODATKA) -> {
                val odtis = z.kljuc.removePrefix(P.PREDPONA_DODATKA)
                if (z.izbrisan) { MedijskiViri.odstraniSkrivniDodatek(c, odtis); true }
                else MedijskiViri.dodajDodatek(c, MedijskiViri.STREMIO, z.vrednost, "")?.also {
                    try { PregledKanalov.zacni(c.applicationContext, it.naslov, rocno = false) } catch (_: Throwable) { }
                } != null
            }
            else -> false
        }
    } catch (e: Exception) { Log.w(TAG, "uporabi: ${e.javaClass.simpleName}"); false }

    private fun vJson(zapisi: List<UskladitevKljucevPravila.Zapis>): JSONArray {
        val a = JSONArray()
        for (z in zapisi) a.put(JSONObject().put("k", z.kljuc).put("c", z.cas).put("i", z.izvor).put("d", z.izbrisan).apply { if (!z.izbrisan) put("v", z.vrednost) })
        return a
    }

    private fun izJson(a: JSONArray?): List<UskladitevKljucevPravila.Zapis> = if (a == null) emptyList() else
        (0 until minOf(a.length(), P.NAJVEC_ZAPISOV)).mapNotNull { a.optJSONObject(it) }.map {
            UskladitevKljucevPravila.Zapis(it.optString("k"), it.optLong("c"), it.optString("i"), it.optBoolean("d"), if (it.optBoolean("d")) "" else it.optString("v"))
        }

    /** Prejeti zapisi (z naprave, ki ji ta naprava zaupa): novejse uporabi. Vrne stevilo uporabljenih. */
    private fun prejmiZapise(c: Context, zapisi: List<UskladitevKljucevPravila.Zapis>): Int {
        val uporabljenih = synchronized(zaklep) {
            // Najprej lastne neusklajene spremembe dobijo cas - sicer bi jih starejsi zapis z druge naprave povozil.
            posodobi(c)
            val meta = beriMeta(c)
            var n = 0
            for (z in P.zaUporabo(meta, zapisi, System.currentTimeMillis(), DebridPravila.VSI.toSet(), ::veljavnaVrednost)) {
                if (!uporabi(c, z)) continue
                meta[z.kljuc] = UskladitevKljucevPravila.Meta(z.cas, z.izvor, z.izbrisan, if (z.izbrisan) "" else odtisVrednosti(z.vrednost))
                n++
            }
            if (n > 0) pisiMeta(c, meta)
            n
        }
        if (uporabljenih > 0) {
            Log.i(TAG, "prejetih sprememb: $uporabljenih")
            sprozi(c)               // naprej po ozjem krogu (idempotentno - brez sprememb se ustavi)
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
        val (meta, vr) = posodobi(c)
        val zapisi = P.zaPoslati(meta, vr)
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
            posljiNapravi(c, link, n, zapisi)
            poslanih++
        }
        if (poslanih > 0) Log.i(TAG, "uskladitev: zapisov ${zapisi.size}, naprav $poslanih")
    }

    private fun posljiNapravi(c: Context, link: LinkUpravitelj, n: LinkOdjemalec.Naprava, zapisi: List<UskladitevKljucevPravila.Zapis>) {
        val par = JSONObject().put("v", P.VERZIJA).put("items", vJson(zapisi))
        link.ukaz(n.id, P.DEJANJE, par, 12_000, LinkOdjemalec.Odgovor { izid, _ ->
            try { delavec.execute {
                val data = izid?.optJSONObject("data")
                val koda = izid?.optString("code").orEmpty()
                when {
                    izid?.optBoolean("ok") == true && data?.optBoolean("accepted") == true -> {
                        zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.POTRJENA, true)
                        // Odgovor nosi stanje tiste naprave - zaupamo ji (je v nasem ozjem krogu, poslali smo ji mi).
                        if (OzjiKrog.vKrogu(c, n.id)) prejmiZapise(c, izJson(data.optJSONArray("items")))
                    }
                    data?.optBoolean("pending") == true -> { }       // caka na potrditev tam (POVABLJENA)
                    data?.optString("reason") == "zavrnjeno" -> zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.ZAVRNJENA, false)
                    koda == "neznano_dejanje" -> zapomni(c, n.id, n.ime, UskladitevKljucevPravila.Stanje.STARA, false)
                }
            } } catch (_: java.util.concurrent.RejectedExecutionException) { }
        })
    }

    // ------------------------------------------------------------------ prejem (Daljinec, dejanje secrets.sync)

    /**
     * `secrets.sync` z druge naprave. Daljinec ga sprejme samo od naprave v ozjem krogu (dejanje ni v naborih DostopPravila);
     * tu se to preveri se enkrat. Nova naprava: zapisi cakajo na potrditev tu (enkrat na napravo). Potrjena ali povabljena:
     * novejse se uporabi, odgovor nosi stanje te naprave.
     */
    fun prejmi(c: Context, od: String, ime: String, parametri: JSONObject): JSONObject {
        val a = c.applicationContext
        val vKrogu = od.isNotBlank() && OzjiKrog.vKrogu(a, od)
        val k = kljucNaprave(a, od)
        val stanje = beriNaprave(a)[k]?.stanje
        val zapisi = izJson(parametri.optJSONArray("items"))
        return when (P.sprejem(vklopljeno(a), vKrogu, stanje)) {
            UskladitevKljucevPravila.Odziv.ZAVRNI -> JSONObject().put("accepted", false)
                .put("reason", if (!vklopljeno(a)) "izklopljeno" else if (!vKrogu) "ni_krog" else "zavrnjeno")
            UskladitevKljucevPravila.Odziv.VPRASAJ -> {
                cakajoce[k] = Cakajoca(k, od, ime, zapisi, System.currentTimeMillis())
                zapomni(a, od, ime, UskladitevKljucevPravila.Stanje.CAKA, false)
                Log.i(TAG, "nova naprava v ozjem krogu zeli uskladiti kljuce (caka na potrditev), zapisov ${zapisi.size}")
                Handler(Looper.getMainLooper()).post {
                    if (poslusalci.isEmpty()) try {
                        android.widget.Toast.makeText(a, a.getString(si.safeer.tv.R.string.os_sinh_prejeto_odpri, DatotekeActivity.lepoIme(ime).ifBlank { ime }),
                            android.widget.Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) { }
                    for (p in poslusalci) try { p() } catch (_: Throwable) { }
                }
                JSONObject().put("accepted", false).put("pending", true)
            }
            UskladitevKljucevPravila.Odziv.SPREJMI -> {
                zapomni(a, od, ime, UskladitevKljucevPravila.Stanje.POTRJENA, true)
                prejmiZapise(a, zapisi)
                val (meta, vr) = posodobi(a)
                JSONObject().put("accepted", true).put("items", vJson(P.zaPoslati(meta, vr)))
            }
        }
    }

    /** Naprava, ki caka na potrditev (najstarejsa), ali null. */
    fun cakajoca(): Cakajoca? = cakajoce.values.minByOrNull { it.prejeto }

    /** Imena prejetih kljucev za vprasanje (brez vrednosti): storitve, »Usenet«, gostitelji dodatkov. */
    fun opisZapisov(z: Cakajoca): List<String> = z.zapisi.filter { !it.izbrisan }.map {
        when {
            it.kljuc.startsWith(P.PREDPONA_DEBRID) -> DebridPravila.ime(it.kljuc.removePrefix(P.PREDPONA_DEBRID))
            it.kljuc == P.KLJUC_USENET -> "Usenet"
            else -> Debrid.PREDPONA_DODATKA + Zasebno.gostitelj(it.vrednost)
        }
    }.distinct()

    /** Uporabnik je odlocil: [da] = naprava je potrjena (tudi vse prihodnje spremembe), sicer zavrnjena. */
    fun potrdi(c: Context, kljuc: String, da: Boolean) {
        val a = c.applicationContext
        val z = cakajoce.remove(kljuc) ?: return
        if (!da) { zapomni(a, z.id, z.ime, UskladitevKljucevPravila.Stanje.ZAVRNJENA, false); return }
        zapomni(a, z.id, z.ime, UskladitevKljucevPravila.Stanje.POTRJENA, true)
        try { delavec.execute {
            if (OzjiKrog.vKrogu(a, z.id)) prejmiZapise(a, z.zapisi)
            sprozi(a, 200)        // tisti napravi takoj se nase stanje
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
