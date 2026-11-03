#!/usr/bin/env python3
"""Naloga 113: odlocitve, ki jih YoutubeVrstaPravilaTest dokaze na cistih funkcijah, morajo klicati tudi pravi razredi
(GlasbaStoritev, SpletniIgralec - Android, brez JVM preizkusa). Brez popravka pade: enota spletnega vira gre naravnost na
spletno stran in stran nima roka."""
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
    t = (OS / ime).read_text(encoding="utf-8")
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)//.*$", "", t)


g = koda("GlasbaStoritev.kt")
s = koda("SpletniIgralec.kt")
preveri("vrsta: enota spletnega vira gre skozi zacniEnoto", re.search(r"SpletniVir\.jeEnota\(sk\)\s*->\s*\{\s*zacniEnoto\(", g) is not None)
preveri("zacniEnoto vprasa YoutubeVrstaPravila.pot", "YoutubeVrstaPravila.pot(" in g)
preveri("zacniEnoto razresi tok YoutubeZvok in ga da Media3 (nalozi)", re.search(r"YoutubeZvok\.razresi\(.*?nalozi\(listOf\(sk\.copy\(zvok = tok\.url", g, re.S) is not None)
preveri("brez toka: rezerva je spletna stran", re.search(r"if \(tok == null\) \{ zacniSplet\(", g) is not None)
preveri("GlasbaStoritev nastavi obZastoju", "s.obZastoju = {" in g)
preveri("zastoj: naprej po YoutubeVrstaPravila.naprejPoZastoju", "YoutubeVrstaPravila.naprejPoZastoju(" in g)
preveri("SpletniIgralec: tik preveri zastoj", re.search(r"private val tik = object : Runnable \{.*?preveriZastoj\(\)", s, re.S) is not None)
preveri("SpletniIgralec: zastoj po YoutubeVrstaPravila.zastoj", "YoutubeVrstaPravila.zastoj(" in s)
preveri("SpletniIgralec: rok tece od nalaganja polne strani", s.count("nalozenoOd = SystemClock.uptimeMillis()") >= 2)
# Zunanji pregled 3. 11. 2026: pravila morajo klicati pravi razredi.
mp = koda("MiniPredvajalnik.kt")
pk = koda("PokrajinaActivity.kt")
pool = koda("LahkiWebViewPool.kt")
preveri("dock: besedila niso cilj fokusa (drsnik dosegljiv)", re.search(r"val besedila = LinearLayout\(a\)\.apply \{[^}]*isFocusable = false", mp) is not None)
preveri("dock: klik na naslovu (svoj cilj)", re.search(r"val naslovni = LinearLayout\(a\)\.apply \{.*?isFocusable = true.*?klikNaslova", mp, re.S) is not None)
preveri("dock: osvezi znova zazene samodejno skrivanje", "MiniPredvajalnikPravila.znovaSkrivanje(" in mp)
preveri("dom: tipka Predvajaj po tipkaNaDomu", "MiniPredvajalnikPravila.tipkaNaDomu(" in pk and "MiniPredvajalnik.odpriCelZaslon(this)" in pk)
preveri("zacniEnoto poda video v pot", "YoutubeVrstaPravila.pot(YoutubeZvok.jeYoutube(sk), sk.video," in g)
preveri("polni pogon: pool pred njim unici prost pogled", re.search(r"fun zacniPolniSplet\(.*?LahkiWebViewPool\.predPolnimPogonom\(\).*?SpletniIgralec\(this, sk\)", g, re.S) is not None)
preveri("nativno predvajanje: pogled ostane samo za LahkaStran", "obNativnemPredvajanju(spletni is LahkaStran)" in g)
preveri("pool: obNativnem po LahkiPoolPravila (tudi najet pogled)", "LahkiPoolPravila.obdrziObNativnem(" in pool and "izrisovalnikPoPredvajanju = true; return" not in pool)
preveri("pool: med polnim pogonom ne najema", "LahkiPoolPravila.smeNajeti(" in pool)
preveri("SpletniIgralec: po unicenju koncaj izrisovalnik", "LahkiWebViewPool.poUnicenju(" in s)
preveri("SpletniIgralec: vgradni zastoj nalozi polno stran", "YoutubeVrstaPravila.poZastojuPolnaStran(vgradno)" in s)
preveri("SpletniIgralec: uradni ukaz IFrame API", "event:'command'" in s and "enablejsapi=1" in (OS / "SpletniIgralec.kt").read_text(encoding="utf-8"))
preveri("SpletniIgralec: straza ozadja", "YoutubeVrstaPravila.ozadjeStoji(" in s)
print("VSE OK" if napak == 0 else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
