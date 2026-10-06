# Safeer 2.1.184 · Safeer OS 0.5.60

Zaslon računalnika prek Global Linka (zdoma) teče bolj gladko: odpravljen je vzrok zatikanja slike na napravi. Kakovost slike je enaka kot prej.

- **Slika prek Global Linka se ne zadržuje več na napravi.** Del aplikacije, ki sliko iz posrednika (link.safeer.si) predaja predvajalniku, je vsak kos podatkov zadržal, dokler ni bil potrjen prejšnji – do 40 ms. Slika je zato prihajala v sunkih, tudi kadar je bila povezava hitra. Zdaj gre vsak kos naprej takoj (računalnik to na svoji strani dela že od prej). Ločljivost, ostrina in 60 slik na sekundo ostanejo, kot so bili.
- **»Povezani zasloni« takoj po odprtju aplikacije.** Če se kartice dotakneš, preden se Safeer Link poveže (zasuk zaslona, vrnitev v aplikacijo), aplikacija nekaj sekund počaka na naprave in zaslon odpre, namesto da reče, naj napravo povežeš. Isto pot uporabljata gumb »Zaslon« v stranski vrstici in med hitrimi dejanji.

Izmerjeno doma (optična linija, telefon na Wi-Fi; ista vsebina – meni igre pri 60 slikah na sekundo na ločenem zaslonu računalnika; 12-sekundni posnetek zaslona telefona, šteje vsaka nova slika):

| pot | novih slik na sekundo | 90 % presledkov pod | zastojev nad 50 ms na sekundo |
| --- | --- | --- | --- |
| neposredno v domačem omrežju | 52,3 | 32,5 ms | 0,6 |
| prek Global Linka, prej (0.5.59) | 41,9 | 53,1 ms | 5,7 |
| prek Global Linka, zdaj (0.5.60) | 48,6 | 34,9 ms | 0,7 |

Posnetek zaslona telefona na mobilnem omrežju pred popravkom je imel enak vzorec kot meritev doma prek Global Linka (41,0 novih slik na sekundo, 3,6 zastoja na sekundo) – vzrok torej ni bilo mobilno omrežje.

Ni preizkušeno v živo: ista meritev na mobilnem omrežju z 0.5.60; čakanje »Povezanih zaslonov« do izteka (doma se Safeer Link poveže prej, kot se je kartice mogoče dotakniti – 1,2 s po hladnem zagonu se je zaslon odprl).

Računalnik: za igre čez cel zaslon, v katerih dotik ni zadel gumba, je popravek v Safeer Control 2.1.59 (Linux 1.0.103).

Predvajalnik ostane 0.2.48.

---

English: **The screen of your computer through Global Link (away from home) runs more smoothly: the cause of the stuttering picture on the device is fixed.** Picture quality is unchanged.

- **The picture through Global Link is no longer held back on the device.** The part of the app that hands the picture from the relay (link.safeer.si) to the player held every chunk of data until the previous one was acknowledged – up to 40 ms. The picture arrived in bursts even on a fast connection. Every chunk is now passed on at once (the computer has done this on its side for some time). Resolution, sharpness and 60 frames per second stay as they were.
- **"Connected screens" right after opening the app.** If you tap the card before Safeer Link has connected, the app waits a few seconds for the devices and opens the screen instead of telling you to connect a device.

Measured at home (fibre line, phone on Wi-Fi, the same content – a game menu at 60 frames per second on the computer's separate screen; 12-second recording of the phone's screen, every new picture counted): directly in the home network 52.3 new pictures per second and 0.6 stalls longer than 50 ms per second; through Global Link before (0.5.59) 41.9 and 5.7; through Global Link now (0.5.60) 48.6 and 0.7. A recording made on a mobile network before the fix showed the same pattern as the home measurement through Global Link (41.0 pictures and 3.6 stalls per second), so the mobile network was not the cause.

Not tested live: the same measurement on a mobile network with 0.5.60; the wait of "Connected screens" until it times out.
