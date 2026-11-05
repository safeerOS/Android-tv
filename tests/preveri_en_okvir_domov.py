#!/usr/bin/env python3
"""Domaca stran brskalnika: okvir fokusa samo iz safeer-home.css, ne se nas obris z odmikom (dvojni okvir).

Lastnik 5. 11. 2026, TV: na kartici »RTV SLO« sta bila dva zelena okvirja - rob + senca iz safeer-home.css in
obris 3 px z odmikom, ki ga tv_spatial.js doda za brave_home.html.
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent
js = (KOREN / "assets" / "tv_spatial.js").read_text(encoding="utf-8")
css = (KOREN / "assets" / "safeer-home.css").read_text(encoding="utf-8")
napake = []

m = re.search(r"jeSafeerDomov\s*\n?\s*\?\s*'([^']*)'", js)
if not m:
    napake.append("slog za brave_home.html (jeSafeerDomov ? '...') ni najden")
else:
    slog = m.group(1)
    pravilo = re.search(r"\.safeer-active-card\{([^}]*)\}", slog)
    if not pravilo:
        napake.append("slog domace strani nima pravila .safeer-active-card")
    else:
        telo = pravilo.group(1)
        if not re.search(r"outline\s*:\s*none", telo):
            napake.append("domaca stran: .safeer-active-card mora imeti outline:none (sicer dvojni okvir): " + telo)
        if "outline-offset" in telo or "box-shadow" in telo:
            napake.append("domaca stran: tv_spatial ne sme dodati odmika ali sence - okvir je v safeer-home.css: " + telo)

if not re.search(r"\.hero-card\.safeer-active-card[^{]*\{[^}]*border-color", css, re.S):
    napake.append("safeer-home.css: kartica s fokusom mora imeti svoj okvir (border-color)")

if napake:
    print("PADLO:\n  " + "\n  ".join(napake))
    sys.exit(1)
print("preveri_en_okvir_domov: OK")
