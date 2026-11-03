package si.safeer.tv

import android.view.KeyEvent
import org.json.JSONObject

enum class PlaybackMode {
    InPlaceWebView,
    CustomView,
    ExoPlayer
}

object TvSite {
    fun isYoutubeTv(url: String): Boolean {
        return url.contains("youtube.com/tv", ignoreCase = true)
    }

    fun isWatchPage(url: String): Boolean {
        val u = url.lowercase()
        if (isYoutubeTv(u)) return false
        return u.contains("/watch") || u.contains("/shorts/") || u.contains("youtube.com/embed")
    }

    fun isBrowserHome(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("android_asset/brave_home") || u.contains("android_asset/splet/splet.html")
    }

    /** Stran gledalca deljenega zaslona (Safeer Link): tuj zaslon gledamo cez cel televizor. */
    fun isSharedScreen(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/cast/screen/") && u.contains("/view")
    }

    fun hideChrome(url: String): Boolean {
        return isSharedScreen(url)
    }
}

interface SiteProfile {
    fun matches(url: String): Boolean
    fun hideChrome(url: String): Boolean = false
    fun playbackMode(): PlaybackMode = PlaybackMode.CustomView
    fun consumeActionUp(keyCode: Int): Boolean = false
    fun handleSearch(query: String, host: MainActivity): Boolean = false
    fun handleSearchKey(host: MainActivity): Boolean = false
    fun handleKey(event: KeyEvent, host: MainActivity): Boolean
    fun handleBack(host: MainActivity): Boolean
}

object SiteProfileResolver {
    private val profiles = listOf(
        YoutubeTvSiteProfile,
        GenericWebSiteProfile
    )

    fun fromUrl(url: String): SiteProfile {
        return profiles.first { it.matches(url) }
    }
}

/**
 * OK na Googlu: fokus na povezavo v fokusiranem elementu (ovoj zadetka je DIV), nato pravi Enter.
 * Pravi Enter odpre povezavo kot uporabnik (tudi prek preusmeritve google.com/goto).
 * Lastnik 2. 11. 2026 (prijava na TV): Enter potrditvenega polja »Prikazi geslo« ne preklopi, gumbe obrazca prijave
 * (»Ste pozabili geslo?«) pa je ujel obrazec. Kontrole, ki niso povezava (polje, gumb, potrditveno polje), zato dobijo
 * en pravi dotik na sredini, ce je tam res one ([GoogleTipke.ok]); polje z dotikom odpre tipkovnico. Ce dotik ne
 * doseze potrditvenega polja (skrit vhod pod narisanim okvirjem), ga preklopi Presledek.
 */
private const val KLIK_FOKUSA = """(function(){var e=document.activeElement,v=window.__gnavPolje;window.__gnavPolje=null;if((!e||e===document.body||e===document.documentElement)&&v&&v.isConnected)e=v;if(!e||e===document.body||e===document.documentElement||e.tagName==='IFRAME')return 'enter'; var kontrola='input,textarea,select,button,[role="checkbox"],[role="switch"],[role="radio"],[role="button"],[contenteditable="true"]'; var a=e.matches('a[href]')?e:(e.matches(kontrola)?null:e.querySelector('a[href]')); if(a){try{a.focus({preventScroll:true});}catch(_){} return 'enter';} var pr=e.matches('input[type="checkbox"],input[type="radio"],[role="checkbox"],[role="switch"],[role="radio"]'); var r=e.getBoundingClientRect(); if(r.width>0&&r.height>0&&(r.top<0||r.bottom>innerHeight||r.left<0||r.right>innerWidth)){try{e.scrollIntoView({block:'center',inline:'nearest'});}catch(_){} r=e.getBoundingClientRect();} if(r.width<=0||r.height<=0)return pr?'space':'enter'; var x=r.left+r.width/2,y=r.top+r.height/2,p=document.elementFromPoint(x,y),lab=p&&p.closest?p.closest('label'):null; if(p&&(p===e||e.contains(p)||(lab&&lab.control===e)))return 'dotik:'+Math.round(x)+','+Math.round(y); return pr?'space':'enter';})()"""

/**
 * Smerne tipke na Googlovih zadetkih (22. 9. 2026, video lastnika): Tab je hodil po vsakem zavihku, meniju
 * zadetka (tri pike) in "Prevedi to stran"; GOR je takoj skocil v naslovno vrstico. Zdaj cilj izberemo po
 * legi na zaslonu (naslednja vrstica v smeri tipke, najblizji rob, poravnava levo; ovoji brez povezave in
 * male ikone brez besedila ne stejejo); Google okvir
 * fokusa pokaze le pri tipkovnici ([smerNaGooglu]), pri zadetkih pa v temnem videzu sploh ne (TV, video
 * Lastnika 30. 9.: fokus je bil nastavljen, videti ni bilo nicesar; inline outline na zadetku pa odreze
 * overflow ovoja) - zato cilj oznacimo sami: en fiksen okvir nad stranjo (#__gnavOkvir, ista modra kot
 * tv_spatial.js), ki sledi drsenju in izgine ob kliku ali robu. V stran ne vbrizgamo nicesar drugega.
 * Lastnik 2. 11. 2026 (dnevnik SafeerGoogleNav na TV): do »Prijava« v glavi in do gumbov Googlovega okna
 * »Preveri nastavitve« (role=dialog) daljinec ni prisel - vse nad dnom iskalnega polja je bilo izloceno, odprto
 * okno pa ni bilo loceno od strani (njegov obrazec je celo premaknil dno polja pod gumbe okna). Zdaj ima stran tri
 * pasove: glava (nad dnom polja; GOR iz polja, LEVO/DESNO po njej, DOL nazaj v polje), telo (zadetki, kot prej) in
 * odprto modalno okno, ki ujame vse smeri in ga na robu ne zapustimo. Ciste odlocitve so med oznakama GNAV_PRAVILA
 * (tests/google_smer_test.js).
 * Meritev na TV 2. 11. 2026 (mobilna postavitev): fokus v iskalnem polju takoj odpre Googlovo prekrivko s predlogi in
 * tipkovnico, ki nato vzame vse tipke. Polje zato ni postaja na poti GOR: z vrha zadetkov (zavihki) gre GOR naravnost v
 * glavo, kjer je polje le en element v vrsti (LEVO/DESNO po bralnem vrstnem redu). Nanj postavimo samo nas okvir
 * (window.__gnavPolje, brez fokusa); odpre ga sele OK ([KLIK_FOKUSA]). DOL iz glave gre na zavihke.
 * Vrne 'ok' (fokus je na cilju), 'ok - glava polje' (okvir na polju, brez fokusa), 'rob' (v tej smeri ni nicesar),
 * 'okno' (rob odprtega okna: ostanemo) ali 'tuje' (okno v tujem okvirju cez sredino zaslona: tipko dobi stran sama).
 */
private const val GOOGLE_SMER = """((function(smer){ var W=innerWidth,H=innerHeight;
/* GNAV_PRAVILA_ZACETEK - ciste odlocitve brez DOM (tests/google_smer_test.js) */
function gnavPas(r,dno){return dno>0&&r.bottom<=dno+1?'glava':'telo';}
function gnavNacrt(smer,od,imaOkno){ if(imaOkno)return {pas:'okno',rob:'okno',prvi:od!=='okno'}; if(od==='glava'){if(smer==='DOWN')return {pas:'telo',rob:'rob',crta:true}; return {pas:'glava',rob:'rob',vrsta:smer!=='UP'};} if(od==='telo')return {pas:'telo',rob:smer==='UP'?'glava':'rob'}; return smer==='UP'?{pas:'glava',rob:'rob',crta:true,blizji:true}:{pas:'telo',rob:'rob',crta:true}; }
function gnavNajblizji(smer,cur,L,blizji){ var vert=(smer==='UP'||smer==='DOWN'),naj=-1,ns=1e9,tol=Math.min(12,cur.height/2); for(var k=0;k<L.length;k++){var q=L[k].r,gap,ov; if(smer==='DOWN'){if(q.top<cur.bottom-tol)continue;gap=q.top-cur.bottom;} else if(smer==='UP'){if(q.bottom>cur.top+tol)continue;gap=cur.top-q.bottom;} else if(smer==='RIGHT'){if(q.left<cur.right-8)continue;gap=q.left-cur.right;} else{if(q.right>cur.left+8)continue;gap=cur.left-q.right;} if(gap<0)gap=0; ov=vert?Math.min(q.right,cur.right)-Math.max(q.left,cur.left):Math.min(q.bottom,cur.bottom)-Math.max(q.top,cur.top); var s=gap+(ov>0?0:-ov)*2+(vert?Math.abs(q.left-cur.left)*0.3:0); if(L[k].ikona)s+=120; if(s<ns){ns=s;naj=k;}} if(naj<0&&blizji){var cx=cur.left+cur.width/2,cy=cur.top+cur.height/2; for(var j=0;j<L.length;j++){var p=L[j].r,d=Math.abs(p.left+p.width/2-cx)+Math.abs(p.top+p.height/2-cy)+(L[j].ikona?120:0);if(d<ns){ns=d;naj=j;}}} return naj; }
function gnavTuje(f){return !!f.naVrhu&&f.w>=120&&f.h>=120&&f.w*f.h>=f.W*f.H*0.08&&(f.pritrjen||f.nadStranjo||f.vDialogu);}
function gnavPrvi(L){var naj=-1;for(var k=0;k<L.length;k++){var a=L[k].r,b=naj<0?null:L[naj].r;if(!b||a.top<b.top-8||(Math.abs(a.top-b.top)<=8&&a.left<b.left))naj=k;}return naj;}
function gnavVrsta(smer,cur,L){ var vsi=L.map(function(a,i){return {r:a.r,i:i};}); vsi.push({r:cur,i:-1}); vsi.sort(function(a,b){var ya=a.r.top+a.r.height/2,yb=b.r.top+b.r.height/2; return Math.abs(ya-yb)>16?ya-yb:a.r.left-b.r.left;}); var t=-1; for(var k=0;k<vsi.length;k++)if(vsi[k].i===-1)t=k; var n=smer==='RIGHT'?t+1:t-1; return n>=0&&n<vsi.length?vsi[n].i:-1; }
/* GNAV_PRAVILA_KONEC */
var forms=[].slice.call(document.querySelectorAll('form[role="search"],form[action="/search"]')); function vObrazcu(x){for(var i=0;i<forms.length;i++)if(forms[i].contains(x))return true;return false;}
var polje=document.querySelector('form[role="search"] textarea,form[action="/search"] textarea,textarea[name="q"],input[name="q"]'); if(polje&&polje.getBoundingClientRect().height<=0)polje=null;
var sel='a[href],button,input:not([type="hidden"]),select,textarea,[role="button"],[role="link"],[role="tab"],[tabindex]:not([tabindex="-1"])';
function najdiOkno(){var d=[].slice.call(document.querySelectorAll('[aria-modal="true"],[role="dialog"],[role="alertdialog"],dialog[open]')),naj=null; for(var i=0;i<d.length;i++){var x=d[i],r=x.getBoundingClientRect();if(r.width<40||r.height<40)continue; var cs=getComputedStyle(x);if(cs.display==='none'||cs.visibility==='hidden'||+cs.opacity===0)continue; if(x.closest('[aria-hidden="true"],[inert]')||!x.querySelector(sel))continue; var px=Math.min(Math.max(r.left+r.width/2,1),W-1),py=Math.min(Math.max(r.top+r.height/2,1),H-1),p=document.elementFromPoint(px,py); if(p&&(p===x||x.contains(p)))naj=x;} return naj;}
function tujeOkno(){var fr=[].slice.call(document.querySelectorAll('iframe')),naj=null; for(var i=0;i<fr.length;i++){var x=fr[i],r=x.getBoundingClientRect(); if(r.width<=0||r.height<=0)continue; var cs=getComputedStyle(x); if(cs.display==='none'||cs.visibility==='hidden'||+cs.opacity===0)continue; var px=Math.min(Math.max(r.left+r.width/2,1),W-1),py=Math.min(Math.max(r.top+r.height/2,1),H-1),p=document.elementFromPoint(px,py),pr=false,nad=false; for(var f=x;f&&f!==document.body;f=f.parentElement){var fs=getComputedStyle(f);if(fs.position==='fixed')pr=true;if(fs.position==='absolute'&&parseInt(fs.zIndex,10)>0)nad=true;} var dl=!!x.closest('[role="dialog"],[role="menu"],[aria-modal="true"],dialog'); if(gnavTuje({naVrhu:p===x,w:r.width,h:r.height,W:W,H:H,pritrjen:pr,nadStranjo:nad,vDialogu:dl}))naj=x;} return naj;}
var okno=najdiOkno(),tuj=okno?null:tujeOkno(); if(smer==='TUJE')return tuj?'tuje':'ne';
if(tuj){window.__gnavOznacen=null;window.__gnavPolje=null;window.__gnavPos&&window.__gnavPos(); if(document.activeElement!==tuj){try{tuj.focus();}catch(_){} return 'tuje+';} return 'tuje';}
var dno=0; [].slice.call(document.querySelectorAll('input:not([type="hidden"]),textarea,[role="combobox"],[role="search"],form')).forEach(function(z){if(okno&&okno.contains(z))return;var f=z.getBoundingClientRect();if(f.height>0&&f.width>W*0.3&&f.bottom>0&&f.top<H*0.4&&f.bottom<H*0.6&&f.bottom>dno)dno=f.bottom;});
var e=window.__gnavOd||document.activeElement,od=null,cur=null; window.__gnavOd=null; if(e===document.body||e===document.documentElement)e=null;
if(!e&&window.__gnavPolje&&window.__gnavPolje===polje)e=polje;
if(e){var cr=e.getBoundingClientRect(),ima=cr.width>0&&cr.height>0; if(okno&&okno.contains(e))od='okno'; else if(!ima)od=null; else if(e===polje||vObrazcu(e))od='glava'; else od=gnavPas(cr,dno); if(od)cur=cr; else e=null;}
var nacrt=gnavNacrt(smer,od,!!okno); var crta={left:0,right:W,top:dno-1,bottom:dno,width:W,height:1}; if(!cur)cur=crta;
function vDrsniku(x){for(var p=x.parentElement;p&&p!==document.body;p=p.parentElement){var o=getComputedStyle(p).overflowX;if((o==='auto'||o==='scroll')&&p.scrollWidth>p.clientWidth+4)return true;}return false;}
var vsi=[].slice.call((okno||document).querySelectorAll(sel)),L=[];
for(var i=0;i<vsi.length;i++){var x=vsi[i]; if(!okno&&x!==polje&&(vObrazcu(x)||x.matches('input,textarea,select')))continue; if(x.closest('[aria-hidden="true"],[inert]'))continue; var r=x.getBoundingClientRect();if(r.width<8||r.height<8)continue; if(r.bottom<-H*1.5||r.top>H*2.5||r.right<-W||r.left>W*2)continue; var cs=getComputedStyle(x);if(cs.visibility==='hidden'||cs.display==='none'||+cs.opacity===0)continue; var cy=r.top+r.height/2; if(r.right<=1||r.left>=W-1){if(!vDrsniku(x))continue;} else if(cy>2&&cy<H-2){var vidna=false,tocke=[r.left+Math.min(12,r.width/2),r.left+r.width*0.25,r.left+r.width/2]; for(var t=0;t<tocke.length&&!vidna;t++){var px=Math.min(Math.max(tocke[t],1),W-1),p=document.elementFromPoint(px,cy);vidna=!!p&&(p===x||x.contains(p)||p.contains(x));} if(!vidna)continue;} L.push({x:x,r:r,pas:okno?'okno':(x===polje?'glava':gnavPas(r,dno))});}
L=L.filter(function(a){if(e&&(a.x===e||e.contains(a.x)||a.x.contains(e)))return false; var povezava=a.x.matches('a[href],button');for(var j=0;j<L.length;j++){var b=L[j];if(b===a)continue; if(!povezava&&a.x.contains(b.x))return false; if(b.x.matches('a[href],button')&&b.x.contains(a.x))return false;} a.ikona=(a.x.innerText||'').replace(/\s+/g,'').length===0&&a.r.width<=56&&a.r.height<=56; return true;});
function vPasu(p){return L.filter(function(a){return a.pas===p;});}
var Lp=vPasu(nacrt.pas),zac=nacrt.crta?crta:cur;
var ix=nacrt.prvi?gnavPrvi(Lp):(nacrt.vrsta?gnavVrsta(smer,cur,Lp):gnavNajblizji(smer,zac,Lp,nacrt.blizji));
if(ix<0&&nacrt.rob==='glava'){Lp=vPasu('glava');ix=gnavNajblizji(smer,cur,Lp,true);}
if(ix<0)return nacrt.rob==='glava'?'rob':nacrt.rob;
var naj=Lp[ix].x,pas=Lp[ix].pas;
var rr=naj.getBoundingClientRect(); if(rr.top<0||rr.bottom>H)naj.scrollIntoView({block:rr.height>H*0.6?'start':'center',inline:'nearest'}); else if(rr.left<0||rr.right>W)naj.scrollIntoView({block:'nearest',inline:'nearest'});
if(naj===polje&&document.activeElement!==polje){try{var akt=document.activeElement;if(akt&&akt.blur)akt.blur();}catch(_){} window.__gnavPolje=polje;} else {window.__gnavPolje=null; try{naj.focus({preventScroll:true,focusVisible:true});}catch(_){naj.focus();}}
try{var o=document.getElementById('__gnavOkvir');if(!o){o=document.createElement('div');o.id='__gnavOkvir';o.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:4px solid #00e5ff;border-radius:8px;box-sizing:border-box;display:none';document.documentElement.appendChild(o);window.__gnavPos=function(){var t=window.__gnavOznacen,k=document.getElementById('__gnavOkvir');if(!k)return;if(!t||!t.isConnected){k.style.display='none';return;}var q=t.getBoundingClientRect();if(q.width===0||q.bottom<0||q.top>innerHeight){k.style.display='none';return;}k.style.display='block';k.style.left=(q.left-6)+'px';k.style.top=(q.top-6)+'px';k.style.width=(q.width+12)+'px';k.style.height=(q.height+12)+'px';};window.addEventListener('scroll',function(){window.__gnavPos();},true);window.addEventListener('resize',function(){window.__gnavPos();});document.addEventListener('click',function(){window.__gnavOznacen=null;window.__gnavPolje=null;window.__gnavPos();},true);} window.__gnavOznacen=naj;window.__gnavPos();}catch(_){}
if(naj===window.__gnavPolje)return 'ok - glava polje';
return (document.activeElement===naj?'ok':'ni')+(naj.matches(':focus-visible')?' fv ':' - ')+pas+' '+((naj.innerText||naj.getAttribute('aria-label')||'')+'').replace(/\s+/g,' ').slice(0,25); }))"""


/** Smer na Googlovih zadetkih; [naRobu] se poklice, ce v tej smeri ni nicesar (GOR: naslovna vrstica). */
internal fun smerNaGooglu(wv: android.webkit.WebView, smer: String, event: KeyEvent, naRobu: () -> Unit) {
    // TV 2. 11. 2026: po odprtju plosce racuna ni bilo nobene vrstice - vstop zabelezimo, da se vidi, ali tipka sploh pride sem.
    android.util.Log.d("SafeerGoogleNav", "$smer vstop")
    // Google okvir fokusa vklopi sele, ko vidi tipko Tab (ne programski fokus ne druge tipke - preizkuseno
    // na TV 22. 9.). Zato na strani enkrat posljemo pravi Tab; cilj pa racunamo od elementa, ki je bil
    // izbran PRED njim (__gnavOd), ne od tistega, kamor je Tab skocil.
    wv.evaluateJavascript("(function(){var t=!window.__gnavTab;window.__gnavTab=1;window.__gnavOd=document.activeElement;return t?'1':'0';})()") { prvic ->
        if ((prvic ?: "").contains("1")) nativnaTipka(wv, event)
        wv.evaluateJavascript("$GOOGLE_SMER('$smer')") { odgovor ->
            android.util.Log.d("SafeerGoogleNav", "$smer -> $odgovor")
            val o = (odgovor ?: "").trim('"')
            // Okno v tujem okvirju (iframe, npr. Googlova plosca racuna) nasa skripta ne vidi: tipko dobi okvir sam
            // (DOL/DESNO Tab, GOR/LEVO Shift+Tab). tuje+: skript je pravkar dal fokus okvirju, prvi korak je naprej.
            if (o == "tuje") nativnaTipka(wv, event)
            if (o == "tuje+") nativnaTipka(wv, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            if (o == "rob") {
                if (smer == "DOWN") wv.evaluateJavascript("window.scrollBy(0,Math.round(innerHeight*0.6))", null)
                else { wv.evaluateJavascript("window.__gnavOznacen=null;window.__gnavPos&&window.__gnavPos();", null); naRobu() }
            }
        }
    }
}

/**
 * Smerno tipko najprej ponudimo nasi navigaciji. Ce ta pove, da ni imela kam (-1), tipko
 * dobi stran sama. Tako pridemo do gumbov v oknih, ki tecejo v svojem okvirju in jih nasa
 * skripta sploh ne vidi - na primer do "Zavrni vse" v Googlovem oknu o piskotkih.
 */
private fun posljiSmer(wv: android.webkit.WebView, smer: String, event: KeyEvent, host: MainActivity? = null) {
    // Safeer OS: LEVO na levem robu strani odpre stransko vrstico. Ali je levo se kaj, pove stran sama (-2 = rob).
    val vMeni: (() -> Unit)? = if (smer == "LEFT" && host != null && host.imaOsVrstico()) ({ host.runOnUiThread { host.vOsVrstico() } }) else null
    // V Googlove strani namenoma ne vbrizgavamo nicesar, da ostanejo prijava, iskanje in
    // reCAPTCHA povsem izvirni. Tam torej nase navigacije ni in tipka gre naravnost strani -
    // sicer bi se izgubila in uporabnik ne bi mogel niti do gumbov v oknu o piskotkih.
    if (si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url)) {
        // Rezultati iskanja: vse smeri po legi na zaslonu ([smerNaGooglu]).
        if (jeGoogleIskanje(wv)) { smerNaGooglu(wv, smer, event) { vMeni?.invoke() }; return }
        nativnaTipka(wv, event)
        return
    }
    try {
        wv.evaluateJavascript("window._safeer_navigate_spatial('$smer'${if (vMeni != null) ", true" else ""});") { odgovor ->
            val ocisceno = (odgovor ?: "").trim().trim('"')
            if (ocisceno == "-2" && vMeni != null) {
                vMeni()
            } else if (ocisceno == "-1" || ocisceno == "null" || ocisceno.isEmpty()) {
                nativnaTipka(wv, event)
            }
        }
    } catch (_: Exception) {
        nativnaTipka(wv, event)
    }
}

/** En pravi dotik na tocki strani (CSS px), kot [ChromiumEngineView] triggerNativeTap: samo znotraj pogleda. */
private fun dotakniSe(wv: android.webkit.WebView, x: Float, y: Float) {
    try {
        // getScale je opuscen od API 17, a edini sinhroni vir povecave (enako kot triggerNativeTap).
        @Suppress("DEPRECATION") val scale = wv.scale
        val vx = x * scale
        val vy = y * scale
        if (!SpletVarnostPravila.dotikVPogledu(vx, vy, wv.width, wv.height)) return
        val zdaj = android.os.SystemClock.uptimeMillis()
        val dol = android.view.MotionEvent.obtain(zdaj, zdaj, android.view.MotionEvent.ACTION_DOWN, vx, vy, 0)
        val gor = android.view.MotionEvent.obtain(zdaj, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, vx, vy, 0)
        wv.dispatchTouchEvent(dol)
        wv.dispatchTouchEvent(gor)
        dol.recycle()
        gor.recycle()
    } catch (_: Exception) {}
}

private fun jeGoogleIskanje(wv: android.webkit.WebView) =
    si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url) && (wv.url ?: "").contains("/search?")

/**
 * Premakne fokus po strani sami. Smerne tipke spletna vsebina ne pozna (daljinec je iznajdba
 * televizorja), zna pa vsaka stran vrstni red s tipko Tab - in brskalnik nov fokus sam
 * pridrsa v pogled. Navzgor in levo gresta nazaj (Shift+Tab).
 */
private fun nativnaTipka(wv: android.webkit.WebView, event: KeyEvent, tipka: Int = KeyEvent.KEYCODE_TAB) {
    try {
        val nazaj = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val meta = if (nazaj && tipka == KeyEvent.KEYCODE_TAB) KeyEvent.META_SHIFT_ON else 0
        val zdaj = android.os.SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(
            KeyEvent(zdaj, zdaj, KeyEvent.ACTION_DOWN, tipka, 0, meta)
        )
        wv.dispatchKeyEvent(
            KeyEvent(zdaj, zdaj + 1, KeyEvent.ACTION_UP, tipka, 0, meta)
        )
    } catch (_: Exception) {}
}

object YoutubeTvSiteProfile : SiteProfile {
    override fun matches(url: String) = TvSite.isYoutubeTv(url)
    override fun playbackMode() = PlaybackMode.InPlaceWebView

    override fun handleSearch(query: String, host: MainActivity): Boolean {
        val wv = host.activeWebView() ?: return false
        val escaped = query.replace("\\", "\\\\").replace("'", "\\'")
        host.hideKeyboard()
        host.editUrl.clearFocus()
        wv.requestFocus()
        wv.evaluateJavascript(
            "window._safeer_yt_tv_search ? window._safeer_yt_tv_search('$escaped') : (location.hash = '#/search?q=' + encodeURIComponent('$escaped'));",
            null
        )
        return true
    }

    override fun handleSearchKey(host: MainActivity): Boolean {
        val wv = host.activeWebView()
        host.hideKeyboard()
        host.editUrl.clearFocus()
        wv?.requestFocus()
        wv?.evaluateJavascript(
            "window._safeer_yt_tv_search ? window._safeer_yt_tv_search('') : (location.hash = '#/search');",
            null
        )
        return true
    }

    /** Cas zadnjega samostojnega pritiska tipke gor (za kretnjo "dvakrat hitro gor"). */
    @Volatile
    private var zadnjiPritiskGor = 0L

    private const val DVOJNI_PRITISK_MS = 700L

    fun dispatchYoutubeTvKey(host: MainActivity, event: KeyEvent): Boolean {
        val webView = host.activeWebView() ?: return host.superDispatchKey(event)
        if (event.action == KeyEvent.ACTION_DOWN && !webView.hasFocus()) {
            host.hideKeyboard()
            host.editUrl.clearFocus()
            webView.requestFocus()
        }
        return webView.dispatchKeyEvent(event)
    }

    override fun handleKey(event: KeyEvent, host: MainActivity): Boolean {
        val keyCode = event.keyCode
        val curUrl = host.activeUrl().lowercase()
        if (host.isChromeFocused() && event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            host.hideKeyboard()
            host.editUrl.clearFocus()
            host.activeWebView()?.requestFocus()
            return true
        }
        if (host.isChromeFocused() || host.virtualPointerView.isPointerVisible ||
            host.tabSwitcherOverlay.visibility == android.view.View.VISIBLE ||
            host.findInPageBar.visibility == android.view.View.VISIBLE
        ) {
            return false
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP) zadnjiPritiskGor = 0L
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (curUrl.contains("#/watch") || curUrl.contains("/watch?v=")) {
                    host.btnBack.requestFocus()
                    true
                } else if (event.repeatCount == 0 &&
                    android.os.SystemClock.uptimeMillis() - zadnjiPritiskGor < DVOJNI_PRITISK_MS
                ) {
                    // Dvakrat hitro gor: leanback vsako smerno tipko obdela sam, zato je to
                    // edina zanesljiva pot iz strani v orodno vrstico brskalnika.
                    zadnjiPritiskGor = 0L
                    host.btnBack.requestFocus()
                    true
                } else {
                    if (event.repeatCount == 0) {
                        zadnjiPritiskGor = android.os.SystemClock.uptimeMillis()
                    }
                    dispatchYoutubeTvKey(host, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> dispatchYoutubeTvKey(host, event)
            else -> false
        }
    }

    override fun handleBack(host: MainActivity): Boolean {
        val ytWv = host.activeWebView() ?: return false
        ytWv.evaluateJavascript(
            """
            (function(){
                var h = (location.hash || '').toLowerCase();
                var domov = h === '' || h === '#' || h === '#/' || h === '#/index';
                try {
                    document.querySelectorAll('video,audio').forEach(function(m){ try { m.pause(); } catch (eP) {} });
                } catch (eV) {}
                if (domov) return 'exit';
                location.replace('https://www.youtube.com/tv');
                return 'browse';
            })();
            """.trimIndent()
        ) { result ->
            if (result != null && result.contains("exit")) {
                host.runOnUiThread {
                    ytWv.loadUrl(SpletDomaca.naslov(host))
                }
            }
        }
        return true
    }
}

object GenericWebSiteProfile : SiteProfile {
    override fun matches(url: String) = true

    /**
     * Med domacim predvajanjem skrijemo vrstico z naslovom: takrat je na zaslonu slika, ne
     * brskalnik. Prej je bila to lastnost ene nastete strani - zdaj velja povsod, kjer smo
     * ujeli pretok.
     */
    override fun hideChrome(url: String) =
        TvSite.isSharedScreen(url) || DashPrevzem.imaSejo()

    /**
     * Ce smo na tej strani ujeli pretok DASH, ga odigra ExoPlayer - to je na televizorju
     * razlika med zatikanjem in gladko sliko. Odloca pretok, ne ime strani.
     */
    override fun playbackMode() =
        if (DashPrevzem.imaSejo()) PlaybackMode.ExoPlayer else PlaybackMode.CustomView

    override fun handleKey(event: KeyEvent, host: MainActivity): Boolean {
        val wv = host.activeWebView()
        val keyCode = event.keyCode
        val watch = TvSite.isWatchPage(host.activeUrl())
        if (watch && !host.isTopBarFocused() && !host.virtualPointerView.isPointerVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val now = System.currentTimeMillis()
                    if (now - host.lastCenterClickTime < 380) {
                        wv?.evaluateJavascript("window._safeer_toggle_fullscreen();", null)
                    } else {
                        wv?.evaluateJavascript("window._safeer_toggle_play_pause();", null)
                    }
                    host.lastCenterClickTime = now
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    wv?.evaluateJavascript("window._safeer_seek(-10);", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    wv?.evaluateJavascript("window._safeer_seek(10);", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    host.editUrl.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    wv?.evaluateJavascript("window._safeer_toggle_fullscreen();", null)
                    return true
                }
            }
        }
        if (wv == null) return false
        // Vgrajeni pregledovalnik PDF (naslov pogleda je notranji, host.activeUrl() vrne javnega):
        // smerne tipke gredo strani (listanje, orodna vrstica), da fokus ne uide iz pogleda;
        // OK/Enter gre naravno v pogled (klik na izbrani gumb pregledovalnika).
        if (!host.isTopBarFocused() && PdfPregledovalnik.jePregledovalnik(wv.url)) {
            if (!wv.hasFocus()) wv.requestFocus()
            val smer = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
                KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
                KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
                else -> null
            }
            if (smer != null) {
                wv.evaluateJavascript("window.SafeerPdfTipka && window.SafeerPdfTipka('$smer');", null)
                return true
            }
            return false
        }
        if (host.isTopBarFocused()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                host.hideKeyboard()
                host.editUrl.clearFocus()
                wv.requestFocus()
                // Na Googlu nase navigacije ni: iz naslovne vrstice v prvo vrstico strani.
                if (jeGoogleIskanje(wv)) wv.evaluateJavascript("document.activeElement&&document.activeElement.blur()") { smerNaGooglu(wv, "DOWN", event) {} }
                // Druge Googlove strani (prijava): _safeer_navigate_spatial tam ne obstaja, DOL ni naredil nicesar - Tab strani.
                else if (si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url)) nativnaTipka(wv, event)
                else wv.evaluateJavascript("window._safeer_navigate_spatial('DOWN');", null)
                return true
            }
            return false
        }
        if (!wv.hasFocus()) wv.requestFocus()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                posljiSmer(wv, "DOWN", event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (jeGoogleIskanje(wv)) {
                    // GOR na Googlu: prejsnja vrstica; z vrha zadetkov v glavo strani (polje je tam en element, brez fokusa,
                    // TV 2. 11. 2026); iz glave v naslovno vrstico.
                    smerNaGooglu(wv, "UP", event) { vNaslovnoVrstico(host) }
                    return true
                }
                if (si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url)) {
                    // Prijava in druge Googlove strani: nase skripte tam ni, zato je GOR (null) vedno skocil v naslovno
                    // vrstico (lastnik 2. 11. 2026: fokusa ni bilo mogoce vrniti v polje za geslo). Zdaj Shift+Tab na
                    // prejsnji element; v naslovno vrstico sele, ko na strani ni vec fokusa ([GoogleTipke.gorVNaslov]).
                    wv.evaluateJavascript(GoogleTipke.JE_NA_VRHU) { o ->
                        if (GoogleTipke.gorVNaslov(o)) vNaslovnoVrstico(host) else nativnaTipka(wv, event)
                    }
                    return true
                }
                wv.evaluateJavascript("window._safeer_navigate_spatial('UP');") { result ->
                    if (result == "-1" || result == "null" || result == null) vNaslovnoVrstico(host)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                posljiSmer(wv, "LEFT", event, host)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                posljiSmer(wv, "RIGHT", event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Na Googlu nase skripte ni, zato OK ni naredil nicesar (zadetka ni bilo mogoce odpreti):
                // tam gre Enter strani sami.
                if (si.safeer.tv.UserScriptManager.isGoogleDomain(wv.url)) {
                    // Fokus je pogosto na ovoju zadetka (DIV): najprej na povezavo v njem, nato Enter strani;
                    // polje, gumb in potrditveno polje dobijo pravi dotik ([KLIK_FOKUSA], [GoogleTipke.ok]).
                    wv.evaluateJavascript(KLIK_FOKUSA) { o ->
                        when (val d = GoogleTipke.ok(o)) {
                            is GoogleTipke.Ok.Dotik -> dotakniSe(wv, d.x, d.y)
                            GoogleTipke.Ok.Presledek -> nativnaTipka(wv, event, KeyEvent.KEYCODE_SPACE)
                            GoogleTipke.Ok.Enter -> nativnaTipka(wv, event, KeyEvent.KEYCODE_ENTER)
                        }
                    }
                    return true
                }
                wv.evaluateJavascript("window._safeer_click_focused_card();", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                wv.evaluateJavascript("window._safeer_toggle_play_pause();", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                wv.evaluateJavascript("window._safeer_seek(-10);", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                wv.evaluateJavascript("window._safeer_seek(10);", null)
                return true
            }
            else -> return false
        }
    }

    /**
     * Fokus v naslovno vrstico. Lastnik 3. 11. 2026: v nacinu aplikacije (prijava v Claude) vrstice ni bilo, GOR je
     * fokus dal skritemu polju in uporabnik je ostal ujet na strani z uganko brez kazalca. Zdaj se vrstica pokaze
     * vedno, ko uporabnik z GOR zapusti stran.
     */
    private fun vNaslovnoVrstico(host: MainActivity) {
        host.runOnUiThread {
            host.mobileTopBar.visibility = android.view.View.VISIBLE
            host.mobileTopBar.animate().translationY(0f).setDuration(150).start()
            host.editUrl.requestFocus()
        }
    }

    /**
     * Ali ima Nazaj kam iti? Prva stran pojavnega okna je prazna lupina, zato bi obicajen
     * Nazaj uporabnika pustil na praznem zaslonu namesto da bi okno zaprl.
     */
    private fun jeSmiselnoNazaj(pogled: android.webkit.WebView): Boolean {
        if (!pogled.canGoBack()) return false
        return try {
            val seznam = pogled.copyBackForwardList()
            val prejsnji = seznam.getItemAtIndex(seznam.currentIndex - 1)?.url.orEmpty()
            prejsnji.isNotBlank() && !prejsnji.startsWith("about:")
        } catch (_: Throwable) {
            false
        }
    }

    override fun handleBack(host: MainActivity): Boolean {
        val wv = host.activeWebView()
        // Googlovo iskanje z odprtim oknom v tujem okvirju (plosca racuna): Nazaj ga zapre z Escape, stran ostane.
        // Samo, ce ima Nazaj kam (canGoBack) - takrat spodnja pot vedno vrne true, zato jo lahko pozenemo kasneje.
        if (wv != null && jeGoogleIskanje(wv) && wv.canGoBack()) {
            wv.evaluateJavascript("$GOOGLE_SMER('TUJE')") { o ->
                android.util.Log.d("SafeerGoogleNav", "BACK -> $o")
                host.runOnUiThread {
                    if (GoogleTipke.jeTuje(o)) nativnaTipka(wv, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), KeyEvent.KEYCODE_ESCAPE)
                    else nazajPoStrani(host)
                }
            }
            return true
        }
        return nazajPoStrani(host)
    }

    private fun nazajPoStrani(host: MainActivity): Boolean {
        val tab = host.tabManager.getActiveTab()
        // Pojavno okno (prijava): Nazaj ga zapre in nas vrne na stran, ki ga je odprla.
        if (tab != null && tab.jePojavni && tab.webView?.let { !jeSmiselnoNazaj(it) } == true) {
            host.tabManager.zapriPojavni(host, tab)
            return true
        }
        if (TvSite.isWatchPage(host.activeUrl())) {
            if (tab?.webView?.canGoBack() == true) {
                tab.webView?.goBack()
                return true
            }
            tab?.webView?.loadUrl(SpletDomaca.naslov(host))
            return true
        }
        if (tab?.webView?.canGoBack() == true) {
            tab.webView?.goBack()
            return true
        }
        if (host.tabManager.count > 1 && tab != null) {
            host.tabManager.closeTab(host, tab.id)
            return true
        }
        return false
    }
}
