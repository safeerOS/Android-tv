# Safeer Add-on SDK — Android (Safeer OS for TV, tablet and phone)

Build add-ons for Safeer Media Center. The protocol is the same on every Safeer device —
[`docs/DODATKI-PROTOKOL.md`](../docs/DODATKI-PROTOKOL.md) (v1) — so an add-on written once works on Safeer OS for Android,
Safeer for Linux and Safeer for Windows. Each of those repositories ships its own SDK; this one is for Android.

Licence: Apache-2.0.

An add-on is a **web service that returns JSON** (a Stremio add-on plus the optional `safeer` extension). No add-on code
runs on the user's device. Any language and any host works.

## What is here

| Path | What |
|---|---|
| `primer/PrimerDodatka.kt` | Example add-on (JDK only): catalog, meta, stream, plus an action, enrich, a folder and settings. Copy it as a start. |
| `orodja/PreveriDodatek.kt` | Validator: checks your add-on with the same code the app uses and tells you what Safeer will do with it. |
| `zazeni_primer.sh` | Runs the example add-on. |
| `preveri_dodatek.sh` | Runs the validator. |
| `../docs/dodatki-protokol-v1.schema.json` | JSON Schema of the `safeer` manifest key. |

Requirements: Java 17 and `kotlinc` (set `KOTLINC=/path/to/kotlinc` if it is not on `PATH`).

## 1. Run the example

```bash
sdk/zazeni_primer.sh 7000
```

It listens on all interfaces: `http://<this computer's IP>:7000/manifest.json`.

## 2. Validate an add-on

```bash
sdk/preveri_dodatek.sh http://localhost:7000/manifest.json --vsebina movie:primer:film1 --dejanja
```

Lines are `OK`, `OPOZORILO` (warning) or `NAPAKA` (error); the exit code is 1 when there is an error. The validator checks
the manifest, the `safeer` extension (every dropped entry with its reason), folders, streams of the given content
(`--vsebina type:id`: playable, pay-wall only — Safeer hides it — or none), enrich, and actions only with `--dejanja`
(actions may change something at your service). It also checks the limits Safeer applies: 1 MiB, 20 s, valid JSON.

## 3. Try it on a device (developer mode)

1. Safeer → Media Center → **My sources** → long-press **Add-ons** → *Turn on add-on developer mode*.
2. **Add-ons** → enter `http://<your computer's IP>:7000/manifest.json`.
3. The **Add-on development** card in My sources: *Reload add-ons* (forgets cached manifests and locks, no reinstall)
   and *Add-on log* (time, host, request kind, HTTP code, size, duration — never paths, stream links or settings).
4. Long-press **Add-ons** again to turn developer mode off.

In developer mode the extension endpoints may use `http://` on a local address. Settings of type `secret` are never sent
over plain http, not even in developer mode — test them over https.

## 4. Publish

Host the add-on on **https**, share the manifest URL. Users add it under My sources → Add-ons (or with an install
button on your page). Change `version` in the manifest and add a `changelog` entry — users see it once after the update.
