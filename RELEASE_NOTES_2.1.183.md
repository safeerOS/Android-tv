# Safeer 2.1.183 · Safeer OS 0.5.59

Slika oddaljenega zaslona se prilagodi zaslonu naprave, na kateri jo gledaš. Zaslon računalnika doma se pri tem ne spremeni.

- **Programi računalnika čez cel zaslon naprave.** Ko s telefona, tablice ali televizorja odpreš program računalnika, naprava računalniku pove obliko in velikost svojega zaslona. Računalnik (Safeer Control 2.1.58, Linux 1.0.102) ločeni zaslon za programe naredi natanko tak: slika zapolni zaslon naprave brez črnih robov in brez prevzorčenja, na gostem zaslonu telefona pa so gumbi in besedilo programa narisani večje. Velikost je največja, ki jo strojni dekodirnik naprave zmore pri 60 slikah na sekundo.
- **»Večja vsebina« in »Manjša vsebina«** v meniju seje (program računalnika): gumbi in besedilo programa so večji ali manjši, slika ostane enako ostra in seja teče naprej. Večje gre samo, dokler imajo programi dovolj prostora, da ostanejo celi – čez to mejo pomaga povečava z dvema prstoma. Izbira se zapomni.
- **»Zapolni zaslon« in »Cela slika«** v meniju seje, kadar gledaš namizje računalnika na napravi z drugačnim razmerjem zaslona (telefon): slika se poveča čez ves zaslon naprave; kar pade čez rob, dosežeš s premikom z dvema prstoma. Povečava je krajevna – računalniku se o njej ne pošlje nič. Izbira se zapomni; gumb 1× vrne izbrano osnovno lego.

Kakovost ostane: 60 slik na sekundo, zvok, ista ostrina. Računalnik s starejšim Safeerjem ali z Windows dobi isto sliko kot doslej (1920 × 1080), »Zapolni zaslon« pa deluje z vsakim.

Izmerjeno (telefon z zaslonom 576 × 1280, računalnik z Linuxom in Safeer Control s to potjo):

- namizje računalnika: cela slika 1024 × 576 pokrije 80,0 % zaslona; »Zapolni zaslon« 96,6 % (ostanek je pas ob izrezu kamere); nova seja se zapolni sama; »Cela slika« vrne 80,0 %; gumb 1× po dodatni povečavi vrne zapolnjeno lego;
- program računalnika (Kalkulator), odprt s telefona: ločeni zaslon 1236 × 576, dekodirnik telefona 1236 × 576 pri 60 slikah na sekundo, slika pokrije 96,6 % zaslona;
- isto prek Global Linka (nastavitev »Preizkus: tudi doma prek interneta«, promet prek link.safeer.si): enaka velikost in pokritost; dotik na gumb »7« v kalkulatorju je zadel gumb;
- »Večja vsebina« (merilo 1,25): računalnik je preklopil merilo, slika je tekla naprej; na tem majhnem zaslonu sta spodnji vrstici kalkulatorja padli iz slike – zato meja prostora, po kateri ta telefon večje vsebine ne ponudi več.

Samodejni preizkusi: velikost slike za napravo in dekodirnik, površina brez izreza kamere, merilo zapolnitve in osnovna lega, merilo vsebine.

Ni preizkušeno v živo: telefon z gostim zaslonom (merilo 2 in izbira manjše vsebine), tablica, televizor.

Predvajalnik ostane 0.2.48.

---

English: **The picture of a remote screen adapts to the screen of the device that shows it.** The screen of the computer at home does not change.

- **Programs from the computer fill the device's screen.** When you open a program of the computer from a phone, tablet or TV, the device tells the computer the shape and size of its screen. The computer (Safeer Control 2.1.58, Linux 1.0.102) makes its separate screen for programs exactly like that: no black bars, no resampling, and on a dense phone screen the program's buttons and text are drawn larger. The size is the largest the device's hardware decoder can play at 60 frames per second.
- **"Larger content" and "Smaller content"** in the session menu (a program of the computer): larger or smaller buttons and text, same sharpness, the session keeps running. Larger is offered only while programs still fit; beyond that, pinch to zoom. The choice is remembered.
- **"Fill the screen" and "Whole picture"** in the session menu when you watch the computer's desktop on a device with a different aspect ratio: the picture is enlarged over the whole screen of the device; what falls outside is reached by moving with two fingers. The zoom is local – nothing is sent to the computer. The choice is remembered.

Measured on a phone with a 576 × 1280 screen: desktop 80.0 % of the screen as a whole picture, 96.6 % with "Fill the screen"; a program of the computer on a 1236 × 576 separate screen covers 96.6 % (the rest is the strip beside the camera cutout), the same through Global Link, and a tap on a calculator button hit the button.

Not tested live: a phone with a dense screen (scale 2), a tablet, a TV.
