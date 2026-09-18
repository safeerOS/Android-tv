---
name: tv-browser-2-xplore-drm
description: >-
  Safeer TV Browser: Xplore WebView catalog + native Media3 ExoPlayer (DASH/Widevine)
  and generic D-Pad spatial nav. Activate for tv-browser-2, Xplore, livetv, DRM,
  Media3, D-Pad, or build_tv_apk.sh.
---

# Safeer TV Browser — Xplore native Media3 playback

Xplore (`xploretv.si`) catalog, login, D-Pad and EPG stay in **WebView**. Video is decoded by **AndroidX Media3 ExoPlayer** on a raw `SurfaceView` overlay. Do **not** add Castlabs PRESTOplay Android SDK or any paid DRM SDK. Do **not** scrape pirate streams.

## Project facts

- **Folder**: `tv-browser-2/` (not `streamN-TV/`, not `freenet-browser/`)
- **Package**: `si.safeer.tv`
- **APK**: `tv-browser-2/TV-Browser-2.apk` via `tv-browser-2/build_tv_apk.sh` (**Gradle + Media3**, then uber-apk-signer)
- **GitHub**: `https://github.com/memelandfaner/Safeer-TV-Browser`
- **Download APK**: `https://github.com/memelandfaner/Safeer-TV-Browser/raw/main/TV-Browser-2.apk` — short: `https://tinyurl.com/27w3uxob` / `https://da.gd/8fziT`
- **Install**: `curl -sL https://raw.githubusercontent.com/memelandfaner/Safeer-TV-Browser/main/install_tv_browser.sh | bash`
- **MainActivity** stays `android.app.Activity` (not AppCompatActivity). No `media3-ui`.

## Frozen / do-not-touch

- Never edit `streamN-TV/`, `freenet-browser/`, or `Android-tv-browser/`.
- Never `git push` unless the user explicitly asks.
- **Never commit Xplore login credentials.**

## Debug order (do not skip)

1. **Build must put Media3 in dex.** `build_tv_apk.sh` fails if `androidx/media3/exoplayer/ExoPlayer` is missing from `classes*.dex`.
2. **Step 1 — clear DASH, no DRM.** `EXO_SMOKE` plays Big Buck Bunny (`dash.akamaized.net/.../bbb_30fps.mpd`) on the SurfaceView overlay. If the overlay never shows picture, **the APK did not include Media3** (or SurfaceView never attached). **Do not debug Widevine.**
3. Only after first frame (`H335` / label `Media3 DASH OK`) wire Xplore MPD + license headers into the same player.

## Playback contract

1. Let the page start clpp so it requests MPD + Widevine license with the logged-in A1 session.
2. `shouldInterceptRequest` must **passthrough** Xplore/DASH/DRM URLs (`return null` after logging). Do not let AdBlock/Threat empty-body a `.mpd` or license POST.
3. Capture DASH `.mpd`. **Split by parsed manifest.** No ContentProtection / PSSH / cenc → play immediately, no DRM (SLO 1). If PSSH / Widevine / cenc **is** present, `prepare()` Exo **immediately** with `DefaultDrmSessionManager` — Media3 POSTs the Widevine challenge. Do **not** wait for a clpp/Castlabs license POST (no 4s pre-roll). License URL order: MPD Laurl → page clpp/presto config (hook at inject) → last intercept hint (never a gate). DRM HTTP timeout (4s) is inside the callback only.
4. Release WebView MediaKeys (`_safeer_xplore_release_cdm` → `video.setMediaKeys(null)`) so Android 11's single CDM is free for Media3.
5. Play with Media3 `DashMediaSource` + session cookies on the MPD, and `DefaultDrmSessionManager` / `MediaItem.DrmConfiguration` (device Widevine).
6. OK on livetv = **one** `tile.click()`. No `safeer_xplore_autoplay` on `/livetv`.
7. `warmDrm()` must **not** call `createMediaKeys()`.
8. Keep kiosk chrome hidden on every `xploretv.si` URL. Do not hide the livetv grid with `visibility:hidden`.
9. D-Pad only on `.item.item--event`, not `.channel-container` logos. After seeding the first tile, **continue** the same RIGHT.
10. DASH `__c/A1_SI_*_ott` must match the tile.
11. BACK while native player is up: exit Exo overlay, then livetv → reload `/livetv`, else `/home`. Never `history.back()`.
12. Grant `PROTECTED_MEDIA_ID` on the UI thread so the page can still fetch the license. Desktop Chrome UA. Whitelist Xplore / Castlabs / Widevine / DRMToday in AdBlock **and** Threat.

Do **not** go back to WebView EME / clpp smash. Gate 1+2 (clear DASH smoke) already passed.

## D-Pad spatial navigation

`assets/tv_spatial.js` provides **generic** spatial navigation that must work on any page.
Do **not** turn on Chromium `--enable-spatial-navigation`, and do **not** add per-site
profiles, selectors or workarounds for individual websites to this repository: the promise
is that the remote works everywhere by the generic rules, not that the code carries a
recipe for one site. Skip carousel arrows (`.swiper-button-*`, `.owl-prev/next`) and
elements inside inactive carousels.

Do not inject a visible `#safeer-probe` overlay.

## Do not restore

H275, H274, H280, H268, `restoreUrlOnResume`, further clpp smash/EME "fixes" instead of native overlay.
