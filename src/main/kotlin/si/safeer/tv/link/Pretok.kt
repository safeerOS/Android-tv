package si.safeer.tv.link

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Zakon solidarnosti, korak 5: sprotno pretvarjanje. Naprava, ki video ne zna predvajati (televizor brez
 * dekodirnika za 4K HEVC 10-bit, AV1, zvok DTS ...), ga ne prenese - pomocnik s strojnim kodirnikom ga
 * sproti pretvarja v H.264/AAC in mu ga poslje kot tok (fragmentiran MP4 po HTTPS s pripetim potrdilom).
 *
 * `video.stream` {url, fp?, token?, name, mime?, width?, height?, seek_ms?} -> {id, url, fp, token}:
 * pomocnik bere izvirnik naravnost z vira (racunalnik, televizor, splet), pretvorjeni tok pise v
 * predpomnilnik in ga streze na `/live/<id>` ([DatotekeStreznik]). Ko ga nihce ne bere vec, se ustavi.
 * `video.stream_stop` {id} ustavi takoj.
 */
@OptIn(UnstableApi::class)
object Pretok {
    private const val TAG = "SafeerPretok"
    private const val VISINA = 1080
    private const val BREZ_BRALCA_MS = 60_000L

    class Tok(val id: String, val ime: String, val datoteka: File) {
        @Volatile var transformer: Transformer? = null
        @Volatile var koncano = false
        @Volatile var napaka = ""
        @Volatile var zadnjiBralec = System.currentTimeMillis()
        @Volatile var bralcev = 0
    }

    private val tokovi = ConcurrentHashMap<String, Tok>()
    private val glavna = Handler(Looper.getMainLooper())

    /** audio_langs iz zahteve: najvec 4 kode ISO 639 (2-3 crke); drugo zavrzemo. */
    internal fun jezikiZvoka(polje: org.json.JSONArray?): List<String> {
        if (polje == null) return emptyList()
        return (0 until minOf(polje.length(), 4)).map { polje.optString(it).trim().lowercase() }
            .filter { it.length in 2..3 && it.all { z -> z in 'a'..'z' } }.distinct()
    }

    fun zacni(context: Context, p: JSONObject, posiljatelj: String): Daljinec.Izid {
        if (Build.VERSION.SDK_INT < 29) return Daljinec.Izid(false, "Sprotno pretvarjanje potrebuje Android 10 ali novejsi", koda = "ni_podprto")
        val url = p.optString("url"); val odtis = p.optString("fp"); val zeton = p.optString("token")
        val ime = p.optString("name").substringAfterLast('/').trim().take(120).ifBlank { "video" }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return Daljinec.Izid(false, "Nepopolna zahteva", koda = "napacna_zahteva")
        if (odtis.isNotBlank() && odtis.length != 64) return Daljinec.Izid(false, "Nepopolna zahteva", koda = "napacna_zahteva")
        val mime = p.optString("mime"); val w = p.optInt("width"); val h = p.optInt("height")
        val glave = glaveZahteve(p.optJSONObject("headers"))
        // Jezik zvocne sledi, ki ga gledalec zeli (lastnik, 11. 10. 2026: pretvorba je vzela privzeto, ne angleske).
        val jeziki = jezikiZvoka(p.optJSONArray("audio_langs"))
        if (mime.startsWith("video/") && w > 0 && h > 0 && !znaDekodirati(MediaFormat.createVideoFormat(mime, w, h)))
            return Daljinec.Izid(false, "Naprava tega videa ne zna prebrati", koda = "ne_zna_dekodirati")
        val pomoc = Zmogljivost.porocilo(context).optJSONObject("pomoc")
        if (pomoc?.optBoolean("lahko", true) == false)
            return Daljinec.Izid(false, "Naprava ta trenutek ne more pomagati", koda = pomoc.optString("razlog"))
        // En tok naenkrat: drugi bi oba zatikala.
        val tece = tokovi.values.firstOrNull { !it.koncano && it.napaka.isBlank() }
        if (tece != null && System.currentTimeMillis() - tece.zadnjiBralec < BREZ_BRALCA_MS)
            return Daljinec.Izid(false, "Naprava ze pretvarja drug video", koda = "preobremenjen")
        tece?.let { ustavi(it) }
        val mapa = File(context.cacheDir, "pretok").apply { mkdirs() }
        mapa.listFiles()?.forEach { f -> if (tokovi.values.none { it.datoteka == f }) f.delete() }
        // Pretvorjeni tok raste v predpomnilniku do konca filma: ocena velikosti (najvec 8 Mb/s) + rezerva naprave.
        val trajanjeMs = p.optLong("duration_ms", 0L); val velikost = p.optLong("size", 0L)
        val ocena = if (trajanjeMs > 0) 8_000_000L / 8L * trajanjeMs / 1000L * 12 / 10 else 0L
        if (mapa.usableSpace in 0 until ocena + Pretvorba.rezerva())
            return Daljinec.Izid(false, "Premalo prostora", koda = "ni_prostora")
        val t = Tok(java.util.UUID.randomUUID().toString(), ime, File(mapa, java.util.UUID.randomUUID().toString() + ".mp4"))
        tokovi[t.id] = t
        while (tokovi.size > 8) tokovi.keys.firstOrNull()?.let { k -> tokovi.remove(k)?.let { ustavi(it) } }
        val streznik = DatotekeStreznik.streznikZa(context, posiljatelj)
            ?: run { tokovi.remove(t.id); return Daljinec.Izid(false, "Streznika ni mogoce zagnati", koda = "napaka") }
        val seek = p.optLong("seek_ms", 0L).coerceAtLeast(0L)
        val app = context.applicationContext
        // Velikost izvirnika (za bitno hitrost izhoda) poizvemo v ozadju, ce je odjemalec ne pozna; ukaz tece na glavni niti.
        Thread({
            val vel = if (velikost > 0 || trajanjeMs <= 0) velikost else poizvediVelikost(url, odtis, zeton, glave)
            val bitna = Pretvorba.bitnaHitrost(vel, trajanjeMs, w, h)
            Log.i(TAG, "Tok $ime: izvirnik $vel B, ${trajanjeMs} ms, ${w}x$h -> bitna hitrost $bitna b/s")
            glavna.post { pretvarjaj(app, t, url, odtis, zeton, glave, seek, bitna, h, jeziki) }
        }, "safeer-pretok").apply { isDaemon = true; start() }
        return Daljinec.Izid(true, "Pretvarjam sproti", JSONObject().put("id", t.id)
            .put("url", streznik.optString("base_url") + "/live/" + t.id)
            .put("fp", streznik.optString("fp")).put("token", streznik.optString("token")))
    }

    fun ustaviUkaz(id: String): Daljinec.Izid {
        val t = tokovi.remove(id) ?: return Daljinec.Izid(false, "Ni takega toka", koda = "ni_opravila")
        ustavi(t)
        return Daljinec.Izid(true, "Ustavljeno")
    }

    /** Glave zahteve za izvirnik (Stremio proxyHeaders), ki jih poslje odjemalec: najvec 16, brez prelomov vrstic. */
    private fun glaveZahteve(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (k in o.keys()) {
            val v = o.optString(k)
            if (k.isBlank() || k.length > 64 || v.length > 2048 || k.any { it < ' ' } || v.any { it == '\r' || it == '\n' }) continue
            if (k.equals("Range", true) || k.equals("Host", true) || k.equals("Content-Length", true)) continue
            m[k] = v
            if (m.size >= 16) break
        }
        return m
    }

    /** Velikost izvirnika z enim bajtom (Range 0-0 -> Content-Range: bytes 0-0/skupaj); 0, ce streznik ne pove. */
    private fun poizvediVelikost(url: String, odtis: String, zeton: String, glave: Map<String, String> = emptyMap()): Long = try {
        val odjemalec = if (odtis.isNotBlank()) si.safeer.tv.os.PripetiVir.odjemalecZaStreznik(odtis)
            else okhttp3.OkHttpClient.Builder().connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        val z = okhttp3.Request.Builder().url(url).apply { glave.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-0").apply { if (zeton.isNotBlank()) header("X-Safeer-Token", zeton) }.build()
        odjemalec.newCall(z).execute().use { r ->
            val obseg = r.header("Content-Range").orEmpty().substringAfter('/', "").trim()
            obseg.toLongOrNull() ?: if (r.code == 200) r.header("Content-Length")?.toLongOrNull() ?: 0L else 0L
        }
    } catch (_: Throwable) { 0L }

    private fun znaDekodirati(f: MediaFormat): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
    } catch (_: Throwable) { true }

    private fun ustavi(t: Tok) {
        t.koncano = true
        glavna.post { try { t.transformer?.cancel() } catch (_: Throwable) { } ; t.transformer = null }
        if (t.bralcev == 0) t.datoteka.delete()
    }

    /** Transformer tece na glavni niti; vir bere naravnost z racunalnika/televizorja (pripeto) ali s spleta. */
    private fun pretvarjaj(ctx: Context, t: Tok, url: String, odtis: String, zeton: String, glave: Map<String, String>,
                           seekMs: Long, bitna: Int, visinaVira: Int, jeziki: List<String> = emptyList()) {
        try {
            // UA gre v tovarno (DefaultHttpDataSource z njim prepise glave zahteve), ostale glave toka na vsako zahtevo.
            val vir: DataSource.Factory = if (odtis.isNotBlank()) si.safeer.tv.os.PripetiVir.Tovarna(odtis, zeton, ctx)
                else DefaultHttpDataSource.Factory().setUserAgent(glave.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "Safeer OS")
                    .setDefaultRequestProperties(glave.filterKeys { !it.equals("User-Agent", true) })
                    .setAllowCrossProtocolRedirects(true)
            // Izbira sledi kot privzeto v Transformerju (najvisja bitna hitrost), z zelenim jezikom zvoka - sicer bi
            // pretvorba vzela privzeto sled vira (npr. sinhronizacijo), cetudi je gledalec izbral izvirnik.
            val izbiraSledi = androidx.media3.exoplayer.trackselection.TrackSelector.Factory { c ->
                androidx.media3.exoplayer.trackselection.DefaultTrackSelector(c,
                    androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters.Builder(c)
                        .setForceHighestSupportedBitrate(true)
                        .apply { if (jeziki.isNotEmpty()) setPreferredAudioLanguages(*jeziki.toTypedArray()) }
                        .build())
            }
            val nalagalnik = DefaultAssetLoaderFactory(ctx, DefaultDecoderFactory.Builder(ctx).build(), Clock.DEFAULT,
                DefaultMediaSourceFactory(vir), DataSourceBitmapLoader(ctx), izbiraSledi)
            val kodirnik = DefaultEncoderFactory.Builder(ctx).apply {
                if (bitna > 0) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitna).build())
            }.build()
            val element = EditedMediaItem.Builder(MediaItem.Builder().setUri(url)
                .apply { if (seekMs > 0) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(seekMs).build()) }
                .build())
                // Najvec 1080p: naprava, ki ne zmore izvirnika, naj dobi lahek tok; manjsega ne povecujemo.
                .setEffects(Effects(emptyList(), if (visinaVira in 1..VISINA) emptyList()
                    else listOf<androidx.media3.common.Effect>(Presentation.createForHeight(VISINA))))
                .build()
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setAssetLoaderFactory(nalagalnik)
                .setEncoderFactory(kodirnik)
                .setMuxerFactory(InAppFragmentedMp4Muxer.Factory(2_000L))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        Log.i(TAG, "Tok ${t.ime} koncan (${t.datoteka.length()} B)"); t.koncano = true; t.transformer = null
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.w(TAG, "Tok ${t.ime}: ${exportException.errorCodeName}")
                        t.napaka = exportException.errorCodeName; t.koncano = true; t.transformer = null
                    }
                }).build()
            t.transformer = transformer
            transformer.start(element, t.datoteka.path)
            val straza = object : Runnable {
                override fun run() {
                    if (t.koncano) return
                    if (t.bralcev == 0 && System.currentTimeMillis() - t.zadnjiBralec > BREZ_BRALCA_MS) {
                        Log.i(TAG, "Tok ${t.ime}: nihce ga ne bere, ustavljam"); ustavi(t); return
                    }
                    // Naprava obdrzi svojo rezervo: ce prostora zmanjka, tok ustavimo (gledalec dobi napako, ne naprava).
                    if (t.datoteka.parentFile?.let { it.usableSpace in 0 until Pretvorba.rezerva() } == true) {
                        Log.w(TAG, "Tok ${t.ime}: zmanjkalo prostora, ustavljam"); t.napaka = "ni_prostora"; ustavi(t); return
                    }
                    glavna.postDelayed(this, 5_000)
                }
            }
            glavna.postDelayed(straza, 5_000)
        } catch (e: Throwable) {
            t.napaka = e.message ?: e.javaClass.simpleName; t.koncano = true
            Log.w(TAG, "Tok ${t.ime}: $e")
        }
    }

    /** `/live/<id>`: rastoco datoteko poslje kot tok (chunked) - bere, kolikor je ze pretvorjenega, in caka na vec. */
    fun postrezi(id: String, metoda: String, izhod: OutputStream) {
        val t = tokovi[id] ?: run { napaka(izhod, 404, "ni takega toka"); return }
        izhod.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nTransfer-Encoding: chunked\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        if (metoda == "HEAD") { izhod.flush(); return }
        t.bralcev++
        try {
            var poslano = 0L
            var cakam = 0
            val b = ByteArray(256 * 1024)
            while (true) {
                t.zadnjiBralec = System.currentTimeMillis()
                val dolzina = if (t.datoteka.exists()) t.datoteka.length() else 0L
                if (dolzina > poslano) {
                    RandomAccessFile(t.datoteka, "r").use { d ->
                        d.seek(poslano)
                        while (poslano < dolzina) {
                            val n = d.read(b, 0, minOf(b.size.toLong(), dolzina - poslano).toInt())
                            if (n <= 0) break
                            izhod.write(Integer.toHexString(n).toByteArray()); izhod.write("\r\n".toByteArray())
                            izhod.write(b, 0, n); izhod.write("\r\n".toByteArray())
                            poslano += n
                        }
                    }
                    izhod.flush()
                    cakam = 0
                    continue
                }
                if (t.koncano) break
                if (++cakam > 600) break           // 60 s brez novih podatkov: pretvornik je obstal
                Thread.sleep(100)
            }
            izhod.write("0\r\n\r\n".toByteArray()); izhod.flush()
        } catch (_: Throwable) {
            // Bralec je zaprl povezavo (predvajalnik ustavljen): straza ustavi pretvornik, ce ne pride nov.
        } finally {
            t.bralcev--
            t.zadnjiBralec = System.currentTimeMillis()
            if (t.koncano && t.bralcev == 0 && t.napaka.isNotBlank()) t.datoteka.delete()
        }
    }

    private fun napaka(izhod: OutputStream, koda: Int, besedilo: String) {
        try {
            val telo = besedilo.toByteArray()
            izhod.write("HTTP/1.1 $koda\r\nContent-Type: text/plain\r\nContent-Length: ${telo.size}\r\nConnection: close\r\n\r\n".toByteArray())
            izhod.write(telo); izhod.flush()
        } catch (_: Throwable) { }
    }
}
