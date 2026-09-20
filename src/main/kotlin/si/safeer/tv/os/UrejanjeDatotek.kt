package si.safeer.tv.os

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Urejanje datotek racunalnika z naprave: Safeer Control (2.1.0 in novejsi) sprejme `POST /d/<id>`
 * z istim zetonom in pripetim potrdilom kot prenos. Brisanje gre v Smeti racunalnika, vrtenje
 * fotografije JPEG spremeni samo oznako EXIF (brez izgube). Kaj naprava sme, pove `edit` v
 * odgovoru `files.list`; brez tega se moznosti ne kazejo.
 */
object UrejanjeDatotek {

    /** Odgovor Controla: `ok`, ob uspehu nova oznaka in ime (preimenovanje, premik), ob napaki kratka koda. */
    class Izid(val ok: Boolean, val napaka: String, val id: String, val ime: String)

    private val ozadje = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    private val json = "application/json; charset=utf-8".toMediaType()

    fun izbrisi(s: DatotekeActivity.Streznik, id: String, naprej: (Izid) -> Unit) =
        ukaz(s, id, JSONObject().put("op", "delete"), naprej)

    fun preimenuj(s: DatotekeActivity.Streznik, id: String, ime: String, naprej: (Izid) -> Unit) =
        ukaz(s, id, JSONObject().put("op", "rename").put("name", ime), naprej)

    fun premakni(s: DatotekeActivity.Streznik, id: String, mapa: String, naprej: (Izid) -> Unit) =
        ukaz(s, id, JSONObject().put("op", "move").put("folder", mapa), naprej)

    /** [vDesno] = 90 stopinj v smeri urinega kazalca, sicer 90 v nasprotni. */
    fun zavrti(s: DatotekeActivity.Streznik, id: String, vDesno: Boolean, naprej: (Izid) -> Unit) =
        ukaz(s, id, JSONObject().put("op", "rotate").put("degrees", if (vDesno) 90 else 270), naprej)

    private fun ukaz(s: DatotekeActivity.Streznik, id: String, telo: JSONObject, naprej: (Izid) -> Unit) {
        ozadje.execute {
            val izid = try {
                val k: OkHttpClient = PripetiVir.odjemalecZaStreznik(s.odtis)
                val z = Request.Builder().url(s.url(id)).header("X-Safeer-Token", s.zeton)
                    .post(telo.toString().toRequestBody(json)).build()
                k.newCall(z).execute().use { o ->
                    val b = o.body?.string().orEmpty()
                    val j = try { JSONObject(b) } catch (_: Throwable) { JSONObject() }
                    Izid(o.isSuccessful && j.optBoolean("ok"), j.optString("napaka").ifBlank { if (o.isSuccessful) "" else "http_${o.code}" },
                        j.optString("id"), j.optString("name"))
                }
            } catch (t: Throwable) {
                Izid(false, "ni_povezave", "", "")
            }
            glavna.post { naprej(izid) }
        }
    }

    /**
     * Dekodira sliko za zaslon [sirina]x[visina] in jo obrne po oznaki EXIF Orientation: fotografija
     * s telefona, posneta pokoncno, je v datoteki pogosto lezeca z oznako - brez tega bi jo televizor
     * pokazal na boku. Velike slike se pomanjsajo ze pri dekodiranju (pomnilnik televizorja).
     */
    fun dekodiraj(bajti: ByteArray, sirina: Int, visina: Int): Bitmap? {
        val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bajti, 0, bajti.size, mere)
        if (mere.outWidth <= 0 || mere.outHeight <= 0) return null
        var vzorec = 1
        while (mere.outWidth / (vzorec * 2) >= sirina && mere.outHeight / (vzorec * 2) >= visina) vzorec *= 2
        val b = BitmapFactory.decodeByteArray(bajti, 0, bajti.size, BitmapFactory.Options().apply { inSampleSize = vzorec })
            ?: return null
        val orientacija = try {
            ExifInterface(ByteArrayInputStream(bajti)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        return obrni(b, orientacija)
    }

    /** Bitna slika, obrnjena po vrednosti EXIF Orientation (1-8); 1 jo vrne nespremenjeno. */
    fun obrni(b: Bitmap, orientacija: Int): Bitmap {
        val m = Matrix()
        when (orientacija) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.preScale(-1f, 1f); m.postRotate(270f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.preScale(-1f, 1f); m.postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return b
        }
        return try {
            val n = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (n !== b) b.recycle()
            n
        } catch (_: Throwable) { b }
    }
}
