# Safeer OS 0.5.21 (TV, tablica, telefon) · Safeer Browser za Android TV 2.1.145

- **Safeer Link – en krog zaupanja:** središče in naprava sta imela vsak svojo kopijo kroga zaupanja nad isto shrambo in zadnji je prepisal člane drugega; po ponovni seznanitvi je naprava po ponovnem zagonu lahko ostala brez kroga (zavračala je sosede, odgovori na ukaze računalnika niso prišli nazaj). Zdaj je krog en sam.
- **Mreža brez trkanja:** stare identitete naprav (npr. telefon pred ponovno namestitvijo) in sosedje, ki nas (še) nimajo v krogu, se ne kličejo več vsakih 20 s – naslov se pozabi, ponovni poskusi so redkejši (do 10 min), oglas v omrežju jih takoj obnovi.
- **Dodatki Stremio – glave na vseh delih toka:** zahtevane glave (`proxyHeaders`) gredo tudi na segmente HLS/DASH na drugem gostitelju (CDN) in pomočniku sprotnega pretvarjanja, da dobi izvirnik.
- Središče na telefonu/tablici se v krog vpiše s pravo platformo (ne več »tv«).

English: Safeer Link now keeps a single trust circle per app (the hub and the device each had their own copy over the same storage, and the last writer overwrote the other – a re-paired device could end up with an empty circle after restart). Mesh calls to stale identities and to neighbours that do not have us in their circle back off (up to 10 min) instead of knocking every 20 s. Stremio `proxyHeaders` now follow the stream to HLS/DASH segments on other hosts and to the live-transcoding helper. No functional browser changes in 2.1.145.
