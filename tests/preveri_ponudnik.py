#!/usr/bin/env python3
"""MobileFileProvider: query() in getType() ne vrzeta izjeme (neodvisni pregled 4. 11. 2026).

Po pogodbi ContentProviderja query() ob napaki vrne null; napacna pot (../, mapa izven cache_files) je prej vrgla
FileNotFoundException v proces klicatelja. openFile sme vreci (tako sistem pove, da datoteke ni).
"""
import re
import sys
from pathlib import Path

VIR = (Path(__file__).resolve().parents[1] / "src/main/kotlin/si/safeer/tv/MobileFileProvider.kt").read_text(encoding="utf-8")


def telo(ime: str) -> str:
    m = re.search(r"override fun " + ime + r"\(", VIR)
    if not m:
        sys.exit(f"NAPAKA: ni metode {ime}")
    nasl = re.search(r"\n    override fun |\n    private fun |\n}", VIR[m.end():])
    return VIR[m.start(): m.end() + (nasl.start() if nasl else len(VIR))]


napak = 0
for ime in ("query", "getType"):
    t = telo(ime)
    ok = re.search(r"\)\s*:\s*(Cursor|String)\?\s*=\s*try\s*\{", t) is not None and "catch (_: Exception)" in t and "null" in t
    print(("  OK   " if ok else "  NAPAKA ") + f"{ime}: try/catch in null ob napacni poti")
    napak += 0 if ok else 1
t = telo("openFile")
ok = "throw FileNotFoundException" in t
print(("  OK   " if ok else "  NAPAKA ") + "openFile sme vreci (samo branje, ni datoteke)")
napak += 0 if ok else 1
if napak:
    sys.exit(f"preveri_ponudnik: {napak} napak")
print("preveri_ponudnik: OK")
