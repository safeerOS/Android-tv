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

- **Ključ naprave.** Vsaka naprava ob prvem zagonu naredi par ključev Ed25519 (`bcprov-ed25519` je že v
  paketu; na Linuxu `cryptography`). `device_id` je izpeljan iz javnega ključa (prvih 16 hex SHA-256), ne
  več iz modela; ime naprave ostane človeško. Zasebni ključ je v zasebni shrambi aplikacije (kot danes žetoni).
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

## 3. Izločitev Link Core iz brskalnika (osnutek)

Danes je hub v `si.safeer.tv.cast.*` znotraj brskalnika TV (in podvojen na telefonu v
`com.safeer.mobile.browser.cast.*`). Cilj: en modul `link-core` (Kotlin, brez Android UI odvisnosti razen
Context), ki ga vgradijo brskalnik TV, Safeer OS, tablica in telefon; na Linuxu ostane Python (`core/`)
s istim protokolom. Podvojena koda telefona se zamenja z modulom. Merilo uspeha: `tests/UsmerjevalnikTest.kt`
in `HubStreznikTest.kt` tečejo nad modulom brez sprememb v pričakovanjih.

## 4. Izvolitev huba (dogovorjeno)

- Vsaka naprava prek mDNS objavi `hub_priority` in `device_id`.
- Hub je naprava z najvišjo prioriteto; pri enaki odloči `device_id` (leksikografsko manjši). Vsi pridejo
  do istega rezultata, brez pogovora, brez glasovanja, brez skupne baze.
- Če se pojavita dva huba (po izpadu omrežja), ostane tisti z višjo prioriteto, drugi se umakne in se
  poveže kot odjemalec.
- Privzete prioritete: strežnik brez GUI (`safeer-core`, pozneje) 100, Linux računalnik 80, TV 60,
  tablica 40, telefon 20. Uporabnik jih lahko spremeni v nastavitvah.
- Hub dela samo odkrivanje, katalog naprav, zmožnosti in dogovor o seji; mediji tečejo neposredno (1.), zato
  seja preživi menjavo huba.
