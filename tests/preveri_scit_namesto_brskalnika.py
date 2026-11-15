#!/usr/bin/env python3
"""Lastnik 15. 11. 2026: Scit v ozadju nadomesti brskalnikovo filtriranje oglasnih zahtev; brskalnik je hitrejsi.
Preverba: shouldInterceptRequest PRED AdBlockEngine.handleIntercept vpraša ScitStanjePravila.brskalnikFiltriraOglase z
Scit.dnsFiltrira(context), kozmeticna pravila po gostitelju pa ostanejo v CosmeticFilterEngine."""
import pathlib, re, sys
k = pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv"
v = (k / "ChromiumEngineView.kt").read_text(encoding="utf-8")
napake = []
i = v.find("AdBlockEngine.handleIntercept(")
vrata = "ScitStanjePravila.brskalnikFiltriraOglase(AdBlockEngine.isEnabled, si.safeer.tv.scit.Scit.dnsFiltrira(context))"
j = v.rfind(vrata, 0, i)
if i < 0 or j < 0 or i - j > 400:
    napake.append("ChromiumEngineView: pred AdBlockEngine.handleIntercept ni vrat Scit.dnsFiltrira")
elif "return null" not in v[j:i]:
    napake.append("ChromiumEngineView: vrata ne vrnejo null")
c = (k / "CosmeticFilterEngine.kt").read_text(encoding="utf-8")
if "pravilaZaGostitelja(pageUrl, AdBlockEngine.isEnabled)" not in c:
    napake.append("CosmeticFilterEngine: pravila po gostitelju niso v CSS")
s = (k / "scit/Scit.kt").read_text(encoding="utf-8")
if not re.search(r"TRANSPORT_VPN", s):
    napake.append("Scit.dnsFiltrira ne preveri VPN omrezja")
if "starostUtripaMs(context)" not in s:
    napake.append("Scit.dnsFiltrira ne preveri utripa storitve (XX1: ubit proces + tuj VPN)")
sv = (k / "scit/ScitStoritev.kt").read_text(encoding="utf-8")
if sv.count("Scit.utrip(this)") < 2:
    napake.append("ScitStoritev: utrip mora biti ob zagonu IN v utripNaloga() (vsakih 10 s)")
if "elapsedRealtime" not in s or "ScitStanjePravila.starostUtripaMs(zapis" not in s:
    napake.append("Scit: utrip mora uporabljati cas od zagona (ZZ4), ne stenske ure")
print("\n".join(napake) or "OK preveri_scit_namesto_brskalnika")
sys.exit(1 if napake else 0)
