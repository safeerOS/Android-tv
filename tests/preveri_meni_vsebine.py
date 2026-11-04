#!/usr/bin/env python3
"""Meni vsebine v Medijskem centru je meni s ploscicami (MeniKanala) za VSO vsebino (preverba TV 4. 11. 2026).

Prej: kanal v zivo je imel ploscice, Radio, Glasba, Video, Nadaljuj gledanje in »Nadaljuj z druge naprave« pa sivi
sistemski seznam (AlertDialog.setItems brez sloga Os_Okno).
"""
import re
import sys
from pathlib import Path

VIR = (Path(__file__).resolve().parents[1] / "src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt").read_text(encoding="utf-8")


def telo(ime: str) -> str:
    m = re.search(r"\n    private fun " + ime + r"\(", VIR)
    if not m:
        sys.exit(f"NAPAKA: ni funkcije {ime}")
    nasl = re.search(r"\n    (private |override |internal )?fun |\n    // -{10}", VIR[m.end():])
    return VIR[m.start(): m.end() + (nasl.start() if nasl else len(VIR))]


napak = 0
for ime in ("meni", "meniNadaljuj", "nadaljujZDrugeNaprave", "izberiPrevzem"):
    t = telo(ime)
    ok = "MeniKanala.pokazi(" in t and ".setItems(" not in t and "AlertDialog.Builder(this)." not in t
    print(("  OK   " if ok else "  NAPAKA ") + f"{ime}: meni s ploscicami, brez sivega seznama")
    napak += 0 if ok else 1
t = telo("meni")
ok = "if (kanal) {\n            MeniKanala" not in t
print(("  OK   " if ok else "  NAPAKA ") + "meni: ploscice ne samo za kanale")
napak += 0 if ok else 1
if napak:
    sys.exit(f"preveri_meni_vsebine: {napak} napak")
print("preveri_meni_vsebine: OK")
