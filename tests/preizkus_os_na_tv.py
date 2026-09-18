#!/usr/bin/env python3
"""Dimni preizkus Safeer OS na pravem televizorju (brez tveganja za nastavitve uporabnika).

Zakaj: zaslonov Safeer OS ne pokrivajo enotski testi, zato smo regresije lovili z ocmi na
posnetkih. Ta preizkus naredi tisto, kar se da narediti samodejno in varno:

  1. namesti APK (neobvezno) in zazene Safeer OS,
  2. premika izbiro po domacem zaslonu (samo smerne tipke - nicesar ne vklopi in ne izklopi),
  3. odpre Nastavitve (gumb Start na ploscku) in se vrne,
  4. prebere dnevnik in zahteva, da ni sesute seje (FATAL) ne blokade (ANR),
  5. prebere porabo pomnilnika in zahteva, da je pod mejo,
  6. na koncu preveri, da je Safeer OS se vedno v ospredju.

Namenoma **ne** pritiska OK po nastavitvah in ne uporablja `monkey` z nakljucnimi kliki: ta bi lahko
uporabniku izklopil Scit ali preklopil nacin delovanja. Vsi posnetki zaslona se shranijo, da je po
preizkusu mogoce pogledati, kako je bilo videti.

Uporaba:
    python3 tests/preizkus_os_na_tv.py 192.168.0.77:5555 [--namesti] [--mapa /pot/za/posnetke]
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time

PAKET = "si.safeer.os"
#: Koliko pomnilnika (PSS) je se v redu za lupino domacega zaslona na televizorju z 2 GB.
NAJVEC_PSS_KB = 160_000
KOREN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def adb(naprava: str, *argumenti: str, tiho: bool = False) -> str:
    ukaz = ["adb"] + (["-s", naprava] if naprava else []) + list(argumenti)
    r = subprocess.run(ukaz, capture_output=True, text=True, timeout=120)
    if r.returncode != 0 and not tiho:
        raise RuntimeError("adb %s: %s" % (" ".join(argumenti), (r.stderr or r.stdout).strip()))
    return r.stdout


def tipke(naprava: str, *kode: int, premor: float = 0.35) -> None:
    for k in kode:
        adb(naprava, "shell", "input", "keyevent", str(k))
        time.sleep(premor)


def posnetek(naprava: str, mapa: str, ime: str) -> str:
    pot = os.path.join(mapa, ime + ".png")
    with open(pot, "wb") as d:
        r = subprocess.run(["adb"] + (["-s", naprava] if naprava else []) + ["exec-out", "screencap", "-p"],
                           capture_output=True, timeout=60)
        d.write(r.stdout)
    return pot


def pss(naprava: str) -> int:
    izpis = adb(naprava, "shell", "dumpsys", "meminfo", PAKET)
    m = re.search(r"TOTAL PSS:\s*(\d+)", izpis) or re.search(r"TOTAL\s+(\d+)", izpis)
    return int(m.group(1)) if m else 0


def v_ospredju(naprava: str) -> bool:
    izpis = adb(naprava, "shell", "dumpsys", "activity", "activities", tiho=True)
    for vrstica in izpis.splitlines():
        if "mResumedActivity" in vrstica or "topResumedActivity" in vrstica:
            return PAKET in vrstica
    return PAKET in izpis


def main() -> int:
    p = argparse.ArgumentParser(description="Dimni preizkus Safeer OS na televizorju")
    p.add_argument("naprava", help="naslov televizorja, npr. 192.168.0.77:5555")
    p.add_argument("--namesti", action="store_true", help="pred preizkusom namesti Safeer-OS.apk iz korena")
    p.add_argument("--mapa", default=os.path.join(KOREN, "build", "preizkus-os"), help="kam s posnetki")
    a = p.parse_args()

    os.makedirs(a.mapa, exist_ok=True)
    adb("", "connect", a.naprava, tiho=True)

    if a.namesti:
        apk = os.path.join(KOREN, "Safeer-OS.apk")
        if not os.path.isfile(apk):
            print("NAPAKA: ni %s - najprej zazeni gradnjo." % apk)
            return 2
        print("namescam", apk)
        adb(a.naprava, "install", "-r", apk)

    adb(a.naprava, "logcat", "-c", tiho=True)
    adb(a.naprava, "shell", "am", "force-stop", PAKET)
    time.sleep(1)
    adb(a.naprava, "shell", "monkey", "-p", PAKET, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")
    time.sleep(6)
    posnetek(a.naprava, a.mapa, "01-domov")

    # Samo premikanje izbire: desno, levo, dol, gor. Nic se ne odpre in nic se ne preklopi.
    tipke(a.naprava, 22, 22, 22, 20, 21, 21, 19, 20, 20, 22, 19, 21)
    posnetek(a.naprava, a.mapa, "02-po-premikanju")

    # Nastavitve odpre gumb Start na ploscku (deterministicno, ne glede na vrstni red kartic).
    adb(a.naprava, "shell", "input", "gamepad", "keyevent", "108")
    time.sleep(3)
    posnetek(a.naprava, a.mapa, "03-nastavitve")
    tipke(a.naprava, 20, 20, 19)          # samo premikanje po seznamu
    adb(a.naprava, "shell", "input", "gamepad", "keyevent", "97")   # B = nazaj
    time.sleep(2)
    posnetek(a.naprava, a.mapa, "04-nazaj-domov")

    napake = []
    dnevnik = adb(a.naprava, "logcat", "-d", tiho=True)
    for vrstica in dnevnik.splitlines():
        if "FATAL EXCEPTION" in vrstica or ("ANR in" in vrstica and PAKET in vrstica):
            napake.append(vrstica.strip())
    poraba = pss(a.naprava)
    if poraba > NAJVEC_PSS_KB:
        napake.append("pomnilnik: %d kB (meja %d kB)" % (poraba, NAJVEC_PSS_KB))
    if not v_ospredju(a.naprava):
        napake.append("Safeer OS ni vec v ospredju - zaslon je najbrz odsel ali se sesul")

    print("posnetki:", a.mapa)
    print("pomnilnik (PSS): %d kB" % poraba)
    if napake:
        print("NAPAKE:")
        for n in napake:
            print(" -", n)
        return 1
    print("OK: zagon, premikanje, Nastavitve in vrnitev brez sesutja; pomnilnik v mejah.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
