#!/usr/bin/env python3
"""Nastavitve Medijskega centra (lastnik 19. 11. 2026, telefon): okno se mora odzvati na klik.

Vzrok (ponovljen na testnem telefonu): klik na »Moji viri« je spremenil stran ZA oknom, okno je ostalo odprto in videz je
bil, da se nic ne zgodi; stanje stikal (Vklopljeno/Izklopljeno) je enovrstica odrezala. Pravilo je v
MedijskiCenterNastavitvePravila.zapreOknoPoKliku (tests/MedijskiCenterNastavitvePravilaTest.kt); tu je preverjeno, da ga
zaslon uporabi: okno se naredi z create(), pred dejanjem postavke se po pravilu zapre, besedilo postavke ni enovrstica.
Uporaba: preveri_mc_nastavitve_okno.py [pot do GlasbaActivity.kt]
"""
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parents[1]
dat = Path(sys.argv[1]) if len(sys.argv) > 1 else KOREN / "src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt"
vir = dat.read_text(encoding="utf-8")
napak = 0


def preveri(opis: str, pogoj: bool) -> None:
    global napak
    print(("  ok  " if pogoj else "  NAPAKA  ") + opis)
    if not pogoj:
        napak += 1


z = vir.find("private fun nastavitveMedijskegaCentra()")
k = vir.find("/** Mesto, velikost na USB in", z)
preveri("funkcija nastavitveMedijskegaCentra je v datoteki", z >= 0 and k > z)
f = vir[z:k] if z >= 0 and k > z else ""
preveri("okno se naredi z create() (da ga postavka lahko zapre)", "val okno = a.create()" in f)
preveri("okno se pokaze z okno.show(), ne z a.show()", "okno.show()" in f and "a.show()" not in f)
preveri("po kliku se okno zapre po pravilu zapreOknoPoKliku", "zapreOknoPoKliku(p)) okno.dismiss()" in f)
i_zapri = f.find("okno.dismiss()")
i_dejanje = f.find("Postavka.MOJI_VIRI -> { izberiNaMestu")
preveri("zapiranje je PRED dejanjem postavke", 0 <= i_zapri < i_dejanje)
preveri("besedilo postavke ni enovrstica (stanje stikala se vidi)", "maxLines = 3" in f)
sys.exit(1 if napak else 0)
