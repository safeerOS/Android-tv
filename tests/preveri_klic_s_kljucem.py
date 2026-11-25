#!/usr/bin/env python3
"""Tedenski pregled 25. 11. 2026 (N3): klic z zetonom Huba (x-safeer-token) ne sme slediti preusmeritvam.

HttpURLConnection ob preusmeritvi ponovi glavo z zetonom na nov naslov. HubTls.zavaruj preusmeritve izklopi in
pripne odtis, ki mu naprava zaupa. HubTls je samo za Android (Context), zato preverjamo vir (vzor: telefon,
tests/KlicSKljucemTest.kt):
 - HubTls.zavaruj izklopi preusmeritve;
 - v vsaki datoteki z zetonom in openConnection ima vsak openConnection svoj HubTls.zavaruj;
 - v QR prijavi je zavaruj med openConnection in glavo z zetonom, brez lastne tovarne vticnic in brez
   vklopa preusmeritev.

Uporaba: preveri_klic_s_kljucem.py [src/main/kotlin]
"""
import pathlib
import re
import sys

koren = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else \
    pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin"
paket = koren / "si/safeer/tv"
napak = 0


def preveri(opis, pogoj):
    global napak
    if pogoj:
        print("OK    ", opis)
    else:
        napak += 1
        print("NAPAKA", opis)


def telo(vir, ime):
    """Telo funkcije od `fun ime(` do zaklepaja na istem zamiku (vir ima zamike po 4)."""
    i = vir.find(f"fun {ime}(")
    if i < 0:
        raise SystemExit(f"NAPAKA funkcije {ime} ni")
    zacetek = vir.rfind("\n", 0, i) + 1
    zamik = re.match(r" *", vir[zacetek:i]).group(0)
    konec = vir.find(f"\n{zamik}}}", i)
    if konec <= i:
        raise SystemExit(f"NAPAKA konca funkcije {ime} ni")
    return vir[i:konec]


hub_tls = (paket / "cast/HubTls.kt").read_text(encoding="utf-8")
preveri("HubTls.zavaruj izklopi preusmeritve", "instanceFollowRedirects = false" in telo(hub_tls, "zavaruj"))

z_zetonom = sorted(
    f for f in koren.rglob("*.kt")
    if "x-safeer-token" in f.read_text(encoding="utf-8").lower() and "openConnection(" in f.read_text(encoding="utf-8"))
imena = [f.name for f in z_zetonom]
# Preverba brez predmeta ne sme uspeti (napacna pot): na TV so tri take datoteke (25. 11. 2026).
preveri(f"najdene datoteke z zetonom in openConnection ({len(z_zetonom)})",
        len(z_zetonom) >= 3 and "QrPrijavaActivity.kt" in imena)
for f in z_zetonom:
    v = f.read_text(encoding="utf-8")
    povezav = len(re.findall(r"openConnection\(", v))
    zavarovanih = len(re.findall(r"HubTls\.zavaruj\(", v))
    preveri(f"{f.name}: vsak openConnection ({povezav}) ima HubTls.zavaruj ({zavarovanih})", zavarovanih >= povezav)

klic = telo((paket / "link/QrPrijavaActivity.kt").read_text(encoding="utf-8"), "klic")
odpri = klic.find("openConnection(")
zavaruj = klic.find("HubTls.zavaruj(povezava")
zeton = klic.lower().find("x-safeer-token")
preveri("QR klic: zavaruj po openConnection in pred glavo z zetonom", 0 <= odpri < zavaruj < zeton)
preveri("QR klic: brez lastne tovarne vticnic mimo zavaruj", "sslSocketFactory =" not in klic)
preveri("QR klic: ne vklopi preusmeritev", "instanceFollowRedirects = true" not in klic)

if napak:
    print(f"preveri_klic_s_kljucem: {napak} napak")
    sys.exit(1)
print("preveri_klic_s_kljucem: OK")
