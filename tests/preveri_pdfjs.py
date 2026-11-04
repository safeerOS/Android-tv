#!/usr/bin/env python3
"""Vgrajeni PDF pregledovalnik (assets/pdfjs): vsaka datoteka, ki jo nalozi viewer, mora biti v repozitoriju.

Izmerjeno 4. 11. 2026 na TV: .gitignore (build/) je izlocil assets/pdfjs/build, APK ni imel pdf.mjs in
pdf.worker.mjs, pregledovalnik je ostal prazen (globalThis.pdfjsLib undefined). Ta preverba to ujame.
"""
import os
import re
import subprocess
import sys

KOREN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(KOREN, "assets", "pdfjs", "web")
napake = []


def obstaja_v_gitu(pot):
    rel = os.path.relpath(pot, KOREN)
    try:
        izid = subprocess.run(["git", "-C", KOREN, "ls-files", "--error-unmatch", rel],
                              capture_output=True, text=True)
    except FileNotFoundError:
        return os.path.isfile(pot)
    return izid.returncode == 0


html = open(os.path.join(WEB, "viewer.html"), encoding="utf-8").read()
for src in re.findall(r'<script[^>]+src="([^"]+)"', html) + re.findall(r'<link[^>]+href="([^"]+\.css)"', html):
    pot = os.path.normpath(os.path.join(WEB, src))
    if not os.path.isfile(pot):
        napake.append("viewer.html nalozi " + src + ", datoteke ni")
    elif not obstaja_v_gitu(pot):
        napake.append(src + " ni v gitu (.gitignore?) - CI in klon je ne dobita")

mjs = open(os.path.join(WEB, "viewer.mjs"), encoding="utf-8").read()
for privzeto in ("../build/pdf.worker.mjs",):
    if privzeto not in mjs:
        napake.append("viewer.mjs ne omenja " + privzeto + " - preveri pot delavca")
    pot = os.path.normpath(os.path.join(WEB, privzeto))
    if not os.path.isfile(pot) or not obstaja_v_gitu(pot):
        napake.append(privzeto + " manjka v gitu")

safeer = open(os.path.join(WEB, "safeer.mjs"), encoding="utf-8").read()
if 'set("enableScripting", false)' not in safeer:
    napake.append("safeer.mjs ne izklopi skript v PDF (enableScripting) - pdf.sandbox.mjs namerno ni v paketu")

if napake:
    for n in napake:
        print("NAPAKA pdfjs:", n)
    sys.exit(1)
print("pdfjs OK")
