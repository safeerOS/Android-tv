package si.safeer.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Izbira datotek za <input type=file> na napravi (TV brskalnik, spletni klepeti). Cista logika (vrstni red, ujemanje tipov,
 * skrite mape) je v [IzbiraDatotek], preizkus v tests/IzbiraDatotekTest.kt.
 *
 * TV 4. 11. 2026 (Philips, w3schools tryhtml5_input_type_file): OK na gumbu ni naredil nicesar - televizor nima DocumentsUI,
 * ACTION_OPEN_DOCUMENT nima prejemnika in WebView je izbor tiho zaprl. Zdaj: OPEN_DOCUMENT, sicer ACTION_GET_CONTENT
 * (upravitelji datotek), sicer lasten seznam iz MediaStore z izbiro z daljincem, sicer jasno okno. Brez novih dovoljenj:
 * branje medijev (READ_EXTERNAL_STORAGE do API 32, READ_MEDIA_* od 33) je v manifestu ze za Datoteke Safeer OS
 * (KrajevneDatoteke); brez njega MediaStore na API 29+ vrne samo datoteke, ki jih je shranil brskalnik (npr. prenosi).
 */
object IzbirnikDatotekTv {
    private const val TAG = "SafeerIzbira"
    private const val NAJVEC = 300

    private fun namera(izbirnik: IzbiraDatotek.Izbirnik, tipi: List<String>, vec: Boolean): Intent? {
        val dejanje = when (izbirnik) {
            IzbiraDatotek.Izbirnik.OPEN_DOCUMENT -> Intent.ACTION_OPEN_DOCUMENT
            IzbiraDatotek.Izbirnik.GET_CONTENT -> Intent.ACTION_GET_CONTENT
            else -> return null
        }
        return Intent(dejanje).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = tipi.singleOrNull() ?: IzbiraDatotek.VSE
            if (tipi.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, tipi.toTypedArray())
            if (vec) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    /**
     * Odpre prvi sistemski izbirnik, ki ga naprava ima (OPEN_DOCUMENT, nato GET_CONTENT). Vrne true, ce je eden odprt
     * (odgovor pride v onActivityResult z [koda]); false, ce naprava nima nobenega - takrat [pokazi].
     */
    @Suppress("DEPRECATION")
    fun zazeniSistemski(activity: Activity, koda: Int, tipi: List<String>, vec: Boolean, po: IzbiraDatotek.Izbirnik? = null): Boolean {
        var izbirnik: IzbiraDatotek.Izbirnik? = po
        while (true) {
            izbirnik = IzbiraDatotek.naslednji(izbirnik)
            val n = namera(izbirnik, tipi, vec) ?: return false
            // TV 4. 11. 2026 20:06: prejemnik je bil samo Googlov nadomestek (frameworkpackagestubs), ki se takoj zapre.
            val prejemniki = try {
                activity.packageManager.queryIntentActivities(n, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) { emptyList() }
            val pravi = IzbiraDatotek.pravi(prejemniki.map { it.activityInfo?.packageName })
            if (prejemniki.isNotEmpty() && pravi.isEmpty()) {
                Log.i(TAG, "Izbirnik datotek $izbirnik: samo nadomestek ${prejemniki.firstOrNull()?.activityInfo?.packageName}")
                continue
            }
            // Ob nadomestku in enem pravem izbirniku gre namera naravnost k pravemu (brez izbire med njima).
            if (pravi.size == 1 && prejemniki.size > 1) prejemniki[pravi[0]].activityInfo?.let { n.setClassName(it.packageName, it.name) }
            try {
                activity.startActivityForResult(n, koda)
                Log.i(TAG, "Izbirnik datotek: $izbirnik")
                zadnji = izbirnik
                zagnanMs = android.os.SystemClock.elapsedRealtime()
                return true
            } catch (_: ActivityNotFoundException) {
                Log.i(TAG, "Izbirnik datotek $izbirnik ni na voljo")
            } catch (e: Exception) {
                Log.w(TAG, "Izbirnik datotek $izbirnik: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Zadnji zagnani sistemski izbirnik in cas zagona ([poOdgovoru]). */
    @Volatile private var zadnji: IzbiraDatotek.Izbirnik? = null
    @Volatile private var zagnanMs = 0L

    enum class PoOdgovoru { NOV_IZBIRNIK, LASTEN, KONEC }

    /**
     * Klic na zacetku onActivityResult izbire datotek. Ce se je sistemski izbirnik zaprl sam takoj po zagonu
     * (IzbiraDatotek.poHitremPreklicu), odpre naslednjega (NOV_IZBIRNIK) ali sporoci, naj klicatelj pokaze lasten
     * seznam (LASTEN); sicer KONEC - odgovor velja kot doslej.
     */
    fun poOdgovoru(activity: Activity, koda: Int, resultCode: Int, tipi: List<String>, vec: Boolean): PoOdgovoru {
        val prej = zadnji ?: return PoOdgovoru.KONEC
        zadnji = null
        val trajanje = android.os.SystemClock.elapsedRealtime() - zagnanMs
        val naslednji = IzbiraDatotek.poHitremPreklicu(prej, resultCode == Activity.RESULT_OK, trajanje) ?: return PoOdgovoru.KONEC
        Log.i(TAG, "Izbirnik datotek $prej se je zaprl sam ($trajanje ms), naslednji: $naslednji")
        return if (zazeniSistemski(activity, koda, tipi, vec, po = prej)) PoOdgovoru.NOV_IZBIRNIK else PoOdgovoru.LASTEN
    }

    private class Datoteka(val uri: Uri, val ime: String, val opis: String)

    /** Datoteke iz MediaStore (najnovejse najprej), ki ustrezajo tipom strani; brez skritih map (Android/...). */
    @Suppress("DEPRECATION")
    private fun datoteke(activity: Activity, tipi: List<String>): List<Datoteka> {
        val zbirka = MediaStore.Files.getContentUri("external")
        val nova = Build.VERSION.SDK_INT >= 29
        val mapaStolpec = if (nova) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val stolpci = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE, mapaStolpec)
        val izid = ArrayList<Datoteka>()
        try {
            activity.contentResolver.query(zbirka, stolpci,
                "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL", null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { k ->
                while (k.moveToNext() && izid.size < NAJVEC) {
                    val ime = k.getString(1) ?: continue
                    val mime = k.getString(3)
                    if (!IzbiraDatotek.ustreza(mime, tipi)) continue
                    val surovaMapa = k.getString(5).orEmpty()
                    val mapa = if (nova) si.safeer.tv.os.DatotekePravila.normaliziraj(surovaMapa)
                        else si.safeer.tv.os.DatotekePravila.relativnaIzPoti(surovaMapa)
                    if (IzbiraDatotek.skritaMapa(mapa)) continue
                    val id = k.getLong(0)
                    // Uri zbirke vrste (slike, glasba, video), sicer splosne zbirke - WebView ga bere prek ContentResolverja.
                    val uri = when (k.getInt(4)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        else -> ContentUris.withAppendedId(zbirka, id)
                    }
                    val velikost = android.text.format.Formatter.formatShortFileSize(activity, k.getLong(2))
                    izid.add(Datoteka(uri, ime, if (mapa.isEmpty()) velikost else "$velikost · $mapa"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Seznama datotek ni bilo mogoce prebrati: ${e.javaClass.simpleName}")
        }
        return izid
    }

    /**
     * Lasten izbirnik (naprava nima sistemskega): seznam datotek z izbiro z daljincem. [koncaj] se poklice natanko enkrat:
     * z izbranimi URI-ji (content://media/...) ali null ob preklicu ali ce datotek ni (takrat okno pove, da izbirnika ni).
     */
    fun pokazi(activity: Activity, tipi: List<String>, vec: Boolean, koncaj: (Array<Uri>?) -> Unit) {
        var koncano = false
        fun enkrat(uriji: Array<Uri>?) { if (!koncano) { koncano = true; koncaj(uriji) } }
        Thread({
            val seznam = datoteke(activity, tipi)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) { enkrat(null); return@runOnUiThread }
                try {
                    when (IzbiraDatotek.poSeznamu(seznam.size)) {
                        IzbiraDatotek.Izbirnik.LASTEN -> okno(activity, seznam, vec, ::enkrat)
                        else -> AlertDialog.Builder(activity)
                            .setTitle(R.string.izbira_datotek_ni_naslov)
                            .setMessage(R.string.izbira_datotek_ni)
                            .setPositiveButton(android.R.string.ok, null)
                            .setOnDismissListener { enkrat(null) }
                            .show()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Okna izbire ni bilo mogoce pokazati: ${e.javaClass.simpleName}")
                    enkrat(null)
                }
            }
        }, "safeer-izbira-datotek").apply { isDaemon = true }.start()
    }

    private fun okno(activity: Activity, seznam: List<Datoteka>, vec: Boolean, enkrat: (Array<Uri>?) -> Unit) {
        val vrstice = seznam.map { "${it.ime}\n${it.opis}" }.toTypedArray()
        val b = AlertDialog.Builder(activity)
            .setTitle(if (vec) R.string.izbira_datotek_naslov_vec else R.string.izbira_datotek_naslov)
            .setNegativeButton(android.R.string.cancel, null)
        if (vec) {
            val izbrane = BooleanArray(seznam.size)
            b.setMultiChoiceItems(vrstice, izbrane) { _, i, da -> izbrane[i] = da }
                .setPositiveButton(R.string.izbira_datotek_izberi) { _, _ ->
                    val uriji = seznam.filterIndexed { i, _ -> izbrane[i] }.map { it.uri }
                    enkrat(if (uriji.isEmpty()) null else uriji.toTypedArray())
                }
        } else {
            b.setItems(vrstice) { _, i -> enkrat(arrayOf(seznam[i].uri)) }
        }
        // Preklic, tipka Nazaj ali zaprtje: stran dobi null (WebView sicer naslednjega izbora ne odpre).
        b.setOnDismissListener { enkrat(null) }
        b.show()
    }
}
