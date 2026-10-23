// Media3: SimpleCache in CacheDataSource sta oznacena kot @UnstableApi (kot DefaultMediaSourceFactory v GlasbaStoritev).
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.content.Context
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Naloga 89 (zamisel iz BitChord: predvajane skladbe ostanejo in igrajo brez omrezja): predvajane skladbe v predpomnilniku na
 * USB (Media3 [SimpleCache]), samo ce je predpomnilnik na USB v uporabi ([UsbPredpomnilnik]) - notranji pomnilnik TV ostane
 * prost. Velikost: [PredpomnilnikPravila.delitevUsb] (zvok), LRU.
 *
 * Kljuc je skladba ([PredpomnilnikPravila.kljucZvoka]), ne naslov toka: povezava YouTube potece, posnetek ostane isti. Ob
 * zapisu si zapomnimo naslov in vrsto toka (metapodatki vsebine), zato se cela skladba ob ponovnem predvajanju zacne
 * naravnost iz predpomnilnika - brez iskanja toka (LahkaStran) in brez omrezja ([celota]).
 *
 * Ustvarjanje in sprostitev (branje kazala z USB) samo na lastni niti; [zdaj] in [celota] berejo kazalo v pomnilniku.
 */
object PredpomnilnikZvoka {
    private const val META_URI = "safeer_uri"
    private const val META_MIME = "safeer_mime"
    private const val META_VIDEO = "safeer_video"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var pot = ""
    @Volatile private var meja = 0L
    private val delo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "safeer-zvok-pp").apply { isDaemon = true }
    }

    /** Predpomnilnik zvoka, ce je pripravljen (USB v uporabi); sicer null. Varno z glavne niti (brez diska). */
    fun zdaj(): SimpleCache? = cache

    /**
     * V ozadju uskladi predpomnilnik z mestom: USB v uporabi -> mapa »zvok« z mejo; sicer (ali drugo mesto, druga meja)
     * sprosti starega. Klic po vsaki preverbi USB ([UsbPredpomnilnik]) in ob zagonu storitve predvajanja.
     */
    fun uskladi(c: Context) {
        val app = c.applicationContext
        try { delo.execute { try { uskladiZdaj(app) } catch (_: Throwable) { sprostiZdaj() } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun uskladiZdaj(app: Context) {
        val mapa = UsbPredpomnilnik.mapa(app, PredpomnilnikPravila.ZVOK)
        val m = PredpomnilnikPravila.delitevUsb(UsbPredpomnilnik.mejaUsb()).zvok
        if (mapa == null || m <= 0L) { sprostiZdaj(); return }
        if (cache != null && mapa.path == pot && m == meja) return
        sprostiZdaj()
        val nov = SimpleCache(mapa, LeastRecentlyUsedCacheEvictor(m), androidx.media3.database.StandaloneDatabaseProvider(app))
        // Prvi klic pocaka na branje kazala (na tej niti) - potem je [zdaj] na glavni niti brez cakanja na USB.
        nov.keys
        pot = mapa.path; meja = m; cache = nov
        android.util.Log.i("SafeerOsMedia", "zvok: predpomnilnik na USB (meja ${m / PredpomnilnikPravila.MB} MB, skladb ${nov.keys.size})")
    }

    private fun sprostiZdaj() {
        val c = cache ?: return
        cache = null; pot = ""; meja = 0L
        try { c.release() } catch (_: Throwable) { }
    }

    /** USB ni vec v uporabi ali je odpovedal: predvajalnik bere naprej z omrezja (CacheDataSource napake prezre). */
    fun sprosti() { try { delo.execute { sprostiZdaj() } } catch (_: java.util.concurrent.RejectedExecutionException) { } }

    /** »Pocisti predpomnilnik«: vse skladbe z USB (po sprostitvi izbrisemo mapo), nato znova prazen predpomnilnik. */
    fun izprazni(c: Context) {
        val app = c.applicationContext
        try { delo.execute {
            val mapa = pot.takeIf { it.isNotEmpty() }?.let { File(it) }
            sprostiZdaj()
            try { mapa?.deleteRecursively() } catch (_: Throwable) { }
            try { uskladiZdaj(app) } catch (_: Throwable) { sprostiZdaj() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** Ob zapisu toka: naslov, vrsta in ali je video (za predvajanje iz predpomnilnika brez iskanja). V ozadju. */
    fun zapomniTok(kljuc: String, sk: Jamendo.Skladba) {
        val c = cache ?: return
        val uri = sk.zvok
        val mime = sk.mime
        val video = sk.video
        try { delo.execute {
            try {
                c.applyContentMetadataMutations(kljuc, ContentMetadataMutations().set(META_URI, uri).set(META_MIME, mime).set(META_VIDEO, if (video) 1L else 0L))
            } catch (_: Throwable) { }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /**
     * Skladba [sk] je v predpomnilniku cela: kopija z zapomnjenim naslovom toka (igra naravnost iz predpomnilnika); sicer null.
     * Kazalo je v pomnilniku - klic z glavne niti je hiter.
     */
    fun celota(sk: Jamendo.Skladba): Jamendo.Skladba? {
        val c = cache ?: return null
        return try {
            val kljuc = PredpomnilnikPravila.kljucZvoka(sk.id)
            val md = c.getContentMetadata(kljuc)
            val uri = md.get(META_URI, null as String?)?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            val dolzina = ContentMetadata.getContentLength(md)
            if (!PredpomnilnikPravila.jeCelota(dolzina, c.getCachedBytes(kljuc, 0, dolzina))) return null
            sk.copy(zvok = uri, mime = md.get(META_MIME, "") ?: "", video = md.get(META_VIDEO, 0L) == 1L)
        } catch (_: Throwable) { null }
    }

    /** Zasedenost (bajti) za prikaz; brez diska. */
    fun velikost(): Long = try { cache?.cacheSpace ?: 0L } catch (_: Throwable) { 0L }
}
