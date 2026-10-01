package si.safeer.tv.link

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Zakon solidarnosti, korak 3 (grafika): naprava s strojnim kodirnikom pretvori video druge naprave
 * (racunalnika s sibko grafiko) v H.264/AAC MP4 do 1080p - obliko, ki jo predvaja vsak televizor.
 *
 * `video.transcode` {url, fp, token, name}: izvirnik prenese s pripetim potrdilom v predpomnilnik,
 * ga pretvori z Media3 Transformer (strojni kodirnik naprave) in shrani v Prenosi/Safeer Shramba;
 * `video.status` {id} pove fazo (prenasam, pretvarjam, shranjujem, koncano, napaka) in napredek.
 * Zacasne datoteke se vedno pobrisejo.
 */
@OptIn(UnstableApi::class)
object Pretvorba {
    private const val TAG = "SafeerPretvorba"
    private const val MB = 1024L * 1024

    /** Prostor, ki ga naprava vedno obdrzi zase: 10 % diska, najmanj 512 MB, najvec 2 GB
     *  (televizor ima pogosto le 5 GB - s fiksnimi 2 GB ne bi nikoli pomagal). Enako v link_pretvorba.py. */
    fun rezerva(): Long = try {
        val s = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        (s.blockCountLong * s.blockSizeLong / 10).coerceIn(512 * MB, 2048 * MB)
    } catch (_: Throwable) { 2048 * MB }
    private const val VISINA = 1080

    class Opravilo(val id: String, val ime: String) {
        @Volatile var faza = "prenasam"
        @Volatile var odstotek = 0
        @Volatile var napaka = ""
        @Volatile var izhod = ""
        @Volatile var oznaka = ""
        @Volatile var velikost = 0L
        fun json(): JSONObject = JSONObject().put("id", id).put("state", faza).put("percent", odstotek)
            .put("error", napaka).put("name", izhod).put("file_id", oznaka).put("size", velikost)
            .put("where", Environment.DIRECTORY_DOWNLOADS + "/" + Shramba.MAPA + "/" + izhod)
    }

    private val opravila = ConcurrentHashMap<String, Opravilo>()
    private val glavna = Handler(Looper.getMainLooper())

    fun zacni(context: Context, p: JSONObject): Daljinec.Izid {
        if (Build.VERSION.SDK_INT < 29) return Daljinec.Izid(false, "Pretvarjanje potrebuje Android 10 ali novejsi", koda = "ni_podprto")
        val url = p.optString("url"); val odtis = p.optString("fp"); val zeton = p.optString("token")
        val ime = p.optString("name").substringAfterLast('/').trim().take(180)
        val velikost = p.optLong("size", 0L)
        if (!url.startsWith("https://") || odtis.length != 64 || zeton.isBlank() || ime.isBlank())
            return Daljinec.Izid(false, "Nepopolna zahteva", koda = "napacna_zahteva")
        // Racunalnik pove obliko izvirnika (ffprobe): ce je ta naprava ne zna prebrati, takoj recemo ne.
        val mime = p.optString("mime"); val w = p.optInt("width"); val h = p.optInt("height")
        if (mime.startsWith("video/") && w > 0 && h > 0 && !znaDekodirati(MediaFormat.createVideoFormat(mime, w, h)))
            return Daljinec.Izid(false, "Naprava tega videa ne zna prebrati", koda = "ne_zna_dekodirati")
        val pomoc = Zmogljivost.porocilo(context).optJSONObject("pomoc")
        if (pomoc?.optBoolean("lahko", true) == false)
            return Daljinec.Izid(false, "Naprava ta trenutek ne more pomagati", koda = pomoc.optString("razlog"))
        // Izvirnik + pretvorjeni video hkrati v predpomnilniku in rezerva za napravo.
        val prosto = Shramba.prostor()
        if (prosto in 0 until 2 * velikost + rezerva()) return Daljinec.Izid(false, "Premalo prostora", koda = "ni_prostora")
        val o = Opravilo(java.util.UUID.randomUUID().toString(), ime)
        opravila[o.id] = o
        while (opravila.size > 16) opravila.keys.firstOrNull()?.let { opravila.remove(it) }
        Thread({ prenesi(context.applicationContext, o, url, odtis, zeton) }, "safeer-pretvorba").apply { isDaemon = true; start() }
        return Daljinec.Izid(true, "Pretvarjam", o.json())
    }

    fun stanje(id: String): Daljinec.Izid {
        val o = opravila[id] ?: return Daljinec.Izid(false, "Ni take pretvorbe", koda = "ni_opravila")
        return Daljinec.Izid(true, o.faza, o.json())
    }

    private fun znaDekodirati(f: MediaFormat): Boolean = try {
        if (Build.VERSION.SDK_INT == 21) f.setString(MediaFormat.KEY_FRAME_RATE, null)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
    } catch (_: Throwable) { true }

    private fun mapa(ctx: Context) = File(ctx.cacheDir, "pretvorba").apply { mkdirs() }

    private fun napaka(o: Opravilo, sporocilo: String, vararg zacasne: File) {
        Log.w(TAG, "Pretvorba ${o.ime}: $sporocilo")
        o.napaka = sporocilo
        o.faza = "napaka"
        zacasne.forEach { it.delete() }
    }

    private fun prenesi(ctx: Context, o: Opravilo, url: String, odtis: String, zeton: String) {
        val vir = File(mapa(ctx), o.id + ".vir")
        try {
            val odjemalec = si.safeer.tv.os.PripetiVir.odjemalecZaStreznik(odtis)
            odjemalec.newCall(Request.Builder().url(url).header("X-Safeer-Token", zeton).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("racunalnik je vrnil ${r.code}")
                val telo = r.body ?: throw IllegalStateException("prazen odgovor")
                val skupaj = telo.contentLength()
                var dobljeno = 0L
                vir.outputStream().use { izhod ->
                    telo.byteStream().use { vhod ->
                        val b = ByteArray(256 * 1024)
                        while (true) {
                            val n = vhod.read(b)
                            if (n < 0) break
                            izhod.write(b, 0, n)
                            dobljeno += n
                            if (skupaj > 0) o.odstotek = (dobljeno * 100 / skupaj).toInt()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            napaka(o, e.message ?: e.javaClass.simpleName, vir)
            return
        }
        // Ce racunalnik oblike ni poznal: preverimo jo zdaj, preden se lotimo pretvorbe.
        val format = try {
            MediaExtractor().run {
                setDataSource(vir.path)
                val f = (0 until trackCount).map { getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                release(); f
            }
        } catch (_: Throwable) { null }
        if (format == null) { napaka(o, "ni_video", vir); return }
        if (!znaDekodirati(format)) { napaka(o, "ne_zna_dekodirati", vir); return }
        // Visina (pokoncni video: sirina), sirina in trajanje izvirnika - za velikost in bitno hitrost izhoda.
        var sirinaVira = 0; var trajanjeMs = 0L
        val visina = try {
            MediaMetadataRetriever().run {
                setDataSource(vir.path)
                val v = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val s = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val r = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                trajanjeMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release()
                sirinaVira = if (r == 90 || r == 270) v else s
                if (r == 90 || r == 270) s else v
            }
        } catch (_: Throwable) { 0 }
        val bitnaHitrost = bitnaHitrost(vir.length(), trajanjeMs, sirinaVira, visina)
        val cilj = File(mapa(ctx), o.id + ".mp4")
        o.faza = "pretvarjam"; o.odstotek = 0
        glavna.post { pretvori(ctx, o, vir, cilj, visina, bitnaHitrost) }
    }

    /** Media3 Transformer mora teci na niti z Looperjem (glavna nit); delo opravi strojni kodirnik. */
    /**
     * Bitna hitrost izhoda H.264: izvirnik x 1,6 (H.264 rabi vec kot HEVC/VP9 za isto kakovost), omejeno na
     * 2-8 Mb/s pri 1080p in sorazmerno manj pri manjsi sliki. Brez tega je bil izhod 3-krat vecji od izvirnika.
     * 0 = naj izbere kodirnik (trajanja ne poznamo).
     */
    fun bitnaHitrost(velikost: Long, trajanjeMs: Long, sirina: Int, visina: Int): Int {
        if (velikost <= 0 || trajanjeMs <= 0) return 0
        val vir = velikost * 8_000.0 / trajanjeMs
        val izhodVisina = if (visina > VISINA) VISINA else visina.coerceAtLeast(1)
        val izhodSirina = if (visina > VISINA && sirina > 0) sirina.toDouble() * VISINA / visina else sirina.toDouble().coerceAtLeast(1.0)
        val delez = ((izhodSirina * izhodVisina) / (1920.0 * 1080.0)).coerceIn(0.1, 1.0)
        val najvec = 8_000_000.0 * delez
        val najmanj = 2_000_000.0 * delez
        return (vir * 1.6).coerceIn(najmanj, najvec).toInt()
    }

    private fun pretvori(ctx: Context, o: Opravilo, vir: File, cilj: File, visina: Int, bitnaHitrost: Int = 0) {
        try {
            val ucinki = if (visina > VISINA) listOf<androidx.media3.common.Effect>(Presentation.createForHeight(VISINA)) else emptyList()
            val element = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(vir)))
                .setEffects(Effects(emptyList(), ucinki)).build()
            val kodirnik = androidx.media3.transformer.DefaultEncoderFactory.Builder(ctx)
                .apply {
                    if (bitnaHitrost > 0) setRequestedVideoEncoderSettings(
                        androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(bitnaHitrost).build())
                }.build()
            val transformer = Transformer.Builder(ctx)
                .setEncoderFactory(kodirnik)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        o.faza = "shranjujem"
                        Thread({ shrani(ctx, o, vir, cilj) }, "safeer-pretvorba-shrani").apply { isDaemon = true; start() }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        napaka(o, "pretvorba ni uspela: " + exportException.errorCodeName, vir, cilj)
                    }
                }).build()
            transformer.start(element, cilj.path)
            val nosilec = ProgressHolder()
            val sledi = object : Runnable {
                override fun run() {
                    if (o.faza != "pretvarjam") return
                    if (transformer.getProgress(nosilec) == Transformer.PROGRESS_STATE_AVAILABLE) o.odstotek = nosilec.progress
                    glavna.postDelayed(this, 1000)
                }
            }
            glavna.postDelayed(sledi, 1000)
        } catch (e: Throwable) {
            napaka(o, e.message ?: e.javaClass.simpleName, vir, cilj)
        }
    }

    private fun shrani(ctx: Context, o: Opravilo, vir: File, cilj: File) {
        if (Build.VERSION.SDK_INT < 29) { napaka(o, "ni_podprto", vir, cilj); return }   // MediaStore.Downloads (za Lint)
        val cr = ctx.contentResolver
        var uri: Uri? = null
        try {
            val ime = o.ime.substringBeforeLast('.').ifBlank { o.ime } + "-" + VISINA + "p.mp4"
            uri = cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ime)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Shramba.MAPA)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IllegalStateException("mape ni mogoce ustvariti")
            cr.openOutputStream(uri)!!.use { izhod -> cilj.inputStream().use { it.copyTo(izhod, 256 * 1024) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            o.izhod = ime
            o.velikost = cilj.length()
            o.oznaka = "media:shramba:" + android.content.ContentUris.parseId(uri)
            o.odstotek = 100
            o.faza = "koncano"
            Log.i(TAG, "Pretvorjeno: ${o.ime} -> $ime (${o.velikost} B)")
        } catch (e: Throwable) {
            try { if (uri != null) cr.delete(uri, null, null) } catch (_: Throwable) { }
            napaka(o, e.message ?: e.javaClass.simpleName)
        } finally {
            vir.delete(); cilj.delete()
        }
    }
}
