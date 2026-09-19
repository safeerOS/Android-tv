# Zunanji odprtokodni projekti in Safeer OS TV

Zapis odločitev, da se isto vprašanje ne odpira znova. Datum pregleda: 18. 9. 2026.

Safeer je pod licenco MIT. To je pri tem pregledu odločilno: kode pod GPL ne smemo prenesti v
naš repozitorij, smemo pa zagnati ločeno GPL aplikacijo in se z njo pogovarjati prek protokola.
Vizualne ideje in arhitekturni vzorci niso isto kot kopiranje kode.

## Kaj že imamo

Safeer Desktop Stream (`core/link_zaslon.py` + `os/ZaslonActivity.kt`) je na tem televizorju
izmerjeno dal 1920×1080 pri 60 sličicah/s, dekoder 14–27 ms, z zvokom, s tipkovnico, miško in
držanjem tipk. Vse to teče po eni sami povezavi Safeer Linka, z eno seznanitvijo in enim
vmesnikom. Pri vsakem predlogu spodaj je zato prvo vprašanje, ali kaj **doda** temu ali le
zamenja nekaj, kar že dela.

## Odločitve

### Sunshine + Moonlight — ne prevzemamo
Oba sta GPL-3.0, Safeer je MIT. Zamenjala bi delujoč lasten pretok z dvema tujima aplikacijama,
novim seznanjanjem in drugim vmesnikom. Edina prava prednost, ki jo imata in je mi nimamo, je
**navidezni igralni plošček na računalniku** (prek `/dev/uinput`): pri nas gumbi ploščka
postanejo tipke in miška, zato igra, ki hoče plošček, ga ne vidi. To naredimo sami, brez tuje
kode. Ovira: `/dev/uinput` je na Linux Mintu dostopen samo rootu, zato je potrebno pravilo udev
(skupina `input`) — to je sprememba uporabnikovega sistema in gre samo z njegovo privolitvijo.

### LocalSend — smiselno, kot sprejemni adapter
Apache-2.0, protokol je javno opisan; kode ni treba kopirati. Vrednost: kdorkoli s telefonom, ki
ima LocalSend, bi lahko poslal datoteko na televizor, brez nameščanja Safeerja. Safeer Link
ostane glavni protokol, LocalSend je le dodatna sprejemna pot.

### Android TV Samples / Compose for TV — referenca, ne prepis zdaj
Prepis domačega zaslona v Compose je tvegana prenova brez današnje koristi za uporabnika; fokus
in vrstice delujejo. Če se je lotimo, gre na kopijo in skozi kritične teste
(`docs/PRENOVA-NA-KOPIJI.md`).

### FLauncher — samo ideje
Flutter in GPL. Njegova najboljša ideja so kategorije aplikacij; to smo 18. 9. 2026 naredili za
programe računalnika (`core/link_programi.py`, skupine iz kategorij XDG). Isto lahko poceni
dodamo še za aplikacije na televizorju.

### Jellyfin — mogoča smer, nizka prednost
Odločitev uporabnika: zanimivo za pozneje, ko bo čas in denar za strežnik. Nekateri uporabniki
Jellyfin že imajo in si lahko povezavo dopolnijo sami, zato naj bo to odprta, dokumentirana pot
(zunanja aplikacija ali ponudnik vsebine prek API-ja), ne pa nekaj, kar Safeer OS obljublja
vnaprej. Vrstice "Nadaljuj gledanje" iz Jellyfina ne kažemo, dokler strežnika ni — vmesnik ne
sme obljubljati nečesa, česar ni.

### Kodi — kot aplikacija, ne kot del Safeerja
Uporabnik jo namesti sam; Safeer OS jo pokaže med aplikacijami televizorja.

### Plasma Bigscreen — ideja za daljno prihodnost
Odločitev uporabnika: zanimivo šele, če kdaj kupi TV box ali mini računalnik. Trenutno ga ne
potrebujemo, verjetnost je majhna, prednost zelo nizka. Android različica ostane
združljivostna plast za obstoječe televizorje.

## Vrstni red dela, o katerem se dogovarjava

1. Navidezni igralni plošček na računalniku (potrebuje privolitev za pravilo udev).
2. Vrstica "Nadaljuj" na domačem zaslonu.
3. Pošteno stanje povezave namesto splošnih napak.
4. Sprejem po LocalSend.
5. Šele na kopiji in z vsemi kritičnimi testi: modularizacija in Compose.
