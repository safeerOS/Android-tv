# Safeer 2.1.175 · Safeer OS 0.5.51

Safeer Link:

- **Zaslon računalnika se s telefona, tablice in televizorja spet odpre.** »Povezani zasloni« so računalnik prosili za sliko, nato pa so se povezali sami nase – znova in znova (»Povezava je padla. Poskušam znova …«), računalnik pa je ob vsakem poskusu odprl novo sejo. Vsako središče Safeer Linka programom na svoji napravi pripiše naslov 127.0.0.1 in ta naslov je nespremenjen potoval k drugim napravam. Zdaj naprave dobijo naslov računalnika. Zadošča ena posodobljena naprava na poti: deluje tudi z računalnikom, ki posodobitve še nima.
- **Vrstica stanja pokaže pravo središče.** »Povezano · …« je kot središče imenovala poljubno napravo, tudi tako, na katero naprava sploh ni bila pripeta. Zdaj pove, na katero središče je naprava v resnici pripeta.
- **Računalnika, ki je dosegljiv samo prek Global Linka, za sliko ne prosimo.** Slika potuje neposredno med napravama v istem omrežju. Zaslon zdaj takoj pove, zakaj slike ni (v sedmih jezikih); prej se je povezoval v prazno.

Preverjeno na pravih napravah: telefon, pripet na središče tablice, je odprl zaslon računalnika z Windows, ki posodobitve še ni imel (1440 × 900, 29 slik na sekundo; pred posodobitvijo »failed to connect to /127.0.0.1«), in nato 8 minut gledal zaslon posodobljenega računalnika v eni seji. Vrstica stanja je pokazala tablico. Na televizorju ni preizkušeno v živo (ista koda kot na telefonu). Sporočilo za računalnik, dosegljiv samo prek Global Linka, pokrivajo preizkusi, ne prava naprava.

Znane omejitve: slika zaslona potrebuje neposredno pot v istem omrežju. Če nista posodobljena ne računalnik ne središče, na katero je naprava pripeta, ostane staro vedenje.

English: **The computer's screen opens from a phone, tablet and TV again.** "Connected screens" asked the computer for its screen and then connected to itself, again and again, while the computer opened a new session for every attempt: every Safeer Link hub gives the address 127.0.0.1 to programs on its own device, and that address travelled unchanged to other devices. Devices now get the address of the computer; one updated device on the path is enough, so it also works with a computer that has not been updated yet. The status line now names the hub the device is really attached to. A computer that is reachable only through Global Link is not asked for its screen; the screen says at once that the picture works only in the same network.
