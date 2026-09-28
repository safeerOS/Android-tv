#!/usr/bin/env python3
"""Statični pogodbeni preizkusi ikon kanalov TV v živo."""

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TV = (ROOT / "src/main/kotlin/si/safeer/tv/os/TvVZivo.kt").read_text(encoding="utf-8")
GLASBA = (ROOT / "src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt").read_text(encoding="utf-8")


def preveri(pogoj: bool, opis: str) -> None:
    if not pogoj:
        raise AssertionError(opis)
    print(f"  OK   {opis}")


print("\n== ikone kanalov TV v živo ==")
vrstice = [v.strip() for v in TV.splitlines() if v.strip().startswith('Kanal("')]
preveri(len(vrstice) == 13, "preizkus zajame vseh 13 kanalov")

domene = {
    "rtvslo": "rtvslo.si", "dw-en": "dw.com", "dw-de": "dw.com", "dw-es": "dw.com",
    "tagesschau24": "tagesschau.de", "f24-en": "france24.com", "f24-fr": "france24.com",
    "f24-es": "france24.com", "aje": "aljazeera.com", "trt-world": "trtworld.com",
    "cbs-news": "cbsnews.com", "arirang": "arirang.com", "redbull": "redbull.com",
}
for vrstica in vrstice:
    nizi = re.findall(r'"([^"]*)"', vrstica)
    preveri(len(nizi) == 8, f"kanal {nizi[0]} ima vsa polja")
    kanal, domaca = nizi[0], nizi[5]
    preveri(domaca.startswith("https://"), f"kanal {kanal} ima HTTPS domačo stran")
    preveri(domene[kanal] in domaca, f"kanal {kanal} uporablja uradno domeno")

preveri('sk.id.startsWith("tv:") -> R.drawable.os_ikona_tv' in GLASBA,
        "TV-kartica uporablja TV-ikono, ne glasbene")
preveri('R.drawable.os_ikona_video' in GLASBA and 'R.drawable.os_ikona_radio' in GLASBA,
        "video in radio imata svoji privzeti ikoni")
preveri((ROOT / "res/drawable/os_ikona_tv.xml").is_file(), "rezervna TV-ikona obstaja")

jeziki = sorted(ROOT.glob("res/values*/os_glasba_strings.xml"))
preveri(len(jeziki) == 7, "preizkus zajame vseh sedem jezikovnih različic")
for datoteka in jeziki:
    besedilo = datoteka.read_text(encoding="utf-8")
    preveri('name="os_media_oznaka_v_zivo"' in besedilo,
            f"oznaka V ŽIVO obstaja v {datoteka.parent.name}")
