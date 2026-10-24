package si.safeer.tv.os

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Naloga 117: predvajanje na drugi napravi v Safeer Linku se upravlja kot lokalno (ideja kot Spotify Connect, koda nasa).
 *
 * Daljinec (ta naprava): po »Poslji na napravo« se zacne seja ([zacni]); dokler cilj nekaj predvaja in ta naprava sama
 * ne igra, gumbi predvajalnika (zaslon, tipke, obvestilo, zaganjalnik) prek [GlasbaStoritev] upravljajo cilj
 * ([OddaljenoPravila.upravljaCilj]). Stanje cilja se osvezuje, dokler je zaslon z daljincem viden; gumb se odzove takoj
 * ([OddaljenoPravila.poUkazu]), odgovor cilja ga nato popravi.
 *
 * Cilj (druga naprava): [izvedi] je odgovor na `control.command` z dejanjem [OddaljenoPravila.DEJANJE]; dovoljen je
 * napravam, ki jim je odprt predvajalnik (cast/DostopPravila, kot play.state in play.stop).
 */
object OddaljenoPredvajanje {
    private const val TAG = "SafeerOddaljeno"

    private val glavna = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ daljinec

    /** Naprava, ki jo ta naprava upravlja (null: seje ni). Spreminja samo glavna nit. */
    @Volatile var cilj: LinkOdjemalec.Naprava? = null
        private set
    @Volatile var stanje: OddaljenoPravila.Stanje = OddaljenoPravila.Stanje()
        private set

    /** Zasloni, ki kazejo stanje cilja (osvezijo prikaz na glavni niti). */
    val poslusalci = CopyOnWriteArraySet<() -> Unit>()
    /** Stevilo vidnih zaslonov z daljincem: samo tedaj se stanje osvezuje (v ozadju nobenih zahtev). */
    private var vidnih = 0
    private var napak = 0
    private var zadnjiOdgovorMs = 0L
    private var app: Context? = null
    /** Zaporedna stevilka seje: odgovor stare seje (ali odgovor, ki ga je prehitel novejsi ukaz) se ne uposteva. */
    private var seja = 0
    private var ukazov = 0

    private fun ura() = SystemClock.elapsedRealtime()

    /** Ali gumbi predvajalnika te naprave zdaj upravljajo cilj. */
    fun upravlja(tuIgra: Boolean): Boolean = OddaljenoPravila.upravljaCilj(cilj != null, stanje.kajIgra, tuIgra)

    /** Zacne sejo daljinca za [n] (prejsnja se konca). */
    fun zacni(ctx: Context, n: LinkOdjemalec.Naprava) {
        app = ctx.applicationContext
        seja++
        cilj = n
        stanje = OddaljenoPravila.Stanje()
        napak = 0
        zadnjiOdgovorMs = ura()
        Log.i(TAG, "seja: ${n.id}")
        javi()
        osvezi()
    }

    /** Konec seje daljinca (uporabnik je izbral »Prekini upravljanje« ali je tu zacel predvajati kaj drugega). */
    fun koncaj() {
        if (cilj == null) return
        seja++
        cilj = null
        stanje = OddaljenoPravila.Stanje()
        glavna.removeCallbacks(zanka)
        javi()
    }

    /** Zaslon z daljincem je viden / skrit (onResume / onPause). */
    fun vidno(da: Boolean) {
        vidnih = (vidnih + if (da) 1 else -1).coerceAtLeast(0)
        glavna.removeCallbacks(zanka)
        if (cilj == null) return
        if (da) osvezi() else naslednjaOsvezitev()
    }

    private val zanka = Runnable { osvezi() }

    private fun naslednjaOsvezitev() {
        glavna.removeCallbacks(zanka)
        val razmik = OddaljenoPravila.razmikOsvezitve(vidnih > 0, stanje.igra)
        if (cilj != null && razmik > 0) glavna.postDelayed(zanka, razmik)
    }

    private fun osvezi() = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.STANJE))

    fun premor() = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.PREMOR))
    fun nadaljuj() = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.NADALJUJ))
    fun preklopi() = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.PREKLOPI))
    fun naslednja() = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.NASLEDNJA))
    fun prejsnja() = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.PREJSNJA))
    fun skoci(ms: Long) = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.SKOCI, ms = ms.coerceAtLeast(0L)))
    fun glasnostZa(korak: Int) = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.GLASNOST, korak = korak))
    fun izVrste(indeks: Int) = poslji(OddaljenoPravila.Ukaz(OddaljenoPravila.IZ_VRSTE, indeks = indeks))

    /** Napovedan cas na cilju za drsnik. */
    fun polozajMs(): Long = OddaljenoPravila.polozajZdaj(stanje, ura())

    private fun poslji(u: OddaljenoPravila.Ukaz) {
        val n = cilj ?: return
        val ctx = app ?: return
        if (u.vrsta != OddaljenoPravila.STANJE) { stanje = OddaljenoPravila.poUkazu(stanje, u, ura()); javi() }
        val moja = seja
        val st = ++ukazov
        val p = JSONObject().put("action", u.vrsta)
        if (u.vrsta == OddaljenoPravila.SKOCI) p.put("position_ms", u.ms)
        if (u.vrsta == OddaljenoPravila.GLASNOST) { if (u.glasnost >= 0) p.put("level", u.glasnost) else p.put("step", u.korak) }
        if (u.vrsta == OddaljenoPravila.IZ_VRSTE) p.put("index", u.indeks)
        LinkUpravitelj.pridobi(ctx).ukaz(n.id, OddaljenoPravila.DEJANJE, p, 4_000, LinkOdjemalec.Odgovor { izid, napaka ->
            glavna.post { naOdgovor(moja, st, izid, napaka) }
        })
    }

    private fun naOdgovor(moja: Int, st: Int, izid: JSONObject?, napaka: String) {
        if (moja != seja) return
        val d = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
        if (d == null) {
            napak++
            if (izid?.optString("code") == "neznano_dejanje" ||
                OddaljenoPravila.sejaKoncana(napak, zadnjiOdgovorMs, ura())) {
                Log.i(TAG, "seja koncana: ${izid?.optString("code").orEmpty().ifBlank { napaka }}")
                koncaj(); return
            }
            naslednjaOsvezitev(); return
        }
        napak = 0
        zadnjiOdgovorMs = ura()
        // Odgovor starejsega ukaza ne povozi napovedi novejsega (dva hitra dotika »Premor« / »Nadaljuj«).
        if (st == ukazov) { stanje = izJson(d, ura()); javi() }
        naslednjaOsvezitev()
    }

    private fun javi() { for (p in poslusalci) try { p() } catch (_: Throwable) { } }

    fun izJson(d: JSONObject, prejetoMs: Long): OddaljenoPravila.Stanje {
        if (!d.optBoolean("playing")) return OddaljenoPravila.Stanje(glasnost = d.optInt("volume", -1), prejetoMs = prejetoMs)
        val v = d.optJSONArray("queue") ?: JSONArray()
        return OddaljenoPravila.Stanje(
            naslov = d.optString("title"), izvajalec = d.optString("artist"), igra = d.optBoolean("is_playing"),
            polozajMs = d.optLong("position_ms"), trajanjeMs = d.optLong("duration_ms"), glasnost = d.optInt("volume", -1),
            indeks = d.optInt("index", -1), vrsta = (0 until v.length()).map { v.optString(it) }, odVrste = d.optInt("queue_from"),
            zvocnik = d.optString("speaker"), prejetoMs = prejetoMs)
    }

    // ------------------------------------------------------------------ cilj

    /**
     * `play.control` z druge naprave: izvede ukaz (na glavni niti) in vrne stanje predvajanja. Vsebine zasebnega dodatka
     * ne pove (kot play.state); deljenje izklopljeno (Predaja.deli) - ne pove in ne izvede nicesar.
     */
    fun izvedi(ctx: Context, parametri: JSONObject): JSONObject {
        if (!Predaja.deli(ctx)) return JSONObject().put("shared", false).put("playing", false)
        val u = OddaljenoPravila.preberiUkaz(parametri.optString("action"), parametri.optLong("position_ms", -1L),
            parametri.optInt("level", -1), parametri.optInt("step", 0), parametri.optInt("index", -1))
            ?: return JSONObject().put("error", "neznan_ukaz")
        return if (Looper.myLooper() == Looper.getMainLooper()) { izvediTu(ctx, u); stanjeTu(ctx) }
        else {
            val izid = java.util.concurrent.ArrayBlockingQueue<JSONObject>(1)
            glavna.post { try { izvediTu(ctx, u); izid.offer(stanjeTu(ctx)) } catch (e: Throwable) { izid.offer(JSONObject().put("playing", false)) } }
            izid.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: JSONObject().put("playing", false)
        }
    }

    private fun izvediTu(ctx: Context, u: OddaljenoPravila.Ukaz) {
        if (GlasbaStoritev.predvajalnik == null) return
        // To napravo upravlja druga: ce je ta sama upravljala tretjo, se ta seja konca - ukazi veljajo tukajsnjemu predvajanju.
        if (u.vrsta != OddaljenoPravila.STANJE) koncaj()
        when (u.vrsta) {
            OddaljenoPravila.PREMOR -> GlasbaStoritev.premorTu()
            OddaljenoPravila.NADALJUJ -> GlasbaStoritev.nadaljuj()
            OddaljenoPravila.PREKLOPI -> GlasbaStoritev.preklopi()
            OddaljenoPravila.NASLEDNJA -> GlasbaStoritev.naslednja()
            OddaljenoPravila.PREJSNJA -> GlasbaStoritev.prejsnja()
            OddaljenoPravila.SKOCI -> GlasbaStoritev.skoci(u.ms)
            OddaljenoPravila.IZ_VRSTE -> GlasbaStoritev.predvajajIzVrste(u.indeks)
            OddaljenoPravila.GLASNOST -> nastaviGlasnost(ctx, u)
        }
    }

    /** Glasnost tistega, ki igra: zvocnika v omrezju ali glasbe te naprave (v odstotkih). */
    private fun nastaviGlasnost(ctx: Context, u: OddaljenoPravila.Ukaz) {
        if (GlasbaStoritev.naZvocniku()) {
            Zvocniki.glasnostNa(OddaljenoPravila.novaGlasnost(Zvocniki.glasnost, u.glasnost, u.korak))
            return
        }
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val najvec = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val zdaj = OddaljenoPravila.vOdstotek(am.getStreamVolume(AudioManager.STREAM_MUSIC), najvec)
        val nova = OddaljenoPravila.novaGlasnost(zdaj, u.glasnost, u.korak)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, OddaljenoPravila.vStopnjo(nova, najvec), 0)
    }

    private fun glasnostTu(ctx: Context): Int {
        if (GlasbaStoritev.naZvocniku()) return Zvocniki.glasnost
        val am = ctx.getSystemService(AudioManager::class.java) ?: return -1
        return OddaljenoPravila.vOdstotek(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    private fun stanjeTu(ctx: Context): JSONObject {
        val o = JSONObject().put("shared", true).put("volume", glasnostTu(ctx))
        val p = GlasbaStoritev.predvajalnik
        val sk = GlasbaStoritev.trenutna()
        if (p == null || sk == null) return o.put("playing", false)
        Stremio.pripravi(ctx)
        if (Stremio.jeZasebna(sk)) return o.put("playing", false)
        val vrsta = GlasbaStoritev.vrsta()
        val indeks = p.currentMediaItemIndex
        val okno = OddaljenoPravila.oknoVrste(vrsta.size, indeks)
        val naslovi = JSONArray()
        for (i in okno) naslovi.put(vrsta[i].takeIf { !Stremio.jeZasebna(it) }?.naslov.orEmpty())
        o.put("playing", true).put("title", sk.naslov).put("artist", sk.izvajalec).put("is_playing", GlasbaStoritev.igra())
            .put("position_ms", GlasbaStoritev.polozajMs()).put("duration_ms", GlasbaStoritev.trajanjeMs())
            .put("index", indeks).put("queue", naslovi).put("queue_from", if (okno.isEmpty()) 0 else okno.first)
        Zvocniki.aktivni?.let { if (GlasbaStoritev.naZvocniku()) o.put("speaker", it.ime) }
        return o
    }
}
