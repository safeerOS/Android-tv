#!/usr/bin/env python3
"""Naloga 42: nov TV zaganjalnik (beta) - preverbe brez Androida (APK zgradi CI).

- vsako besedilo (R.string.*), ki ga uporabljajo datoteke zaganjalnika, je v values in v vseh sestih jezikih;
- vsak vir (R.drawable/R.layout/R.style) obstaja;
- tema Pokrajina ima vse atribute kot ostale teme in barve z dovolj kontrasta (WCAG AA: besedilo 4.5, poudarek fokusa 3);
- stari domaci zaslon odpre novega samo, ce je beta vklopljena;
- v vmesniku ni nadomestkov (TODO, lorem, »kmalu«).
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent
SRC = KOREN / "src/main/kotlin/si/safeer/tv"
RES = KOREN / "res"
JEZIKI = ["values", "values-en", "values-sl", "values-de", "values-es", "values-fr", "values-it"]

# Datoteke zaganjalnika (vsak del naloge 42 doda svoje).
DATOTEKE = [
    "os/PokrajinaActivity.kt",
    "os/ZaganjalnikBeta.kt",
    "os/MiniPredvajalnik.kt",
]

napak = 0


def preveri(opis: str, pogoj: bool, podrobno: str = "") -> None:
    global napak
    if pogoj:
        print(f"  ok  {opis}")
    else:
        napak += 1
        print(f"  NAPAKA  {opis}" + (f": {podrobno}" if podrobno else ""))


def besedila(mapa: str) -> set:
    imena = set()
    for f in (RES / mapa).glob("*.xml"):
        imena.update(re.findall(r'<(?:string|plurals)\s+name="([^"]+)"', f.read_text(encoding="utf-8")))
    return imena


def neprevedljiva() -> set:
    """Imena z translatable="false" (ime znamke) - ta so samo v values."""
    imena = set()
    for f in (RES / "values").glob("*.xml"):
        imena.update(re.findall(r'<string\s+name="([^"]+)"\s+translatable="false"', f.read_text(encoding="utf-8")))
    return imena


def viri(vrsta: str) -> set:
    if vrsta == "style":
        imena = set()
        for f in (RES / "values").glob("*.xml"):
            imena.update(n.replace(".", "_") for n in re.findall(r'<style\s+name="([^"]+)"', f.read_text(encoding="utf-8")))
        return imena
    imena = set()
    for d in RES.glob(vrsta + "*"):
        imena.update(p.stem.split(".")[0] for p in d.iterdir())
    return imena


def barve() -> dict:
    izid = {}
    for f in (RES / "values").glob("*.xml"):
        for ime, v in re.findall(r'<color\s+name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>', f.read_text(encoding="utf-8")):
            izid[ime] = int(("FF" + v) if len(v) == 6 else v, 16)
    return izid


def svetlost(argb: int) -> float:
    def k(c):
        s = c / 255
        return s / 12.92 if s <= 0.03928 else ((s + 0.055) / 1.055) ** 2.4
    return 0.2126 * k((argb >> 16) & 255) + 0.7152 * k((argb >> 8) & 255) + 0.0722 * k(argb & 255)


def kontrast(a: int, b: int) -> float:
    la, lb = svetlost(a), svetlost(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def cez(spredaj: int, zadaj: int) -> int:
    a = ((spredaj >> 24) & 255) / 255
    k = lambda s: int(((spredaj >> s) & 255) * a + ((zadaj >> s) & 255) * (1 - a))
    return 0xFF000000 | (k(16) << 16) | (k(8) << 8) | k(0)


def main() -> int:
    print("Preverba novega TV zaganjalnika (beta)")
    vsebina = {}
    for d in DATOTEKE:
        p = SRC / d
        preveri(f"datoteka {d}", p.is_file())
        if p.is_file():
            vsebina[d] = p.read_text(encoding="utf-8")

    print("\n== besedila v sestih jezikih ==")
    po_jezikih = {m: besedila(m) for m in JEZIKI}
    znamke = neprevedljiva()
    for d, t in vsebina.items():
        for ime in sorted(set(re.findall(r"(?<!android\.)R\.string\.([A-Za-z0-9_]+)", t))):
            manjka = [m for m in (["values"] if ime in znamke else JEZIKI) if ime not in po_jezikih[m]]
            preveri(f"{d}: {ime}", not manjka, "manjka v " + ", ".join(manjka))

    print("\n== viri ==")
    for vrsta in ("drawable", "layout", "style"):
        obstojeci = viri(vrsta)
        for d, t in vsebina.items():
            for ime in sorted(set(re.findall(rf"(?<!android\.)R\.{vrsta}\.([A-Za-z0-9_]+)", t))):
                preveri(f"{d}: R.{vrsta}.{ime}", ime in obstojeci)

    print("\n== tema Pokrajina ==")
    slogi = (RES / "values/os_styles.xml").read_text(encoding="utf-8")
    def atributi(ime):
        m = re.search(rf'<style name="{re.escape(ime)}" parent="[^"]+">(.*?)</style>', slogi, re.S)
        return re.findall(r'<item name="([^"]+)">', m.group(1)) if m else None
    preveri("isti atributi kot Safeer Namizje", atributi("Theme.SafeerOs.Pokrajina") == atributi("Theme.SafeerOs.Namizje"))
    preveri("crna razlicica za predvajanje", atributi("Theme.SafeerOs.Pokrajina.Predvajalnik") is not None)
    tema = (SRC / "os/Tema.kt").read_text(encoding="utf-8")
    preveri("tema je v izbiri (Videz) na prvem mestu", re.search(r"val VSE = listOf\(\s*(//[^\n]*\n\s*)*Vnos\(PokrajinaPravila\.TEMA", tema) is not None)
    preveri("brez izbire velja Pokrajina samo z novim zaganjalnikom", "PokrajinaPravila.tema(shranjena, ZaganjalnikBeta.vklopljen(c))" in tema)
    b = barve()
    manjka = [k for k in ("ozadje", "besedilo", "umirjeno", "mint", "kartica_steklo", "kartica_steklo_fokus", "kartica_obroba")
              if "tema_pokrajina_" + k not in b]
    preveri("barve teme Pokrajina", not manjka, "manjka " + ", ".join(manjka))
    if manjka:
        return konec()
    p = lambda ime: b["tema_pokrajina_" + ime]
    steklo = cez(p("kartica_steklo"), p("ozadje"))
    steklo_f = cez(p("kartica_steklo_fokus"), p("ozadje"))
    for opis, k, meja in [
        ("besedilo na ozadju", kontrast(p("besedilo"), p("ozadje")), 4.5),
        ("besedilo na steklu", kontrast(p("besedilo"), steklo), 4.5),
        ("besedilo na steklu s fokusom", kontrast(p("besedilo"), steklo_f), 4.5),
        ("umirjeno besedilo na steklu", kontrast(p("umirjeno"), steklo), 4.5),
        ("obroba fokusa (poudarek) na steklu", kontrast(p("mint"), steklo), 3.0),
        ("obroba fokusa ob obrobi brez fokusa", kontrast(p("mint"), cez(p("kartica_obroba"), steklo)), 1.5),
    ]:
        preveri(f"kontrast {opis}: {k:.2f} >= {meja}", k >= meja)

    print("\n== beta in nadomestki ==")
    domov = (SRC / "os/DomovActivity.kt").read_text(encoding="utf-8")
    preveri("stari zaslon odpre novega samo z vklopljeno beto",
            re.search(r"if \(ZaganjalnikBeta\.vklopljen\(this\)\) \{\s*try \{\s*val nov = Intent\(this, PokrajinaActivity::class\.java\)", domov) is not None)
    manifest = (KOREN / "AndroidManifest.xml").read_text(encoding="utf-8")
    preveri("PokrajinaActivity v manifestu, ni izvozena", re.search(r'android:name="si\.safeer\.tv\.os\.PokrajinaActivity"\s+android:exported="false"', manifest) is not None)
    for d, t in vsebina.items():
        najdeno = re.findall(r"(?i)\b(TODO|FIXME|lorem|placeholder|coming soon|kmalu na voljo)\b", t)
        preveri(f"{d}: brez nadomestkov", not najdeno, ", ".join(najdeno))

    preveri("mini predvajalnik na novem domacem zaslonu", "MiniPredvajalnik.naOknu(this)" in vsebina.get("os/PokrajinaActivity.kt", ""))
    return konec()


def konec() -> int:
    print()
    if napak:
        print(f"preveri_zaganjalnik_beta: {napak} napak")
        return 1
    print("preveri_zaganjalnik_beta: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
