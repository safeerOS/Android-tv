#!/usr/bin/env python3
"""Staticna preverba: »Shrani« ponudnika Usenet mora odkleniti tudi trajni zapah pogona (UsenetNntp.dovoliPrijavoZnova)."""
import pathlib, re, sys
src = (pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os/UsenetPonudnik.kt").read_text()
m = re.search(r"fun shrani\(p: P\.Ponudnik, geslo: P\.Geslo\): Boolean(.*?)\n    \}", src, re.S)
telo = m.group(1) if m else ""
if "UsenetNntp.dovoliPrijavoZnova(" not in telo or "UsenetZapahShramba.namesti(" not in telo:
    print("NAPAKA: UsenetPonudnik.shrani ne odklene trajnega zapaha UsenetNntp"); sys.exit(1)
print("OK")
