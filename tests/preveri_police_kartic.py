#!/usr/bin/env python3
"""Police kartic Glasbe (Seznami predvajanja, Videospoti) so oznacene s POLICA_KARTIC (TV 9. 11. 2026, apk-17).

Polica je navpicni LinearLayout (naslov + vrsta kartic) brez oznake: brezOdrezanihVrst je ni stel ne kot vidno vrsto ne kot
vrsto, zato je odrezana vrsta Videospoti na spodnjem robu ostala odrezana (posnetek TV po apk-16 in apk-17).
"""
import re
import sys
from pathlib import Path

VIR = (Path(__file__).resolve().parents[1] / "src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt").read_text(encoding="utf-8")


def telo(ime: str) -> str:
    m = re.search(r"\n    private fun " + ime + r"\(", VIR)
    if not m:
        sys.exit(f"NAPAKA: ni funkcije {ime}")
    nasl = re.search(r"\n    (private |override |internal )?fun |\n    // -{10}|\n    /\*\*", VIR[m.end():])
    return VIR[m.start(): m.end() + (nasl.start() if nasl else len(VIR))]


napake = []
for ime in ("policaSeznamov", "policaVidea"):
    if "tag = POLICA_KARTIC" not in telo(ime):
        napake.append(f"{ime}: polica nima tag = POLICA_KARTIC")
b = telo("brezOdrezanihVrst")
m = re.search(r"val jeVrsta = ([^\n]*)", b)
if not m or "POLICA_KARTIC" not in m.group(1):
    napake.append("brezOdrezanihVrst: jeVrsta ne steje police (POLICA_KARTIC)")
if napake:
    print("\n".join("NAPAKA: " + n for n in napake))
    sys.exit(1)
print("OK police kartic")
