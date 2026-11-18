#!/usr/bin/env python3
"""Pas Doma sega do roba zaslona: vsebina Medijskega centra mora ob Domu izklopiti clipToPadding (ne samo clipChildren).

Izmerjeno 18. 11. 2026: ViewGroup z odmikom obreze otroke na polnilo, tudi ce je clipChildren=false; pas je ostal na
60..1860 px. Preverba brez popravka pade (vrstice »vsebina.clipToPadding« ni).
"""
import sys
from pathlib import Path

vir = (Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt").read_text(encoding="utf-8")
if "vsebina.clipToPadding = razdelek != DOMOV" not in vir:
    print("NAPAKA: vsebina Doma ne izklopi clipToPadding - pas bo obrezan na odmik")
    sys.exit(1)
print("OK: pas Doma ni obrezan na polnilo vsebine")
