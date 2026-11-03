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
# Zunanji pregled Video 3. 11. 2026
preveri("mreza filmov: najvec MREZA_HKRATI", "OsPravila.MREZA_HKRATI - o.caka.get()" in g and "minOf(o.proracun, 12 - o.caka.get()" not in g)
preveri("mreza filmov: ne med tipkami in po kliku", "OsPravila.smemPreverjatiMrezo(klikNaFilm, odTipke)" in g)
preveri("klik na film ustavi plakate in preverjanje", re.search(r"private fun razresiStremio\(.*?klikNaFilm = true\s+izprazniVrstoSlik", g, re.S) is not None)
preveri("plakati: onTrimMemory in OOM izpraznita vrsto", "izprazniVrstoSlik(\"onTrimMemory" in g and "izprazniVrstoSlik(\"OutOfMemoryError\")" in g)
preveri("plakati: niti po nitiSlik", "OsPravila.nitiSlik(Naprava.jeTelevizor(this))" in g)
# Zunanji pregled podnapisov 3. 11. 2026
pa = re.sub(r"(?m)^\s*//.*$", "", re.sub(r"/\*.*?\*/", "", (OS / "PredvajanjeActivity.kt").read_text(encoding="utf-8"), flags=re.S))
preveri("podnapisi: sele po READY 1 s", "OsPravila.podnapisiZdaj(pripravljen, pripravljenOd, zdaj)" in pa)
preveri("podnapisi: samo zadnji dodatek", "OsPravila.dodatkiZaPodnapise(" in pa)
preveri("podnapisi: med predvajanjem ob premoru", "OsPravila.podnapisZdajAliObPremoru(p.isPlaying)" in pa)
preveri("podnapisi: HLS na gumbu, brez iskanja", "GlasbaStoritev.jeHls()" in pa and "R.string.podnapisi_ne_gre else" in pa)
print("VSE OK" if napak == 0 else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
