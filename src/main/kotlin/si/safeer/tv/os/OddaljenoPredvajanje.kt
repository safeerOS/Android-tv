package si.safeer.tv.os

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.R
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Naloga 117: predvajanje na drugi napravi v Safeer Linku se upravlja kot lokalno (ideja kot Spotify Connect, koda nasa).
 *
 * Daljinec (ta naprava): po »Poslji na napravo« se zacne seja ([zacni]); cilj se upravlja v oknu »Upravljaj napravo«
 * ([ZvocnikIzbira.upravljajNapravo]), dock in tipke pa ga upravljajo samo, ko tu ni nalozeno nic
 * ([OddaljenoPravila.upravljaCilj], pregled C1). Stanje cilja se osvezuje, dokler je zaslon z daljincem viden (v ozadju le
 * se nekajkrat); gumb se odzove takoj ([OddaljenoPravila.poUkazu]), odgovor cilja ga nato popravi (neuspel ukaz ga vrne).
 * Seja se konca sama, ko cilj ne odgovarja, ne deli vec ali predolgo ne predvaja ([OddaljenoPravila.brezPredvajanja]).
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
    /** Stevilo vidnih zaslonov z daljincem: tedaj se stanje osvezuje (v ozadju le se nekajkrat, glej [vOzadju]). */
    private var vidnih = 0
    private var napak = 0
    private var zadnjiOdgovorMs = 0L
    /** Zacetek seje ali zadnji odgovor, ko je cilj igral ([OddaljenoPravila.brezPredvajanja]). */
    private var zadnjeIgranjeMs = 0L
    /** Osvezitve brez vidnega okna od skritja, dotika docka ali ukaza ([OddaljenoPravila.razmikOsvezitve]). */
    private var vOzadju = 0
    private var zadnjaZahtevaMs = 0L
    /** Zakaj se je zadnja seja koncala ([OddaljenoPravila.KONEC_UPORABNIK] ...); prazno, dokler seja tece. */
    @Volatile var zadnjiKonec: String = ""
        private set
    private var app: Context? = null
    /** Zaporedna stevilka seje: odgovor stare seje (ali odgovor, ki ga je prehitel novejsi ukaz) se ne uposteva. */
    private var seja = 0
    private var ukazov = 0

    private fun ura() = SystemClock.elapsedRealtime()

    /** Ali gumbi predvajalnika te naprave zdaj upravljajo cilj ([tuNalozeno]: tu je nalozena skladba - potem ne). */
    fun upravlja(tuNalozeno: Boolean): Boolean = OddaljenoPravila.upravljaCilj(cilj != null, stanje.kajIgra, tuNalozeno)

    /** Zacne sejo daljinca za [n] (prejsnja se konca). */
    fun zacni(ctx: Context, n: LinkOdjemalec.Naprava) {
        app = ctx.applicationContext
        seja++
        cilj = n
        stanje = OddaljenoPravila.Stanje()
        napak = 0
        zadnjiOdgovorMs = ura()
        zadnjeIgranjeMs = ura()
        vOzadju = 0
        zadnjiKonec = ""
        Log.i(TAG, "seja: ${n.id}")
        javi()
        osvezi()
    }

    /**
     * Konec seje daljinca: uporabnik je izbral »Prekini upravljanje«, tu zacel predvajati kaj drugega ([OddaljenoPravila.KONEC_TU])
     * ali je sejo koncal cilj ([naOdgovor]). Klic z glavne niti; sporocilo samo, ce je sejo koncal cilj (pregled C7).
     */
    fun koncaj(razlog: String = OddaljenoPravila.KONEC_UPORABNIK) {
        val n = cilj ?: return
        seja++
        cilj = null
        stanje = OddaljenoPravila.Stanje()
        zadnjiKonec = razlog
        glavna.removeCallbacks(zanka)
        Log.i(TAG, "seja koncana: $razlog")
        if (OddaljenoPravila.javiKonec(razlog)) sporociKonec(n, razlog)
        javi()
    }

    private fun sporociKonec(n: LinkOdjemalec.Naprava, razlog: String) {
        val ctx = app ?: return
        val niz = when (razlog) {
            OddaljenoPravila.KONEC_NE_DELI -> R.string.naprava_ne_deli
            OddaljenoPravila.KONEC_STARA -> R.string.os_predaja_poslji_stara
            OddaljenoPravila.KONEC_BREZ_PREDVAJANJA -> R.string.naprava_brez_predvajanja
            else -> R.string.naprava_konec
        }
        try { Toast.makeText(ctx, ctx.getString(niz, DatotekeActivity.lepoIme(n.ime)), Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
    }

    /** Zaslon z daljincem je viden / skrit (okno odprto / zaprto). Vsak true ima natanko en false. */
    fun vidno(da: Boolean) {
        vidnih = (vidnih + if (da) 1 else -1).coerceAtLeast(0)
        glavna.removeCallbacks(zanka)
        if (cilj == null) return
        vOzadju = 0
        if (da) osvezi() else naslednjaOsvezitev()
    }

    /**
     * Pregled C2: zaslon brez lastnega stevca vidnosti (dock na domacem zaslonu) kaze stanje cilja - ce je starejse od
     * [OddaljenoPravila.SVEZE_MS], ga osvezi in dovoli se nekaj osvezitev v ozadju (potem spet nic).
     */
    fun prikazano() {
        if (cilj == null || ura() - zadnjaZahtevaMs < OddaljenoPravila.SVEZE_MS) return
        vOzadju = 0
        osvezi()
    }

    private val zanka = Runnable { osvezi() }

    private fun naslednjaOsvezitev() {
        glavna.removeCallbacks(zanka)
        if (cilj == null) return
        val razmik = OddaljenoPravila.razmikOsvezitve(vidnih > 0, stanje.igra, vOzadju)
        if (razmik <= 0) return
        if (vidnih == 0) vOzadju++
        glavna.postDelayed(zanka, razmik)
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
        val prej = stanje
        if (u.vrsta != OddaljenoPravila.STANJE) { stanje = OddaljenoPravila.poUkazu(stanje, u, ura()); vOzadju = 0; javi() }
        val moja = seja
        val st = ++ukazov
        zadnjaZahtevaMs = ura()
        val p = JSONObject().put("action", u.vrsta)
        if (u.vrsta == OddaljenoPravila.SKOCI) p.put("position_ms", u.ms)
        if (u.vrsta == OddaljenoPravila.GLASNOST) { if (u.glasnost >= 0) p.put("level", u.glasnost) else p.put("step", u.korak) }
        if (u.vrsta == OddaljenoPravila.IZ_VRSTE) p.put("index", u.indeks)
        LinkUpravitelj.pridobi(ctx).ukaz(n.id, OddaljenoPravila.DEJANJE, p, 4_000, LinkOdjemalec.Odgovor { izid, napaka ->
            glavna.post { naOdgovor(moja, st, u, prej, izid, napaka) }
        })
    }

    private fun naOdgovor(moja: Int, st: Int, u: OddaljenoPravila.Ukaz, prej: OddaljenoPravila.Stanje, izid: JSONObject?, napaka: String) {
        if (moja != seja) return
        val ok = izid?.optBoolean("ok") == true
        val d = if (ok) izid?.optJSONObject("data") else null
        // Pregled C2: dostop zaprt, deljenje izklopljeno ali cilj ukaza ne pozna - seja se konca takoj (s sporocilom).
        OddaljenoPravila.konecPoOdgovoru(ok, izid?.optString("code").orEmpty().ifBlank { napaka }, d?.optBoolean("shared", true) ?: true)
            ?.let { koncaj(it); return }
        if (d == null) {
            napak++
            if (OddaljenoPravila.sejaKoncana(napak, zadnjiOdgovorMs, ura())) { koncaj(OddaljenoPravila.KONEC_NI_ODZIVA); return }
            // Pregled C8: ukaz ni prisel do cilja - gumb ne kaze vec napovedi, ki se ni zgodila (ce je ni prehitel novejsi).
            if (u.vrsta != OddaljenoPravila.STANJE && st == ukazov) { stanje = prej; javi() }
            naslednjaOsvezitev(); return
        }
        napak = 0
        zadnjiOdgovorMs = ura()
        // Odgovor starejsega ukaza ne povozi napovedi novejsega (dva hitra dotika »Premor« / »Nadaljuj«).
        if (st == ukazov) { stanje = izJson(d, ura()); javi() }
        if (stanje.igra) zadnjeIgranjeMs = ura()
        if (OddaljenoPravila.brezPredvajanja(stanje, zadnjeIgranjeMs, ura())) { koncaj(OddaljenoPravila.KONEC_BREZ_PREDVAJANJA); return }
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
        if (u.vrsta != OddaljenoPravila.STANJE) koncaj(OddaljenoPravila.KONEC_TU)
        when (u.vrsta) {
            OddaljenoPravila.PREMOR -> GlasbaStoritev.premorTu()
            OddaljenoPravila.NADALJUJ -> GlasbaStoritev.nadaljujTu()
            OddaljenoPravila.PREKLOPI -> GlasbaStoritev.preklopiTu()
            OddaljenoPravila.NASLEDNJA -> GlasbaStoritev.naslednjaTu()
            OddaljenoPravila.PREJSNJA -> GlasbaStoritev.prejsnjaTu()
            // Pregled C5: v zivo ali neprevijalno - skoci() ga ne izvede.
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
