# Safeer 2.1.182 · Safeer OS 0.5.58

Od računalnika do naprave, ki ni doma (Global Link). Prejšnja izdaja je uredila pot od naprave do računalnika; ta ureja obratno smer:

- **Datoteke naprave, ki je na mobilnih podatkih.** Telefon zdoma je drugi napravi poslal seznam svojih datotek, ne pa tudi opisa, kako do njih – ta je nastal samo, kadar je imel telefon naslov v domačem omrežju. Računalnik je glasbo, slike in videe videl, odpreti pa ni mogel ničesar. Zdaj naprava opis pošlje vedno; brez domačega omrežja pove, da je pot samo prek njenega središča (Global Link). Naprava, ki bere, gre tja takoj – brez poskusa neposredne povezave, ki ne more uspeti.
- **Vse aplikacije naprave, ne samo prvih 60.** Druga naprava je dobila prvih 60 aplikacij po abecedi – pri telefonu z veliko aplikacijami vse do črke G, naprej nič. Seznam gre zdaj po kosih (do 400 aplikacij), ikone se narišejo samo za kos, ki se pošilja, in velja velikost kosa, ki jo druga naprava zahteva. Katalog, ki ga naprava objavi svojemu središču, ima do 200 aplikacij in ostane pod mejo, ki jo središče sprejme (večjega bi zavrglo v celoti).
- **Opis strežnika datotek je povsod isti.** Seznam mape, iskanje in tokovi povedo, da središče naprave zna tudi urejanje, sličice in tokove (`hub: 2`); prej je to povedal samo eden od treh odgovorov.

Izmerjeno pred popravkom (telefon na mobilnih podatkih, računalnik z Linuxom doma): seznam zbirke je prišel brez opisa strežnika; seznam aplikacij je imel 60 vnosov z začetnicami od A do G.

Preizkušeno s to izdajo (testni telefon in televizor doma, računalnik z Linuxom):

- seznam aplikacij testnega telefona po kosih: po 10 → 10 + 10 + 5 od 25, vse različne, po abecedi; z ikonami po 7 → 7 + 7 + 7 + 4, vsaka z ikono; brez zahtevane velikosti kosa vseh 25 v enem sporočilu kot prej;
- televizor kot vir: seznam mape nosi `hub: 2`; računalnik je datoteko prebral neposredno (prvih 64 KiB v 0,1 s) in z naslovom brez domačega omrežja prek Global Linka (promet prek link.safeer.si: prva zahteva 1,5 s, prvih 64 KiB 0,9 s) – brez čakanja na neposredni poskus.

Samodejni preizkusi: katalog naprave s 400 aplikacijami in z zelo dolgimi imeni središče sprejme v celoti (200 vnosov oziroma manj kot 32 KiB); naslov brez domačega omrežja pomeni pot samo prek središča.

Ni preizkušeno v živo pred izdajo: naprava, ki je res na mobilnih podatkih, kot vir datotek s to izdajo, in naprava z več kot 60 aplikacijami – naprave doma tega nimajo; telefon, tablica ali televizor kot bralec datotek naprave brez domačega omrežja.

Predvajalnik: 0.2.48.

---

English: **From the computer to a device that is away from home (Global Link).** The previous release fixed the way from a device to the computer; this one fixes the other direction.

- **Files of a device on mobile data.** A phone away from home sent another device the list of its files but not the description of how to reach them – that was only created when the phone had an address on the home network. The computer saw music, pictures and videos and could open none of them. The device now always sends the description; without a home network it says that the only way is through its hub (Global Link), and the reading device goes there at once, without a direct attempt that cannot succeed.
- **All apps of a device, not just the first 60.** Another device received the first 60 apps in alphabetical order – on a phone with many apps everything up to the letter G and nothing after it. The list now goes in pages (up to 400 apps), icons are drawn only for the page being sent, and the page size asked for by the other device is honoured. The catalogue a device announces to its hub has up to 200 apps and stays under the limit the hub accepts (a larger one would be dropped as a whole).
- **The file server description is the same everywhere** (folder list, search and streams all say `hub: 2`).

Tested with this release (test phone and TV at home, computer with Linux): app list in pages (10 + 10 + 5 of 25; with icons 7 + 7 + 7 + 4); the TV as a source – the folder list carries `hub: 2`, the computer read a file directly and, given an address without a home network, through Global Link (traffic via link.safeer.si: first request 1.5 s, first 64 KiB in 0.9 s).

Not tested live before release: a device that really is on mobile data as a file source with this release, and a device with more than 60 apps – the devices at home have neither; a phone, tablet or TV as the reader of files from a device without a home network.
