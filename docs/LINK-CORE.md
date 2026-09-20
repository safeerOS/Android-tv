# Safeer Link Core — prenova za več gostiteljev

Veja `prenova-link` (varnostna kopija; `main` ostane pri izdaji 2.1.117 / OS 0.4.0). Vrstni red dela:

1. meritev, kaj danes teče neposredno (spodaj, opravljeno 20. 9. 2026);
2. skupno zaupanje: ključ vsake naprave in seznam seznanjenih pri vseh;
3. izločitev Safeer Link Core iz brskalnika na televizorju v samostojno komponento;
4. izvolitev huba;
5. Protocol v1: model naprave, zmožnosti, katalog aplikacij;
6. Linux in Android kot providerja, nato Safeer OS za Linux, pozneje Windows.

Pravilo skozi vse korake: **seznanitve, ki jih uporabnik ima danes, prehod preživijo** — nobena naprava
ne sme znova zahtevati kode samo zato, ker smo zamenjali kodo ali hub.

## 1. Kaj je danes neposredno in kaj gre skozi hub (izmerjeno)

Preverjeno v kodi (`os/ZaslonOdjemalec.kt`, `os/DatotekeActivity.kt`, `lms/core/link_zaslon.py`,
`lms/core/link_datoteke.py`, `cast/HubTokovi.kt`, telefon `cast/HubTokovi.kt`, `link/PrenosDatotekeStoritev.kt`)
in v živo na tablici SM-X210 → računalnik (20. 9. 2026, 01:36): seja 1920×1080 pri 60 sl/s, ~4 Mb/s,
zvok 48 kHz; dnevnik `SafeerOsZaslon`.

| Seja | Pot | Kako |
|---|---|---|
| Zaslon računalnika → TV / tablica | **neposredno** | `screen.start` gre po hubu; Control odpre naključna vrata s svojim TLS potrdilom, vrne `port`, `fp`, `token`; odjemalec odpre surov TCP + TLS s pripetim odtisom na naslov računalnika. Slika (H.264), zvok (PCM) in vnos tečejo po tej povezavi. |
| Programi računalnika → TV / tablica | **neposredno** | ista pot kot zaslon (`screen: apps`). |
| Datoteke računalnika → TV / tablica | **neposredno** | `files.list` po hubu; odgovor prinese `base_url`, `fp`, `token`; prenos, predvajanje in slike gredo naravnost z računalnika (`/d/<id>`). |
| Telefon → TV: datoteka | **skozi hub** | telefon `PUT /cast/file` na hub, hub obvesti cilj (`share.file`), cilj `GET /cast/file/<id>` s huba. |
| Telefon → TV: zaslon telefona | **skozi hub** | telefon `POST /cast/screen/<id>` (JPEG okvirji) na hub, TV gleda MJPEG s huba. |
| Stran, besedilo, daljinec, ukazi | **skozi hub** | majhna sporočila po WebSocketu huba (`share.text`, `cast.url`, `control.*`). Ostane tako: hub je za to pravo mesto (odkrivanje, seznanitev, dogovor o seji). |

Sklep za Protocol v1: **način, ki ga ima danes Control (ponudnik odpre vrata s pripetim potrdilom in
enkratnim žetonom, hub prenese samo dogovor), postane pravilo za vse tokove**, tudi za pošiljanje datotek in
zaslona s telefona. Nov P2P sistem ni potreben. Telefon dobi isti ponudniški del (vrata + potrdilo + žeton),
kot ga ima Control; hub relej `/cast/file` in `/cast/screen` ostane samo kot rezerva za stare telefone.

## 2. Zaupanje danes in po prenovi

Danes je zaupanje **hubovsko**: naprava ima `id` iz modela (npr. `tv-ph1m_ea_9970a-os`), hub ji ob
seznanitvi (SPAKE2 s kodo z zaslona) izda žeton, naprava si zapomni `odtis huba → žeton`
(`Seznanitve.kt`, telefon isto, Linux `seznanitve` v nastavitvah). Hub hrani seznam žetonov.
Posledica: drug hub = druga seznanitev za vsako napravo. Naprave si med seboj ne zaupajo, zaupajo hubu.

Po prenovi je zaupanje **medsebojno**:

- **Ključ naprave.** Na Androidu je to ključ EC P-256 v AndroidKeyStore, ki ga `HubTls` že dela za TLS
  potrdilo huba (strojno varovan, nikoli ne zapusti KeyStore; `HubTls.javniKljucB64()`, `HubTls.podpisi()`);
  na Linuxu ključ EC P-256, ki ga Safeer Control že dela za svoje TLS potrdilo (`core/link_datoteke.py`,
  openssl; podpis prek `openssl dgst`, brez nove odvisnosti - `core/link_krog.py`). Obstoječe naprave
  obdržijo svoj `device_id` (krog veže id na ključ);
  nove naprave dobijo id iz javnega ključa (`KrogZaupanja.idIzKljuca`, `n-` + 16 hex SHA-256).
- **Krog zaupanja** (`krog.json`): seznam `{device_id, pubkey, ime, platforma, dodano, dodal}` in
  nadgrobnikov `{device_id, umaknjeno, umaknil}`. Vsaka naprava hrani cel krog. Združevanje je unija po
  `device_id`, umik (nadgrobnik) ima prednost pred vnosom, novejši zapis pred starejšim.
- **Seznanitev** ostane taka, kot je (koda na zaslonu gostitelja, SPAKE2), le da si v zaščitenem kanalu
  strani izmenjata javna ključa in nova naprava dobi cel krog. Hub krog razpošlje vsem povezanim
  (`trust.update`); naprava, ki se poveže pozneje, ga dobi ob prijavi.
- **Prijava na hub** brez žetona: hub pošlje `nonce`, naprava ga podpiše s svojim ključem; hub preveri
  podpis proti krogu. Hubovo TLS potrdilo mora nositi hubov javni ključ iz kroga (naprava preveri, da
  javni ključ v potrdilu = ključ huba v krogu). Tako **kateri koli član kroga lahko postane hub** in
  nobena naprava ne rabi nove kode.
- **Neposredne seje** (zaslon, datoteke): ponudnik namesto enkratnega žetona po hubu lahko zahteva podpis
  odjemalca; v v1 ostane obstoječi žeton (deluje in je preverjen), podpis pride, ko bo hub že zamenljiv.

### Prehod: seznanitve preživijo

1. Nadgrajen hub (TV) sprejema oba načina: **žeton** (star) in **podpis** (nov).
2. Naprava, ki se po nadgradnji prvič poveže s starim žetonom, v isti seji pošlje svoj javni ključ
   (`trust.enroll`). Hub jo, ker je žeton veljaven, doda v krog in ji vrne krog. Od takrat naprej se
   prijavlja s podpisom. Uporabnik ne vidi nič.
3. Hub sam je prvi član kroga (svoj ključ naredi ob nadgradnji); njegov obstoječi seznam žetonov
   (`seznanjeneNaprave()`) je vir za `dodal`.
4. Nenadgrajene naprave delajo naprej z žetonom, dokler jih uporabnik ne posodobi; hub žetone
   odstrani šele, ko je vsak njihov lastnik v krogu.

### Stanje (20. 9. 2026, veja `prenova-link`)

Narejeno na TV: `cast/KrogZaupanja.kt` (krog, združevanje, podpisi; JVM), `cast/KrogNaprave.kt`
(krog na odjemalcu), hub: `POST /cast/trust/enroll` (z žetonom vpiše ključ), `POST /cast/auth/challenge`
in `POST /cast/auth/ticket` (prijava s podpisom, podpis vezan na odtis huba in enkratni izziv),
`GET /cast/trust/ring`, sporočilo `trust.update` vsem ob prijavi in ob vsaki spremembi; Safeer OS
(`LinkOdjemalec`) se ob prvem stiku vpiše z žetonom, potem prihaja s podpisom, ob neuspehu pade nazaj
na žeton. Preizkušeno v živo na Philips TV: prehod brez kode. Preizkusi v `tests/UsmerjevalnikTest.kt`
(`preizkusKroga`). Še ne: brskalnik TV kot odjemalec tujega huba, telefon, Linux Control (koda in
prijava s podpisom), vezava `device_id` v `cast.register` na ključ (pride s korakom 3).

**Popravek (20. 9., pozneje):** JVM preizkus kroga, pognan zares (kotlinc v zabojniku), je pokazal, da
hub *vsak* podpis zavrne: `KrogZaupanja.preveriPodpis(id, …)` je zaradi enakega podpisa metode klical
samega sebe namesto funkcije spremljevalca (ključ kot id → ni člana → 401). Safeer OS je zato v resnici
vedno padel nazaj na žeton, česar dnevnik ni razkril. Popravljeno (`preveriPodpisSKljucem`), poleg tega
`umakni` neznane naprave ne pušča nadgrobnika in `json()` je urejen po id (isti krog = isti niz na vsaki
napravi). `tests/run_usmerjevalnik_tests.sh` zdaj gre skozi (46 preverb kroga). Nauk: JVM preizkuse
poganjati lokalno, ne čakati na CI.

**Linux (`safeer-lms`, veja `prenova-link`):** `core/link_krog.py` (isti zapis in pravila kot
`KrogZaupanja.kt`, ključ = Controlov TLS ključ), `core/link_hub.py`: `vzemi_vstopnico_s_podpisom`
(izziv → podpis → vstopnica) in `vpisi_v_krog` (z žetonom vpiše ključ); `Povezava._odpri` gre najprej s
podpisom, sicer z žetonom in se vpiše; `trust.update` se shrani v `~/.config/safeer-browser/krog.json`.
Brskalnik in Control na istem računalniku imata isti ključ in isti krog, a različna id-ja (`pc-x`,
`pc-x-control`) - krog to dopušča (id → ključ), ob prehodu na id iz ključa (korak 3/5) bo treba enega od
njiju označiti kot sorodnika. Preizkusi: `tests/test_link_krog.py` (brez huba) in
`tests/test_link_krog_zivo.py` (proti pravemu hubu; prva prijava z žetonom vpiše ključ, druga gre s podpisom).

## 3. Izločitev Link Core iz brskalnika (narejeno, 20. 9.)

Hub je bil v `si.safeer.tv.cast.*` znotraj brskalnika TV in podvojen (z zamikom: brez kroga, brez
`pair/cancel`) na telefonu v `com.safeer.mobile.browser.cast.*`. Ker telefon gradi s kotlinc brez Gradla,
bi Gradle modul zahteval prenovo obeh gradenj; izbrana je enostavnejša pot z istim učinkom:

- **En vir**: `tv-browser-2/src/main/kotlin/si/safeer/tv/cast/` (12 datotek brez odvisnosti od aplikacije:
  `HubDiscovery`, `HubObjava`, `HubPairing`, `HubStreznik`, `HubTls`, `HubTokovi`, `HubUsmerjevalnik`,
  `JsonLahki`, `KrogNaprave`, `KrogZaupanja`, `Seznanitve`, `Spake2`). Krmilnik, storitev in
  sprejemnik/odjemalec ostanejo v vsaki aplikaciji svoji (TV: `HubKrmilnik`, `HubStoritev`,
  `CastReceiverService`, `LinkSorodnikStoritev`; telefon: `HubKrmilnik`, `HubStoritev`, `CastSenderClient`).
- **Kopija na telefonu** nastane samo s `tools/link-core-sync.sh` (zamenja paket, doda glavo). Gradnja
  telefona (`build_mobile_apk.sh`) požene `--preveri` in pade, če se kopija razlikuje od vira; spremembe
  se torej ne morejo več razit. Safeer OS in tablica sta okusa istega Gradle projekta in vir delita že zdaj.
- **Vezava `device_id` na žeton/ključ** (`cast.register`): vstopnica za WebSocket, izdana z žetonom ali s
  podpisom, je vezana na napravo (`izdajVstopnico(deviceId)`, `vezaneVstopnice`); povezava, odprta s tako
  vstopnico, se sme prijaviti samo pod tem `device_id` (sicer `napacen_device_id`). Povezava brez vezane
  vstopnice (preizkusi, stari tok) se prijavi po starem. Preverjeno: JVM (302 preverb) in v živo - TV
  sam (receiver), Safeer OS, tablica (`tv-sm-x210-os`, po vpisu ključa) in Linux Control se prijavijo
  z vezano vstopnico in dobijo `accepted` (`tests/test_link_naprave_zivo.py`).
- Hub na telefonu zdaj pozna krog (isti vir) in ob zagonu vpiše svoj ključ (`vpisiLastniKljuc(...,
  "phone")`). Preizkus telefona kot huba čaka na Matejev telefon (testni ni seznanjen).

Odprto za korak 5: id naprave iz ključa (`n-…`) za nove naprave in oznaka sorodnika (brskalnik + Control
na istem računalniku, TV + OS na istem televizorju delijo ključ, a imajo ločene id-je).

## 4. Izvolitev huba (narejeno, 20. 9.)

- Vsaka naprava prek mDNS objavi `hub_priority` in `device_id`.
- Hub je naprava z najvišjo prioriteto; pri enaki odloči `device_id` (leksikografsko manjši). Vsi pridejo
  do istega rezultata, brez pogovora, brez glasovanja, brez skupne baze.
- Če se pojavita dva huba (po izpadu omrežja), ostane tisti z višjo prioriteto, drugi se umakne in se
  poveže kot odjemalec.
- Privzete prioritete: strežnik brez GUI (`safeer-core`, pozneje) 100, Linux računalnik 80, TV 60,
  tablica 40, telefon 20. Uporabnik jih lahko spremeni v nastavitvah.
- Hub dela samo odkrivanje, katalog naprav, zmožnosti in dogovor o seji; mediji tečejo neposredno (1.), zato
  seja preživi menjavo huba.

### Kako je narejeno

- `cast/IzvolitevHuba.kt` (jedro, JVM, `tests/IzvolitevTest.kt`): prioritete strežnik 100 / Linux 80 / TV 60 /
  tablica 40 / telefon 20 (`privzetaPrioriteta`), `jePred` (višja prioriteta, pri enaki manjši id),
  `komuSeUmaknem(jaz, videni)`. Oglas mDNS nosi `prio` in `id` (`HubObjava`), `HubDiscovery.poisciVse` zbere
  vse žive hube brez spreminjanja nastavitev.
- `HubKrmilnik` (TV/tablica): po zagonu huba čez 1,5 s izvolitev, nato vsakih 90 s; kandidati so samo člani
  kroga zaupanja (tuj oglas nima glasu). Umik: lastni hub ugasne, `izvoljeni_hub_*` v nastavitvah,
  sprejemnik (`CastReceiverService`) gre na izvoljeni hub s **podpisom ključa** (`/cast/auth/challenge` →
  `/cast/auth/ticket`); zaupanje potrdilu: odtis iz oglasa **in** javni ključ v potrdilu = ključ tega člana v
  krogu (`HubTls.Zaupnik(pripeti, pripetiKljuc)`). Sorodniki (Safeer OS, tablica) dobijo prek
  `LinkSorodnikStoritev`/`Sorodnik` poverilnice izvoljenega huba brez žetona in se prijavijo s podpisom.
  Ko izvoljeni hub izgine (3 neuspehi sprejemnika), naprava spet gosti sama (`izvoljeniHubIzgubljen`),
  če je uporabnik Link prižgal.
- **Alias v krogu** (`POST /cast/trust/alias`): ista naprava ima več id-jev z istim ključem (TV brskalnik +
  Safeer OS, tablica + njen zaslon, Control + brskalnik). Član z izzivom za znani id podpiše in vpiše svoj
  drugi id z istim ključem; drug ključ pod tem id je 409. Tako je tablica po umiku na TV-ju prisotna kot
  `tv-sm-x210-os` (sender) in `tv-sm-x210` (receiver).
- Linux (`safeer-lms`): `poisci_hube_mdns` bere `id`; `poisci_hub_z_odtisom` prepozna hub iz kroga
  (`link_tls.potrdilo_huba` → ključ v potrdilu = ključ člana; `krog=True`), `safeer_link` se nanj poveže brez
  žetona s podpisom (`link_krog.je_vpisan`). Preizkus `tests/test_link_izvolitev.py`.
- Telefon: hub oglaša `prio=20` in `id`; izvolitve (umika) telefon še ne izvaja - telefon gosti le, ko ni
  nikogar drugega, in TV/tablica se telefonu ne umakneta (nižja prioriteta).

### Preverjeno v živo (tablica ↔ TV)

1. Tablica v domačem načinu zažene svoj hub (40), zagleda TV (60), se umakne, njen OS-odjemalec in zaslon
   se prijavita na TV s podpisom (zaslon prek aliasa). `/cast/devices` na TV: TV, Control, `tv-sm-x210-os`,
   `tv-sm-x210`.
2. TV ugasne: tablica po ~7 s spet gosti sama; OS-odjemalec se poveže na lastni hub s podpisom.
3. TV se vrne: TV gosti (60), tablica se ob naslednji izvolitvi (≤ 90 s) spet umakne.

Odprto: izvolitev na telefonu (umik) in `safeer-core` brez GUI (prioriteta 100) - korak 6.

## 5. Protocol v1: model naprave in katalog aplikacij (narejeno, 20. 9.)

Protokol ostaja 0.2 v obliki (ista sporočila, isti `cast.register`); v1 doda **polja**, ki jih hub 0.2
prezre in odjemalec 0.2 ne pošilja. Nič se ne podre v nobeni smeri - stari in novi se mešajo.

### Model naprave (`cast.register.payload`)

| polje | pomen | vrednosti |
|---|---|---|
| `protocol` | različica protokola odjemalca | `"1.0"` (`HubUsmerjevalnik.PROTOKOL_V1`) |
| `platform` | platforma | `tv`, `tablet`, `phone`, `linux`, pozneje `windows`, `server` |
| `kind` | kaj je ta odjemalec | `screen` (zaslon, ki lahko gosti hub), `os` (Safeer OS), `handheld` (telefon), `computer` (brskalnik na računalniku), `control` (Safeer Control) |
| `version` | različica aplikacije (`versionName` / `APP_VERSION`) | niz |
| `priority` | prioriteta pri izvolitvi huba - pošlje le, kdor lahko gosti hub | 1-1000 |
| `apps` | katalog aplikacij naprave (glej spodaj) | objekt |

Hub polja shrani ob napravi (`Naprava.protokol/platforma/vrsta/razlicica/prioriteta/aplikacije`, omejene
dolžine) in jih vrne v `cast.devices` in `GET /cast/devices` **samo, kadar jih naprava pove** - seznam za
odjemalce 0.2 je nespremenjen.

### Katalog aplikacij

`apps` je objekt po id-ju aplikacije: `{"<id>": {"name": "...", "kind": "...", "icon": "..."?}}`.
Hub vsebine ne razlaga; hrani največ `NAJVEC_APLIKACIJ` (200) vnosov in `NAJVEC_KATALOG_BAJTOV` (32 KiB),
imena ≤ 64 znakov, `icon` ≤ 256 znakov (torej URL ali ime, ne slika - ikone daljinec vzame z ukazom `apps`).
Kar meje presega, hub zavrže (`preveriKatalog`). Katalog gre ob prijavi (`apps` v `cast.register`) ali
naknadno s sporočilom **`apps.announce`** `{"type":"apps.announce","payload":{"apps":{…}}}`; hub odgovori
`apps.ack` (`accepted`, ali `rejected` z `ni_prijavljena`/`manjka_apps`) in ob spremembi vsem razpošlje nov
`cast.devices`. Ista objava dvakrat ne razpošilja.

### Kdo kaj pošlje

- TV/tablica zaslon (`CastReceiverService`): `screen`, prioriteta (`HubKrmilnik.prioriteta`), `apps` =
  aplikacije, ki jih zaslon zna zagnati (`Daljinec.katalog`, `kind: "android"`, po imenu paketa - isti id,
  kot ga sprejme ukaz `launch_app`).
- Safeer OS (`LinkOdjemalec`): `os`, brez prioritete (hub gosti zaslon).
- Telefon (`CastSenderClient`): `handheld`, platforma `phone`, brez prioritete.
- Linux (`link_hub.model_naprave_v1`): `linux`, `control` za Safeer Control (id `…-control`) oz. `computer`
  za brskalnik; brez prioritete, dokler računalnik huba ne gosti (korak 6).

Preizkusi: JVM `tests/UsmerjevalnikTest.kt` (`preizkusProtokolaV1`: prijava v1, seznam, mešanje z 0.2,
`apps.announce`, meje kataloga), Linux `tests/test_link_protokol_v1.py`; v živo `tests/test_link_naprave_zivo.py`
izpiše polja v1 vsake prijavljene naprave.

## 6. Id naprave iz ključa (narejeno, 20. 9.)

Identiteta naprave je njen ključ; id je le ime zanj. Id je zato izpeljan iz ključa:
`n-` + prvih 16 šestnajstiških znakov SHA-256 zapisa SPKI (`KrogZaupanja.idIzKljuca`,
`link_krog.id_iz_kljuca`). Sorodnik z istim ključem doda pripono: `n-…-os` (Safeer OS na tablici),
`n-…-control` (Safeer Control). Safeer OS na televizorju teče v svojem procesu in ima svoj ključ v KeyStore,
zato svoj `n-…-os` (id pove brskalniku v zahtevi za žeton, `LinkSorodnikStoritev` ga izda temu id-ju).
Isti ključ → isti id na vseh hubih in po vsaki menjavi huba; model naprave ali ime računalnika ne odločata več.

### Prehod: seznanitve preživijo, brez kode

- Stari id-ji (`tv-<model>`, `tv-<model>-os`, `phone-<model>`, `pc-<ime>-control`) ostanejo v krogih z istim
  ključem. Nič se ne briše.
- `KrogZaupanja.clanZaId(id)` (Linux `Krog.clan_za_id`): za id iz ključa najde člana po ključu, tudi če je ta
  v krogu pod starim id-jem. Hub tako sprejme **izziv in podpis za nov id**, ki ga še ni videl, kadar ključ
  pozna: podpis dokazuje isti ključ, zato hub nov id **sam vpiše kot alias** starega (`/cast/auth/ticket`,
  `dodal` = stari id) in vrne krog z obema. Odjemalcu ni treba vedeti za `/cast/trust/alias`.
- Odjemalec gre s podpisom tudi, kadar je njegov ključ v krogu pod drugim id-jem (`KrogNaprave.lahkoSPodpisom`,
  `link_krog.lahko_s_podpisom`); brez ključa v krogu gre po starem z žetonom.
- Vstopnica, izdana enemu id-ju, velja za prijavo drugega z **istim ključem** (`istiKljuc` v `registriraj`);
  z drugim ključem ostane `napacen_device_id`.
- Izvolitev: kandidat je hub, katerega id iz oglasa mDNS najde člana po id-ju **ali po ključu**
  (`clanZaId`); pripenjanje potrdila izvoljenega huba (`KrogNaprave.kljucHuba`) enako. Tako naprava zaupa
  hubu, ki je dobil nov id, še preden je videla nov krog.
- Če ključa ni mogoče dobiti (KeyStore odpove), naprava obdrži stari id (`HubKrmilnik.stariId`,
  `link_hub.stari_id_naprave`).

Kaj se za uporabnika spremeni: v seznamih naprav so id-ji `n-…` namesto `tv-…`; imena ostanejo. Vzdevki,
ki jih je uporabnik dal starim id-jem na hubu, se ne prenesejo na nove (odprto, majhno).

Preizkusi: JVM `preizkusIdaIzKljuca` (oblika id-ja, `clanZaId`, samodejni alias ob prijavi, tuj podpis 401,
vstopnica čez oba id-ja istega ključa, ne čez dva ključa), Linux `tests/test_link_protokol_v1.py::IdIzKljuca`.
V živo (spodaj).

## 7. Korak 6: računalnik in Android kot ponudnika aplikacij (začeto, 20. 9.)

Ponudnik je naprava, ki drugim pove, katere aplikacije ima, in jih na ukaz zažene. Za odjemalca
(Safeer OS na TV/tablici, pozneje na računalniku; Safeer Control) je vseeno, ali je ponudnik
Linux ali Android - uporablja iste ukaze in isto obliko.

| ukaz (`control.command`) | parametri | odgovor `data` |
|---|---|---|
| `apps.list` | `icons` (bool), pri Linuxu še `offset`/`limit` | `{"enabled", "items": [{"id", "name", "icon"? / "icon_png"?}], "total", "offset"}` |
| `apps.launch` | `app` = id iz kataloga (Android: ime paketa; Linux: `app:<vnos>.desktop`) | `ok` + sporočilo |

- Katalog brez ikon gre ob prijavi v `cast.register.apps` (Protocol v1) in je viden v `cast.devices`;
  ikone da `apps.list`, ko jih odjemalec res potrebuje (hub hrani največ 200 vnosov / 32 KiB).
- **Linux (Safeer Control)**: `link_programi.Programi.katalog_v1()` - samo če je uporabnik programe za
  televizor dovolil (privzeto izklopljeno); ob vklopu/izklopu se Control znova prijavi s svežim katalogom.
  `Povezava.objavi_katalog()` pošlje `apps.announce` brez ponovne prijave. `apps.list/launch/close/running`
  sta že obstajala.
- **Android (TV, tablica, telefon)**: `Daljinec` razume `apps.list` in `apps.launch` (stara `apps` in
  `launch_app` ostaneta za obstoječe odjemalce); katalog v prijavi zaslona pošilja `CastReceiverService`.

Preverjeno v živo (`safeer-lms/tests/test_link_ponudnik_zivo.py`): računalnik se prijavi s katalogom, hub
ga pokaže v `/cast/devices`; isti odjemalec pošlje zaslonu `apps.list` in dobi 16 aplikacij v enotni obliki.
Enote: `tests/test_link_ponudnik.py` (katalog prazen brez dovoljenja, meje huba).

Naslednje: odjemalec Safeer OS (TV/tablica) bere katalog iz `cast.devices` namesto posebnega klica, in
pretakanje aplikacije Android → računalnik (slika + vnos; obratna smer PC → TV že obstaja), ki je pogoj za
Safeer OS na računalniku.

### Sejni žeton (prijava s podpisom)

Naprava, ki se je umaknila izvoljenemu hubu, se prijavi s podpisom in nima žetona seznanitve za ta hub.
WebSocket to ne moti, HTTP pa (deljenje zaslona, datotek in besedila gre po HTTP z `x-safeer-token`).
`/cast/auth/ticket` zato vrne še `session_token` (`saf_seja_…`): velja 12 ur, samo v pomnilniku huba,
vezan na napravo (`napravaZeZetona`), največ 64 hkrati; umik naprave iz kroga ga takoj razveljavi.
`CastReceiverService` ga shrani kot žeton za trenutni hub samo pri izvoljenem hubu in nikoli ne prepiše
pravega žetona seznanitve. JVM: 6 novih preverb v `preizkusIdaIzKljuca`.

### Deljenje zaslona s tablice in televizorja (prvi del pretakanja na računalnik)

`si.safeer.tv.link.DeljenjeZaslonaStoritev` - ista storitev kot na telefonu (MediaProjection → JPEG ~8/s →
`/cast/share/screen/*` na hubu), z zaupanjem po krogu (pri izvoljenem hubu potrdilo nosi ključ iz kroga).
Stran Link na tablici/TV ponudi »Zaslon« pri vsaki napravi; sistemsko okno »Deli celoten zaslon« se pokaže
enkrat na deljenje. Preverjeno v živo: tablica deli zaslon na TV (TV kaže »Zasedeno«), prekinitev z gumba.

Naslednje za Safeer OS na računalniku: gledalec v aplikaciji Safeer Control (obstaja za deljene zaslone),
vnos z miške/tipkovnice nazaj na Android (AccessibilityService: dotik, poteg, Nazaj/Domov; uporabnik ga
vklopi enkrat) in »pretoči aplikacijo« = `apps.launch` + deljenje zaslona proti napravi, ki je vprašala.
Omejitev Androida: dovoljenje za zajem zaslona je treba potrditi na napravi za vsako deljenje.
