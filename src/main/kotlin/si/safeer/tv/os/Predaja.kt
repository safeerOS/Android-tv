package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.link.DatotekeStreznik
import java.util.concurrent.ConcurrentHashMap

/**
 * "Nadaljuj na drugi napravi" (Matej, 28. 9. 2026): nic samodejnega. Vsaka naprava le HRANI stanje "kaj predvajam in
 * kje" (in kaj je nazadnje gledala); druga naprava si ga na izrecno zahtevo uporabnika POTEGNE (`play.state`) in
 * nadaljuje pri isti sekundi. Izvor sam ne ustavi predvajanja - le ce uporabnik na cilju izbere "ustavi tam"
 * (`play.stop`). Stanje se deli samo napravam v krogu zaupanja (ukazi Linka) in le, ce deljenje ni izklopljeno.
 */
object Predaja {
    private const val TAG = "SafeerPredaja"
    private const val PREFS = "safeer_os_predaja"
    private const val KLJUC_DELI = "deli"

    /** Ali ta naprava drugim pove, kaj predvaja (stikalo v Nastavitvah; privzeto da). */
    fun deli(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_DELI, true)
    fun nastaviDeli(ctx: Context, da: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_DELI, da).apply() }

    // ------------------------------------------------------------------ izvor (odgovor na play.state)

    /**
     * Odgovor na `play.state` za napravo [posiljatelj]: kaj igra zdaj (ali kaj je nazadnje gledala) in pri kateri
     * sekundi. Datoteka te naprave dobi naslov in zeton za posiljatelja (kot pri Datotekah), sprotni tok pomocnika
     * se pove kot izvirnik - cilj si pomocnika poisce sam.
     */
    fun stanje(ctx: Context, posiljatelj: String): JSONObject {
        val o = JSONObject().put("shared", deli(ctx))
        if (!deli(ctx)) return o
        val p = GlasbaStoritev.predvajalnik
        val sk = GlasbaStoritev.trenutna()
        if (p != null && sk != null) {
            val tok = SprotnaPomoc.tokZa(sk)
            val izvirnik = tok?.izvirnik ?: sk
            val streznik = tok?.streznikIzvirnika ?: GlasbaStoritev.streznikTrenutni
            val polozaj = (tok?.zamikMs ?: 0L) + p.currentPosition.coerceAtLeast(0L)
            val trajanje = tok?.trajanjeMs?.takeIf { it > 0 } ?: p.duration.takeIf { it > 0 } ?: 0L
            val vnos = zaPosiljanje(ctx, izvirnik, streznik, posiljatelj) ?: return o.put("playing", false).put("reason", "ni_deljeno")
            o.put("playing", true).put("is_playing", p.isPlaying).put("position_ms", polozaj).put("duration_ms", trajanje)
            vnos.toMap().forEach { (k, v) -> o.put(k, v) }
            return o
        }
        // Nic ne igra: zadnji video s shranjenim mestom (televizor je film ugasnil pri 1:02 - tablica nadaljuje).
        val zadnji = MediaNapredek.seznam(ctx).firstOrNull() ?: return o.put("playing", false)
        val vnos = zaPosiljanje(ctx, zadnji.skladba, null, posiljatelj) ?: return o.put("playing", false)
        o.put("playing", false).put("last", true).put("position_ms", zadnji.polozaj).put("duration_ms", zadnji.trajanje).put("when", zadnji.cas)
        vnos.toMap().forEach { (k, v) -> o.put(k, v) }
        return o
    }

    /** `play.stop`: uporabnik na cilju je izbral "nadaljuj tukaj in ustavi tam" - tu samo pavza (nic se ne izgubi). */
    fun ustavi(): JSONObject {
        val p = GlasbaStoritev.predvajalnik ?: return JSONObject().put("stopped", false)
        android.os.Handler(android.os.Looper.getMainLooper()).post { try { p.pause() } catch (_: Throwable) { } }
        return JSONObject().put("stopped", true)
    }

    private class Vnos(val item: JSONObject, val server: JSONObject?, val serverDevice: String) {
        fun toMap(): Map<String, Any> {
            val m = HashMap<String, Any>()
            m["item"] = item
            if (server != null) m["server"] = server
            if (serverDevice.isNotBlank()) m["server_device"] = serverDevice
            return m
        }
    }

    /** Skladba, kot jo lahko druga naprava predvaja: datoteka te naprave prek njenega streznika, tuja datoteka z id-jem streznika. */
    private fun zaPosiljanje(ctx: Context, sk: Jamendo.Skladba, streznik: DatotekeActivity.Streznik?, posiljatelj: String): Vnos? {
        if (sk.id.startsWith("krajevno:")) {
            // Datoteka te naprave: le, ce naprava datoteke deli (isto stikalo in dovoljenje kot Datoteke).
            if (!DatotekeStreznik.vklopljeno(ctx) || !DatotekeStreznik.imamoDovoljenje(ctx)) return null
            val oznaka = DatotekeStreznik.oznakaZa(sk.zvok) ?: return null
            val s = DatotekeStreznik.streznikZa(ctx, posiljatelj) ?: return null
            val url = s.optString("base_url").trimEnd('/') + "/d/" + android.net.Uri.encode(oznaka)
            return Vnos(vJson(sk.copy(id = oznaka, zvok = url, povezava = url, podnapisi = emptyList())), s, Identiteta.id(ctx))
        }
        if (streznik != null && streznik.naprava.isNotBlank()) {
            // Datoteka druge naprave (racunalnik, telefon): cilj dobi svoj zeton pri tisti napravi (files.list).
            return Vnos(vJson(sk.copy(podnapisi = emptyList())), null, streznik.naprava)
        }
        if (sk.zvok.startsWith("content://") || sk.zvok.startsWith("file:") || sk.zvok.startsWith("/")) return null
        return Vnos(vJson(sk.copy(podnapisi = emptyList())), null, "")
    }

    // ------------------------------------------------------------------ cilj (vprasaj naprave)

    /** Kar naprava ponuja za nadaljevanje: kaj, kje (sekunda), ali ravno igra ali je bilo nazadnje, in prek cesa. */
    class Ponudba(val naprava: LinkOdjemalec.Naprava, val skladba: Jamendo.Skladba, val polozajMs: Long, val trajanjeMs: Long,
                  val igra: Boolean, val nazadnje: Boolean, val streznik: DatotekeActivity.Streznik?, val streznikNaprava: String)

    /** Vse naprave v Linku vprasa hkrati (2,5 s); [nato] dobi ponudbe (najprej tiste, ki ravno igrajo) na glavni niti. */
    fun poizvedi(ctx: Context, nato: (List<Ponudba>) -> Unit) {
        val app = ctx.applicationContext
        val link = LinkUpravitelj.pridobi(app)
        val naprave = link.naprave.filter { !link.jeTaNaprava(it) && "remote" in it.zmoznosti }
        if (!link.povezan || naprave.isEmpty()) { nato(emptyList()); return }
        val ponudbe = ConcurrentHashMap<String, Ponudba>()
        var cakam = naprave.size
        val glavna = android.os.Handler(android.os.Looper.getMainLooper())
        for (n in naprave) {
            link.ukaz(n.id, "play.state", JSONObject(), 2_500, LinkOdjemalec.Odgovor { izid, _ ->
                val d = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                val item = d?.optJSONObject("item")
                log("play.state ${n.id}: ${izid?.toString()?.take(400)}")
                if (d != null && item != null && (d.optBoolean("playing") || d.optBoolean("last"))) {
                    val s = d.optJSONObject("server")?.let { DatotekeActivity.Streznik(it.optString("base_url").trimEnd('/'), it.optString("fp"), it.optString("token"), n.id) }
                    ponudbe[n.id] = Ponudba(n, izJson(item), d.optLong("position_ms", 0L), d.optLong("duration_ms", 0L),
                        d.optBoolean("playing") && d.optBoolean("is_playing", true), d.optBoolean("last"), s, d.optString("server_device"))
                }
                glavna.post {
                    if (--cakam == 0) nato(naprave.mapNotNull { ponudbe[it.id] }.sortedWith(compareByDescending<Ponudba> { it.igra }.thenByDescending { !it.nazadnje }))
                }
            })
        }
    }

    /**
     * Mapa, v kateri je datoteka z oznako [id] na njenem strezniku (`files.list` da naslov in zeton streznika le za mapo
     * z datotekami): racunalnik `disk:/pot` ali `share:<i>:<pot>`, Android `media:<zbirka>:<id>` -> zbirka.
     */
    fun mapaDatoteke(id: String): String = when {
        id.startsWith("disk:") -> "disk:" + id.removePrefix("disk:").substringBeforeLast('/', "/")
        id.startsWith("share:") -> id.split(":", limit = 3).let { d -> if (d.size == 3) "share:${d[1]}:" + d[2].substringBeforeLast('/', "") else "root" }
        id.startsWith("media:") -> id.split(":").getOrNull(1).orEmpty().ifBlank { "root" }
        else -> "root"
    }

    // ------------------------------------------------------------------ zapis skladbe

    fun vJson(s: Jamendo.Skladba): JSONObject = JSONObject()
        .put("id", s.id).put("naslov", s.naslov).put("izvajalec", s.izvajalec).put("slika", s.slika).put("zvok", s.zvok)
        .put("povezava", s.povezava).put("radio", s.radio).put("video", s.video).put("mime", s.mime).put("streznik", s.streznik)
        .put("kanal", s.kanal).put("mediaType", s.mediaType).put("genres", JSONArray(s.genres)).put("year", s.year)
        .put("season", s.season).put("episode", s.episode).put("imdbId", s.imdbId).put("tmdbId", s.tmdbId)
        .put("quality", s.quality).put("rating", s.rating).put("language", s.language)

    fun izJson(o: JSONObject): Jamendo.Skladba = Jamendo.Skladba(
        o.optString("id"), o.optString("naslov"), o.optString("izvajalec"), o.optString("slika"), o.optString("zvok"), o.optString("povezava"),
        radio = o.optBoolean("radio"), video = o.optBoolean("video"), mime = o.optString("mime"), streznik = o.optString("streznik"),
        kanal = o.optString("kanal"), mediaType = o.optString("mediaType"),
        genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
        year = o.optInt("year"), season = o.optInt("season"), episode = o.optInt("episode"), imdbId = o.optString("imdbId"),
        tmdbId = o.optString("tmdbId"), quality = o.optInt("quality"), rating = o.optDouble("rating", 0.0), language = o.optString("language"))

    fun log(b: String) = Log.i(TAG, b)
}
