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
 *
 * Kadar glasba igra na zvocniku v omrezju ([Zvocniki]), je ta naprava daljinec: predvajaj/premor, cas, preskok,
 * naprej/nazaj in glasnost iz zaslonov, obvestila, seje (zaklenjeni zaslon, slusalke) gredo zvocniku. Zato vsi
 * sprasujejo tu ([igra], [polozajMs], [preklopi], [skoci] ...), ne predvajalnika neposredno. Predvajalnik te naprave
 * medtem stoji pri isti skladbi vrste (ne nalaga in ne igra) - vrsta, nakljucno in ponavljanje ostanejo njegovi.
 */
class GlasbaStoritev : Service() {

    private var seja: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Zadnji posnetek, za katerega smo že povedali, da mu manjka dekoder (sporočilo samo enkrat). */
    private var brezDekoderja: String? = null
    /** Posnetek, za katerega so sledi ze v dnevniku. */
    private var zabelezeneSledi: String? = null
    /** Trenutni posnetek je ze kdaj zares igral (napaka pred tem pomeni, da se sploh ni zacel). Bere ga tudi nit nalaganja. */
    @Volatile private var zacelo = false
    /** Odgovor imenika (DNS) za streznik toka, ki ga predvajalnik ni nasel ([vprasajImenik]). */
    private class OdgovorImenika(val gostitelj: String, val obstaja: Boolean?, val kontrola: Boolean?, val kdaj: Long)
    @Volatile private var odgovorImenika: OdgovorImenika? = null
    @Volatile private var vprasanjeImeniku = ""

    /** Gostitelj, ki ga imenik (DNS) ni nasel, ali "" (napaka ni te vrste). Po preusmeritvi velja ime iz sporocila sistema. */
    private fun neznanGostitelj(napaka: Throwable?): String {
        var e = napaka
        var izZahteve = ""
        var izSporocila = ""
        var neznan = false
        while (e != null) {
            if (e is androidx.media3.datasource.HttpDataSource.HttpDataSourceException && izZahteve.isEmpty()) izZahteve = e.dataSpec.uri.host.orEmpty().lowercase()
            if (e is java.net.UnknownHostException) { neznan = true; izSporocila = OsPravila.gostiteljIzNapake(e.message) }
            e = e.cause
        }
        return if (neznan) izSporocila.ifEmpty { izZahteve } else ""
    }

    /** Streznik toka ni odgovoril v roku, je povezavo zavrnil ali do njega ni poti (ne odgovor HTTP in ne imenik). */
    private fun streznikSeNeOdziva(napaka: Throwable?): Boolean {
        var e = napaka
        while (e != null) {
            if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException || e is java.net.UnknownHostException) return false
            if (e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException) return true
            e = e.cause
        }
        return false
    }

    /** Posnetek (id) in stevilo pokvarjenih zvocnih okvirjev v njem (lastnik 28. 10. 2026). */
    private var napakeOkvirja: Pair<String, Int> = "" to 0
    private val NAJVEC_NAPAK_OKVIRJA = 3

    /** Pokvarjen ali okrnjen okvir AC3/E-AC3/DTS/AC4 pri predaji zvocniku (razclenjevalnik glave okvirja v Media3). */
    private fun napakaZvocnegaOkvirja(error: PlaybackException): Boolean {
        var e: Throwable? = error.cause
        var globina = 0
        while (e != null && globina++ < 6) {
            if (e is IndexOutOfBoundsException || e is java.nio.BufferUnderflowException)
                return e.stackTrace.take(8).any { s -> listOf("Ac3Util", "Ac4Util", "DtsUtil", "DefaultAudioSink").any { s.className.endsWith(it) } }
            e = e.cause
        }
        return false
    }

    /** Imenik vprasamo v ozadju (odgovor pride po glavni niti, zato tu nihce ne caka); izid prebere koncna napaka. */
    private fun vprasajImenik(gostitelj: String) {
        if (gostitelj.isEmpty() || vprasanjeImeniku == gostitelj) return
        vprasanjeImeniku = gostitelj
        Thread {
            val o = Imenik.obstaja(listOf(gostitelj, Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            odgovorImenika = OdgovorImenika(gostitelj, o[0], o[1], android.os.SystemClock.uptimeMillis())
            vprasanjeImeniku = ""
        }.start()
    }
    /** Enota, ki je ze zapisana med nedavno predvajane (da je ob vsakem nadaljevanju po pavzi ne pisemo znova). */
    private var zapisanaNedavno = ""
    private fun zapomniNedavnoEnkrat() {
        val sk = trenutna() ?: return
        if (sk.id == zapisanaNedavno) return
        zapisanaNedavno = sk.id
        MedijskiViri.zapomniNedavno(this, sk)
    }
    /** Posnetek, ki smo ga po napaki »neznana oblika« ze poskusili kot seznam HLS. */
    private var kotHls = ""
    /** Naloga 95: zaporedne ponovne povezave kanala v zivo brez novih podatkov in vse ponovne povezave tega vira. */
    private var ponovitevVZivo = 0
    private var ponovitevVZivoSkupaj = 0
    /** Naloga 97: kdaj so bile ponovitve tega vira (elapsedRealtime) - meja [OsPravila.PONOVITEV_V_ZIVO_OKNO_NAJVEC]. */
    private val casiPonovitevVZivo = ArrayList<Long>()
    /** Naloga 102: ponovitev ni stekla, ker je uporabnik ustavil - pritisk na predvajaj pripravi vir znova ([zbudiPredvajalnik]). */
    @Volatile private var prekinjenaPonovitevVZivo = false
    /** Naloga 101 (pregled 100, S4): zamaknjena ponovna povezava (ena naenkrat; odstrani jo ustavitev). */
    private var zamaknjenaPonovitev: Runnable? = null
    /** Predvaja se en sam kanal ali postaja v zivo s spleta (ne datoteka z naprave v Linku): bere ga nit nalaganja. */
    @Volatile private var enaVZivo = false

    /**
     * Kot VLC uporabniku povemo, kadar naprava slike ali zvoka posnetka ne zna dekodirati (npr. DVD z MPEG-2
     * in AC-3 na telefonu brez teh dekoderjev). Brez tega predvajalnik tiho kaže črn zaslon.
     */
    private fun preveriDekoderje(p: Player, tracks: androidx.media3.common.Tracks) {
        val kljuc = p.currentMediaItem?.mediaId + "#" + p.currentMediaItemIndex
        // Oblika slike in zvoka v dnevnik (brez naslovov): ali kanal sploh ima zvok in ali ga naprava zna.
        if (tracks.groups.isNotEmpty() && kljuc != zabelezeneSledi) {
            zabelezeneSledi = kljuc
            Log.i("SafeerOsMedia", "sledi: " + tracks.groups.joinToString("; ") { g ->
                val f = g.getTrackFormat(0)
                val vrsta = when (g.type) { C.TRACK_TYPE_VIDEO -> "slika"; C.TRACK_TYPE_AUDIO -> "zvok"; C.TRACK_TYPE_TEXT -> "besedilo"; else -> "drugo" }
                val mere = if (g.type == C.TRACK_TYPE_VIDEO) " ${f.width}x${f.height}" else if (g.type == C.TRACK_TYPE_AUDIO) " ${f.channelCount}k ${f.sampleRate}Hz" else ""
                "$vrsta ${f.sampleMimeType ?: f.containerMimeType ?: "?"}$mere${if (g.isSupported) "" else " NEPODPRTO"}${if (g.isSelected) " *" else ""}"
            })
        }
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

    /** Id posnetka, cigar dolzino smo ze preverili (ena preverba na posnetek). */
    private var trajanjePreverjeno = ""

    /**
     * Film z IMDb: dolzina posnetka proti dolzini filma (Cinemeta). Ce se ne ujemata, je dodatek dal napacen paket
     * (lastnik 30. 10. 2026, »Preboj«): naslednji rezervni vir od zacetka, sicer opozorilo.
     */
    private fun preveriTrajanje(p: Player) {
        val s = trenutna() ?: return
        if (!s.video || s.radio || s.mediaType != "movie" || s.imdbId.isBlank()) return
        if (trajanjePreverjeno == s.id) return
        val dolzina = p.duration
        if (dolzina <= 0 || dolzina == androidx.media3.common.C.TIME_UNSET) return
        trajanjePreverjeno = s.id
        val imdb = s.imdbId
        Thread {
            val film = TrajanjeFilma.minut(imdb) ?: return@Thread
            if (!OsPravila.trajanjeNeUstreza(dolzina, film)) return@Thread
            android.os.Handler(mainLooper).post {
                if (trenutna()?.id != s.id) return@post
                val posnetek = OsPravila.napisTrajanja((dolzina / 60_000).toInt())
                val pravi = OsPravila.napisTrajanja(film)
                Log.i("SafeerOsMedia", "napacen posnetek: traja $posnetek, film $pravi")
                if (drugVirOd(this, 0)) obvesti(getString(R.string.os_media_napacen_posnetek_drug, posnetek, pravi))
                else obvesti(getString(R.string.os_media_napacen_posnetek, posnetek, pravi))
            }
        }.apply { isDaemon = true }.start()
    }

    private fun obvesti(besedilo: String) {
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(applicationContext, besedilo, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Naloga 89: predpomnilnik predvajanih skladb na USB (ce je v uporabi) - pripravi se v ozadju.
        PredpomnilnikZvoka.uskladi(this)
        // Tovarna: rezervni dekoder in meritve (SafeerExoMer) kot drugje; zvok in izbira sledi ostaneta tu.
        val p = si.safeer.tv.predvajalnik.PredvajalnikTovarna.ustvari(this,
            si.safeer.tv.predvajalnik.PredvajalnikTovarna.Profil.GLASBA, izbiraSledi = IzbiraZvocneSledi(this))
        // Vsebina "glasba" (privzeto v Media3), ne "neznano": televizor po tej oznaki izbere
        // obdelavo zvoka (npr. Philipsov nacin za govor/glasbo), ki je bila prej nedolocena.
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.setHandleAudioBecomingNoisy(true)
        Podnapisi.uveljavi(this, p)
        // Kaj gre v resnici iz zvocnikov (predan tok Dolby ali dekodirano v stereo): za dnevnik in za zaslon predvajanja.
        p.addAnalyticsListener(ZvokIzhod.poslusalec(this) { oznaka -> zvocniIzhod = oznaka })
        // Naloga 133: hitrost povezave za izbiro kakovosti (meri jo predvajalnik med nalaganjem, brez dodatnih prenosov).
        p.addAnalyticsListener(HitrostPovezave.poslusalec(this))
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Nedavno predvajano (plosca Safeer Media): shranljive skladbe, postaje in videi - sele, ko res tecejo.
                // Kanal ali film, ki se ni zacel, ne sodi med »nazadnje predvajano« (izmerjeno 5. 10. 2026: kanal brez
                // streznika je bil tam z gumbom »Nadaljuj«).
                if (p.isPlaying) zapomniNedavnoEnkrat()
                // Naloga 90a: zasedenost predpomnilnika zvoka ob vsaki skladbi (brez diska, kazalo je v pomnilniku).
                if (PredpomnilnikZvoka.zdaj() != null) android.util.Log.i("SafeerOsGlasba", "predpomnjenje: velikost ${PredpomnilnikZvoka.velikost() / 1024} KB")
                osvezi()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                igraZadnje = predvajalnik?.let { teceLokalno(it) } ?: isPlaying
                if (isPlaying) { zacelo = true; koncnaNapaka = null; zapomniNedavnoEnkrat(); zaporedneNapake = 0 }
                // Naloga 75: pregled kanalov dodatkov v ozadju ne tece med predvajanjem.
                if (isPlaying) PregledKanalov.ustavi("predvajanje")
                // Naloga 64: nas predvajalnik igra - WebView-ji, ki so iskali tok, in njihov izrisovalnik (~178 MB) niso vec
                // potrebni. Spletno predvajanje (stopnja 2/3) obdrzi svojega.
                if (isPlaying) LahkiWebViewPool.obNativnemPredvajanju(spletni != null)
                // Merjenje cakanja pred zacetkom (torrent prek pomocnika): od dotika do prve slike.
                if (isPlaying && merimOd != 0L) { Log.i("SafeerTorrentCas", "predvajanje tece po ${android.os.SystemClock.uptimeMillis() - merimOd} ms"); merimOd = 0L }
                osvezi()
            }
            // Pregled C1: igra() sledi playWhenReady - premor med nalaganjem mora osveziti gumb, obvestilo in sejo.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = osvezi()
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
                // Naloga 95: kanal v zivo, ki mu streznik zapre povezavo, se poveze znova (zaslon predvajanja ostane).
                if (playbackState == Player.STATE_ENDED) { if (!ponoviVZivo(p, "konec toka")) poKoncu() } else osvezi()
                if (playbackState == Player.STATE_READY) preveriTrajanje(p)
            }
            override fun onPlayerError(error: PlaybackException) {
                // Pokvarjena skladba ne sme ustaviti vsega: naprej na naslednjo, ce obstaja. Uporabnik
                // izve, kaj se je zgodilo (prej je predvajanje tiho obstalo in ni bilo jasno zakaj).
                val ime = trenutna()?.naslov.orEmpty()
                // Naloga 93: vsaka napaka predvajalnika v dnevnik (koda, ime kode, HTTP, vzrok) - brez naslova toka.
                Log.i("SafeerOsMedia", "napaka predvajalnika: koda=${error.errorCode} (${error.errorCodeName}), http=${httpKoda(error)}, " +
                    "vzrok=${error.cause?.javaClass?.simpleName}, v zivo=$enaVZivo, zacelo=$zacelo")
                // Naloga 95: napaka vira (vhod/izhod, kode 2xxx) med predvajanjem kanala v zivo - streznik zapre povezavo:
                // ponovna povezava, brez sporocila.
                if (error.errorCode / 1000 == PlaybackException.ERROR_CODE_IO_UNSPECIFIED / 1000 &&
                    ponoviVZivo(p, "napaka vira ${error.errorCode}", error.errorCode, httpKoda(error))) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Prenos v zivo po pavzi ali zastoju: predvajalnik je zaostal za oknom prenosa. To ni napaka kanala -
                    // nazaj na zivo. (Prej: »Predvajanje se je ustavilo zaradi napake« in crn zaslon.)
                    Log.i("SafeerOsMedia", "za oknom prenosa v zivo: nazaj na zivo")
                    p.seekToDefaultPosition(); p.prepare()
                    return
                }
                // Lastnik 28. 10. 2026: video z racunalnika se je ustavil - pokvarjen okvir AC3 pri predaji zvocniku
                // (Ac3Util, IndexOutOfBounds). Nadaljuj 1 s naprej (cez pokvarjen okvir), najvec NAJVEC_NAPAK_OKVIRJA na posnetek.
                if (napakaZvocnegaOkvirja(error)) {
                    val id = trenutna()?.id.orEmpty()
                    val n = if (napakeOkvirja.first == id) napakeOkvirja.second + 1 else 1
                    napakeOkvirja = id to n
                    if (n <= NAJVEC_NAPAK_OKVIRJA) {
                        Log.i("SafeerOsMedia", "pokvarjen zvocni okvir: naprej 1 s ($n/$NAJVEC_NAPAK_OKVIRJA)")
                        p.seekTo(p.currentPosition + 1_000); p.prepare(); p.play()
                        return
                    }
                }
                val enota = trenutna()
                if (enota != null && vrsta.size == 1 && enota.mime.isBlank() && enota.zvok.startsWith("http") && kotHls != enota.id &&
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    // Naslov brez koncnice (preusmeritev): po naslovu nismo prepoznali, da je vsebina seznam HLS. En poskus kot HLS.
                    kotHls = enota.id
                    Log.i("SafeerOsMedia", "neznana oblika: poskus kot seznam HLS")
                    nalozi(listOf(enota.copy(mime = androidx.media3.common.MimeTypes.APPLICATION_M3U8)), 0, streznikTrenutni)
                    return
                }
                // Lastnik 27. 10. 2026 13:48: YouTube je zavrnil tok (403) - skladba gre na spletno stran, ne naslednja.
                val vVrsti = spletnaVrsta.getOrNull(spletniIndeks)
                if (vVrsti != null && enota?.id == vVrsti.id &&
                    YoutubeVrstaPravila.tokZavrnjen(httpKoda(error), YoutubeZvok.jeYoutube(vVrsti), vVrsti.video)) {
                    Log.i("SafeerOsMedia", "youtube: tok zavrnjen (http=${httpKoda(error)}), spletna stran")
                    YoutubeZvok.tokZavrnjen(vVrsti)
                    zacniSplet(vVrsti.copy(zvok = "", mime = ""), true)
                    return
                }
                // Brez konca preskakovanja: najvec NAJVEC_ZAPOREDNIH_NAPAK zapored in samo, ce uporabnik ni ustavil.
                val naprej = YoutubeVrstaPravila.naprejPoNapaki(zaporedneNapake,
                    p.hasNextMediaItem() || spletnaVrsta.size > spletniIndeks + 1, p.playWhenReady)
                zaporedneNapake++
                if (!naprej && (p.hasNextMediaItem() || spletnaVrsta.size > spletniIndeks + 1)) {
                    Log.i("SafeerOsMedia", "napaka: ne naprej (zapored=$zaporedneNapake, zeli igrati=${p.playWhenReady})")
                    obvesti(getString(R.string.os_nedel_skladba, ime.ifBlank { "?" }, NedelujoceSkladbe.opis(this@GlasbaStoritev, NedelujocePravila.NI_TOKA)))
                    ustaviPredvajanje()
                } else if (p.hasNextMediaItem()) {
                    obvesti(getString(R.string.os_media_napaka_naslednja, ime.ifBlank { "?" }))
                    p.seekToNextMediaItem(); p.prepare(); p.play()
                } else if (spletnaVrsta.size > spletniIndeks + 1) {
                    obvesti(getString(R.string.os_media_napaka_naslednja, ime.ifBlank { "?" }))
                    zacniElement(spletniIndeks + 1)
                } else {
                    // Dnevnik TV 28. 10. 2026 09:08: TV je preklopil na vhod (zaslon predvajanja je odsel, povrsina izgubljena),
                    // dekoder je zato javil napako - to ni okvara vira. Prej je v ozadju zacela druga izdaja in prepisala
                    // napredek. Zdaj: brez zaslona napaka dekodiranja videa ne menja vira; mesto ostane za »Nadaljuj«.
                    if (OsPravila.brezMenjaveVira(enota?.video == true, PredvajanjeActivity.odprt, SprotnaPomoc.jeNapakaDekodiranja(error))) {
                        Log.i("SafeerOsMedia", "napaka dekodiranja brez zaslona predvajanja: vir ostane, mesto=${p.currentPosition}")
                        p.pause()
                        return
                    }
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
                    val druga = obOdpovedi
                    if (druga != null && !zacelo) {
                        obOdpovedi = null
                        Log.i("SafeerOsMedia", "tok odpovedal pred zacetkom (koda=${error.errorCode}): druga pot izbire toka")
                        obvesti(getString(R.string.os_media_drug_vir))
                        druga()
                        return
                    }
                    // Koncna napaka. Kanal ali postaja, ki pri viru ne dela, dobi stavek z razlogom in je do jutri skrit
                    // ([MrtviKanali]); ce se sploh ni zacel, predvajanje zapremo - prej je ostal crn predvajalnik brez
                    // razlage, kanal pa v mali vrstici kot »zdaj se predvaja«.
                    val http = httpKoda(error)
                    val vZivo = enota != null && (enota.radio || enota.id.startsWith("tv:") || Stremio.jeVZivo(enota) || IptvSeznami.jeKanal(enota))
                    // Te napake resuje zaslon predvajanja sam (stran pod nasim upravljanjem, sprotna pretvorba v Linku).
                    val resujeZaslon = enota != null && (SpletniVir.jeEnota(enota) || SprotnaPomoc.jeSprotniTok(enota) || SprotnaPomoc.jeNapakaDekodiranja(error))
                    // Streznika toka ni v imeniku (DNS): kanal je mrtev sele, ko je imenik sveze odgovoril, da imena ni, in
                    // isti hip odgovoril za kontrolno ime ([OsPravila.mrtevGostitelj]) - sicer je lahko odpovedalo omrezje.
                    val gostitelj = if (vZivo && !resujeZaslon) neznanGostitelj(error) else ""
                    // Streznik se ne odziva (rok povezave, zavrnjena povezava): mrtev sele, ko je imenik isti hip odgovoril
                    // za kontrolno ime - omrezje te naprave torej dela ([OsPravila.mrtevNeodziven]).
                    val neodziven = vZivo && !resujeZaslon && gostitelj.isEmpty() && streznikSeNeOdziva(error)
                    val kljucImenika = if (neodziven) NEODZIVEN else gostitelj
                    val imenik = odgovorImenika?.takeIf { kljucImenika.isNotEmpty() && it.gostitelj == kljucImenika &&
                        android.os.SystemClock.uptimeMillis() - it.kdaj < (if (neodziven) OsPravila.KONTROLA_VELJA_MS else OsPravila.IMENIK_VELJA_MS) }
                    val brezStreznika = !neodziven && imenik != null && OsPravila.mrtevGostitelj(imenik.obstaja, imenik.kontrola)
                    val brezOdziva = neodziven && imenik != null && OsPravila.mrtevNeodziven(zacelo, imenik.kontrola)
                    val mrtev = vZivo && !resujeZaslon && (OsPravila.mrtevKanal(error.errorCode, http) || brezStreznika || brezOdziva)
                    val besedilo = when {
                        brezStreznika -> getString(R.string.os_media_kanal_mrtev_streznik, ime.ifBlank { "?" })
                        brezOdziva -> getString(R.string.os_media_kanal_mrtev_neodziven, ime.ifBlank { "?" })
                        mrtev && http > 0 -> getString(R.string.os_media_kanal_mrtev_http, ime.ifBlank { "?" }, http)
                        mrtev -> getString(R.string.os_media_kanal_mrtev, ime.ifBlank { "?" })
                        else -> getString(napakaZaUporabnika(error.errorCode))
                    }
                    val oImeniku = if (kljucImenika.isEmpty()) "" else ", imenik: ime=${imenik?.obstaja}, kontrola=${imenik?.kontrola}, neodziven=$neodziven"
                    Log.i("SafeerOsMedia", "koncna napaka: koda=${error.errorCode}, http=$http, vZivo=$vZivo, zacelo=$zacelo, mrtev=$mrtev$oImeniku")
                    koncnaNapaka = KoncnaNapaka(enota?.id.orEmpty(), error.errorCode, http, besedilo)
                    // Naloga 108: z razlogom in imenom (izbira »Ne delujejo« v Live TV).
                    if (mrtev && enota != null) MrtviKanali.zapomni(this@GlasbaStoritev, enota.id, when {
                        brezStreznika -> NedelujocePravila.STREZNIK
                        brezOdziva -> NedelujocePravila.NEODZIVEN
                        http > 0 -> NedelujocePravila.http(http)
                        else -> NedelujocePravila.NI_ZNANO
                    }, enota.naslov)
                    obvesti(besedilo)
                    if (vZivo && !zacelo && !resujeZaslon) android.os.Handler(mainLooper).post { ustaviPredvajanje() } else osvezi()
                }
            }
        })
        exo = p
        predvajalnik = p
        // Samodejno naslednja skladba izklopljena: po trenutni skladbi pavza (lastnik 27. 10. 2026).
        p.pauseAtEndOfMediaItems = !samodejnoNaslednja(this)

        seja = MediaSession(this, "SafeerGlasba").apply {
            setCallback(object : MediaSession.Callback() {
                // Tipke gredo predvajalniku, ki igra: nasemu, spletnemu ([SpletniIgralec]) ali zvocniku v omrezju. Pregled C1:
                // seja kaze stanje te naprave, zato nikoli ne upravlja druge naprave v Linku (to dela okno »Upravljaj napravo«).
                override fun onPlay() { nadaljujTu() }
                override fun onPause() { premorTu() }
                override fun onSkipToNext() { naslednjaTu() }
                override fun onSkipToPrevious() { prejsnjaTu() }
                override fun onStop() { konec() }
                override fun onSeekTo(pos: Long) { skoci(pos) }
                // Gumb »Predvajaj na napravi« v predvajalniku sistema (zaklenjen zaslon, hitre nastavitve).
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action != AKCIJA_NA_NAPRAVI) return
                    try { izbiraNaprave().send() } catch (e: Exception) { Log.i("SafeerOsMedia", "izbira naprave: ${e.javaClass.simpleName}") }
                }
            })
            isActive = true
        }
        primerek = this
        Zvocniki.obSpremembi = { osvezi() }
        Zvocniki.obKoncu = { dejanje, polozaj, ime, zelelIgrati -> zvocnikJavlja(dejanje, polozaj, ime, zelelIgrati) }
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
            // Obvestilo kaze to napravo - gumbi upravljajo to napravo (pregled C1).
            AKCIJA_TOGGLE -> { preklopiTu(); return START_NOT_STICKY }
            AKCIJA_NAPREJ -> { naslednjaTu(); return START_NOT_STICKY }
            AKCIJA_NAZAJ -> { prejsnjaTu(); return START_NOT_STICKY }
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
        preklicPonovitveVZivo()
        prekinjenaPonovitevVZivo = false      // naloga 103 (pregled 102, tocka 3)
        spletnaVrsta = emptyList(); zetonVrste++
        izbir++
        Zvocniki.ustavi {}
        predvajalnik?.stop()
        exo?.clearMediaItems()
        koncajSplet()
        android.os.Handler(mainLooper).post { stopSelf() }
    }

    /** Zaustavitev iz povratnega klica predvajalnika: storitev ustavimo sele po njem. */
    private fun konec() { android.os.Handler(mainLooper).post { stopSelf() } }

    /**
     * Naloga 95: kanal v zivo s progresivnim virom (surov MPEG-TS prek http) - streznik povezavo sproti zapre, predvajalnik
     * pride do konca. Isti vir pripravimo znova od privzetega mesta: zaslon predvajanja ostane, zadnja slika tudi (seek ne
     * pobrise povrsine). Odlocitev in meja: [OsPravila.ponovnaPovezavaVZivo]; po meji obstojeca pot (konec, napaka).
     */
    private fun ponoviVZivo(p: Player, zakaj: String, kodaNapake: Int = 0, http: Int = 0): Boolean {
        val enota = trenutna() ?: return false
        // Naloga 101: nov konec ali napaka nadomesti cakajoco ponovitev - nikoli dve hkrati.
        preklicPonovitveVZivo()
        val kanal = vrsta.size == 1 && (IptvSeznami.jeKanal(enota) || Stremio.jeVZivo(enota))
        val progresivno = androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(android.net.Uri.parse(enota.zvok),
            enota.mime.ifBlank { null }) == C.CONTENT_TYPE_OTHER
        val predvajano = p.currentPosition.coerceAtLeast(0L)
        val zdaj = android.os.SystemClock.elapsedRealtime()
        // Naloga 114: odlocitev na enem mestu ([OsPravila.odlocitevVZivo] - HTTP koda 101, meja 97, tih konec 102/103;
        // sonda odtisa 100/101 je iz poti predvajanja odstranjena, kanal se ob meji NE oznaci kot mrtev).
        val odlocitev = OsPravila.odlocitevVZivo(kanal, progresivno, zacelo, kodaNapake, http, ponovitevVZivo, predvajano, casiPonovitevVZivo, zdaj,
            imaRezervo = rezerve.isNotEmpty(), imaNaslednjega = spletnaVrsta.size > spletniIndeks + 1)
        val zamik = odlocitev.zamikMs
        val n = odlocitev.zaporedno
        if (odlocitev.vrsta == OsPravila.VZIVO_OBSTOJECA_POT) {
            if (kanal && progresivno && zacelo && OsPravila.zamikPonovitveVZivo(kodaNapake, http) != null)
                Log.i("SafeerOsMedia", "kanal v zivo: brez ponovne povezave ($zakaj) - meja ponovitev, naprej rezerva ali naslednji element")
            return false
        }
        if (odlocitev.vrsta == OsPravila.VZIVO_TIH_KONEC) {
            // Meja ponovitev: tiho konec (brez sporocila in brez oznake mrtvega kanala), zaslon predvajanja se zapre.
            Log.i("SafeerOsMedia", "kanal v zivo: brez ponovne povezave ($zakaj) - meja ponovitev, $ponovitevVZivo zaporednih brez novih podatkov, tiho konec")
            // Naloga 103 (pregled 102, tocka 2): medtem izbran drug vir se ne ustavi.
            val izbranoOb = izbir
            glavnaNit.post { if (primerek === this && izbir == izbranoOb && trenutna()?.id == enota.id) ustaviPredvajanje() }
            return true
        }
        ponovitevVZivo = n
        ponovitevVZivoSkupaj++
        casiPonovitevVZivo += zdaj
        Log.i("SafeerOsMedia", "kanal v zivo: ponovna povezava $ponovitevVZivoSkupaj ($zakaj, predvajano $predvajano ms, zaporedno brez podatkov $n" +
            (if (zamik > 0) ", cez $zamik ms" else "") + ")")
        fun znova() { p.seekToDefaultPosition(); p.prepare(); p.play() }
        // Ponovitev (takojsnja ali zamaknjena): ne velja, ce je medtem izbran drug vir ali predvajanje ustavljeno
        // ([ustaviPredvajanje] jo odstrani); ce je uporabnik pritisnil pavzo (playWhenReady), pocaka na predvajaj.
        val izbrano = izbir
        // Naloga 102 (pregled 101, #3): pogoj velja tudi za takojsnjo ponovitev.
        val naprej = Runnable {
            zamaknjenaPonovitev = null
            if (primerek !== this || izbir != izbrano || trenutna()?.id != enota.id) return@Runnable
            if (p.playWhenReady) znova() else prekinjenaPonovitevVZivo = true
        }
        if (zamik <= 0L) naprej.run() else { zamaknjenaPonovitev = naprej; glavnaNit.postDelayed(naprej, zamik) }
        return true
    }

    /** Naloga 101: odstrani cakajoco ponovitev kanala v zivo (ustavitev, konec storitve, nov konec toka). */
    private fun preklicPonovitveVZivo() {
        zamaknjenaPonovitev?.let { glavnaNit.removeCallbacks(it) }
        zamaknjenaPonovitev = null
    }

    private val glavnaNit by lazy { android.os.Handler(mainLooper) }

    /** Posnetek je pri koncu: v vrsti posnetkov s strani (seznam predvajanja) zacne naslednji, sicer je konec. */
    private fun poKoncu() {
        // Lastnik 27. 10. 2026: stikali »Samodejno naslednja skladba« in »… naprej po koncu albuma« (privzeto vklopljeni).
        when (MiniPredvajalnikPravila.poKoncuSkladbe(samodejnoNaslednja(this), samodejnoAlbum(this), spletnaVrsta.size > spletniIndeks + 1)) {
            MiniPredvajalnikPravila.PO_KONCU_NASLEDNJA -> android.os.Handler(mainLooper).post { zacniElement(spletniIndeks + 1) }
            MiniPredvajalnikPravila.PO_KONCU_MIX -> if (!nadaljujZMixom(koncajBrez = true)) konec()
            else -> konec()
        }
    }

    private var nalagaMix = false

    /**
     * Lastnik 27. 10. 2026: glasba z YouTuba po zadnji skladbi v vrsti (konec ali Naprej) ne obstane, ampak se nadaljuje s
     * samodejnim mixom sorodnih skladb ([YoutubeMixPravila], [UvozSeznama.mix]); vrsta se podaljsa, nalaga se v ozadju.
     * Vrne false, ce mix ne pride v postev (takrat velja dosedanje: konec). [koncajBrez]: ce mixa ni, se predvajanje konca
     * (konec skladbe); na Naprej pa ostane, kot je.
     */
    private fun nadaljujZMixom(koncajBrez: Boolean): Boolean {
        val zdaj = spletnaVrsta.getOrNull(spletniIndeks) ?: return false
        if (!YoutubeMixPravila.samodejnoNaprej(spletnaVrsta.size > spletniIndeks + 1, YoutubeZvok.jeYoutube(zdaj), zdaj.video)) return false
        val id = YoutubeZvok.videoId(zdaj) ?: return false
        if (nalagaMix) return true
        nalagaMix = true
        val zeton = zetonVrste
        val ze = spletnaVrsta.mapNotNull { YoutubeZvok.videoId(it) }.toSet()
        Thread {
            val nove = try { UvozSeznama.mix(id, ze) } catch (_: Throwable) { emptyList() }
            android.os.Handler(mainLooper).post {
                nalagaMix = false
                if (zeton != zetonVrste) return@post                       // vmes je uporabnik zagnal kaj drugega
                Log.i("SafeerOsMedia", "mix: +${nove.size} po \"${zdaj.naslov.take(40)}\"")
                if (nove.isEmpty()) { if (koncajBrez) konec(); return@post }
                spletnaVrsta = spletnaVrsta + nove
                zacniElement(spletniIndeks + 1)
            }
        }.apply { name = "SafeerMix"; isDaemon = true }.start()
        return true
    }

    /**
     * Vrsta, v kateri vsak posnetek zahteva svojo pripravo (stran posnetka, uvozena skladba brez posnetka):
     * predvajalnik dobi po enega, ob koncu zacnemo naslednjega. Navadne skladbe s tokom gredo skozi isto vrsto.
     * [preskoceni]: koliko zaporednih ni uspelo - po treh odnehamo, da ne preletimo vsega seznama brez zvoka.
     */
    private fun zacniElement(i: Int, preskoceni: Int = 0) {
        val sk = spletnaVrsta.getOrNull(i) ?: run { ustaviPredvajanje(); return }
        // Naloga 109: skladba, ki trajno ne gre (zaseben, odstranjen, ni v tej drzavi ...), se tiho preskoci - ne ustavi vrste.
        val znanRazlog = NedelujoceSkladbe.razlog(this, sk.id)
        if (znanRazlog != null && NedelujocePravila.trajen(znanRazlog)) {
            val naprej = NedelujocePravila.prviPredvajljiv(spletnaVrsta.map { NedelujoceSkladbe.razlog(this, it.id) }, i)
            Log.i("SafeerOsMedia", "vrsta: preskok \"${sk.naslov.take(40)}\" (razlog=$znanRazlog), naprej=$naprej")
            if (naprej < 0) { obvesti(getString(R.string.os_nedel_skladba, sk.naslov, NedelujoceSkladbe.opis(this, znanRazlog))); ustaviPredvajanje() }
            else zacniElement(naprej, preskoceni)
            return
        }
        spletniIndeks = i
        val moj = ++zetonVrste
        trenutniIdVrste = sk.id
        // Glasba igra na zvocniku: posnetek YouTube (in uvozena skladba, ko dobi posnetek) gre tja kot zvocni tok (lastnik,
        // 25. 10. 2026). Drug posnetek s strani zvocnik ne more: igra ta naprava - zacne sele, ko zvocnik potrdi Stop (ali po
        // kratkem roku), da hip ne igrata oba.
        if (Zvocniki.aktivni != null && (UvozSeznama.jeIskana(sk) || SpletniVir.jeEnota(sk))) {
            if (UvozSeznama.jeIskana(sk) || zaZvocnik(sk)) { naZvocnikVVrsti(i, sk, moj, preskoceni); return }
            val izbrano = izbir
            Zvocniki.ustavi { if (primerek === this && moj == zetonVrste && izbrano == izbir) zacniElement(i, preskoceni) }
            osvezi()
            return
        }
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
                            // Naloga 109: razlog (»vir ne da toka«, ni trajen) - seznam ga pokaze; iskanje ga ponovi po svojih pravilih.
                            NedelujoceSkladbe.zapomni(this, sk.id, NedelujocePravila.NI_TOKA, sk.naslov)
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
            SpletniVir.jeEnota(sk) -> { zacniEnoto(sk, moj); pripraviNaslednjo(i + 1) }
            else -> { nalozi(listOf(sk), 0, null); pripraviNaslednjo(i + 1) }
        }
    }

    /**
     * Naloga 113: enota spletnega vira iz vrste. Posnetek YouTube igra najprej kot zvok v nasem predvajalniku (Media3,
     * [YoutubeZvok] - odjemalec VISIONOS, brez vgradnje, brez strani v WebView, brez DRM); sele ce YouTube toka ne da, gre
     * na spletno stran ([SpletniIgralec]), ki ima rok ([YoutubeVrstaPravila.ROK_ZASTOJA_MS]).
     */
    private fun zacniEnoto(sk: Jamendo.Skladba, moj: Int) {
        val spletna = sk.copy(zvok = "", mime = "")
        if (YoutubeVrstaPravila.pot(YoutubeZvok.jeYoutube(sk), Zvocniki.aktivni != null, YoutubeZvok.znanoNe(sk)) != YoutubeVrstaPravila.Pot.MEDIA3_ZVOK ||
            // Cela skladba na USB (naloga 89) ima prednost - zacniSplet jo igra brez omrezja.
            (!sk.video && !sk.radio && PredpomnilnikZvoka.celota(spletna) != null)) {
            zacniSplet(spletna, true); return
        }
        // Zaslon ze kaze naslov, medtem ko YouTube vprasamo za tok (obicajno < 1 s, iz priprave takoj).
        val izbrano = ++izbir
        exo?.let { it.stop(); it.clearMediaItems() }
        koncajSplet()
        exo?.let { predvajalnik = it }
        vrsta = listOf(sk); osvezi()
        Thread {
            val tok = try { YoutubeZvok.razresi(this, sk) } catch (e: Exception) {
                Log.i("SafeerOsMedia", "youtube: toka ni (${(e as? YoutubeZvok.NiToka)?.razlog ?: e.javaClass.simpleName}), spletna stran")
                null
            }
            android.os.Handler(mainLooper).post {
                // Uporabnik je medtem izbral kaj drugega (vrsta ali posamezna skladba): ta izid ne velja vec.
                if (moj != zetonVrste || primerek !== this || izbrano != izbir) return@post
                if (tok == null) { zacniSplet(spletna, true); return@post }
                Log.i("SafeerOsMedia", "stopnja=1 uspeh=Media3 youtube itag=${tok.itag}")
                // Tok zahtevamo z istim User-Agent, s katerim smo naslov dobili (kot posrednik zvocnika).
                SpletniVir.zapomniGlaveToka(tok.url, mapOf("User-Agent" to YoutubeZvokPravila.UA))
                zaporedniZastoji = 0
                nalozi(listOf(sk.copy(zvok = tok.url, mime = tok.mime, video = false)), 0, null)
            }
        }.apply { name = "Safeer-youtube-zvok-vrsta"; start() }
    }

    /** Napake predvajalnika zapored (lastnik 27. 10. 2026: preskakovanje brez konca); nic, ko kaj zaigra. */
    private var zaporedneNapake = 0

    /** Naloga 113: koliko spletnih strani je zapored obstalo (rok brez predvajanja); nic, ko kaj zaigra. */
    private var zaporedniZastoji = 0

    /**
     * Naloga 113: spletna stran posnetka v roku ni zacela igrati. Uporabnik izve, katera skladba ne gre in da gre vrsta
     * naprej; razlog ni trajen (stran lahko drugic dela), zato ga ne zapisemo kot trajnega.
     */
    private fun zastojSpletneStrani(s: SpletniIgralec, sk: Jamendo.Skladba) {
        if (spletni !== s) return
        val imaNaslednjo = spletnaVrsta.size > spletniIndeks + 1
        val naprej = YoutubeVrstaPravila.naprejPoZastoju(zaporedniZastoji, imaNaslednjo)
        zaporedniZastoji++
        Log.i("SafeerOsMedia", "zastoj strani: \"${sk.naslov.take(40)}\", naprej=$naprej")
        NedelujoceSkladbe.zapomni(this, sk.id, NedelujocePravila.NI_TOKA, sk.naslov)
        android.os.Handler(mainLooper).post {
            if (spletni !== s || primerek !== this) return@post
            if (naprej) { obvesti(getString(R.string.os_media_napaka_naslednja, sk.naslov)); zacniElement(spletniIndeks + 1) }
            else { obvesti(getString(R.string.os_nedel_skladba, sk.naslov, NedelujoceSkladbe.opis(this, NedelujocePravila.NI_TOKA))); ustaviPredvajanje() }
        }
    }

    /**
     * Posnetek YouTube iz vrste, medtem ko glasba igra na zvocniku (lastnik, 25. 10. 2026): zvocnik dobi zvocni tok
     * ([YoutubeZvok]), ta naprava ostane daljinec in strani ne nalaga. Uvozena skladba najprej dobi posnetek
     * ([UvozSeznama.najdi]). Ce ne gre (DRM, brez toka), uporabnik izve; vrsta, ki je prisla do tu sama, ostane na
     * premoru - kot doslej pri skladbi, ki je zvocnik ne zmore.
     */
    private fun naZvocnikVVrsti(i: Int, sk: Jamendo.Skladba, moj: Int, preskoceni: Int) {
        val z = Zvocniki.aktivni ?: return zacniElement(i, preskoceni)
        val samodejno = naslednjaSamodejno
        val igraj = samodejno || Zvocniki.stanje.zeliIgrati
        val izbrano = izbir
        // Ta naprava je daljinec: zaslon pokaze skladbo takoj ([trenutna]), igra jo zvocnik.
        exo?.let { it.stop(); it.clearMediaItems() }
        koncajSplet()
        exo?.let { predvajalnik = it }
        vrsta = listOf(sk)
        osvezi()
        fun poslji(cilj: Jamendo.Skladba) {
            zagonSamodejen = samodejno
            Zvocniki.predvajaj(this, z, cilj, 0L, 0L, igraj) { napaka ->
                if (napaka == null || napaka is Zvocniki.Prehiteno || primerek !== this || moj != zetonVrste) return@predvajaj
                obvesti(Zvocniki.besediloNapake(this, napaka, z.ime))
                Zvocniki.ustavi {
                    if (primerek !== this || moj != zetonVrste || izbrano != izbir) return@ustavi
                    if (samodejno || !igraj) osvezi() else zacniSplet(cilj.copy(zvok = "", mime = ""), true)
                }
            }
            osvezi()
        }
        if (UvozSeznama.jeIskana(sk)) {
            Thread {
                val najden = try { UvozSeznama.najdi(sk) } catch (_: Exception) { null }
                if (najden != null) MedijskiViri.zamenjaj(this, mapOf(sk.id to najden))
                android.os.Handler(mainLooper).post {
                    if (moj != zetonVrste || primerek !== this) return@post
                    if (najden == null) {
                        NedelujoceSkladbe.zapomni(this, sk.id, NedelujocePravila.NI_TOKA, sk.naslov)
                        obvesti(getString(R.string.os_media_napaka_naslednja, sk.naslov))
                        if (preskoceni < 2 && spletnaVrsta.size > i + 1) zacniElement(i + 1, preskoceni + 1) else ustaviPredvajanje()
                        return@post
                    }
                    spletnaVrsta = spletnaVrsta.mapIndexed { j, x -> if (j == i) najden else x }
                    trenutniIdVrste = najden.id
                    vrsta = listOf(najden)
                    if (zaZvocnik(najden)) poslji(najden) else zacniElement(i, preskoceni)
                }
            }.apply { name = "Safeer-uvoz-najdi-zvocnik"; start() }
        } else poslji(sk)
        pripraviNaslednjo(i + 1)
    }

    /** Naslednji uvozeni skladbi poiscemo posnetek ze med predvajanjem trenutne: prehod je potem brez cakanja. */
    private fun pripraviNaslednjo(i: Int) {
        // Na zvocniku: tok YouTube naslednjega posnetka razresimo vnaprej - naslednja skladba stece brez cakanja na YouTube.
        // Naloga 113: tudi na tej napravi (Media3) - naslednji posnetek YouTube stece brez cakanja na YouTube.
        spletnaVrsta.getOrNull(i)?.takeIf { YoutubeZvok.jeYoutube(it) }?.let { YoutubeZvok.pripravi(this, it) {} }
        val sk = spletnaVrsta.getOrNull(i)?.takeIf { UvozSeznama.jeIskana(it) } ?: return
        Thread {
            val najden = try { UvozSeznama.najdi(sk) } catch (_: Exception) { null } ?: return@Thread
            MedijskiViri.zamenjaj(this, mapOf(sk.id to najden))
            android.os.Handler(mainLooper).post {
                if (spletnaVrsta.getOrNull(i)?.id == sk.id) spletnaVrsta = spletnaVrsta.mapIndexed { j, x -> if (j == i) najden else x }
            }
        }.apply { name = "Safeer-uvoz-naslednja"; start() }
    }

    /** Odgovor HTTP, zaradi katerega je predvajanje odpovedalo; 0 = napaka ni odgovor vira. */
    private fun httpKoda(error: PlaybackException): Int {
        var e: Throwable? = error
        while (e != null) {
            if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return e.responseCode
            e = e.cause
        }
        return 0
    }

    private fun nalozi(seznam: List<Jamendo.Skladba>, od: Int, s: DatotekeActivity.Streznik?) {
        val p = exo ?: return
        val mojaIzbira = ++izbir
        koncajSplet()
        predvajalnik = p
        vrsta = seznam
        zacelo = false; koncnaNapaka = null; zapisanaNedavno = ""
        ponovitevVZivo = 0; ponovitevVZivoSkupaj = 0; casiPonovitevVZivo.clear(); prekinjenaPonovitevVZivo = false
        enaVZivo = s == null && seznam.size == 1 && seznam[0].let { it.radio || it.id.startsWith("tv:") || Stremio.jeVZivo(it) || IptvSeznami.jeKanal(it) }
        streznikTrenutni = s
        // Kanal ali postaja v zivo: streznik, ki se v 5 s ne oglasi, ne dela (privzeti rok 8 s je za datoteke).
        val rokPovezave = if (enaVZivo) OsPravila.ROK_POVEZAVE_V_ZIVO_MS else 0
        // Datoteke z racunalnika gredo skozi pripeti vir (TLS z odtisom in zetonom Safeer Controla),
        // vse ostalo (splet, datoteke televizorja) skozi obicajnega.
        // DvdVir: slike ISO (safeer-dvd:) bere kot tok glavnega naslova diska, vse drugo gre naravnost naprej.
        val tovarna = (if (s != null) androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(PripetiVir.Tovarna(s.odtis, s.zeton, this, s.naprava)))
            else androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(SpletniVir.virPodatkov(this, rokPovezaveMs = rokPovezave))).setLoadErrorHandlingPolicy(hitraNapaka))
            .setSubtitleParserFactory(Podnapisi.Popravljalnik())
        // Tok z glavami (Stremio proxyHeaders) dobi svojo tovarno: glave spremljajo vse njegove zahteve, tudi dele HLS/DASH
        // na drugih gostiteljih, drugih tokov v vrsti pa ne zadevajo.
        val tovarneZGlavami = HashMap<Map<String, String>, androidx.media3.exoplayer.source.MediaSource.Factory>()
        fun tovarnaZa(sk: Jamendo.Skladba): androidx.media3.exoplayer.source.MediaSource.Factory {
            if (s != null) return tovarna
            val glave = SpletniVir.glaveToka(sk.zvok)
            if (glave.isEmpty()) return tovarna
            return tovarneZGlavami.getOrPut(glave) {
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(SpletniVir.virPodatkov(this, glave, rokPovezave)))
                    .setLoadErrorHandlingPolicy(hitraNapaka).setSubtitleParserFactory(Podnapisi.Popravljalnik())
            }
        }
        // Seznami HLS gredo skozi nas razclenjevalnik ([HlsSeznami]): kanal z neveljavno zapisanim datumom v seznamu je
        // prej odpovedal z napako razclenjevanja, drugi predvajalniki pa ga predvajajo.
        val hlsTovarne = HashMap<Map<String, String>, androidx.media3.exoplayer.source.MediaSource.Factory>()
        fun hlsTovarnaZa(sk: Jamendo.Skladba): androidx.media3.exoplayer.source.MediaSource.Factory? {
            if (s != null) return null
            val vrstaVsebine = androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(android.net.Uri.parse(sk.zvok), sk.mime.ifBlank { null })
            if (vrstaVsebine != C.CONTENT_TYPE_HLS) return null
            return hlsTovarne.getOrPut(SpletniVir.glaveToka(sk.zvok)) {
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(DvdVir.Tovarna(SpletniVir.virPodatkov(this, SpletniVir.glaveToka(sk.zvok), rokPovezave)))
                    .setPlaylistParserFactory(HlsSeznami()).setLoadErrorHandlingPolicy(hitraNapaka)
                    .setSubtitleParserFactory(Podnapisi.Popravljalnik())
            }
        }
        // Naloga 89: predvajane skladbe v predpomnilnik na USB (samo zvok, enodelni tok, ne v zivo, ne zasebno). Napake
        // predpomnilnika (odklop USB) predvajalnik prezre in bere z omrezja.
        val zvocni = if (s == null) PredpomnilnikZvoka.zdaj() else null
        val predpomnjeneTovarne = HashMap<Map<String, String>, androidx.media3.exoplayer.source.MediaSource.Factory>()
        // Naloga 90a: razlog, zakaj skladba ne gre v predpomnilnik (prazno = gre); v dnevnik SafeerOsGlasba.
        fun razlogBrez(sk: Jamendo.Skladba): String = if (zvocni == null) "USB predpomnilnik ni v uporabi" else PredpomnilnikPravila.razlogBrezPredpomnjenja(
            zvok = !sk.video || sk.id in zvocniTokovi,
            vZivo = enaVZivo || sk.radio || Stremio.jeVZivo(sk) || IptvSeznami.jeKanal(sk),
            http = sk.zvok.startsWith("https://") || sk.zvok.startsWith("http://"),
            enodelni = androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(android.net.Uri.parse(sk.zvok), sk.mime.ifBlank { null }) == C.CONTENT_TYPE_OTHER,
            zasebno = Stremio.jeZasebna(sk))
        fun vPredpomnilnik(sk: Jamendo.Skladba): Boolean = razlogBrez(sk).isEmpty()
        fun predpomnjenaTovarna(sk: Jamendo.Skladba): androidx.media3.exoplayer.source.MediaSource.Factory {
            val glave = SpletniVir.glaveToka(sk.zvok)
            return predpomnjeneTovarne.getOrPut(glave) {
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.cache.CacheDataSource.Factory()
                    .setCache(zvocni!!)
                    .setUpstreamDataSourceFactory(DvdVir.Tovarna(SpletniVir.virPodatkov(this, glave, rokPovezave)))
                    .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR))
                    .setLoadErrorHandlingPolicy(hitraNapaka).setSubtitleParserFactory(Podnapisi.Popravljalnik())
            }
        }
        p.setMediaSources(seznam.map { sk ->
            val pp = vPredpomnilnik(sk)
            val ime = sk.naslov.take(40)
            if (pp) {
                PredpomnilnikZvoka.zapomniTok(PredpomnilnikPravila.kljucZvoka(sk.id), sk)
                android.util.Log.i("SafeerOsGlasba", "predpomnjenje: zacetek \"$ime\" (v predpomnilniku ${PredpomnilnikZvoka.velikost() / 1024} KB)")
            } else if (!sk.radio) android.util.Log.i("SafeerOsGlasba", "predpomnjenje: preskoceno \"$ime\" - ${razlogBrez(sk)}")
            // Naloga 93: kanal v zivo - katera tovarna, vrsta vsebine po naslovu in mime, shema (brez naslova toka).
            if (enaVZivo) android.util.Log.i("SafeerOsMedia", "kanal v zivo \"$ime\": tovarna=" + when {
                    pp -> "predpomnjena"
                    hlsTovarnaZa(sk) != null -> "HLS"
                    SpletniVir.glaveToka(sk.zvok).isNotEmpty() -> "privzeta z glavami"
                    else -> "privzeta"
                } + ", vrsta=${androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(android.net.Uri.parse(sk.zvok), sk.mime.ifBlank { null })}" +
                ", mime=${sk.mime.ifBlank { "-" }}, shema=${sk.zvok.substringBefore(':')}")
            (if (pp) predpomnjenaTovarna(sk) else hlsTovarnaZa(sk) ?: tovarnaZa(sk)).createMediaSource(MediaItem.Builder().setMediaId(sk.id).setUri(sk.zvok)
                .apply { if (pp) setCustomCacheKey(PredpomnilnikPravila.kljucZvoka(sk.id)) }
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
        val prva = seznam.getOrNull(od.coerceIn(0, (seznam.size - 1).coerceAtLeast(0)))
        // Tok z vec zvocnimi sledmi: film v anglescini, slovenski film v slovenscini (TokIzbira.jezikiSledi).
        if (prva != null && prva.video) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages(*TokIzbira.jezikiSledi(prva.language).toTypedArray()).build()
        }
        val zvocnik = Zvocniki.aktivni
        val samodejno = naslednjaSamodejno
        naslednjaSamodejno = false
        if (zvocnik != null && prva != null && s == null && zaZvocnik(prva)) {
            // Glasba igra na zvocniku: tudi nova izbira gre tja; ta naprava ostane daljinec (ne nalaga in ne igra).
            // (Datoteke naprave v Linku - pripeti vir [s] - zvocnik ne more prebrati: te igra ta naprava.)
            p.playWhenReady = false
            posljiNaZvocnik(zvocnik, prva, zacetekPoskusa, samodejno)
            return
        }
        if (zvocnik != null) {
            // Tega zvocnik ne zmore (slika, pripeti vir, tok z glavami): igra ta naprava - razen ce je vrsta prisla do tega
            // sama. Zacne sele, ko zvocnik potrdi Stop (ali po kratkem roku), da hip ne igrata oba.
            p.playWhenReady = false
            Zvocniki.ustavi {
                if (primerek === this && mojaIzbira == izbir && exo === p && predvajalnik === p && !samodejno) { p.prepare(); p.play() }
                osvezi()
            }
            osvezi()
            return
        }
        p.prepare()
        p.play()
    }

    /**
     * Zaporedna stevilka izbire (nalaganje vrste, stran, konec predvajanja): zagon te naprave, ki caka na Stop zvocnika,
     * odpade, ce je uporabnik medtem izbral kaj drugega.
     */
    private var izbir = 0

    // ------------------------------------------------------------------ zvocnik v omrezju
    /** Vrsta je do naslednje skladbe prisla sama (konec prejsnje na zvocniku), ne po uporabnikovi izbiri. */
    private var naslednjaSamodejno = false
    private var zagonSamodejen = false

    /**
     * Trenutno skladbo vrste poslje zvocniku. Ce je zvocnik ne sprejme: skladbo, ki jo je izbral uporabnik, igra ta
     * naprava; kadar je vrsta do nje prisla sama ([samodejno] - nihce ne drzi telefona), ta naprava ostane na premoru
     * (ne zacne igrati iz svojega zvocnika).
     */
    private fun posljiNaZvocnik(z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, odMs: Long = 0L, samodejno: Boolean = false, igraj: Boolean = true) {
        val p = exo ?: return
        p.pause(); p.stop()
        zagonSamodejen = samodejno
        Zvocniki.predvajaj(this, z, sk, ZvocnikPravila.odPolozaja(odMs, 0L, sk.radio), 0L, igraj) { napaka ->
            // Zagon, ki ga je prehitela novejsa izbira, ni napaka zvocnika (novejsa izbira ze tece).
            if (napaka != null && napaka !is Zvocniki.Prehiteno && primerek === this) {
                obvesti(Zvocniki.besediloNapake(this, napaka, z.ime))
                // Ta naprava zacne sele, ko zvocnik potrdi Stop (ali po kratkem roku), in samo, ce je to se vedno izbrana skladba.
                Zvocniki.ustavi {
                    if (primerek === this && trenutna()?.id == sk.id) { if (samodejno || !igraj) osvezi() else naTejNapravi(odMs) }
                }
            }
        }
        osvezi()
    }

    /** Predvajanje (spet) na tej napravi. [odMs]: mesto, na katerem nadaljuje; null = kjer predvajalnik te naprave stoji. */
    private fun naTejNapravi(odMs: Long?) {
        // Posnetek s strani (YouTube), ki je igral na zvocniku: ta naprava nadaljuje v spletnem predvajalniku na istem mestu.
        val sk = trenutna()
        if (sk != null && SpletniVir.jeEnota(sk)) { nadaljujSplet(sk, odMs); return }
        val p = exo ?: return
        if (trenutna()?.radio == true) p.seekToDefaultPosition()
        else if (odMs != null) p.seekTo(p.currentMediaItemIndex, odMs.coerceAtLeast(0L))
        p.prepare()
        p.play()
        osvezi()
    }

    /** Spletni posnetek spet na tej napravi (po zvocniku): isti predvajalnik, ce ga se imamo, sicer nov - na mestu [odMs]. */
    private fun nadaljujSplet(sk: Jamendo.Skladba, odMs: Long?) {
        val s = spletni
        if (s != null && predvajalnik === s && spletniId == sk.id) {
            if (odMs != null) s.seekTo(odMs.coerceAtLeast(0L))
            s.play()
            osvezi()
            return
        }
        zacetekSpleta = if (odMs != null && odMs > 0L) sk.id to odMs else null
        zacniSplet(sk.copy(zvok = "", mime = ""), true)
    }

    /**
     * Po premiku na drugo skladbo vrste: kadar igra zvocnik, jo dobi zvocnik (ali ta naprava, ce je zvocnik ne zmore).
     * [igraj] = false (naprej/nazaj med premorom): skladba se zvocniku samo nalozi, kot na tej napravi ostane premor.
     */
    private fun poPremikuVVrsti(samodejno: Boolean = false, igraj: Boolean = true) {
        val z = Zvocniki.aktivni ?: return
        val sk = trenutna() ?: return
        if (zaZvocnik(sk)) posljiNaZvocnik(z, sk, 0L, samodejno, igraj)
        else Zvocniki.ustavi { if (primerek === this && trenutna()?.id == sk.id) { if (samodejno || !igraj) osvezi() else naTejNapravi(0L) } }
    }

    /** [Zvocniki.obKoncu]: skladba na zvocniku se je koncala, zvocnik je prevzel kdo drug ali pa skladba ni stekla. */
    private fun zvocnikJavlja(dejanje: ZvocnikPravila.Dejanje, polozajMs: Long, ime: String, zelelIgrati: Boolean) {
        val p = exo ?: return
        when (dejanje) {
            ZvocnikPravila.Dejanje.KONEC_SKLADBE -> {
                val z = Zvocniki.aktivni ?: return
                val naslednjaVVrsti = spletnaVrsta.getOrNull(spletniIndeks + 1)
                when {
                    // Ponavljanje ene skladbe ima prednost pred vrsto (kot na tej napravi).
                    p.repeatMode == Player.REPEAT_MODE_ONE -> trenutna()?.let { posljiNaZvocnik(z, it, 0L, true) }
                    naslednjaVVrsti != null -> {
                        if (!zaZvocnik(naslednjaVVrsti) && !UvozSeznama.jeIskana(naslednjaVVrsti)) {
                            // Naslednji posnetek ne more na zvocnik. Vrsta je do njega prisla sama (nihce ne drzi naprave),
                            // zato ta naprava ne zacne igrati iz svojega zvocnika: seja se konca, uporabnik nadaljuje z Naprej.
                            Zvocniki.koncajSejo(); osvezi()
                        } else { naslednjaSamodejno = true; zacniElement(spletniIndeks + 1); naslednjaSamodejno = false }
                    }
                    p.hasNextMediaItem() -> { p.seekToNextMediaItem(); poPremikuVVrsti(true) }
                    // Konec vrste - kot pri predvajanju na tej napravi.
                    else -> { Zvocniki.koncajSejo(); konec() }
                }
            }
            ZvocnikPravila.Dejanje.NI_ZACELO -> {
                // Zvocnik skladbe ne zmore (oblika, nedosegljiv naslov): skladbo, ki jo je izbral uporabnik, igra ta naprava -
                // razen ce je vrsta do nje prisla sama ali je uporabnik medtem izbral premor.
                obvesti(getString(R.string.zvocnik_napaka, ime))
                // Nadaljuje na mestu, ki je cakalo na zvocnik (prenos sredi skladbe ali podkasta), ne od zacetka.
                if (zagonSamodejen || !zelelIgrati) osvezi() else naTejNapravi(polozajMs)
            }
            ZvocnikPravila.Dejanje.USTAVI -> {
                // Zvocnik premora ni izvedel niti po ponovitvah, zato je ustavljen: ta naprava ostane na premoru na istem mestu.
                if (trenutna()?.radio != true) p.seekTo(p.currentMediaItemIndex, polozajMs.coerceAtLeast(0L))
                obvesti(getString(R.string.zvocnik_ustavljen, ime))
                osvezi()
            }
            ZvocnikPravila.Dejanje.KONEC_POZEN -> {
                // Skladba na zvocniku se je koncala, a tega nismo videli sproti (zvocnik dolgo ni bil dosegljiv ali je naprava
                // spala): naslednje NE posljemo - glasba ne sme zaceti sama cez ure. Seja je ze koncana. Odigrana vrsta se konca
                // kot sicer; sicer ta naprava ostane na premoru pri koncu te skladbe (»predvajaj« nadaljuje z naslednjo tukaj).
                if (!zvocnikImaNadaljevanje()) konec()
                else {
                    p.seekTo(p.currentMediaItemIndex, polozajMs.coerceAtLeast(0L))
                    obvesti(getString(R.string.zvocnik_konec, ime))
                    osvezi()
                }
            }
            else -> {
                // Zvocnik je prevzel kdo drug (drug vhod, druga naprava) ali ni vec dosegljiv: ta naprava ostane na premoru
                // na istem mestu, da uporabnik lahko nadaljuje tukaj (predvajalnik se pripravi ob prvem »predvajaj«).
                if (trenutna()?.radio != true) p.seekTo(p.currentMediaItemIndex, polozajMs.coerceAtLeast(0L))
                obvesti(getString(R.string.zvocnik_konec, ime))
                osvezi()
            }
        }
    }

    private var glasnostZvocnika: android.media.VolumeProvider? = null
    /** UDN zvocnika, na katerega seja usmerja tipke za glasnost ("" = glasnost te naprave). */
    private var sejaNaZvocniku = ""

    /**
     * Tipke za glasnost in drsnik sistema (tudi na zaklenjenem zaslonu in v drugih programih): med predvajanjem na
     * zvocniku nastavljajo glasnost zvocnika, ne te naprave.
     */
    private fun uskladiGlasnostSeje() {
        val s = seja ?: return
        val z = Zvocniki.aktivni
        if (z == null) {
            if (sejaNaZvocniku.isNotEmpty()) {
                sejaNaZvocniku = ""; glasnostZvocnika = null
                s.setPlaybackToLocal(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            }
            return
        }
        val najvec = Zvocniki.najGlasnost
        val znana = Zvocniki.glasnost
        val obstojeci = glasnostZvocnika
        if (sejaNaZvocniku == z.udn && obstojeci != null && obstojeci.maxVolume == najvec) {
            if (znana >= 0 && obstojeci.currentVolume != znana.coerceIn(0, najvec)) obstojeci.currentVolume = znana.coerceIn(0, najvec)
            return
        }
        val nov = object : android.media.VolumeProvider(android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE, najvec, znana.coerceIn(0, najvec)) {
            override fun onAdjustVolume(direction: Int) {
                // Samo glasneje/tisje; utisanje in drugi ukazi sistema zvocnika ne zadevajo.
                if (direction == 1 || direction == -1) Zvocniki.glasnostZa(direction * ZvocnikPravila.korakGlasnosti(Zvocniki.najGlasnost, Zvocniki.lestvicaZnana))
            }
            override fun onSetVolumeTo(volume: Int) { Zvocniki.glasnostNa(volume) }
        }
        glasnostZvocnika = nov; sejaNaZvocniku = z.udn
        s.setPlaybackToRemote(nov)
    }

    /** Kdaj se je zacel zadnji poskus predvajanja (uptimeMillis) in pri katerem mestu - za preklop na rezervni tok. */
    private var poskusOd = 0L
    private var zacetekPoskusa = 0L

    /**
     * Mrtva povezava (odgovor 4xx: potekla, izbrisana, zavrnjena) se s ponavljanjem ne popravi. Kadar ima vsebina
     * rezervne tokove, zato ne cakamo na tri ponovitve (nekaj sekund), ampak gremo takoj na naslednjega. Brez rezerv
     * ostane privzeto vedenje (ponovitve), ker pomocnik v Linku med pripravo torrenta lahko hip odgovarja z napako.
     */
    private val IMENIK_PONOVI_MS = 700L
    /** Kljuc odgovora imenika za streznik, ki se ne odziva (ime obstaja; vprasali smo samo za kontrolo). */
    private val NEODZIVEN = "*"
    private val hitraNapaka = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (enaVZivo && !zacelo) {
                // Kanal ali postaja se ni stekla, streznika toka pa ni v imeniku (DNS). Tri ponovitve tega ne popravijo,
                // uporabnik pa caka (izmerjeno 5. 10. 2026: 3,1 s do sporocila): en ponovni poskus za hip brez omrezja,
                // medtem imenik vprasamo sveze. Med predvajanjem ostane privzeto vedenje (zaloga premosti kratek izpad).
                val gostitelj = neznanGostitelj(loadErrorInfo.exception)
                if (gostitelj.isNotEmpty()) {
                    vprasajImenik(gostitelj)
                    return if (loadErrorInfo.errorCount >= 2) C.TIME_UNSET else IMENIK_PONOVI_MS
                }
                if (streznikSeNeOdziva(loadErrorInfo.exception)) {
                    // Streznik toka ni odgovoril v roku ali je povezavo zavrnil. Izmerjeno 5. 10. 2026: stirje poskusi po
                    // 8 s = 35 s vrtenja do sporocila. En poskus je dovolj. Pred koncem vprasamo imenik za kontrolno ime,
                    // da locimo neodziven streznik od izpada omrezja te naprave: ta nit ni glavna (odgovor pride po
                    // glavni), zato sme pocakati; na glavni niti ne cakamo in kanal ostane.
                    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                        val o = Imenik.obstaja(listOf(Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
                        odgovorImenika = OdgovorImenika(NEODZIVEN, true, o[0], android.os.SystemClock.uptimeMillis())
                    }
                    return C.TIME_UNSET
                }
            }
            var e: Throwable? = loadErrorInfo.exception
            while (e != null) {
                if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    if (rezerve.isNotEmpty() && e.responseCode in 400..499) return C.TIME_UNSET
                    // Kanal ali postaja v zivo: vir je odgovoril, da prenosa ni (404, 403, 410 ...). Ponavljanje tega ne
                    // popravi, uporabnik pa caka (izmerjeno 5. 10. 2026: 7,5 s do sporocila) - en poskus je dovolj.
                    if (enaVZivo && OsPravila.mrtevKanal(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, e.responseCode)) return C.TIME_UNSET
                    break
                }
                e = e.cause
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private var exo: ExoPlayer? = null
    private var spletni: SpletniIgralec? = null
    /** Skladba, ki jo kaze [spletni]. */
    private var spletniId = ""
    /** (id, ms): posnetek s strani, ki se vraca z zvocnika, ob prvem predvajanju skoci na to mesto. */
    private var zacetekSpleta: Pair<String, Long>? = null

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
    /**
     * Naloga 89: tokovi (id), katerih izvirnik je zvok - posnetek YouTube iz seznama glasbe je po razresitvi »video«
     * ([SpletniVir.razresenaSkladba]), v predpomnilnik zvoka pa sodi. Samo glavna nit; omejeno.
     */
    private val zvocniTokovi = LinkedHashSet<String>()

    private fun zapomniZvocniTok(id: String) {
        zvocniTokovi.remove(id); zvocniTokovi += id
        while (zvocniTokovi.size > 200) zvocniTokovi.remove(zvocniTokovi.first())
    }

    private fun zacniSplet(sk: Jamendo.Skladba, dovoliPrevzem: Boolean) {
        if (Zvocniki.aktivni != null) {
            // Stran igra na tej napravi: zacne sele, ko zvocnik potrdi Stop (ali po kratkem roku), da hip ne igrata oba.
            val mojaIzbira = ++izbir
            Zvocniki.ustavi { if (primerek === this && mojaIzbira == izbir) zacniSplet(sk, dovoliPrevzem) }
            return
        }
        izbir++
        exo?.let { it.stop(); it.clearMediaItems() }
        koncajSplet()
        // Naloga 89: cela skladba je v predpomnilniku na USB - igra naravnost iz njega, brez iskanja toka (LahkaStran) in
        // brez omrezja. Samo zvok (glasba, skladbe seznamov).
        if (!sk.video && !sk.radio) PredpomnilnikZvoka.celota(sk)?.let { tok ->
            android.util.Log.i("SafeerOsMedia", "zvok: iz predpomnilnika na USB, brez iskanja toka")
            zapomniZvocniTok(tok.id)
            nalozi(listOf(tok), 0, null)
            return
        }
        if (rabiPolniPogon(sk)) { zacniPolniSplet(sk); return }
        lateinit var lahki: LahkaStran
        lahki = LahkaStran(this, sk,
            dovoliPrevzem = dovoliPrevzem,
            obToku = { tok ->
                if (spletni === lahki) {
                    spletni = null
                    android.util.Log.i("SafeerOsMedia", "stopnja=2 uspeh=Media3")
                    // Naloga 89: izvirnik je zvok - tok gre v predpomnilnik zvoka (ce je USB v uporabi).
                    if (!sk.video && !sk.radio) zapomniZvocniTok(tok.id)
                    nalozi(listOf(tok), 0, null)
                }
            },
            obPripravi = { pripravljen ->
                if (spletni === pripravljen) {
                    android.util.Log.i("SafeerOsMedia", "stopnja=2 uspeh=LahkaStran")
                    // Naloga 90a: spletna stran (YouTube) igra v WebView, tok ne gre skozi Media3 - predpomnjenje ni mogoce.
                    android.util.Log.i("SafeerOsGlasba", "predpomnjenje: preskoceno \"${sk.naslov.take(40)}\" - igra na spletni strani (WebView), ne prek Media3")
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
        // Naloga 109: napaka predvajalnika na strani (YouTube) z razlogom.
        s.obNapakiStrani = { besedilo -> napakaSpletneStrani(s, sk, besedilo) }
        // Naloga 113: stran, ki ne zacne igrati, ne nalaga vecno.
        s.obZastoju = { zastojSpletneStrani(s, sk) }
        s.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Naloga 109: skladba igra - star zapis, da ne deluje, ne velja vec.
                if (isPlaying) { NedelujoceSkladbe.pozabi(this@GlasbaStoritev, sk.id); zaporedniZastoji = 0 }
                // Nadaljevanje po zvocniku: na mesto, kjer je zvocnik nehal - ko stran zares igra (prej bi skok izgubila).
                val zacetek = zacetekSpleta
                if (isPlaying && zacetek != null && zacetek.first == sk.id) { zacetekSpleta = null; s.seekTo(zacetek.second) }
                osvezi()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = osvezi()
            override fun onMediaMetadataChanged(mediaMetadata: M3Metadata) {
                val t = mediaMetadata.title?.toString().orEmpty()
                if (t.isNotBlank()) vrsta = vrsta.map { it.copy(naslov = t, izvajalec = mediaMetadata.artist?.toString()?.ifBlank { null } ?: it.izvajalec) }
                osvezi()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) poKoncu() else osvezi() }
        })
        spletni = s
        spletniId = sk.id
        s.vVrsti = spletnaVrsta.size > 1
        SpletniIgralec.zadnja?.get()?.let { s.gostuj(it) }
        vrsta = listOf(sk)
        predvajalnik = s
        MedijskiViri.zapomniNedavno(this, sk)
        osvezi()
    }

    /**
     * Naloga 109: spletna stran posnetka kaze napako predvajalnika. Trajen razlog ([NedelujocePravila.trajen]) se zapise
     * ([NedelujoceSkladbe]) in vrsta gre tiho naprej (naslednja predvajljiva skladba); brez naslednje kratko obvestilo z
     * razlogom. Nepoznana napaka: nic (stran ostane, kot doslej).
     */
    private fun napakaSpletneStrani(s: SpletniIgralec, sk: Jamendo.Skladba, besedilo: String) {
        if (spletni !== s) return
        val razlog = NedelujocePravila.izNapakeYoutube(besedilo)
        Log.i("SafeerOsMedia", "skladba ne deluje: \"${sk.naslov.take(40)}\", razlog=${razlog.ifEmpty { "ni znano" }}")
        if (!NedelujocePravila.trajen(razlog)) return
        NedelujoceSkladbe.zapomni(this, sk.id, razlog, sk.naslov)
        // Ne v povratnem klicu strani: naslednja skladba sprosti ta pogled (WebView).
        android.os.Handler(mainLooper).post {
            if (spletni !== s || primerek !== this) return@post
            val naprej = if (spletnaVrsta.size > 1) NedelujocePravila.prviPredvajljiv(spletnaVrsta.map { NedelujoceSkladbe.razlog(this, it.id) }, spletniIndeks + 1) else -1
            if (naprej >= 0) zacniElement(naprej)
            else { obvesti(getString(R.string.os_nedel_skladba, sk.naslov, NedelujoceSkladbe.opis(this, razlog))); ustaviPredvajanje() }
        }
    }

    private fun koncajSplet() { spletni?.release(); spletni = null }

    /**
     * Naloga 72: podnapisi iz dodatka k posnetku, ki ze igra - isti tok (z istimi glavami) z dodanimi podnapisi na istem
     * mestu, brez ponovnega iskanja toka in brez ustavitve. Tok HLS podnapisov ob strani ne sprejme (HlsMediaSource) in
     * tok z racunalnika gre skozi pripeti vir: takrat false.
     */
    private fun dodajPodnapiseVTok(novi: List<Podnapisi.Podnapis>): Boolean {
        val p = exo ?: return false
        if (predvajalnik !== p || streznikTrenutni != null || novi.isEmpty()) return false
        val i = p.currentMediaItemIndex
        val sk = vrsta.getOrNull(i) ?: return false
        val vrstaToka = androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(android.net.Uri.parse(sk.zvok), sk.mime.ifBlank { null })
        if (vrstaToka == C.CONTENT_TYPE_HLS) return false
        val nov = sk.copy(podnapisi = (sk.podnapisi + novi).distinctBy { it.url })
        if (nov.podnapisi.size == sk.podnapisi.size) return true
        val tovarna = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DvdVir.Tovarna(SpletniVir.virPodatkov(this, SpletniVir.glaveToka(sk.zvok))))
            .setLoadErrorHandlingPolicy(hitraNapaka).setSubtitleParserFactory(Podnapisi.Popravljalnik())
        val vir = tovarna.createMediaSource(MediaItem.Builder().setMediaId(nov.id).setUri(nov.zvok)
            .apply { if (nov.mime.isNotBlank()) setMimeType(nov.mime) }
            .setSubtitleConfigurations(Podnapisi.konfiguracije(this, nov.podnapisi))
            .setMediaMetadata(M3Metadata.Builder().setTitle(nov.naslov).setArtist(nov.izvajalec).build())
            .build())
        val kje = p.currentPosition
        // Vrsta mora ves cas ustrezati predvajalniku (trenutna() med prehodom): nov posnetek za starim, nato stari ven.
        vrsta = vrsta.toMutableList().also { it.add(i + 1, nov) }
        p.addMediaSource(i + 1, vir)
        p.seekTo(i + 1, kje)
        p.removeMediaItem(i)
        vrsta = vrsta.toMutableList().also { it.removeAt(i) }
        Log.i("SafeerOsMedia", "podnapisi iz dodatka dodani k toku: ${novi.size} (na ${kje / 1000} s)")
        return true
    }

    /** Predvajalnik z odprto izbiro naprave (lastnik 27. 10. 2026: zvocniki v omrezju tudi brez odprtega Safeer OS). */
    private fun izbiraNaprave(): PendingIntent = PendingIntent.getActivity(this, 15,
        Intent(this, PredvajanjeActivity::class.java).putExtra(PredvajanjeActivity.IZBERI_NAPRAVO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
            // Kje igra, kadar ne igra ta naprava: gumbi obvestila takrat upravljajo zvocnik.
            .setSubText(Zvocniki.napis(this))
            // Na zaklenjenem zaslonu cel predvajalnik (kot drugi glasbeni programi); vsebina je naslov skladbe.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // Lastnik 27. 10. 2026: upravljanje tudi, ko Safeer OS ni odprt (kot Spotify). MediaStyle s sejo: Android pokaze
        // predvajalnik na zaklenjenem zaslonu in v hitrih nastavitvah z drsnikom casa (seja ima ACTION_SEEK_TO in trajanje).
        val radio = s?.radio == true
        val dejanja = ObvestiloPredvajanjaPravila.dejanja(imaNaprej(), radio)
        for (d in dejanja) {
            val (ikona, opis, akcija, koda) = when (d) {
                ObvestiloPredvajanjaPravila.NAZAJ -> listOf(R.drawable.os_ikona_prejsnja, getString(R.string.zg_prejsnja), AKCIJA_NAZAJ, 14)
                ObvestiloPredvajanjaPravila.PREKLOPI -> listOf(if (igra()) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj,
                    getString(if (igra()) R.string.zg_premor else R.string.zg_predvajaj), AKCIJA_TOGGLE, 11)
                ObvestiloPredvajanjaPravila.NAPREJ -> listOf(R.drawable.os_ikona_naslednja, getString(R.string.zg_naslednja), AKCIJA_NAPREJ, 12)
                ObvestiloPredvajanjaPravila.NA_NAPRAVI -> listOf(R.drawable.os_ikona_predvajaj_na, getString(R.string.zvocnik_na_napravi), "", 15)
                else -> listOf(R.drawable.os_ikona_ustavi, "Ustavi", AKCIJA_USTAVI, 13)
            }
            // »Predvajaj na napravi« odpre predvajalnik z izbiro naprave (izbira rabi zaslon); ostali gumbi gredo storitvi.
            val namen = when {
                d == ObvestiloPredvajanjaPravila.NA_NAPRAVI -> izbiraNaprave()
                // Lastnik 27. 10. 2026: Predvajaj pri filmu ali epizodi odpre predvajalnik s sliko (ne samo zvok v ozadju).
                d == ObvestiloPredvajanjaPravila.PREKLOPI && MiniPredvajalnikPravila.dejanjePredvajaj(s?.video == true, igra()) ==
                    MiniPredvajalnikPravila.ODPRI_PREDVAJALNIK -> PendingIntent.getActivity(this, 16,
                    Intent(this, PredvajanjeActivity::class.java).putExtra(PredvajanjeActivity.NADALJUJ, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else -> dejanje(akcija as String, koda as Int)
            }
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, ikona as Int),
                opis as String, namen).build())
        }
        b.setStyle(Notification.MediaStyle().also { st ->
            seja?.let { st.setMediaSession(it.sessionToken) }
            st.setShowActionsInCompactView(*ObvestiloPredvajanjaPravila.strnjeno(dejanja))
        })
        return b.setOngoing(true).build()
    }

    private fun osvezi() {
        if (predvajalnik == null) return
        // Pregled 5: isto stanje kot igra() na glavni niti (playWhenReady, tudi med nalaganjem) - tudi za druge niti.
        predvajalnik?.let { igraZadnje = teceLokalno(it) }
        val s = trenutna()
        // Med nedavno predvajane gre skladba sele, ko res igra - tudi na zvocniku (preskocene ne).
        if (naZvocniku() && Zvocniki.stanje.let { it.igral && it.zeliIgrati }) zapomniNedavnoEnkrat()
        // Stanje, cas in trajanje tistega, ki igra: te naprave ali zvocnika v omrezju.
        seja?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s?.naslov ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s?.izvajalec ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, trajanjeMs().takeIf { it > 0 } ?: -1L)
            .build())
        seja?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO)
            .addCustomAction(PlaybackState.CustomAction.Builder(AKCIJA_NA_NAPRAVI, getString(R.string.zvocnik_na_napravi),
                R.drawable.os_ikona_predvajaj_na).build())
            .setState(stanjeSeje(), polozajMs(), if (stanjeSeje() == PlaybackState.STATE_BUFFERING) 0f else hitrostZaSejo())
            .build())
        uskladiGlasnostSeje()
        getSystemService(NotificationManager::class.java).notify(ID, obvestilo())
        poslusalci.toList().forEach { it() }
    }

    override fun onDestroy() {
        preklicPonovitveVZivo()
        application.unregisterActivityLifecycleCallbacks(zasloni)
        // Konec predvajanja je tudi konec na zvocniku (brez te naprave bi igral naprej brez daljinca).
        if (primerek === this) { Zvocniki.obSpremembi = null; Zvocniki.obKoncu = null; Zvocniki.ustavi {} }
        // Naloga 86 (M1): release() ne poklice onIsPlayingChanged(false) - stanje za klice iz ozadja (PregledKanalov) bi sicer
        // ostalo »igra« do naslednjega predvajanja (casovnik izklopa med predvajanjem: pregled kanalov se ne bi vec zacel).
        if (primerek === this) igraZadnje = false
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
        private const val AKCIJA_NAZAJ = "si.safeer.media.PREVIOUS"
        private const val AKCIJA_NA_NAPRAVI = "si.safeer.media.NA_NAPRAVI"
        /** Strani, za katere vemo, da v varcnem pogledu ne tecejo (aplikacije v JavaScriptu). */
        private val POLNI_POGON = setOf("youtube.com", "youtu.be")

        /** Predvajalnik, dokler storitev tece; sicer null. */
        @Volatile var predvajalnik: Player? = null
            private set
        /** Kaj gre ta hip iz zvocnikov, kadar je to vredno povedati (»Dolby Atmos«, »5.1 → stereo«); sicer prazno. */
        @Volatile var zvocniIzhod: String = ""
            private set
        private var vrsta: List<Jamendo.Skladba> = emptyList()
        /** Streznik (racunalnik/naprava s pripetim potrdilom), s katerega tece trenutni seznam; null za splet in lokalno. */
        @Volatile var streznikTrenutni: DatotekeActivity.Streznik? = null
            private set
        private var cakajoci: Triple<List<Jamendo.Skladba>, Int, DatotekeActivity.Streznik?>? = null
        /** (id posnetka, mesto v ms): naslednje nalaganje tega posnetka zacne pri tem mestu (nadaljevanje ogleda). */
        @Volatile var zacetnoMesto: Pair<String, Long>? = null
        /**
         * Lastnik 28. 10. 2026: film z napredkom - uporabnik izbere »Nadaljuj od …« ali »Od zacetka« (zaslon predvajanja,
         * [PredvajanjeActivity]). (id predvajanega elementa, mesto ms); do izbire predvajanje miruje.
         */
        @Volatile var ponudbaNadaljevanja: Pair<String, Long>? = null
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
            stevecZagonov.incrementAndGet()
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

        private fun nastavitveSamodejno(c: Context) = c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE)
        fun samodejnoNaslednja(c: Context): Boolean = nastavitveSamodejno(c).getBoolean(MiniPredvajalnikPravila.KLJUC_SAMODEJNO_NASLEDNJA, true)
        fun samodejnoAlbum(c: Context): Boolean = nastavitveSamodejno(c).getBoolean(MiniPredvajalnikPravila.KLJUC_SAMODEJNO_ALBUM, true)
        fun nastaviSamodejnoNaslednja(c: Context, da: Boolean) {
            nastavitveSamodejno(c).edit().putBoolean(MiniPredvajalnikPravila.KLJUC_SAMODEJNO_NASLEDNJA, da).apply()
            // pauseAtEndOfMediaItems ima samo ExoPlayer (predvajalnik je lahko tudi spletni igralec).
            (predvajalnik as? androidx.media3.exoplayer.ExoPlayer)?.pauseAtEndOfMediaItems = !da
        }
        fun nastaviSamodejnoAlbum(c: Context, da: Boolean) {
            nastavitveSamodejno(c).edit().putBoolean(MiniPredvajalnikPravila.KLJUC_SAMODEJNO_ALBUM, da).apply()
        }

        /** Ali Naprej kam pelje: naslednja skladba navadne vrste ali naslednji posnetek seznama. */
        fun imaNaslednjo(): Boolean = spletnaVrsta.size > spletniIndeks + 1 || predvajalnik?.hasNextMediaItem() == true

        /** Ali gumb Naprej kam pelje - tudi zadnja glasba z YouTuba (samodejni mix). Za gumbe; vrsta sama gleda [imaNaslednjo]. */
        fun imaNaprej(): Boolean = imaNaslednjo() || spletnaVrsta.getOrNull(spletniIndeks)?.let {
            YoutubeMixPravila.samodejnoNaprej(false, YoutubeZvok.jeYoutube(it), it.video) } == true

        // ---- Kdo igra: ta naprava ali zvocnik v omrezju ([Zvocniki]). Zasloni, seja, obvestilo in tipke sprasujejo tu.

        /** Glasba igra na zvocniku v omrezju; ta naprava je daljinec. */
        fun naZvocniku(): Boolean = predvajalnik != null && Zvocniki.aktivni != null

        /**
         * Zadnje znano stanje predvajalnika za klice iz drugih niti: Media3 dovoli dostop samo z glavne niti (izmerjeno na TV
         * 23. 10. 2026 04:02 - PregledKanalov je iz ozadja klical igra() in aplikacija se je ob zacetku predvajanja sesula).
         */
        @Volatile var igraZadnje = false
        /**
         * Ali predvajanje tece po uporabnikovi zelji. Pregled C1 (25. 10. 2026): na glavni niti po playWhenReady (tudi med
         * nalaganjem), ne isPlaying - prej je bil med nalaganjem prikazan »Predvajaj« in preklop ni mogel ustaviti.
         */
        fun igra(): Boolean = when {
            naZvocniku() -> Zvocniki.stanje.zeliIgrati
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper() -> igraZadnje
            else -> predvajalnik?.let { teceLokalno(it) } == true
        }

        /** Nas ali spletni predvajalnik tece ali se nalaga po zelji: ni pavze, ni napake, ni konca. */
        private fun teceLokalno(p: Player): Boolean =
            p.playWhenReady && p.playerError == null && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)

        /** Stanje za sejo sistema: med nalaganjem na tej napravi STATE_BUFFERING (cas na zaklenjenem zaslonu ne tece). */
        private fun stanjeSeje(): Int = when {
            !igra() -> PlaybackState.STATE_PAUSED
            !naZvocniku() && predvajalnik?.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PLAYING
        }

        fun polozajMs(): Long = if (naZvocniku()) Zvocniki.polozaj() else predvajalnik?.currentPosition?.coerceAtLeast(0L) ?: 0L

        fun trajanjeMs(): Long = if (naZvocniku()) Zvocniki.stanje.trajanjeMs else predvajalnik?.duration?.takeIf { it > 0 } ?: 0L

        /**
         * Po predvajanju na zvocniku predvajalnik te naprave stoji (ne nalaga), dokler ga uporabnik spet ne potrebuje:
         * preden igra, ga pripravimo. Brez tega »predvajaj« po koncu seje na zvocniku ne bi naredil nicesar. Pri navadnem
         * predvajanju na tej napravi to stanje (stoji, ima vrsto) ne nastane. Tudi po napaki »predvajaj« pripravi isti
         * vnos znova (28. 10. 2026: mini predvajalnik po vrnitvi na domaci zaslon sicer ni nadaljeval).
         */
        private fun zbudiPredvajalnik() {
            val s = primerek ?: return
            val e = s.exo ?: return
            // Naloga 102 (pregled 101, #4): kanal v zivo, katerega ponovna povezava ni stekla, ker je uporabnik ustavil
            // (IDLE po napaki ali ENDED): predvajaj ga pripravi znova od zivega mesta.
            if (predvajalnik === e && s.prekinjenaPonovitevVZivo && e.mediaItemCount > 0 &&
                (e.playbackState == Player.STATE_IDLE || e.playbackState == Player.STATE_ENDED)) {
                s.prekinjenaPonovitevVZivo = false
                e.seekToDefaultPosition(); e.prepare()
                return
            }
            if (predvajalnik === e && e.playbackState == Player.STATE_IDLE && e.mediaItemCount > 0) e.prepare()
        }

        /**
         * Naloga 117: dock, tipke Media Centra in zaganjalnik upravljajo drugo napravo v Linku (seja daljinca po »Poslji na
         * napravo«), dokler tu ni nalozeno nic ([OddaljenoPravila.upravljaCilj], pregled C1). Zaslon predvajanja, obvestilo in
         * seja sistema kazejo to napravo, zato klicejo ...Tu() in nikoli ne upravljajo druge.
         */
        fun naDrugiNapravi(): Boolean = OddaljenoPredvajanje.cilj != null &&
            // Pregled C1: samo, ko tu ni nalozeno nic (trenutna() bere predvajalnik - samo z glavne niti).
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && OddaljenoPredvajanje.upravlja(trenutna() != null)

        fun nadaljuj() {
            if (naDrugiNapravi()) { OddaljenoPredvajanje.nadaljuj(); return }
            nadaljujTu()
        }

        /** Ukazi TE naprave (zaslon predvajanja, obvestilo, seja, ukazi daljinca): nikoli ne gredo na drugo napravo. */
        /**
         * Lastnik 28. 10. 2026 (preverba TV, beta 29): izbrani vir ne omogoca skoka na shranjeno mesto (MKV brez indeksa) -
         * naslednji rezervni vir zacne tam (kot ob okvari vira). false, ce rezerv ni.
         */
        fun drugVirOd(c: Context, mesto: Long): Boolean {
            val naslednji = rezerve.firstOrNull() ?: return false
            val ostale = rezerve.drop(1)
            SpletniVir.zapomniGlaveToka(naslednji.first.zvok, naslednji.second)
            zacetnoMesto = naslednji.first.id to mesto
            Log.i("SafeerOsMedia", "vir brez skoka: naslednji vir od $mesto ms, rezerv=${ostale.size}")
            predvajaj(c, listOf(naslednji.first), 0)
            rezerve = ostale
            return true
        }

        fun nadaljujTu() { if (naZvocniku()) Zvocniki.nadaljuj() else { zbudiPredvajalnik(); predvajalnik?.play() } }

        fun premor() {
            if (naDrugiNapravi()) { OddaljenoPredvajanje.premor(); return }
            premorTu()
        }

        /** Premor TE naprave (play.stop z druge naprave, ukazi daljinca): nikoli ne gre naprej na drugo napravo. */
        fun premorTu() { if (naZvocniku()) Zvocniki.premor() else predvajalnik?.pause() }

        fun preklopi() {
            if (naDrugiNapravi()) { OddaljenoPredvajanje.preklopi(); return }
            preklopiTu()
        }

        /** Preklop TE naprave po playWhenReady (pregled C1): med nalaganjem gumb ustavi, kot kaze ikona. */
        fun preklopiTu() {
            if (naZvocniku()) Zvocniki.preklopi()
            else predvajalnik?.let { if (teceLokalno(it)) it.pause() else { zbudiPredvajalnik(); it.play() } }
        }

        /**
         * Pregled C5: posnetek v zivo (radio, kanal, HLS v zivo) - kot prikaz »V zivo« na zaslonu predvajanja; previjanje
         * tedaj ne velja. Klic z glavne niti.
         */
        fun vZivo(): Boolean {
            val p = predvajalnik ?: return false
            val sk = trenutna()
            return OsPravila.prikazVZivo(p.isCurrentMediaItemLive, p.isCurrentMediaItemDynamic,
                sk != null && (sk.radio || sk.id.startsWith("tv:") || Stremio.jeVZivo(sk) || IptvSeznami.jeKanal(sk)))
        }

        /** Skok (zaslon, seja sistema, ukaz daljinca): v zivo in pri neprevijalnem posnetku ga ni (pregled C5). */
        fun skoci(ms: Long) {
            if (vZivo()) return
            if (naZvocniku()) Zvocniki.skoci(ms) else predvajalnik?.let { if (it.isCurrentMediaItemSeekable) it.seekTo(ms) }
        }

        /** Hitrost za sejo sistema: dokler zvok na zvocniku se ne tece (zagon, pristajanje po preskoku), cas stoji. */
        private fun hitrostZaSejo(): Float {
            if (!naZvocniku()) return 1f
            val z = Zvocniki.stanje
            // Kratek zamik ob nadaljevanju ne steje; pristajanje po preskoku (sekunda ali dve) pa.
            return if (z.zeliIgrati && z.igral && z.cakajociPreskokMs < 0L && android.os.SystemClock.elapsedRealtime() + 500L >= z.veljaOd) 1f else 0f
        }

        /** Po koncu skladbe na zvocniku bo treba poslati naslednjo (vrsta ali ponavljanje): naprava mora ostati budna. */
        fun zvocnikImaNadaljevanje(): Boolean = spletnaVrsta.size > spletniIndeks + 1 ||
            predvajalnik?.let { it.hasNextMediaItem() || it.repeatMode != Player.REPEAT_MODE_OFF } == true

        /** Skladba iz vrste (kartica »V vrsti«): na zvocniku, kadar igra zvocnik, sicer tu. */
        fun predvajajIzVrste(indeks: Int) {
            val p = predvajalnik ?: return
            if (indeks !in 0 until p.mediaItemCount) return
            p.seekTo(indeks, 0L)
            val s = primerek
            if (s != null && naZvocniku()) s.poPremikuVVrsti() else { zbudiPredvajalnik(); p.play() }
        }

        /**
         * »Predvajaj na zvocniku«: kar igra tu, gre na zvocnik in nadaljuje na istem mestu; ta naprava postane daljinec
         * (ena glasba naenkrat). Napako dobi [konec].
         */
        fun naZvocnik(ctx: Context, z: DlnaPravila.Zvocnik, izbrana: Jamendo.Skladba, konec: (Exception?) -> Unit) {
            val p = predvajalnik
            // Med izbiranjem zvocnika je lahko zacela igrati druga skladba: na zvocnik gre tista, ki igra ZDAJ. (Prej je
            // zvocnik dobil staro, jo takoj ustavil, ta naprava pa je igrala naprej - izbira zvocnika brez ucinka.)
            val sk = (if (p != null) trenutna() else null) ?: izbrana
            // Nic ne igra vec (uporabnik je med iskanjem zvocnikov ustavil predvajanje) ali pa tega zvocnik ne zmore sam:
            // zvocniku ne posljemo nicesar (prej: poslano in takoj ustavljeno).
            if (p == null || !zaZvocnik(sk)) { konec(IllegalStateException("ni kaj poslati na zvocnik")); return }
            val tukaj = trenutna()?.id == sk.id
            val trajanje = if (tukaj && !sk.radio) p.duration.takeIf { it > 0 } ?: 0L else 0L
            val od = if (tukaj) ZvocnikPravila.odPolozaja(p.currentPosition, trajanje, sk.radio) else 0L
            primerek?.zagonSamodejen = false
            Zvocniki.predvajaj(ctx, z, sk, od, trajanje) { napaka ->
                if (napaka == null) {
                    if (predvajalnik == null || trenutna()?.id != sk.id) {
                        // Medtem je uporabnik izbral kaj drugega (ali ustavil predvajanje): zvocnik ne sme igrati stare skladbe.
                        Zvocniki.ustavi {}
                    } else predvajalnik?.let { it.pause(); if (it is ExoPlayer) it.stop() }
                }
                primerek?.osvezi()
                konec(napaka)
            }
        }

        /** »Predvajaj tukaj«: zvocnik se ustavi, ta naprava nadaljuje na istem mestu. */
        fun vrniNaNapravo() {
            if (!naZvocniku()) return
            val polozaj = Zvocniki.polozaj()
            val sk = trenutna()
            val vZivo = sk?.radio == true
            // Ta naprava zacne sele, ko zvocnik potrdi Stop (ali po kratkem roku) - prej sta ob pocasnem zvocniku hip igrala oba,
            // ob nedosegljivem pa oba brez konca in brez sporocila.
            Zvocniki.ustavi {
                val s = primerek
                if (trenutna()?.id == sk?.id) { if (s != null) s.naTejNapravi(if (vZivo) null else polozaj) else predvajalnik?.play() }
            }
        }

        /**
         * Ali skladbo zvocnik lahko predvaja: zvocni tok brez posebnih glav zahteve (te pozna samo nas predvajalnik - tudi
         * glave po domeni, npr. kljuc vira API: zvocnik bi tok dobil brez njih), ne skladba, ki jo je treba se poiskati, od
         * posnetkov s strani pa samo YouTube (zvocni tok neposredno od YouTuba, lastnik 25. 10. 2026), ki ni film ali serija
         * ([zvokIzVidea]) - razen ce ze vemo, da toka ni (DRM, brez naslova).
         */
        fun zaZvocnik(sk: Jamendo.Skladba): Boolean {
            if (YoutubeZvok.jeYoutube(sk)) return !YoutubeZvok.znanoNe(sk) && (!sk.video || zvokIzVidea(sk))
            return !UvozSeznama.jeIskana(sk) && !SpletniVir.jeEnota(sk) &&
                ZvocnikPravila.primerna(sk.zvok, sk.video, sk.mime, SpletniVir.imaGlave(sk.zvok))
        }

        /**
         * Posnetek s sliko, ki sme na zvocnik kot zvok: YouTube, ki ni film ali serija (videospot, koncert - glasba z YouTuba
         * je pogosto videospot; lastnik 25. 10. 2026: »Predvajaj na zvocniku« za skladbe z YouTuba). Film na zvocnik ne gre
         * (usklajenost slike in zvoka je posebna naloga).
         */
        fun zvokIzVidea(sk: Jamendo.Skladba): Boolean = YoutubeZvok.jeYoutube(sk) &&
            SpletniVir.vrstaVsebine(sk).let { it != SpletniVir.FILM && it != SpletniVir.SERIJA }

        /**
         * Gumb »Predvajaj na zvocniku«: pri YouTubu samo, ko je tok ze razresen - posnetek z DRM ali brez naslova gumba nima
         * (lastnik: kar ne moremo predvajati, ne ponujamo). Nerazresen posnetek se razresi v ozadju, [osvezi] nato gumb pokaze.
         */
        fun zaZvocnikPonudba(ctx: Context, sk: Jamendo.Skladba, osvezi: () -> Unit): Boolean {
            if (!zaZvocnik(sk)) return false
            if (!YoutubeZvok.jeYoutube(sk) || YoutubeZvok.znanTok(sk) != null) return true
            YoutubeZvok.pripravi(ctx, sk, osvezi)
            return false
        }

        fun naslednja() {
            if (naDrugiNapravi()) { OddaljenoPredvajanje.naslednja(); return }
            naslednjaTu()
        }

        fun naslednjaTu() {
            val s = primerek
            if (s != null && spletnaVrsta.size > spletniIndeks + 1) s.zacniElement(spletniIndeks + 1)
            // Na zvocniku: med premorom se naslednja samo nalozi (kot na tej napravi ostane premor).
            else if (predvajalnik?.hasNextMediaItem() == true) predvajalnik?.let { it.seekToNextMediaItem(); s?.poPremikuVVrsti(false, !naZvocniku() || Zvocniki.stanje.zeliIgrati) }
            // Zadnja skladba z YouTuba: Naprej nadaljuje s samodejnim mixom (lastnik 27. 10. 2026).
            else s?.nadaljujZMixom(koncajBrez = false)
        }

        /** Nazaj: po prvih sekundah na zacetek posnetka, sicer prejsnji (kot pri vsakem predvajalniku). */
        fun prejsnja() {
            if (naDrugiNapravi()) { OddaljenoPredvajanje.prejsnja(); return }
            prejsnjaTu()
        }

        fun prejsnjaTu() {
            val s = primerek
            val p = predvajalnik
            if (naZvocniku()) {
                val vVrsti = s != null && spletnaVrsta.isNotEmpty()
                val imaPrejsnjo = if (vVrsti) spletniIndeks > 0 else p?.hasPreviousMediaItem() == true
                if (ZvocnikPravila.nazajNaZacetek(Zvocniki.polozaj(), imaPrejsnjo)) Zvocniki.skoci(0L)
                else if (vVrsti) s.zacniElement(spletniIndeks - 1)
                else { p?.seekToPreviousMediaItem(); s?.poPremikuVVrsti(false, Zvocniki.stanje.zeliIgrati) }
                return
            }
            if (s != null && spletnaVrsta.isNotEmpty()) {
                if ((p?.currentPosition ?: 0L) > 5000 || spletniIndeks == 0) p?.seekTo(0) else s.zacniElement(spletniIndeks - 1)
            } else p?.let { if (it.currentPosition > 5000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
        }

        /** Mesto v seznamu predvajanja (1-based) in dolzina, kadar igra seznam posnetkov; sicer null. */
        fun mestoVVrsti(): Pair<Int, Int>? = if (spletnaVrsta.size > 1 && predvajalnik != null) spletniIndeks + 1 to spletnaVrsta.size else null

        /** Enoto spletne aplikacije, katere toka ne moremo ujeti, predvaja stran pod nasim upravljanjem. */
        /** Naloga 117: uporabnik predvaja tu - seja daljinca se konca, gumbi spet upravljajo to napravo. */
        private fun koncajDaljinec() {
            if (OddaljenoPredvajanje.cilj != null) android.os.Handler(android.os.Looper.getMainLooper()).post { OddaljenoPredvajanje.koncaj(OddaljenoPravila.KONEC_TU) }
        }

        fun predvajajSplet(ctx: Context, sk: Jamendo.Skladba, dovoliPrevzem: Boolean = true) {
            // Glasba z YouTuba: najprej zvok prek Media3 (vrsta, zacniEnoto), stran samo kot rezerva (YoutubeVrstaPravila).
            if (YoutubeVrstaPravila.posameznoPrekMedia3(YoutubeZvok.jeYoutube(sk), sk.video, dovoliPrevzem) && SpletniVir.jeEnota(sk)) {
                predvajajVrsto(ctx, listOf(sk), 0); return
            }
            koncajDaljinec()
            zapustiVrsto(sk.id)
            cakajocaVrsta = null
            cakajociSplet = sk to dovoliPrevzem
            stevecZagonov.incrementAndGet()
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

        /** Zaslon se prijavi, da izve za spremembe (nova skladba, pavza, konec). */
        val poslusalci = mutableSetOf<() -> Unit>()

        private val stevecZagonov = java.util.concurrent.atomic.AtomicInteger()

        /**
         * Koliko predvajanj je bilo namerno zagnanih ([predvajaj], [predvajajSplet], [predvajajVrsto]). Zaslon predvajanja
         * z njim loci »uporabnik je odsel« od »medtem se je zacelo nekaj novega«: onStop starega zaslona pride sele po
         * zagonu novega posnetka in ga ne sme ustaviti.
         */
        val zagonov: Int get() = stevecZagonov.get()

        /** Naloga 72: podnapisi iz dodatka k posnetku, ki igra (glej [dodajPodnapiseVTok]). Klic z glavne niti. */
        fun dodajPodnapise(novi: List<Podnapisi.Podnapis>): Boolean = primerek?.dodajPodnapiseVTok(novi) ?: false

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

        /**
         * TV 28. 10. 2026 (20:03 in 20:04): tok lastnega Useneta je dvakrat odpovedal, preden se je zacel (MatroskaExtractor,
         * NullPointerException), rezerv ni bilo - »koncna napaka«, ceprav je bilo 44 drugih tokov. [obOdpovedi] velja za zadnje
         * zagnano predvajanje: ce prvi tok odpove pred zacetkom in rezerv ni vec, izbira toka poskusi drugo pot (enkrat).
         */
        @Volatile var obOdpovedi: (() -> Unit)? = null

        fun predvajaj(ctx: Context, seznam: List<Jamendo.Skladba>, od: Int, streznik: DatotekeActivity.Streznik? = null) {
            if (seznam.isEmpty()) return
            koncajDaljinec()
            if (seznam.size == 1) zapustiVrsto(seznam[0].id) else { spletnaVrsta = emptyList(); zetonVrste++ }
            cakajocaVrsta = null
            rezerve = emptyList()
            obOdpovedi = null
            cakajoci = Triple(seznam, od, streznik)
            stevecZagonov.incrementAndGet()
            val namen = Intent(ctx, GlasbaStoritev::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(namen) else ctx.startService(namen)
        }

        /**
         * Prej je kartica Mediji na zacetnem zaslonu med predvajanjem pokazala "zdaj se predvaja".
         * Ta kartica je zdaj samo se postavka v stranski vrstici (brez dinamicne vsebine, kot vse
         * druge postavke), zato tale funkcija nima vec svojih pogledov - klici vanjo ostajajo varni.
         */
        fun osveziKartico(a: android.app.Activity) { }

        /**
         * Klik na kartico »Media Center« odpre Media Center, tudi med predvajanjem (naloga 90c: po Nazaj z zaslona predvajanja
         * je prvi klik odprl zaslon predvajanja). Zaslon predvajanja je na voljo iz mini vrstice in plosce.
         */
        fun namenKartice(ctx: Context): Intent = Intent(ctx, GlasbaActivity::class.java)

        /** Predvajanje tece ali se nalaga po uporabnikovi zelji: ni pavze, ni napake, ni konca. */
        fun tece(): Boolean = if (naZvocniku()) Zvocniki.stanje.zeliIgrati else predvajalnik?.let {
            it.playWhenReady && it.playerError == null && (it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING)
        } == true

        /** Napaka, po kateri predvajanje ne gre naprej (ni rezervnega toka): kaj povedati uporabniku. */
        class KoncnaNapaka(val id: String, val koda: Int, val http: Int, val besedilo: String)
        @Volatile var koncnaNapaka: KoncnaNapaka? = null
            private set

        /** Casovnik izklopa: ob izteku predvajanje ustavimo (za zaspance pred televizorjem). */
        private var izklopOb = 0L
        private val ura = android.os.Handler(android.os.Looper.getMainLooper())
        private val izklopi = Runnable { izklopOb = 0L; premorTu(); Zvocniki.casovnikSpremenjen(); poslusalci.toList().forEach { it() } }

        /** Nastavi casovnik v minutah; 0 ga izklopi. */
        fun nastaviCasovnik(minut: Int) {
            ura.removeCallbacks(izklopi)
            izklopOb = if (minut > 0) System.currentTimeMillis() + minut * 60_000L else 0L
            if (minut > 0) ura.postDelayed(izklopi, minut * 60_000L)
            // Na zvocniku ta naprava sicer lahko zaspi (ne igra sama) - casovnik pa mora docakati svoj cas.
            Zvocniki.casovnikSpremenjen()
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
