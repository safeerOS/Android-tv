#!/usr/bin/env python3
"""Tedenski pregled 25. 11. 2026 (N5/N2): kljuc TMDB ne sme v APK, ki ga CI objavi kot artefakt.

build.gradle vgradi TMDB_KLJUC iz okolja v BuildConfig. Ce ga ima korak gradnje v posu, ki APK nalozi z
upload-artifact, je kljuc v artefaktu (dosegljiv vsakomur z dostopom do teka). Preverba: v nobenem posu z
upload-artifact (ne v koraku, ne v env posa) in ne v env celega delovnega toka se TMDB_KLJUC ne pojavi.
Samo standardna knjiznica (brez PyYAML): posi so kljuci z zamikom 2 pod 'jobs:'.

Uporaba: preveri_ci_skrivnosti.py [pot do gradnja.yml]
"""
import pathlib
import re
import sys

SKRIVNOSTI = ("TMDB_KLJUC",)
pot = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else \
    pathlib.Path(__file__).resolve().parent.parent / ".github/workflows/gradnja.yml"

vrstice = [v.split(" #", 1)[0] for v in pot.read_text(encoding="utf-8").splitlines()
           if not v.lstrip().startswith("#")]

padci = []
try:
    zacetek_jobs = next(i for i, v in enumerate(vrstice) if v.rstrip() == "jobs:")
except StopIteration:
    print(f"PADEL: v {pot} ni 'jobs:'")
    sys.exit(1)

# Vse pred 'jobs:' (tudi env celega toka) velja za vse pose.
glava = "\n".join(vrstice[:zacetek_jobs])
for s in SKRIVNOSTI:
    if s in glava:
        padci.append(f"{s} je na ravni celega delovnega toka (velja tudi za pos z artefaktom)")

posi = {}
ime = None
for v in vrstice[zacetek_jobs + 1:]:
    m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", v)
    if m:
        ime = m.group(1)
        posi[ime] = []
    elif ime is not None:
        posi[ime].append(v)

z_artefaktom = [p for p, telo in posi.items() if any("actions/upload-artifact" in v for v in telo)]
if not z_artefaktom:
    padci.append("kontrola: ni najden noben pos z actions/upload-artifact (razclenitev ne dela?)")
for p in z_artefaktom:
    telo = "\n".join(posi[p])
    for s in SKRIVNOSTI:
        if s in telo:
            padci.append(f"pos '{p}' nalozi artefakt in v gradnji uporabi {s} - kljuc bi bil v objavljenem APK")

print(f"posi: {', '.join(posi)}; z artefaktom: {', '.join(z_artefaktom) or '-'}")
if padci:
    for p in padci:
        print("PADEL:", p)
    sys.exit(1)
print("OK: pos z artefaktom ne dobi skrivnosti", ", ".join(SKRIVNOSTI))
