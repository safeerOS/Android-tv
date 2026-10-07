# Safeer 2.1.187 · Safeer OS 0.5.63

## Popravek: gledalec deljenega zaslona lahko napravo spet upravlja

V 0.5.62 (dostop po napravah) je naprava, ki deli svoj zaslon, zavrnila dotik gledalca, kadar je središče Safeer Linka na napravi z Androidom. Dotik gledalca pride do nje kot ukaz posebnega pošiljatelja »gledalec«, ki ni naprava v Linku in zato nima zapisa o dostopu. Slika se je videla, upravljanje ni delovalo.

Zdaj dotik, poteg in tipka gledalca veljajo, dokler naprava sama deli zaslon, in samo, če je napravi, s katero ga deli, na njej odprto »Zaslon in upravljanje«:

- Naprave, ki so ob uvedbi dovoljenj obdržale poln dostop, delajo kot pred 0.5.62.
- Napravi brez tega dostopa zaslon lahko pokažeš, upravljati ga ne more. To je novo: prej je deljeni zaslon lahko upravljal vsak gledalec.
- Gledalec ne more nič drugega – datotek, programov in drugih ukazov zanj ni.
- Dotik velja samo z znakom deljenja, ki ga doda središče, ki deljenje gosti. Naprava v Linku, ki bi se središču le predstavila z imenom »gledalec«, znaka ne pozna in dotika ne more poslati.

Zaradi znaka deljenja mora imeti 0.5.63 tudi naprava, ki je središče: prek središča s starejšo različico se zaslon vidi, upravljati pa ga ni mogoče.

Napaka je bila ugotovljena z branjem kode. Ne napaka ne popravek nista bila izmerjena v živo: pot zahteva deljenje zaslona prek središča na napravi z Androidom. Pravilo in znak deljenja pokrivajo samodejni preizkusi (`tests/DostopPravilaTest.kt`, `tests/TokoviTest.kt`). Računalnik kot središče te poti nima in ni bil prizadet.

Safeer Browser za Android TV 2.1.187 izide skupaj s Safeer OS 0.5.63; drugih sprememb ni. Predvajalnik ostane 0.2.49.

---

English: **Fix: the viewer of a shared screen can control the device again.** In 0.5.62 (per-device access) a device sharing its screen refused the viewer's touch when the Safeer Link hub runs on an Android device: the touch reaches the device as a command from a special sender, "gledalec" (viewer), which is not a device in the Link and therefore has no access record. The picture was shown, control did not work. Now the viewer's tap, swipe and key are accepted while the device itself is sharing its screen, and only if the device it is shared with has "Screen and control" open on it. Devices that kept full access when permissions were introduced work as before 0.5.62; a device without that access can be shown the screen but cannot control it (new: before, any viewer of a shared screen could control it); the viewer can do nothing else. The touch is accepted only with a share mark added by the hub that hosts the share; a device in the Link that merely introduces itself to the hub under the name "gledalec" does not know the mark and cannot send a touch. Because of the share mark the device acting as the hub needs 0.5.63 as well: through a hub on an older version the screen is shown but cannot be controlled. The bug was found by reading the code; neither the bug nor the fix was measured live (the path needs a screen share through a hub on an Android device). The rule and the share mark are covered by automated tests. A computer as the hub does not have this path and was not affected. Safeer Browser for Android TV 2.1.187 is released together with Safeer OS 0.5.63 with no other changes; the player stays at 0.2.49.
