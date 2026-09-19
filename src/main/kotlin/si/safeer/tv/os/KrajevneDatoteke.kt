package si.safeer.tv.os

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Datoteke s tega televizorja (brez Safeer Linka in brez racunalnika): videi, glasba in slike, ki
 * jih Android ze pozna - notranja shramba in prikljucen USB.
 *
 * Beremo prek MediaStore: to je edini nacin, ki na Androidu 11+ deluje brez posebnih dovoljenj za
 * ves disk, in ki hkrati vidi USB kljucke (vsak prikljucen nosilec je svoj "volume"). Poti naprav
 * ne kazemo; vsaka datoteka je naslov content://, ki ga predvajalnik odpre neposredno.
 */
object KrajevneDatoteke {
    private const val TAG = "SafeerOsKrajevne"
    const val KOREN = "local:"
    const val VIDEO = "local:video"
    const val AUDIO = "local:audio"
    const val SLIKE = "local:image"
    private const val NAJVEC = 500

    fun jeKrajevna(oznaka: String): Boolean = oznaka.startsWith(KOREN) || oznaka.startsWith("content://")

    /** Na Androidu 14+ ponudimo tudi omejen dostop (samo izbrane slike in videi). */
    fun dovoljenja(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES")
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Ali lahko kaj pokazemo. Dovolj je katerokoli od dovoljenj: ob omejenem dostopu MediaStore vrne
     * samo izbrane slike in videe, brez dovoljenja za glasbo pa je glasbena zbirka prazna.
     */
    fun imamoDovoljenje(context: Context): Boolean = dovoljenja().any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Koren: tri zbirke (videi, glasba, slike) s stevilom najdenih datotek. */
    fun koren(context: Context): List<DatotekeActivity.Vnos> = listOf(
        zbirka(context, VIDEO, context.getString(si.safeer.tv.R.string.os_krajevno_videi)),
        zbirka(context, AUDIO, context.getString(si.safeer.tv.R.string.os_krajevno_glasba)),
        zbirka(context, SLIKE, context.getString(si.safeer.tv.R.string.os_krajevno_slike)),
    )

    private fun zbirka(context: Context, oznaka: String, ime: String): DatotekeActivity.Vnos {
        val n = stevilo(context, oznaka)
        val pod = context.getString(si.safeer.tv.R.string.os_krajevno_stevilo, n)
        return DatotekeActivity.Vnos(oznaka, ime, "folder", -1, "", pod)
    }

    private fun zbirkaUri(oznaka: String): Uri = when (oznaka) {
        AUDIO -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        SLIKE -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun vrstaZa(oznaka: String): String = when (oznaka) {
        AUDIO -> "audio"
        SLIKE -> "image"
        else -> "video"
    }

    private fun stevilo(context: Context, oznaka: String): Int = try {
        context.contentResolver.query(zbirkaUri(oznaka), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count } ?: 0
    } catch (e: Throwable) { Log.w(TAG, "Stetja ni bilo mogoce narediti: ${e.message}"); 0 }

    /** Vsebina zbirke: datoteke po abecedi, najvec [NAJVEC]. Prazna imena in mape brez datotek izpustimo. */
    fun vsebina(context: Context, oznaka: String): List<DatotekeActivity.Vnos> {
        val vrsta = vrstaZa(oznaka)
        val stolpci = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        val vnosi = ArrayList<DatotekeActivity.Vnos>()
        try {
            context.contentResolver.query(zbirkaUri(oznaka), stolpci, null, null,
                MediaStore.MediaColumns.DISPLAY_NAME + " ASC")?.use { k ->
                val ci = k.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val cn = k.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val cs = k.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val cm = k.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (k.moveToNext() && vnosi.size < NAJVEC) {
                    val ime = k.getString(cn) ?: continue
                    val uri = ContentUris.withAppendedId(zbirkaUri(oznaka), k.getLong(ci))
                    vnosi.add(DatotekeActivity.Vnos(uri.toString(), ime, vrsta, k.getLong(cs), k.getString(cm) ?: "", ""))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Branje zbirke ni uspelo: ${e.message}")
        }
        return vnosi
    }
}
