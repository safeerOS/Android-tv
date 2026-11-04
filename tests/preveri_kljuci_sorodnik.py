#!/usr/bin/env python3
"""Lastnik 4. 11. 2026 (05:10): kljuc, vnesen v Safeer OS, velja tudi v Predvajalniku na isti napravi. Preverba, da je
prenos med paketoma varen: storitev z dovoljenjem podpisa, preverba klicatelja, vrednosti ne v dnevnik in ne v namere.
Brez popravka pade."""
import pathlib, re, sys

R = pathlib.Path(__file__).resolve().parent.parent
OS = R / "src/main/kotlin/si/safeer/tv/os"
napak = 0


def preveri(opis, pogoj):
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    napak += 0 if pogoj else 1


m = (R / "AndroidManifest.xml").read_text(encoding="utf-8")
blok = re.search(r'<service\s+android:name="si\.safeer\.tv\.os\.KljuciSorodnikStoritev"[^>]*>', m)
preveri("manifest: storitev kljucev s signature dovoljenjem LINK", blok is not None and 'android:permission="si.safeer.tv.permission.LINK"' in blok.group(0))
preveri("manifest: LINK je protectionLevel signature", re.search(r'si\.safeer\.tv\.permission\.LINK"\s*\n?\s*android:protectionLevel="signature"', m) is not None)
s = (OS / "KljuciSorodnikStoritev.kt").read_text(encoding="utf-8") if (OS / "KljuciSorodnikStoritev.kt").exists() else ""
preveri("storitev: klicatelj po uid sporocila in podpisu", "m.sendingUid" in s and "UskladitevKljucevPravila.jeSorodnik(" in s and "podpisEnak(" in s)
preveri("storitev: vrednosti ne v dnevnik", not re.search(r"Log\.\w\([^)]*(prejeto|ZAPISI|zaSorodnika)", s))
u = (OS / "UskladitevKljucev.kt").read_text(encoding="utf-8")
preveri("odjemalec: pred posiljanjem preverba podpisa paketa", "P.jeSorodnik(paket, a.packageName, podpisEnak(a, paket))" in u)
preveri("odjemalec: izmenjava tudi brez Linka (pred preverbo povezave)", u.find("posljiSorodnikom(c)") < u.find("if (link.jeKrajevni() || !link.povezan) return"))
preveri("odjemalec: vrednosti ne v namere (samo Messenger sporocilo)", "putExtra(KljuciSorodnikStoritev.ZAPISI" not in u and ".setPackage(paket)" in u)
preveri("odjemalec: vrednosti ne v dnevnik", not re.search(r"Log\.\w\([^)]*(moji|prejeto)\b", u))
print("VSE OK" if napak == 0 else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
