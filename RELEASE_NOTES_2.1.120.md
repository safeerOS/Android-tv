# Safeer Browser for Android TV 2.1.120 · Safeer OS 0.4.3 · Safeer OS Tablet 0.3.3

**Safeer OS: edit your computer's files from the TV and the tablet** (with Safeer Control 2.1.0 or newer on the computer).

- **Files → hold OK** on a file or folder of the computer: *Rename*, *Move to folder*, *Delete*. Deleting never destroys anything: the file goes to the computer's Trash, where you can restore it. On the tablet: long press.
- **Pictures:** hold OK (tablet: buttons at the top right) → *Rotate left / right*. The rotation is saved on the computer, so the picture is upright everywhere from then on. A JPEG photo keeps its original pixels – only the EXIF orientation is changed; other images are rotated by the computer.
- **Fix: portrait photos are shown upright.** A photo taken with a phone in portrait is stored landscape with an orientation tag; the viewer now honours it (computer, phone, tablet and USB files alike). Before, such a photo was shown on its side.
- Pictures are still downscaled to the screen size while decoding (TV memory).

**Safeer Link hub: one device that stops reading no longer holds up the others.**

- Writing to a socket blocks and has no deadline, and the hub announces the device list to everyone in turn, so a device that stopped reading its data (a frozen app, a dropped Wi-Fi) used to delay every other device's sign-in for as long as its own write took.
- Each connection now has its own outgoing queue and a single writer thread: sending never waits for a device, stale device lists in the queue are merged into the newest one, a device that falls far enough behind (64 frames / 512 KB) has its connection closed and can come straight back with a new one, and closing a connection never waits longer than 300 ms.
- The device register (who is connected, and what happens when the same device returns) now lives in its own class; behaviour is unchanged.

Slovensko: **Središče (hub):** naprava, ki neha brati (zamrznjena aplikacija, izgubljen WiFi), ne zadrži več prijave in objav vsem drugim – vsaka povezava ima svojo izhodno vrsto in svojo pisalno nit, zastareli seznami naprav se združijo, kdor preveč zaostane, dobi zaprto povezavo in se lahko takoj vrne. **Datoteke računalnika urejaš s TV in tablice** (s Safeer Controlom 2.1.0+): zadržan OK na datoteki ali mapi → *Preimenuj*, *Premakni v mapo*, *Izbriši* (datoteka gre v **Smeti** računalnika, ne izgine). **Slike:** zadržan OK (tablica: gumbi zgoraj desno) → *Zavrti v levo / desno* – vrtenje se shrani na računalniku (JPEG brez izgube, samo oznaka EXIF). **Popravek:** pokončne fotografije s telefona se kažejo pokončno (pregledovalnik upošteva EXIF orientacijo); prej so bile na boku.
