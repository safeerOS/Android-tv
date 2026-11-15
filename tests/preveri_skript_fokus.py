#!/usr/bin/env python3
"""Skript za fokus (tv_spatial.js) na navadnih straneh ne tece (lastnik 15. 11. 2026: »cela stran ni vec skripta, ker nas to
samo upocasni«; izmerjeno 14. 11.: vzrok zatikanja 48 % -> 2 % slabih slicic). Pravilo je v tests/KazalecPravilaTest.kt
(KazalecPravila.skriptZaFokus); tu je preverjeno, da ga vbrizg in lupina res uporabljata:

- UserScriptManager vbrizga tvSpatialJs samo ob pritrdilnem odgovoru skriptZaFokus (brez odlocitve: staro vedenje);
- MainActivity odlocitev nastavi iz KazalecPravila.skriptZaFokus in jo ob unicenju dejavnosti pocisti;
- ob preklopu na puscice (VirtualPointerView.naSpremembo) se skript vbrizga v ze nalozeno stran.
"""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "src/main/kotlin/si/safeer/tv"
napak = 0


def preveri(opis: str, pogoj: bool) -> None:
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    if not pogoj:
        napak += 1


usm = (SRC / "UserScriptManager.kt").read_text(encoding="utf-8")
ma = (SRC / "MainActivity.kt").read_text(encoding="utf-8")
vp = (SRC / "VirtualPointerView.kt").read_text(encoding="utf-8")

vbrizgi = [m for m in re.finditer(r"evaluateJavascript\(tvSpatialJs\(webView\)", usm)]
preveri("tvSpatialJs se vbrizga na dveh mestih (vbrizg ob nalaganju in vbrizgajFokusZdaj)", len(vbrizgi) == 2)
pri_nalaganju = usm[max(0, vbrizgi[-1].start() - 500):vbrizgi[-1].end()] if vbrizgi else ""
preveri("vbrizg ob nalaganju je pogojen s skriptZaFokus", "skriptZaFokus?.invoke(target)" in pri_nalaganju)
preveri("brez odlocitve velja staro vedenje (?: true)", "skriptZaFokus?.invoke(target) ?: true" in usm)
preveri("MainActivity odlocitev jemlje iz KazalecPravila.skriptZaFokus", "KazalecPravila.skriptZaFokus(" in ma)
preveri("MainActivity nastavi UserScriptManager.skriptZaFokus", "UserScriptManager.skriptZaFokus = { naslov -> skriptZaFokusZa(naslov) }" in ma)
preveri("ob onDestroy se odlocitev pocisti", "UserScriptManager.skriptZaFokus = null" in ma)
preveri("preklop na puscice vbrizga skript v nalozeno stran", "UserScriptManager.vbrizgajFokusZdaj" in ma and "naSpremembo" in vp)
sys.exit(1 if napak else 0)
