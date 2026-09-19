# Tvegane prenove: na kopiji, nikoli na delujočem produktu

Pravilo, po katerem delava: **delujočega produkta se ne dotikava.** Kar je tvegano, gre na svojo
vejo (kopijo). Ko je kopija dokazano boljša od tega, kar imava zdaj, in ko so vsi kritični testi
zeleni, jo šele takrat zamenjava.

Ta dokument pove, kaj čaka na kopiji, zakaj je tvegano in kaj mora biti zeleno pred zamenjavo.

## Kaj gre na kopijo

**1. Razdelitev na module.** Danes sta obe aplikaciji zgrajeni iz iste kode: Safeer OS nosi tudi
brskalniški pogon, PDF.js, filtre oglasov in Cast/Hub strežnik, čeprav jih v svoji lupini ne
odpre. Predlog: `shared-core`, `safeer-link`, `safeer-shield`, `safeer-browser`, `safeer-os`,
`safeer-player`. Pridobitev: manjši paket, hitrejši zagon, manj pomnilnika.
*Tveganje:* premik vsakega razreda lahko pretrga pot, ki je nikjer ne preizkuša test — na primer
zagon Huba, kadar brskalnik ni nameščen.

**2. Skupna storitev (Runtime).** Predlog je bil, naj Hub, Ščit in odkrivanje naprav teče v eni
sami majhni aplikaciji, Safeer OS in brskalnik pa naj bosta samo odjemalca.
*Tveganje:* Safeer OS danes namenoma zna oboje sam (`HubStoritev.zagotovi`, `Scit.vklopi`), kadar
brskalnika ni. Tega ne smeva izgubiti — brez brskalnika mora Safeer OS delovati naprej.

**3. Žetoni v Android Keystore.** Žeton Safeer Linka, naslov in prstni odtis so zdaj v zasebnih
SharedPreferences. Bolje: šifrirano s ključem iz Keystora, z rotacijo in izbrisom ob odstranitvi
seznanitve, plus seznam zaupanja vrednih naprav in gumb »Prekliči vse povezave«.
*Tveganje:* ob napaki pri selitvi uporabnik izgubi seznanitev in mora vse naprave povezati znova.

**4. Ozek `networkSecurityConfig` namesto globalnega `usesCleartextTraffic`.**
*Tveganje:* brskalnik mora še naprej odpreti navadne `http://` strani, Link pa lokalne naslove;
preozka nastavitev tiho pretrga eno od teh poti.

## Kaj je že narejeno na delujočem produktu (ker ni bilo tvegano)

- `SHA256SUMS` se zapiše ob vsaki gradnji in se takoj preveri (prej je ostajal iz stare izdaje in
  je navajal napačno vsoto).
- Safeer OS ne zahteva več mikrofona in risanja čez druge aplikacije; oboje uporablja le brskalnik.
- Safeer OS nima več `largeHeap` (poraba ob zagonu domačega zaslona: pod 100 MB).

## Kritični testi pred zamenjavo

Zamenjava je dovoljena šele, ko je **vse** spodnje zeleno. Kar ni mogoče preizkusiti samodejno,
se preizkusi na televizorju in zapiše v opombe izdaje.

1. `bash tests/run_threat_policy_tests.sh` (opravilo `testi-tv`) — ščit, filtri, SponsorBlock.
2. `python3 -m unittest discover -s tests` v repozitoriju Safeer Control (`testi-linux`).
3. `bash build_tv_apk.sh` — gradnja mora biti zelena, vključno s preverjanjem podpisa, ločenih
   aplikacij in kontrolnih vsot.
4. `python3 tests/preizkus_os_na_tv.py <IP televizorja> --namesti` — dimni preizkus na napravi:
   zagon, premikanje, Nastavitve in vrnitev, brez sesute seje in brez prekoračitve pomnilnika.
5. Ročno na televizorju, ker tega adb ne zna sprožiti:
   - slika računalnika z zvokom in kazalcem (daljinec), navidezna tipkovnica in shranjevanje v
     dokumentu,
   - igralni plošček: leva palica premika izbiro, A/B/X/Y/Start delajo, kar piše v vrstici pomoči,
   - datoteke z računalnika: video in glasba na televizorju, besedilo v bralniku, drugo na
     računalniku,
   - zagon ob vklopu televizorja z resničnim izklopom iz elektrike.
6. Poraba pomnilnika Safeer OS po petih minutah uporabe ne sme biti večja kot pri sedanji
   različici (izmerjeno z `dumpsys meminfo si.safeer.os`).

Če katerakoli točka pade in je ni mogoče popraviti brez novega tveganja, kopija ostane kopija.
