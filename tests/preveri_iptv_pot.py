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

print("Grok 60, Ů3: opozorilo za menjavo gesla zajame tudi izbrisane vire")
pr = telo(viri, "private fun preseli(a: Context)")
i_zapomni = pr.find("izbrisaniRacuniPredPrehodom(")
i_zavrzi = pr.find("brezGrobovZRacunom(")
preveri("prehod: grobovi se najprej zapomnijo (odtis, gostitelj), sele nato zavrzejo", 0 <= i_zapomni < i_zavrzi)
preveri("prehod: zapis izbrisanih je trajen (commit) pred brisanjem grobov", 0 <= pr.find(".putStringSet(VIRI_IZBRISANI_PRED") < i_zavrzi and ".commit()" in pr[i_zapomni:i_zavrzi])
preveri("opozoriloDeljenja zajame izbrisane vire", "opozoriloIzbrisanih(" in telo(viri, "fun opozoriloDeljenja("))
preveri("opozoriloVideno oznaci tudi izbrisane vire", "odtisiIzbrisanih(" in telo(viri, "fun opozoriloVideno("))

print("Grok 60, Ů1: odprtje seznama in TV v zivo ne kliceta nove prijave ob vsakem odprtju")
pr = telo(iptv, "fun pripravi(")
preveri("IptvSeznami.pripravi: ena odlocitev po IptvPravila.odlociPripravo", "IptvPravila.odlociPripravo(" in pr)
preveri("IptvSeznami.pripravi: brez lastnega predpomnilnika in premora mimo odlocitve", "zastarel(" not in pr and "smePrenesti(" not in pr)
pr = telo(iptv, "fun prenesi(")
preveri("IptvSeznami.prenesi zapise poskus prijave (od tu steje meja 1 na minuto)", "zapisiPoskus(" in pr)
os_ = telo(viri, "fun osveziSeznam(")
preveri("osveziSeznam (odprtje seznama): za racun ista odlocitev (24-urni predpomnilnik, premor I5), brez izrecne osvezitve",
        "IptvPravila.smeNaOmrezjeSeznam(" in os_ and "izrecno = false" in os_)
preveri("osveziSeznam: zapise poskus prijave in izid (premor I5, cas branja)", "IptvSeznami.zapisiPoskus(" in os_ and "IptvSeznami.poIzidu(" in os_)
# rocno = true samo pri izrecnem kliku »Poskusi znova«: nobeno odprtje (seznama, TV v zivo, pregled) ne sme klicati pripravi z rocno.
klici = []
for f in sorted(OS.glob("*.kt")):
    t = koda(f.name)
    for m in re.finditer(r"IptvSeznami\.pripravi\(([^)]*)\)", t):
        if re.search(r"rocno\s*=\s*true|,\s*true\s*$", m.group(1)):
            klici.append((f.name, t[max(0, m.start() - 900): m.start()]))
preveri("IptvSeznami.pripravi z rocno = true: natanko en klic", len(klici) == 1)
preveri("... in to je izrecen klik »Poskusi znova« (GlasbaActivity)", len(klici) == 1 and klici[0][0] == "GlasbaActivity.kt" and "os_iptv_poskusi_znova" in klici[0][1])

print("VSE OK" if not napak else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
