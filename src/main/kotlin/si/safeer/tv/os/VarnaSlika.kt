package si.safeer.tv.os

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Dekodiranje slike neznanega izvora (ikona programa z racunalnika): najprej samo mere, nato
 * pomanjsano na najvec [IKONA_PX] pik po daljsi stranici. Tako tudi nenavadna ali zlonamerna
 * slika z ogromnimi merami ne more napolniti pomnilnika televizorja.
 */
object VarnaSlika {

    /** Dovolj za ostro kartico na televizorju (enako kot pri ikonah spletnih aplikacij). */
    const val IKONA_PX = 512

    fun izBajtov(bajti: ByteArray, najvec: Int = IKONA_PX): Bitmap? = try {
        val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bajti, 0, bajti.size, mere)
        val v = OsPravila.vzorec(mere.outWidth, mere.outHeight, najvec)
        if (v == 0) null
        else BitmapFactory.decodeByteArray(bajti, 0, bajti.size, BitmapFactory.Options().apply { inSampleSize = v })
    } catch (_: Throwable) { null }

    fun izDatoteke(pot: String, najvec: Int = IKONA_PX): Bitmap? = try {
        val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pot, mere)
        val v = OsPravila.vzorec(mere.outWidth, mere.outHeight, najvec)
        if (v == 0) null
        else BitmapFactory.decodeFile(pot, BitmapFactory.Options().apply { inSampleSize = v })
    } catch (_: Throwable) { null }
}
