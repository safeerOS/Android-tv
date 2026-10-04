package si.safeer.tv.os

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import si.safeer.tv.os.RazpolozljivostPravila as P

/**
 * Kaj od prikazanega se res da predvajati (lastnik, 2. 10. 2026: "ce videa ali serije ne moremo predvajati, ga ne
 * prikazemo"). Katalog (npr. Cinemeta) pozna vse naslove, tokove pa imajo le uporabnikovi dodatki - zato si za vsak
 * naslov zapomnimo, ali ga kateri dodatek ponuja. Vsaka poizvedba dodatku je draga: dodatek jih dovoli le nekaj na
 * minuto (Stremio.zeton), zato enega naslova nikoli ne vprasamo po nepotrebnem (4. 10. 2026: mreza Filmi se je polnila
 * po eno kartico na osem sekund, ker so zapisi kar naprej izginjali):
 *
 *  - zapis velja za vse nacine naprave (z ali brez torrentov, koliko prostora) - [RazpolozljivostPravila];
 *  - zastarel odgovor ostane za prikaz in se preveri znova v ozadju (»je« 30 dni, »ni« 7 dni);
 *  - sprememba dodatkov nicesar ne pozabi: odgovori samo niso vec svezi - in to le, ce se je spremenil dodatek, ki
 *    daje tokove (podnapisi in katalogi ne stejejo);
 *  - naprave v Linku z istimi dodatki si zapise delijo (SeznamiSink): eno vprasanje na dom, ne na napravo.
 */
object Razpolozljivost {
    private const val DATOTEKA = "safeer_razpolozljivost"
    private const val KLJUC_ZAPISI = "zapisi"
    private const val KLJUC_DODATKI = "dodatki3"
    private const val NAJVEC = 4000
    private const val ZACASNO_VELJA = 10 * 60_000L
    /** Koliko zapisov gre v eno sporocilo Linka. */
    const val STRAN = 300

    /**
     * Skrito le za kratek cas in samo v pomnilniku (kljuc -> cas): dotik ni dal toka, a se ne vemo, ali so odgovorili
     * vsi dodatki. Izpad dodatka ali omrezja ne sme naslova skriti za ure - to stori sele potrjen "ni" ([zapomni]).
     */
    private val zacasno = ConcurrentHashMap<String, Long>()

    fun zacasnoNi(kljuc: String) { zacasno[kljuc] = System.currentTimeMillis() }

    private val zapisi = ConcurrentHashMap<String, P.Zapis>()
    @Volatile private var nalozeno = false
    @Volatile private var umazano = false
    @Volatile private var shranjujem = false

    /** Kaj ta naprava ta hip zmore s torrenti (Stremio.torrentGre): 0 = nic, bajti = do te velikosti, Long.MAX_VALUE = vse. */
    @Volatile private var meja = Long.MAX_VALUE
    /** Dodatki ob zadnjem [pripravi] (odtis naslova -> ali daje tokove) in seznam, iz katerega so nastali. */
    @Volatile private var dodatki: Map<String, Char>? = null
    @Volatile private var zadnjiSeznam: List<Pair<String, Boolean?>>? = null
    /** Odtis dodatkov, ki dajejo tokove: zapise si delijo samo naprave z enakim. Prazen = ni kaj deliti. */
    @Volatile private var odtisDodatkov = ""

    /** Poklicano (iz katerekoli niti), ko zapise dopolni druga naprava v Linku: odprta mreza se uredi znova. */
    @Volatile var obSpremembi: (() -> Unit)? = null

    fun kljuc(tip: String, id: String) = "$tip|$id"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            try {
                val p = prefs(c)
                val novi = p.getString(KLJUC_ZAPISI, null)
                if (novi != null) {
                    val o = JSONObject(novi)
                    for (k in o.keys()) P.izNiza(o.optString(k))?.let { zapisi[k] = it }
                } else {
                    // Zapisi pred 4. 10. 2026 (po en na nacin): prevzamemo, kar se da, da se mreza po posodobitvi ne sprazni.
                    val zdaj = System.currentTimeMillis()
                    val o = JSONObject(p.getString("stanja", "{}") ?: "{}")
                    for (k in o.keys()) P.izStarega(k, o.optLong(k))?.let { (kljuc, z) -> zapisi[kljuc] = P.zdruzi(zapisi[kljuc], z, zdaj) }
                    p.edit().remove("stanja").remove("dodatki").putString(KLJUC_ZAPISI, vJson().toString()).apply()
                }
                dodatki = P.dodatkiIzNiza(p.getString(KLJUC_DODATKI, null))
                odtisDodatkov = dodatki?.let { P.odtis(it) }.orEmpty()
            } catch (_: Exception) { }
            nalozeno = true
        }
    }

    private fun vJson(): JSONObject = JSONObject().also { o -> for ((k, v) in zapisi) o.put(k, P.vNiz(v)) }

    private fun zacasnoSkrit(kljuc: String): Boolean {
        val cas = zacasno[kljuc] ?: return false
        if (System.currentTimeMillis() - cas in 0..ZACASNO_VELJA) return true
        zacasno.remove(kljuc)
        return false
    }

    /** true = se da predvajati, false = ne, null = ne vemo ali pa je odgovor zastarel (preveriti znova). */
    fun stanje(c: Context, kljuc: String): Boolean? {
        nalozi(c)
        if (zacasnoSkrit(kljuc)) return false
        return P.stanje(zapisi[kljuc], meja, System.currentTimeMillis())
    }

    /**
     * Zadnji znani odgovor, tudi ce ni vec svez: po njem risemo (kartica ostane ali ostane skrita), medtem ko ga v
     * ozadju preverjamo znova. null = naslova se ne poznamo - samo na take seznam caka.
     */
    fun znano(c: Context, kljuc: String): Boolean? {
        nalozi(c)
        if (zacasnoSkrit(kljuc)) return false
        return P.znano(zapisi[kljuc], meja, System.currentTimeMillis())
    }

    /** Odgovor dodatkov za naslov ([Stremio.razpolozljivo]). */
    fun zapomni(c: Context, kljuc: String, izid: P.Izid) {
        nalozi(c)
        if (zapisi.size > NAJVEC) {
            // Najstarejso polovico pozabimo (zapisi se sproti obnavljajo).
            zapisi.entries.sortedBy { it.value.cas }.take(NAJVEC / 2).forEach { zapisi.remove(it.key) }
        }
        zapisi[kljuc] = P.zapisi(zapisi[kljuc], izid, System.currentTimeMillis())
        zacasno.remove(kljuc)
        umazano = true
        shrani(c.applicationContext)
    }

    /** Predvajanje na tej napravi je uspelo: naslov je na voljo vsaj napravam, ki zmorejo toliko kot ta. */
    fun zapomniNaVoljo(c: Context, kljuc: String) = zapomni(c, kljuc, P.Izid(jeOd = meja))

    /**
     * Pred risanjem: kaj naprava ta hip zmore ([novaMeja]) in kateri dodatki so nastavljeni ([seznam]: naslov dodatka,
     * ali daje tokove - null, dokler manifesta ne poznamo). Poceni klic: dela samo, kadar se seznam spremeni.
     */
    fun pripravi(c: Context, seznam: List<Pair<String, Boolean?>>, novaMeja: Long) {
        meja = novaMeja
        nastaviDodatke(c, seznam)
    }

    /** Samo dodatki (brez meje naprave): usklajevanje v ozadju jih pozna, se preden je mreza prvic narisana. */
    fun nastaviDodatke(c: Context, seznam: List<Pair<String, Boolean?>>) {
        nalozi(c)
        if (seznam == zadnjiSeznam) return
        synchronized(this) {
            if (seznam == zadnjiSeznam) return
            val zdaj = LinkedHashMap<String, Char>()
            for ((naslov, daje) in seznam) zdaj[P.odtisNaslova(Stremio.osnova(naslov))] = when (daje) { true -> 's'; false -> 'n'; null -> 'u' }
            val prej = dodatki
            val s = P.dodatki(prej, zdaj)
            if (s.zastaraj) {
                val t = System.currentTimeMillis()
                for ((k, v) in zapisi) zapisi[k] = P.zastaraj(v, t)
                zacasno.clear()
                umazano = true
                shrani(c.applicationContext)
                try { android.util.Log.i("SafeerOsMedia", "dodatki s tokovi so se spremenili: ${zapisi.size} odgovorov preverimo znova v ozadju") } catch (_: Throwable) { }
            }
            if (s.dodatki != prej) try { prefs(c).edit().putString(KLJUC_DODATKI, P.dodatkiVNiz(s.dodatki)).apply() } catch (_: Exception) { }
            dodatki = s.dodatki
            odtisDodatkov = P.odtis(s.dodatki)
            zadnjiSeznam = seznam
        }
    }

    // ------------------------------------------------------------------ delitev med napravami v Linku (SeznamiSink)

    private fun zaDelitev(): List<Map.Entry<String, P.Zapis>> = zapisi.entries.filter { P.zaDelitev(it.key) }.sortedBy { it.key }

    /** Kaj ta naprava ve: odtis dodatkov, stevilo zapisov in cas najnovejsega (druga naprava vprasa samo, ce je kaj novega). */
    fun povzetek(c: Context): JSONObject {
        nalozi(c)
        val vsi = if (odtisDodatkov.isEmpty()) emptyList() else zaDelitev()
        return JSONObject().put("odtis", odtisDodatkov).put("vseh", vsi.size).put("zadnji", vsi.maxOfOrNull { it.value.cas } ?: 0L)
    }

    /** Stran zapisov za napravo z istimi dodatki ([odtis]); drugacni dodatki = drugacni odgovori, zato nic. */
    fun izvoz(c: Context, odtis: String, od: Int): JSONObject {
        nalozi(c)
        val o = JSONObject().put("odtis", odtisDodatkov).put("od", od.coerceAtLeast(0))
        if (odtisDodatkov.isEmpty() || odtis != odtisDodatkov) return o.put("vseh", 0).put("zapisi", JSONObject())
        val vsi = zaDelitev()
        val stran = JSONObject()
        vsi.drop(od.coerceAtLeast(0)).take(STRAN).forEach { stran.put(it.key, P.vNiz(it.value)) }
        return o.put("vseh", vsi.size).put("zapisi", stran)
    }

    fun odtis(c: Context): String { nalozi(c); return odtisDodatkov }

    /** Zapisi druge naprave z istimi dodatki: obvelja novejsi dokaz. Vrne, koliko se jih je tu spremenilo. */
    fun prevzemi(c: Context, tuji: JSONObject): Int {
        nalozi(c)
        val zdaj = System.currentTimeMillis()
        var novih = 0
        for (k in tuji.keys()) {
            if (!P.zaDelitev(k)) continue
            val tuj = P.izNiza(tuji.optString(k)) ?: continue
            val moj = zapisi[k]
            val z = P.zdruzi(moj, tuj, zdaj)
            if (z != moj) { zapisi[k] = z; novih++ }
        }
        if (novih > 0) {
            umazano = true
            shrani(c.applicationContext)
            try { obSpremembi?.invoke() } catch (_: Throwable) { }
        }
        return novih
    }

    /** Zapis na disk zdruzimo: med preverjanjem mreze pride veliko sprememb zapored. */
    private fun shrani(c: Context) {
        if (shranjujem) return
        shranjujem = true
        Thread {
            try {
                Thread.sleep(1_500)
                while (umazano) {
                    umazano = false
                    prefs(c).edit().putString(KLJUC_ZAPISI, vJson().toString()).apply()
                }
            } catch (_: Exception) { } finally { shranjujem = false }
        }.apply { name = "Safeer-razpolozljivost"; isDaemon = true; start() }
    }
}
