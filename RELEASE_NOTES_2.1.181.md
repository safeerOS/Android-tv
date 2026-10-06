# Safeer 2.1.181 · Safeer OS 0.5.57

Zdoma kot doma (Global Link) – kar deluje v domačem omrežju, deluje tudi, ko nisi doma:

- **Zaslon računalnika tudi zdoma – v isti kakovosti in z zvokom.** Prej je slika zaslona delovala samo v istem omrežju; zdoma je pisalo, da računalnik ni dosegljiv. Zdaj pride do telefona, tablice ali televizorja prek Global Linka: šifrirano do računalnika, posrednik vidi samo šifrirane bajte. Slika (do 1920 × 1080 pri 60 slikah na sekundo), zvok, dotik, miška in tipkovnica so isti kot doma – kakovosti zaradi poti ne nižamo. Naprava poskusi obe poti hkrati: doma je neposredna povezava končana, preden se druga sploh začne, zdoma pa ni čakanja na naslove domačega omrežja.
- **Preimenovanje, premik, brisanje in vrtenje slik tudi zdoma.** Urejanje datotek na računalniku je bilo prek Global Linka skrito, ker je šlo samo neposredno. Zdaj gre po isti poti kot branje; vsak ukaz se izvede natanko enkrat.
- **Sličice videov in slik z računalnika ter datumi v mreži.** Računalnik s Safeer Control 2.1.56 pošlje pomanjšano sliko za vsak video in vsako fotografijo (prej je telefon za sličico prenesel celo fotografijo, videi so imeli samo ikono) in pove, kdaj je bila datoteka spremenjena – mreža jih razvrsti po dnevih.
- **Sprotno pretvorjen video in tok z druge naprave** gresta zdoma po isti poti kot datoteke.
- **Datoteke, odprte takoj po zagonu.** Če si Datoteke odprl, preden se je Safeer Link povezal, si pristal v datotekah te naprave (in pri vprašanju za dovoljenje), čeprav si hotel na računalnik. Zdaj se pokaže seznam virov z napisom »Povezujem …« in se dopolni, ko naprave pridejo.
- **Telefon, tablica ali televizor kot vir** zdoma sprejme tudi urejanje in tokove (isti žeton in ista pravila kot doma).

Na računalniku je za zaslon, urejanje in sličice zdoma potreben Safeer Control 2.1.56 (Linux 1.0.100). Pri starejšem Safeerju na računalniku naprava to pove (»… potrebuje novejši Safeer. Posodobi Safeer na računalniku.«) in zdoma ponudi samo branje datotek. Računalnik z Windows te poti še nima.

Izmerjeno (telefon s 576 × 1280 točkami, računalnik z Linuxom in Safeer Control s to kodo):

- s stikalom »Preizkus: tudi doma prek interneta« (promet gre prek link.safeer.si): zaslon računalnika se je povezal 0,6 s po odgovoru računalnika; 1920 × 1080, 58–65 slik na sekundo, 4,4–5,3 Mb/s pri mirnem namizju, dekodiranje 30–80 ms, zvočni tok prisoten; dolg pritisk na Nazaj konča sejo in zajem na računalniku se ustavi;
- brez stikala (doma): neposredna povezava v 23 ms, enake številke slike, posrednika se naprava ni dotaknila;
- prek Global Linka preimenovana datoteka je bila na disku računalnika preimenovana, v mreži so bile sličice treh videov in fotografije ter razdelki po dnevih.

Ni preizkušeno v živo: resnično tuje omrežje (mobilni podatki); dotik in tipkovnica v sliki zaslona prek Global Linka (pot nazaj preverja samodejni preizkus na računalniku s pravim središčem in pravo šifrirano povezavo); premik, brisanje in vrtenje prek Global Linka (ista zahteva kot preimenovanje); sprotno pretvorjen video in tok z druge naprave prek Global Linka; telefon, tablica ali televizor kot vir prek Global Linka; Datoteke, odprte pred povezavo Safeer Linka; televizor in tablica.

Pri polnem gibanju (video čez ves zaslon) gre slika zaslona do 24 Mb/s – toliko podatkov teče tudi prek posrednika in mobilnega omrežja.

Predvajalnik: 0.2.47.
