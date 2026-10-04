# Blocked requests and players on web pages

On a site with videos, the player in the built-in browser **never started**: it stayed in its "loading" state
after the tap and did not request the video at all. With ad blocking switched off it was worse — the player
disappeared from the page. This note records what was measured (4 October 2026), the rules the browser follows
now, and how to check them again.

## What was happening

Measured on a phone through the WebView's DevTools protocol (debug build only). Three separate causes, all of
them ours:

1. **A blocked request was answered, not failed.** Before the video, the player asks its own site for an ad
   with `fetch`. The filter lists block that request — and the browser answered it with `200 OK` and an invented
   JSON body (a shape made for YouTube, used for every blocked address that contained `json`). For the player
   that is a successful answer it cannot read; it waited for the ad forever.
2. **The overlay cleaner removed the player.** The cleaner (part of the pop-up shield) removed everything that
   matched `div[class*="mgp_ad"]` five times a second. The player writes the state of its ad as a class on its
   **own main container**, so as soon as an ad was ready the rule matched the whole player and removed it. The
   cosmetic filter had the same rule and hid the player (size 0 × 0).
3. **An empty ad slot was wider than the screen.** The blocked ad network leaves `<ins>` placeholders with a
   fixed size; one is 480 px wide. On a 316 px wide phone the whole page became 480 px wide: content off the
   right edge, consent buttons outside the screen.

## The rules now

**A blocked request for data fails, before the response starts.** That is what every ad blocker does and what
pages are written for: `fetch` rejects, `XMLHttpRequest` ends with status 0, and the page carries on without
the ad. (`AdBlockEngine.vrstaBlokade`)

| Request | Answer |
|---|---|
| data (`fetch`, `XMLHttpRequest`) and media streams | fails with a network error |
| page, frame | empty `200 OK` (a failed frame would show an error page) |
| image, style sheet, script | empty `200 OK` (a failed script triggers blocker detectors) |
| the browser does not say what it expects | empty `200 OK`, as before |
| YouTube's ad paths, and Google hosts with `json` in the address | invented empty JSON, as before — tested there |

The failure has to happen **before the response headers**. WebView asks the response stream how many bytes are
available before it sends the headers to the page; the stream reports the error at that point. A stream that
only failed when read would let `fetch` succeed first (the headers `200 OK` are already out) and break the body
afterwards — pages do not expect that.

**The cleaner and the cosmetic filter leave players alone.**

- There are no rules for the inside of a player. With blocking on there is no ad to hide — the ad request fails.
- The cleaner never removes an element that contains a `<video>` or `<audio>` (it is a player, or wraps one), and
  nothing inside a player container.
- Placeholders of an ad network that is blocked on the network are hidden — only while blocking is on. With
  blocking off they hold the ad the user allowed.

## Measured after the change

Same phone, same page, same network:

| | Before | Now |
|---|---|---|
| Tap on play, blocking on | never starts | video plays after about 1.5 s, no ad |
| Tap on play, blocking off | the player disappears | the ad plays (about 8 s), then the video |
| Page width on a 316 px phone | 480 px | 316 px |
| YouTube: tap on a video → picture | 2.0 s | 2.0 s (unchanged) |

## Tests

- `tests/AdBlockTest.kt` — which answer a blocked request gets; the failed response reports the error when asked
  for its size, and when read; the invented JSON only for YouTube's paths and Google hosts; the cosmetic rules
  contain nothing for the inside of a player; placeholders follow the blocking switch.
- `tests/cistilec_prekrivk_test.js` — the cleaner runs in Node against a small document: a player whose
  container carries an ad-state class stays whole, an element with a video is never removed, ad overlays outside
  the player are removed as before.

## Known limits

- The filter lists are loaded in the background after the app starts (on a slow phone this took about half a
  minute). A page opened in that time is filtered by the built-in rules only; an ad can get through. The player
  works either way.
- A site that looks for a blocker by watching for failed requests can now see one where it saw an empty answer
  before. Ad blocking can be switched off from the browser menu; the page reloads without it.
