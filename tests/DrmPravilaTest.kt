package si.safeer.tv

fun main() {
    println("Preizkus DrmPravila...")

    // 1. ChromeOS User Agent generiranje
    val privzetiUa = DrmPravila.chromeOsUserAgent()
    check(privzetiUa.contains("CrOS x86_64 14541.0.0")) { "Napačen CrOS del: $privzetiUa" }
    check(privzetiUa.contains("Chrome/131.0.0.0")) { "Napačna privzeta Chrome različica: $privzetiUa" }
    check(privzetiUa.startsWith("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0)")) { "Napačen začetek UA: $privzetiUa" }

    val poMeriUa = DrmPravila.chromeOsUserAgent("133.0.6943.120")
    check(poMeriUa.contains("Chrome/133.0.6943.120")) { "Prilagojena različica se ne ujema: $poMeriUa" }

    // 2. Izbira User Agenta (dolociUserAgent) z ohranjanjem vseh izjem
    val namizni = DrmPravila.chromeOsUserAgent()

    // Namizni TV način za običajne strani
    val uaObicajna = DrmPravila.dolociUserAgent(
        url = "https://www.netflix.com",
        dotik = false,
        namizniUa = namizni,
        jeGoogle = false,
        jeGoogleAuth = false,
        jeYoutubeTv = false,
        preskociNamizni = false
    )
    check(uaObicajna == namizni) { "Namizni UA ni bil uporabljen za Netflix: $uaObicajna" }

    // Google prijava mora dobiti Safari macOS UA, da prepreči blokado "Disallowed User-Agent"
    val uaGoogleAuth = DrmPravila.dolociUserAgent(
        url = "https://accounts.google.com/signin",
        dotik = false,
        namizniUa = namizni,
        jeGoogle = true,
        jeGoogleAuth = true,
        jeYoutubeTv = false,
        preskociNamizni = false
    )
    check(uaGoogleAuth == DrmPravila.GOOGLE_AUTH_USER_AGENT) { "Google prijava nima Mac Safari UA: $uaGoogleAuth" }

    // Google iskanje (ne prijava) mora dobiti Chrome Android UA
    val uaGoogleSearch = DrmPravila.dolociUserAgent(
        url = "https://www.google.com/search?q=test",
        dotik = false,
        namizniUa = namizni,
        jeGoogle = true,
        jeGoogleAuth = false,
        jeYoutubeTv = false,
        preskociNamizni = false
    )
    check(uaGoogleSearch == DrmPravila.CHROME_ANDROID_USER_AGENT) { "Google iskanje nima Chrome Android UA: $uaGoogleSearch" }

    // Naprava na dotik (telefon/tablica) vedno dobi Chrome Android UA
    val uaDotik = DrmPravila.dolociUserAgent(
        url = "https://www.netflix.com",
        dotik = true,
        namizniUa = namizni,
        jeGoogle = false,
        jeGoogleAuth = false,
        jeYoutubeTv = false,
        preskociNamizni = false
    )
    check(uaDotik == DrmPravila.CHROME_ANDROID_USER_AGENT) { "Na dotik nima mobilnega Chrome UA: $uaDotik" }

    // YouTube TV način dobi Smart TV Tizen UA
    val uaYtTv = DrmPravila.dolociUserAgent(
        url = "https://www.youtube.com/tv#/watch?v=123",
        dotik = false,
        namizniUa = namizni,
        jeGoogle = false,
        jeGoogleAuth = false,
        jeYoutubeTv = true,
        preskociNamizni = false
    )
    check(uaYtTv == DrmPravila.SMART_TV_USER_AGENT) { "YouTube TV nima Smart TV UA: $uaYtTv" }

    // 3. Enoten seznam izvorov za DRM
    val origins = DrmPravila.DRM_ALLOWED_ORIGINS
    check(origins.contains("https://*.netflix.com")) { "Manjka netflix wild origin" }
    check(origins.contains("https://*.bitmovin.com")) { "Manjka bitmovin wild origin" }
    check(origins.contains("https://shaka-player-demo.appspot.com")) { "Manjka shaka demo origin" }
    check(origins.contains("https://*.widevine.com")) { "Manjka widevine origin" }
    check(origins.contains("https://*.drmtoday.com")) { "Manjka drmtoday origin" }
    check(origins.contains("https://*.castlabs.com")) { "Manjka castlabs origin" }
    check(origins.contains("https://*.expressplay.com")) { "Manjka expressplay origin" }

    // 4. Prepoznavanje DRM URL-jev (jeDrmUrl)
    check(DrmPravila.jeDrmUrl("https://www.netflix.com/browse")) { "netflix.com/browse ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://netflix.com")) { "netflix.com ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://shaka-player-demo.appspot.com/demo/")) { "shaka demo ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://shaka-project.github.io/")) { "shaka github io ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://bitmovin.com/demos/stream-test")) { "bitmovin ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://lic.drmtoday.com/license-server-widevine/cenc/")) { "drmtoday ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://license.widevine.com/cenc/getlicense")) { "widevine license ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://wv.service.expressplay.com/hms/wv/rights/")) { "expressplay ni prepoznan" }
    check(DrmPravila.jeDrmUrl("https://lic.staging.castlabs.com/license")) { "castlabs ni prepoznan" }

    // Negativni primeri za jeDrmUrl
    check(!DrmPravila.jeDrmUrl("https://www.google.com")) { "Google ne sme biti DRM URL" }
    check(!DrmPravila.jeDrmUrl("https://rtvslo.si")) { "RTV SLO ne sme biti DRM URL" }
    check(!DrmPravila.jeDrmUrl("https://fake-netflix.com")) { "fake-netflix.com ne sme biti DRM URL" }
    check(!DrmPravila.jeDrmUrl("https://notnetflix.com/test")) { "notnetflix.com ne sme biti DRM URL" }

    // 5. Vsebina skripte ob začetku dokumenta (ustvariDrmZacetnoSkripto)
    val skripta = DrmPravila.ustvariDrmZacetnoSkripto("131.0.6778.200")

    // Idempotentnost
    check(skripta.contains("window._safeer_drm_injected")) { "Skripta nima zaščite pred dvojnim vbrizgavanjem" }

    // Platform in maxTouchPoints
    check(skripta.contains("Object.defineProperty(navigator, 'platform'")) { "Skripta ne preglasi navigator.platform" }
    check(skripta.contains("'Linux x86_64'")) { "Skripta ne vrača Linux x86_64 za platform" }
    check(skripta.contains("Object.defineProperty(navigator, 'maxTouchPoints'")) { "Skripta ne preglasi maxTouchPoints" }
    check(skripta.contains("return 0;")) { "Skripta ne vrača 0 za maxTouchPoints" }

    // navigator.userAgentData
    check(skripta.contains("platform: 'Chrome OS'")) { "Skripta ne nastavi userAgentData platforme na Chrome OS" }
    check(skripta.contains("mobile: false")) { "Skripta ne nastavi userAgentData mobile na false" }
    check(skripta.contains("Chromium")) { "Skripta nima Chromium v brands" }
    check(skripta.contains("Google Chrome")) { "Skripta nima Google Chrome v brands" }
    check(skripta.contains("getHighEntropyValues")) { "Skripta nima podpore za getHighEntropyValues" }

    // EME shim za com.widevine.alpha
    check(skripta.contains("navigator.requestMediaKeySystemAccess")) { "Skripta ne prestreza requestMediaKeySystemAccess" }
    check(skripta.contains("com.widevine.alpha")) { "Skripta ne obravnava com.widevine.alpha" }
    check(skripta.contains("persistentState = 'optional'")) { "Skripta ne omili persistentState na optional" }
    check(skripta.contains("distinctiveIdentifier = 'optional'")) { "Skripta ne omili distinctiveIdentifier na optional" }
    check(skripta.contains("SW_SECURE_CRYPTO")) { "Skripta ne nastavi SW_SECURE_CRYPTO robustnosti" }
    check(skripta.contains("avc1") && skripta.contains("vp09")) { "Skripta ne filtrira video kodekov na avc1/vp09" }

    // Povratek na izvirno konfiguracijo ob napaki
    check(skripta.contains("return origEme(keySystem, supportedConfigurations);")) { "Skripta nima fallbacka na izvirno konfiguracijo" }

    // 6. Preizkus MediaDrm preverjanja
    val mediaDrmIzvid = DrmPravila.preveriMediaDrm()
    check(mediaDrmIzvid.podprto) { "MediaDrm Widevine bi moral biti podprt v testu" }
    check(mediaDrmIzvid.securityLevel == "L1") { "MediaDrm varnostni nivo bi moral biti L1, dobljeno: ${mediaDrmIzvid.securityLevel}" }

    println("DrmPravilaTest: VSI PREIZKUSI USPESNI (OK)")
}
