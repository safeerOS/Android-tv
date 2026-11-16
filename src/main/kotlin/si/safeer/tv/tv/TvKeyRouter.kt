package si.safeer.tv

import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import org.json.JSONObject

class TvKeyRouter(private val host: MainActivity) {

    private var lastDpadNavAt = 0L
    private var overlayBackDown = false

    fun dispatch(event: KeyEvent): Boolean {
        if (host.isScreenOffActive()) {
            // P8: spust Nazaj, ki ga pogoltne ugasnjen zaslon, ne sme pustiti zastavice prekrivnega sloja.
            overlayBackDown = false
            if (event.action == KeyEvent.ACTION_DOWN) {
                host.toggleScreenOffAudio(false)
            }
            return true
        }

        val keyCode = event.keyCode
        // Slepi pregled TV4 (TV4-2): nov pritisk OK zacne cisto - zastavici prejsnjega pritiska, katerega spust ni prisel do obdelave
        // (zaslon ugasnjen, predvajanje, prekrivni sloj), ne smeta klikniti na skritem kazalcu. Pred vsemi zgodnjimi vrnitvami.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            val ok = si.safeer.tv.KazalecPravila.okNovPritisk(si.safeer.tv.KazalecPravila.OkStanje(host.virtualPointerView.cakaKlik, host.virtualPointerView.okSpustPogoltni))
            host.virtualPointerView.cakaKlik = ok.cakaKlik
            host.virtualPointerView.okSpustPogoltni = ok.pogoltniSpust
        }
        val profile = SiteProfileResolver.fromUrl(host.activeUrl())
        val chromeFocused = host.isChromeFocused()

        if (host.playback.isNativeActive()) {
            if (event.action != KeyEvent.ACTION_DOWN) {
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (host.channelPad.cancel()) return true
                host.handleBrowserBack()
                return true
            }
            if (si.safeer.tv.TvTipke.dejanje(keyCode) == si.safeer.tv.TvTipke.Dejanje.HITRE_STRANI) {
                host.chrome.showPortals()
                return true
            }
            val nativeDigit = ChannelDigitPad.digitOf(keyCode)
            if (nativeDigit != null && host.channelPad.accepts()) {
                return host.channelPad.onDigit(nativeDigit, event.repeatCount)
            }
            if (host.playback.handleNativeKey(event)) return true
        }

        // TV-PP (Google Android TV kakovost, izmerjeno 16. 11. 2026): predvajaj/pavza na daljincu naj upravlja video v strani.
        val medijskiSkript = si.safeer.tv.TvTipke.jsMedijskaTipka(keyCode)
        if (medijskiSkript != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) host.activeWebView()?.evaluateJavascript(medijskiSkript, null)
            return true
        }

        // Native overlays own both halves of D-pad/OK events. Page profiles must
        // not consume ACTION_UP, otherwise focused Android buttons never click.
        if (keyCode == KeyEvent.KEYCODE_BACK && overlayBackDown) {
            // P8: nov pritisk Nazaj pomeni, da se je spust prejsnjega izgubil - tipka gre naprej, ni pogoltnjena.
            val nazaj = si.safeer.tv.UsmerjevalnikTipkPravila.nazajPoSloju(overlayBackDown, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
            overlayBackDown = nazaj.zastavica
            if (nazaj.pogoltni) return true
        }
        if (host.tabSwitcherOverlay.visibility == View.VISIBLE || host.findInPageBar.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    overlayBackDown = true
                    host.handleBrowserBack()
                }
                return true
            }
            return host.superDispatchKey(event)
        }

        if (profile === YoutubeTvSiteProfile) {
            val handled = YoutubeTvSiteProfile.handleKey(event, host)
            if (handled) return true
        }

        // Nacin miske: kratek OK klikne ob spustu tipke (dolg pritisk zgrabi za vlecenje).
        if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            val spust = si.safeer.tv.KazalecPravila.okSpust(host.virtualPointerView.cakaKlik, host.virtualPointerView.okSpustPogoltni)
            host.virtualPointerView.okPorabljen = false
            host.virtualPointerView.okSpustPogoltni = false
            if (spust == si.safeer.tv.KazalecPravila.OkSpust.KLIK) {
                host.virtualPointerView.cakaKlik = false
                host.activeWebView()?.let { host.virtualPointerView.performClickOnWebView(it) }
                return true
            }
            // N5: prvi OK je kazalec samo pokazal (keydown ni sel v stran) - tudi spust ne sme priti do strani kot sam keyup.
            if (spust == si.safeer.tv.KazalecPravila.OkSpust.POGOLTNI) return true
        }
        // Ai Lina tv browser (lastnik 15. 11. 2026): spust smerne tipke ustavi gibanje kazalca v eni slicici (KazalecGibanje);
        // ostala obdelava spusta ostane kot prej.
        if (event.action == KeyEvent.ACTION_UP && host.virtualPointerView.isPointerVisible) {
            val smerGor = si.safeer.tv.KazalecGibanje.smerIzTipke(keyCode)
            if (smerGor != 0) host.virtualPointerView.smerGor(smerGor, event.eventTime, host.activeWebView())
        }
        // Nazaj med vlecenjem: vlecenje se preklice (dotik ne obvisi v strani), Nazaj ne gre naprej.
        if (keyCode == KeyEvent.KEYCODE_BACK && host.virtualPointerView.vlecem) {
            if (event.action == KeyEvent.ACTION_DOWN) host.virtualPointerView.koncajVlecenje(host.activeWebView(), preklic = true)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK && profile === YoutubeTvSiteProfile) {
                return true
            }
            if (!chromeFocused && profile.consumeActionUp(keyCode)) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    SafeerDbg.log(
                        "H130",
                        "TvKeyRouter.kt:keyup",
                        "consume OK up",
                        JSONObject().put("code", keyCode)
                    )
                }
                return true
            }
            return host.superDispatchKey(event)
        }

        if (si.safeer.tv.TvTipke.dejanje(keyCode) == si.safeer.tv.TvTipke.Dejanje.HITRE_STRANI) {
            host.chrome.showPortals()
            Toast.makeText(host, UiText.get(R.string.ui_quick_sites), Toast.LENGTH_SHORT).show()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (profile.handleSearchKey(host)) return true
            host.chrome.showPortals()
            return true
        }

        if (si.safeer.tv.TvTipke.dejanje(keyCode) == si.safeer.tv.TvTipke.Dejanje.KAZALEC) {
            host.virtualPointerView.isPointerVisible = !host.virtualPointerView.isPointerVisible
            host.zapomniKazalec()
            Toast.makeText(
                host,
                if (host.virtualPointerView.isPointerVisible) UiText.get(R.string.ui_pointer_on) else UiText.get(R.string.ui_dpad_on),
                Toast.LENGTH_SHORT
            ).show()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW || keyCode == KeyEvent.KEYCODE_BOOKMARK || keyCode == 185) {
            host.showBookmarksDialog()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_PROG_BLUE || keyCode == 186 || keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_F) {
            host.activeWebView()?.evaluateJavascript("window._safeer_toggle_fullscreen();", null)
            return true
        }

        if (host.isChromeFocused() &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            return host.moveChromeFocus(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        if (host.editUrl.hasFocus()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val q = host.editUrl.text.toString().trim()
                    if (si.safeer.tv.SmartOmnibox.okOdpre(q, host.activeUrl())) {
                        host.performNavigation(q)
                        host.hideKeyboard()
                        host.editUrl.clearFocus()
                        host.activeWebView()?.requestFocus()
                        return true
                    } else {
                        if (si.safeer.tv.SmartOmnibox.izprazniObTipkovnici(q, host.activeUrl())) host.editUrl.setText("")
                        else host.editUrl.selectAll()
                        host.showKeyboard()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // TV 3. 11. 2026: DOL je sel vedno na stran, v predloge se ni dalo priti (kot Chrome: DOL v predloge).
                    host.hideKeyboard()
                    when (si.safeer.tv.OmniboxTipke.dolIzPolja(host.searchSuggestionsOverlay.visibility == View.VISIBLE,
                            host.portalChipsContainer.childCount, host.suggestionsListContainer.childCount)) {
                        si.safeer.tv.OmniboxTipke.Cilj.PRVI_CIP -> host.portalChipsContainer.getChildAt(0).requestFocus()
                        si.safeer.tv.OmniboxTipke.Cilj.PRVA_VRSTICA -> host.suggestionsListContainer.getChildAt(0).requestFocus()
                        else -> {
                            host.editUrl.clearFocus()
                            host.activeWebView()?.requestFocus()
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    host.hideKeyboard()
                    host.editUrl.clearFocus()
                    host.activeWebView()?.requestFocus()
                    return true
                }
            }
            return host.superDispatchKey(event)
        }

        val isSuggestionsOverlayFocused = host.searchSuggestionsOverlay.hasFocus() ||
            host.portalChipsContainer.hasFocus() ||
            host.suggestionsListContainer.hasFocus()

        if (isSuggestionsOverlayFocused) {
            // GOR s cipov (ali s prvega predloga brez cipov) nazaj v polje; OK in Nazaj obdela Android oziroma onBackPressed.
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                val fokus = host.currentFocus
                val seznam = host.suggestionsListContainer
                val vrstica = (0 until seznam.childCount).indexOfFirst { seznam.getChildAt(it) === fokus }
                if (si.safeer.tv.OmniboxTipke.gorVPlosci(host.portalChipsContainer.hasFocus(), vrstica,
                        host.portalChipsContainer.childCount) == si.safeer.tv.OmniboxTipke.Cilj.POLJE) {
                    host.editUrl.requestFocus()
                    return true
                }
            }
            return host.superDispatchKey(event)
        }

        val isTopBarFocused = host.isTopBarFocused()

        if (host.virtualPointerView.isPointerVisible && !isTopBarFocused) {
            val activeWv = host.activeWebView()
            // Nacin miske (spletne aplikacije, npr. Facebook): drzana tipka pospesi kazalec.
            // Lastnik 15. 11. 2026: gibanje po slicicah (KazalecGibanje): prvi pritisk = natancen korak 24 px, drzanje = pospesek
            // (neodvisen od ritma ponavljanja tipke), drsenje strani samo s potiskom ob rob; spust ustavi (zgoraj).
            val ponovitev = event.repeatCount > 0
            // K7/K8 (lastnik 15. 11. 2026): tipka daljinca pokaze skritega navideznega kazalca (mirovanje ali prava miska); prvi OK ga
            // samo pokaze - klika na mestu, ki ga uporabnik ne vidi, ni.
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                val bilSkrit = host.virtualPointerView.tipka(event.eventTime)
                if (si.safeer.tv.KazalecPravila.tipkaSamoPokaze(bilSkrit, keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    // TV4-2: samo pogoltni spust; klik ne caka (zastarel cakaKlik bi ob spustu kliknil na skritem mestu).
                    val samoPokazi = si.safeer.tv.KazalecPravila.okSamoPokazi(si.safeer.tv.KazalecPravila.OkStanje(host.virtualPointerView.cakaKlik, host.virtualPointerView.okSpustPogoltni))
                    host.virtualPointerView.okPorabljen = true
                    host.virtualPointerView.cakaKlik = samoPokazi.cakaKlik
                    host.virtualPointerView.okSpustPogoltni = samoPokazi.pogoltniSpust
                    return true
                }
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // V nacinu aplikacije vrstice z naslovom ni: fokus ne sme oditi v skrito polje.
                    // Lastnik 9. 11. 2026 (video 24ur.com): prej je ze 120 px pod vrhom tipka gor skocila v naslovno vrstico -
                    // stran se je navzgor premaknila najvec enkrat. Zdaj sele na samem vrhu in ko stran navzgor ne more vec.
                    // Lastnik 11. 11. 2026: z dna strani navzgor se kazalec ne zaleti v vrstico - samo namerna tipka na vrhu.
                    val smemVVrstico = si.safeer.tv.TvTipke.gorPritisk(event.repeatCount, android.os.SystemClock.uptimeMillis())
                    if (smemVVrstico && si.safeer.tv.KazalecPravila.vNaslovnoVrstico(host.virtualPointerView.pointerY, activeWv?.canScrollVertically(-1) == true) &&
                        host.nacinAplikacije == null &&
                        host.mobileTopBar.visibility == View.VISIBLE && !host.virtualPointerView.vlecem) {
                        host.editUrl.requestFocus()
                    } else {
                        host.virtualPointerView.smerDol(si.safeer.tv.KazalecGibanje.GOR, ponovitev, event.eventTime, activeWv)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    host.virtualPointerView.smerDol(si.safeer.tv.KazalecGibanje.DOL, ponovitev, event.eventTime, activeWv)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Kazalec na levem robu zaslona: levo od strani je stranska vrstica Safeer OS. Samo NOV pritisk (kot GOR na vrhu):
                    // pospesena drzana tipka bi kazalec pripeljala do roba in takoj odprla vrstico.
                    if (host.virtualPointerView.pointerX <= 10.5f && !ponovitev && host.imaOsVrstico()) host.vOsVrstico()
                    else host.virtualPointerView.smerDol(si.safeer.tv.KazalecGibanje.LEVO, ponovitev, event.eventTime, activeWv)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    host.virtualPointerView.smerDol(si.safeer.tv.KazalecGibanje.DESNO, ponovitev, event.eventTime, activeWv)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val miska = host.virtualPointerView
                    if (activeWv == null) return true
                    when {
                        // Med vlecenjem: OK spusti; ponovitve iste drzane tipke ne zgrabijo znova.
                        miska.vlecem && event.repeatCount == 0 -> { miska.koncajVlecenje(activeWv); miska.okPorabljen = true }
                        miska.vlecem || miska.okPorabljen -> Unit
                        // Drzan OK (~0,5 s): zgrabi; klika ob spustu ni.
                        event.repeatCount >= si.safeer.tv.os.OsPravila.PONOVITEV_ZA_VLECENJE -> {
                            miska.zacniVlecenje(activeWv)
                            Toast.makeText(host, UiText.get(R.string.ui_pointer_drag), Toast.LENGTH_SHORT).show()
                        }
                        event.repeatCount == 0 -> {
                            val caka = si.safeer.tv.KazalecPravila.okCakaKlik(si.safeer.tv.KazalecPravila.OkStanje(miska.cakaKlik, miska.okSpustPogoltni))
                            miska.cakaKlik = caka.cakaKlik; miska.okSpustPogoltni = caka.pogoltniSpust
                        }
                    }
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (host.tabManager.count > 1) {
                    host.tabManager.switchToPrevTab()
                    Toast.makeText(host, UiText.get(R.string.ui_previous_tab), Toast.LENGTH_SHORT).show()
                } else {
                    host.activeWebView()?.pageUp(false)
                }
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (host.tabManager.count > 1) {
                    host.tabManager.switchToNextTab()
                    Toast.makeText(host, UiText.get(R.string.ui_next_tab), Toast.LENGTH_SHORT).show()
                } else {
                    host.activeWebView()?.pageDown(false)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (host.channelPad.cancel()) return true
                host.handleBrowserBack()
                return true
            }
        }

        if (isTopBarFocused && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            return host.superDispatchKey(event)
        }

        // TV 3. 11. 2026: na strani s captcho lastnik ni mogel vklopiti kazalca (rdeci in zeleni gumb vzame
        // sistem Philips). Drzan OK na strani v nacinu D-pad zato vedno vklopi kazalec - ne glede na barvne gumbe.
        if (si.safeer.tv.TvTipke.drzanOkVkljuciKazalec(
                keyCode, event.repeatCount, host.virtualPointerView.isPointerVisible, isTopBarFocused || host.editUrl.hasFocus()
            ) && event.action == KeyEvent.ACTION_DOWN
        ) {
            host.virtualPointerView.isPointerVisible = true
            host.zapomniKazalec()
            host.virtualPointerView.okPorabljen = true
            Toast.makeText(host, UiText.get(R.string.ui_pointer_on), Toast.LENGTH_SHORT).show()
            return true
        }

        val dpadMove = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (dpadMove && profile !== YoutubeTvSiteProfile && event.action == KeyEvent.ACTION_DOWN) {
            val now = android.os.SystemClock.uptimeMillis()
            if (event.repeatCount > 0 && now - lastDpadNavAt < 95L) {
                return true
            }
            lastDpadNavAt = now
        }

        val gridDigit = ChannelDigitPad.digitOf(keyCode)
        if (gridDigit != null && host.channelPad.accepts()) {
            return host.channelPad.onDigit(gridDigit, event.repeatCount)
        }

        // Preizkus (lastnik 1. 11. 2026): z `adb shell setprop log.tag.SafeerBrezNasegaFokusa DEBUG` gredo smerne tipke
        // mimo nasega fokusa naravnost v WebView (vgrajena prostorska navigacija), da izmerimo, kako se TV odzove.
        // Nastavi lahko samo adb (lupina), ne aplikacija; brez lastnosti se nic ne spremeni.
        if (dpadMove && android.util.Log.isLoggable("SafeerBrezNasegaFokusa", android.util.Log.DEBUG)) {
            return host.superDispatchKey(event)
        }

        if (profile.handleKey(event, host)) return true

        return host.superDispatchKey(event)
    }
}
