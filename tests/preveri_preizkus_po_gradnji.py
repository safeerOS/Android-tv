#!/usr/bin/env python3
"""Naloga 135: ciste odlocitve orodja tests/preizkus_po_gradnji.py (branje dnevnika, sesutja, porocilo) - brez naprave."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import preizkus_po_gradnji as P  # noqa: E402

napak = 0


def enako(opis, pricakovano, dobljeno):
    global napak
    if pricakovano != dobljeno:
        napak += 1
        print("  NAPAKA  %s: pricakovano %r, dobljeno %r" % (opis, pricakovano, dobljeno))


DNEVNIK_OK = [
    "10-24 18:38:32.133  5909  5909 I SafeerOsMedia: tokovi: pripravljen pri debridu 84 od 95 torrentov; izbran=Opis(visina=1080)",
    "10-24 18:38:33.948  5909  6000 I SafeerTorBox: tok pripravljen: https://nexus-286.weur.tb-cdn.st/…",
    "10-24 18:38:35.669  5909  5909 I SafeerExoMer: GLASBA prva slika po 2746 ms",
]
enako("tok in prva slika", {"tok": True, "prva_slika_ms": 2746, "napaka": ""}, P.oceni(DNEVNIK_OK))
enako("brez dnevnika ni toka", {"tok": False, "prva_slika_ms": None, "napaka": ""}, P.oceni([]))
enako("neposreden tok (brez debrida) - sledi slike stejejo kot tok",
      {"tok": True, "prva_slika_ms": 1200, "napaka": ""},
      P.oceni(["I SafeerOsMedia: sledi: slika video/avc 1920x1080 *", "I SafeerExoMer: GLASBA prva slika po 1200 ms"]))
enako("napaka predvajalnika",
      {"tok": True, "prva_slika_ms": None, "napaka": "ERROR_CODE_IO_BAD_HTTP_STATUS"},
      P.oceni(["I SafeerTorBox: tok pripravljen: https://x/…",
               "I SafeerExoMer: GLASBA povzetek (napaka ERROR_CODE_IO_BAD_HTTP_STATUS): prvaSlika=-1ms prekinitve=0/0ms"]))
enako("povzetek brez napake ni napaka", "",
      P.oceni(["I SafeerExoMer: GLASBA povzetek (sprostitev): prvaSlika=2746ms prekinitve=0/0ms izpuscene=0"])["napaka"])

SESUTJE = ["--------- beginning of crash", "E AndroidRuntime: FATAL EXCEPTION: main",
           "E AndroidRuntime: Process: si.safeer.os, PID: 5909", "E AndroidRuntime: java.lang.IllegalStateException",
           "E AndroidRuntime: FATAL EXCEPTION: main", "E AndroidRuntime: Process: com.tuja.aplikacija, PID: 77"]
enako("sesutja samo nase aplikacije", 1, P.sesutja(SESUTJE, "si.safeer.os"))
enako("brez sesutij", 0, P.sesutja([], "si.safeer.os"))
enako("drug paket z isto predpono ne steje", 0, P.sesutja(["E AndroidRuntime: Process: si.safeer.os.viri, PID: 1"], "si.safeer.os"))

enako("naprava s paketom", ("192.0.2.10:5555", "si.safeer.phone"), P.naprava("192.0.2.10:5555=si.safeer.phone"))
enako("naprava brez paketa: TV", ("192.0.2.10:5555", "si.safeer.os"), P.naprava("192.0.2.10:5555"))
enako("serijska stevilka USB", ("R9TXC0CJY4W", "si.safeer.tablet"), P.naprava("R9TXC0CJY4W=si.safeer.tablet"))

enako("epizoda", ("tt0108778", 1, 1), P.epizoda("tt0108778:1:1"))
enako("film ni epizoda", None, P.epizoda("tt1375666"))
enako("napacen zapis", None, P.epizoda("tt0108778:x:1"))

ok = {"tok": True, "prva_slika_ms": 2746, "napaka": ""}
ni = {"tok": False, "prva_slika_ms": None, "napaka": ""}
enako("izid OK", True, P.uspeh(ok, 0))
enako("izid brez toka", False, P.uspeh(ni, 0))
enako("izid s sesutjem", False, P.uspeh(ok, 1))
enako("izid z napako predvajalnika", False, P.uspeh(dict(ok, napaka="ERROR_CODE_DECODING_FAILED"), 0))
enako("vrstica porocila",
      "OK      192.0.2.10:5555 si.safeer.os 0.5.109-preizkus-135 | film: slika 2746 ms | epizoda: slika 2746 ms | sesutij: 0",
      P.vrstica("192.0.2.10:5555", "si.safeer.os", "0.5.109-preizkus-135", [("film", ok), ("epizoda", ok)], 0))
enako("vrstica: epizoda brez toka je NAPAKA",
      "NAPAKA  x p v | film: slika 2746 ms | epizoda: ni toka | sesutij: 0",
      P.vrstica("x", "p", "v", [("film", ok), ("epizoda", ni)], 0))
enako("vrstica: napaka predvajalnika se pokaze",
      "NAPAKA  x p v | film: napaka ERROR_CODE_DECODING_FAILED | sesutij: 0",
      P.vrstica("x", "p", "v", [("film", dict(ok, napaka="ERROR_CODE_DECODING_FAILED"))], 0))
enako("vrstica: tok brez slike", "NAPAKA  x p v | film: tok, brez slike | sesutij: 1",
      P.vrstica("x", "p", "v", [("film", {"tok": True, "prva_slika_ms": None, "napaka": ""})], 1))

if napak:
    print("preveri_preizkus_po_gradnji: %d napak" % napak)
    sys.exit(1)
print("preveri_preizkus_po_gradnji: OK")
