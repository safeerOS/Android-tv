package si.safeer.tv.os

import android.media.MediaCodecList
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Tanek prevod MediaCodecList v [ZaslonZmoznosti.Dekoder]; vsa presoja je v cisti [ZaslonZmoznosti]. */
object ZaslonZmoznostiAndroid : ZaslonZmoznosti.ViriDekoderjev {
    override fun dekoderji(): List<ZaslonZmoznosti.Dekoder> {
        val izid = ArrayList<ZaslonZmoznosti.Dekoder>()
        // REGULAR_CODECS: samo kodeki, primerni za obicajno predvajanje (brez tunnel/secure posebnosti).
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            // Alias je isti dekoder pod drugim imenom (Android 10+): ne steje dvakrat.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) continue
            val strojni: Boolean? = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> info.isHardwareAccelerated
                // Pred Androidom 10 ni podatka: znana programska imena izlocimo, ostalo ostane neznano.
                info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.") -> false
                else -> null
            }
            for (mime in info.supportedTypes) {
                if (!mime.startsWith("video/")) continue
                val video = try { info.getCapabilitiesForType(mime).videoCapabilities } catch (_: Throwable) { null }
                    ?: continue
                izid += object : ZaslonZmoznosti.Dekoder {
                    override val mime = mime
                    override val koder = info.isEncoder
                    override val strojni = strojni
                    override val najSirina = video.supportedWidths.upper
                    override val najVisina = video.supportedHeights.upper
                    override fun podpira(sirina: Int, visina: Int) = video.isSizeSupported(sirina, visina)
                }
            }
        }
        return izid
    }

    /** Doda "codecs" in "maxsize" v parametre screen.start; brez HEVC ne doda nicesar (obnasanje kot prej). */
    fun dodaj(parametri: JSONObject, hevcDovoljen: Boolean): JSONObject {
        val z = ZaslonZmoznosti.sestavi(this, hevcDovoljen)
        if (z.prazen) return parametri
        parametri.put("codecs", JSONArray(z.codecs))
        val velikosti = JSONObject()
        for ((k, v) in z.maxsize) velikosti.put(k, JSONArray(v))
        parametri.put("maxsize", velikosti)
        return parametri
    }
}
