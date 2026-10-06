# Safeer 2.1.185 · Safeer OS 0.5.61

Zaslon računalnika porabi precej manj podatkov, kakovost je odvisna od poti in omrežja, program, ki je na računalniku že odprt, pa se pokaže na njegovem namizju. Za vse to je na računalniku potreben Safeer Control 2.1.60 (Linux 1.0.104); s starejšim teče vse kot doslej.

- **Kakovost po poti in omrežju.** Doma (naprava doseže računalnik neposredno) ostane najvišja kakovost. Zdoma (prek Global Linka) je kakovost umerjena na običajno povezavo 4G, na 5G je stopnjo višja. Naprava računalniku pove, po katerem omrežju je povezana (Wi-Fi, kabel, 5G, 4G); kakovost izbere računalnik. Velja za telefon, tablico in televizor – za vsako napravo, ki gostuje v drugem omrežju.
- **Pod 4G zaslona računalnika zdoma ni.** Na mobilnem omrežju, slabšem od 4G, aplikacija seje ne začne in pove: »Za zaslon računalnika zdoma je potrebna povezava 4G ali hitrejša.« Raje brez storitve kot slaba. Kadar vrste omrežja ni mogoče ugotoviti, aplikacija seje ne zavrne.
- **HEVC zdoma, kjer ga naprava strojno dekodira.** Naprava HEVC ponudi samo, če ga njen strojni dekoder zmore pri 60 slikah na sekundo v velikosti zaslona in v velikosti 1920 × 1080; računalnik ga uporabi zdoma, če ga njegova grafična kartica strojno kodira. Doma ostane H.264.
- **Varovalka.** Če dekoder HEVC na napravi ne da slike (ni ga mogoče pripraviti, odpove pred prvo sliko ali po 60 poslanih slikah ne vrne nobene), naprava isto sejo sama zahteva znova s H.264 in si to zapomni do naslednje posodobitve aplikacije.
- **Ključna slika na 10 sekund namesto vsako sekundo.** Naprava delov toka ne izpušča več (poln dekoder počaka), zato pogosta ključna slika ni potrebna. Pri mirni sliki je bila ključna slika večina prometa.
- **Program, ki je na računalniku že odprt.** Program, ki sme teči samo enkrat, se na ločenem zaslonu ni odprl: 30 sekund si gledal prazen zaslon, nato se je seja končala. Zdaj aplikacija pove »… je na računalniku že odprt – kažem namizje računalnika.« in pokaže namizje z oknom tega programa v ospredju.
- **»Odpiram … na računalniku …«** piše, dokler se program na računalniku odpira, namesto praznega zaslona.
- **»Podatki o povezavi«** v meniju seje (☰): velikost slike, slike na sekundo, Mb/s, zastoji nad 50 ms na sekundo, pot, omrežje naprave in tok – na primer »prek Global Linka · 4G · HEVC q20«. Privzeto so skriti.

Izmerjeno (telefon s strojnim dekoderjem HEVC doma na Wi-Fi, računalnik s Safeer Control 2.1.60; samo slika, 60 slik na sekundo):

| vsebina | prej (0.5.60, Control 2.1.59) | zdaj doma (H.264 q16) | zdaj zdoma, 5G (HEVC q18) | zdaj zdoma, 4G (HEVC q20) |
| --- | --- | --- | --- | --- |
| mirno okno programa, 1236 × 576 | 0,53 Mb/s + 1,54 Mb/s tišine | 0,29 Mb/s (v živo) | – | 0,11 Mb/s (v živo) |
| meni igre, 1236 × 576 | 11,44 Mb/s + 1,54 Mb/s zvoka | 10,75 Mb/s | 5,30 Mb/s | 3,28 Mb/s |

»V živo« je promet, ki ga je naštel telefon (60 slik/s, 0 zastojev na sekundo); druge številke so zajem iste vsebine na računalniku z nastavitvami posamezne stopnje (8–10 s). Zvok je nestisnjen (1,54 Mb/s, kadar kaj igra; tišine računalnik ne pošilja več). Kakovost slike istih nastavitev na brezizgubnem posnetku igre (SSIM, 1 = enako): H.264 q16 0,9910; HEVC q18 0,9907; HEVC q20 0,9872.

Drugo izmerjeno: program, ki je bil na namizju že odprt – 4,1 s od dotika do pripravljene slike namizja. Varovalka (gradnja z namerno pokvarjenim tokom HEVC) – 3,4 s po začetku je seja tekla s H.264, naslednja seja je s H.264 začela takoj. Televizor doma (1920 × 1080): 0,27 Mb/s namesto 1,95 Mb/s, 60 slik/s, brez presledkov nad 50 ms, dekoder 19 ms.

Ni preizkušeno v živo: mobilno omrežje – testni telefon nima kartice SIM, zato zaznava 4G/5G (tudi 5G NSA), stopnja za 5G in zavrnitev pod 4G niso preizkušene na pravi povezavi (pravila pokriva samodejni preizkus; dovoljenje za branje vrste omrežja je običajno in ob namestitvi podeljeno); tablica in drugi telefoni kot gledalci s HEVC (zato varovalka); dekoder HEVC, ki ga ni mogoče pripraviti ali ki javi napako (v preizkusu je dekoder molčal).

Novo dovoljenje: `READ_BASIC_PHONE_STATE` (Android 13+, običajno dovoljenje brez vprašanja uporabniku) – aplikacija z njim prebere samo vrsto mobilnega omrežja (4G, 5G).

Predvajalnik ostane 0.2.48.

---

English: **The screen of your computer uses far less data, quality follows the path and the network, and a program that is already open on the computer is shown on its desktop.** All of this needs Safeer Control 2.1.60 on the computer (Linux 1.0.104); with an older one everything works as before.

- **Quality by path and network.** At home (the device reaches the computer directly) quality stays at the highest level. Away from home (through Global Link) it is tuned to an ordinary 4G connection, and one step higher on 5G. The device tells the computer which network it is on (Wi-Fi, cable, 5G, 4G); the computer picks the quality. This applies to phones, tablets and TVs – to every device that is a guest in another network.
- **No computer screen away from home below 4G.** On a mobile network slower than 4G the app does not start the session and says why. Better no service than a bad one. When the kind of network cannot be determined, the app does not refuse.
- **HEVC away from home, where the device decodes it in hardware.** The device offers HEVC only if its hardware decoder handles 60 frames per second at the size of its screen and at 1920 × 1080; the computer uses it away from home if its graphics card encodes HEVC in hardware. At home H.264 stays.
- **Safety net.** If the HEVC decoder of the device gives no picture (it cannot be set up, fails before the first picture, or returns none after 60 pictures sent), the device asks for the same session again with H.264 and remembers this until the next update of the app.
- **A key frame every 10 seconds instead of every second.** The device no longer drops parts of the stream (it waits for a full decoder), so frequent key frames are not needed.
- **A program that is already open on the computer.** A program that may run only once did not open on the separate screen: you watched an empty screen for 30 seconds and the session ended. The app now says "… is already open on the computer – showing the computer's desktop." and shows the desktop with that program's window in front.
- **"Opening … on the computer…"** is shown while the program is opening, instead of an empty screen.
- **"Connection details"** in the session menu (☰): picture size, frames per second, Mb/s, stalls longer than 50 ms per second, the path, the device's network and the stream – for example "via Global Link · 4G · HEVC q20". Hidden by default.

Measured (a phone with a hardware HEVC decoder at home on Wi-Fi, computer with Safeer Control 2.1.60; picture only, 60 frames per second): a still program window, 1236 × 576 – before 0.53 Mb/s plus 1.54 Mb/s of silence; now at home 0.29 Mb/s (H.264 q16, live), away from home on 4G 0.11 Mb/s (HEVC q20, live). A game menu – before 11.44 Mb/s plus 1.54 Mb/s of sound; now at home 10.75 Mb/s, away on 5G 5.30 Mb/s (HEVC q18), away on 4G 3.28 Mb/s (HEVC q20). "Live" is the traffic counted by the phone; the other numbers are captures of the same content on the computer with the settings of each step. Picture quality of the same settings on a losslessly recorded game scene (SSIM, 1 = identical): H.264 q16 0.9910; HEVC q18 0.9907; HEVC q20 0.9872. A program that was already open on the desktop: 4.1 s from the tap to the desktop being ready. A TV at home (1920 × 1080): 0.27 Mb/s instead of 1.95 Mb/s, 60 frames per second, no gaps longer than 50 ms.

Not tested live: a mobile network – the test phone has no SIM card, so detecting 4G/5G (including 5G NSA), the 5G step and the refusal below 4G were not tried on a real connection (the rules are covered by an automated test; the permission to read the kind of network is a normal one and is granted at install); a tablet and other phones as viewers with HEVC (hence the safety net); an HEVC decoder that cannot be set up or that reports an error.

New permission: `READ_BASIC_PHONE_STATE` (Android 13+, a normal permission, no prompt) – the app reads only the kind of mobile network (4G, 5G) with it.
