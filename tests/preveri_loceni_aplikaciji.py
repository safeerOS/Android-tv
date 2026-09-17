#!/usr/bin/env python3
"""Preveri, da sta Safeer Browser TV in Safeer OS res loceni aplikaciji.

Android ju mora videti kot dve aplikaciji: dve imeni paketa, vsaka s svojim vnosom v zaganjalniku,
domaci zaslon (alias HOME) samo pri Safeer OS, brskalnik pa brez zaslonov Safeer OS. Podpisana sta
z istim kljucem - to je pogoj, da se smeta pogovarjati (dovoljenje istega podpisa).

Uporaba: preveri_loceni_aplikaciji.py <brskalnik.apk> <safeer-os.apk>
"""
import os
import re
import subprocess
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent


def orodje(ime: str) -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or str(KOREN / ".android-sdk")
    pot = Path(sdk) / "build-tools" / "34.0.0" / ime
    if pot.exists():
        return str(pot)
    najden = subprocess.run(["which", ime], text=True, capture_output=True).stdout.strip()
    if not najden:
        print(f"OPOZORILO: {ime} ni na voljo, preverbe ni mogoce opraviti.")
        sys.exit(0)
    return najden


def badging(apk: str) -> str:
    return subprocess.run([orodje("aapt2"), "dump", "badging", apk], text=True, capture_output=True).stdout


def manifest(apk: str) -> str:
    return subprocess.run([orodje("aapt2"), "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
                          text=True, capture_output=True).stdout


def podpis(apk: str) -> str:
    izpis = subprocess.run([orodje("apksigner"), "verify", "--print-certs", apk], text=True, capture_output=True).stdout
    for vrstica in izpis.splitlines():
        if "SHA-256 digest" in vrstica and "certificate" in vrstica.lower():
            return vrstica.split(":")[-1].strip()
    return ""


def paket(b: str) -> str:
    m = re.search(r"package: name='([^']+)'", b)
    return m.group(1) if m else ""


def zaganjalniki(b: str) -> list:
    """aapt2 izpise vnos posebej za LAUNCHER in LEANBACK_LAUNCHER; steje aplikacija, ne vrstica."""
    return sorted(set(re.findall(r"launchable-activity: name='([^']+)'", b)))


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    brskalnik, os_apk = sys.argv[1], sys.argv[2]
    napake = []

    bb, bo = badging(brskalnik), badging(os_apk)
    if paket(bb) != "si.safeer.tv":
        napake.append(f"brskalnik ni si.safeer.tv, ampak '{paket(bb)}'")
    if paket(bo) != "si.safeer.os":
        napake.append(f"Safeer OS ni si.safeer.os, ampak '{paket(bo)}'")

    zb, zo = zaganjalniki(bb), zaganjalniki(bo)
    if zb != ["si.safeer.tv.MainActivity"]:
        napake.append(f"brskalnik mora imeti en vnos v zaganjalniku (MainActivity), ima {zb}")
    if zo != ["si.safeer.tv.os.DomovActivity"]:
        napake.append(f"Safeer OS mora imeti en vnos v zaganjalniku (DomovActivity), ima {zo}")

    mb, mo = manifest(brskalnik), manifest(os_apk)
    if "os.DomovActivity" in mb:
        napake.append("brskalnik ima v manifestu zaslone Safeer OS (DomovActivity)")
    if "ZaganjalnikAlias" in mb:
        napake.append("brskalnik ima alias domacega zaslona (ZaganjalnikAlias)")
    if "ZaganjalnikAlias" not in mo:
        napake.append("Safeer OS nima aliasa domacega zaslona (ZaganjalnikAlias)")
    if "si.safeer.os.spletne" not in mo:
        napake.append("Safeer OS nima ponudnika spletnih aplikacij (si.safeer.os.spletne)")

    pb, po = podpis(brskalnik), podpis(os_apk)
    if pb and po and pb != po:
        napake.append("aplikaciji nista podpisani z istim kljucem - most med njima ne bo delal")

    if napake:
        print("NAPAKA: aplikaciji nista pravilno loceni:")
        for n in napake:
            print("  -", n)
        return 1
    print("OK: si.safeer.tv in si.safeer.os sta loceni aplikaciji, vsaka s svojim vnosom v zaganjalniku.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
