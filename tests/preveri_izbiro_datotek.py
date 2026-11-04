#!/usr/bin/env python3
"""Izbira datotek (<input type=file>, 4. 11. 2026): glavni brskalnik TV in spletni klepeti strani vrneta samo URI-je,
ki jih spusti IzbiraDatotek.sprejmi (content:// tuje avtoritete, nikoli file:// ali nas ponudnik si.safeer.*), in
nikoli surovega FileChooserParams.parseResult (ta vrne karkoli je v nameri). Pravilo samo preverja IzbiraDatotekTest.kt;
ta preverba pazi, da ga klicejo vsi izbirniki in da brskalnik onShowFileChooser sploh ima."""
import pathlib
import sys

KOREN = pathlib.Path(__file__).resolve().parent.parent
TV = KOREN / "src/main/kotlin/si/safeer/tv"


def main() -> int:
    napake = []
    pogled = (TV / "ChromiumEngineView.kt").read_text(encoding="utf-8")
    glavna = (TV / "MainActivity.kt").read_text(encoding="utf-8")
    klepet = (TV / "os/SpletniKlepetActivity.kt").read_text(encoding="utf-8")
    tabi = (TV / "TabManager.kt").read_text(encoding="utf-8")
    if "override fun onShowFileChooser(" not in pogled or "onIzbiraDatotek?.invoke(" not in pogled:
        napake.append("ChromiumEngineView: ni onShowFileChooser, ki klice onIzbiraDatotek")
    if "wv.onIzbiraDatotek = " not in glavna:
        napake.append("MainActivity: zavihek nima onIzbiraDatotek (attachTabListeners)")
    for ime, besedilo in (("MainActivity.kt", glavna), ("os/SpletniKlepetActivity.kt", klepet)):
        if "parseResult(" in besedilo:
            napake.append(f"{ime}: FileChooserParams.parseResult brez filtra - uporabi IzbiraDatotek.sprejmi")
        if "IzbiraDatotek.sprejmi(" not in besedilo:
            napake.append(f"{ime}: odgovor izbirnika ne gre skozi IzbiraDatotek.sprejmi")
        if "ACTION_OPEN_DOCUMENT" not in besedilo:
            napake.append(f"{ime}: izbirnik ni ACTION_OPEN_DOCUMENT (SAF)")
    if "w.cakaIzbiroDatotek)" not in tabi:
        napake.append("TabManager: spanje ne uposteva odprtega izbirnika (cakaIzbiroDatotek)")
    for n in napake:
        print("NAPAKA", n)
    if napake:
        return 1
    print("preveri_izbiro_datotek: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
