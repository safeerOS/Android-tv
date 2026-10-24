#!/usr/bin/env python3
"""Naloga 135: samodejni preizkus predvajanja po vsaki gradnji, na vec testnih napravah hkrati porocilo OK/NAPAKA.

Kaj naredi na vsaki napravi:
  1. (neobvezno) namesti APK kot nadgradnjo in prebere namesceno razlicico,
  2. zazene film in epizodo serije prek PreizkusActivity (izvozena samo z dovoljenjem DUMP - ima ga samo adb/sistem,
     druge aplikacije je ne morejo zagnati) - BREZ dotikov po koordinatah (ti so 24. 10. 2026 odpirali FILMBOX+),
  3. v dnevniku caka na tok in prvo sliko (SafeerTorBox / SafeerOsMedia / SafeerExoMer), ustavi predvajanje,
  4. presteje sesutja Safeerja (logcat -b crash) od zacetka preizkusa - dnevnika naprave ne brise (logcat -T, ne -c).
Dopolnjuje tests/preizkus_os_na_tv.py (domaci zaslon, ANR, pomnilnik). Naslovov naprav ni v datoteki - podas jih ob zagonu.

Uporaba:
    python3 tests/preizkus_po_gradnji.py NAPRAVA[=paket] [NAPRAVA[=paket] ...] [--apk POT] [--film tt…] [--epizoda tt…:S:E]
    paket: si.safeer.os (TV, privzeto), si.safeer.phone, si.safeer.tablet
Izhod: 0 = vse OK, 1 = kje NAPAKA, 2 = napacni argumenti.
"""
from __future__ import annotations

import argparse
import re
import shlex
import subprocess
import sys
import time

PRIVZET_PAKET = "si.safeer.os"
AKTIVNOST = "si.safeer.tv.os.PreizkusActivity"
EXTRA_IMDB = "si.safeer.preizkus.IMDB"
EXTRA_NASLOV = "si.safeer.preizkus.NASLOV"

_PRVA_SLIKA = re.compile(r"SafeerExoMer: \S+ prva slika po (\d+) ms")
_NAPAKA = re.compile(r"SafeerExoMer: \S+ povzetek \(napaka (\S+?)\)")
_TOK = ("SafeerTorBox: tok pripravljen", "SafeerOsMedia: sledi: slika")
_EPIZODA = re.compile(r"(tt\d+):(\d+):(\d+)")


# ---------------------------------------------------------------------- ciste odlocitve (tests/preveri_preizkus_po_gradnji.py)

def oceni(vrstice: list[str]) -> dict:
    """Iz dnevnika: ali je tok pripravljen, cas do prve slike (ms) in napaka predvajalnika (koda Media3)."""
    tok = any(any(z in v for z in _TOK) for v in vrstice)
    slika = next((int(m.group(1)) for v in vrstice for m in [_PRVA_SLIKA.search(v)] if m), None)
    napaka = next((m.group(1) for v in vrstice for m in [_NAPAKA.search(v)] if m), "")
    return {"tok": tok, "prva_slika_ms": slika, "napaka": napaka}


def sesutja(vrstice: list[str], paket: str) -> int:
    """Stevilo sesutij [paket] v medpomnilniku crash (»Process: paket, PID«) - druge aplikacije ne stejejo."""
    return sum(1 for v in vrstice if re.search(r"Process: %s, PID" % re.escape(paket), v))


def naprava(arg: str) -> tuple[str, str]:
    """»naslov[=paket]« -> (naslov, paket); brez paketa TV (si.safeer.os)."""
    naslov, _, paket = arg.partition("=")
    return naslov.strip(), (paket.strip() or PRIVZET_PAKET)


def epizoda(imdb: str):
    """»tt…:S:E« -> (tt…, S, E); sicer None (film)."""
    m = _EPIZODA.fullmatch(imdb.strip())
    return (m.group(1), int(m.group(2)), int(m.group(3))) if m else None


def uspeh(o: dict, st_sesutij: int) -> bool:
    return o["tok"] and o["prva_slika_ms"] is not None and not o["napaka"] and st_sesutij == 0


def _opis(o: dict) -> str:
    if o["napaka"]:
        return "napaka " + o["napaka"]
    if o["prva_slika_ms"] is not None:
        return "slika %d ms" % o["prva_slika_ms"]
    return "tok, brez slike" if o["tok"] else "ni toka"


def vrstica(naslov: str, paket: str, verzija: str, rezultati: list, st_sesutij: int) -> str:
    """Ena vrstica porocila za napravo: OK samo, ce sta film in epizoda dala sliko brez napake in ni sesutja."""
    ok = all(uspeh(o, 0) for _, o in rezultati) and st_sesutij == 0
    deli = ["%s: %s" % (ime, _opis(o)) for ime, o in rezultati] + ["sesutij: %d" % st_sesutij]
    return "%-7s %s %s %s | %s" % ("OK" if ok else "NAPAKA", naslov, paket, verzija, " | ".join(deli))


# ---------------------------------------------------------------------- naprava (adb)

def _adb(n: str, *a: str, rok: int = 60) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", n, *a], capture_output=True, text=True, timeout=rok)


def _cas_naprave(n: str) -> str:
    """Trenutni cas naprave v obliki za logcat -T (MM-DD hh:mm:ss.mmm, krajevni cas naprave kot v logcat)."""
    return _adb(n, "shell", "date", "+%m-%d_%H:%M:%S.000", rok=15).stdout.strip().replace("_", " ")


def _dnevnik(n: str, medpomnilnik: str = "main", od: str = "") -> list[str]:
    # Dnevnika naprave ne brisemo (logcat -c bi izbrisal tudi zapise drugih aplikacij in prejsnjih preizkusov, ki jih
    # lastnik morda se potrebuje) - beremo samo zapise od casa [od] naprej.
    a = ["logcat", "-d", "-b", medpomnilnik]
    if od:
        # »adb logcat« (ne »adb shell«) argumente zascite sam - cas s presledkom gre brez narekovajev.
        a += ["-T", od]
    return _adb(n, *a).stdout.splitlines()


def _verzija(n: str, paket: str) -> str:
    m = re.search(r"versionName=(\S+)", _adb(n, "shell", "dumpsys", "package", paket).stdout)
    return m.group(1) if m else "?"


def _predvajaj(n: str, paket: str, imdb: str, naslov: str, rok: float) -> dict:
    # Ugasnjen zaslon (tablica »Dozing«, 24. 10. 2026): brez povrsine ni prve slike - zaslon najprej zbudimo.
    _adb(n, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    _adb(n, "shell", "wm", "dismiss-keyguard")
    od = _cas_naprave(n)
    if not re.fullmatch(r"\d\d-\d\d \d\d:\d\d:\d\d\.000", od):
        # Brez casa naprave bi brali tudi stare zapise (lazno OK iz prejsnjega preizkusa) - raje napaka.
        return {"tok": False, "prva_slika_ms": None, "napaka": "cas_naprave"}
    # adb shell zdruzi argumente v en ukaz brez narekovajev: naslov s presledkom (»Preizkus film«) bi razpadel in drugi
    # del bi postal paket namena (WP28 S, 24. 10. 2026) - zato vsak argument v narekovaje.
    # -f 0x10008000 (NEW_TASK | CLEAR_TASK): vsak zagon nova aktivnost (sicer gre drugi zagon le v obstojeco nalogo -
    # SM-X210). Stevilka, ker am na tablici imen stikal --activity-* ne pozna (»Unknown option«).
    ukaz = ["am", "start", "-f", "0x10008000", "-n", "%s/%s" % (paket, AKTIVNOST),
            "--es", EXTRA_IMDB, imdb, "--es", EXTRA_NASLOV, naslov]
    r = _adb(n, "shell", " ".join(shlex.quote(x) for x in ukaz))
    if r.returncode != 0 or "Error" in r.stdout + r.stderr:
        return {"tok": False, "prva_slika_ms": None, "napaka": "zagon"}
    konec = time.monotonic() + rok
    o = oceni([])
    while time.monotonic() < konec:
        time.sleep(2)
        o = oceni(_dnevnik(n, od=od))
        if o["prva_slika_ms"] is not None or o["napaka"]:
            break
    _adb(n, "shell", "input", "keyevent", "KEYCODE_MEDIA_STOP")
    time.sleep(2)
    return o


def preizkusi(arg: str, apk: str, film: str, ep: str, rok: float) -> tuple[bool, str]:
    n, paket = naprava(arg)
    if ":" in n:     # naprava prek omrezja: povezi (ze povezana ostane, kot je)
        subprocess.run(["adb", "connect", n], capture_output=True, text=True, timeout=20)
    if _adb(n, "get-state", rok=15).stdout.strip() != "device":
        return False, "%-7s %s %s ni povezana" % ("NAPAKA", n, paket)
    if apk:
        r = _adb(n, "install", "-r", apk, rok=300)
        if "Success" not in r.stdout:
            return False, "%-7s %s %s namestitev ni uspela: %s" % ("NAPAKA", n, paket, (r.stdout + r.stderr).strip()[-120:])
    od_sesutij = _cas_naprave(n)
    rezultati = [("film", _predvajaj(n, paket, film, "Preizkus film", rok))]
    e = epizoda(ep)
    if e:
        rezultati.append(("epizoda", _predvajaj(n, paket, ep, "Preizkus serija", rok)))
    st = sesutja(_dnevnik(n, "crash", od=od_sesutij), paket)
    v = vrstica(n, paket, _verzija(n, paket), rezultati, st)
    return v.startswith("OK"), v


def main(argv: list[str]) -> int:
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("naprave", nargs="+", help="naslov[:vrata] ali serijska stevilka, neobvezno =paket")
    a.add_argument("--apk", default="", help="namesti ta APK kot nadgradnjo (sicer preizkusi namesceno)")
    a.add_argument("--film", default="tt1375666", help="id IMDb filma (privzeto Inception)")
    a.add_argument("--epizoda", default="tt0108778:1:1", help="tt…:sezona:epizoda (privzeto Friends S1E1); prazno = brez")
    a.add_argument("--rok", type=float, default=60.0, help="najvec sekund za tok in prvo sliko")
    p = a.parse_args(argv)
    vse_ok = True
    for arg in p.naprave:
        ok, v = preizkusi(arg, p.apk, p.film, p.epizoda, p.rok)
        print(v, flush=True)
        vse_ok = vse_ok and ok
    print("PREIZKUS PO GRADNJI: " + ("OK" if vse_ok else "NAPAKA"))
    return 0 if vse_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
