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
import android.util.Log
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

    /** Zadnji posnetek, za katerega smo že povedali, da mu manjka dekoder (sporočilo samo enkrat). */
    private var brezDekoderja: String? = null

    /**
     * Kot VLC uporabniku povemo, kadar naprava slike ali zvoka posnetka ne zna dekodirati (npr. DVD z MPEG-2
     * in AC-3 na telefonu brez teh dekoderjev). Brez tega predvajalnik tiho kaže črn zaslon.
     */
    private fun preveriDekoderje(p: Player, tracks: androidx.media3.common.Tracks) {
        val kljuc = p.currentMediaItem?.mediaId + "#" + p.currentMediaItemIndex
        if (tracks.groups.isEmpty() || kljuc == brezDekoderja) return
        fun manjka(vrsta: Int): String? {
            val skupine = tracks.groups.filter { it.type == vrsta }
            if (skupine.isEmpty() || skupine.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }) return null
            return skupine.first().getTrackFormat(0).sampleMimeType.orEmpty()
        }
        fun ime(mime: String) = when (mime) {
            "video/mpeg2" -> "MPEG-2"; "audio/ac3" -> "Dolby Digital (AC-3)"; "audio/eac3" -> "Dolby Digital Plus"
            "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"; "audio/true-hd" -> "Dolby TrueHD"
            else -> mime.substringAfter('/').uppercase()
        }
        val slika = manjka(androidx.media3.common.C.TRACK_TYPE_VIDEO)
        val zvok = manjka(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        when {
            slika != null -> obvesti(getString(R.string.os_media_ni_dekoderja_slike, ime(slika)))
            zvok != null -> obvesti(getString(R.string.os_media_ni_dekoderja_zvoka, ime(zvok)))
            else -> return
        }
        brezDekoderja = kljuc
    }

    private fun obvesti(besedilo: String) {
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(applicationContext, besedilo, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this).build()
        // Vsebina "glasba" (privzeto v Media3), ne "neznano": televizor po tej oznaki izbere
        // obdelavo zvoka (npr. Philipsov nacin za govor/glasbo), ki je bila prej nedolocena.
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.setHandleAudioBecomingNoisy(true)
        Podnapisi.uveljavi(this, p)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Nedavno predvajano (plosca Safeer Media): shranljive skladbe, postaje in videi.
                trenutna()?.let { MedijskiViri.zapomniNedavno(this@GlasbaStoritev, it) }
                osvezi()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Merjenje cakanja pred zacetkom (torrent prek pomocnika): od dotika do prve slike.
                if (isPlaying && merimOd != 0L) { Log.i("SafeerTorrentCas", "predvajanje tece po ${android.os.SystemClock.uptimeMillis() - merimOd} ms"); merimOd = 0L }
                osvezi()
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = preveriDekoderje(p, tracks)
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                // Radio: naslov skladbe iz toka (ICY StreamTitle) kot "izvajalec" pod imenom postaje.
                // Naslov iz MediaItem ima v Media3 prednost, zato onMediaMetadataChanged tega ne prinese.
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i) as? androidx.media3.extractor.metadata.icy.IcyInfo ?: continue
                    val t = e.title?.trim().orEmpty()
                    if (t.isBlank()) continue
                    val z = p.currentMediaItemIndex
                    vrsta = vrsta.mapIndexed { j, s -> if (j == z && s.radio && s.izvajalec != t) s.copy(izvajalec = t) else s }
                    osvezi()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) poKoncu() else osvezi()
            }
            override fun onPlayerError(error: PlaybackException) {
                // Pokvarjena skladba ne sme ustaviti vsega: naprej na naslednjo, ce obstaja. Uporabnik
                // izve, kaj se je zgodilo (prej je predvajanje tiho obstalo in ni bilo jasno zakaj).
                val ime = trenutna()?.naslov.orEmpty()
                if (p.hasNextMediaItem()) {
                    obvesti(getString(R.string.os_media_napaka_naslednja, ime.ifBlank { "?" }))
                    p.seekToNextMediaItem(); p.prepare(); p.play()
                } else if (spletnaVrsta.size > spletniIndeks + 1) {
                    obvesti(getString(R.string.os_media_napaka_naslednja, ime.ifBlank { "?" }))
                    zacniElement(spletniIndeks + 1)
                } else {
                    // Film iz dodatka ima rezervne tokove (naslednji najboljsi): poskusimo jih po vrsti, brez vprasanj.
                    val naslednji = rezerve.firstOrNull()
                    if (naslednji != null) {
                        val ostale = rezerve.drop(1)
                        SpletniVir.zapomniGlaveToka(naslednji.first.zvok, naslednji.second)
                        val trajalo = android.os.SystemClock.uptimeMillis() - poskusOd
                        // Mesto ostane: tok, ki odpove sredi filma (povezava potece), nadaljuje naslednji od tam, ne od zacetka.
                        val mesto = maxOf(p.currentPosition, zacetekPoskusa)
                        if (mesto > 5_000) zacetnoMesto = naslednji.first.id to mesto
                        Log.i("SafeerOsMedia", "tok odpovedal: koda=${error.errorCode}, po $trajalo ms, mesto=$mesto, rezerv=${ostale.size}")
                        // Mrtva povezava odpove v trenutku ([hitraNapaka]): preklop je neopazen, zato brez obvestila. Povemo
                        // le, kadar je uporabnik ze cakal ali gledal (sicer bi ob vsakem filmu bral, da "vir ne dela").
                        if (trajalo > 2_500) obvesti(getString(R.string.os_media_drug_vir))
                        predvajaj(this@GlasbaStoritev, listOf(naslednji.first), 0)
                        rezerve = ostale
                        return
                    }
                    obvesti(getString(napakaZaUporabnika(error.errorCode)))
                    osvezi()
                }
            }
        })
        exo = p
        predvajalnik = p

        seja = MediaSession(this, "SafeerGlasba").apply {
            setCallback(object : MediaSession.Callback() {
                // Tipke gredo predvajalniku, ki igra: nasemu ali spletnemu ([SpletniIgralec]).
                override fun onPlay() { predvajalnik?.play() }
                override fun onPause() { predvajalnik?.pause() }
                override fun onSkipToNext() { naslednja() }
                override fun onSkipToPrevious() { prejsnja() }
                override fun onStop() { konec() }
                override fun onSeekTo(pos: Long) { predvajalnik?.seekTo(pos) }
            })
            isActive = true
        }
        primerek = this
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
            AKCIJA_NAPREJ -> { naslednja(); return START_NOT_STICKY }
            AKCIJA_USTAVI -> { ustaviPredvajanje(); return START_NOT_STICKY }
        }
        zacniVOspredju()
        cakajoci?.let { (seznam, od, s) -> cakajoci = null; nalozi(seznam, od, s) }
        cakajociSplet?.let { (sk, prevzem) -> cakajociSplet = null; zacniSplet(sk, prevzem) }
        cakajocaVrsta?.let { (seznam, od) -> cakajocaVrsta = null; spletnaVrsta = seznam; zacniElement(od) }
        return START_NOT_STICKY
    }

    /** Stop pomeni konec seje, ne pavze: sprostimo tudi skriti spletni predvajalnik/WebView. */
    private fun ustaviPredvajanje() {
        spletnaVrsta = emptyList(); zetonVrste++
        predvajalnik?.stop()
        exo?.clearMediaItems()
        koncajSplet()
        android.os.Handler(mainLooper).post { stopSelf() }
    }

    /** Zaustavitev iz povratnega klica predvajalnika: storitev ustavimo sele po njem. */
    private fun konec() { android.os.Handler(mainLooper).post { stopSelf() } }

    /** Posnetek je pri koncu: v vrsti posnetkov s strani (seznam predvajanja) zacne naslednji, sicer je konec. */
    private fun poKoncu() {
        if (spletnaVrsta.size > spletniIndeks + 1) android.os.Handler(mainLooper).post { zacniElement(spletniIndeks + 1) }
        else konec()
    }

    /**
     * Vrsta, v kateri vsak posnetek zahteva svojo pripravo (stran posnetka, uvozena skladba brez posnetka):
     * predvajalnik dobi po enega, ob koncu zacnemo naslednjega. Navadne skladbe s tokom gredo skozi isto vrsto.
     * [preskoceni]: koliko zaporednih ni uspelo - po treh odnehamo, da ne preletimo vsega seznama brez zvoka.
     */
    private fun zacniElement(i: Int, preskoceni: Int = 0) {
        val sk = spletnaVrsta.getOrNull(i) ?: run { ustaviPredvajanje(); return }
        spletniIndeks = i
        val moj = ++zetonVrste
        trenutniIdVrste = sk.id
        when {
            UvozSeznama.jeIskana(sk) -> {
                // Dokler iscemo posnetek, zaslon ze kaze naslov in izvajalca.
                exo?.let { it.stop(); it.clearMediaItems() }
                koncajSplet()
                exo?.let { predvajalnik = it }
                vrsta = listOf(sk); osvezi()
                Thread {
                    val najden = try { UvozSeznama.najdi(sk) } catch (_: Exception) { null }
                    if (najden != null) MedijskiViri.zamenjaj(this, mapOf(sk.id to najden))
                    android.os.Handler(mainLooper).post {
                        if (moj != zetonVrste || primerek !== this) return@post
                        if (najden == null) {
                            obvesti(getString(R.string.os_media_napaka_naslednja, sk.naslov))
                            if (preskoceni < 2 && spletnaVrsta.size > i + 1) zacniElement(i + 1, preskoceni + 1) else ustaviPredvajanje()
                            return@post
                        }
                        spletnaVrsta = spletnaVrsta.mapIndexed { j, x -> if (j == i) najden else x }
                        trenutniIdVrste = najden.id
                        zacniSplet(najden, true)
                        pripraviNaslednjo(i + 1)
                    }
                }.apply { name = "Safeer-uvoz-najdi"; start() }
            }
            SpletniVir.jeEnota(sk) -> { zacniSplet(sk.copy(zvok = "", mime = ""), true); pripraviNaslednjo(i + 1) }
            else -> { nalozi(listOf(sk), 0, null); pripraviNaslednjo(i + 1) }
        }
    }

    /** Naslednji uvozeni skladbi poiscemo posnetek ze med predvajanjem trenutne: prehod je potem brez cakanja. */
    private fun pripraviNaslednjo(i: Int) {
        val sk = spletnaVrsta.getOrNull(i)?.takeIf { UvozSeznama.jeIskana(it) } ?: return
        Thread {
            val najden = try { UvozSeznama.najdi(sk) } catch (_: Exception) { null } ?: return@Thread
            MedijskiViri.zamenjaj(this, mapOf(sk.id to najden))
            android.os.Handler(mainLooper).post {
                if (spletnaVrsta.getOrNull(i)?.id == sk.id) spletnaVrsta = spletnaVrsta.mapIndexed { j, x -> if (j == i) najden else x }
            }
        }.apply { name = "Safeer-uvoz-naslednja"; start() }
    }

    private fun nalozi(seznam: List<Jamendo.Skladba>, od: Int, s: DatotekeActivity.Streznik?) {
        val p = exo ?: return
        koncajSplet()
        predvajalnik = p
        vrsta = seznam
        streznikTrenutni = s
        // Datoteke z racunalnika gredo skozi pripeti vir (TLS z odtisom in zetonom Safeer Controla),
        // vse ostalo (splet, datoteke televizorja) skozi obicajnega.
        // DvdVir: slike ISO (safeer-dvd:) bere kot tok glavnega naslova diska, vse drugo gre naravnost naprej.
        val tovarna = (if (s != null) androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(PripetiVir.Tovarna(s.odtis, s.zeton, this, s.naprava)))
            else androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(SpletniVir.virPodatkov(this))).setLoadErrorHandlingPolicy(hitraNapaka))
            .setSubtitleParserFactory(Podnapisi.Popravljalnik())
        // Tok z glavami (Stremio proxyHeaders) dobi svojo tovarno: glave spremljajo vse njegove zahteve, tudi dele HLS/DASH
        // na drugih gostiteljih, drugih tokov v vrsti pa ne zadevajo.
        val tovarneZGlavami = HashMap<Map<String, String>, androidx.media3.exoplayer.source.MediaSource.Factory>()
        fun tovarnaZa(sk: Jamendo.Skladba): androidx.media3.exoplayer.source.MediaSource.Factory {
            if (s != null) return tovarna
            val glave = SpletniVir.glaveToka(sk.zvok)
            if (glave.isEmpty()) return tovarna
            return tovarneZGlavami.getOrPut(glave) {
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(SpletniVir.virPodatkov(this, glave)))
                    .setLoadErrorHandlingPolicy(hitraNapaka).setSubtitleParserFactory(Podnapisi.Popravljalnik())
            }
        }
        p.setMediaSources(seznam.map { sk ->
            tovarnaZa(sk).createMediaSource(MediaItem.Builder().setMediaId(sk.id).setUri(sk.zvok)
                .apply { if (sk.mime.isNotBlank()) setMimeType(sk.mime) }
                .apply { if (sk.podnapisi.isNotEmpty()) setSubtitleConfigurations(Podnapisi.konfiguracije(this@GlasbaStoritev, sk.podnapisi)) }
                .setMediaMetadata(M3Metadata.Builder().setTitle(sk.naslov).setArtist(sk.izvajalec).build())
                .build())
        }, od.coerceIn(0, (seznam.size - 1).coerceAtLeast(0)),
            // Nadaljevanje ogleda zacne naravnost pri shranjenem mestu (prej: zacetek pri 0 in skok cez 0,9 s - pri
            // torrentu in pocasnem viru se je najprej prenesel zacetek filma, ki ga nihce ni gledal).
            (zacetnoMesto?.takeIf { it.first == seznam.getOrNull(od)?.id }?.second ?: 0L).also { zacetekPoskusa = it })
        zacetnoMesto = null
        poskusOd = android.os.SystemClock.uptimeMillis()
        p.prepare()
        p.play()
    }

    /** Kdaj se je zacel zadnji poskus predvajanja (uptimeMillis) in pri katerem mestu - za preklop na rezervni tok. */
    private var poskusOd = 0L
    private var zacetekPoskusa = 0L

    /**
     * Mrtva povezava (odgovor 4xx: potekla, izbrisana, zavrnjena) se s ponavljanjem ne popravi. Kadar ima vsebina
     * rezervne tokove, zato ne cakamo na tri ponovitve (nekaj sekund), ampak gremo takoj na naslednjega. Brez rezerv
     * ostane privzeto vedenje (ponovitve), ker pomocnik v Linku med pripravo torrenta lahko hip odgovarja z napako.
     */
    private val hitraNapaka = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            var e: Throwable? = loadErrorInfo.exception
            while (e != null) {
                if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    if (rezerve.isNotEmpty() && e.responseCode in 400..499) return C.TIME_UNSET
                    break
                }
                e = e.cause
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private var exo: ExoPlayer? = null
    private var spletni: SpletniIgralec? = null

    /** Gostitelj strani brez www./m. - kljuc za "ta stran rabi polni pogon". */
    private fun gostiteljStrani(sk: Jamendo.Skladba): String = try {
        java.net.URL(sk.povezava).host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
    } catch (_: Exception) { "" }

    /**
     * Strani, ki so cele aplikacije v JavaScriptu (YouTube), v varcnem pogledu ne stecejo: gremo naravnost na
     * polni pogon, namesto da uporabnik caka na neuspeh druge stopnje. Seznam se dopolnjuje sam ([zapomniPolniPogon]).
     */
    private fun rabiPolniPogon(sk: Jamendo.Skladba): Boolean {
        val g = gostiteljStrani(sk)
        return g.isNotBlank() && (g in POLNI_POGON || getSharedPreferences("safeer_mediji", MODE_PRIVATE).getBoolean("polni-pogon:$g", false))
    }

    private fun zapomniPolniPogon(sk: Jamendo.Skladba) {
        val g = gostiteljStrani(sk).ifBlank { return }
        getSharedPreferences("safeer_mediji", MODE_PRIVATE).edit().putBoolean("polni-pogon:$g", true).apply()
    }

    /** Druga stopnja je varcni sistemski WebView; polni brskalnik se ustvari sele po njenem neuspehu. */
    private fun zacniSplet(sk: Jamendo.Skladba, dovoliPrevzem: Boolean) {
        exo?.let { it.stop(); it.clearMediaItems() }
        koncajSplet()
        if (rabiPolniPogon(sk)) { zacniPolniSplet(sk); return }
        lateinit var lahki: LahkaStran
        lahki = LahkaStran(this, sk,
            dovoliPrevzem = dovoliPrevzem,
            obToku = { tok ->
                if (spletni === lahki) {
                    spletni = null
                    android.util.Log.i("SafeerOsMedia", "stopnja=2 uspeh=Media3")
                    nalozi(listOf(tok), 0, null)
                }
            },
            obPripravi = { pripravljen ->
                if (spletni === pripravljen) {
                    android.util.Log.i("SafeerOsMedia", "stopnja=2 uspeh=LahkaStran")
                    osvezi()
                    // "Pripravljena" stran, ki po 8 s se vedno ne igra (zelimo pa, da igra), v varcnem pogledu ne
                    // stece: polni pogon, in za to stran si to zapomnimo.
                    android.os.Handler(mainLooper).postDelayed({
                        if (spletni === pripravljen && pripravljen.playWhenReady && !pripravljen.isPlaying && pripravljen.currentPosition <= 0L) {
                            android.util.Log.i("SafeerOsMedia", "stopnja=2 ne igra, polni pogon")
                            spletni = null
                            pripravljen.release()
                            zacniPolniSplet(sk, zapomni = true)
                        }
                    }, 8_000)
                }
            },
            obNeuspehu = { neuspesen ->
                if (spletni === neuspesen) {
                    spletni = null
                    zacniPolniSplet(sk, zapomni = true)
                }
            })
        priklopiSpletnega(lahki, sk)
    }

    private fun zacniPolniSplet(sk: Jamendo.Skladba, zapomni: Boolean = false) {
        val s = SpletniIgralec(this, sk) {
            android.util.Log.i("SafeerOsMedia", "stopnja=3 uspeh=ChromiumEngineView")
            if (zapomni) zapomniPolniPogon(sk)
        }
        priklopiSpletnega(s, sk)
    }

    private fun priklopiSpletnega(s: SpletniIgralec, sk: Jamendo.Skladba) {
        spletni?.takeIf { it !== s }?.release()
        s.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = osvezi()
            override fun onMediaMetadataChanged(mediaMetadata: M3Metadata) {
                val t = mediaMetadata.title?.toString().orEmpty()
                if (t.isNotBlank()) vrsta = vrsta.map { it.copy(naslov = t, izvajalec = mediaMetadata.artist?.toString()?.ifBlank { null } ?: it.izvajalec) }
                osvezi()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) poKoncu() else osvezi() }
        })
        spletni = s
        s.vVrsti = spletnaVrsta.size > 1
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
        if (imaNaslednjo()) b.addAction(Notification.Action.Builder(
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
        if (primerek === this) primerek = null
        spletnaVrsta = emptyList(); zetonVrste++
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
        /** Strani, za katere vemo, da v varcnem pogledu ne tecejo (aplikacije v JavaScriptu). */
        private val POLNI_POGON = setOf("youtube.com", "youtu.be")

        /** Predvajalnik, dokler storitev tece; sicer null. */
        @Volatile var predvajalnik: Player? = null
            private set
        private var vrsta: List<Jamendo.Skladba> = emptyList()
        /** Streznik (racunalnik/naprava s pripetim potrdilom), s katerega tece trenutni seznam; null za splet in lokalno. */
        @Volatile var streznikTrenutni: DatotekeActivity.Streznik? = null
            private set
        private var cakajoci: Triple<List<Jamendo.Skladba>, Int, DatotekeActivity.Streznik?>? = null
        /** (id posnetka, mesto v ms): naslednje nalaganje tega posnetka zacne pri tem mestu (nadaljevanje ogleda). */
        @Volatile var zacetnoMesto: Pair<String, Long>? = null
        /** Kdaj (uptimeMillis) je uporabnik izbral film iz torrenta; 0 = ne merimo. Dnevnik SafeerTorrentCas. */
        @Volatile var merimOd = 0L
        private var cakajociSplet: Pair<Jamendo.Skladba, Boolean>? = null

        @Volatile private var primerek: GlasbaStoritev? = null
        /** Vrsta posnetkov, ki se pripravljajo sproti (glej [zacniElement]); prazna = navadno predvajanje. */
        private var spletnaVrsta: List<Jamendo.Skladba> = emptyList()
        private var spletniIndeks = 0
        private var zetonVrste = 0
        private var trenutniIdVrste = ""
        private var cakajocaVrsta: Pair<List<Jamendo.Skladba>, Int>? = null

        /** Novo predvajanje zunaj vrste jo konca; ponovni zagon ISTEGA posnetka (drug nacin, podnapisi) je ne. */
        private fun zapustiVrsto(id: String) {
            if (spletnaVrsta.isEmpty() || id == trenutniIdVrste || id == spletnaVrsta.getOrNull(spletniIndeks)?.id) return
            spletnaVrsta = emptyList(); zetonVrste++
        }

        /**
         * Seznam predvajanja s posnetki s strani (YouTube ...) in uvozenimi skladbami: igra po vrsti kot vsak
         * seznam, Naprej/Nazaj preklapljata, ob koncu posnetka zacne naslednji.
         */
        fun predvajajVrsto(ctx: Context, seznam: List<Jamendo.Skladba>, od: Int) {
            if (seznam.isEmpty()) return
            rezerve = emptyList()
            cakajoci = null; cakajociSplet = null
            cakajocaVrsta = seznam to od.coerceIn(0, seznam.size - 1)
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

        /** Ali Naprej kam pelje: naslednja skladba navadne vrste ali naslednji posnetek seznama. */
        fun imaNaslednjo(): Boolean = spletnaVrsta.size > spletniIndeks + 1 || predvajalnik?.hasNextMediaItem() == true

        fun naslednja() {
            val s = primerek
            if (s != null && spletnaVrsta.size > spletniIndeks + 1) s.zacniElement(spletniIndeks + 1)
            else predvajalnik?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        }

        /** Nazaj: po prvih sekundah na zacetek posnetka, sicer prejsnji (kot pri vsakem predvajalniku). */
        fun prejsnja() {
            val s = primerek
            val p = predvajalnik
            if (s != null && spletnaVrsta.isNotEmpty()) {
                if ((p?.currentPosition ?: 0L) > 5000 || spletniIndeks == 0) p?.seekTo(0) else s.zacniElement(spletniIndeks - 1)
            } else p?.let { if (it.currentPosition > 5000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
        }

        /** Mesto v seznamu predvajanja (1-based) in dolzina, kadar igra seznam posnetkov; sicer null. */
        fun mestoVVrsti(): Pair<Int, Int>? = if (spletnaVrsta.size > 1 && predvajalnik != null) spletniIndeks + 1 to spletnaVrsta.size else null

        /** Enoto spletne aplikacije, katere toka ne moremo ujeti, predvaja stran pod nasim upravljanjem. */
        fun predvajajSplet(ctx: Context, sk: Jamendo.Skladba, dovoliPrevzem: Boolean = true) {
            zapustiVrsto(sk.id)
            cakajocaVrsta = null
            cakajociSplet = sk to dovoliPrevzem
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
        /** Rezervni tokovi iste vsebine z glavami, ki jih zahtevajo; veljajo samo za zadnje zagnano predvajanje. */
        @Volatile private var rezerve: List<Pair<Jamendo.Skladba, Map<String, String>>> = emptyList()

        /** Predvaja [prvi] tok; ce ga predvajalnik ne zmore (kodek, mrtva povezava), sam poskusi [ostali] po vrsti. */
        fun predvajajZRezervami(ctx: Context, prvi: Jamendo.Skladba, ostali: List<Pair<Jamendo.Skladba, Map<String, String>>>) {
            predvajaj(ctx, listOf(prvi), 0)
            rezerve = ostali
        }

        fun predvajaj(ctx: Context, seznam: List<Jamendo.Skladba>, od: Int, streznik: DatotekeActivity.Streznik? = null) {
            if (seznam.isEmpty()) return
            if (seznam.size == 1) zapustiVrsto(seznam[0].id) else { spletnaVrsta = emptyList(); zetonVrste++ }
            cakajocaVrsta = null
            rezerve = emptyList()
            cakajoci = Triple(seznam, od, streznik)
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

        /**
         * Prej je kartica Mediji na zacetnem zaslonu med predvajanjem pokazala "zdaj se predvaja".
         * Ta kartica je zdaj samo se postavka v stranski vrstici (brez dinamicne vsebine, kot vse
         * druge postavke), zato tale funkcija nima vec svojih pogledov - klici vanjo ostajajo varni.
         */
        fun osveziKartico(a: android.app.Activity) { }

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

/** Koda napake ExoPlayerja -> razumljivo sporočilo (enake skupine kot Safeer Media na Linuxu). */
internal fun napakaZaUporabnika(koda: Int): Int = when (koda) {
    1003, in 2000..2999 -> R.string.os_media_napaka_tok    // casovna omejitev, ERROR_CODE_IO_*: omrezje, 404, zavrnjen dostop
    in 3000..3999, in 4000..4999 -> R.string.os_media_napaka_format  // razclenjevanje, dekoder
    in 6000..6999 -> R.string.os_media_napaka_zascita      // ERROR_CODE_DRM_*
    else -> R.string.os_media_napaka_splosno
}
