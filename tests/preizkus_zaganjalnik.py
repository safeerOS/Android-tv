#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Meritve prilagodljivega zaganjalnika Safeer OS na pravi napravi (adb) - stevilke, ne vtisi.

  python3 tests/preizkus_zaganjalnik.py NAPRAVA PAKET [--zasuk] [--padec] [--ponovitve N]

  NAPRAVA  serijska ali IP:vrata (adb -s)
  PAKET    si.safeer.tablet | si.safeer.phone | si.safeer.os

Meri:
  - hladen zagon do domacega zaslona (am start -W, TotalTime; mediana N ponovitev),
  - PSS zaganjalnika v mirovanju (dumpsys meminfo, 10 s po zagonu),
  - --zasuk: preris ob zasuku (dnevnik SafeerZaganjalnik »preris ob spremembi okna ... ms«); zasuk vrne, kot je bil,
  - --padec: zascita pred padci - alias domacega zaslona omogoci (NE nastavi ga za privzetega), dvakrat sesuje proces
    (am crash) in preveri, da je alias spet onemogocen (tipka Domov bi sla na prejsnji zaganjalnik).
Zagon nikoli ne spremeni privzetega domacega zaslona naprave.
"""
from __future__ import annotations

import re
import statistics
import subprocess
import sys
import time

AKT = "si.safeer.tv.os.ZaganjalnikActivity"
ALIAS = "si.safeer.tv.os.ZaganjalnikAlias"


def adb(naprava: str, *args: str, preveri: bool = False) -> str:
    r = subprocess.run(["adb", "-s", naprava, *args], capture_output=True, text=True, timeout=120)
    if preveri and r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return r.stdout + r.stderr


def lupina(naprava: str, ukaz: str) -> str:
    return adb(naprava, "shell", ukaz)


def hladen_zagon(naprava: str, paket: str, n: int) -> list[int]:
    casi = []
    for _ in range(n):
        lupina(naprava, f"am force-stop {paket}")
        time.sleep(1.5)
        izpis = lupina(naprava, f"am start -W -n {paket}/{AKT}")
        m = re.search(r"TotalTime:\s*(\d+)", izpis)
        if m:
            casi.append(int(m.group(1)))
        time.sleep(1.0)
    return casi


def pss(naprava: str, paket: str) -> dict[str, int]:
    izpis = lupina(naprava, f"dumpsys meminfo {paket}")
    out: dict[str, int] = {}
    for kljuc, vzorec in (("skupaj", r"TOTAL PSS:\s+(\d+)"), ("java", r"Java Heap:\s+(\d+)"),
                          ("native", r"Native Heap:\s+(\d+)"), ("grafika", r"Graphics:\s+(\d+)"),
                          ("koda", r"Code:\s+(\d+)")):
        m = re.search(vzorec, izpis)
        if m:
            out[kljuc] = int(m.group(1))
    m = re.search(r"WebViews:\s+(\d+)", izpis)
    out["webview"] = int(m.group(1)) if m else -1
    return out


def zasuk(naprava: str) -> list[str]:
    prej_samodejno = lupina(naprava, "settings get system accelerometer_rotation").strip()
    prej_lega = lupina(naprava, "settings get system user_rotation").strip()
    lupina(naprava, "logcat -c")
    try:
        lupina(naprava, "settings put system accelerometer_rotation 0")
        for lega in ("1", "0", "1", "0"):
            lupina(naprava, f"settings put system user_rotation {lega}")
            time.sleep(2.5)
    finally:
        lupina(naprava, f"settings put system user_rotation {prej_lega if prej_lega not in ('', 'null') else '0'}")
        lupina(naprava, f"settings put system accelerometer_rotation {prej_samodejno if prej_samodejno not in ('', 'null') else '1'}")
    dnevnik = lupina(naprava, "logcat -d -s SafeerZaganjalnik:I")
    return [v for v in dnevnik.splitlines() if "preris" in v]


def stanje_aliasa(naprava: str, paket: str) -> str:
    izpis = lupina(naprava, f"dumpsys package {paket}")
    # enabledComponents / disabledComponents seznam
    del_ = izpis.split("disabledComponents:", 1)
    if len(del_) > 1 and ALIAS in del_[1].split("enabledComponents:", 1)[0]:
        return "onemogocen"
    vkl = izpis.split("enabledComponents:", 1)
    if len(vkl) > 1 and ALIAS in vkl[1].split("disabledComponents:", 1)[0]:
        return "omogocen"
    return "privzeto (manifest: onemogocen)"


def padec(naprava: str, paket: str) -> list[str]:
    zapis = []
    lupina(naprava, f"pm enable {paket}/{ALIAS}")
    zapis.append("alias po omogocenju: " + stanje_aliasa(naprava, paket))
    for i in range(2):
        lupina(naprava, f"am start -W -n {paket}/{AKT}")
        time.sleep(2)
        zapis.append(f"padec {i + 1}: " + lupina(naprava, f"am crash {paket}").strip()[:80])
        time.sleep(3)
    zapis.append("alias po dveh padcih: " + stanje_aliasa(naprava, paket))
    # Pospravimo: alias nazaj na privzeto iz manifesta (onemogocen), ne glede na izid.
    lupina(naprava, f"pm default-state {paket}/{ALIAS}")
    zapis.append("alias po pospravljanju: " + stanje_aliasa(naprava, paket))
    return zapis


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    naprava, paket = sys.argv[1], sys.argv[2]
    n = 5
    if "--ponovitve" in sys.argv:
        n = int(sys.argv[sys.argv.index("--ponovitve") + 1])
    model = lupina(naprava, "getprop ro.product.model").strip()
    print(f"== {model} ({naprava}) {paket}")
    casi = hladen_zagon(naprava, paket, n)
    if casi:
        print(f"hladen zagon (TotalTime ms): {casi} -> mediana {statistics.median(casi):.0f} ms")
    time.sleep(10)
    print("PSS v mirovanju (kB):", pss(naprava, paket))
    if "--zasuk" in sys.argv:
        for v in zasuk(naprava):
            print("zasuk:", v.split("SafeerZaganjalnik:", 1)[-1].strip())
    if "--padec" in sys.argv:
        for v in padec(naprava, paket):
            print(v)
    return 0


if __name__ == "__main__":
    sys.exit(main())
