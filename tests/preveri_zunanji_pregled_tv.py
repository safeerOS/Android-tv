#!/usr/bin/env python3
"""Najdbe CodeRabbit 4. 11. 2026 (TV), ki jih ni mogoce preizkusiti brez naprave: preverba v kodi, da popravek ostane.

Vsaka vrstica je ena preverjena najdba (vzrok v sporocilu commita). Brez popravka pade.
"""
import os, re, sys
KOREN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def beri(p):
    with open(os.path.join(KOREN, p), encoding="utf-8") as f:
        return f.read()
napake = []
def preveri(opis, pogoj):
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    if not pogoj: napake.append(opis)

zag = beri("src/main/kotlin/si/safeer/tv/os/ZaganjalnikActivity.kt")
i_vrata = zag.find("Zaganjalnik.preveriObZagonu(this)")
i_spredaj = zag.find("Zaganjalnik.domNaZaslonu(this, true)")
i_zgradi = zag.find("        zgradi()\n")
preveri("Zaganjalnik: dom spredaj po vratih in pred zgradi() (padec pred onResume steje)", 0 < i_vrata < i_spredaj < i_zgradi)

nas = beri("src/main/kotlin/si/safeer/tv/os/NastavitveActivity.kt")
preveri("Nastavitve: Scit pri sosedu brez tihega izklopa", "if (!Scit.odpriZaslon(this)) preklopiScit()" not in nas and "if (!Scit.vodimo(this)) Scit.odpriVklop(this)" in nas)

b = beri("build_tv_apk.sh")
gen = b[b.find("keytool -genkeypair"):b.find("-dname", b.find("keytool -genkeypair"))]
preveri("build_tv_apk: nova shramba z geslom shrambe", "-storepass:env RELEASE_STORE_PASS" in gen)
preveri("build_tv_apk: razlicni gesli za novo PKCS12 shrambo zavrnjeni", '[ "$RELEASE_STORE_PASS" != "$RELEASE_KEY_PASS" ]' in b)

privzeto = beri("res/values/os_strings.xml")
for ime in ("os_splet_brskalnik", "os_splet_brskalnik_opis", "os_splet_brskalnik_privzeto", "os_splet_brskalnik_ni"):
    m = re.search(r'<string name="%s">([^<]*)</string>' % ime, privzeto)
    preveri("os_strings privzeto v anglescini: " + ime, bool(m) and not re.search(r"[čšžČŠŽ]|Brskalnik|brskalnik|privzeto|Na napravi", m.group(1)))

g = beri("src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt")
preveri("Glasba: video vrsta v podpisu glave", "|${videoVsi.map { it.id }.hashCode()}\")" in g)
preveri("Glasba: klikNaFilm po koncu in preklicu priprave", g.count("koncajKlikNaFilm()") >= 3 and "OsPravila.koncajKlikNaFilm(" in g)
preveri("Glasba: izpraznjena vrsta slik pobrise oznake cakajocih", "?.tag = null } } }" in g)
preveri("Glasba: izbira kanalov se konca ob menjavi razdelka", "if (i != TV_V_ZIVO) izbiraKanalov = null" in g)

ma = beri("src/main/kotlin/si/safeer/tv/MainActivity.kt")
preveri("Brskalnik: potek odgovora Scita odpre zaslon samo z odprtim menijem", "if (b == null) { if (dialog.isShowing) zaslonScitaOs(false) }" in ma)

pr = beri("src/main/kotlin/si/safeer/tv/os/PredvajanjeActivity.kt")
preveri("Predvajanje: casovnik podnapisov iz OsPravila.pripravljenOd", "OsPravila.pripravljenOd(pripravljenZa, pripravljenOd, kljuc, pripravljen, zdaj)" in pr)

if napake:
    print("NAPAKA zunanji pregled TV:", len(napake)); sys.exit(1)
print("zunanji pregled TV OK")
