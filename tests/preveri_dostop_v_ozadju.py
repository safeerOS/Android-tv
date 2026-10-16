#!/usr/bin/env python3
"""Zahteve v ozadju ne smejo na racunalniku odpreti obvestila »prosi za dostop« (lastnik, 15. 10. 2026).

Predpomnjenje seznama programov (domaci zaslon) in stanje kartic poslji s parametrom ProsnjaDostopa.V_OZADJU;
ko uporabnik sam odpre programe racunalnika, gre zahteva BREZ njega (racunalnik vprasa, telefon caka na potrditev).
"""
import pathlib
import re
import sys

OS = pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os"
napak = 0


def preveri(opis, pogoj):
    global napak
    print(("  OK   " if pogoj else "FAIL   ") + opis)
    napak += 0 if pogoj else 1


pravila = (OS / "ProsnjaDostopa.kt").read_text(encoding="utf-8")
preveri("ime parametra je dogovor z racunalnikom (core/safeer_link.py: params.background)",
        'const val V_OZADJU = "background"' in pravila)
app = (OS / "AplikacijeHostaActivity.kt").read_text(encoding="utf-8")
preveri("prenesi doda parameter samo v ozadju", "if (vOzadju) zahteva.put(ProsnjaDostopa.V_OZADJU, true)" in app)
preveri("predpomnjenje domacega zaslona je v ozadju", "prenesi(c, r, vOzadju = true)" in app)
preveri("uporabnik sam odpre programe: brez parametra (racunalnik vprasa)", re.search(r"prenesi\(this, r\) \{", app) is not None)
preveri("programi med cakanjem na potrditev prosijo znova", "dostop.cakaj()" in app and "naloziStran(r)" in app)
domov = (OS / "DomovActivity.kt").read_text(encoding="utf-8")
preveri("stanje kartic (apps.running) je v ozadju", '"apps.running", org.json.JSONObject().put("apps", polje).put(ProsnjaDostopa.V_OZADJU, true)' in domov)
zasloni = (OS / "PovezaniZasloni.kt").read_text(encoding="utf-8")
preveri("Povezani zasloni: tiha preverba dovoljenja je v ozadju (brez obvestila na racunalniku)",
        "JSONObject().put(ProsnjaDostopa.V_OZADJU, true), PREVERBA_POTEK_MS" in zasloni)
sprejemnik = (OS.parent / "cast/CastReceiverService.kt").read_text(encoding="utf-8")
preveri("deli cel zaslon (CEL_ZASLON) zahteva tudi dovoljenje zaslona, gledalec nikoli",
        '(!celZaslon || (!gledalec && Dostop.smeDejanje(this, posiljatelj, "screen.start", zascitaJedro)))' in sprejemnik)
print("VSE OK" if not napak else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
