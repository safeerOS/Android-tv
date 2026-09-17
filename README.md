# Safeer TV Browser (Android TV)

**Trenutna različica: 2.1.103** (Safeer OS 0.2.3) — *Varnejši na spletu.* Vgrajen W3C Global Privacy Control (GPC), Do Not Track (DNT), kirurško čiščenje sledilnih parametrov (UrlSanitizer), zaščita pred Botnet C2 strežniki (abuse.ch Feodo Tracker / URLhaus), Xplore TV kiosk z nativnim Media3 ExoPlayerjem (DASH + Widevine) ter HydraHD D-Pad navigacija.

## Dve aplikaciji iz ene kode

Ta repozitorij zgradi **dva** APK-ja, ki ju Android vidi kot dve ločeni aplikaciji — vsaka s svojim
imenom, ikono, vnosom v zaganjalniku, nastavitvami in odstranitvijo:

| Aplikacija | Paket | Okus (`build.gradle`) | Rezultat gradnje |
|---|---|---|---|
| Safeer TV Browser | `si.safeer.tv` | `brskalnik` | `TV-Browser-2.apk` |
| Safeer OS (domači zaslon za televizor) | `si.safeer.os` | `os` | `Safeer-OS.apk` |

Skupna koda je v `src/`, kar aplikaciji loči, pa je v `okusi/brskalnik/AndroidManifest.xml` in
`okusi/os/AndroidManifest.xml`: zaganjalniški vnosi, alias domačega zaslona (`ZaganjalnikAlias`,
privzeto onemogočen) in ponudnik spletnih aplikacij. Brskalnik nima zaslonov Safeer OS, Safeer OS
pa nima vnosa brskalnika.

Obe aplikaciji **morata biti podpisani z istim ključem**: mostovi med njima so zaščiteni z
dovoljenjem istega podpisa (`si.safeer.tv.permission.LINK`). Prek njih Safeer OS dobi žeton
Safeer Linka od brskalnika (brez druge povezave), bere in preklaplja Safeer Ščit (en filter na
televizorju), brskalnik pa preda spletno aplikacijo na domači zaslon Safeer OS
(`content://si.safeer.os.spletne`). Brez brskalnika Safeer OS vse to opravi sam.

`tests/preveri_loceni_aplikaciji.py` preveri, da ločitev drži (imeni paketov, po en vnos v
zaganjalniku, alias samo v Safeer OS, isti podpis); teče v `build_tv_apk.sh` in v CI.

## Prenos in namestitev APK

| Kaj | Povezava |
|---|---|
| GitHub | https://github.com/memelandfaner/Safeer-TV-Browser |
| APK (neposredno) | https://github.com/memelandfaner/Safeer-TV-Browser/raw/main/TV-Browser-2.apk |
| Kratka povezava (APK) | https://tinyurl.com/27w3uxob |
| Kratka povezava (da.gd) | https://da.gd/8fziT |
| 1-vrstica (PC → TV prek ADB) | `curl -sL https://raw.githubusercontent.com/memelandfaner/Safeer-TV-Browser/main/install_tv_browser.sh \| bash` |

Na TV dovoli namestitev iz neznanih virov, nato odpri preneseni `TV-Browser-2.apk`.

Ista datoteka je tudi v `Release/Artifacts/tv-browser-2-release.apk`.

Imeni datotek sta se iz časov, ko se je brskalnik imenoval drugače. Ostajata nespremenjeni, ker nanju kažeta kratki povezavi zgoraj in povezava za prenos na spletni strani; izdelek sam se imenuje **Safeer TV Browser**.

Paket: `si.safeer.tv` (do različice 2.1.85 `com.example.safeerbrowser` iz predloge; sprememba imena pomeni, da je treba starejšo različico odstraniti in novo namestiti na novo – nastavitve in zaznamki iz stare različice se ne prenesejo). Gradnja: `./build_tv_apk.sh`; APK podpiše produkcijski ključ `keystore/safeer-tv-release.jks` (geslo v `RELEASE_KEY_PASS` ali `keystore/.release_pass`; mapa je v `.gitignore` in ključ nikoli ne zapusti računalnika).

```bash
adb install -r TV-Browser-2.apk
```

## 🛡️ Varnost in Zasebnost (Varnejši na spletu)
1. **W3C Global Privacy Control & Do Not Track**: Avtomatsko posredovanje `Sec-GPC: 1` in `DNT: 1` ter injiciranje v brskalniški `navigator` objekt ob zagonu vseh spletnih strani.
2. **Čiščenje sledilnih parametrov (UrlSanitizer)**: Avtomatsko odstranjevanje nadzornih identifikatorjev (`utm_*`, `fbclid`, `gclid`, `msclkid`, `twclid`, `mc_eid` itd.) pri vseh povezavah.
3. **Botnet C2 & Malware ščit**: Integracija $O(k)$ drevesa z bazo znanih nevarnih domen (abuse.ch Feodo Tracker, URLhaus, ThreatFox, Phishing Army).
4. **Zaščita pred ugrabitvijo oken**: Popolna nevtralizacija neželenih popunder oken in lažnih sistemskih opozoril.

## 📺 HydraHD (daljinec)

Na `hydrahd.ws` D-Pad **ne** uporablja Chromiumove izvorne prostorske navigacije. Polja so `button.slidebtn` (Watch Now), `div.tab` (Movies/Series) in `a.hthis` (plakati z `height:0`). JS v `assets/tv_spatial.js` označi samo kartice, ne logotipa, logina ali puščic carousela.

- Začetna stran: Watch Now → Movies → prvi plakat → levo/desno po vrsti.
- Film / serija: Predvajaj, nato sezone in epizode.
- Zeleni gumb na daljincu vklopi kazalec, če stran nima pravih polj.

Orodna vrstica brskalnika je skrita samo na predvajalniku (`/movie/`, `/tv/`, `/watch`).

## 📡 Xplore TV

Katalog, prijava in EPG ostaneta v WebView. Video je **AndroidX Media3** na SurfaceView, ne Castlabs Android SDK. Podrobnosti: skill `tv-browser-2-xplore-drm`.

Xplore gesel **ne** committaj. Lokalno: `xplore_auth.local.js` (glej `xplore_auth.local.js.example`).

## 🎮 Daljinec

- D-Pad: prostorska izbira polj (cyan obroč)
- GOR na vrhu strani → URL vrstica (razen Xplore kiosk / Hydra predvajalnik)
- RDEČA / MENI → portali
- ZELENA → kazalec
- RUMENA → zaznamki
- BACK med Xplore predvajanjem zapre Exo, ne `history.back()`

---

## ⚖️ Licenca
Projekt je izdan pod licenco [Apache License 2.0](LICENSE).
