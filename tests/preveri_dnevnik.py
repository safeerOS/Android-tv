#!/usr/bin/env python3
"""Dnevnik ne sme vsebovati naslovov tokov in virov (naloga 112).

Naslov toka ali vira ima lahko uporabnisko ime in geslo, zeton v poti (»/play/<zeton>«) ali v poizvedbi. V dnevnik gre
samo prek si.safeer.tv.os.Zasebno (zaDnevnik, gostitelj) ali kot gostitelj (.host). Preverba gre cez kodo medijev, casta
in Linka (os/, cast/, link/); brskalnik (zavihki, blokade) ni zajet - tam je naslov strani namen dnevnika.

Izjema v isti vrstici: komentar »// dnevnik-ok: <razlog>«.
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv"
MAPE = ["os", "cast", "link"]
DNEVNIK = re.compile(r"\bLog\.[iwdev]\(")
# ${...} ali $ime v nizu
VSTAVEK = re.compile(r"\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")
TVEGANO = re.compile(r"\b(zvok|povezava|url|urlStr)\b")
VARNO = re.compile(r"Zasebno\.|\.host\b|gostitelj\(|\.length\b|\.size\b|\.isBlank\(\)|\.isNotBlank\(\)|\.isEmpty\(\)|\.isNotEmpty\(\)|\.startsWith\(|hashCode\(\)|[=!]= null")

napake = []
pregledanih = 0
for mapa in MAPE:
    for pot in sorted((KOREN / mapa).rglob("*.kt")):
        for st, vrstica in enumerate(pot.read_text(encoding="utf-8").splitlines(), 1):
            if not DNEVNIK.search(vrstica) or "dnevnik-ok:" in vrstica:
                continue
            pregledanih += 1
            for m in VSTAVEK.finditer(vrstica):
                izraz = m.group(1) if m.group(1) is not None else m.group(2)
                if TVEGANO.search(izraz) and not VARNO.search(izraz):
                    napake.append(f"{pot.relative_to(KOREN)}:{st}: ${{{izraz}}}")

print(f"preverjenih vrstic dnevnika: {pregledanih}")
for n in napake:
    print("  NAPAKA naslov v dnevniku: " + n)
if napake:
    print("Uporabi Zasebno.zaDnevnik(naslov) ali Zasebno.gostitelj(naslov).")
    sys.exit(1)
print("  OK   dnevnik brez naslovov tokov in virov")
