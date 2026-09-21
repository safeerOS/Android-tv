# Safeer Browser for Android TV 2.1.120 · Safeer OS 0.4.3 · Safeer OS Tablet 0.3.3

**Safeer OS: edit your computer's files from the TV and the tablet** (with Safeer Control 2.1.0 or newer on the computer).

- **Files → hold OK** on a file or folder of the computer: *Rename*, *Move to folder*, *Delete*. Deleting never destroys anything: the file goes to the computer's Trash, where you can restore it. On the tablet: long press.
- **Pictures:** hold OK (tablet: buttons at the top right) → *Rotate left / right*. The rotation is saved on the computer, so the picture is upright everywhere from then on. A JPEG photo keeps its original pixels – only the EXIF orientation is changed; other images are rotated by the computer.
- **Fix: portrait photos are shown upright.** A photo taken with a phone in portrait is stored landscape with an orientation tag; the viewer now honours it (computer, phone, tablet and USB files alike). Before, such a photo was shown on its side.
- Pictures are still downscaled to the screen size while decoding (TV memory).

**…and the photos on your phone, tablet or another TV** (Safeer Mobile 1.0.32 / Safeer OS 0.4.3 / 0.3.3 or newer on that device).

- **Hold OK → Rotate left / right, Delete.** A landscape photo becomes portrait with one press.
- **Android asks the owner first.** A photo the device did not create itself may only be changed with consent, so the device that holds it shows the system question. The TV waits for the answer and shows the result by itself — the photo turns or disappears on the TV as soon as you tap *Allow* on the device. If you deny, the TV says so; if nobody answers within 25 s, the TV tells you to confirm on the device and the action still completes when you do. For the same photo you are asked only once.
- **Only what works is offered.** Phones and tablets keep photos in the media library, which has no folders and whose names belong to the record, so *Rename*, *Move* and *Open on the computer* are not shown for them. The hint and the delete question have their own wording for devices.
- **Delete = the device's Trash.**

**Tablet: notifications, asked once.** Android does not let an app open a window from the background, and without notification permission the system silently drops the notification too — so confirmations never appeared, and neither did **screen sharing** to the tablet. The tablet now asks once, on first start. Both work again, also when Safeer is in the background.

**Safeer Link hub: one device that stops reading no longer holds up the others.**

- Writing to a socket blocks and has no deadline, and the hub announces the device list to everyone in turn, so a device that stopped reading its data (a frozen app, a dropped Wi-Fi) used to delay every other device's sign-in for as long as its own write took.
- Each connection now has its own outgoing queue and a single writer thread: sending never waits for a device, stale device lists in the queue are merged into the newest one, a device that falls far enough behind (64 frames / 512 KB) has its connection closed and can come straight back with a new one, and closing a connection never waits longer than 300 ms.
- The device register (who is connected, and what happens when the same device returns) now lives in its own class; behaviour is unchanged.

**Safeer OS home: smoother to use with the remote.**

- The home section is now called **Safeer OS apps** – it shows the apps of every device in Safeer Link, not only this TV.
- **OK opens only what you chose.** A second press of OK carried over from the previous screen (or a key held a moment too long) could open the first app in *All apps* – usually the browser. A screen now ignores OK for half a second after it appears and never acts on a key release whose press it did not see; the list also no longer pulls the focus to the first app when it finishes loading after you have already moved, and the focus stays on the filter you picked.
- **Apps grow out of their tile.** An app on this TV now opens with a zoom from the tile you pressed instead of a cut to black, so the app's own start-up screen follows without a jump.

Slovensko: **Datoteke računalnika urejaš s TV in tablice** (s Safeer Controlom 2.1.0+): zadržan OK → *Preimenuj*, *Premakni v mapo*, *Izbriši* (v **Smeti** računalnika). **Slike:** *Zavrti v levo / desno* – shrani se na računalniku (JPEG brez izgube). **Enako za fotografije na telefonu, tablici in drugem televizorju:** zavrti (ležeča → pokončna z enim pritiskom) ali izbriši (v Smeti naprave). Za tujo fotografijo **Android najprej vpraša lastnika** na tisti napravi – televizor počaka na odgovor in izid pokaže sam; ob zavrnitvi to pove. Za naprave so na voljo le dejanja, ki tam delujejo (brez preimenovanja, premika in odpiranja na računalniku). **Tablica ob prvem zagonu enkrat vpraša za obvestila** – brez njih se nista pokazala ne vprašanje za potrditev ne **deljenje zaslona**; zdaj oboje spet dela, tudi ko je Safeer v ozadju. **Popravek:** pokončne fotografije se kažejo pokončno. **Domača stran Safeer OS:** razdelek se imenuje **Aplikacije Safeer OS**; OK odpre samo, kar si izbral (brez nehotenega odpiranja brskalnika v *Vse aplikacije*); aplikacija se odpre s povečavo iz ploščice namesto črnega zaslona. **Središče (hub):** naprava, ki neha brati, ne zadrži več drugih – vsaka povezava ima svojo izhodno vrsto.
