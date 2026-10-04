# YouTube in the built-in browser: a video starts without a black screen

On a tablet or a phone, tapping a video on `m.youtube.com` used to leave the player **black for about ten
seconds** before the video started — and it then started around its sixth second. This note records what was
measured (4 October 2026), what the browser does about it, and how to measure it again.

## What was happening

Measured on a phone through the WebView's DevTools protocol (`cdp.py` in the round notes; debug build only):

| Time after the tap | Event |
|---|---|
| 0.0 s | the page starts its in-page navigation and asks for the watch data (`get_watch`) |
| 0.5 s | the answer names an ad (`playerAds`, `adSlots`); the player enters the ad state |
| 0.9 s … | the ad stream is requested; Safeer blocks it (it always did), the player retries |
| 5.7 s | the player fetches the video itself in the background |
| 10.9 s | the player gives up on the ad and shows the video — at 0:06 |

Three separate causes:

1. **The player waited for an ad that never arrives.** Blocking the ad stream on the network is not enough: the
   player still schedules the ad and waits for its timeout.
2. **The first seconds of the video were skipped.** The helper that skips ads set the playback position to the
   ad's length *before the ad had loaded*. A position set on an empty media element applies to the next thing it
   loads — which was the video.
3. **The server makes an in-page navigation wait.** Even with the ad removed from the answer, the streaming
   server answered the first request for the video with "come back later" for the length of the ad (5 s and 16 s
   were seen). This cannot be removed on the device.

A watch page that is **loaded as a new page** behaves differently: its data contains no ad, the first request
gets the video at once, and the picture is there in one to two seconds.

## What the browser does now

`assets/youtube_zacetek.js` runs on YouTube before the page's own scripts
(`WebViewCompat.addDocumentStartJavaScript`; on a WebView without that feature it is injected as early as
possible instead). It leaves `youtube.com/tv` alone — the TV interface has its own helper.

1. **Player data without ads.** `adPlacements`, `playerAds`, `adSlots` and `adBreakHeartbeatParams` are removed
   from the player response wherever the page reads it (`JSON.parse`, `Response.json()`, a JSON
   `XMLHttpRequest`, the initial `ytInitialPlayerResponse`). Nothing else in the data is touched, and `fetch`
   itself is not replaced.
2. **Another video is a new page.** A tap on a link to a *different* video, and every other move to a different
   video (next in a mix or playlist, the player's buttons), loads the watch page as a new page instead of
   navigating inside the page. Search, channels, Shorts, the same video at another time, YouTube Music and
   players embedded in other sites are left as they are. A page that kept asking for another video right after
   loading would be left alone after four attempts in ten seconds.
3. **A video opened from YouTube starts by itself.** On touch devices media normally needs a tap. A watch page
   loaded as a new page has not been tapped yet, so a navigation *from a YouTube page to a YouTube page* lifts
   that rule for the page it opens. An address that the app opens itself (a typed address, a link from another
   device, a restored tab) keeps the rule — sound never starts on its own.
4. **The ad-skipping helper no longer sets a position on media that has not loaded.**
5. **The address bar follows in-page navigation** (`doUpdateVisitedHistory`); it used to keep showing the
   address of the last full page load.

The network rules that block ad streams are unchanged; they remain the safety net.

## Measured after the change

Same phone, same video, same network:

| Case | Before | After |
|---|---|---|
| tap on a search result → picture and sound | 10.9 s, starts at 0:06 | 2.0 s, starts at 0:00 |
| tap on a related video on a watch page | as above | 1.8 s |
| end of a video → next video of the mix | wait of the ad's length | 1.7 s |
| typed watch address | paused with a play button | unchanged |

## What this does not do

- The Back button on a watch page now returns to the previous page and playback stops; YouTube's own minimise
  gesture still keeps the mini player.
- If YouTube starts putting ads into freshly loaded watch pages as well, the wait described in point 3 above
  would return for the length of the ad. The measurement below shows it immediately.
- YouTube Music and the desktop layout were not measured.

## Measuring again

Install a debug build, open the browser, then on a computer:

```
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof <package>)
```

and record player and network events while tapping a video. The interesting lines are the first
`videoplayback` request and its size (about a hundred bytes means the server asked the player to wait), the
`ad-showing` state of the player, and the first `playing` event with its position.

Rules without a device: `node tests/youtube_zacetek_test.js`.
