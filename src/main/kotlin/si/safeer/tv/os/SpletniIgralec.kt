// Media3: SimpleBasePlayer je oznacen kot @UnstableApi.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import si.safeer.tv.ChromiumEngineView

/**
 * Nas predvajalnik za vsebino, ki jo zna predvajati le stran sama (zasciteni tokovi - YouTube,
 * Spotify ...): stran tece v skritem pogledu brskalnika Safeer (z vsemi zascitami, brez oglasov,
 * predvajanje v ozadju), mi pa jo upravljamo kot vsak drug predvajalnik - kartica Domov,
 * obvestilo, tipke daljinca, zaslon predvajanja. Video pokazemo tako, da pogled pripnemo na
 * zaslon predvajanja; ko ga zapustis, zvok igra naprej.
 *
 * Predvajalnik strani je pogosto v okvirju (iframe) drugega izvora, kamor glavna stran ne seze:
 * zato v vsak okvir ob zacetku vstavimo posrednika ([POSREDNIK_JS]) - ukazi gredo navzdol,
 * stanje navzgor, s standardnim postMessage.
 */
class SpletniIgralec(ctx: Context, private val sk: Jamendo.Skladba) : SimpleBasePlayer(Looper.getMainLooper()) {

    val pogled: ChromiumEngineView = ChromiumEngineView(ctx).apply {
        settings.mediaPlaybackRequiresUserGesture = false
        isFocusable = false
        val d = resources.displayMetrics
        layout(0, 0, d.widthPixels, d.heightPixels)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(this, POSREDNIK_JS, setOf("*"))
        } catch (_: Exception) { }
        onPageLoaded = { _, _ ->
            evaluateJavascript(POSREDNIK_JS, null)
            evaluateJavascript(SpletniVir.SOGLASJE_JS, null)
            ukaz("play"); ura.postDelayed({ ukaz("play") }, 3_000); ura.postDelayed({ if (igra && !pripravljeno) ukaz("play") }, 7_000)
            if (kino) evaluateJavascript(KINO_JS, null)
        }
        loadUrl(sk.povezava)
    }

    private val ura = Handler(Looper.getMainLooper())
    /** Video je na zaslonu predvajanja: stran kaze samo video (slog ponovimo, ker ga nova stran pobrise). */
    private var kino = false
    private var igra = true
    /** Kaj zeli uporabnik (tipke, kartica, obvestilo); stran, ki se ustavi sama (odpet pogled), spet zazenemo. */
    private var zelja = true
    private var zadnjiZagon = 0L
    private var pripravljeno = false
    private var polozaj = 0L
    private var trajanje = C.TIME_UNSET
    private var koncano = 0
    private var naslov = sk.naslov
    private var izvajalec = sk.izvajalec

    private fun ukaz(c: String, t: Double = 0.0) =
        pogled.evaluateJavascript("(function(){try{window.postMessage({safeer:'ukaz',c:'$c',t:$t},'*');}catch(e){}})()", null)

    private val tik = object : Runnable {
        override fun run() {
            if (kino) pogled.evaluateJavascript(KINO_JS, null)
            // Casovniki JS so skupni vsem pogledom procesa; ce jih je brskalnik ustavil, stran ne tece.
            if (zelja) pogled.resumeTimers()
            pogled.evaluateJavascript(STANJE_JS) { r ->
                val o = try { JSONObject(r ?: "{}") } catch (_: Exception) { JSONObject() }
                if (o.has("t")) {
                    pripravljeno = o.optInt("r") >= 2
                    polozaj = (o.optDouble("t", 0.0) * 1000).toLong()
                    trajanje = o.optDouble("d", 0.0).takeIf { it > 0 && it.isFinite() }?.let { (it * 1000).toLong() } ?: C.TIME_UNSET
                    // Stran ob koncu pogosto sama zacne naslednje (YouTube): konec javimo sele, ko obstane.
                    koncano = if (o.optBoolean("e")) koncano + 1 else 0
                    o.optString("n").takeIf { it.isNotBlank() }?.let { naslov = it }
                    o.optString("a").takeIf { it.isNotBlank() }?.let { izvajalec = it }
                    // Stran se je ustavila ali zagnala sama (npr. oglas je koncan): sledimo ji.
                    if (!o.optBoolean("e")) igra = o.optBoolean("p")
                    val zdaj = System.currentTimeMillis()
                    if (zelja && !igra && !o.optBoolean("e") && zdaj - zadnjiZagon > 2_500) { zadnjiZagon = zdaj; ukaz("play") }
                }
                invalidateState()
                ura.postDelayed(this, 1_000)
            }
        }
    }

    init { ura.postDelayed(tik, 1_500) }

    override fun getState(): State {
        val metapodatki = MediaMetadata.Builder().setTitle(naslov).setArtist(izvajalec).build()
        val postavka = MediaItemData.Builder(sk.id)
            .setMediaItem(MediaItem.Builder().setMediaId(sk.id).setMediaMetadata(metapodatki).build())
            .setMediaMetadata(metapodatki)
            .setDurationUs(if (trajanje == C.TIME_UNSET) C.TIME_UNSET else trajanje * 1000)
            .setIsSeekable(trajanje != C.TIME_UNSET)
            .build()
        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_RELEASE).build())
            .setPlaylist(listOf(postavka))
            .setPlayWhenReady(igra, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when { koncano >= 6 -> Player.STATE_ENDED; pripravljeno -> Player.STATE_READY; else -> Player.STATE_BUFFERING })
            .setContentPositionMs(PositionSupplier.getExtrapolating(polozaj, if (igra && pripravljeno) 1f else 0f))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        igra = playWhenReady; zelja = playWhenReady
        ukaz(if (playWhenReady) "play" else "pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        polozaj = positionMs.coerceAtLeast(0)
        ukaz("seek", polozaj / 1000.0)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        igra = false; zelja = false
        ukaz("pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        zelja = false
        skrij()
        ura.removeCallbacksAndMessages(null)
        try { pogled.stopLoading(); pogled.loadUrl("about:blank"); pogled.destroy() } catch (_: Exception) { }
        return Futures.immediateVoidFuture()
    }

    /** Video na zaslon predvajanja: pogled gre nad povrsino (ki je pri tem predvajalniku prazna). */
    fun pokazi(nad: View) {
        val stars = nad.parent as? ViewGroup ?: return
        if (pogled.parent === stars) return
        kino = true
        (pogled.parent as? ViewGroup)?.removeView(pogled)
        stars.addView(pogled, stars.indexOfChild(nad) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kino = true
        pogled.evaluateJavascript(KINO_JS, null)
    }

    /** Zaslon predvajanja je zaprt: pogled odpnemo, zvok igra naprej. */
    fun skrij() {
        kino = false
        (pogled.parent as? ViewGroup)?.removeView(pogled)
        zadnja?.get()?.let { gostuj(it) }
        if (zelja) ura.postDelayed({ ukaz("play") }, 400)
    }

    /**
     * Odpet pogled Android ustavi. Zato stran, ko ni na zaslonu predvajanja, ostane pripeta na zaslon
     * Safeer, ki je v ospredju (1 tocka, pod vsebino) - kot brskalnik, ki igra v ozadju.
     */
    fun gostuj(a: android.app.Activity) {
        if (kino || a.isFinishing) return
        val koren = a.window.decorView as? ViewGroup ?: return
        if (pogled.parent === koren) return
        (pogled.parent as? ViewGroup)?.removeView(pogled)
        koren.addView(pogled, 0, android.widget.FrameLayout.LayoutParams(1, 1))
    }

    /** Zaslon, na katerem je bila stran pripeta, se zapira. */
    fun izgubi(a: android.app.Activity) {
        if (!kino && pogled.parent === a.window.decorView) (pogled.parent as ViewGroup).removeView(pogled)
    }

    companion object {
        /** Zadnji zaslon Safeer v ospredju (nastavi ga [GlasbaStoritev]). */
        @Volatile var zadnja: java.lang.ref.WeakReference<android.app.Activity>? = null

        /**
         * Posrednik v vsakem okviru strani. Ukaz {safeer:'ukaz'} izvede na svojem glavnem mediju (ce
         * ga nima: najvecji gumb »play«) in ga poda svojim okvirjem - nasa pavza drzi _safeer_app_bg, da je
         * zascita predvajanja v ozadju ne zazene znova, »play« jo sprosti; okvir z medijem vsako sekundo
         * sporoci stanje vrhnji strani ({safeer:'stanje'}).
         */
        private const val POSREDNIK_JS = """(function(){if(window.__safeerAgent)return;window.__safeerAgent=1;
          function glavni(){var m=[].slice.call(document.querySelectorAll('video,audio'));
            return m.filter(function(x){return !x.paused;})[0]||m.sort(function(a,b){return (b.clientWidth*b.clientHeight)-(a.clientWidth*a.clientHeight);})[0];}
          function gumb(){var g=[].slice.call(document.querySelectorAll('button,[role=button]')).filter(function(b){
            var n=(b.getAttribute('aria-label')||b.getAttribute('title')||b.className||'')+'';var r=b.getBoundingClientRect();
            return /\bplay\b|predvajaj/i.test(n)&&r.width>0&&r.height>0;});
            g.sort(function(x,y){var a=x.getBoundingClientRect(),b=y.getBoundingClientRect();return b.width*b.height-a.width*a.height;});
            if(g[0])g[0].click();}
          window.addEventListener('message',function(e){var d=e.data;if(!d||d.safeer!=='ukaz')return;var m=glavni();
            if(d.c==='play'){window._safeer_app_bg=false;if(m){if(m.paused){try{m.play();}catch(x){}}}else if(document.querySelector('button,[role=button]'))gumb();}
            else if(d.c==='pause'&&m){window._safeer_app_bg=true;try{m.pause();}catch(x){}}
            else if(d.c==='seek'&&m){try{m.currentTime=d.t;}catch(x){}}
            for(var i=0;i<window.frames.length;i++){try{window.frames[i].postMessage(d,'*');}catch(x){}}});
          if(window.top!==window){setInterval(function(){var m=glavni();if(m){try{window.top.postMessage({safeer:'stanje',
            p:!m.paused,t:m.currentTime||0,d:m.duration||0,r:m.readyState,e:!!m.ended},'*');}catch(x){}}},1000);}
          else{window.addEventListener('message',function(e){var d=e.data;if(d&&d.safeer==='stanje'){window.__safeerOkvir=d;window.__safeerOkvirCas=Date.now();}});}
        })()"""

        /** Stanje: medij vrhnje strani, sicer zadnje stanje okvirja z medijem; naslov iz MediaSession strani. */
        private const val STANJE_JS = """(function(){var m=[].slice.call(document.querySelectorAll('video,audio'));
          m=m.filter(function(x){return !x.paused;})[0]||m.sort(function(a,b){return (b.clientWidth*b.clientHeight)-(a.clientWidth*a.clientHeight);})[0];
          var md=navigator.mediaSession&&navigator.mediaSession.metadata;var n=(md&&md.title)||'',a=(md&&md.artist)||'';
          if(m)return {p:!m.paused,t:m.currentTime||0,d:m.duration||0,r:m.readyState,e:!!m.ended,n:n,a:a};
          var o=window.__safeerOkvir;if(o&&Date.now()-window.__safeerOkvirCas<3000)return {p:o.p,t:o.t,d:o.d,r:o.r,e:o.e,n:n,a:a};
          return {};})()"""

        /**
         * Samo video strani cez ves pogled, ostala stran nevidna (splosen slog, za vsako stran). Cilj je
         * video vrhnje strani, sicer najvecji okvir (predvajalnik v iframu).
         */
        private const val KINO_JS = """(function(){var s=document.getElementById('safeer-kino');if(!s){s=document.createElement('style');s.id='safeer-kino';
          s.textContent='html,body{background:#000!important;overflow:hidden!important}body *{visibility:hidden!important}'+
          '[data-safeer-kino]{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;'+
          'max-width:none!important;max-height:none!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important;transform:none!important;border:0!important}';
          (document.head||document.documentElement).appendChild(s);}
          var c=document.querySelector('video');if(!c){var f=[].slice.call(document.querySelectorAll('iframe'));
            f.sort(function(a,b){return (b.clientWidth*b.clientHeight)-(a.clientWidth*a.clientHeight);});c=f[0];}
          var st=document.querySelector('[data-safeer-kino]');if(st&&st!==c)st.removeAttribute('data-safeer-kino');
          if(c)c.setAttribute('data-safeer-kino','1');})()"""
    }
}
