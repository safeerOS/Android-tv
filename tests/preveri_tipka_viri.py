#!/usr/bin/env python3
"""Tipka Viri (SOURCES) na televizorju, 21. 11. 2026: alias zaslona virov (ViriAlias) vklopi ViriActivity.uskladi. Klic je
bil samo v DomovActivity, pravi domaci zaslon TV pa je PokrajinaActivity (nanj kaze ZaganjalnikAlias) - alias je ostal
izklopljen in tipka ni pokazala nicesar. Staticna preverba: zaslon, na katerega kaze ZaganjalnikAlias, klice uskladi
v onResume. Ali se na napravi pokaze izbirnik (Philipsov predal + nas zaslon), je NEPREVERJENO brez naprave.
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parents[1]
napak = 0


def preveri(opis: str, pogoj: bool) -> None:
    global napak
    if not pogoj:
        napak += 1
        print("NAPAKA: " + opis)


manifest = (KOREN / "AndroidManifest.xml").read_text(encoding="utf-8")
# Alias Domov televizorja je v manifestu okusa »os« (okusi/os); tablica in telefon imata svojega in nimata tipke Viri.
okus = (KOREN / "okusi/os/AndroidManifest.xml").read_text(encoding="utf-8")
m = re.search(r'<activity-alias[^>]*android:name="si\.safeer\.tv\.os\.ZaganjalnikAlias"[^>]*>', okus, re.S)
preveri("okusi/os/AndroidManifest: ZaganjalnikAlias obstaja", m is not None)
cilj = re.search(r'android:targetActivity="([^"]+)"', m.group(0)).group(1) if m else ""
preveri("ZaganjalnikAlias ima targetActivity", bool(cilj))
if cilj:
    pot = KOREN / "src/main/kotlin" / (cilj.replace(".", "/") + ".kt")
    preveri("izvorna datoteka domacega zaslona obstaja: " + cilj, pot.is_file())
    if pot.is_file():
        vir = pot.read_text(encoding="utf-8")
        r = re.search(r"override fun onResume\(\)\s*\{(.*?)\n    \}", vir, re.S)
        preveri(cilj + ": onResume obstaja", r is not None)
        preveri(cilj + ": onResume klice ViriActivity.uskladi (sicer tipka Viri ne pokaze nicesar)",
                bool(r) and "ViriActivity.uskladi(" in r.group(1))
preveri("AndroidManifest: ViriAlias sprejme VIEW_INPUTS in SOURCE_LIST",
        "com.android.tv.action.VIEW_INPUTS" in manifest and "org.droidtv.intent.action.SOURCE_LIST" in manifest)
if napak:
    sys.exit(1)
print("preveri_tipka_viri: OK")
