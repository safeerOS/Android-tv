// Media3 oznacuje del API-ja kot @UnstableApi (DefaultLoadControl, RenderersFactory, DefaultTrackSelector);
// uporabljamo ga namerno, ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.predvajalnik

import android.content.Context
import android.media.MediaCodecList
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
 * Ena tovarna za vse ExoPlayerje v Safeer OS / TV brskalniku (prej tri loceno nastavljena mesta:
 * GlasbaStoritev, PlaybackSession, PredvajalnikActivity). Enotno: obravnava napak dekoderja
 * (rezervni dekoder), brez razsiritvenih izrisovalnikov, zbujanje ob omrezju, meritve.
 * Razlicno po PROFILU: medpomnilnik in zvocne lastnosti. Kratek TV-medpomnilnik (1,5-12 s, prednost
 * casu) ostane samo pri TV v zivo; glasba in filmi uporabljajo privzeti Media3 medpomnilnik.
 */
object PredvajalnikTovarna {

    enum class Profil { GLASBA, TV_V_ZIVO, FILM }

    /** Najvecja locljivost, ki jo strojni dekoder naprave zares zmore (namesto trde meje 1080p). */
    data class Zmogljivost(val sirina: Int, val visina: Int, val hevc: Boolean, val vir: String)

    private const val OZNAKA = "SafeerExoMer"

    fun ustvari(
        ctx: Context,
        profil: Profil,
        virMedijev: MediaSource.Factory? = null,
        izbiraSledi: DefaultTrackSelector? = null,
        meritve: Boolean = true,
    ): ExoPlayer {
        val izrisovalniki = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val b = ExoPlayer.Builder(ctx).setRenderersFactory(izrisovalniki)
        nadzorNalaganja(profil)?.let { b.setLoadControl(it) }
        virMedijev?.let { b.setMediaSourceFactory(it) }
        izbiraSledi?.let { b.setTrackSelector(it) }
        val p = b.build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(
                when (profil) {
                    Profil.GLASBA -> C.AUDIO_CONTENT_TYPE_UNKNOWN   // isti predvajalnik igra glasbo, radio in video
                    Profil.TV_V_ZIVO, Profil.FILM -> C.AUDIO_CONTENT_TYPE_MOVIE
                }
            ).build(),
            true
        )
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        if (profil == Profil.GLASBA) p.setHandleAudioBecomingNoisy(true)
        if (profil == Profil.TV_V_ZIVO) {
            try { p.setForegroundMode(true) } catch (_: Exception) {}
        }
        if (meritve) p.addAnalyticsListener(Meritve(profil))
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

    /**
     * Zmogljivost strojnega dekoderja (najvecja podprta locljivost H.264, ali ima HEVC). Meritev, ne
     * ugibanje: iz MediaCodecList. Ce ni podatka, 1920x1080 (dosedanja meja).
     */
    fun zmogljivost(): Zmogljivost {
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
                        if (w * h > sirina * visina) { sirina = w; visina = h; vir = info.name }
                    }
                }
            }
        } catch (_: Exception) {}
        if (sirina <= 0 || visina <= 0) { sirina = 1920; visina = 1080; vir = "privzeto" }
        return Zmogljivost(sirina, visina, hevc, vir)
    }

    /**
     * Osnovna politika izbire sledi za video: prednost H.264 pred HEVC (ce ga naprava ima), najvecja
     * locljivost po izmerjeni zmogljivosti, brez mesanja kodekov med prilagajanjem. [ponovniPoskus] >= 1
     * (po napaki dekoderja, koda 4003): samo H.264, 720p30, meje se smejo preseci, ce ni druge sledi.
     */
    fun politikaVidea(sel: DefaultTrackSelector, zm: Zmogljivost, ponovniPoskus: Int = 0) {
        val b = sel.buildUponParameters()
            .setForceHighestSupportedBitrate(false)
            .setAllowVideoMixedMimeTypeAdaptiveness(false)
            .setMaxVideoSize(zm.sirina, zm.visina)
        if (zm.hevc) b.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
        else b.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
        if (ponovniPoskus >= 1) {
            b.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
                .setMaxVideoSize(1280, 720)
                .setMaxVideoFrameRate(30)
                .setExceedVideoConstraintsIfNecessary(true)
        }
        sel.setParameters(b)
    }

    /** Napaka, ki jo resi drug dekoder ali nizja locljivost (ne omrezje, ne DRM). */
    fun jeNapakaDekoderja(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> (error.message ?: "").contains("MediaCodecVideoRenderer", ignoreCase = true)
    }

    /**
     * Meritve za odlocanje po podatkih (ne po obcutku): cas do prve slike, stevilo in trajanje
     * prekinitev zaradi medpomnjenja, izpuscene slicice. Samo v dnevnik naprave (logcat), nikamor drugam.
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

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            povzetek("napaka ${error.errorCodeName}")
        }

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = povzetek("sprostitev")

        private fun povzetek(razlog: String) {
            if (zacetek == 0L) return
            Log.i(OZNAKA, "$profil povzetek ($razlog): prvaSlika=${prvaSlikaMs}ms prekinitve=$prekinitve/${prekinitveMs}ms izpuscene=$izpuscene")
            zacetek = 0
        }
    }
}
