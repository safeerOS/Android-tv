package si.safeer.tv

class DrmPravilaUnitTest {

    @org.junit.Test
    fun preizkusiChromeOsUserAgent() {
        val privzetiUa = DrmPravila.chromeOsUserAgent()
        assert(privzetiUa.contains("CrOS x86_64 14541.0.0")) { "Napačen CrOS del: $privzetiUa" }
        assert(privzetiUa.contains("Chrome/131.0.0.0")) { "Napačna privzeta Chrome različica: $privzetiUa" }
        assert(privzetiUa.startsWith("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0)")) { "Napačen začetek UA: $privzetiUa" }

        val poMeriUa = DrmPravila.chromeOsUserAgent("133.0.6943.120")
        assert(poMeriUa.contains("Chrome/133.0.6943.120")) { "Prilagojena različica se ne ujema: $poMeriUa" }
    }

    @org.junit.Test
    fun preizkusiIzbiroUserAgenta() {
        val namizni = DrmPravila.chromeOsUserAgent()

        // Običajna stran na TV
        val uaObicajna = DrmPravila.dolociUserAgent(
            url = "https://www.netflix.com",
            dotik = false,
            namizniUa = namizni,
            jeGoogle = false,
            jeGoogleAuth = false,
            jeYoutubeTv = false,
            preskociNamizni = false
        )
        assert(uaObicajna == namizni)

        // Google prijava
        val uaGoogleAuth = DrmPravila.dolociUserAgent(
            url = "https://accounts.google.com/signin",
            dotik = false,
            namizniUa = namizni,
            jeGoogle = true,
            jeGoogleAuth = true,
            jeYoutubeTv = false,
            preskociNamizni = false
        )
        assert(uaGoogleAuth == DrmPravila.GOOGLE_AUTH_USER_AGENT)

        // Dotik
        val uaDotik = DrmPravila.dolociUserAgent(
            url = "https://www.netflix.com",
            dotik = true,
            namizniUa = namizni,
            jeGoogle = false,
            jeGoogleAuth = false,
            jeYoutubeTv = false,
            preskociNamizni = false
        )
        assert(uaDotik == DrmPravila.CHROME_ANDROID_USER_AGENT)
    }

    @org.junit.Test
    fun preizkusiDrmOrigineInUrlje() {
        val origins = DrmPravila.DRM_ALLOWED_ORIGINS
        assert(origins.contains("https://*.netflix.com"))
        assert(origins.contains("https://*.bitmovin.com"))
        assert(origins.contains("https://shaka-player-demo.appspot.com"))
        assert(origins.contains("https://*.widevine.com"))
        assert(origins.contains("https://*.drmtoday.com"))
        assert(origins.contains("https://*.castlabs.com"))
        assert(origins.contains("https://*.expressplay.com"))

        assert(DrmPravila.jeDrmUrl("https://www.netflix.com/browse"))
        assert(DrmPravila.jeDrmUrl("https://netflix.com"))
        assert(DrmPravila.jeDrmUrl("https://shaka-player-demo.appspot.com/demo/"))
        assert(DrmPravila.jeDrmUrl("https://bitmovin.com/demos/stream-test"))
        assert(DrmPravila.jeDrmUrl("https://lic.drmtoday.com/license-server"))

        assert(!DrmPravila.jeDrmUrl("https://www.google.com"))
        assert(!DrmPravila.jeDrmUrl("https://rtvslo.si"))
        assert(!DrmPravila.jeDrmUrl("https://fake-netflix.com"))
    }

    @org.junit.Test
    fun preizkusiDrmZacetnoSkripto() {
        val skripta = DrmPravila.ustvariDrmZacetnoSkripto("131.0.6778.200")
        assert(skripta.contains("window._safeer_drm_injected"))
        assert(skripta.contains("'Linux x86_64'"))
        assert(skripta.contains("return 0;"))
        assert(skripta.contains("platform: 'Chrome OS'"))
        assert(skripta.contains("mobile: false"))
        assert(skripta.contains("navigator.requestMediaKeySystemAccess"))
        assert(skripta.contains("com.widevine.alpha"))
        assert(skripta.contains("persistentState = 'optional'"))
        assert(skripta.contains("distinctiveIdentifier = 'optional'"))
        assert(skripta.contains("SW_SECURE_CRYPTO"))
        assert(skripta.contains("return origEme(keySystem, supportedConfigurations);"))
    }
}
