#!/usr/bin/env python3
"""Zunanji pregled 3. 11. 2026 (placljivi kanali v mrezi TV v zivo): odlocitve, ki jih preizkusa KanaliPravilaTest in
OsPravilaTest dokazeta na cistih funkcijah, morajo klicati pravi razredi (GlasbaActivity, Stremio, PregledKanalov - Android).
Brez popravka pade."""
import pathlib, re, sys

OS = pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os"
napak = 0


def preveri(opis, pogoj):
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    napak += 0 if pogoj else 1


def koda(ime):
    t = (OS / ime).read_text(encoding="utf-8")
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", t)


g = koda("GlasbaActivity.kt")
s = koda("Stremio.kt")
p = koda("PregledKanalov.kt")
preveri("mreza: kanal po KanaliPravila.vMrezi", "KanaliPravila.vMrezi(" in g)
preveri("mreza: kandidati dodatkov se preverijo", "preveriKandidate(" in g and "m.kandidati = kandidati" in g)
preveri("mreza: neodklenjena velja tudi za priljubljene", "Stremio.jeEnota(sk) && neodklenjena(sk)" in g and "sk.id !in vednoId && neodklenjena(sk)" not in g)
preveri("mreza: vgrajeni v privzetem po vgrajenVPrivzetem", "KanaliPravila.vgrajenVPrivzetem(" in g)
preveri("Stremio: imaLastenTok zapise placljivo", re.search(r"fun imaLastenTok\(.*?zapisiPlacljivo\(tip, id", s, re.S) is not None)
preveri("Stremio: zaklep uposteva placilni zid", "poOdgovoruTokov(prej ?: OsPravila.Zaklep(), vnosov, predvajljivih, zdaj, placilnih)" in s)
preveri("PregledKanalov: samo JSON tokov po smeVprasatiTokove", "KanaliPravila.smeVprasatiTokove(" in p and "Stremio.imaLastenTok(sk, null)" in p)
preveri("PregledKanalov: dodatek s poverilnico nikoli", "IptvPravila.dodatekSPoverilnico(dodatek)" in p)
print("VSE OK" if napak == 0 else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
