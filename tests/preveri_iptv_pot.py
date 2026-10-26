#!/usr/bin/env python3
"""Prava pot vira z racunom IPTV (Grok 60): odlocitve, ki jih preizkusi pravil (IptvRacunTest, IptvVarovaloTest) dokazejo na
cistih funkcijah, morajo klicati tudi pravi razredi aplikacije (Android: brez JVM preizkusa) - sicer preizkus drzi, aplikacija pa ne.

Preverjene so vse oblike zapisa klica (MedijskiViri.f(...), f(...)), ker jih iskanje po eni obliki zgresi.
"""
import pathlib
import re
import sys

OS = pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os"
napak = 0


def preveri(opis, pogoj):
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    napak += 0 if pogoj else 1


def koda(ime):
    # Komentarji (// in blok) ne steje: preverjamo klice, ne besedila.
    t = (OS / ime).read_text(encoding="utf-8")
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)//.*$", "", t)


def telo(t, podpis):
    """Besedilo funkcije od [podpis] do naslednje funkcije na isti ravni zamika (dovolj za preverjanje klicev)."""
    i = t.find(podpis)
    if i < 0:
        return ""
    m = re.search(r"\n    (?:@\w+ )*(?:private |internal )?(?:fun|const val|val|var|class|object) ", t[i + len(podpis):])
    return t[i: i + len(podpis) + (m.start() if m else len(t))]


iptv = koda("IptvSeznami.kt")
uskl = koda("UskladitevKljucev.kt")
viri = koda("MedijskiViri.kt")

print("Grok 60, Ů2: »dodan tu« samo z dokazom")
preveri("IptvSeznami: samodejni prenos ne pozna stare izjeme TV (dodanTu)", re.search(r"\bdodanTu\(", iptv) is None)
preveri("IptvSeznami: samodejni prenos kliče dodanTuZDokazom", re.search(r"\bdodanTuZDokazom\(", iptv) is not None)
preveri("UskladitevKljucev: secrets.sync ne pozna stare izjeme TV (dodanTu)", re.search(r"\bdodanTu\(", uskl) is None)
preveri("UskladitevKljucev: oddajaIptv dobi dokaz (dodanTuZDokazom)", re.search(r"oddajaIptv\([^)]*dodanTuZDokazom\(", uskl) is not None)
preveri("MedijskiViri.shrani zapise dokaz po IptvPravila.dokazDodanTu", "IptvPravila.dokazDodanTu(" in telo(viri, "private fun shrani("))
preveri("MedijskiViri.shrani: novi viri po VHODNEM naslovu (prehod ne da dokaza podedovanemu)", "if (v.naslov !in prejsnji) noviTu +=" in viri)
d = telo(viri, "fun dodanTuZDokazom(")
preveri("dodanTuZDokazom bere samo zapis dokaza (ne VIRI_TUKAJ, ne TV)", "VIRI_DOKAZ" in d and "VIRI_TUKAJ" not in d and "jeTelevizor" not in d and "viriTukaj" not in d)
preveri("dodajanje obstojecega vira z racunom po vpisu naslova je potrditev", "potrdiDodanTu(ctx, obstojeci.naslov)" in viri)

print("VSE OK" if not napak else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
