# Safeer TV Browser

A privacy-first web browser for Android TV, built to be driven with a remote control —
plus **Safeer OS**, a home screen for the same television, built from the same source.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_TV_9%2B-3ddc84?style=flat-square)](#requirements)
[![Downloads](https://img.shields.io/badge/Download-Releases-00e5ff?style=flat-square)](../../releases/latest)
[![Latest release](https://img.shields.io/github/v/release/safeerOS/Android-tv?style=flat-square&label=release)](../../releases/latest)

Slovenian: [README.sl.md](README.sl.md) · Website: [safeer.si](https://safeer.si)

---

## What it is

Television browsers are usually an afterthought: a phone browser with a cursor bolted on.
Safeer starts from the remote. Arrow keys move a visible focus ring between the things you
can actually click, OK opens them, and Back does what you expect. Video goes to the
television's own decoder instead of being squeezed through a web view.

It blocks ads and trackers, refuses known malware and phishing hosts, and asks no one for
permission to do it — everything runs on the device, and nothing is sent anywhere to make
these decisions.

## Current version

Everything below is built from branch `main` of this repository.

| App | Version | Package | Build flavour | Release |
|---|---|---|---|---|
| Safeer TV Browser | **2.1.215** | `si.safeer.tv` | `brskalnik` | [`v2.1.215`](https://github.com/safeerOS/Android-tv/releases/tag/v2.1.215) |
| Safeer OS — television home screen | **0.5.93** | `si.safeer.os` | `os` | same release as the browser |
| Safeer OS — tablet | **0.5.93** | `si.safeer.tablet` | `tablica` | same release as the browser |
| Safeer OS — phone | **0.5.93** | `si.safeer.phone` | `telefon` | same release as the browser |
| Safeer Player (Predvajalnik) | **0.2.77** | `si.safeer.player` | `predvajalnik` | [`predvajalnik-v0.2.77`](https://github.com/safeerOS/Android-tv/releases/tag/predvajalnik-v0.2.77) |

What changed: [CHANGELOG.md](CHANGELOG.md) · notes for every version: [docs/releases/](docs/releases/) ·
download: [safeer.si](https://safeer.si/#prenosi) or [GitHub Releases](../../releases).
The standalone phone browser (Safeer Browser for Android) lives in
[safeerOS/Mobile-android](https://github.com/safeerOS/Mobile-android); Linux and Windows in
[safeerOS/Safeer-linux](https://github.com/safeerOS/Safeer-linux) and
[safeerOS/Safeer-windows](https://github.com/safeerOS/Safeer-windows).

## Two apps, one source tree

| App | Package | Build flavour | Artifact |
|---|---|---|---|
| Safeer TV Browser | `si.safeer.tv` | `brskalnik` | `TV-Browser-2.apk` |
| Safeer OS (television home screen) | `si.safeer.os` | `os` | `Safeer-OS.apk` |

Android sees two separate applications, each with its own launcher entry, settings and
uninstall. They must be signed with the same key: the bridges between them are guarded by a
signature-level permission, which is how Safeer OS reads the shield state and receives web
apps from the browser without any network hop. Either app works on its own.

`tests/preveri_loceni_aplikaciji.py` enforces the separation on every build. The same tree also
builds Safeer OS for tablets and phones and Safeer Player (table above).

## What it does

**Remote-first navigation.** Directional keys move focus by geometry, not by the page's tab
order, so a grid of thumbnails behaves like a grid. Where a page gives our navigation nothing
to move to, the key falls through to the page itself — that is how you reach the buttons
inside a cookie dialog that lives in its own frame.

**Native DASH playback.** When the browser sees a DASH stream on a page, it hands the
manifest and the licence request to AndroidX Media3 ExoPlayer on a SurfaceView. On a
television this is the difference between stuttering and a clean picture. The rule is
generic: the manifest is recognised by the DASH standard, the licence endpoint by the usual
markers, and cookies and the `Referer`/`Origin` headers come from the page you are on.
Content protection is untouched — the licence is issued by the provider's server and
decrypted by Widevine; the browser only carries your own session forward.

**Ad and tracker blocking.** An EasyList-compatible engine plus a reverse-domain trie of
known ad, tracking and malware hosts. Cosmetic filtering hides what is left.

**Threat shield.** Botnet C2, malware and phishing hosts from abuse.ch (Feodo Tracker,
URLhaus, ThreatFox) and Phishing Army, matched locally in O(k). Lists are delivered as a
signed bundle; a bundle that fails its Ed25519 signature is never used.

**BankGuard.** Real banking and payment sites are exempt from cosmetic filtering and script
injection, so a filter can never be the reason a payment fails.

**Popup handling.** Popunders and fake system dialogs are neutralised. Sign-in windows are
not: a `window.open` whose destination is an OAuth or sign-in URL opens as a real tab and
keeps its `window.opener`, so signing in with Google, Facebook or X works.

**Privacy defaults.** `Sec-GPC: 1` and `DNT: 1` on every request, tracking parameters
(`utm_*`, `fbclid`, `gclid`, …) stripped from links, no telemetry.

**SponsorBlock** for YouTube, on by default and switchable in settings.

## No per-site recipes

Safeer contains no adaptation written for one named website. Everything above works by what a
page *is* — its markup, its stream format, its request pattern — not by who publishes it.
A guard (`tests/check_public_package.py`) runs on every build. It fails the build if
a new `site_<name>.js` appears, or if one of the adaptations we removed comes back under
its old name anywhere outside the bookmark lists.

This is a deliberate promise: the browser should work on your sites, not on ours.

## Usenet

- Safeer is a client, like a newsreader. You are responsible for your account with your Usenet provider, for the
  content you access and for using it lawfully.
- No NZB indexer is preset and Safeer bundles none. You add your own indexer (Newznab address and API key).
- Any NNTP provider works through one generic setting: server, port, TLS, username, password and number of
  connections.
- With the built-in Usenet engine everything happens on your device: Safeer connects only to your indexer and your
  NNTP provider (TLS by default). Nothing goes to a Safeer server or any other cloud.
- Safeer gives no recommendations about content or providers.

Status: the built-in Usenet engine (NNTP straight from your device) is in development and not yet in a release. Until
then Usenet plays through a debrid service you set up yourself - in that case the NZB file and playback go through
that service.

## Install

Download the APK from [Releases](../../releases/latest), allow installation from unknown
sources on the television, and open the file. Or, from a computer with ADB:

```bash
adb install -r safeer-browser-tv-<version>.apk
```

Verify what you downloaded against `SHA256SUMS` from the same release:

```bash
sha256sum -c --ignore-missing SHA256SUMS
```

## Requirements

Android TV 9 (API 28) or newer. Built against API 34.

## Build from source

```bash
./build_tv_apk.sh
```

Produces both signed APKs. Release signing uses `keystore/safeer-tv-release.jks` with the
password in `RELEASE_KEY_PASS` or `keystore/.release_pass`; that directory is git-ignored and
the key never leaves the maintainer's machine. Without it, build the debug variants with
Gradle.

Tests:

```bash
bash tests/run_threat_policy_tests.sh
```

## Contributing

Bug reports and patches are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Security issues
go through [SECURITY.md](SECURITY.md), privately, not in a public issue.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
