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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Nedavno predvajano (plosca Safeer Media): shranljive skladbe, postaje in videi.
                trenutna()?.let { MedijskiViri.zapomniNedavno(this@GlasbaStoritev, it) }
                osvezi()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = osvezi()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) konec() else osvezi()
            }
            override fun onPlayerError(error: PlaybackException) {
                // Pokvarjena skladba ne sme ustaviti vsega: naprej na naslednjo, ce obstaja.
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() } else osvezi()
            }
        })
        exo = p
        predvajalnik = p

        seja = MediaSession(this, "SafeerGlasba").apply {
            setCallback(object : MediaSession.Callback() {
                // Tipke gredo predvajalniku, ki igra: nasemu ali spletnemu ([SpletniIgralec]).
                override fun onPlay() { predvajalnik?.play() }
                override fun onPause() { predvajalnik?.pause() }
                override fun onSkipToNext() { predvajalnik?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
                override fun onSkipToPrevious() { predvajalnik?.seekToPreviousMediaItem() }
                override fun onStop() { konec() }
                override fun onSeekTo(pos: Long) { predvajalnik?.seekTo(pos) }
            })
            isActive = true
        }
        zacniVOspredju()
        application.registerActivityLifecycleCallbacks(zasloni)
    }

    /** Spletni predvajalnik ostane pripet na zaslon Safeer v ospredju (glej [SpletniIgralec.gostuj]). */
    private val zasloni = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: android.app.Activity) { SpletniIgralec.zadnja = java.lang.ref.WeakReference(a); spletni?.gostuj(a) }
        override fun onActivityDestroyed(a: android.app.Activity) { spletni?.izgubi(a) }
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivityPaused(a: android.app.Activity) {}
        override fun onActivityStopped(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AKCIJA_TOGGLE -> { predvajalnik?.let { if (it.isPlaying) it.pause() else it.play() }; return START_NOT_STICKY }
            AKCIJA_NAPREJ -> { predvajalnik?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }; return START_NOT_STICKY }
            AKCIJA_USTAVI -> { ustaviPredvajanje(); return START_NOT_STICKY }
        }
        zacniVOspredju()
        cakajoci?.let { (seznam, od, s) -> cakajoci = null; nalozi(seznam, od, s) }
        cakajociSplet?.let { cakajociSplet = null; zacniSplet(it) }
        return START_NOT_STICKY
    }

    /** Stop pomeni konec seje, ne pavze: sprostimo tudi skriti spletni predvajalnik/WebView. */
    private fun ustaviPredvajanje() {
        predvajalnik?.stop()
        exo?.clearMediaItems()
        koncajSplet()
        android.os.Handler(mainLooper).post { stopSelf() }
    }

    /** Zaustavitev iz povratnega klica predvajalnika: storitev ustavimo sele po njem. */
    private fun konec() { android.os.Handler(mainLooper).post { stopSelf() } }

    private fun nalozi(seznam: List<Jamendo.Skladba>, od: Int, s: DatotekeActivity.Streznik?) {
        val p = exo ?: return
        koncajSplet()
        predvajalnik = p
        vrsta = seznam
        // Datoteke z racunalnika gredo skozi pripeti vir (TLS z odtisom in zetonom Safeer Controla),
        // vse ostalo (splet, datoteke televizorja) skozi obicajnega.
        val tovarna = if (s != null) androidx.media3.exoplayer.source.DefaultMediaSourceFactory(PripetiVir.Tovarna(s.odtis, s.zeton))
            else androidx.media3.exoplayer.source.DefaultMediaSourceFactory(SpletniVir.virPodatkov(this))
        p.setMediaSources(seznam.map { sk ->
            tovarna.createMediaSource(MediaItem.Builder().setMediaId(sk.id).setUri(sk.zvok)
                .apply { if (sk.mime.isNotBlank()) setMimeType(sk.mime) }
                .setMediaMetadata(M3Metadata.Builder().setTitle(sk.naslov).setArtist(sk.izvajalec).build())
                .build())
        }, od.coerceIn(0, (seznam.size - 1).coerceAtLeast(0)), 0L)
        p.prepare()
        p.play()
    }

    private var exo: ExoPlayer? = null
    private var spletni: SpletniIgralec? = null

    /** Zasciteno vsebino predvaja stran v skritem pogledu; nas predvajalnik jo le upravlja. */
    private fun zacniSplet(sk: Jamendo.Skladba) {
        exo?.let { it.stop(); it.clearMediaItems() }
        koncajSplet()
        val s = SpletniIgralec(this, sk)
        s.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = osvezi()
            override fun onMediaMetadataChanged(mediaMetadata: M3Metadata) {
                val t = mediaMetadata.title?.toString().orEmpty()
                if (t.isNotBlank()) vrsta = vrsta.map { it.copy(naslov = t, izvajalec = mediaMetadata.artist?.toString()?.ifBlank { null } ?: it.izvajalec) }
                osvezi()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) konec() else osvezi() }
        })
        spletni = s
        SpletniIgralec.zadnja?.get()?.let { s.gostuj(it) }
        vrsta = listOf(sk)
        predvajalnik = s
        MedijskiViri.zapomniNedavno(this, sk)
        osvezi()
    }

    private fun koncajSplet() { spletni?.release(); spletni = null }

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
        fun dejanje(akcija: String, koda: Int): PendingIntent = PendingIntent.getService(
            this, koda, Intent(this, GlasbaStoritev::class.java).setAction(akcija),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        b.setSmallIcon(R.drawable.os_ikona_glasba)
            .setContentTitle(s?.naslov ?: getString(R.string.os_glasba_naslov))
            .setContentText(s?.izvajalec ?: "")
            .setContentIntent(odpri)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, if (predvajalnik?.isPlaying == true) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj),
                if (predvajalnik?.isPlaying == true) "Pavza" else "Predvajaj", dejanje(AKCIJA_TOGGLE, 11)).build())
        if (predvajalnik?.hasNextMediaItem() == true) b.addAction(Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.os_ikona_naslednja), "Naprej", dejanje(AKCIJA_NAPREJ, 12)).build())
        b.addAction(Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.os_ikona_ustavi), "Ustavi", dejanje(AKCIJA_USTAVI, 13)).build())
        return b.setOngoing(true).build()
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
        application.unregisterActivityLifecycleCallbacks(zasloni)
        seja?.release(); seja = null
        koncajSplet()
        exo?.release(); exo = null; predvajalnik = null
        vrsta = emptyList()
        poslusalci.toList().forEach { it() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 4711
        private const val KANAL = "safeer_glasba"
        private const val AKCIJA_TOGGLE = "si.safeer.media.TOGGLE"
        private const val AKCIJA_NAPREJ = "si.safeer.media.NEXT"
        private const val AKCIJA_USTAVI = "si.safeer.media.STOP"

        /** Predvajalnik, dokler storitev tece; sicer null. */
        @Volatile var predvajalnik: Player? = null
            private set
        private var vrsta: List<Jamendo.Skladba> = emptyList()
        private var cakajoci: Triple<List<Jamendo.Skladba>, Int, DatotekeActivity.Streznik?>? = null
        private var cakajociSplet: Jamendo.Skladba? = null

        /** Enoto spletne aplikacije, katere toka ne moremo ujeti, predvaja stran pod nasim upravljanjem. */
        fun predvajajSplet(ctx: Context, sk: Jamendo.Skladba) {
            cakajociSplet = sk
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

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

        /**
         * Kartica Mediji na zacetnem zaslonu (televizor in tablica): med predvajanjem pokaze, kaj
         * igra - kot "zdaj se predvaja" - sicer je obicajna kartica Glasba in video.
         */
        fun osveziKartico(a: android.app.Activity) {
            val naslov = a.findViewById<android.widget.TextView>(R.id.medijiNaslov) ?: return
            val opis = a.findViewById<android.widget.TextView>(R.id.medijiOpis) ?: return
            val ikona = a.findViewById<android.widget.ImageView>(R.id.medijiIkona) ?: return
            val sk = trenutna(); val p = predvajalnik
            val kartica = a.findViewById<android.widget.LinearLayout>(R.id.karticaMediji)
            val tipke = kartica?.let { tipkeKartice(a, it) }
            val puscica = kartica?.getChildAt(kartica.childCount - 1) as? android.widget.TextView
            if (sk == null || p == null) {
                naslov.setText(R.string.os_mediji_kartica); opis.setText(R.string.os_mediji_kartica_opis)
                ikona.setImageResource(R.drawable.os_ikona_glasba)
                tipke?.visibility = android.view.View.GONE; puscica?.visibility = android.view.View.VISIBLE; return
            }
            // Upravljanje kar na kartici: predvajanje v ozadju brez odpiranja predvajalnika.
            tipke?.let {
                it.visibility = android.view.View.VISIBLE; puscica?.visibility = android.view.View.GONE
                (it.getChildAt(0) as android.widget.ImageView).setImageResource(if (p.isPlaying) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
                it.getChildAt(1).visibility = if (p.hasNextMediaItem()) android.view.View.VISIBLE else android.view.View.GONE
            }
            naslov.text = sk.naslov
            opis.text = listOf(a.getString(if (p.isPlaying) R.string.os_mediji_zdaj else R.string.os_mediji_pavza), sk.izvajalec)
                .filter { it.isNotBlank() }.joinToString(" · ")
            // S tipkami na kartici bi ikona stanja delovala kot se ena tipka: ostane nota.
            ikona.setImageResource(if (tipke != null) R.drawable.os_ikona_glasba else if (p.isPlaying) R.drawable.os_ikona_predvajaj else R.drawable.os_ikona_pavza)
        }

        /** Tipki pavza/naprej na kartici Mediji; ustvarimo ju enkrat, pred puscico. */
        private fun tipkeKartice(a: android.app.Activity, kartica: android.widget.LinearLayout): android.widget.LinearLayout {
            kartica.findViewWithTag<android.widget.LinearLayout>("tipkeKartice")?.let { return it }
            val g = a.resources.displayMetrics.density
            fun tipka(res: Int, klik: () -> Unit) = android.widget.ImageView(a).apply {
                setImageResource(res); isFocusable = true; isClickable = true
                imageTintList = android.content.res.ColorStateList.valueOf(a.getColor(R.color.os_besedilo))
                val r = (10 * g).toInt(); setPadding(r, r, r, r)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(a.getColor(R.color.os_kartica_dvignjena)); setStroke((2 * g).toInt(), a.getColor(R.color.os_mint)) })
                    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(a.getColor(R.color.os_kartica_dvignjena)) })
                }
                setOnClickListener { klik() }
                layoutParams = android.widget.LinearLayout.LayoutParams((44 * g).toInt(), (44 * g).toInt())
            }
            val v = android.widget.LinearLayout(a).apply {
                tag = "tipkeKartice"; orientation = android.widget.LinearLayout.HORIZONTAL
                addView(tipka(R.drawable.os_ikona_pavza) { predvajalnik?.let { if (it.isPlaying) it.pause() else it.play() } })
                addView(tipka(R.drawable.os_ikona_naslednja) { predvajalnik?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
                    .apply { contentDescription = a.getString(R.string.os_naprej) })
                addView(tipka(R.drawable.os_ikona_ustavi) { ustavi(a) }
                    .apply { contentDescription = a.getString(R.string.os_media_ustavi) })
            }
            kartica.addView(v, (kartica.childCount - 1).coerceAtLeast(0))
            return v
        }

        /** Klik na kartico: med predvajanjem naravnost na predvajanje, sicer v Medije. */
        fun namenKartice(ctx: Context): Intent =
            Intent(ctx, if (trenutna() != null) PredvajanjeActivity::class.java else GlasbaActivity::class.java)

        /** Casovnik izklopa: ob izteku predvajanje ustavimo (za zaspance pred televizorjem). */
        private var izklopOb = 0L
        private val ura = android.os.Handler(android.os.Looper.getMainLooper())
        private val izklopi = Runnable { izklopOb = 0L; predvajalnik?.pause(); poslusalci.toList().forEach { it() } }

        /** Nastavi casovnik v minutah; 0 ga izklopi. */
        fun nastaviCasovnik(minut: Int) {
            ura.removeCallbacks(izklopi)
            izklopOb = if (minut > 0) System.currentTimeMillis() + minut * 60_000L else 0L
            if (minut > 0) ura.postDelayed(izklopi, minut * 60_000L)
        }

        /** Preostale minute casovnika (zaokrozeno navzgor), 0 = izklopljen. */
        fun casovnikMinut(): Int = if (izklopOb == 0L) 0 else ((izklopOb - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)

        fun ustavi(ctx: Context) {
            nastaviCasovnik(0)
            ctx.stopService(Intent(ctx, GlasbaStoritev::class.java))
        }
    }
}
