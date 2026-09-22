# Safeer Link P2P — načrt (stanje 22. 9. 2026)

Cilj: v domačem omrežju se vsaka naprava lahko seznani z vsako in podatki tečejo **neposredno** med
napravama; središče (hub) je le imenik in posrednik dogovora, ne pot za vsebino. Isto velja prek
interneta, kjer je zadnja možnost rele (link.safeer.si), ki vidi samo šifrirane pakete.

## Kaj je že narejeno (rc1)

1. **Seznanitev pri katerikoli napravi.** Središče razpošlje `pair.code` (koda za novo napravo) vsem
   članom; vsaka naprava z zaslonom jo pokaže, `pair.done` jo skrije, `pair.reject` jo zavrne.
   QR: član pošlje `pair.invite`, središče vrne `qr_id`, skrivnost, svoj naslov in odtis potrdila;
   nova naprava se pridruži prek `/cast/pair/qr/join`. Deluje na Androidu in v Controlu (Python).
2. **Podpisani vnosi v krogu zaupanja.** `podatkiClana` / `podatki_clana` in `podatkiUmika` /
   `podatki_umika`; vnos podpiše naprava, ki doda ali umakne. `zdruzi(preveriPodpise=true)` sprejme
   nov ali spremenjen ključ samo s podpisom člana, ki ga že poznamo. Krog od lastnega huba velja kot
   doslej (združljivost s starimi napravami).
3. **Ena naprava = ena povezava.** Stara povezava se zapre, preden se odpre nova; ob zavrnitvi je
   premor (2 s → 1 min); hub, ki nas ne sprejme, 10 minut ni kandidat za izvolitev.

## 3. korak: Safeer Data Transport (v0.26)

### Poti (po vrsti)
1. `LAN_DIRECT` — neposredna povezava TLS na naslov iz oglasa mDNS ali iz `cast.devices`.
2. `INTERNET_DIRECT` — javni naslov iz koordinacije (Internet Requester); luknjanje UDP/TCP.
3. `SAFEER_RELAY` — link.safeer.si; rele vidi samo šifrirane pakete in ne pozna ključev.

Naprava poskusi po vrsti in si zapomni, katera pot je nazadnje delovala (po `device` iz kroga, ne po
naslovu). Menjava poti med prenosom ne sme prekiniti prenosa (glej nadaljevanje spodaj).

### Dogovor (handshake)
- Obe napravi sta v krogu zaupanja, zato **ključa ne izmenjujeta na novo**: sejni ključ nastane iz
  ECDH med ključema iz kroga (X25519 ni na voljo v AndroidKeyStore; uporabimo ECDH P-256 nad istim
  ključem, ki podpisuje) + naključna nonca vsake strani, prek HKDF v ključ AES-256-GCM.
- Dogovor gre po Safeer Linku (`data.offer` / `data.answer` prek huba) in vsebuje: id seje, vrsto
  (datoteka, zaslon), naslove kandidatov, nonco in podpis pošiljatelja.
- Rele ne sme dobiti sejnega ključa; podatki so šifrirani od konca do konca tudi na LAN.

### Datoteke
- Kos = 1 MiB; vsak kos ima zaporedno številko in je šifriran posebej (AES-GCM, nonca = id seje +
  številka kosa), da je mogoče nadaljevati.
- Prejemnik hrani `.safeer-del` in zapis prejetih kosov; ob prekinitvi se prenos nadaljuje tam, kjer
  je ostal (tudi po menjavi poti ali ponovnem zagonu).
- Po zadnjem kosu se preveri SHA-256 celotne datoteke; šele nato se datoteka preimenuje v končno ime.
- Uporabnik vidi: ime, velikost, hitrost, preostali čas, in gumb Prekliči.

### Zaslon
- P2P prenos slike (WebRTC ali lasten tok prek iste seje); rele samo za TURN, kadar neposredno ne gre.
- Ista seja in isti ključ kot pri datotekah; brez vsebine na relejem.

### Kaj je treba narediti (vrstni red)
1. Python + Kotlin: `data.offer` / `data.answer` prek huba, ECDH iz ključev kroga, HKDF, AES-GCM.
   Testi: dogovor obeh strani, tuj podpis pade, rele ne more odšifrirati.
2. Prenos datoteke po kosih z nadaljevanjem (najprej LAN_DIRECT), SHA-256 na koncu. Testi: prekinjen
   prenos se nadaljuje, pokvarjen kos pade, prenos med Androidom in računalnikom v obe smeri.
3. Pot INTERNET_DIRECT in preklop na SAFEER_RELAY, ko neposredno ne gre; zapomnjena pot.
4. Zaslon prek iste seje; TURN samo kot zadnja možnost.
5. Živi preizkus: telefon ↔ TV, telefon ↔ računalnik, tablica ↔ TV, vsakič v LAN in prek interneta.

### Meje in pravila
- Rele nikoli ne vidi vsebine; ključi ostanejo na napravah.
- Nobena pot ne sme delovati brez članstva v krogu zaupanja.
- Prenos ne sme zasesti Safeer Linka: nadzor (sporočila) gre naprej, tudi ko teče velik prenos.
