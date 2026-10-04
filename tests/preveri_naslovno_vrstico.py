#!/usr/bin/env python3
"""Naslovna vrstica vgrajenega brskalnika na dotik in robovi strani: pravila, ki jih prevajalnik ne ujame.

Postavitev brskalnika je nastala za daljinec (focusableInTouchMode). Na dotik to pomeni, da prvi dotik gumbu samo da
fokus in klika ne izvede; ce je gumb v naslovni vrstici ali med predlogi, polje naslova s tem izgubi fokus in se
urejanje zapre (krizec ni pobrisal naslova, cip portala ni odprl strani - Safeer OS Mobile 0.5.45, 4. 10. 2026).
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent
napake = []


def preveri(opis, pogoj):
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    if not pogoj:
        napake.append(opis)


def beri(*pot):
    return KOREN.joinpath(*pot).read_text(encoding="utf-8")


glavna = beri("src", "main", "kotlin", "si", "safeer", "tv", "MainActivity.kt")
chrome = beri("src", "main", "kotlin", "si", "safeer", "tv", "tv", "TvChrome.kt")
robovi = beri("src", "main", "kotlin", "si", "safeer", "tv", "os", "Robovi.kt")
postavitev = beri("res", "layout", "activity_main.xml")
splet = beri("assets", "splet", "splet.js")

print("Naslovna vrstica na dotik")
polje = re.search(r"<si\.safeer\.tv\.OmniboxPolje(.*?)/>", postavitev, re.S)
preveri("dotik vrstice izbere ves naslov (selectAllOnFocus)", bool(polje) and 'android:selectAllOnFocus="true"' in polje.group(1))
dotik = re.search(r"if \(ChromiumEngineView\.naDotik\(this\)\) \{\s*listOf<View>\((.*?)\)\.forEach \{ it\.isFocusableInTouchMode = false \}", glavna, re.S)
gumbi = set(re.findall(r"\w+", dotik.group(1))) if dotik else set()
for gumb in ("btnClearUrl", "btnSearchTrigger", "btnFavorite", "btnBack", "btnHome", "btnReload", "btnTabCount", "btnMenu"):
    preveri("na dotik %s ne jemlje fokusa" % gumb, gumb in gumbi)
# Vsak gumb postavitve, ki je narejen za daljinec, mora biti v tem seznamu (polje naslova samo seveda ostane).
for ime in re.findall(r'android:id="@\+id/(\w+)"(?:(?!android:id=).)*?android:focusableInTouchMode="true"', postavitev, re.S):
    if ime != "editUrl":
        preveri("gumb %s iz postavitve je v seznamu za dotik" % ime, ime in gumbi)
preveri("cipi portalov in predlogi na dotik ne jemljejo fokusa",
        "isFocusableInTouchMode = true" not in chrome and "isFocusableInTouchMode = !ChromiumEngineView.naDotik(context)" in glavna)
preveri("dotik strani zapre tudi tipkovnico", "if (ChromiumEngineView.naDotik(this)) hideKeyboard()" in glavna)
preveri("ob fokusu ni predloga za stran, ki je odprta",
        "suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }\n                suggestionsListContainer.removeAllViews()" in glavna)
preveri("Prilepi in pojdi: vsebino odlozisca preberemo sele ob dotiku cipa",
        chrome.index("primaryClipDescription") < chrome.index("setOnClickListener {\n                val besedilo = try {\n                    odlozisce.primaryClip?"))

print("Robovi strani")
poslusalec = re.search(r"setOnApplyWindowInsetsListener \{(.*?)\n        \}", robovi, re.S)
preveri("porabljeni robovi ne gredo naprej do WebView (safe-area-inset)",
        bool(poslusalec) and poslusalec.group(1).rstrip().endswith("WindowInsets.CONSUMED"))

print("Polje zacetne strani")
preveri("na Androidu odloci gostitelj (most: query)", 'most({ action: "query", text: vnos })' in splet)
node = shutil.which("node")
if node:
    # vNaslov je rezerva za gostitelje brez mostu: lokalni naslovi po http, vse drugo kot prej.
    funkciji = re.search(r"(  function jeIPv4\(g\) \{.*?\n  \}\n\n  function vNaslov\(vnos\) \{.*?\n  \})", splet, re.S)
    preveri("vNaslov je mogoce izlusciti", bool(funkciji))
    if funkciji:
        primeri = {"192.168.0.1": "http://192.168.0.1", "192.168.0.135:8799/sa.html": "http://192.168.0.135:8799/sa.html",
                   "localhost:8080/api": "http://localhost:8080/api", "tiskalnik.local": "http://tiskalnik.local",
                   "nas.lan/datoteke": "http://nas.lan/datoteke", "safeer.si": "https://safeer.si",
                   "https://primer.si/a": "https://primer.si/a", "256.1.1.1": "https://256.1.1.1",
                   "defrag windows": "ISKANJE:defrag%20windows", "localhost je doma": "ISKANJE:localhost%20je%20doma"}
        skripta = ("var ISKALNIKI = { x: 'ISKANJE:' }, S = { iskalnik: 'x' };\n" + funkciji.group(1) +
                   "\nvar p = %s; var slabo = Object.keys(p).filter(function (k) { return vNaslov(k) !== p[k]; })"
                   ".map(function (k) { return k + ' -> ' + vNaslov(k); }); if (slabo.length) { console.log(slabo.join('; ')); process.exit(1); }"
                   % __import__("json").dumps(primeri))
        izid = subprocess.run([node, "-e", skripta], capture_output=True, text=True)
        preveri("vNaslov: lokalni naslovi po http" + ((" (" + izid.stdout.strip() + izid.stderr.strip()[:200] + ")") if izid.returncode else ""),
                izid.returncode == 0)
else:
    print("  (node ni namescen: preizkus vNaslov preskocen)")

print("VSE OK" if not napake else "NAPAK: %d" % len(napake))
sys.exit(1 if napake else 0)
