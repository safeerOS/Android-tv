#!/usr/bin/env python3
"""Katalog spletnih storitev za Sporocila na Androidu (lastnik 27. 10. 2026: »sporocila kot Ferdium tudi na Androidu,
tudi na TV - tipkovnica in miska, uporabniki brez racunalnika«).

Vir je Safeer-linux (en katalog za vse naprave): izmerjeni vnosi core/spletni_klepeti.py (KATALOG, PRIPISI) in
razsirjeni katalog core/ferdium_katalog/katalog-sporocila.json (podatki iz ferdium-recipes, MIT; kode receptov ni).
Ikone SVG (ze ociscene na Linuxu, core/varna_svg.py) se tu izrisejo v PNG 96 px z librsvg (GdkPixbuf), ker Android
SVG ne zna brez dodatne knjiznice. Izid: assets/sporocila/spletni-klepeti.json in LICENSE-ferdium-recipes.

Na Androidu ostanejo samo storitve s spletnim odjemalcem (namizni programi Signal, Viber ... imajo uradno aplikacijo
za Android - zavihek Aplikacije).

Uporaba: python3 tools/izdelaj_spletne_klepete.py POT_DO_SAFEER_LINUX
"""
import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile

NAJVECJA_IKONA = 96


def _linux_katalog(linux: str):
    sys.path.insert(0, linux)
    from core import spletni_klepeti  # noqa: E402  (samo standardna knjiznica)
    return spletni_klepeti.KATALOG, spletni_klepeti.PRIPISI


def _png(svg: str) -> str:
    """SVG -> PNG (base64) z librsvg v locenem procesu (pokvarjena ikona ne podre izdelave: brez ikone)."""
    with tempfile.TemporaryDirectory() as d:
        vhod, izhod = os.path.join(d, "i.svg"), os.path.join(d, "i.png")
        with open(vhod, "w", encoding="utf-8") as f:
            f.write(svg)
        koda = ("import gi,sys; gi.require_version('GdkPixbuf','2.0'); from gi.repository import GdkPixbuf;"
                "p=GdkPixbuf.Pixbuf.new_from_file_at_size(sys.argv[1],%d,%d); p.savev(sys.argv[2],'png',[],[])"
                % (NAJVECJA_IKONA, NAJVECJA_IKONA))
        r = subprocess.run([sys.executable, "-c", koda, vhod, izhod], capture_output=True, timeout=30)
        if r.returncode != 0 or not os.path.isfile(izhod):
            return ""
        with open(izhod, "rb") as f:
            return base64.b64encode(f.read()).decode("ascii")


def izdelaj(linux: str):
    katalog, pripisi = _linux_katalog(linux)
    with open(os.path.join(linux, "core", "ferdium_katalog", "katalog-sporocila.json"), encoding="utf-8") as f:
        ferdium = json.load(f)
    ikone = {}
    for v in ferdium["katalog"]:
        if v.get("ikona_svg"):
            ikone[v["id"]] = v["ikona_svg"]
            if v.get("obstojeci"):
                ikone.setdefault(v["obstojeci"], v["ikona_svg"])
    storitve, videni = [], set()
    for v in katalog:                     # izmerjeni (15. 10. 2026) najprej
        if not v.get("splet"):
            continue
        videni.add(v["id"])
        storitve.append({"id": v["id"], "ime": v["ime"], "pripis": v.get("pripis", ""), "vrsta": v["vrsta"],
                         "splet": v["splet"], "prenos": v.get("prenos", ""), "izmerjeno": True,
                         "ikona": _png(ikone[v["id"]]) if v["id"] in ikone else ""})
    for v in ferdium["katalog"]:
        if not v.get("splet") or v["id"] in videni or (v.get("obstojeci") and v["obstojeci"] in videni):
            continue
        videni.add(v["id"])
        storitve.append({"id": v["id"], "ime": v["ime"], "pripis": "", "vrsta": v.get("vrsta", "klepet"),
                         "splet": v["splet"], "prenos": v.get("prenos", ""), "izmerjeno": False,
                         "ikona": _png(v["ikona_svg"]) if v.get("ikona_svg") else ""})
    vir = dict(ferdium["vir"])
    licenca = vir.pop("licenca_besedilo", "")
    return {"oblika": 1, "opis": "Spletne storitve za Sporocila (Safeer za Android): samo podatki, brez kode strani.",
            "vir": vir, "pripisi": pripisi, "storitve": storitve}, licenca


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    linux = os.path.abspath(sys.argv[1])
    podatki, licenca = izdelaj(linux)
    koren = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    mapa = os.path.join(koren, "assets", "sporocila")
    os.makedirs(mapa, exist_ok=True)
    pot = os.path.join(mapa, "spletni-klepeti.json")
    with open(pot, "w", encoding="utf-8") as f:
        json.dump(podatki, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    with open(os.path.join(mapa, "LICENSE-ferdium-recipes"), "w", encoding="utf-8") as f:
        f.write(licenca.rstrip() + "\n")
    brez = [s["id"] for s in podatki["storitve"] if not s["ikona"]]
    with open(pot, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    print("storitev: %d, brez ikone: %d %s, sha256 %s" % (len(podatki["storitve"]), len(brez), brez, sha))
    return 0


if __name__ == "__main__":
    sys.exit(main())
