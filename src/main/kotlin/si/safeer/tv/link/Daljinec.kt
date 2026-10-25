package si.safeer.tv.link

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ukazi Safeer Controla po Safeer Linku (sporocilo `control.command`).
 *
 * Seznanjena naprava (racunalnik s Safeer Controlom, telefon) sme tej napravi narocati samo
 * to, kar aplikacija sme narediti sama: tipke in drsenje v brskalniku, glasnost, odpiranje
 * strani, zagon aplikacij, ponovni zagon in ciscenje lastnega predpomnilnika. Nic od tega
 * ne potrebuje Shizukuja ali ADB - zato tudi ni skrite moci, ki bi jo lahko kdo zlorabil.
 *
 * Kar potrebuje odprt brskalnik (tipke, drsenje, posnetek), izvede dejavnost v ospredju
 * prek [VOspredju]; ce brskalnika ni na zaslonu, ukaz vrne razumljivo napako (razen
 * `home` in `open_url`, ki brskalnik pripeljeta v ospredje).
 *
 * Ista datoteka je v brskalniku za telefon (com.safeer.mobile.browser.link); spremembe
 * gredo v obe.
 */
object Daljinec {
    private const val TAG = "SafeerDaljinec"

    /** Zmoznost, s katero se naprava prijavi sredi scu: "to napravo je mogoce upravljati". */
    const val ZMOZNOST = "remote"
    /** Naprava zna deliti cel zaslon na zahtevo druge naprave (apps.launch app=[CEL_ZASLON], stream=true). */
    const val ZMOZNOST_DELI_ZASLON = "zaslon.deli"
    /** Namesto aplikacije: deli cel zaslon, nicesar ne odpri (»Povezani zasloni« na drugi napravi). */
    const val CEL_ZASLON = "safeer.cel.zaslon"

    /** Vsa dejanja, ki jih ta naprava razume; Control jih dobi v odgovoru na `status`. */
    val DEJANJA = listOf(
        "key", "scroll", "open_url", "volume", "launch_app", "open_in_app", "apps",
        "restart", "clear_cache", "status", "screenshot",
        // Protocol v1: ista imena kot pri ponudniku na racunalniku (Safeer Control), da odjemalec
        // (Safeer OS, Control) aplikacije katere koli naprave nasteje in zazene na en nacin.
        "apps.list", "apps.launch",
        // Konec »Odpri tukaj«: okno na drugi napravi se je zaprlo - nehamo deliti zaslon, aplikacijo umaknemo z zaslona.
        "apps.close",
        // Vnos z racunalnika na zaslon, ki ga naprava deli (Safeer Vnos, storitev dostopnosti):
        // dotik in poteg v delezih zaslona, tipka (sistemska ali tipkovnice), kolesce, besedilo v polje s fokusom.
        "input.tap", "input.swipe", "input.key", "input.text", "input.enable", "input.scroll",
        // Zvok racunalnika na tej napravi (Safeer OS za racunalnik: Zvok -> Predvajaj tukaj).
        "audio.play", "audio.stop",
        // Datoteke te naprave (videi, glasba, slike) za druge naprave - kot jih deli Safeer Control.
        "files.list", "files.search",
        // Opcijski telefonski gamepad za Android aplikacijo, ki tece na tej napravi.
        "gamepad.button", "gamepad.axis", "gamepad.release",
        // Magnet povezava z druge naprave: odpre se v Safeer OS predvajalniku te naprave.
        "magnet.open",
        // »Prikazi na TV / tablici« z druge naprave: naslov poisce Medijski center te naprave (PrikaziNa).
        "media.open",
        // Zakon solidarnosti: ta naprava torrent prenasa in pretaka napravi v Linku, ki ga sama ne zmore (MagnetPomoc).
        "magnet.stream", "magnet.list", "magnet.remove", "magnet.keep",
        // Zakon solidarnosti: koliko proste moci ima ta naprava in ali ta trenutek sme pomagati (Zmogljivost).
        "host.info",
        // Skupni prostor: shrani datoteko druge naprave (racunalnik z malo prostora) - Shramba.
        "storage.put", "storage.status",
        // Grafika: pretvorba videa na napravi z najboljsim strojnim kodirnikom (Pretvorba).
        "video.transcode", "video.status",
        // Sprotno pretvarjanje za napravo, ki videa ne zna predvajati (Pretok).
        "video.stream", "video.stream_stop",
        // "Nadaljuj na drugi napravi": kaj ta naprava predvaja (ali je nazadnje gledala) in kje; pavza, ce cilj tako izbere (Predaja).
        "play.state", "play.stop", "play.offer",
        // Naloga 129: kljuci debrid storitev s telefona (ozji krog, potrditev na tej napravi).
        si.safeer.tv.os.DebridPravila.DEJANJE_KLJUCI,
        // Naloga 136: torrenti, ki jih je Real-Debrid ali AllDebrid ze vrnil kot pripravljene (⚡), z druge naprave v krogu.
        si.safeer.tv.os.DebridPravila.DEJANJE_PREDPOMNJENI,
        // Naloga 117: druga naprava upravlja predvajanje na tej kot lokalno (premor, naprej, cas, glasnost, vrsta).
        si.safeer.tv.os.OddaljenoPravila.DEJANJE,
        // Seznami predvajanja so enaki na vseh napravah v Linku: druga naprava prebere sezname te naprave (SeznamiSink).
        "lists.get",
        // Preverjevalec doma: druga naprava vprasa, kaj se da predvajati; dodatke vprasa samo ta (DomPreverjanje).
        "avail.get"
    )

    /** Zmoznost, s katero se naprava javi, da zna predvajati zvok racunalnika ([ZvokSprejemnik]). */
    const val ZMOZNOST_ZVOK = "audio"

    /** Izid ukaza: `ok`, kratko sporocilo za uporabnika in neobvezni podatki. */
    class Izid(val ok: Boolean, val sporocilo: String, val podatki: JSONObject? = null, val koda: String = "",
               /** »Odpri tukaj« brez vprasanja: sprejemnik odgovori, ko deljenje res stece (CastReceiverService, meritev M1). */
               val pocakajZacetek: Boolean = false) {
        fun json(): JSONObject = JSONObject().apply {
            put("ok", ok)
            put("message", sporocilo)
            if (koda.isNotBlank()) put("code", koda)
            if (podatki != null) put("data", podatki)
        }

        /** Isti odgovor z izidom cakanja: [steklo] = deljenje tece (`started`); sicer ostane »pending«. */
        fun zZacetkom(steklo: Boolean): Izid {
            val p = JSONObject((podatki ?: JSONObject()).toString()).put("started", steklo)
            return Izid(ok, if (steklo) "Deljenje zaslona teče" else sporocilo, p, koda)
        }
    }

    const val KODA_NI_DOVOLJENO = "ni_dovoljeno"

    /**
     * Odgovor napravi, ki ji uporabnik te naprave zmoznosti ni odprl (cast/Dostop). Pri seznamih ima obliko »naprava
     * tega ne deli«, kot jo naprave ze poznajo (prazen seznam, shared/enabled = false) - starejsa razlicica na drugi
     * strani zato ne pokaze napake, ampak napravo brez deljenja. Vse drugo dobi kratko zavrnitev s kodo.
     */
    fun zavrnitevDostopa(dejanje: String): Izid = when (dejanje.trim().lowercase()) {
        "files.list", "files.search" -> Izid(true, "Naprava datotek ne deli",
            JSONObject().put("folder", "").put("items", JSONArray()).put("shared", false).put("reason", "access"))
        "apps.list" -> Izid(true, "Naprava programov ne deli",
            JSONObject().put("items", JSONArray()).put("enabled", false).put("reason", "access"))
        "apps" -> Izid(true, "Naprava programov ne deli", JSONObject().put("apps", JSONArray()).put("reason", "access"))
        "play.state" -> Izid(true, "Predvajanje", JSONObject().put("shared", false).put("reason", "access"))
        si.safeer.tv.os.OddaljenoPravila.DEJANJE -> Izid(true, "Predvajanje", JSONObject().put("shared", false).put("playing", false).put("reason", "access"))
        else -> Izid(false, "Ta naprava ti tega ne dovoli. Dostop odpre njen uporabnik v Safeer Linku.", koda = KODA_NI_DOVOLJENO)
    }

    /** Kar zna izvesti samo odprti brskalnik. Vrne null, ce dejanja ne pozna. */
    interface VOspredju {
        fun izvediUkaz(dejanje: String, parametri: JSONObject): Izid?
    }

    /** Dejanja, za katera mora biti brskalnik odprt. */
    private val ZAHTEVA_OSPREDJE = setOf("key", "scroll", "screenshot")

    /** Tipke daljinca, ki jih dejavnost dobi kot navadne KeyEvent-e (kot s pravega daljinca). */
    val TIPKE: Map<String, Int> = mapOf(
        "up" to android.view.KeyEvent.KEYCODE_DPAD_UP,
        "down" to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        "center" to android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        "back" to android.view.KeyEvent.KEYCODE_BACK,
        "menu" to android.view.KeyEvent.KEYCODE_MENU,
        "play_pause" to android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "play" to android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
        "stop" to android.view.KeyEvent.KEYCODE_MEDIA_STOP,
        "next" to android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "rewind" to android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
        "fast_forward" to android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "channel_up" to android.view.KeyEvent.KEYCODE_CHANNEL_UP,
        "channel_down" to android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
        "page_up" to android.view.KeyEvent.KEYCODE_PAGE_UP,
        "page_down" to android.view.KeyEvent.KEYCODE_PAGE_DOWN,
        // Stevke: program po stevilki (strani v zivo jih razumejo kot tipke daljinca).
        "0" to android.view.KeyEvent.KEYCODE_0, "1" to android.view.KeyEvent.KEYCODE_1,
        "2" to android.view.KeyEvent.KEYCODE_2, "3" to android.view.KeyEvent.KEYCODE_3,
        "4" to android.view.KeyEvent.KEYCODE_4, "5" to android.view.KeyEvent.KEYCODE_5,
        "6" to android.view.KeyEvent.KEYCODE_6, "7" to android.view.KeyEvent.KEYCODE_7,
        "8" to android.view.KeyEvent.KEYCODE_8, "9" to android.view.KeyEvent.KEYCODE_9
    )

    /**
     * Izvede ukaz. Klice se na glavni niti (dejavnost in WebView to zahtevata).
     * @param ospredje dejavnost v ospredju ali null, ce brskalnik ni odprt
     * @param odpriStran kako odpreti stran, ko brskalnika ni v ospredju (storitev to zna)
     */
    fun izvedi(
        context: Context,
        dejanje: String,
        parametri: JSONObject,
        ospredje: VOspredju?,
        domacaStran: String,
        odpriStran: (String, String) -> Unit
    ): Izid {
        val d = dejanje.trim().lowercase()
        if (d !in DEJANJA) return Izid(false, "Neznano dejanje: $d", koda = "neznano_dejanje")
        // Protocol v1: apps.list / apps.launch sta enotni imeni; `app` je id iz kataloga (tu ime paketa).
        if (d == "apps.list") return seznamV1(context, parametri)
        if (d == "apps.launch") {
            val paket = parametri.optString("app", "").ifBlank { parametri.optString("package", "") }
            // `stream: true` = pretoci aplikacijo napravi, ki je vprasala (slika na hub, vnos z Safeer Vnos).
            // Posiljatelja doda CastReceiverService iz sporocila huba (`_posiljatelj`), ne iz parametrov.
            if (parametri.optBoolean("stream", false)) {
                return pretociAplikacijo(context, paket, parametri.optString(PARAM_POSILJATELJ, ""))
            }
            return zazeniAplikacijo(context, paket)
        }
        if (d == "apps.close") return zapriPretok(context, parametri)
        // Vnos z racunalnika ne potrebuje brskalnika v ospredju: gre v aplikacijo, ki je na zaslonu.
        if (d.startsWith("input.")) return vnos(context, d, parametri)
        if (d.startsWith("gamepad.")) return igralniPloskek(context, d, parametri)
        // Zvok z racunalnika igra ne glede na to, kaj je na zaslonu.
        if (d == "audio.play") return ZvokSprejemnik.zacni(context, parametri)
        if (d == "audio.stop") return ZvokSprejemnik.ustavi()
        if (d == "files.search") {
            val podatki = DatotekeStreznik.isci(context, parametri.optString("q", ""), parametri.optString(PARAM_POSILJATELJ, ""))
            return Izid(true, "${podatki.optJSONArray("items")?.length() ?: 0} zadetkov", podatki)
        }
        if (d == "files.list") {
            val podatki = DatotekeStreznik.seznam(context, parametri.optString("folder", ""), parametri.optString(PARAM_POSILJATELJ, ""))
            return Izid(true, if (podatki.optBoolean("shared")) "Datoteke" else "Naprava datotek ne deli", podatki)
        }
        if (d == "magnet.open") return odpriMagnet(context, parametri.optString("uri", ""))
        if (d == "media.open") return odpriMedij(context, parametri)
        if (d == "magnet.stream") return MagnetPomoc.tok(context, parametri, parametri.optString(PARAM_POSILJATELJ, ""))
        if (d == "magnet.list") return MagnetPomoc.seznam(context, parametri.optString(PARAM_POSILJATELJ, ""))
        if (d == "magnet.remove") return MagnetPomoc.odstrani(context, parametri)
        if (d == "magnet.keep") return MagnetPomoc.obdrzi(context, parametri, parametri.optString(PARAM_POSILJATELJ, ""))
        if (d == "host.info") return Izid(true, "Zmogljivost", Zmogljivost.porocilo(context))
        if (d == "storage.put") return Shramba.sprejmi(context, parametri)
        if (d == "storage.status") return Shramba.stanje(parametri.optString("id"))
        if (d == "video.transcode") return Pretvorba.zacni(context, parametri)
        if (d == "video.status") return Pretvorba.stanje(parametri.optString("id"))
        if (d == "video.stream") return Pretok.zacni(context, parametri, parametri.optString(PARAM_POSILJATELJ, ""))
        if (d == "video.stream_stop") return Pretok.ustaviUkaz(parametri.optString("id"))
        if (d == si.safeer.tv.os.SeznamiSink.DEJANJE) return Izid(true, "Seznami", si.safeer.tv.os.SeznamiSink.izvoz(context, parametri))
        // Odgovor caka na dodatke, zato ga sprejemnik da z delovne niti (CastReceiverService); na glavni niti ga ni.
        if (d == si.safeer.tv.os.DomPreverjanjePravila.DEJANJE) return Izid(false, "Samo prek sprejemnika", koda = "ni_na_voljo")
        if (d == "play.state") return Izid(true, "Predvajanje", si.safeer.tv.os.Predaja.stanje(context, parametri.optString(PARAM_POSILJATELJ, "")))
        if (d == "play.stop") return Izid(true, "Pavza", si.safeer.tv.os.Predaja.ustavi())
        if (d == si.safeer.tv.os.OddaljenoPravila.DEJANJE) {
            val podatki = si.safeer.tv.os.OddaljenoPredvajanje.izvedi(context, parametri)
            return if (podatki.has("error")) Izid(false, "Neznan ukaz predvajanja", koda = "neveljavno") else Izid(true, "Predvajanje", podatki)
        }
        // "Poslji na napravo": izvor ponudi, kar igra; tu le tiho obvestilo s Sprejmi/Zavrni (nic se ne zacne samo).
        if (d == "play.offer") return Izid(true, "Ponudba", si.safeer.tv.os.Predaja.prejmiPonudbo(context, parametri.optString(PARAM_POSILJATELJ, ""), parametri))
        // Naloga 129: kljuci debrid storitev z druge naprave (ozji krog - dejanje ni v naborih DostopPravila); shranijo se
        // sele po potrditvi na tej napravi.
        if (d == si.safeer.tv.os.DebridPravila.DEJANJE_KLJUCI) {
            val od = parametri.optString(PARAM_POSILJATELJ, "")
            val ime = try { si.safeer.tv.os.LinkUpravitelj.pridobi(context).naprave.firstOrNull { it.id == od }?.ime } catch (_: Throwable) { null }
            return Izid(true, "Kljuci prejeti", si.safeer.tv.os.Debrid.prejmiKljuce(context, parametri, ime?.takeIf { it.isNotBlank() } ?: od))
        }
        // Naloga 136: pripravljeni torrenti z druge naprave (ozji krog - dejanje ni v naborih DostopPravila).
        if (d == si.safeer.tv.os.DebridPravila.DEJANJE_PREDPOMNJENI)
            return Izid(true, "Pripravljeni torrenti", si.safeer.tv.os.Debrid.prejmiZapomnjene(context, parametri))
        try {
            // Najprej dejavnost: tipke, drsenje, posnetek in tudi status z odprto stranjo.
            if (ospredje != null) {
                val izid = ospredje.izvediUkaz(d, parametri)
                if (izid != null) return izid
            } else if (d in ZAHTEVA_OSPREDJE) {
                // "home" brskalnik odpre; vse druge tipke potrebujejo odprt brskalnik.
                if (d == "key" && parametri.optString("key") == "home") {
                    odpriStran(domacaStran, "")
                    return Izid(true, "Safeer se odpira")
                }
                return Izid(false, "Safeer ni odprt na zaslonu. Najprej odpri stran ali Domov.", koda = "ni_v_ospredju")
            }
            return when (d) {
                "open_url" -> odpriUrl(parametri, domacaStran, odpriStran)
                "volume" -> glasnost(context, parametri)
                "launch_app" -> zazeniAplikacijo(context, parametri.optString("package", ""))
                "open_in_app" -> odpriVAplikaciji(context, parametri.optString("package", ""), parametri.optString("url", ""))
                "apps" -> Izid(true, "Seznam aplikacij", JSONObject().put("apps", aplikacije(context, parametri.optBoolean("icons", false))))
                "restart" -> znovaZazeni(context)
                "clear_cache" -> pocistiPredpomnilnik(context)
                "status" -> Izid(true, "Stanje", stanje(context, null))
                else -> Izid(false, "Dejanje $d tu ni na voljo")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Ukaz $d ni uspel: ${e.message}")
            return Izid(false, "Ukaz ni uspel: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun odpriUrl(parametri: JSONObject, domacaStran: String, odpriStran: (String, String) -> Unit): Izid {
        var url = parametri.optString("url", "").trim()
        if (url == "home" || url == "safeer://home") url = domacaStran
        if (!(url.startsWith("http://") || url.startsWith("https://") || url == domacaStran)) {
            return Izid(false, "Dovoljeni so samo naslovi http(s)")
        }
        odpriStran(url, parametri.optString("title", ""))
        return Izid(true, "Stran se odpira")
    }

    /** Glasnost naprave (STREAM_MUSIC): gor/dol/utisaj ali raven 0-100. */
    fun glasnost(context: Context, parametri: JSONObject): Izid {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val najvec = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val smer = parametri.optString("direction", "").lowercase()
        val raven = if (parametri.has("level")) parametri.optInt("level", -1) else -1
        when {
            raven in 0..100 -> {
                val indeks = Math.round(raven / 100.0 * najvec).toInt().coerceIn(0, najvec)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, indeks, AudioManager.FLAG_SHOW_UI)
            }
            smer == "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            smer == "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            smer == "mute" -> utisaj(am, true)
            smer == "unmute" -> utisaj(am, false)
            smer == "toggle_mute" -> utisaj(am, !jeUtisano(am))
            smer.isEmpty() -> { /* samo branje */ }
            else -> return Izid(false, "Neznana smer glasnosti: $smer")
        }
        val trenutno = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val podatki = JSONObject()
            .put("level", Math.round(trenutno * 100.0 / najvec).toInt())
            .put("muted", jeUtisano(am))
        return Izid(true, "Glasnost ${podatki.getInt("level")} %", podatki)
    }

    private fun jeUtisano(am: AudioManager): Boolean =
        am.isStreamMute(AudioManager.STREAM_MUSIC)

    private fun utisaj(am: AudioManager, utisano: Boolean) {
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (utisano) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    /** Namera za zagon aplikacije po imenu paketa (na televizorju najprej Leanback). */
    fun nameraZaZagon(context: Context, paket: String): Intent? {
        val pm = context.packageManager
        val namera = pm.getLeanbackLaunchIntentForPackage(paket)
            ?: pm.getLaunchIntentForPackage(paket) ?: return null
        namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return namera
    }

    /**
     * Zazene drugo aplikacijo. Android 10+ zagon iz ozadja pogosto tiho zavrne, zato poleg
     * neposrednega poskusa objavimo obvestilo s celozaslonsko namero - enako, kot brskalnik
     * odpre sam sebe, ko pride stran s telefona.
     */
    private fun zazeniAplikacijo(context: Context, paket: String): Izid {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(paket)) return Izid(false, "Neveljavno ime paketa")
        val namera = nameraZaZagon(context, paket) ?: return Izid(false, "Aplikacija $paket ni namescena", koda = "ni_namescena")
        val ime = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(paket, 0)).toString()
        } catch (_: Throwable) { paket }
        if (!smeZagnatiIzOzadja(context)) {
            // Android bi zagon tiho zavrnil; povej po resnici, zakaj, in ponudi resitev na napravi.
            prebudiZNamero(context, namera, ime)
            return izidBrezPoti(context, ime)
        }
        val neposredno = zazeniDejavnost(context, namera)
        // S Safeer Vnosom vidimo, ali je aplikacija na zaslonu: obvestilo samo, ce se ni odprla (prej vedno).
        namigPoZagonu(context, neposredno, VnosStoritev.aktivna(), { VnosStoritev.paketNaVrhu()?.let { it == paket } }, namera, ime)
        return Izid(true, "Odpiram $ime", JSONObject().put("package", paket).put("label", ime))
    }

    /** Zagon dejavnosti: prek Safeer Vnosa, ce je vklopljen (zagon iz ozadja je dovoljen, A2), sicer navadno. */
    private fun zazeniDejavnost(context: Context, namera: Intent): Boolean {
        if (VnosStoritev.zazeni(namera)) return true
        return try { context.startActivity(namera); true } catch (e: Throwable) {
            Log.w(TAG, "Neposredni zagon ni uspel: ${e.message}"); false
        }
    }

    /**
     * Rezervno obvestilo »tapni za nadaljevanje« po neposrednem zagonu (A2): takoj, ce zagon ni uspel ali tega ne moremo
     * preveriti ([preverljivo]); sicer sele po [DeljenjePravila.NAMIG_PO_MS], ce [odprlo] pove, da okna ni na zaslonu.
     */
    private fun namigPoZagonu(context: Context, neposredno: Boolean, preverljivo: Boolean, odprlo: () -> Boolean?,
                              namera: Intent, ime: String, besedilo: Int = si.safeer.tv.R.string.ui_link_zagon_odpri, velja: Long = 0L) {
        if (DeljenjePravila.namigTakoj(neposredno, preverljivo)) { prebudiZNamero(context, namera, ime, besedilo, velja); return }
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val je = try { odprlo() } catch (_: Throwable) { null }
            if (DeljenjePravila.namigPozneje(je)) {
                Log.i(TAG, "Okno se po neposrednem zagonu ni odprlo ($je): rezervno obvestilo")
                prebudiZNamero(app, namera, ime, besedilo, velja)
            }
        }, DeljenjePravila.NAMIG_PO_MS)
    }

    /** Odpre magnet povezavo v Safeer OS (MagnetActivity); brskalnik je nima. Povezavo najprej preverimo. */
    private fun odpriMagnet(context: Context, uri: String): Izid {
        if (si.safeer.tv.BuildConfig.FLAVOR == "brskalnik") return Izid(false, "Magnet povezave tu niso na voljo", koda = "ni_podprto")
        if (uri.length > 8192 || si.safeer.tv.os.MagnetMotor.hash(uri) == null) return Izid(false, "Neveljavna magnet povezava", koda = "ni_magnet")
        val namera = Intent().setClassName(context, "si.safeer.tv.os.MagnetActivity")
            .putExtra(si.safeer.tv.os.MagnetActivity.EXTRA_URI, uri)
            .putExtra(si.safeer.tv.os.MagnetActivity.EXTRA_SAMODEJNO, si.safeer.tv.os.MagnetActivity.ZETON)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val ime = "Safeer OS"
        if (!smeZagnatiIzOzadja(context)) {
            prebudiZNamero(context, namera, ime)
            return izidBrezPoti(context, ime)
        }
        try { context.startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Magnet ni odprt: ${e.message}") }
        prebudiZNamero(context, namera, ime)
        return Izid(true, "Odpiram magnet povezavo")
    }

    /** `media.open`: Safeer Media te naprave pride v ospredje in isce naslov (uporabnik je rekel »prikazi na TV«). */
    private fun odpriMedij(context: Context, parametri: org.json.JSONObject): Izid {
        if (si.safeer.tv.BuildConfig.FLAVOR == "brskalnik") return Izid(false, "Medijski center tu ni na voljo", koda = "ni_podprto")
        val naslov = si.safeer.tv.os.PrikaziNa.naslovIz(parametri)
        if (naslov.isEmpty()) return Izid(false, "Ni naslova", koda = "prazno")
        val namera = Intent().setClassName(context, "si.safeer.tv.os.GlasbaActivity")
            .putExtra(si.safeer.tv.os.GlasbaActivity.ISKANJE_BESEDA, naslov)
            .putExtra(si.safeer.tv.os.GlasbaActivity.ISKANJE_NATANCNO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val ime = "Safeer Media"
        if (!smeZagnatiIzOzadja(context)) {
            prebudiZNamero(context, namera, ime)
            return izidBrezPoti(context, ime)
        }
        try { context.startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Safeer Media ni odprta: ${e.message}") }
        prebudiZNamero(context, namera, ime)
        return Izid(true, "Odpiram v Medijskem centru: $naslov")
    }

    /** Kljuc, pod katerim CastReceiverService doda id posiljatelja ukaza (vedno prepise, kar pride od zunaj). */
    const val PARAM_POSILJATELJ = "_posiljatelj"

    /**
     * Pretoci aplikacijo [paket] napravi [cilj]: nevidna dejavnost vprasa za zajem zaslona (uporabnik ga
     * potrdi na tej napravi), zazene deljenje in odpre aplikacijo. Odgovor pride takoj; slika pride, ko
     * uporabnik potrdi.
     */
    /**
     * `apps.close`: okno »Odpri tukaj« na drugi napravi se je zaprlo. Android ne dovoli zapreti tuje aplikacije;
     * nehamo pa deliti zaslon in aplikacijo umaknemo z zaslona (Domov prek Safeer Vnosa) - igra se v ozadju ustavi
     * sama. Do 0.5.65 je zaprto okno na racunalniku tu pustilo vse, kot je bilo: zaslon se je delil naprej in igra
     * je igrala naprej (izmerjeno 7. 10. 2026 na tablici). Odlocitev: PretokKonec.
     */
    private fun zapriPretok(context: Context, p: JSONObject): Izid {
        // Tuje aplikacije Android ne da zapreti: ukaz poznamo samo kot konec pretoka (»Odpri tukaj«).
        if (!p.optBoolean("stream", false)) return Izid(false, "Android ne dovoli zapreti aplikacije", koda = "ni_podprto")
        val posiljatelj = p.optString(PARAM_POSILJATELJ, "")
        val o = PretokKonec.odloci(true, posiljatelj, DeljenjeZaslonaStoritev.tece, DeljenjeZaslonaStoritev.cilj,
            PretokKonec.zahteve.odprtoTukajZa(), VnosStoritev.aktivna(), PretokKonec.zahteve.cakaOd(), PretokKonec.zahteve.zacenjaZa())
        if (o.preklici) {
            // Zahteva, ki se caka na soglasje, je umaknjena: okno in obvestilo izgineta, pozna potrditev ne zacne deljenja.
            PretokKonec.zahteve.umakni()
            pospraviObvestiloZagona(context)
            PretociActivity.zapriCakajoco()
        }
        if (o.ustaviDeljenje) {
            // Zapis o »Odpri tukaj« je s tem porabljen: ponovljen ukaz (deljenje se se ustavlja) naprave ne vrze domov se enkrat.
            PretokKonec.zahteve.novoDeljenje()
            // Deljenje, ki se sele zaganja: ukaz za ustavitev pride v storitev za ukazom za zagon in ga konca, brz ko stece.
            PretokKonec.zahteve.zagonKoncan()
            DeljenjeZaslonaStoritev.ustavi(context)
        }
        val domov = o.domov && VnosStoritev.tipka("home")
        Log.i(TAG, "apps.close: deljenje=${o.ustaviDeljenje} domov=$domov preklic=${o.preklici}")
        val sporocilo = when {
            o.ustaviDeljenje && domov -> "Deljenje se ustavlja, aplikacija je umaknjena z zaslona"
            o.ustaviDeljenje -> "Deljenje se ustavlja"
            o.preklici -> "Zahteva je umaknjena"
            else -> "Zaslon se tej napravi ne deli"
        }
        return Izid(true, sporocilo, JSONObject()
            .put("stream", if (o.ustaviDeljenje) "stopping" else if (o.preklici) "cancelled" else "none").put("home", domov))
    }

    private fun pretociAplikacijo(context: Context, paket: String, cilj: String): Izid {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(paket)) return Izid(false, "Neveljavno ime paketa")
        if (cilj.isBlank()) return Izid(false, "Ni znano, komu pretociti", koda = "ni_posiljatelja")
        val celZaslon = paket == CEL_ZASLON
        if (!celZaslon && nameraZaZagon(context, paket) == null) return Izid(false, "Aplikacija $paket ni namescena", koda = "ni_namescena")
        val ime = if (celZaslon) context.getString(si.safeer.tv.R.string.os_zaslon) else try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(paket, 0)).toString()
        } catch (_: Throwable) { paket }
        // A1 (nacrt »dex-upravljanje«): ena potrditev na ogled, ne na aplikacijo. Ko zaslon tej napravi ze delimo, aplikacijo
        // samo odpremo - slika gre v okno, ki je na racunalniku ze odprto. »stream: pending« ostane zaradi starejsega
        // racunalnika (brez njega pove »posodobi Safeer«); »session: running« pove, da novega okna ne bo.
        when (DeljenjePravila.pot(cilj, celZaslon, DeljenjeZaslonaStoritev.tece, DeljenjeZaslonaStoritev.cilj,
                PretokKonec.zahteve.zacenjaZa(), DeljenjeZaslonaStoritev.cakaNaOdklep)) {
            DeljenjePravila.Pot.ZE_DELIM -> return Izid(true, "Zaslon se tej napravi že deli",
                JSONObject().put("package", paket).put("label", ime).put("stream", "pending").put("session", "running"))
            DeljenjePravila.Pot.ODPRI_V_SEJI -> {
                val izid = zazeniAplikacijo(context, paket)
                if (!izid.ok) return izid
                Log.i(TAG, "Odpri tukaj: aplikacija v tekocem deljenju, brez novega soglasja")
                return Izid(true, "Odpiram $ime v oknu, ki je že odprto",
                    (izid.podatki ?: JSONObject()).put("stream", "pending").put("session", "running"))
            }
            DeljenjePravila.Pot.NOVA_SEJA -> { }
        }
        // Meritev M1: brez okna za soglasje (trajno dovoljenje) mora biti deljenje na napravi vidno - z obvestilom »Ustavi«
        // ali z nasim gumbom na zaslonu. Ce ne gre nobeno, zaslona ne delimo in racunalniku povemo, zakaj.
        val projekcijaDovoljena = ZaupanjeZaslona.projekcijaDovoljena(context)
        if (!DeljenjePravila.smeZacetiBrezOkna(projekcijaDovoljena, obvestilaVklopljena(context), prekrivanje(context))) {
            Log.w(TAG, "Odpri tukaj zavrnjen: trajno dovoljenje brez vidnega znaka deljenja (obvestila in prikaz izklopljena)")
            return Izid(false, "Zaslona ne delim: naprava ne bi pokazala, da se zaslon deli (obvestila za Safeer in prikaz čez " +
                "druge aplikacije sta izklopljena). Na napravi odpri ${stranDovoljenj(context)} in vklopi obvestila.", koda = KODA_NI_ZNAKA)
        }
        if (!zahtevajDeljenje(context, cilj, paket, ime, odprtoTukaj = true, samodejno = true, besedilo = si.safeer.tv.R.string.ui_link_zagon_pretok))
            return izidBrezPoti(context, ime)
        // A3: s trajnim dovoljenjem Androida okna za soglasje ni - ne prosimo za potrditev, ki je ne bo (meritev M1).
        val potrditev = DeljenjePravila.potrditev(projekcijaDovoljena, ZaupanjeZaslona.zaupano(context, cilj))
        val inOdpre = if (celZaslon) "" else ", nato se odpre $ime"
        val sporocilo = when (potrditev) {
            DeljenjePravila.Potrditev.BREZ -> "Deljenje zaslona se začne samodejno$inOdpre"
            DeljenjePravila.Potrditev.NASA -> "Safeer na napravi vpraša, ali sme ta naprava videti zaslon$inOdpre"
            DeljenjePravila.Potrditev.SISTEMSKA -> "Na napravi potrdi deljenje zaslona$inOdpre"
        }
        // Zahteva je sla v okno takoj (zahtevajDeljenje = true): brez vprasanja pocakamo na dejanski zacetek.
        return Izid(true, sporocilo, JSONObject().put("package", paket).put("label", ime).put("stream", "pending"),
            pocakajZacetek = DeljenjePravila.pocakajZacetek(potrditev, true))
    }

    const val KODA_NI_ZNAKA = "ni_vidnega_znaka"
    const val KODA_DOVOLJENJE_OBVESTILA = "potrebno_dovoljenje_obvestila"

    /** Ali so obvestila za Safeer vklopljena (in, ce je [kanal] dan, ta kanal ni utisan). */
    fun obvestilaVklopljena(context: Context, kanal: String? = null): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.areNotificationsEnabled() &&
            (kanal == null || nm.getNotificationChannel(kanal)?.importance != android.app.NotificationManager.IMPORTANCE_NONE)
    } catch (_: Throwable) { false }

    /** Safeer sme risati cez druge aplikacije (prikaz cez druge aplikacije). */
    fun prekrivanje(context: Context): Boolean =
        try { android.provider.Settings.canDrawOverlays(context) } catch (_: Throwable) { false }

    /** Stran Dovoljenja v Safeer OS (ce jo ta razlicica ima) ali podatki o aplikaciji v sistemskih nastavitvah. */
    private fun nameraDovoljenj(context: Context): Intent {
        val stran = Intent().setClassName(context, "si.safeer.tv.os.DovoljenjaActivity")
        val ima = try { context.packageManager.resolveActivity(stran, 0) != null } catch (_: Throwable) { false }
        return (if (ima) stran else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun stranDovoljenj(context: Context): String =
        if (nameraDovoljenj(context).component != null) "Safeer › Nastavitve › Dovoljenja" else "sistemske nastavitve › Aplikacije › Safeer"

    /**
     * Android zagon iz ozadja zavrne. Obvestilo za tap (in prosnja za »Prikaz cez druge aplikacije«) je na zaslonu samo, ce
     * so obvestila vklopljena (meritev M1: na obeh testnih telefonih niso bila) - sicer racunalniku povemo, kje na napravi kaj
     * vklopiti, in stran z dovoljenji poskusimo odpreti (Android jo iz ozadja obicajno zavrne).
     */
    private fun izidBrezPoti(context: Context, ime: String): Izid {
        if (DeljenjePravila.brezZagona(obvestilaVklopljena(context)) == DeljenjePravila.BrezZagona.OBVESTILO)
            return izidBrezDovoljenja(context, ime)
        zazeniDejavnost(context, nameraDovoljenj(context))
        return Izid(false, "Na napravi se ne more pokazati nič: obvestila za Safeer so izklopljena in Safeer ne sme odpirati " +
            "oken v ozadju. Na napravi odpri ${stranDovoljenj(context)}, vklopi obvestila ali prikaz čez druge aplikacije, " +
            "nato ponovi $ime.", koda = KODA_DOVOLJENJE_OBVESTILA)
    }

    /**
     * Nova zahteva za deljenje zaslona napravi [cilj]: nevidna [PretociActivity] vprasa za soglasje. Ena zahteva naenkrat:
     * nova zamenja staro (okno stare se zapre, njena potrditev ne zacne nicesar). Zahteva velja dve minuti
     * ([PretokKonec.CAKA_MS]): obvestilo potem izgine, pozna potrditev ne zacne deljenja.
     *
     * [samodejno]: okno za soglasje odpremo takoj, kadar Android zagon iz ozadja dovoli (A2: tudi s Safeer Vnosom);
     * obvestilo je potem samo rezerva, ce se okno ni odprlo. Sicer (in brez dovoljenja) samo obvestilo, ki ga uporabnik
     * tapne. Vrne false, ce je bil zahtevan takojsnji zagon, a ga Android ne bi dovolil (objavljeno je samo obvestilo).
     */
    private fun zahtevajDeljenje(context: Context, cilj: String, paket: String, ime: String, odprtoTukaj: Boolean,
                                 samodejno: Boolean, besedilo: Int): Boolean {
        PretociActivity.zapriCakajoco()
        val zeton = PretokKonec.zahteve.nova(cilj)
        val namera = PretociActivity.namera(context, cilj, paket, zeton, odprtoTukaj)
        if (!samodejno || !smeZagnatiIzOzadja(context)) {
            prebudiZNamero(context, namera, ime, besedilo, velja = PretokKonec.CAKA_MS)
            return !samodejno
        }
        val neposredno = zazeniDejavnost(context, namera)
        Log.i(TAG, "Okno za soglasje: neposredni zagon=${neposredno} (Safeer Vnos=${VnosStoritev.aktivna()})")
        // Zahteva, ki ne caka vec (potrjena, zavrnjena, umaknjena), obvestila ne potrebuje.
        namigPoZagonu(context, neposredno, true, { PretociActivity.odprtaZahteva == zeton || !PretokKonec.zahteve.caka(zeton) },
            namera, ime, besedilo, PretokKonec.CAKA_MS)
        return true
    }

    /**
     * A4: naprava je odklenjena, deljenje napravi [cilj] caka na odklep (Android 15 QPR1+ ga je ob zaklepu ustavil).
     * Potrebno je novo soglasje: zaupani napravi ([samodejno]) okno odpremo takoj - s trajnim dovoljenjem Androida ga
     * sploh ni -, drugi samo obvestilo »Nadaljuj deljenje zaslona«. Slika gre v isti tok, v okno, ki je na racunalniku
     * ostalo odprto (DeljenjeZaslonaStoritev).
     */
    fun nadaljujDeljenje(context: Context, cilj: String, odprtoTukaj: Boolean, samodejno: Boolean) {
        zahtevajDeljenje(context, cilj, CEL_ZASLON, imeNaprave(context, cilj), odprtoTukaj, samodejno,
            si.safeer.tv.R.string.deljenje_nadaljuj)
    }

    /** Ime naprave v Linku (krajevni vzdevek ali ime s Huba) in njena platforma; brez seznama id. */
    private fun napravaLinka(context: Context, id: String): Pair<String, String> {
        var ime = ""
        var platforma = ""
        try {
            val polje = JSONArray(si.safeer.tv.cast.CastReceiverService.zadnjeNaprave)
            for (i in 0 until polje.length()) {
                val n = polje.optJSONObject(i) ?: continue
                if (n.optString("id", "") != id) continue
                ime = n.optString("name", "")
                platforma = n.optString("platform", "")
                break
            }
        } catch (_: Throwable) { }
        return LinkVzdevki.ime(context, id, ime.ifBlank { id }) to platforma
    }

    /** Ime naprave za besedila na tej napravi (obvestilo deljenja, vprasanje). */
    fun imeNaprave(context: Context, id: String): String = napravaLinka(context, id).first

    /** »Vedno dovoli temu racunalniku« ali »… tej napravi« - kot se naprava predstavi Hubu. */
    fun oznakaVednoDovoli(context: Context, id: String): String {
        val racunalnik = napravaLinka(context, id).second.lowercase() in setOf("linux", "windows", "macos", "mac", "pc")
        return context.getString(if (racunalnik) si.safeer.tv.R.string.deljenje_vedno_racunalnik else si.safeer.tv.R.string.deljenje_vedno_napravi)
    }

    private const val KANAL_ZAGON = "safeer_link_zagon"
    private const val OBVESTILO_ZAGON = 4046

    /** Obvestilo za zagon ni vec potrebno (dejavnost se je odprla neposredno). */
    fun pospraviObvestiloZagona(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(OBVESTILO_ZAGON)
        } catch (_: Throwable) {}
    }

    private const val KANAL_DOVOLJENJE = "safeer_link_dovoljenje"
    private const val OBVESTILO_DOVOLJENJE = 4047
    const val KODA_DOVOLJENJE_PRIKAZ = "potrebno_dovoljenje_prikaz"

    /**
     * Ali sme Safeer odpreti program, ko ni v ospredju (Android 10+: "Prikaz cez druge aplikacije"). A2: tudi z vklopljenim
     * Safeer Vnosom - sistem ga veze z BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS ([VnosStoritev.zazeni]).
     */
    fun smeZagnatiIzOzadja(context: Context): Boolean = DeljenjePravila.smeIzOzadja(Build.VERSION.SDK_INT,
        try { android.provider.Settings.canDrawOverlays(context) } catch (_: Throwable) { false },
        VnosStoritev.aktivna(), jeVOspredju(context))

    private fun jeVOspredju(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val moj = android.os.Process.myPid()
        am.runningAppProcesses.orEmpty().any {
            it.pid == moj && it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    } catch (_: Throwable) { false }

    /** Enkratno obvestilo na tej napravi: dotik odpre nastavitev "Prikaz cez druge aplikacije" za Safeer. */
    private fun obvestiZaDovoljenje(context: Context, ime: String) {
        try {
            val upravitelj = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (upravitelj.getNotificationChannel(KANAL_DOVOLJENJE) == null) {
                val kanal = android.app.NotificationChannel(KANAL_DOVOLJENJE, "Safeer Link - dovoljenje za zagon",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                kanal.description = "Enkratna prosnja za dovoljenje, da Safeer Link odpira programe na tej napravi."
                upravitelj.createNotificationChannel(kanal)
            }
            val nastavitev = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val cakajoca = PendingIntent.getActivity(context, OBVESTILO_DOVOLJENJE, nastavitev,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val obvestilo = android.app.Notification.Builder(context, KANAL_DOVOLJENJE)
                .setContentTitle("Safeer Link: dovoli odpiranje programov")
                .setContentText("Seznanjena naprava je zelela odpreti $ime. Dotakni se in vklopi »Prikaz čez druge aplikacije« za Safeer.")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(cakajoca)
                .setAutoCancel(true)
                .build()
            upravitelj.notify(OBVESTILO_DOVOLJENJE, obvestilo)
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila za dovoljenje ni bilo mogoce objaviti: ${e.message}")
        }
    }

    private fun izidBrezDovoljenja(context: Context, ime: String): Izid {
        obvestiZaDovoljenje(context, ime)
        return Izid(false, "Na tej napravi enkrat dovoli »Prikaz čez druge aplikacije« za Safeer (obvestilo je že na zaslonu), nato ponovi zagon $ime.",
            koda = KODA_DOVOLJENJE_PRIKAZ)
    }

    /**
     * Ce Android zagon iz ozadja zavrne (Android 10+, strozje od 14), uporabnik tapne to obvestilo. Zato
     * pove, kaj se bo zgodilo, ne samo ime aplikacije.
     */
    private fun prebudiZNamero(context: Context, namera: Intent, ime: String,
                               besedilo: Int = si.safeer.tv.R.string.ui_link_zagon_odpri, velja: Long = 0L) {
        try {
            val upravitelj = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (upravitelj.getNotificationChannel(KANAL_ZAGON) == null) {
                val kanal = android.app.NotificationChannel(KANAL_ZAGON, "Safeer Link - zagon aplikacij",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                kanal.description = "Odpre aplikacijo, ki jo izbere seznanjena naprava."
                kanal.setShowBadge(false)
                upravitelj.createNotificationChannel(kanal)
            }
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val cakajoca = PendingIntent.getActivity(context, OBVESTILO_ZAGON, namera, zastavice)
            val gradnik = android.app.Notification.Builder(context, KANAL_ZAGON)
            val obvestilo = gradnik
                .setContentTitle(si.safeer.tv.UiText.get(besedilo, ime).ifBlank { context.getString(besedilo, ime) })
                .setContentText(si.safeer.tv.UiText.get(si.safeer.tv.R.string.ui_link_zagon_tapni)
                    .ifBlank { context.getString(si.safeer.tv.R.string.ui_link_zagon_tapni) })
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(cakajoca)
                .setFullScreenIntent(cakajoca, true)
                .setAutoCancel(true)
                // Zahteva z rokom (»Odpri tukaj«): obvestilo izgine, ko zahteva ne velja vec.
                .apply { if (velja > 0) setTimeoutAfter(velja) }
                .build()
            upravitelj.notify(OBVESTILO_ZAGON, obvestilo)
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila za zagon ni bilo mogoce objaviti: ${e.message}")
        }
    }

    /**
     * Povezavo odpre v izbrani aplikaciji (npr. iskanje v aplikaciji YouTube). Ce je
     * aplikacija ne zna sprejeti, vrne neuspeh in klicatelj jo odpre v Safeerju.
     */
    private fun odpriVAplikaciji(context: Context, paket: String, url: String): Izid {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(paket)) return Izid(false, "Neveljavno ime paketa")
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return Izid(false, "Dovoljeni so samo naslovi http(s)")
        val namera = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(paket)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val zna = try { context.packageManager.queryIntentActivities(namera, 0).isNotEmpty() } catch (_: Throwable) { false }
        if (!zna) return Izid(false, "Aplikacija $paket te povezave ne zna odpreti", koda = "ne_zna_povezave")
        val ime = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(paket, 0)).toString()
        } catch (_: Throwable) { paket }
        try { context.startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Zagon $paket z naslovom ni uspel: ${e.message}") }
        prebudiZNamero(context, namera, ime)
        return Izid(true, "Odpiram $ime", JSONObject().put("package", paket).put("label", ime))
    }

    /** Najvec aplikacij v ENEM sporocilu z ikonami (stari ukaz `apps`): sporocilo sredisca sme imeti 256 KiB. */
    const val NAJVEC_V_SPOROCILU = 60

    /** Najvec aplikacij v seznamu po kosih (`apps.list`). Katalog za Hub ima svoje meje ([KatalogAplikacij]). */
    const val NAJVEC_APLIKACIJ = 400

    /**
     * Aplikacije, ki jih je mogoce zagnati (Leanback ali navadni zaganjalnik), po imenu.
     * Z ikonami (48 px, WebP) za mrezo v daljincu. [najvec]: stari ukaz `apps` poslje ves seznam z ikonami v enem
     * sporocilu (meja sredisca 256 KiB), zato tam 60; `apps.list` gre po kosih in katalog je brez ikon - tam vse.
     */
    fun aplikacije(context: Context, zIkonami: Boolean = false, najvec: Int = NAJVEC_V_SPOROCILU): JSONArray {
        val pm = context.packageManager
        val najdene = LinkedHashMap<String, String>()
        val kategorije = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (kategorija in kategorije) {
            val namera = Intent(Intent.ACTION_MAIN).addCategory(kategorija)
            val seznam = try { pm.queryIntentActivities(namera, 0) } catch (_: Throwable) { emptyList() }
            for (info in seznam) {
                val paket = info.activityInfo?.packageName ?: continue
                if (paket == context.packageName || najdene.containsKey(paket)) continue
                val ime = try { info.loadLabel(pm).toString().trim() } catch (_: Throwable) { "" }
                // Brez cloveskega imena (samo ime paketa) je vnos za uporabnika neuporaben.
                if (ime.isBlank() || ime == paket || Regex("^[a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(ime)) continue
                najdene[paket] = ime
            }
        }
        val polje = JSONArray()
        for ((paket, ime) in najdene.entries.sortedBy { it.value.lowercase() }.take(najvec)) {
            val zapis = JSONObject().put("package", paket).put("label", ime)
            if (zIkonami) ikonaAplikacije(pm, paket)?.let { zapis.put("icon", it) }
            polje.put(zapis)
        }
        return polje
    }

    /**
     * Dotik, poteg, sistemska tipka ali besedilo z racunalnika (Safeer Vnos). Ce storitev dostopnosti
     * ni vklopljena, uporabnik dobi jasno sporocilo; `input.enable` odpre nastavitve, kjer jo vklopi.
     */
    private fun vnos(context: Context, d: String, p: JSONObject): Izid {
        if (d == "input.enable") return vklopiVnos(context)
        // A4: deljenje caka na odklep - dotik ali tipka z racunalnika bi sla na zaklepni zaslon.
        if (!DeljenjePravila.vnosDovoljen(DeljenjeZaslonaStoritev.cakaNaOdklep)) return izidZaklenjeno()
        if (!VnosStoritev.aktivna()) {
            return Izid(false, "Na tej napravi vklopi Safeer Vnos (Nastavitve → Dostopnost).", koda = "vnos_ni_vklopljen")
        }
        val uspelo = when (d) {
            "input.tap" -> VnosStoritev.dotik(p.optDouble("x", -1.0), p.optDouble("y", -1.0), p.optLong("ms", 60))
            "input.swipe" -> VnosStoritev.poteg(p.optDouble("x1", -1.0), p.optDouble("y1", -1.0),
                p.optDouble("x2", -1.0), p.optDouble("y2", -1.0), p.optLong("ms", 300))
            "input.key" -> VnosStoritev.tipka(p.optString("key", ""))
            "input.scroll" -> VnosStoritev.kolesce(p.optDouble("x", 0.5), p.optDouble("y", 0.5), p.optInt("steps", 1))
            "input.text" -> VnosStoritev.besedilo(p.optString("text", "").take(2000))
            else -> false
        }
        return if (uspelo) Izid(true, "Vnos izveden") else Izid(false, "Vnosa ni bilo mogoce izvesti", koda = "vnos_ni_uspel")
    }


    /**
     * `input.enable`: uporabnika z najmanj truda pripelje do vklopa Safeer Vnosa (lastnik 25. 10. 2026). Voden tok:
     * [OmejenoNastavitvePravila]. Omejene nastavitve (Android 13+, lokalna namestitev) najprej zahtevajo
     * »⋮ → Dovoli omejene nastavitve → PIN« na strani aplikacije, sele potem se stikalo da vklopiti; od API 31 pridemo
     * naravnost na podrobnosti Safeer Vnosa (ACTION_ACCESSIBILITY_DETAILS_SETTINGS), da je stikalo takoj vidno.
     */
    private fun vklopiVnos(context: Context): Izid {
        if (VnosStoritev.aktivna()) return Izid(true, "Safeer Vnos je že vklopljen.")
        val zaklenjeno = omejenoZaklenjeno(context)
        val poskusil = jeZePoskusilVnos(context)
        beleziPoskusVnosa(context)
        val sl = try { context.resources.configuration.locales[0].language == "sl" } catch (_: Throwable) { false }
        return try {
            when (OmejenoNastavitvePravila.korak(false, zaklenjeno, poskusil)) {
                OmejenoNastavitvePravila.Korak.ODKLENI_OMEJENE -> {
                    // Tap na stikalo je »Prepovedana nastavitev« zavrnila. Odklep je odvisen od proizvajalca: AOSP/WP28 ima
                    // na strani aplikacije meni »⋮ → Dovoli omejene nastavitve«, OnePlus (izmerjeno 25. 10. 2026) ga NIMA -
                    // zato ne obljubimo poti, ki je naprava morda nima; odpremo podatke aplikacije (veljavno povsod) in
                    // povemo oba primera.
                    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val pot = if (sl) "Android je vklop zavrnil (omejena nastavitev). Če je zgoraj desno meni »⋮«, tapni »⋮« → »Dovoli omejene nastavitve« → potrdi s PIN-om, nato znova vklopi Safeer Vnos. Če menija ni, te omejitve na tej napravi ni mogoče odkleniti brez računalnika – javi Safeer."
                        else "Android blocked it (restricted setting). If there is a “⋮” menu top right, tap “⋮” → “Allow restricted settings” → confirm with your PIN, then enable Safeer Vnos again. If there is no such menu, this restriction can’t be lifted on this device without a computer – tell Safeer."
                    toast(context, pot)
                    Izid(true, pot, koda = KODA_OMEJENE_NASTAVITVE)
                }
                else -> {
                    odpriPodrobnostiVnosa(context)
                    val pot = if (sl) "Vklopi stikalo prav pri »Safeer Vnos« (ne pri sistemskem »Meniju za dostopnost«). Če Android pokaže »Prepovedana nastavitev«, najprej odkleni omejene nastavitve."
                        else "Turn on the switch for “Safeer Vnos” (not the system “Accessibility Menu”). If Android shows “Restricted setting”, allow restricted settings first."
                    toast(context, pot)
                    Izid(true, if (sl) "Na napravi se odpira Safeer Vnos: $pot" else "Opening Safeer Vnos on the device: $pot")
                }
            }
        } catch (e: Throwable) {
            Izid(false, "Nastavitev ni bilo mogoče odpreti: ${e.message}")
        }
    }

    const val KODA_OMEJENE_NASTAVITVE = "omejene_nastavitve"
    private const val PREFS_VNOS = "safeer_vnos_tok"

    /**
     * Od API 31 naravnost na podrobnosti Safeer Vnosa (ACTION_ACCESSIBILITY_DETAILS_SETTINGS - stikalo je takoj vidno,
     * brez iskanja po seznamu); nizje seznam dostopnosti z oznaceno postavko. Oboje uporabi isti `fragment_args`
     * mehanizem Nastavitev (brez skritih konstant). Ob napaki rezervno odpre navaden seznam dostopnosti.
     */
    private fun odpriPodrobnostiVnosa(context: Context) {
        val komponenta = android.content.ComponentName(context, VnosStoritev::class.java).flattenToString()
        val oznaci = android.os.Bundle().apply { putString(":settings:fragment_args_key", komponenta) }
        val akcija = if (Build.VERSION.SDK_INT >= 31) "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
            else android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
        val namera = Intent(akcija).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", komponenta)
            .putExtra(":settings:show_fragment_args", oznaci)
        try {
            if (context.packageManager.resolveActivity(namera, 0) != null) { context.startActivity(namera); return }
        } catch (_: Throwable) { }
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", komponenta)
            .putExtra(":settings:show_fragment_args", oznaci))
    }

    private fun toast(context: Context, besedilo: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { android.widget.Toast.makeText(context.applicationContext, besedilo, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
        }, 700)
    }

    /**
     * Ali so omejene nastavitve za Safeer se zaklenjene: AppOps `android:access_restricted_settings` ni `allow`
     * (po odklepu »Dovoli omejene nastavitve« postane `allow`). Na Androidu < 13 omejitve ni.
     */
    private fun omejenoZaklenjeno(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ao = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
            ao.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), context.packageName) !=
                android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }   // operacije ne poznamo: ne zavajamo z odklepom, ki ga morda ni treba
    }

    private fun jeZePoskusilVnos(context: Context): Boolean =
        try { context.getSharedPreferences(PREFS_VNOS, Context.MODE_PRIVATE).getBoolean("poskusil", false) } catch (_: Throwable) { false }

    private fun beleziPoskusVnosa(context: Context) {
        try { context.getSharedPreferences(PREFS_VNOS, Context.MODE_PRIVATE).edit().putBoolean("poskusil", true).apply() } catch (_: Throwable) { }
    }

    /** Odgovor racunalniku na vnos, medtem ko deljenje caka na odklep (A4). */
    private fun izidZaklenjeno(): Izid =
        Izid(false, "Naprava je zaklenjena – odkleni jo za nadaljevanje.", koda = KODA_ZAKLENJENO)

    const val KODA_ZAKLENJENO = "zaklenjeno"

    /**
     * Telefonski igralni plosek -> dotiki na Android gostitelju. To ni sistemski gamepad in ne
     * zahteva roota: Safeer Vnos (AccessibilityService), ki ga uporabnik sam vklopi, pretvori
     * omejen nabor gamepad dogodkov v geste. Profil je lokalna nastavitev gostitelja; oddaljena
     * naprava ne sme poslati poljubnih koordinat. Tako telefon ne dobi splosnega dostopa do zaslona.
     */
    private fun igralniPloskek(context: Context, d: String, p: JSONObject): Izid {
        if (!DeljenjePravila.vnosDovoljen(DeljenjeZaslonaStoritev.cakaNaOdklep)) return izidZaklenjeno()
        if (!VnosStoritev.aktivna()) return Izid(false,
            "Na napravi z igro vklopi Safeer Vnos (Nastavitve → Dostopnost).", koda = "vnos_ni_vklopljen")
        val ok = when (d) {
            "gamepad.button" -> VnosStoritev.igralniGumb(p.optString("button", ""), p.optBoolean("down", false))
            "gamepad.axis" -> VnosStoritev.igralnaOs(p.optString("axis", ""), p.optDouble("value", 0.0))
            "gamepad.release" -> VnosStoritev.igralniSprosti()
            else -> false
        }
        return if (ok) Izid(true, "Igralni vnos izveden", JSONObject().put("controller", "phone-touch-gamepad"))
        else Izid(false, "Igralnega vnosa ni bilo mogoce izvesti", koda = "gamepad_ni_uspel")
    }

    /**
     * Odgovor na `apps.list` v obliki, ki jo pozna tudi ponudnik na racunalniku:
     * {"enabled": true, "items": [{"id", "name", "icon"?}], "total", "offset"}. `icon` je data URL.
     */
    private fun seznamV1(context: Context, parametri: JSONObject): Izid {
        val zIkonami = parametri.optBoolean("icons", false)
        // Imena vseh aplikacij (brez ikon); ikone narisemo samo za kos, ki gre v to sporocilo. Prej je bil seznam
        // prirezan na prvih 60 po abecedi - druga naprava aplikacij od H naprej sploh ni videla.
        val polje = aplikacije(context, false, NAJVEC_APLIKACIJ)
        val pm = context.packageManager
        val meja = parametri.optInt("limit", 0)
        // Po kosih (offset) in do ~190 kB: sporocilo v Safeer Linku sme imeti najvec 256 kB; prevelik
        // odgovor se izgubi in druga naprava pokaze crke namesto ikon.
        val od = parametri.optInt("offset", 0).coerceIn(0, polje.length())
        val elementi = JSONArray()
        var velikost = 0
        for (i in od until polje.length()) {
            val z = polje.optJSONObject(i) ?: continue
            if (meja > 0 && elementi.length() >= meja) break
            val e = JSONObject().put("id", z.optString("package")).put("name", z.optString("label"))
            val ikona = if (zIkonami) ikonaAplikacije(pm, z.optString("package")) else null
            if (ikona != null) e.put("icon", ikona)
            val teza = (ikona?.length ?: 0) + z.optString("label").length * 2 + 120
            if (elementi.length() > 0 && velikost + teza > 190_000) break
            velikost += teza
            elementi.put(e)
        }
        val podatki = JSONObject().put("enabled", true).put("items", elementi)
            .put("total", polje.length()).put("offset", od)
        return Izid(true, "Seznam aplikacij", podatki)
    }

    /**
     * Katalog aplikacij za Protocol v1 (cast.register `apps` / apps.announce): objekt po imenu paketa,
     * {"<paket>": {"name": "...", "kind": "android"}}. Brez ikon - hub jih ne hrani; daljinec jih
     * vzame z ukazom `apps` z icons=true, ko jih potrebuje.
     */
    fun katalog(context: Context): JSONObject {
        // Meje Huba (200 vnosov, 32 KiB - vecji katalog zavrze v celoti) varuje KatalogAplikacij.
        return KatalogAplikacij.izSeznama(aplikacije(context, najvec = KatalogAplikacij.NAJVEC_VNOSOV))
    }

    /** Ikona aplikacije kot data URL (WebP, 48 px); null, ce je ni mogoce narisati. */
    private fun ikonaAplikacije(pm: PackageManager, paket: String): String? = try {
        val risba = pm.getApplicationIcon(paket)
        val velikost = 48
        val slika = android.graphics.Bitmap.createBitmap(velikost, velikost, android.graphics.Bitmap.Config.ARGB_8888)
        val platno = android.graphics.Canvas(slika)
        risba.setBounds(0, 0, velikost, velikost)
        risba.draw(platno)
        val izhod = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        slika.compress(android.graphics.Bitmap.CompressFormat.WEBP, 70, izhod)
        slika.recycle()
        "data:image/webp;base64," + android.util.Base64.encodeToString(izhod.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (_: Throwable) { null }

    /** Ponovni zagon aplikacije: cez pol sekunde jo sistem odpre znova, potem se koncamo. */
    private fun znovaZazeni(context: Context): Izid {
        val namera = nameraZaZagon(context, context.packageName)
            ?: return Izid(false, "Ponovni zagon ni mogoc")
        try {
            val zastavice = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val cakajoca = PendingIntent.getActivity(context, 4047, namera, zastavice)
            val budilka = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            budilka.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 700, cakajoca)
        } catch (e: Throwable) {
            return Izid(false, "Ponovnega zagona ni bilo mogoce nacrtovati: ${e.message}")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (_: Throwable) { }
        }, 400)
        return Izid(true, "Safeer se znova zaganja")
    }

    /** Pocisti lasten predpomnilnik (mapi cache in code_cache; WebView pocisti dejavnost). */
    private fun pocistiPredpomnilnik(context: Context): Izid {
        var bajtov = 0L
        for (mapa in listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir)) {
            bajtov += pobrisiVsebino(mapa)
        }
        val mb = bajtov / (1024.0 * 1024.0)
        return Izid(true, String.format(java.util.Locale.ROOT, "Predpomnilnik pociscen (%.1f MB)", mb),
            JSONObject().put("bytes", bajtov))
    }

    private fun pobrisiVsebino(mapa: java.io.File): Long {
        var skupaj = 0L
        val vnosi = mapa.listFiles() ?: return 0L
        for (v in vnosi) {
            try {
                if (v.isDirectory) {
                    skupaj += pobrisiVsebino(v)
                    v.delete()
                } else {
                    skupaj += v.length()
                    v.delete()
                }
            } catch (_: Throwable) { }
        }
        return skupaj
    }

    /** Osnovno stanje naprave; dejavnost doda odprto stran. */
    fun stanje(context: Context, dodatno: JSONObject?): JSONObject {
        val razlicica = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Throwable) { "" }
        val s = JSONObject()
            .put("app", context.packageName)
            .put("version", razlicica)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("foreground", dodatno != null)
            .put("can_wake", lahkoVOspredje(context))
            // A3: Android za deljenje zaslona ne vprasa (AppOps PROJECT_MEDIA = allow); »screen_waiting«: caka na odklep (A4).
            .put("screen_permanent", ZaupanjeZaslona.projekcijaDovoljena(context))
            .put("screen_waiting", DeljenjeZaslonaStoritev.cakaNaOdklep)
            .put("actions", JSONArray(DEJANJA))
            .put("keys", JSONArray(TIPKE.keys.toList()))
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val najvec = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            s.put("volume", Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / najvec).toInt())
            s.put("muted", jeUtisano(am))
        } catch (_: Throwable) { }
        if (dodatno != null) {
            val kljuci = dodatno.keys()
            while (kljuci.hasNext()) {
                val k = kljuci.next()
                s.put(k, dodatno.get(k))
            }
        }
        return s
    }

    /**
     * Ali se aplikacija sme sama postaviti v ospredje (Android 10+: dovoljenje za prekrivanje).
     * Na telefonu tega ne zahtevamo - tam stran odpre obvestilo, ki ga uporabnik tapne.
     */
    fun lahkoVOspredje(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        // Telefon in tablica: stran ali ukaz odpre obvestilo, ki ga uporabnik tapne - dovoljenja ni treba.
        val televizor = try { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } catch (_: Throwable) { false }
        if (!televizor) return true
        return try { android.provider.Settings.canDrawOverlays(context) } catch (_: Throwable) { true }
    }

    /** Sporocilo `control.result`, ki gre prek sredisca nazaj posiljatelju ukaza. */
    fun sporociloIzida(cilj: String, refId: String, dejanje: String, izid: Izid): JSONObject =
        JSONObject().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("type", "control.result")
            put("target", cilj)
            // Oznaka ukaza in ime dejanja prideta od posiljatelja ukaza: ponovimo samo toliko, kolikor sta dolga v nasih
            // programih (odgovor ne sme biti nacin, da kdo z dolgim ukazom dobi dolg odgovor).
            put("ref_id", refId.take(si.safeer.tv.cast.DostopPravila.NAJVEC_OZNAKE_SPOROCILA))
            put("payload", izid.json().put("action", dejanje.take(si.safeer.tv.cast.DostopPravila.NAJVEC_PONOVLJENEGA_DEJANJA)))
        }
}
