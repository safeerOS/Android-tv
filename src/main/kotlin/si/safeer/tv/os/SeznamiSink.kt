package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Seznami predvajanja so enaki na vseh uporabnikovih napravah v Safeer Linku (telefon, tablica, televizor,
 * racunalnik): ko odpres Medijski center, naprava vprasa ostale (`lists.get`) in prevzame, kar je novejse; izbris se
 * prenese enako. Nic ne gre v oblak - samo med napravami v Linku. Pravila: [SeznamiPravila].
 *
 * Protokol (enak v Safeer OS za racunalnik, core/os_media.py):
 *  - `lists.get {}` -> `{lists: [{ime, vir, cas, stevilo}], deleted: {ime: cas}}`
 *  - `lists.get {ime, od}` -> `{ime, vir, cas, stevilo, od, skladbe: [najvec 100]}` (sporocila Linka so omejena)
 */
object SeznamiSink {
    const val DEJANJE = "lists.get"
    /** Zmoznost v prijavi v Link: naprava zna `lists.get`. Starejsih razlicic ne sprasujemo - na racunalniku bi
     *  neznano dejanje sprozilo vprasanje o pravicah naprave. */
    const val ZMOZNOST = "lists"
    private const val TAG = "SafeerSeznami"
    private const val STRAN = 100
    /** Med dvema usklajevanjema (odprtje Medijskega centra je pogosto) naj mine vsaj toliko. */
    private const val PREMOR_MS = 45_000L
    @Volatile private var zadnjic = 0L
    @Volatile private var tece = false

    // ------------------------------------------------------------------ zapis skladbe

    fun vZapis(s: Jamendo.Skladba): SeznamiPravila.Zapis {
        val iskana = UvozSeznama.jeIskana(s)
        val yt = if (iskana) "" else SeznamiPravila.youtubePosnetek(s.povezava)
        val sekund = if (iskana) s.id.removePrefix(UvozSeznama.PREDPONA_ISKANJA).substringBefore('|').toIntOrNull() ?: 0 else 0
        val drugo = yt.isBlank() && !iskana
        return SeznamiPravila.Zapis(s.naslov, s.izvajalec, yt, sekund, s.slika, if (drugo) s.zvok.ifBlank { s.povezava } else "", s.video,
            if (drugo) Predaja.vJson(s).toString() else "")
    }

    fun izZapisa(z: SeznamiPravila.Zapis): Jamendo.Skladba? = when {
        z.youtube.isNotBlank() -> SpletniVir.enota("https://www.youtube.com/watch?v=${z.youtube}", z.naslov.ifBlank { "YouTube" },
            z.izvajalec.ifBlank { "YouTube" }, z.slika.ifBlank { "https://i.ytimg.com/vi/${z.youtube}/mqdefault.jpg" }, z.video)
        z.izvirnik.isNotBlank() -> try { Predaja.izJson(JSONObject(z.izvirnik)).takeIf { it.id.isNotBlank() } } catch (_: Exception) { null }
        // Skladba iz kataloga racunalnika z neposrednim naslovom (npr. Jamendo).
        z.url.startsWith("https://") -> Jamendo.Skladba(z.url, z.naslov, z.izvajalec, z.slika, z.url, z.url, video = z.video)
        z.naslov.isNotBlank() -> UvozSeznama.iskana(z.naslov, z.izvajalec, z.sekund, z.slika)
        else -> null
    }

    private fun json(z: SeznamiPravila.Zapis): JSONObject = JSONObject().put("naslov", z.naslov).put("izvajalec", z.izvajalec)
        .put("youtube", z.youtube).put("sekund", z.sekund).put("slika", z.slika).apply {
            if (z.url.isNotBlank()) put("url", z.url)
            if (z.video) put("video", true)
            if (z.izvirnik.isNotBlank()) put("android", z.izvirnik)
        }

    private fun zapis(o: JSONObject) = SeznamiPravila.Zapis(o.optString("naslov"), o.optString("izvajalec"), o.optString("youtube"),
        o.optInt("sekund"), o.optString("slika"), o.optString("url"), o.optBoolean("video"), o.optString("android"))

    // ------------------------------------------------------------------ ta naprava odgovarja (Daljinec: lists.get)

    fun izvoz(ctx: Context, p: JSONObject): JSONObject {
        val seznami = MedijskiViri.seznami(ctx)
        val ime = p.optString("ime")
        if (ime.isBlank()) {
            val a = JSONArray()
            seznami.forEach { a.put(JSONObject().put("ime", it.ime).put("vir", it.vir).put("cas", it.cas).put("stevilo", it.skladbe.size)) }
            val izbrisani = JSONObject()
            MedijskiViri.izbrisaniSeznami(ctx).forEach { (k, v) -> izbrisani.put(k, v) }
            return JSONObject().put("lists", a).put("deleted", izbrisani)
        }
        val sz = seznami.firstOrNull { it.ime == ime } ?: return JSONObject().put("ime", ime).put("stevilo", 0).put("skladbe", JSONArray())
        val od = p.optInt("od").coerceAtLeast(0)
        val skladbe = JSONArray()
        sz.skladbe.drop(od).take(STRAN).forEach { skladbe.put(json(vZapis(it))) }
        return JSONObject().put("ime", sz.ime).put("vir", sz.vir).put("cas", sz.cas).put("stevilo", sz.skladbe.size).put("od", od).put("skladbe", skladbe)
    }

    // ------------------------------------------------------------------ ta naprava prevzema

    /**
     * Vprasa naprave v Linku in prevzame novejse sezname in izbrise. Tece v ozadju; [nato] (glavna nit) dobi true, ce se
     * je tukaj kaj spremenilo. Brez Linka ali drugih naprav se ne zgodi nic.
     */
    fun uskladi(ctx: Context, nato: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        val zdaj = android.os.SystemClock.elapsedRealtime()
        if (tece || (zadnjic != 0L && zdaj - zadnjic < PREMOR_MS)) return
        val link = try { LinkUpravitelj.pridobi(app) } catch (_: Throwable) { return }
        val naprave = try { link.naprave.filter { !link.jeTaNaprava(it) && "remote" in it.zmoznosti && ZMOZNOST in it.zmoznosti } } catch (_: Throwable) { emptyList() }
        if (!link.povezan || naprave.isEmpty()) { Log.i(TAG, "brez uskladitve: povezan=${link.povezan}, naprav=${naprave.size}"); return }
        Log.i(TAG, "uskladitev z ${naprave.size} napravami")
        tece = true; zadnjic = zdaj
        Thread {
            var spremenjeno = false
            try {
                for (n in naprave) {
                    try { if (prevzemiOd(app, link, n.id)) spremenjeno = true } catch (e: Exception) { Log.w(TAG, "naprava ${n.id}: ${e.message}") }
                }
            } finally { tece = false }
            if (spremenjeno) Handler(Looper.getMainLooper()).post { nato(true) }
        }.apply { name = "Safeer-seznami"; isDaemon = true; start() }
    }

    /** Ukaz po Linku, na katerega pocakamo (klic z delovne niti); null = naprava ni odgovorila ali dejanja ne pozna. */
    private fun vprasaj(link: LinkUpravitelj, naprava: String, p: JSONObject): JSONObject? {
        val zapah = CountDownLatch(1)
        var odgovor: JSONObject? = null
        link.ukaz(naprava, DEJANJE, p, 6_000, LinkOdjemalec.Odgovor { izid, napaka ->
            odgovor = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
            if (odgovor == null) Log.i(TAG, "$naprava ne da seznamov: ${napaka ?: izid?.optString("message")}")
            zapah.countDown()
        })
        zapah.await(8, TimeUnit.SECONDS)
        return odgovor
    }

    private fun prevzemiOd(app: Context, link: LinkUpravitelj, naprava: String): Boolean {
        val kazalo = vprasaj(link, naprava, JSONObject()) ?: return false
        var spremenjeno = false
        // 1) Izbrisi: seznam, ki je bil drugje izbrisan in ga tu od takrat nismo spremenili, izgine tudi tukaj.
        kazalo.optJSONObject("deleted")?.let { izbrisani ->
            val moji = MedijskiViri.seznami(app).associateBy { it.ime }
            for (ime in izbrisani.keys()) {
                val cas = izbrisani.optLong(ime)
                val moj = moji[ime]
                if (moj == null) MedijskiViri.zapomniIzbrisSeznama(app, ime, cas)
                else if (SeznamiPravila.izbrisVelja(moj.cas, cas)) { MedijskiViri.odstraniSeznam(app, ime, cas); spremenjeno = true }
            }
        }
        // 2) Seznami: novejsega prevzamemo, enako stara z razlicnim stevilom skladb zdruzimo.
        val tuji = kazalo.optJSONArray("lists") ?: return spremenjeno
        for (i in 0 until tuji.length()) {
            val g = tuji.optJSONObject(i) ?: continue
            val tuj = SeznamiPravila.Glava(g.optString("ime"), g.optLong("cas"), g.optInt("stevilo"))
            if (tuj.ime.isBlank()) continue
            val moj = MedijskiViri.seznami(app).firstOrNull { it.ime == tuj.ime }
            val korak = SeznamiPravila.korak(moj?.let { SeznamiPravila.Glava(it.ime, it.cas, it.skladbe.size) },
                MedijskiViri.izbrisaniSeznami(app)[tuj.ime] ?: 0L, tuj)
            if (korak == SeznamiPravila.Korak.NIC) continue
            val tuje = skladbeOd(link, naprava, tuj) ?: continue
            val moje = moj?.skladbe?.map { vZapis(it) } ?: emptyList()
            val nove = if (korak == SeznamiPravila.Korak.PREVZEMI) SeznamiPravila.prevzemi(moje, tuje, MedijskiViri.NAJVEC_V_SEZNAMU)
                else SeznamiPravila.zdruzi(moje, tuje, MedijskiViri.NAJVEC_V_SEZNAMU)
            val skladbe = nove.mapNotNull { izZapisa(it) }.distinctBy { it.id }
            if (skladbe.isEmpty()) continue
            MedijskiViri.nastaviSeznam(app, MedijskiViri.Seznam(tuj.ime, skladbe, tuj.cas, g.optString("vir").ifBlank { moj?.vir.orEmpty() }))
            Log.i(TAG, "seznam »${tuj.ime}« od $naprava: $korak, ${skladbe.size} skladb")
            spremenjeno = true
        }
        return spremenjeno
    }

    /** Vse skladbe seznama po straneh; null, ce naprava vmes ne odgovori (napol prenesenega seznama ne shranimo). */
    private fun skladbeOd(link: LinkUpravitelj, naprava: String, g: SeznamiPravila.Glava): List<SeznamiPravila.Zapis>? {
        val vse = ArrayList<SeznamiPravila.Zapis>()
        var od = 0
        while (od < g.stevilo && od < MedijskiViri.NAJVEC_V_SEZNAMU) {
            val stran = vprasaj(link, naprava, JSONObject().put("ime", g.ime).put("od", od))?.optJSONArray("skladbe") ?: return null
            if (stran.length() == 0) break
            for (i in 0 until stran.length()) stran.optJSONObject(i)?.let { vse += zapis(it) }
            od += stran.length()
        }
        return vse.takeIf { it.isNotEmpty() }
    }
}
