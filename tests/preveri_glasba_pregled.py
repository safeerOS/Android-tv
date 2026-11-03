#!/usr/bin/env python3
"""Zunanji pregled Glasbe 3. 11. 2026: odlocitve iz OsPravilaTest (vrstaZZvokom, zaGlasbo, jeKoncert) morajo klicati pravi
razredi (GlasbaActivity - Android). Brez popravka pade."""
import pathlib, re, sys

OS = pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os"
napak = 0


def preveri(opis, pogoj):
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    napak += 0 if pogoj else 1


t = (OS / "GlasbaActivity.kt").read_text(encoding="utf-8")
t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
g = re.sub(r"(?m)^\s*//.*$", "", t)
preveri("klik na skladbo: vrsta samo z zvokom", "OsPravila.vrstaZZvokom(seznam.size, i)" in g)
preveri("jezik: izjema samo koncert", g.count("OsPravila.jeKoncert(it.video, Stremio.jeEnota(it), it.genres, it.naslov)") == 2)
preveri("priljubljene v Glasbi po zaGlasbo", "skladbe(p.filter { OsPravila.zaGlasbo(" in g and "skladbe(p.filterNot { it.video || it.radio })" not in g)
s = re.search(r"private fun sorodnePolice\(.*?\n    \}\n", g, re.S)
s = s.group(0) if s else ""
preveri("prazna Glasba: brez radijskih postaj", "os_media_sorodno_postaje" not in s)
preveri("prazna Glasba: TV kanali samo v Radiu", "if (radio && zaTv.isNotEmpty())" in s)
preveri("prazna Glasba: nedavne skladbe in seznami", "R.string.os_media_nedavno" in s and "seznamiZaGlasbo()" in s)
print("VSE OK" if napak == 0 else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
