#!/usr/bin/env python3
"""Preveri, da ima APK v dex vse razrede, ki jih navaja njegov manifest (activity, service, receiver, provider,
activity-alias/targetActivity, application).

Zakaj: 19. 10. 2026 je telefon 0.5.89-0.5.92 ob zagonu padel (ClassNotFoundException DomovTabletActivity) - manifest
okusa telefon je navajal razred iz src/tablica, gradnja pa te mape za telefon ni vec prevajala. Gradle in aapt2 tega
ne opazita; napaka se pokaze sele na napravi. Pozeni po vsaki gradnji izdaje:

    python3 tools/preveri-apk-razrede.py Safeer-OS.apk Safeer-OS-Mobile.apk Safeer-OS-Tablet.apk ...

Izhod 0 = vsi razredi so v dex; 1 = manjka vsaj en (izpise ga).
"""
import os
import re
import subprocess
import sys
import zipfile

KORENSKA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAPT2 = os.environ.get("AAPT2") or os.path.join(KORENSKA, ".android-sdk/build-tools/36.0.0/aapt2")
OZNAKE = ("activity", "activity-alias", "service", "receiver", "provider", "application")
IME = re.compile(r'A: http://schemas\.android\.com/apk/res/android:(name|targetActivity)\(0x[0-9a-f]+\)="([^"]+)"')


def razredi_manifesta(apk: str) -> set:
    """Imena razredov komponent iz prevedenega manifesta (aapt2 dump xmltree)."""
    izpis = subprocess.run([AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                           capture_output=True, text=True, timeout=120, check=True).stdout
    paket = re.search(r'A: package="([^"]+)"', izpis)
    paket = paket.group(1) if paket else ""
    razredi, element, globina = set(), "", -1
    for vrstica in izpis.splitlines():
        zamik = len(vrstica) - len(vrstica.lstrip())
        el = re.match(r"\s*E: ([\w-]+)", vrstica)
        if el:
            element = el.group(1) if el.group(1) in OZNAKE else ""
            globina = zamik
            continue
        a = IME.search(vrstica)
        # Samo atributi same komponente (ne <meta-data android:name>, <action android:name> ...).
        if a and element and zamik == globina + 2:
            # Ime activity-alias ni razred (razred je targetActivity); pri drugih komponentah je razred name.
            if (element == "activity-alias") != (a.group(1) == "targetActivity"):
                continue
            ime = a.group(2)
            if ime.startswith("."):
                ime = paket + ime
            if "." in ime:
                razredi.add(ime)
    return razredi


def razredi_dex(apk: str) -> bytes:
    with zipfile.ZipFile(apk) as z:
        return b"".join(z.read(i) for i in z.namelist() if re.fullmatch(r"classes\d*\.dex", i))


def main(apki: list) -> int:
    if not apki:
        print(__doc__.strip())
        return 2
    napaka = False
    for apk in apki:
        dex = razredi_dex(apk)
        vsi = razredi_manifesta(apk)
        manjka = sorted(r for r in vsi if ("L" + r.replace(".", "/") + ";").encode() not in dex)
        if manjka:
            napaka = True
            print("NAPAKA %s: manifest navaja razrede, ki jih ni v dex: %s" % (os.path.basename(apk), ", ".join(manjka)))
        else:
            print("OK %s (%d razredov komponent)" % (os.path.basename(apk), len(vsi)))
    return 1 if napaka else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
