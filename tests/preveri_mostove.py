#!/usr/bin/env python3
"""Mostovi WebView (addJavascriptInterface) vidijo VSE okvirje strani, tudi tuje (AGENTS.md, zunanji pregled 3. 11. 2026).

Preveri, da imajo razredi mostu samo znane metode @JavascriptInterface in da vsaka metoda, ki spreminja stanje
ali bere podatke uporabnika, pred dejanjem preveri domaco stran (naDomaci*) ali zeton. Nova metoda mostu mora biti
dodana sem z razlogom, sicer preizkus pade.
"""
import os, re, sys

KOREN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POT = os.path.join(KOREN, "src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt")

# ime -> zahtevana zascita v telesu (None = varno brez zascite, razlog v komentarju)
DOVOLJENE = {
    "sporocilo": "jeDovoljenIzvor",          # SafeerAndroid: samo splet.html, preverba na glavni niti
    "sponsorSegments": None,                 # samo prebere javne odseke SponsorBlock za videoId
    "onScrollChanged": None,                 # samo smer drsenja za skrivanje vrstice
    "getUiLanguage": None,                   # jezik vmesnika, ni zaseben
    "navigate": "naDomaciNaGlavni",
    "isci": "naDomaciNaGlavni",
    "getHomeTiles": "naDomaci(",
    "openHomeTilesEditor": "naDomaciNaGlavni",
    "addHomeTile": "naDomaciNaGlavni",
    "triggerNativeTap": "zetonVelja",
}


def metode(besedilo):
    """(ime, telo) za vsako funkcijo z @JavascriptInterface; telo do naslednje anotacije ali funkcije na isti ravni."""
    izid = []
    for m in re.finditer(r"@(?:android\.webkit\.)?JavascriptInterface\s+fun\s+(\w+)\s*\(", besedilo):
        zacetek = m.end()
        konec = besedilo.find("@android.webkit.JavascriptInterface", zacetek)
        konec2 = besedilo.find("@JavascriptInterface", zacetek)
        konci = [k for k in (konec, konec2) if k != -1]
        telo = besedilo[zacetek:min(konci) if konci else zacetek + 4000]
        izid.append((m.group(1), telo[:4000]))
    return izid


def main():
    with open(POT, encoding="utf-8") as f:
        besedilo = f.read()
    najdene = metode(besedilo)
    napake = []
    for ime, telo in najdene:
        if ime not in DOVOLJENE:
            napake.append(f"neznana metoda mostu {ime}() - dodaj zascito in jo vpisi v DOVOLJENE")
            continue
        zascita = DOVOLJENE[ime]
        if zascita and zascita not in telo:
            napake.append(f"{ime}() nima zascite {zascita}")
    imena = {i for i, _ in najdene}
    for ime in ("onMedijskaZahteva", "forceAudioOn"):
        if ime in imena:
            napake.append(f"{ime}() je spet v mostu (odstranjen 3. 11. 2026)")
    if not najdene:
        napake.append("ni najdenih metod mostu - je pot napacna?")
    # Zunanji pregled 3. 11. 2026: namere iz Sporocil enako ociscene kot v brskalniku; varnostna vrata na domu TV.
    obvezno = {
        "src/main/kotlin/si/safeer/tv/os/SpletniKlepetActivity.kt": ["clipData = null", "replaceExtras(null", "dovoljenaNamera(", "hasGesture()", "component = null", "selector = null"],
        "src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt": ["clipData = null", "replaceExtras(null", "dovoljenaNamera(", "hasGesture()"],
        "src/main/kotlin/si/safeer/tv/os/DomovActivity.kt": ["Zaganjalnik.preveriObZagonu(this)", "Zaganjalnik.namestiZascito(this)"],
        "src/main/kotlin/si/safeer/tv/os/PokrajinaActivity.kt": ["Zaganjalnik.namestiZascito(this)"],
    }
    for rel, nizi in obvezno.items():
        with open(os.path.join(KOREN, rel), encoding="utf-8") as f:
            vsebina = f.read()
        for niz in nizi:
            if niz not in vsebina:
                napake.append(f"{rel}: manjka {niz}")
    if napake:
        print("NAPAKA preveri_mostove:\n  " + "\n  ".join(napake))
        return 1
    print(f"OK preveri_mostove ({len(najdene)} metod)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
