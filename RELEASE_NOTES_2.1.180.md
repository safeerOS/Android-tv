# Safeer 2.1.180 · Safeer OS 0.5.56

Datoteke z drugih naprav – doma in zdoma (Global Link):

- **Zdoma se odprejo tudi besedilne datoteke in slike.** Glasba in video z računalnika sta prek Global Linka že igrala, besedilo in slike pa ne: po šestih sekundah je pisalo »Datoteke ni bilo mogoče prebrati.« Zdaj gredo po isti poti kot predvajanje – šifrirano do naprave, posrednik vidi samo šifrirane bajte.
- **Zdoma brez čakanja pri vsaki datoteki.** Naprava je prej vsakič najprej poskusila domači naslov in šele po šestih sekundah šla prek Global Linka (tudi ob previjanju po minuti premora). Zdaj kratek poskus pove, ali si doma; zdoma počakaš kvečjemu prvič, vrnitev domov zazna sama.
- **Mapa, ki si jo že videl, je na zaslonu takoj.** Nazaj v nadrejeno mapo in ponoven vstop ne pokažeta več praznega seznama in napisa »Nalagam«; svež seznam pride v ozadju in se, če se je kaj spremenilo, dopolni na mestu. Nazaj te vrne na vrstico, s katere si vstopil.
- **Nazaj iz predvajalnika vrne v mapo,** iz katere si datoteko odprl (prej je bil vmes še Medijski center).
- **Mreža pokaže imena.** Mape, glasba in dokumenti imajo v mrežnem pogledu pod ikono ime; glava »Brez datuma« se ne kaže, kadar naprava datumov ne pošlje.
- **Sličice slik z računalnika** se naložijo z enim prenosom namesto dveh, niso več mehke (prej so bile lahko pol manjše od ploščice) in so obrnjene tako, kot je bila fotografija posneta. Enako doma in zdoma.
- **Kratka prekinitev povezave te ne vrže iz mape.** Ko se Safeer Link vzpostavi znova (menjava središča, preklop omrežja), mapa ostane na zaslonu; na seznam virov se vrneš šele, če naprave 25 sekund ni nazaj.

Popravki:

- Prva vrsta v mrežnem pogledu se ni vedno odzvala na dotik (bila je narisana, a ne pripeta v okno).
- Zapoznel odgovor naprave ni več mogel prepisati mape, v katero si medtem šel.
- Seznam se po menjavi mape ni več pokazal zamaknjen.

Zdoma še ne deluje (sledi v naslednji izdaji, ker potrebuje tudi novo različico na računalniku): preimenovanje, premik in brisanje datotek – možnosti se zato zdoma za zdaj ne ponudijo – ter slika zaslona računalnika.

Izmerjeno (telefon s 576 × 1280 točkami, računalnik z Linuxom, nastavitev »Preizkus: tudi doma prek interneta«): besedilna datoteka se je prek Global Linka odprla približno dve sekundi po dotiku (prvič, z vzpostavitvijo poti); znana mapa je na zaslonu v isti sliki kot njen naslov; prvi vstop v neznano mapo traja okoli pol sekunde. Preizkušeno tudi: glasba, slika in sličica slike prek Global Linka, mreža in seznam z dotikom in s smernimi tipkami.

Ni preizkušeno v živo: resnično tuje omrežje (mobilni podatki), televizor, tablica, izbira ozadja z druge naprave prek Global Linka ter telefon, tablica ali televizor kot vir datotek prek Global Linka.

Predvajalnik: 0.2.46.
