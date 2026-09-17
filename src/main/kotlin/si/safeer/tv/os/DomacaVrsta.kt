package si.safeer.tv.os

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.os.Build
import android.util.Log

/**
 * Spletne aplikacije na domacem zaslonu televizorja.
 *
 * Android TV ima na domacem zaslonu vrste s karticami ("kanali predogleda"): tam so Netflix, YouTube
 * in druge aplikacije. Safeer OS vanje postavi svojo vrsto s spletnimi aplikacijami, tako da je
 * spletna stran z domacega zaslona televizorja enako dosegljiva kot katerakoli nalozena aplikacija -
 * to je tisto, kar je Firefox OS obljubljal, na televizorju pa danes zna le malokdo.
 *
 * Vse tece prek sistemskega ponudnika (`android.media.tv.TvContract`); uporabnik vrsto potrdi sam
 * (sistemsko okno), mi je ne moremo dodati na skrivaj. Ce televizor vrst ne podpira, se vse skupaj
 * tiho preskoci - domaci zaslon Safeer OS dela naprej.
 */
object DomacaVrsta {
    private const val TAG = "SafeerOsVrsta"
    private const val PREFS = "safeer_os"
    private const val KLJUC_KANAL = "domaca_vrsta_id"
    private const val KLJUC_VPRASANO = "domaca_vrsta_vprasano"
    private const val PONUDNIK = "safeer-os-spletne"

    private const val DEJANJE_DODAJ = "android.media.tv.action.REQUEST_CHANNEL_BROWSABLE"
    private const val DODATEK_KANAL = "android.media.tv.extra.CHANNEL_ID"

    /** Ta televizor sistemskega okna za dodajanje vrste nima; v tej seji ne poskusamo vec. */
    @Volatile private var brezOkna = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun podprto(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && c.packageManager.hasSystemFeature("android.software.leanback")

    /**
     * Uskladi vrsto s seznamom spletnih aplikacij: kanal ustvari, ce ga se ni, in vanj zapise
     * kartice. Klice se poceni in pogosto (ob vsakem obisku domacega zaslona), zato dela v ozadju.
     */
    fun osvezi(c: Context) {
        if (!podprto(c)) return
        val app = c.applicationContext
        Thread({
            try {
                val id = kanal(app) ?: return@Thread
                zapisiKartice(app, id)
            } catch (e: Throwable) {
                Log.w(TAG, "Vrste ni bilo mogoce osveziti: ${e.message}")
            }
        }, "safeer-os-vrsta").start()
    }

    /**
     * Enkrat ponudi, da se vrsta pojavi na domacem zaslonu. Sistem pokaze svoje okno; ce uporabnik
     * zavrne, ne silimo vec (dokler ne odstrani in znova doda aplikacij).
     */
    fun ponudiEnkrat(a: Activity) {
        if (!podprto(a)) return
        if (brezOkna || prefs(a).getBoolean(KLJUC_VPRASANO, false)) return
        Thread({
            val id = try { kanal(a.applicationContext) } catch (_: Throwable) { null } ?: return@Thread
            try { zapisiKartice(a.applicationContext, id) } catch (_: Throwable) { }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                try {
                    a.startActivityForResult(Intent(DEJANJE_DODAJ).putExtra(DODATEK_KANAL, id), 7021)
                    prefs(a).edit().putBoolean(KLJUC_VPRASANO, true).apply()
                } catch (e: Throwable) {
                    // Nekateri televizorji vrst na domacem zaslonu ne ponujajo (storitev priporocil
                    // je izklopljena ali je domaci zaslon proizvajalcev): tiho odnehamo.
                    brezOkna = true
                    Log.w(TAG, "Sistemskega okna za vrsto ni: ${e.message}")
                }
            }
        }, "safeer-os-vrsta-ponudba").start()
    }

    // ------------------------------------------------------------------ kanal in kartice

    @SuppressLint("RestrictedApi")
    private fun kanal(c: Context): Long? {
        val shranjen = prefs(c).getLong(KLJUC_KANAL, -1L)
        if (shranjen > 0 && obstaja(c, shranjen)) return shranjen
        val v = ContentValues().apply {
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, c.getString(si.safeer.tv.R.string.os_vrsta_ime))
            put(TvContract.Channels.COLUMN_DESCRIPTION, c.getString(si.safeer.tv.R.string.os_vrsta_opis))
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, PONUDNIK)
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, nameraDomov(c))
        }
        val uri = try { c.contentResolver.insert(TvContract.Channels.CONTENT_URI, v) } catch (e: Throwable) {
            Log.w(TAG, "Kanala ni bilo mogoce ustvariti: ${e.message}"); null
        } ?: return null
        val id = ContentUris.parseId(uri)
        prefs(c).edit().putLong(KLJUC_KANAL, id).apply()
        logotip(c, id)
        return id
    }

    private fun obstaja(c: Context, id: Long): Boolean = try {
        c.contentResolver.query(TvContract.buildChannelUri(id), arrayOf(TvContract.Channels._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (_: Throwable) { false }

    /** Logotip vrste: ikona Safeerja; gre skozi ponudnika, zato je dovoljenj ne potrebuje. */
    private fun logotip(c: Context, id: Long) {
        try {
            val d = c.packageManager.getApplicationIcon(c.packageName)
            val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val platno = android.graphics.Canvas(b)
            d.setBounds(0, 0, 160, 160); d.draw(platno)
            c.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use {
                b.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Throwable) { Log.w(TAG, "Logotipa ni bilo mogoce zapisati: ${e.message}") }
    }

    private fun zapisiKartice(c: Context, kanalId: Long) {
        val aplikacije = SpletneAplikacije.seznam(c)
        // Kartic je malo (najvec 24): stare pobrisemo in zapisemo sveze - tako ni podvojenih.
        // Ponudnik ne dovoli brisanja s pogojem, zato vsako kartico pobrisemo po njenem naslovu.
        try {
            c.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(kanalId),
                arrayOf(TvContract.PreviewPrograms._ID), null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val id = k.getLong(0)
                    try { c.contentResolver.delete(TvContract.buildPreviewProgramUri(id), null, null) } catch (_: Throwable) { }
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "Starih kartic ni bilo mogoce pobrisati: ${e.message}") }
        for (a in aplikacije) {
            val ime = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }
            val v = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, kanalId)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CLIP)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, ime)
                put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, SpletneAplikacije.gostitelj(a.url))
                put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, nameraAplikacije(c, a.url, ime))
                put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, a.url)
                if (a.ikonaUrl.isNotBlank()) {
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, a.ikonaUrl)
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                        TvContract.PreviewPrograms.ASPECT_RATIO_1_1)
                }
            }
            try { c.contentResolver.insert(TvContract.PreviewPrograms.CONTENT_URI, v) }
            catch (e: Throwable) { Log.w(TAG, "Kartice ni bilo mogoce zapisati: ${e.message}") }
        }
        Log.i(TAG, "Vrsta ${kanalId}: ${aplikacije.size} kartic")
    }

    /** Odpre spletno aplikacijo cez ves zaslon, naravnost z domacega zaslona televizorja. */
    private fun nameraAplikacije(c: Context, url: String, ime: String): String =
        Intent(c, si.safeer.tv.MainActivity::class.java)
            .putExtra("spletna_aplikacija", url)
            .putExtra("aplikacija_ime", ime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .toUri(Intent.URI_INTENT_SCHEME)

    private fun nameraDomov(c: Context): String =
        Intent(c, DomovActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .toUri(Intent.URI_INTENT_SCHEME)
}
