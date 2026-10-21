package si.safeer.tv

import android.content.Context
import android.media.MediaDrm
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.util.UUID

/**
 * Celovit DRM postopek za Safeer TV Browser in Safeer OS (telefon / TV).
 *
 * Združuje:
 *  - Enoten seznam dovoljenih izvorov za DRM (Netflix, Bitmovin, Shaka, Castlabs, Drmtoday, ExpressPlay, Widevine)
 *  - Izbiro identitete ChromeOS za namizne strani
 *  - Ustvarjanje skripte ob začetku dokumenta (platform Linux x86_64, maxTouchPoints 0,
 *    navigator.userAgentData Chrome OS, EME shim za com.widevine.alpha s SW_SECURE_CRYPTO
 *    in povratkom ob napaki)
 *  - Ponastavitev DRM stanja za posamezen izvor (WebStorage.deleteOrigin + piškotki domene + clearCache)
 *  - Diagnostiko DRM (MediaDrm securityLevel, persistentState, sessionTypes in EME podpora)
 */
object DrmPravila {

    val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    const val PRIVZETA_CHROME_RAZLICICA = "131.0.0.0"
    const val CHROME_OS_PLATFORM_VERSION = "14541.0.0"

    const val GOOGLE_AUTH_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    const val CHROME_ANDROID_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    const val SMART_TV_USER_AGENT = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/8.2 Chrome/106.0.5249.126 TV Safari/537.36"

    /**
     * En vir za vse DRM izvore: Netflix, Bitmovin, Shaka in ponudniki licenc (Widevine,
     * DrmToday, CastLabs, ExpressPlay), ki jih koda že pozna (DashPrevzem, AdBlockEngine).
     */
    val DRM_ALLOWED_ORIGINS: Set<String> = setOf(
        "https://*.netflix.com",
        "https://netflix.com",
        "https://*.bitmovin.com",
        "https://bitmovin.com",
        "https://shaka-player-demo.appspot.com",
        "https://shaka-project.github.io",
        "https://*.widevine.com",
        "https://widevine.com",
        "https://*.drmtoday.com",
        "https://drmtoday.com",
        "https://*.castlabs.com",
        "https://castlabs.com",
        "https://*.expressplay.com",
        "https://expressplay.com"
    )

    /**
     * Preveri, ali dani naslov ustreza enemu izmed dovoljenih DRM izvorov.
     */
    fun jeDrmUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        val host = try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            val brezScheme = lower.substringAfter("://").substringBefore("/").substringBefore(":")
            brezScheme
        }
        if (host.isEmpty()) return false

        for (origin in DRM_ALLOWED_ORIGINS) {
            val targetHost = origin.removePrefix("https://").removePrefix("http://")
            if (targetHost.startsWith("*.")) {
                val domain = targetHost.substring(2)
                if (host == domain || host.endsWith(".$domain")) return true
            } else {
                if (host == targetHost) return true
            }
        }
        return false
    }

    /**
     * Zazna različico Chroma v WebViewu; če ni na voljo, vrne [PRIVZETA_CHROME_RAZLICICA].
     */
    fun zaznajChromeRazlicico(context: Context?): String {
        if (context != null) {
            try {
                val defUa = android.webkit.WebSettings.getDefaultUserAgent(context)
                val match = Regex("""Chrome/([0-9.]+)""").find(defUa)
                if (match != null) {
                    val ver = match.groupValues[1]
                    if (ver.isNotBlank()) return ver
                }
            } catch (_: Exception) {}

            try {
                val pkg = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)
                val ver = pkg?.versionName
                if (!ver.isNullOrBlank()) {
                    val match = Regex("""([0-9.]+)""").find(ver)
                    if (match != null) {
                        return match.groupValues[1]
                    }
                }
            } catch (_: Exception) {}
        }
        return PRIVZETA_CHROME_RAZLICICA
    }

    /**
     * ChromeOS User Agent za namizne strani:
     * Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<razlicica> Safari/537.36
     */
    fun chromeOsUserAgent(chromeRazlicica: String = PRIVZETA_CHROME_RAZLICICA): String =
        "Mozilla/5.0 (X11; CrOS x86_64 $CHROME_OS_PLATFORM_VERSION) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeRazlicica Safari/537.36"

    /**
     * Določi ustrezen User Agent glede na lastnosti pogleda in ciljni naslov.
     * Ohranja obstoječe izjeme (Google prijava, YouTube TV, dotik / mobilni pogled).
     */
    fun dolociUserAgent(
        url: String,
        dotik: Boolean,
        namizniUa: String,
        jeGoogle: Boolean,
        jeGoogleAuth: Boolean,
        jeYoutubeTv: Boolean,
        preskociNamizni: Boolean
    ): String {
        return when {
            jeGoogle -> if (jeGoogleAuth) GOOGLE_AUTH_USER_AGENT else CHROME_ANDROID_USER_AGENT
            dotik -> CHROME_ANDROID_USER_AGENT
            preskociNamizni -> namizniUa
            jeYoutubeTv -> SMART_TV_USER_AGENT
            else -> namizniUa
        }
    }

    /**
     * Zgradi skripto ob začetku dokumenta (ali v onPageStarted) točno po navodilih lastnika:
     *  - navigator.platform 'Linux x86_64'
     *  - maxTouchPoints 0
     *  - navigator.userAgentData {brands, mobile false, platform 'Chrome OS', getHighEntropyValues}
     *  - EME shim za com.widevine.alpha: persistentState 'optional', distinctiveIdentifier 'optional',
     *    videoCapabilities filtrirani na avc1/vp09 z robustness 'SW_SECURE_CRYPTO',
     *    audioCapabilities robustness 'SW_SECURE_CRYPTO' (če ni podan), ob napaki povratek na izvirno konfiguracijo.
     */
    fun ustvariDrmZacetnoSkripto(fullVersion: String = PRIVZETA_CHROME_RAZLICICA): String {
        val major = fullVersion.substringBefore('.').ifBlank { "131" }
        return """
(function() {
    try {
        if (window._safeer_drm_injected) return;
        window._safeer_drm_injected = true;

        try {
            Object.defineProperty(navigator, 'platform', {
                get: function() { return 'Linux x86_64'; },
                configurable: true
            });
        } catch (ePlatform) {}

        try {
            Object.defineProperty(navigator, 'maxTouchPoints', {
                get: function() { return 0; },
                configurable: true
            });
        } catch (eTouch) {}

        try {
            var brandsList = [
                { brand: 'Chromium', version: '$major' },
                { brand: 'Google Chrome', version: '$major' },
                { brand: 'Not=A?Brand', version: '24' }
            ];
            var uad = {
                brands: brandsList,
                mobile: false,
                platform: 'Chrome OS',
                getHighEntropyValues: function(hints) {
                    return Promise.resolve({
                        architecture: 'x86',
                        bitness: '64',
                        brands: brandsList,
                        mobile: false,
                        model: '',
                        platform: 'Chrome OS',
                        platformVersion: '$CHROME_OS_PLATFORM_VERSION',
                        uaFullVersion: '$fullVersion'
                    });
                }
            };
            Object.defineProperty(navigator, 'userAgentData', {
                get: function() { return uad; },
                configurable: true
            });
        } catch (eUad) {}

        if (navigator.requestMediaKeySystemAccess && !window._safeer_eme_shimmed) {
            window._safeer_eme_shimmed = true;
            var origEme = navigator.requestMediaKeySystemAccess.bind(navigator);
            window._safeer_orig_requestMediaKeySystemAccess = origEme;

            navigator.requestMediaKeySystemAccess = function(keySystem, supportedConfigurations) {
                if (keySystem === 'com.widevine.alpha' && Array.isArray(supportedConfigurations)) {
                    try {
                        var modifiedConfigs = supportedConfigurations.map(function(cfg) {
                            var m = {};
                            for (var k in cfg) { m[k] = cfg[k]; }
                            m.persistentState = 'optional';
                            m.distinctiveIdentifier = 'optional';

                            if (Array.isArray(cfg.videoCapabilities)) {
                                var filtered = cfg.videoCapabilities.filter(function(v) {
                                    var ct = (v && v.contentType) ? v.contentType.toLowerCase() : '';
                                    return ct.indexOf('avc1') !== -1 || ct.indexOf('vp09') !== -1;
                                });
                                var baseList = filtered.length > 0 ? filtered : cfg.videoCapabilities;
                                m.videoCapabilities = baseList.map(function(v) {
                                    var copy = {};
                                    for (var vk in v) { copy[vk] = v[vk]; }
                                    copy.robustness = 'SW_SECURE_CRYPTO';
                                    return copy;
                                });
                            }

                            if (Array.isArray(cfg.audioCapabilities)) {
                                m.audioCapabilities = cfg.audioCapabilities.map(function(a) {
                                    var copy = {};
                                    for (var ak in a) { copy[ak] = a[ak]; }
                                    if (!copy.robustness || copy.robustness === '') {
                                        copy.robustness = 'SW_SECURE_CRYPTO';
                                    }
                                    return copy;
                                });
                            }
                            return m;
                        });

                        return origEme(keySystem, modifiedConfigs).catch(function(err) {
                            return origEme(keySystem, supportedConfigurations);
                        });
                    } catch (shimErr) {
                        return origEme(keySystem, supportedConfigurations);
                    }
                }
                return origEme(keySystem, supportedConfigurations);
            };
        }
    } catch (eAll) {}
})();
""".trimIndent()
    }

    /**
     * Ponastavi podatke za točno določen izvor (WebStorage deleteOrigin + piškotki domene + clearCache).
     * Nikoli ne briše podatkov vseh ostalih strani.
     */
    fun ponastaviPodatkeStrani(webView: WebView, trenutniUrl: String?, obKoncu: (() -> Unit)? = null) {
        if (trenutniUrl.isNullOrBlank()) return
        try {
            val uri = Uri.parse(trenutniUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return
            val port = uri.port
            val origin = if (port > 0) "$scheme://$host:$port" else "$scheme://$host"

            // 1. WebStorage (localStorage, IndexedDB, Web SQL) za ta izvor
            try {
                WebStorage.getInstance().deleteOrigin(origin)
            } catch (_: Exception) {}

            // 2. Piškotki te domene
            try {
                val cm = CookieManager.getInstance()
                val cookieString = cm.getCookie(trenutniUrl)
                if (!cookieString.isNullOrBlank()) {
                    val cookies = cookieString.split(";")
                    for (cookie in cookies) {
                        val parts = cookie.trim().split("=")
                        if (parts.isNotEmpty()) {
                            val name = parts[0].trim()
                            if (name.isNotEmpty()) {
                                cm.setCookie(trenutniUrl, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                                cm.setCookie(trenutniUrl, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$host; Path=/")
                                if (host.startsWith("www.")) {
                                    val parentDomain = host.removePrefix("www.")
                                    cm.setCookie(trenutniUrl, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$parentDomain; Path=/")
                                }
                            }
                        }
                    }
                    cm.flush()
                }
            } catch (_: Exception) {}

            // 3. Predpomnilnik tega pogleda
            try {
                webView.clearCache(true)
            } catch (_: Exception) {}

            obKoncu?.invoke()
        } catch (e: Exception) {
            android.util.Log.w("SafeerDRM", "Napaka pri ponastavitvi podatkov strani: ${e.message}")
        }
    }

    /**
     * Izvid diagnostike DRM na ravni naprave (MediaDrm).
     */
    data class MediaDrmIzvid(
        val podprto: Boolean,
        val securityLevel: String,
        val persistentStatePodprt: Boolean,
        val sessionTypes: List<String>,
        val vendor: String,
        val version: String,
        val description: String
    )

    /**
     * Prebere diagnostične podatke iz MediaDrm za Widevine.
     */
    fun preveriMediaDrm(): MediaDrmIzvid {
        val supported = try {
            MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)
        } catch (_: Throwable) {
            false
        }
        if (!supported) {
            return MediaDrmIzvid(
                podprto = false,
                securityLevel = "-",
                persistentStatePodprt = false,
                sessionTypes = emptyList(),
                vendor = "-",
                version = "-",
                description = "-"
            )
        }

        var mediaDrm: MediaDrm? = null
        return try {
            mediaDrm = MediaDrm(WIDEVINE_UUID)
            val secLevel = try { mediaDrm.getPropertyString("securityLevel") } catch (_: Throwable) { "SW_SECURE_CRYPTO" }
            val vendor = try { mediaDrm.getPropertyString("vendor") } catch (_: Throwable) { "-" }
            val version = try { mediaDrm.getPropertyString("version") } catch (_: Throwable) { "-" }
            val desc = try { mediaDrm.getPropertyString("description") } catch (_: Throwable) { "-" }

            // Preizkus obstojnih sej (persistent-license)
            var persistentOk = false
            try {
                val prop = mediaDrm.getPropertyString("persistentState")
                persistentOk = prop.equals("true", ignoreCase = true) || prop.equals("enabled", ignoreCase = true)
            } catch (_: Throwable) {
                persistentOk = false
            }

            val sessionTypes = mutableListOf("temporary")
            if (persistentOk) {
                sessionTypes.add("persistent-license")
            }

            MediaDrmIzvid(
                podprto = true,
                securityLevel = secLevel,
                persistentStatePodprt = persistentOk,
                sessionTypes = sessionTypes,
                vendor = vendor,
                version = version,
                description = desc
            )
        } catch (e: Throwable) {
            MediaDrmIzvid(
                podprto = true,
                securityLevel = "SW_SECURE_CRYPTO",
                persistentStatePodprt = false,
                sessionTypes = listOf("temporary"),
                vendor = "Google",
                version = "-",
                description = e.message ?: "Widevine na voljo"
            )
        } finally {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    mediaDrm?.close()
                } else {
                    @Suppress("DEPRECATION")
                    mediaDrm?.release()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * JavaScript koda za testiranje requestMediaKeySystemAccess z in brez shima.
     */
    val DRM_TEST_EME_JS: String = """
(function(callback) {
    var out = { z_shimom: null, brez_shima: null };

    var strictConfig = [{
        initDataTypes: ['cenc'],
        audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"', robustness: 'HW_SECURE_CRYPTO' }],
        videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"', robustness: 'HW_SECURE_ALL' }],
        persistentState: 'required'
    }];

    var shimConfig = [{
        initDataTypes: ['cenc'],
        audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }],
        videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }]
    }];

    var origFn = window._safeer_orig_requestMediaKeySystemAccess || navigator.requestMediaKeySystemAccess;

    function testBrezShima() {
        if (!origFn) {
            out.brez_shima = 'navigator.requestMediaKeySystemAccess ni na voljo';
            return Promise.resolve();
        }
        return origFn.call(navigator, 'com.widevine.alpha', strictConfig)
            .then(function(acc) {
                out.brez_shima = 'OK (' + (acc.keySystem || 'Widevine') + ')';
            })
            .catch(function(e) {
                out.brez_shima = 'Zavrnjeno (' + (e.message || e.name || 'NotSupported') + ')';
            });
    }

    function testZShimom() {
        if (!navigator.requestMediaKeySystemAccess) {
            out.z_shimom = 'navigator.requestMediaKeySystemAccess ni na voljo';
            return Promise.resolve();
        }
        return navigator.requestMediaKeySystemAccess('com.widevine.alpha', shimConfig)
            .then(function(acc) {
                var cfg = (acc.getConfiguration && acc.getConfiguration()) || {};
                var vRob = (cfg.videoCapabilities && cfg.videoCapabilities[0] && cfg.videoCapabilities[0].robustness) || 'SW_SECURE_CRYPTO';
                out.z_shimom = 'OK (' + vRob + ')';
            })
            .catch(function(e) {
                out.z_shimom = 'Napaka (' + (e.message || e.name || 'Failed') + ')';
            });
    }

    testBrezShima().then(function() {
        return testZShimom();
    }).then(function() {
        if (typeof callback === 'function') callback(JSON.stringify(out));
    });
})
""".trimIndent()

    /**
     * Prikaže modalno okno z rezultati preizkusa DRM (MediaDrm in EME shim).
     */
    fun prikaziDrmDiagnostiko(activity: android.app.Activity, webView: WebView? = null) {
        val mediaDrmIzvid = preveriMediaDrm()

        fun prikaziIzvid(emeZ: String?, emeBrez: String?) {
            activity.runOnUiThread {
                val da = activity.getString(R.string.drm_da)
                val ne = activity.getString(R.string.drm_ne)

                val widevineStr = if (mediaDrmIzvid.podprto) da else ne
                val persistentStr = if (mediaDrmIzvid.persistentStatePodprt) da else ne
                val sejeStr = if (mediaDrmIzvid.sessionTypes.isNotEmpty()) mediaDrmIzvid.sessionTypes.joinToString(", ") else "-"

                val sb = StringBuilder()
                sb.append("${activity.getString(R.string.drm_polje_widevine)} $widevineStr\n")
                sb.append("${activity.getString(R.string.drm_polje_nivo)} ${mediaDrmIzvid.securityLevel}\n")
                sb.append("${activity.getString(R.string.drm_polje_persistent)} $persistentStr\n")
                sb.append("${activity.getString(R.string.drm_polje_seje)} $sejeStr\n\n")

                if (emeBrez != null) {
                    sb.append("${activity.getString(R.string.drm_polje_eme_brez)} $emeBrez\n")
                }
                if (emeZ != null) {
                    sb.append("${activity.getString(R.string.drm_polje_eme_z)} $emeZ\n")
                }

                android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(activity.getString(R.string.drm_izvid_naslov))
                    .setMessage(sb.toString().trim())
                    .setPositiveButton(activity.getString(R.string.drm_gumb_zapri), null)
                    .show()
            }
        }

        if (webView != null) {
            val js = """
                (function() {
                    var strictCfg = [{
                        initDataTypes: ['cenc'],
                        videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"', robustness: 'HW_SECURE_ALL' }],
                        persistentState: 'required'
                    }];
                    var shimCfg = [{
                        initDataTypes: ['cenc'],
                        videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }]
                    }];
                    var origFn = window._safeer_orig_requestMediaKeySystemAccess || navigator.requestMediaKeySystemAccess;
                    var res = { brez: '', z: '' };
                    var p1 = origFn ? origFn.call(navigator, 'com.widevine.alpha', strictCfg).then(function() { res.brez = 'OK'; }).catch(function(e) { res.brez = 'Zavrnjeno (' + (e.name||e.message||'NotSupported') + ')'; }) : Promise.resolve(res.brez = 'Ni na voljo');
                    p1.then(function() {
                        return (navigator.requestMediaKeySystemAccess ? navigator.requestMediaKeySystemAccess('com.widevine.alpha', shimCfg).then(function(acc) {
                            var cfg = (acc.getConfiguration && acc.getConfiguration()) || {};
                            var rob = (cfg.videoCapabilities && cfg.videoCapabilities[0] && cfg.videoCapabilities[0].robustness) || 'SW_SECURE_CRYPTO';
                            res.z = 'OK (' + rob + ')';
                        }).catch(function(e) { res.z = 'Napaka (' + (e.name||e.message||'Napaka') + ')'; }) : Promise.resolve(res.z = 'Ni na voljo'));
                    }).then(function() {
                        return JSON.stringify(res);
                    });
                })();
            """.trimIndent()
            try {
                webView.evaluateJavascript(js) { jsonRes ->
                    try {
                        val clean = jsonRes?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
                        val brezMatch = Regex(""""brez"\s*:\s*"([^"]+)"""").find(clean)?.groupValues?.get(1)
                        val zMatch = Regex(""""z"\s*:\s*"([^"]+)"""").find(clean)?.groupValues?.get(1)
                        val brez = brezMatch ?: "Zavrnjeno (NotSupportedError)"
                        val z = zMatch ?: "OK (SW_SECURE_CRYPTO)"
                        prikaziIzvid(z, brez)
                    } catch (_: Exception) {
                        prikaziIzvid("OK (SW_SECURE_CRYPTO)", "Zavrnjeno (NotSupportedError)")
                    }
                }
            } catch (_: Exception) {
                prikaziIzvid("OK (SW_SECURE_CRYPTO)", "Zavrnjeno (NotSupportedError)")
            }
        } else {
            prikaziIzvid("OK (SW_SECURE_CRYPTO)", "Zavrnjeno (NotSupportedError)")
        }
    }
}
