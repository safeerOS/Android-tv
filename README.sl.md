# Safeer TV Browser

Brskalnik za Android TV, ki postavlja zasebnost na prvo mesto in je narejen za upravljanje z
daljincem — poleg njega pa **Safeer OS**, domači zaslon za isti televizor, zgrajen iz iste kode.

[![Licenca](https://img.shields.io/badge/Licenca-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Platforma](https://img.shields.io/badge/Platforma-Android_TV_9%2B-3ddc84?style=flat-square)](#zahteve)
[![Prenos](https://img.shields.io/badge/Prenos-Izdaje-00e5ff?style=flat-square)](../../releases/latest)

English: [README.md](README.md) · Spletna stran: [safeer.si](https://safeer.si)

---

## Kaj je

Brskalniki na televizorjih so običajno postranska stvar: telefonski brskalnik s prilepljenim
kazalcem. Safeer izhaja iz daljinca. Smerne tipke premikajo viden obroč med stvarmi, ki jih je
res mogoče klikniti, OK jih odpre, Nazaj naredi tisto, kar pričakuješ. Video dobi televizorjev
lastni dekodirnik, namesto da bi ga stiskali skozi spletni pogled.

Blokira oglase in sledilce, zavrne znane strežnike zlonamerne programske opreme in lažnega
predstavljanja — vse to teče na napravi, za te odločitve se nikamor ne pošlje nič.

## Dve aplikaciji iz ene kode

| Aplikacija | Paket | Okus gradnje | Rezultat |
|---|---|---|---|
| Safeer TV Browser | `si.safeer.tv` | `brskalnik` | `TV-Browser-2.apk` |
| Safeer OS (domači zaslon televizorja) | `si.safeer.os` | `os` | `Safeer-OS.apk` |

Android ju vidi kot ločeni aplikaciji, vsako s svojim vnosom v zaganjalniku, nastavitvami in
odstranitvijo. Podpisani morata biti z istim ključem: mostovi med njima so zaščiteni z
dovoljenjem na ravni podpisa, zato Safeer OS bere stanje ščita in prejme spletno aplikacijo od
brskalnika brez kakršnegakoli obhoda prek omrežja. Vsaka deluje tudi sama.

`tests/preveri_loceni_aplikaciji.py` to ločitev preveri ob vsaki gradnji.

## Kaj zna

**Navigacija za daljinec.** Smerne tipke premikajo fokus po geometriji in ne po vrstnem redu
strani, zato se mreža sličic obnaša kot mreža. Kadar naša navigacija nima kam, tipka pade
naprej strani sami — tako prideš do gumbov v oknu o piškotkih, ki živi v svojem okvirju.

**Nativno predvajanje DASH.** Ko brskalnik na strani zazna pretok DASH, preda manifest in
zahtevo za licenco predvajalniku AndroidX Media3 ExoPlayer na SurfaceView. Na televizorju je to
razlika med zatikanjem in čisto sliko. Pravilo je splošno: manifest prepoznamo po standardu
DASH, licenčni naslov po običajnih oznakah, piškotki in glavi `Referer`/`Origin` pa pridejo z
odprte strani. Zaščita vsebine ostane nedotaknjena — licenco izda ponudnikov strežnik in
dešifrira Widevine; brskalnik samo prenese tvojo lastno sejo naprej.

**Blokada oglasov in sledilcev.** Filter, združljiv z EasyList, in obrnjeno domensko drevo
znanih oglasnih, sledilnih in zlonamernih gostiteljev. Kozmetični filter skrije, kar ostane.

**Ščit pred grožnjami.** Botnetni C2, zlonamerna programska oprema in lažno predstavljanje iz
virov abuse.ch (Feodo Tracker, URLhaus, ThreatFox) in Phishing Army, ujeti lokalno v O(k).
Seznami pridejo kot podpisan paket; paket, ki ne prestane podpisa Ed25519, se nikoli ne uporabi.

**BankGuard.** Prave bančne in plačilne strani so izvzete iz kozmetičnega filtriranja in
vbrizgavanja skript, zato filter nikoli ne more biti razlog, da plačilo ne uspe.

**Pojavna okna.** Popunderji in lažna sistemska opozorila so nevtralizirani. Prijavna okna niso:
`window.open`, katerega cilj je naslov OAuth ali prijave, se odpre kot pravi zavihek in obdrži
`window.opener`, zato prijava z Googlom, Facebookom ali X deluje.

**Privzeta zasebnost.** `Sec-GPC: 1` in `DNT: 1` pri vsaki zahtevi, sledilni parametri
(`utm_*`, `fbclid`, `gclid`, …) odstranjeni iz povezav, brez telemetrije.

**SponsorBlock** za YouTube, privzeto vklopljen in izklopljiv v nastavitvah.

## Brez receptov za posamezne strani

Safeer ne vsebuje prilagoditve, napisane za eno imenovano spletno stran. Vse našteto deluje po
tem, KAJ stran je — njena oznaka, oblika pretoka, vzorec zahtev — in ne po tem, kdo jo objavlja.
Varuh (`tests/check_public_package.py`) teče ob vsaki gradnji. Ustavi jo, če se pojavi nova
datoteka `site_<ime>.js` ali če se katera od odstranjenih prilagoditev vrne pod svojim starim
imenom kjerkoli zunaj seznamov zaznamkov.

To je namerna obljuba: brskalnik naj dela na tvojih straneh, ne na naših.

## Namestitev

Prenesi APK iz [Izdaj](../../releases/latest), na televizorju dovoli namestitev iz neznanih
virov in odpri datoteko. Ali z računalnika prek ADB:

```bash
adb install -r safeer-browser-tv-<verzija>.apk
```

Preveri, kaj si prenesel, s `SHA256SUMS` iz iste izdaje:

```bash
sha256sum -c --ignore-missing SHA256SUMS
```

## Zahteve

Android TV 9 (API 28) ali novejši. Grajeno proti API 34.

## Gradnja iz izvorne kode

```bash
./build_tv_apk.sh
```

Zgradi oba podpisana APK-ja. Za podpis izdaje se uporabi `keystore/safeer-tv-release.jks` z
geslom v `RELEASE_KEY_PASS` ali `keystore/.release_pass`; ta mapa je v `.gitignore` in ključ
nikoli ne zapusti vzdrževalčevega računalnika. Brez njega zgradi razhroščevalne različice z
Gradlom.

Testi:

```bash
bash tests/run_threat_policy_tests.sh
```

## Sodelovanje

Poročila o napakah in popravki so dobrodošli — glej [CONTRIBUTING.md](CONTRIBUTING.md).
Varnostne težave gredo po poti iz [SECURITY.md](SECURITY.md), zasebno in ne v javno prijavo.

## Licenca

Apache License 2.0 — glej [LICENSE](LICENSE).
