#!/usr/bin/env python3
"""Popravki slepega pregleda TV3 (15. 11. 2026): staticna preverba povezav (pravila so v tests/KazalecPopravki3*Test.kt in
tests/HttpsListFetcherPreverbaTest.kt; tu je preverjeno, da jih vmesnik in prenos seznamov res uporabljajo).

- N5: spust OK po »samo pokazi« se pogoltne prek KazalecPravila.okSpust (TvKeyRouter), zastavico postavi samo veja »samo pokazi«;
- N6: skrijZaMisko odloca KazalecPravila.smeSkritiZaMisko (z cakaKlik);
- I4: onSizeChanged odloca KazalecPravila.zacetnaLega (sredina samo prvic);
- N1: KazalecPravila.gostitelj ne uporablja java.net.URI; N3: HttpsListFetcher ne uporablja HttpURLConnection in ne sledi preusmeritvam sam.
Integracija z WebView (keyup brez keydown, prava miska, prvi onSizeChanged) je NEPREVERJENA na napravi.
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parents[1]
SRC = KOREN / "src/main/kotlin"
napak = 0


def beri(pot: Path) -> str:
    return pot.read_text(encoding="utf-8")


def preveri(opis: str, pogoj: bool) -> None:
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    if not pogoj:
        napak += 1


router = beri(SRC / "si/safeer/tv/tv/TvKeyRouter.kt")
pogled = beri(SRC / "si/safeer/tv/VirtualPointerView.kt")
pravila = beri(SRC / "si/safeer/tv/KazalecPravila.kt")
agent = beri(SRC / "com/safeer/threatfeed/ThreatListAgent.kt")

preveri("N5: spust OK prek KazalecPravila.okSpust (klik, pogoltni, prepusti)",
        "KazalecPravila.okSpust(host.virtualPointerView.cakaKlik, host.virtualPointerView.okSpustPogoltni)" in router
        and "si.safeer.tv.KazalecPravila.OkSpust.POGOLTNI) return true" in router)
preveri("N5: zastavico okSpustPogoltni postavi samo veja »samo pokazi« (tipkaSamoPokaze), klik jo ponastavi",
        len(re.findall(r"okSpustPogoltni = true", router)) == 1
        and re.search(r"tipkaSamoPokaze\([^\n]*\{\n\s*host\.virtualPointerView\.okPorabljen = true\n\s*host\.virtualPointerView\.okSpustPogoltni = true", router) is not None
        and "miska.cakaKlik = true; miska.okSpustPogoltni = false" in router)
preveri("N6: skrijZaMisko odloca KazalecPravila.smeSkritiZaMisko(..., cakaKlik, ...)",
        "KazalecPravila.smeSkritiZaMisko(isPointerVisible, vlecem, cakaKlik, skrivanje.skrit)" in pogled)
preveri("I4: onSizeChanged odloca KazalecPravila.zacetnaLega in si zapomni prvo postavitev",
        "KazalecPravila.zacetnaLega(legaPostavljena, pointerX, pointerY, w, h)" in pogled and "legaPostavljena = true" in pogled
        and not re.search(r"if \(pointerX == 0f && pointerY == 0f\)", pogled))
preveri("N1: KazalecPravila ne uporablja java.net.URI", "java.net.URI" not in re.sub(r"/\*.*?\*/", "", pravila, flags=re.S).replace("//", "\n//"))
m = re.search(r"class HttpsListFetcher\(.*?\n}\n\n", agent, flags=re.S)
telo = m.group(0) if m else ""
preveri("N3: HttpsListFetcher je v datoteki", bool(telo))
preveri("N3: HttpsListFetcher ne uporablja HttpURLConnection in ne sledi preusmeritvam sam (followRedirects(false))",
        "HttpURLConnection(" not in telo and "openConnection" not in telo and "followRedirects(false)" in telo and "followSslRedirects(false)" in telo)
preveri("N3: razresitev imena je pripeta (Dns), vsi naslovi prek isNonPublic, IP-literal prek literalAddress",
        ".dns(PinnedDns())" in telo and "all.any { isNonPublic(it.address) }" in telo and "ThreatListNetwork.literalAddress(target.host)" in telo)
preveri("N3: produkcijska koda ne nastavi allowPlainHttpForTests ne clientHook",
        not any("allowPlainHttpForTests" in beri(p) or "clientHook" in beri(p) for p in (SRC / "si").rglob("*.kt")))
sys.exit(1 if napak else 0)
