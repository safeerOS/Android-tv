package si.safeer.tv.os

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import org.json.JSONObject
import si.safeer.tv.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Zakon solidarnosti, korak 5 (odjemalec): ta naprava videa ne zna predvajati (ni dekodirnika za obliko,
 * locljivost ali zvok) - namesto napake prosi napravo v Safeer Linku z najboljsim strojnim kodirnikom, da ga
 * sproti pretvarja v H.264/AAC in nam poslje kot tok (`video.stream`, Pretok na pomocniku).
 *
 * Nadzornik: vse naprave vprasa hkrati (host.info, 2,5 s), izbere tisto, ki sme pomagati, ima strojni kodirnik
 * za 1080p, zna prebrati izvirnik in ni ta naprava; ce zavrne (medtem ni vec moci), vzame naslednjo.
 */
object SprotnaPomoc {
    private const val TAG = "SafeerSprotnaPomoc"
    const val PRIPONA = "#live"

    /** Napake, pri katerih pomaga pretvorba: dekodirnik (video ali zvok) manjka, odpove ali oblike ne podpira. */
    fun jeNapakaDekodiranja(e: PlaybackException): Boolean = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)

    /** Oblika, ki je ta naprava ni zmogla (iz napake upodabljalnika): {mime, width, height} ali prazno. */
    @OptIn(UnstableApi::class)
    fun oblikaIzNapake(e: PlaybackException): JSONObject {
        val f = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat ?: return JSONObject()
        return oblikaIzFormata(f)
    }

    fun oblikaIzFormata(f: androidx.media3.common.Format): JSONObject {
        val o = JSONObject()
        f.sampleMimeType?.let { o.put("mime", it) }
        if (f.width > 0 && f.height > 0) o.put("width", f.width).put("height", f.height)
        return o
    }

    /**
     * Sled, ki je ta naprava ne zmore: za vsako vrsto (video, zvok) gledamo samo IZBRANO sled. Tezava je,
     * ce izbrana video sled presega dekodirnik (4K na napravi s Full HD dekodirnikom se vrti, a zatika) ali
     * ce za vrsto ni izbrana nobena sled, ceprav obstajajo (noben zvok ni podprt -> film brez zvoka).
     * Neizbrane skupine (druga zvocna sled v DTS ob podprti AAC) NISO tezava - predvajanje tece in ga ne
     * smemo prekiniti (Stremio tok na tablici, 1. 10. 2026). Vrne obliko te sledi ali null.
     */
    @OptIn(UnstableApi::class)
    fun nepodprtaSled(tracks: androidx.media3.common.Tracks): androidx.media3.common.Format? {
        for (vrsta in intArrayOf(androidx.media3.common.C.TRACK_TYPE_VIDEO, androidx.media3.common.C.TRACK_TYPE_AUDIO)) {
            var obstaja = false
            var izbranaPodprta = false
            var izbranaNepodprta: androidx.media3.common.Format? = null
            var prvaNepodprta: androidx.media3.common.Format? = null
            for (g in tracks.groups) {
                if (g.type != vrsta) continue
                obstaja = true
                for (i in 0 until g.length) {
                    val podprta = g.getTrackSupport(i) == androidx.media3.common.C.FORMAT_HANDLED
                    if (g.isTrackSelected(i)) { if (podprta) izbranaPodprta = true else izbranaNepodprta = g.getTrackFormat(i) }
                    else if (!podprta && prvaNepodprta == null) prvaNepodprta = g.getTrackFormat(i)
                }
            }
            if (!obstaja || izbranaPodprta) continue
            if (izbranaNepodprta != null) {
                // Izbrana, a presega zmoznosti: pri videu stejemo le, ce dekodirnika za to velikost res ni
                // (raven/profil cez deklarirano se navadno predvaja); zvok, ki presega, ostane.
                if (vrsta == androidx.media3.common.C.TRACK_TYPE_VIDEO && !znaDekodirati(izbranaNepodprta)) return izbranaNepodprta
                continue
            }
            // Nobena sled te vrste ni izbrana (predvajalnik je ni mogel): vzrok je prva nepodprta.
            if (prvaNepodprta != null) return prvaNepodprta
        }
        return null
    }

    /** Ali ima ta naprava dekodirnik za obliko v njeni velikosti (brez podatkov: da). */
    fun znaDekodirati(f: androidx.media3.common.Format): Boolean {
        val mime = f.sampleMimeType ?: return true
        if (f.width <= 0 || f.height <= 0) return true
        return try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(android.media.MediaFormat.createVideoFormat(mime, f.width, f.height)) != null
        } catch (_: Throwable) { true }
    }

    /** Vir, ki ga lahko bere le ta naprava (krajevni torrent motor 127.0.0.1): pomocnik ga ne doseze. */
    fun jeKrajevniVir(url: String): Boolean {
        val g = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("").lowercase()
        return g.isBlank() || g == "localhost" || g == "::1" || g.startsWith("127.") || g.startsWith("[::1")
    }

    /** Ta skladba je ze sprotni tok pomocnika: ce ne gre niti ta, ne prosimo naprej. */
    fun jeSprotniTok(sk: Jamendo.Skladba): Boolean = sk.id.endsWith(PRIPONA)

    /**
     * Ocena pomocnika (0 = ne pride v postev): strojni kodirnik pred programskim (racunalnik s ffmpeg brez
     * VAAPI), nato najvecja sirina H.264 kodirnika, nato jedra.
     */
    fun ocena(info: JSONObject, oblika: JSONObject): Long {
        if (info.optJSONObject("pomoc")?.optBoolean("lahko", true) == false) return 0
        val gpu = info.optJSONObject("gpu") ?: return 0
        val strojno = gpu.optBoolean("strojno")
        if (!strojno && !gpu.optBoolean("ffmpeg")) return 0
        var sirina = 0
        val k = gpu.optJSONArray("kodirniki")
        for (i in 0 until (k?.length() ?: 0)) {
            val e = k!!.optJSONObject(i) ?: continue
            if (e.optString("vrsta") in setOf("avc", "h264")) sirina = maxOf(sirina, e.optInt("sirina", 1920))
        }
        if (sirina < 1920) return 0
        // Ali zna prebrati izvirnik (dekodirniki, kot jih javi host.info); brez podatkov preveri pomocnik sam.
        val mime = oblika.optString("mime").removePrefix("video/")
        val dek = gpu.optJSONArray("dekodirniki")
        if (mime.isNotBlank() && oblika.optInt("width") > 0 && dek != null) {
            val w = maxOf(oblika.optInt("width"), oblika.optInt("height")); val h = minOf(oblika.optInt("width"), oblika.optInt("height"))
            var zna = false
            for (i in 0 until dek.length()) {
                val d = dek.optJSONObject(i) ?: continue
                if (d.optString("vrsta") != mime) continue
                val dw = maxOf(d.optInt("sirina"), d.optInt("visina")); val dh = minOf(d.optInt("sirina"), d.optInt("visina"))
                if (w <= dw && h <= dh) { zna = true; break }
            }
            if (!zna) return 0
        }
        return (if (strojno) 1_000_000_000L else 0L) + sirina.toLong() * 1000 + (info.optJSONObject("cpu")?.optInt("jedra") ?: 0)
    }

    /**
     * Poskusi predvajati [sk] prek pomocnika. [naStanje] dobi sporocilo za zaslon, [naKonec] true, ce je
     * pomocnik prevzel (predvajanje se je znova zacelo), sicer false (prikazi navadno napako).
     */
    fun poskusi(ctx: Context, sk: Jamendo.Skladba, streznik: DatotekeActivity.Streznik?, oblika: JSONObject,
                pozicijaMs: Long, trajanjeMs: Long, naStanje: (String) -> Unit, naKonec: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val link = LinkUpravitelj.pridobi(app)
        if (link.jeKrajevni() || !link.povezan || jeSprotniTok(sk) || !(sk.zvok.startsWith("http://") || sk.zvok.startsWith("https://"))
            || jeKrajevniVir(sk.zvok)) {
            naKonec(false); return
        }
        // Tudi racunalnik (Safeer Control s ffmpeg) je pomocnik: host.info pove gpu.kodirniki, sicer ocena 0.
        val kandidati = link.naprave.filter { n -> !link.jeTaNaprava(n) && "files" in n.zmoznosti }
        if (kandidati.isEmpty()) { naKonec(false); return }
        naStanje(app.getString(R.string.os_sprotno_iscem))
        val ocene = ConcurrentHashMap<String, Long>()
        var cakam = kandidati.size
        val glavna = android.os.Handler(android.os.Looper.getMainLooper())
        fun izberi() {
            val vrsta = kandidati.filter { (ocene[it.id] ?: 0L) > 0 }.sortedByDescending { ocene[it.id] }
            if (vrsta.isEmpty()) { Log.i(TAG, "Nobena naprava ne more pomagati: $ocene"); naKonec(false); return }
            prosi(app, link, vrsta, 0, sk, streznik, oblika, pozicijaMs, trajanjeMs, naStanje, naKonec)
        }
        for (n in kandidati) {
            link.ukaz(n.id, "host.info", JSONObject(), 2_500, LinkOdjemalec.Odgovor { izid, _ ->
                izid?.optJSONObject("data")?.let { ocene[n.id] = ocena(it, oblika) }
                glavna.post { if (--cakam == 0) izberi() }
            })
        }
    }

    private fun prosi(app: Context, link: LinkUpravitelj, vrsta: List<LinkOdjemalec.Naprava>, k: Int, sk: Jamendo.Skladba,
                      streznik: DatotekeActivity.Streznik?, oblika: JSONObject, pozicijaMs: Long, trajanjeMs: Long,
                      naStanje: (String) -> Unit, naKonec: (Boolean) -> Unit) {
        if (k >= vrsta.size) { naKonec(false); return }
        val n = vrsta[k]
        val ime = DatotekeActivity.lepoIme(n.ime).ifBlank { n.id }
        naStanje(app.getString(R.string.os_sprotno_pretvarja, ime))
        val p = JSONObject().put("url", sk.zvok).put("name", sk.naslov.ifBlank { "video" }).put("seek_ms", pozicijaMs)
        // Trajanje pove pomocniku, koliko prostora bo tok vzel in s kaksno bitno hitrostjo naj kodira.
        if (trajanjeMs > 0) p.put("duration_ms", trajanjeMs)
        if (streznik != null) p.put("fp", streznik.odtis).put("token", streznik.zeton)
        // Glave toka (Stremio proxyHeaders): brez njih pomocnik izvirnika ne bi dobil (403).
        SpletniVir.glaveToka(sk.zvok).takeIf { it.isNotEmpty() }?.let { p.put("headers", JSONObject(it)) }
        for (kljuc in oblika.keys()) p.put(kljuc, oblika.get(kljuc))
        link.ukaz(n.id, "video.stream", p, 30_000, LinkOdjemalec.Odgovor { izid, napaka ->
            val d = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
            val url = d?.optString("url").orEmpty()
            if (d == null || !url.startsWith("https://")) {
                Log.i(TAG, "${n.id} ne more: ${izid?.optString("code") ?: napaka}")
                prosi(app, link, vrsta, k + 1, sk, streznik, oblika, pozicijaMs, trajanjeMs, naStanje, naKonec)
                return@Odgovor
            }
            val s = DatotekeActivity.Streznik(url.substringBeforeLast("/live/"), d.optString("fp"), d.optString("token"), n.id)
            val tok = sk.copy(id = sk.id + PRIPONA, zvok = url, povezava = url, mime = "video/mp4", video = true,
                izvajalec = app.getString(R.string.os_sprotno_vir, ime))
            Log.i(TAG, "Sprotni tok z ${n.id}: $url")
            GlasbaStoritev.predvajaj(app, listOf(tok), 0, s)
            naKonec(true)
        })
    }
}
