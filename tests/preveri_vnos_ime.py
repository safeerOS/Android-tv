#!/usr/bin/env python3
"""Storitev dostopnosti »Safeer Vnos« mora imeti v nastavitvah dostopnosti razlicno ime po aplikaciji (19. 11. 2026).

Lastnik je na telefonu v Dostopnost > Namescene aplikacije videl DVA enaka »Safeer Vnos« (si.safeer.phone in si.safeer.os,
oba z istim razredom VnosStoritev) in ni vedel, katerega vklopiti. Ime storitve (@string/vnos_ime) zato nosi ime aplikacije v
oklepaju, v vseh jezikih; posamezen okus ima natanko eno definicijo (dotik je skupen telefonu in tablici).
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent / "okusi"
# okus -> mape z viri (kot v build.gradle: telefon in tablica delita okusi/dotik/res), pricakovano ime aplikacije v oklepaju
OKUSI = {
    "os": (["os"], "Safeer OS"),
    "telefon": (["dotik", "telefon"], "Safeer OS Mobile"),
    "tablica": (["dotik", "tablica"], "Safeer OS Tablet"),
}
JEZIKI = ["values", "values-sl", "values-en", "values-de", "values-es", "values-fr", "values-it"]
VNOS = re.compile(r'<string name="vnos_ime"[^>]*>([^<]*)</string>')

napake = []
for jezik in JEZIKI:
    imena = {}
    for okus, (mape, aplikacija) in OKUSI.items():
        najdeno = []
        for mapa in mape:
            for pot in sorted((KOREN / mapa / "res" / jezik).glob("*.xml")):
                najdeno += VNOS.findall(pot.read_text(encoding="utf-8"))
        if len(najdeno) != 1:
            napake.append(f"{jezik}/{okus}: vnos_ime definiran {len(najdeno)}x (mora natanko 1x)")
            continue
        ime = najdeno[0]
        imena[okus] = ime
        if not ime.endswith(f"({aplikacija})"):
            napake.append(f"{jezik}/{okus}: »{ime}« ne konca z »({aplikacija})«")
    if len(set(imena.values())) != len(imena):
        napake.append(f"{jezik}: imena niso razlicna po okusih: {imena}")

if napake:
    print("VNOS IME: NAPAKE")
    for n in napake:
        print(" -", n)
    sys.exit(1)
print(f"VNOS IME: OK ({len(JEZIKI)} jezikov x {len(OKUSI)} okusov)")
