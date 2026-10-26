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
preveri("IptvSeznami: prenos ob uporabi kliče uporabaDovoljena (dodan tu ALI iz ozjega kroga ALI dovoljen)", re.search(r"\buporabaDovoljena\(", iptv) is not None and len(re.findall(r"\bdodanTuZDokazom\(", iptv)) == 1 and "dodanTuDokaz = MedijskiViri.dodanTuZDokazom(" in iptv)
preveri("UskladitevKljucev: secrets.sync ne pozna stare izjeme TV (dodanTu)", re.search(r"\bdodanTu\(", uskl) is None)
preveri("UskladitevKljucev: oddajaIptv dobi dokaz (dodanTuZDokazom)", re.search(r"oddajaIptv\([^)]*dodanTuZDokazom\(", uskl) is not None)
preveri("MedijskiViri.shrani zapise dokaz po IptvPravila.dokazDodanTu", "IptvPravila.dokazDodanTu(" in telo(viri, "private fun shrani("))
preveri("MedijskiViri.shrani: novi viri po VHODNEM naslovu (prehod ne da dokaza podedovanemu)", "if (v.naslov !in prejsnji) noviTu +=" in viri)
d = telo(viri, "fun dodanTuZDokazom(")
preveri("dodanTuZDokazom bere samo zapis dokaza (ne VIRI_TUKAJ, ne TV)", "VIRI_DOKAZ" in d and "VIRI_TUKAJ" not in d and "jeTelevizor" not in d and "viriTukaj" not in d)
preveri("dodajanje obstojecega vira z racunom po vpisu naslova je potrditev", "potrdiDodanTu(ctx, obstojeci.naslov)" in viri)

print("Odlocitev lastnika 26. 10. 12:10 in Grok 64 Ź1: vir iz ozjega kroga je uporaben takoj, zunaj kroga fail-closed, dovoljenje ni dokaz")
sh = telo(viri, "private fun shrani(")
preveri("shrani zapise uporabo po IptvPravila.uporabaPoPrejemu z odKroga", "IptvPravila.uporabaPoPrejemu(" in sh and "odKroga = odKroga" in sh)
pv = telo(viri, "fun prevzemiVir(")
preveri("prevzemiVir (lists.get): odKroga = odOzjegaKroga", "odKroga = odOzjegaKroga" in pv)
preveri("prevzemiVir: kopija, ki jo ze imamo, od naprave iz kroga postane uporabna", "dovoliUporabo(ctx, v.naslov)" in pv and "odOzjegaKroga &&" in pv)
dr = telo(viri, "fun dodajRacun(")
preveri("dodajRacun (secrets.sync, samo ozji krog): odKroga = prejeto", "odKroga = prejeto" in dr)
dv = telo(viri, "fun dovoliUporabo(")
preveri("dovoliUporabo: IptvPravila.potrditevUporabe", "IptvPravila.potrditevUporabe(" in dv)
preveri("Ź1: dovoliUporabo ne piše dokaza »dodan tu« in ne sprozi secrets.sync", "VIRI_DOKAZ" not in dv and "UskladitevKljucev" not in dv and "potrdiDodanTu" not in dv)
du = telo(viri, "fun uporabaDovoljena(")
preveri("uporabaDovoljena: dodan tu z dokazom ALI zapis uporabe", "IptvPravila.smeUporabiti(" in du and "dodanTuZDokazom(" in du and "VIRI_UPORABA" in du)
gl = koda("GlasbaActivity.kt")
preveri("Ź1: meni »Dovoli samodejni prenos« kliče dovoliUporabo, ne potrdiDodanTu", "dovoliUporabo(app, dodan.naslov)" in gl and re.search(r"\bpotrdiDodanTu\(", gl) is None)
preveri("kartica in meni merita uporabaDovoljena (ne dodanTuZDokazom)", gl.count("uporabaDovoljena(") >= 3 and re.search(r"\bdodanTuZDokazom\(", gl) is None)
preveri("secrets.sync (oddaja geslo naprej) ostane samo pri dokazu dodan tu", re.search(r"oddajaIptv\([^)]*dodanTuZDokazom\(", uskl) is not None and "uporabaDovoljena" not in uskl)

print("Grok 68: prva povezava ni samodejna (Ą2); dokaz ozjega kroga je posiljatelj v krogu TE naprave (Ą3)")
pr = telo(iptv, "fun pripravi(")
preveri("Ą2: pripravi poda dokaz »dodan tu« in uporabnikovo namero odlocitvi", "dodanTuDokaz = MedijskiViri.dodanTuZDokazom(" in pr and "uporabnik = uporabnik" in pr)
preveri("Ą2: odlociPripravo uporablja uporabnikSprozilPovezavo", "uporabnikSprozilPovezavo(" in telo(koda("IptvPravila.kt"), "fun odlociPripravo("))
osz = telo(viri, "fun osveziSeznam(")
racun_veja = osz.split("val p = try { URL(pravi)")[0]
preveri("Ą2: odprtje seznama z racunom gre prek IptvSeznami.pripravi(uporabnik = true) (en predpomnilnik, en premor, ena prijava)", "IptvSeznami.pripravi(ctx, naslov, uporabnik = true)" in racun_veja)
preveri("Ą2: odprtje seznama z racunom nima lastnega branja omrezja", "openConnection" not in racun_veja)
preveri("Ą2: isti prenos napolni skladbe pogleda (prenesi -> shraniSkladbeSeznama)", "shraniSkladbeSeznama(" in telo(iptv, "private fun prenesi("))
preveri("Ą2: mreza TV v zivo poda namero uporabnika (naloziKanale)", "IptvSeznami.pripravi(app, n, uporabnik = m.uporabnik)" in gl)
preveri("Ą2: samo izrecna izbira zavihka (stranska vrstica, zavihek v namenu) je uporabnikova namera", gl.count("izrecno = true") == 2 and "izberi(razdelekZavihka(z), izrecno = true)" in gl and "izberi(razdelekZavihka(it), izrecno = true)" in gl)
preveri("Ą2: mreza zapomni namero (m.uporabnik = izrecno)", "m.uporabnik = izrecno" in gl)
preveri("Ą2: samodejni ponovni poskusi mreze (3 mesta) namere ne prenesejo", len(re.findall(r"odpriKanale\(m\.zvrst, m\.jezik\)", gl)) == 3)
sink = koda("SeznamiSink.kt")
preveri("Ą3: lists.get - dokaz ozjega kroga zdruzi OzjiKrog.vKrogu(naprava) in odgovor", re.search(r"odOzjegaKroga\(try \{ OzjiKrog\.vKrogu\(app, naprava\)", sink) is not None)
preveri("Ą3: secrets.sync - sprejem zahteva vKrogu (OzjiKrog.vKrogu(od))", "OzjiKrog.vKrogu(a, od)" in uskl and "P.sprejem(vklopljeno(a), vKrogu," in uskl)

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
# rocno = true samo pri izrecnem kliku »Poskusi znova«: nobeno odprtje (seznama, TV v zivo, pregled) ne sme klicati pripravi z rocno.
klici = []
for f in sorted(OS.glob("*.kt")):
    t = koda(f.name)
    for m in re.finditer(r"IptvSeznami\.pripravi\(([^)]*)\)", t):
        if re.search(r"rocno\s*=\s*true|,\s*true\s*$", m.group(1)):
            klici.append((f.name, t[max(0, m.start() - 900): m.start()]))
preveri("IptvSeznami.pripravi z rocno = true: natanko en klic", len(klici) == 1)
preveri("... in to je izrecen klik »Poskusi znova« (GlasbaActivity)", len(klici) == 1 and klici[0][0] == "GlasbaActivity.kt" and "os_iptv_poskusi_znova" in klici[0][1])

print("Grok 60, Ů4: pogled seznama bere z mejo velikosti in roka")
os_ = telo(viri, "fun osveziSeznam(")
preveri("osveziSeznam bere prek IptvPravila.omejenoBesedilo", "IptvPravila.omejenoBesedilo(" in os_)
preveri("osveziSeznam: brez readText() (branje brez meje)", "readText(" not in os_ and "readLines(" not in os_)
preveri("osveziSeznam zapre bralnik (use) in povezavo (disconnect)", ".use {" in os_ and "p.disconnect()" in os_)

print("Skupni primeri v4: vdelava strani samo brez znakov poverilnice")
dod = telo(viri, "fun dodaj(")
razv = telo(viri, "private fun razvrsti(")
preveri("dodaj: stran HTML z znaki poverilnice se zavrne (smeVdelatiStran, prejet = false)", "smeVdelatiStran(cisto, prejet = false)" in dod)
preveri("razvrsti: stran HTML po preusmeritvi preverjena (p.url)", "smeVdelatiStran(p.url.toString()" in razv)

print("VSE OK" if not napak else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
