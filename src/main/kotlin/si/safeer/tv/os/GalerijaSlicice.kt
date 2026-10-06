package si.safeer.tv.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import okhttp3.Request
import java.util.concurrent.ExecutorService

/** Lahke galerijske slicice: dva delavca, platformni API in omejen LRU (1/8 Java kopice). */
object GalerijaSlicice {
    private val predpomnilnik = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L / 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    /** Slicice, ki jih pravkar ni bilo mogoce dobiti (do kdaj ne sprasujemo znova): drsenje po mrezi ne ponavlja zahtev. */
    private val neuspele = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PREMOR_NEUSPELE_MS = 60_000L

    fun pocisti() { predpomnilnik.evictAll(); neuspele.clear() }

    /** Nov zeton ali nova pot do naprave: slicice, ki prej niso uspele, poskusimo znova. */
    fun pozabiNeuspele() = neuspele.clear()

    fun nalozi(context: Context, v: DatotekeActivity.Vnos, streznik: DatotekeActivity.Streznik?,
               velikost: Int, izvajalec: ExecutorService, naprej: (String, Bitmap?) -> Unit): String {
        val kljuc = "${v.id}|${v.spremenjeno}|$velikost"
        predpomnilnik.get(kljuc)?.let { naprej(kljuc, it); return kljuc }
        if ((neuspele[kljuc] ?: 0L) > android.os.SystemClock.elapsedRealtime()) { naprej(kljuc, null); return kljuc }
        izvajalec.execute {
            val b = try {
                if (v.id.startsWith("content://")) lokalna(context, v, velikost)
                else if (streznik != null) oddaljena(context.applicationContext, v, streznik, velikost)
                else null
            } catch (_: Throwable) { null }
            if (b != null) predpomnilnik.put(kljuc, b)
            else {
                if (neuspele.size > 500) neuspele.clear()
                neuspele[kljuc] = android.os.SystemClock.elapsedRealtime() + PREMOR_NEUSPELE_MS
            }
            naprej(kljuc, b)
        }
        return kljuc
    }

    private fun lokalna(context: Context, v: DatotekeActivity.Vnos, velikost: Int): Bitmap? {
        val uri = Uri.parse(v.id)
        if (Build.VERSION.SDK_INT >= 29) return context.contentResolver.loadThumbnail(uri, Size(velikost, velikost), null)
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        return if (v.vrsta == "video") MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id,
            MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id,
            MediaStore.Images.Thumbnails.MINI_KIND, null)
    }

    private fun oddaljena(context: Context, v: DatotekeActivity.Vnos, s: DatotekeActivity.Streznik, velikost: Int): Bitmap? {
        val k = PripetiVir.odjemalecZaNapravo(s.odtis, context, s.naprava)
        // Novejse Safeer naprave vrnejo ze pomanjsan JPEG. Pri strezniku brez slicic sliko prenesemo sami
        // in jo dekodiramo vzorceno; za oddaljen video brez /thumb ne prenasamo cele datoteke.
        fun zahteva(url: String) = Request.Builder().url(url).header("X-Safeer-Token", s.zeton).build()
        k.newCall(zahteva(s.slicicaUrl(v.id))).execute().use { o ->
            if (o.isSuccessful) o.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }?.let { return it }
        }
        if (v.vrsta != "image") return null
        val url = s.url(v.id)
        if (v.velikost in 0L..NAJVEC_V_ENEM) {
            // Obicajna fotografija: en prenos (doma in zdoma), slicica ni manjsa od ploscice in je obrnjena po EXIF.
            val bajti = k.newCall(zahteva(url)).execute().use { o -> if (o.isSuccessful) o.body?.bytes() else null } ?: return null
            val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bajti, 0, bajti.size, mere)
            if (mere.outWidth <= 0 || mere.outHeight <= 0) return null
            val moznosti = BitmapFactory.Options().apply {
                inSampleSize = vzorec(mere.outWidth, mere.outHeight, velikost); inPreferredConfig = Bitmap.Config.RGB_565
            }
            val b = BitmapFactory.decodeByteArray(bajti, 0, bajti.size, moznosti) ?: return null
            val orientacija = try {
                android.media.ExifInterface(java.io.ByteArrayInputStream(bajti))
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Throwable) { android.media.ExifInterface.ORIENTATION_NORMAL }
            return UrejanjeDatotek.obrni(b, orientacija)
        }
        // Zelo velika slika (ali neznana velikost): ne v pomnilnik - najprej mere, nato vzorceno iz toka.
        val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        k.newCall(zahteva(url)).execute().use { o -> if (o.isSuccessful) o.body?.byteStream()?.use { BitmapFactory.decodeStream(it, null, mere) } }
        if (mere.outWidth <= 0 || mere.outHeight <= 0) return null
        val moznosti = BitmapFactory.Options().apply {
            inSampleSize = vzorec(mere.outWidth, mere.outHeight, velikost); inPreferredConfig = Bitmap.Config.RGB_565
        }
        return k.newCall(zahteva(url)).execute().use { o ->
            if (o.isSuccessful) o.body?.byteStream()?.use { BitmapFactory.decodeStream(it, null, moznosti) } else null
        }
    }

    /** Do te velikosti sliko za slicico prenesemo v enem kosu (v pomnilnik); vecje beremo iz toka. */
    private const val NAJVEC_V_ENEM = 16L * 1024 * 1024

    /**
     * Najvecje vzorcenje (potenca 2), pri katerem krajsa stranica slike se ni manjsa od ploscice: slicica,
     * ki zapolni ploscico, se ne sme povecevati - bila bi mehka.
     */
    private fun vzorec(sirina: Int, visina: Int, velikost: Int): Int {
        var v = 1
        while (minOf(sirina, visina) / (v * 2) >= velikost) v *= 2
        return v
    }
}
