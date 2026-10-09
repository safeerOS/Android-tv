// Media3 oznacuje del API-ja kot @UnstableApi (DefaultLoadControl, RenderersFactory, DefaultTrackSelector);
// uporabljamo ga namerno, ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.predvajalnik

import android.content.Context
import android.media.MediaCodecList
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Ena tovarna za vse ExoPlayerje (GlasbaStoritev, PlaybackSession, PredvajalnikActivity). Zdruzuje poskusno vejo
 * predvajalnik-tovarna (30. 9.) z danasnjo kodo: povsod enako - rezervni dekoder (prej le TV v zivo), brez
 * razsiritvenih izrisovalnikov, meritve v dnevnik; po profilu - medpomnilnik (kratek samo TV v zivo). Zvocne
 * lastnosti (vsebina glasba/film, Dolby) in izbiro sledi pusti klicatelju: tam so od takrat izboljsave
 * (IzbiraZvocneSledi, AUDIO_CONTENT_TYPE_MUSIC za Philipsovo obdelavo zvoka), ki jih tovarna ne sme povoziti.
 */
object PredvajalnikTovarna {

    enum class Profil { GLASBA, TV_V_ZIVO, FILM }

    /** Najvecja smiselna locljivost videa: kar zmore dekoder, a ne vec od zaslona (vsaj 1080p, kot doslej). */
    data class Zmogljivost(val sirina: Int, val visina: Int, val hevc: Boolean, val vir: String)

    private const val OZNAKA = "SafeerExoMer"

    fun ustvari(
        ctx: Context,
        profil: Profil,
        virMedijev: MediaSource.Factory? = null,
        izbiraSledi: DefaultTrackSelector? = null,
    ): ExoPlayer {
        val izrisovalniki = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val b = ExoPlayer.Builder(ctx).setRenderersFactory(izrisovalniki)
        nadzorNalaganja(profil)?.let { b.setLoadControl(it) }
        virMedijev?.let { b.setMediaSourceFactory(it) }
        izbiraSledi?.let { b.setTrackSelector(it) }
        val p = b.build()
        p.addAnalyticsListener(Meritve(profil))
        return p
    }

    /** Medpomnilnik po profilu; null = privzeti Media3 (glasba, filmi). */
    fun nadzorNalaganja(profil: Profil): DefaultLoadControl? = when (profil) {
        Profil.TV_V_ZIVO -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 12_000, 800, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        Profil.GLASBA, Profil.FILM -> null
    }

    @Volatile private var zmogljivostPredpomnjena: Zmogljivost? = null

    /**
     * Zmogljivost strojnega dekoderja H.264 (MediaCodecList), omejena z zaslonom: 4K tok na zaslonu 1080p bi le
     * porabil omrezje. Brez podatka ostane dosedanja meja 1920x1080. Izracun enkrat na proces.
     */
    fun zmogljivost(ctx: Context): Zmogljivost {
        zmogljivostPredpomnjena?.let { return it }
        var sirina = 0; var visina = 0; var hevc = false; var vir = "privzeto"
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (tip in info.supportedTypes) {
                    if (!tip.equals(MimeTypes.VIDEO_H264, true) && !tip.equals(MimeTypes.VIDEO_H265, true)) continue
                    val zm = try { info.getCapabilitiesForType(tip).videoCapabilities } catch (_: Exception) { null } ?: continue
                    if (tip.equals(MimeTypes.VIDEO_H265, true)) hevc = true
                    if (tip.equals(MimeTypes.VIDEO_H264, true)) {
                        val w = zm.supportedWidths.upper; val h = zm.supportedHeights.upper
                        if (w.toLong() * h > sirina.toLong() * visina) { sirina = w; visina = h; vir = info.name }
                    }
                }
            }
        } catch (_: Exception) {}
        // Zaslon: najvecji nacin prikaza (televizor 4K ima nacin 3840x2160, ceprav vmesnik riše v 1080p).
        var zw = 0; var zh = 0
        try {
            val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
            dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.supportedModes?.forEach { m ->
                val w = maxOf(m.physicalWidth, m.physicalHeight); val h = minOf(m.physicalWidth, m.physicalHeight)
                if (w.toLong() * h > zw.toLong() * zh) { zw = w; zh = h }
            }
        } catch (_: Exception) {}
        if (sirina <= 0 || visina <= 0) { sirina = 1920; visina = 1080; vir = "privzeto" }
        if (zw > 0 && zh > 0) { sirina = minOf(sirina, maxOf(zw, 1920)); visina = minOf(visina, maxOf(zh, 1080)) }
        sirina = maxOf(sirina, 1920); visina = maxOf(visina, 1080)
        val z = Zmogljivost(sirina, visina, hevc, vir)
        Log.i(OZNAKA, "zmogljivost: ${z.sirina}x${z.visina} hevc=${z.hevc} dekoder=${z.vir} zaslon=${zw}x$zh")
        zmogljivostPredpomnjena = z
        return z
    }

    /**
     * Meritve za odlocanje po podatkih: cas do prve slike, stevilo in trajanje prekinitev zaradi medpomnjenja,
     * izpuscene slicice. Samo v dnevnik naprave (logcat, oznaka SafeerExoMer), nikamor drugam.
     */
    class Meritve(private val profil: Profil) : AnalyticsListener {
        private var zacetek = 0L
        private var prvaSlikaMs = -1L
        private var medpomnjenjeOd = 0L
        private var prekinitve = 0
        private var prekinitveMs = 0L
        private var izpuscene = 0
        private var igra = false

        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            zacetek = SystemClock.elapsedRealtime(); prvaSlikaMs = -1; prekinitve = 0; prekinitveMs = 0; izpuscene = 0; igra = false
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (prvaSlikaMs < 0 && zacetek > 0) {
                prvaSlikaMs = SystemClock.elapsedRealtime() - zacetek
                Log.i(OZNAKA, "$profil prva slika po ${prvaSlikaMs} ms")
            }
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (igra) { medpomnjenjeOd = SystemClock.elapsedRealtime(); prekinitve++ }
                Player.STATE_READY -> {
                    if (medpomnjenjeOd > 0) { prekinitveMs += SystemClock.elapsedRealtime() - medpomnjenjeOd; medpomnjenjeOd = 0 }
                    igra = true
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> povzetek("konec/idle")
            }
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            izpuscene += droppedFrames
            if (droppedFrames >= 10) Log.w(OZNAKA, "$profil izpuscenih $droppedFrames slicic v $elapsedMs ms (skupaj $izpuscene)")
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) = povzetek("napaka ${error.errorCodeName}")

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = povzetek("sprostitev")

        private fun povzetek(razlog: String) {
            if (zacetek == 0L) return
            Log.i(OZNAKA, "$profil povzetek ($razlog): prvaSlika=${prvaSlikaMs}ms prekinitve=$prekinitve/${prekinitveMs}ms izpuscene=$izpuscene")
            zacetek = 0
        }
    }
}
