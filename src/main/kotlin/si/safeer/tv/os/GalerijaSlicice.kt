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

    fun pocisti() = predpomnilnik.evictAll()

    fun nalozi(context: Context, v: DatotekeActivity.Vnos, streznik: DatotekeActivity.Streznik?,
               velikost: Int, izvajalec: ExecutorService, naprej: (String, Bitmap?) -> Unit): String {
        val kljuc = "${v.id}|${v.spremenjeno}|$velikost"
        predpomnilnik.get(kljuc)?.let { naprej(kljuc, it); return kljuc }
        izvajalec.execute {
            val b = try {
                if (v.id.startsWith("content://")) lokalna(context, v, velikost)
                else if (streznik != null) oddaljena(v, streznik, velikost)
                else null
            } catch (_: Throwable) { null }
            if (b != null) predpomnilnik.put(kljuc, b)
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

    private fun oddaljena(v: DatotekeActivity.Vnos, s: DatotekeActivity.Streznik, velikost: Int): Bitmap? {
        val k = PripetiVir.odjemalecZaStreznik(s.odtis)
        // Novejse Safeer naprave vrnejo ze pomanjsan JPEG. Pri starejsem strezniku sliko
        // dekodiramo vzorceno; za oddaljen video brez /thumb ne prenasamo cele datoteke.
        fun zahteva(url: String) = Request.Builder().url(url).header("X-Safeer-Token", s.zeton).build()
        k.newCall(zahteva(s.slicicaUrl(v.id))).execute().use { o ->
            if (o.isSuccessful) o.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }?.let { return it }
        }
        if (v.vrsta != "image") return null
        val url = s.url(v.id)
        val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        k.newCall(zahteva(url)).execute().use { o -> if (o.isSuccessful) o.body?.byteStream()?.use { BitmapFactory.decodeStream(it, null, mere) } }
        if (mere.outWidth <= 0 || mere.outHeight <= 0) return null
        var vzorec = 1
        while (mere.outWidth / vzorec > velikost * 2 || mere.outHeight / vzorec > velikost * 2) vzorec *= 2
        val moznosti = BitmapFactory.Options().apply { inSampleSize = vzorec; inPreferredConfig = Bitmap.Config.RGB_565 }
        return k.newCall(zahteva(url)).execute().use { o ->
            if (o.isSuccessful) o.body?.byteStream()?.use { BitmapFactory.decodeStream(it, null, moznosti) } else null
        }
    }
}
