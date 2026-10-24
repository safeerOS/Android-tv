# Safeer Add-on Protocol — v1

<!-- PROTOKOL-VERZIJA: 1 -->

One protocol for Safeer add-ons on every device: Safeer OS for TV, tablet and phone (Android-tv), Safeer for Linux
(Safeer-linux) and Safeer for Windows (Safeer-windows). An add-on is written once and works on all of them. Each
repository carries an identical copy of this document and its own SDK (host support, developer mode, validator, example
add-on) in the language of that repository.

Licence of this document and of the SDKs: Apache-2.0.

## 1. What an add-on is

An add-on is an **HTTP(S) service that returns JSON**. It is a [Stremio add-on](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md)
(`manifest.json`, `catalog`, `meta`, `stream`, `subtitles`) with optional Safeer extensions under the `safeer` key of the
manifest. Every existing Stremio add-on therefore works in Safeer unchanged, and an add-on with Safeer extensions still
works in other Stremio clients (they ignore unknown keys).

**No add-on code ever runs on the user's device.** Safeer draws everything itself — buttons, folders, settings — from the
data the add-on returns. An add-on cannot inject scripts, HTML, styles or native code.

## 2. Manifest extension

```json
{
  "id": "org.example.lists",
  "version": "1.2.0",
  "name": "Example Lists",
  "resources": ["catalog", "meta", "stream"],
  "types": ["movie", "series"],
  "catalogs": [],
  "safeer": {
    "protocol": 1,
    "actions": [
      { "id": "add-to-list", "on": ["movie", "series"], "icon": "plus",
        "label": { "en": "Add to my list", "sl": "Dodaj na moj seznam" }, "confirm": false }
    ],
    "enrich": ["movie", "series"],
    "folders": [
      { "id": "my-account", "icon": "folder", "name": { "en": "My account", "sl": "Moj račun" } }
    ],
    "settings": [
      { "key": "api_key", "type": "secret", "required": true, "label": { "en": "API key", "sl": "Ključ API" } },
      { "key": "quality", "type": "choice", "options": ["auto", "1080p", "720p"], "default": "auto",
        "label": { "en": "Quality", "sl": "Kakovost" } },
      { "key": "only_cached", "type": "toggle", "default": false, "label": { "en": "Only instant", "sl": "Samo takoj" } }
    ],
    "changelog": { "1.2.0": { "en": "Folders", "sl": "Mape" } }
  }
}
```

| Field | Meaning |
|---|---|
| `protocol` | Integer, required. The lowest protocol version the add-on's extensions need. A host with an older protocol ignores **all** `safeer` extensions (the Stremio part keeps working) and tells the user to update Safeer. |
| `actions` | Up to 8 buttons on a content page. `id`: `[a-z0-9-]{1,40}`. `on`: content types (`movie`, `series`, `episode`, `tv`, `channel`, `radio`, `music`). `icon`: one of the fixed icon names (§6). `label`: text per language. `confirm`: ask the user before running. |
| `enrich` | Content types for which the page is shown at once and completed later by `enrich` (§3.2). |
| `folders` | Up to 4 folders the user can browse in Media Center (e.g. the user's own account at a service). |
| `settings` | Up to 20 fields Safeer draws in its own settings screen. `type`: `text`, `secret`, `toggle`, `choice`. `secret` values are stored encrypted on the device and are never shown again or logged. |
| `changelog` | Shown to the user once after the add-on updates (`version` of the manifest changed). |

Unknown fields inside `safeer` are ignored (forward compatibility). Invalid entries are dropped one by one; a broken
extension never disables the Stremio part of the add-on.

### Text

Every user-visible text is an object `{ "<language>": "<plain text>" }` (`en`, `sl`, `de`, `fr`, `it`, `es`, …) or a
plain string. Safeer picks the user's language, then `en`, then the first one. Text is plain (no HTML, no markup),
trimmed, at most 80 characters for labels and names, 500 for messages and change logs.

## 3. Endpoints

All endpoints are `GET` relative to the add-on base URL (the manifest URL without `/manifest.json`), as in Stremio.
`{type}` and `{id}` are URL-encoded.

### 3.1 Action — `/safeer/action/{actionId}/{type}/{id}.json`

```json
{ "ok": true, "message": { "en": "Added", "sl": "Dodano" }, "refresh": false }
```
`message` is shown as a short notice. `refresh: true` asks Safeer to reload the page (catalog, meta).

### 3.2 Enrich — `/safeer/enrich/{type}/{id}.json`

```json
{ "meta": { "imdbRating": "7.9", "language": "en", "cast": ["…"], "description": "…" } }
```
Same fields as a Stremio `meta` object. Safeer merges only fields it does not already have; it never replaces the
title, the id or the streams.

### 3.3 Folder — `/safeer/folder/{folderId}.json` and `/safeer/folder/{folderId}/{path}.json`

```json
{ "items": [
  { "kind": "folder", "path": "movies", "name": "Movies" },
  { "kind": "meta", "type": "movie", "id": "tt0111161", "name": "The Shawshank Redemption", "poster": "https://…" }
] }
```
`kind`: `folder` (opens `{path}`), `meta` (a normal content card; playback through `stream` as usual). At most 200 items
per response; `next` (a path) for more.

## 4. Settings sent to the add-on

Values are sent on every request in the header `X-Safeer-Config: <base64url(JSON object)>`, never in the URL, so they
do not end up in logs, history or the player. A request with `secret` values is only sent over HTTPS (developer mode:
also to a local address, §7). Safeer also sends `Accept-Language`. Nothing else about the user or the device is sent.

## 5. Limits and failures

- Response at most 1 MiB, connect timeout 10 s, read timeout 20 s. Larger or slower responses are dropped.
- `https://` required (images and links too), except a local address in developer mode.
- An add-on that fails is marked as not responding (with time and HTTP code) and retried later; its content is never
  shown as broken to the user — content that cannot play (dead channel, pay-wall only) is hidden (tasks 120, 121).
- Rate: Safeer limits its own requests per add-on and backs off on HTTP 429.

## 6. Icons

`plus`, `check`, `heart`, `star`, `list`, `folder`, `download`, `subtitles`, `info`, `share`, `refresh`, `lock`.
An unknown icon becomes `info`.

## 7. Developer mode (each SDK)

Install from a local address (`http://192.168.x.x:port/manifest.json`, `http://localhost:…`), a "Reload add-ons"
button that forgets cached manifests and locks, and a log of add-on responses without stream URLs or settings values.

## 8. Versions

- **v1** (this document): actions, enrich, folders, settings, changelog.

A new protocol version only adds fields; a host never breaks an add-on written for an older version.
