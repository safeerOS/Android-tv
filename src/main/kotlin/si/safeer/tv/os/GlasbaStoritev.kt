// Media3: DefaultMediaSourceFactory s pripetim virom je oznacen kot @UnstableApi (kot v PredvajalnikActivity).
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as M3Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import si.safeer.tv.R

/**
 * Glasba v ozadju: en predvajalnik za ves Safeer OS. Tece kot storitev v ospredju (mediaPlayback),
 * zato glasba igra naprej, ko uporabnik zapusti zaslon Glasba. Sistemska seja (MediaSession) sprejme
 * tipke daljinca za predvajaj/pavza/naprej/nazaj/ustavi tudi takrat, ko Safeer OS ni v ospredju.
 *
 * En predvajalnik za glasbo, radio in video: zaslon [PredvajanjeActivity] mu le pripne sliko, zato
 * zvok igra naprej, ko zaslon zapustis (predvajanje v ozadju). Zasloni ga berejo neposredno
 * ([predvajalnik], [trenutna]) - isti proces.
 */
class GlasbaStoritev : Service() {

    private var seja: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN).build(), true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = osvezi()
            override fun onIsPlayingChanged(isPlaying: Boolean) = osvezi()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) konec() else osvezi()
            }
            override fun onPlayerError(error: PlaybackException) {
                // Pokvarjena skladba ne sme ustaviti vsega: naprej na naslednjo, ce obstaja.
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() } else osvezi()
            }
        })
        predvajalnik = p

        seja = MediaSession(this, "SafeerGlasba").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { p.play() }
                override fun onPause() { p.pause() }
                override fun onSkipToNext() { if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
                override fun onSkipToPrevious() { p.seekToPreviousMediaItem() }
                override fun onStop() { konec() }
                override fun onSeekTo(pos: Long) { p.seekTo(pos) }
            })
            isActive = true
        }
        zacniVOspredju()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        zacniVOspredju()
        cakajoci?.let { (seznam, od, s) -> cakajoci = null; nalozi(seznam, od, s) }
        return START_NOT_STICKY
    }

    /** Zaustavitev iz povratnega klica predvajalnika: storitev ustavimo sele po njem. */
    private fun konec() { android.os.Handler(mainLooper).post { stopSelf() } }

    private fun nalozi(seznam: List<Jamendo.Skladba>, od: Int, s: DatotekeActivity.Streznik?) {
        val p = predvajalnik ?: return
        vrsta = seznam
        // Datoteke z racunalnika gredo skozi pripeti vir (TLS z odtisom in zetonom Safeer Controla),
        // vse ostalo (splet, datoteke televizorja) skozi obicajnega.
        val tovarna = if (s != null) androidx.media3.exoplayer.source.DefaultMediaSourceFactory(PripetiVir.Tovarna(s.odtis, s.zeton))
            else androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
        p.setMediaSources(seznam.map { sk ->
            tovarna.createMediaSource(MediaItem.Builder().setMediaId(sk.id).setUri(sk.zvok)
                .apply { if (sk.mime.isNotBlank()) setMimeType(sk.mime) }
                .setMediaMetadata(M3Metadata.Builder().setTitle(sk.naslov).setArtist(sk.izvajalec).build())
                .build())
        }, od.coerceIn(0, (seznam.size - 1).coerceAtLeast(0)), 0L)
        p.prepare()
        p.play()
    }

    private fun zacniVOspredju() {
        val o = obvestilo()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, o, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, o)
    }

    private fun obvestilo(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(KANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(KANAL, getString(R.string.os_glasba_naslov), NotificationManager.IMPORTANCE_LOW))
        }
        val s = trenutna()
        val odpri = PendingIntent.getActivity(this, 0, Intent(this, PredvajanjeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, KANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.os_ikona_glasba)
            .setContentTitle(s?.naslov ?: getString(R.string.os_glasba_naslov))
            .setContentText(s?.izvajalec ?: "")
            .setContentIntent(odpri)
            .setOngoing(true)
            .build()
    }

    private fun osvezi() {
        val p = predvajalnik ?: return
        val s = trenutna()
        seja?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s?.naslov ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s?.izvajalec ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, p.duration.takeIf { it > 0 } ?: -1L)
            .build())
        seja?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO)
            .setState(if (p.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                p.currentPosition.coerceAtLeast(0), 1f)
            .build())
        getSystemService(NotificationManager::class.java).notify(ID, obvestilo())
        poslusalci.toList().forEach { it() }
    }

    override fun onDestroy() {
        seja?.release(); seja = null
        predvajalnik?.release(); predvajalnik = null
        vrsta = emptyList()
        poslusalci.toList().forEach { it() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 4711
        private const val KANAL = "safeer_glasba"

        /** Predvajalnik, dokler storitev tece; sicer null. */
        @Volatile var predvajalnik: ExoPlayer? = null
            private set
        private var vrsta: List<Jamendo.Skladba> = emptyList()
        private var cakajoci: Triple<List<Jamendo.Skladba>, Int, DatotekeActivity.Streznik?>? = null

        /** Zaslon se prijavi, da izve za spremembe (nova skladba, pavza, konec). */
        val poslusalci = mutableSetOf<() -> Unit>()

        /** Ves seznam, ki se predvaja (za "v vrsti" na zaslonu predvajanja). */
        fun vrsta(): List<Jamendo.Skladba> = if (predvajalnik == null) emptyList() else vrsta

        fun trenutna(): Jamendo.Skladba? {
            val p = predvajalnik ?: return null
            return vrsta.getOrNull(p.currentMediaItemIndex)
        }

        /** Predvajaj seznam od izbrane skladbe naprej; storitev se zazene, ce se ne tece. */
        fun predvajaj(ctx: Context, seznam: List<Jamendo.Skladba>, od: Int, streznik: DatotekeActivity.Streznik? = null) {
            if (seznam.isEmpty()) return
            cakajoci = Triple(seznam, od, streznik)
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

        fun ustavi(ctx: Context) {
            ctx.stopService(Intent(ctx, GlasbaStoritev::class.java))
        }
    }
}
