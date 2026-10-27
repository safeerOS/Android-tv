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
    "os/SporociloPasica.kt",
    "os/NapraveKrogaPravila.kt",
    "os/DomacaLupa.kt",
    "os/PrikaziNa.kt",
    "os/NastavitveActivity.kt",
    "os/UsenetPonudnikOkno.kt",
    "os/UsenetNntp.kt",
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

    print("\n== sporocila na hitro (del C) ==")
    pokrajina = vsebina.get("os/PokrajinaActivity.kt", "")
    # Lastnik 27. 10. 2026 (na TV z beto): vrstica zadnjih sporocil na domacem zaslonu je nepotrebna - odstranjena.
    # Sporocila ostanejo v aplikaciji Sporocila (gumb v vrstici), opozorilo med predvajanjem ostane spodaj.
    preveri("domaci zaslon brez vrstice zadnjih sporocil",
            "SporocilaHitroPravila" not in pokrajina and "vrstaSporocil" not in pokrajina and "KlepetLinka.poslusalci" not in pokrajina)
    os_act = (SRC / "os/OsActivity.kt").read_text(encoding="utf-8")
    preveri("vsak zaslon Safeer OS v ospredju pokaze opozorilo (samo z novim zaganjalnikom)", "KlepetLinka.prikaz = if (!ZaganjalnikBeta.vklopljen(this)) null" in os_act)
    preveri("opozorilo se ob odhodu zaslona umakne", re.search(r"override fun onPause\(\) \{.*?KlepetLinka\.prikaz = null", os_act, re.S) is not None)
    pasica = vsebina.get("os/SporociloPasica.kt", "")
    preveri("opozorilo ne vzame fokusa (predvajanje tece naprej)", "isFocusable = false" in pasica and "requestFocus" not in pasica)
    klepet = (SRC / "os/KlepetLinka.kt").read_text(encoding="utf-8")
    preveri("prejem sporocila sprozi opozorilo samo zunaj odprtega pogovora",
            "SporocilaHitroPravila.opozorilo(vOspredju = true, odprtPogovor = odprt)" in klepet)

    print("\n== Safeer Link: naprave ozjega kroga (del D) ==")
    preveri("Safeer Link v vrstici odpre naprave kroga", "PokrajinaPravila.LINK -> napraveKroga()" in pokrajina)
    preveri("seznam samo iz ozjega kroga (NapraveKrogaPravila.zaPrikaz z OzjiKrog.vKrogu)",
            "NapraveKrogaPravila.zaPrikaz(" in pokrajina and "OzjiKrog.vKrogu(this, it.id)" in pokrajina)
    domov = (SRC / "os/DomovActivity.kt").read_text(encoding="utf-8")
    for pot in ("PovezaniZasloni.zaprosi(this, link, r)", "Intent(this, ZaslonActivity::class.java)", "DatotekeActivity.EXTRA_RACUNALNIK"):
        preveri(f"ista pot kot kartica Zaslon: {pot}", pot in pokrajina and pot in domov)
    usk = (SRC / "os/UskladitevKljucev.kt").read_text(encoding="utf-8")
    preveri("prejeto zaupanje se uporabi samo po NapraveKrogaPravila.sprejmiZaupanje",
            re.search(r"P\.PREDPONA_ZAUPANJA\) ->\s*if \(zaupanjeVelja\(c, z\.kljuc\)\)", usk) is not None and
            "NapraveKrogaPravila.sprejmiZaupanje(NapraveKrogaPravila.VIR_USKLADITEV" in usk)
    preveri("poslje se samo zaupanje napravam v krogu", "NapraveKrogaPravila.zaPoslati(si.safeer.tv.link.ZaupanjeZaslona.vsaJedra(c))" in usk)
    preveri("prejem usklajevanja zahteva ozji krog posiljatelja", "val vKrogu = od.isNotBlank() && OzjiKrog.vKrogu(a, od)" in usk)
    # Dostopa nikoli ne podeli besedilo sporocila: poti sporocil ne klicejo nicesar, kar daje dostop ali zaupanje.
    dajeDostop = re.compile(r"ZaupanjeZaslona\.nastavi|Dostop\.(?:nastavi|dodaj|odpri|dovoli)|nastaviJedro")
    for d in ("os/KlepetLinka.kt", "os/SporocilaKanali.kt", "os/SporocilaHitroPravila.kt", "os/SporociloPasica.kt", "os/PokrajinaActivity.kt"):
        t = (SRC / d).read_text(encoding="utf-8")
        preveri(f"{d}: sporocilo ne podeli dostopa", dajeDostop.search(t) is None)

    print("\n== lupa in glasovni ukazi (del E) ==")
    lupa = vsebina.get("os/DomacaLupa.kt", "")
    prikazi = vsebina.get("os/PrikaziNa.kt", "")
    preveri("lupa in glas: ena razclemba (LupaPravila.razcleni z napravami kroga)",
            "LupaPravila.razcleni(q, napraveKroga(a))" in lupa)
    preveri("PrikaziNa nima vec svoje razclembe (VZORCI) - uporabi LupaPravila", "VZORCI" not in prikazi and "LupaPravila.razcleni(niz)" in prikazi)
    preveri("cilj po imenu samo iz ozjega kroga", re.search(r"fun napraveKroga\(c: Context\).*?OzjiKrog\.vKrogu\(c, it\.id\)", lupa, re.S) is not None)
    preveri("cilj po vrsti samo iz ozjega kroga", "OzjiKrog.vKrogu(ctx, it.id)" in prikazi)
    preveri("vreme na napravo prek Linka (open_url)", 'link.ukaz(n.id, "open_url"' in lupa)
    for skupina in ("pk_lupa_programi", "VRSTE_DATOTEK", "os_meni_nastavitve", "pk_lupa_mediji", "pk_lupa_splet", "pk_lupa_predlogi"):
        preveri(f"zadetki lupe: {skupina}", skupina in pokrajina)
    preveri("sprotni predlogi samo po pravilu (od 3 znakov, ne naslov, stikalo)", "LupaPravila.predlogeIskati(q, spletniPredlogi())" in pokrajina)
    preveri("glasovni vnos (mikrofon) v lupi novega zaganjalnika", "DomacaLupa.prevzemi(this, polje, okvir)" in pokrajina and "fun glas(a: Activity)" in lupa)

    print("\n== beta (del F) ==")
    gradle = (KOREN / "build.gradle").read_text(encoding="utf-8")
    preveri("gradnja -PsafeerBeta=N: pripona -beta.N in BuildConfig.BETA",
            "project.findProperty('safeerBeta')" in gradle and "\"-beta.$beta\"" in gradle and "buildConfigField 'boolean', 'BETA'" in gradle)
    preveri("gradnja zavrne neveljavno stevilko bete", "[1-9][0-9]{0,3}" in gradle and "throw new GradleException" in gradle)
    # CI 40481571137 (Lint NewApi): ValueAnimator.getDurationScale je sele od API 33, minSdk je 28 - na starejsem TV bi
    # se Pokrajina sesula. Skala animacij se bere iz Settings.Global.ANIMATOR_DURATION_SCALE (vse razlicice).
    pokrajina = (KOREN / "src/main/kotlin/si/safeer/tv/os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    preveri("Pokrajina brez API 33 (getDurationScale) pri minSdk 28", "getDurationScale" not in pokrajina)
    # Lastnik 27. 10. 2026: izklop domacega zaslona na TV brez drugega zaganjalnika je pustil crn FallbackHome.
    nast = (KOREN / "src/main/kotlin/si/safeer/tv/os/NastavitveActivity.kt").read_text(encoding="utf-8")
    vrata = (KOREN / "src/main/kotlin/si/safeer/tv/os/VarnostnaVrata.kt").read_text(encoding="utf-8")
    preveri("izklop domacega zaslona brez drugega zaganjalnika zavrnjen (Nastavitve, varnostna vrata)",
            "!Zaganjalnik.smeOpustiti(this)" in nast.split("private fun preklopiZaganjalnik")[1][:600]
            and "!Zaganjalnik.smeOpustiti(c)" in vrata.split("fun vrni(c: Context)")[1][:400])
    preveri("besedilo os_zaganjalnik_edini v 7 mapah", all("name=\"os_zaganjalnik_edini\"" in
            (KOREN / "res" / m / "os_strings.xml").read_text(encoding="utf-8") for m in
            ("values", "values-sl", "values-en", "values-de", "values-es", "values-fr", "values-it")))
    # Rocna gradnja bete v CI: vhod »beta« gre prek okolja v -PsafeerBeta (brez vhoda gradnja kot prej).
    ci = (KOREN / ".github" / "workflows" / "gradnja.yml").read_text(encoding="utf-8")
    preveri("CI: rocna gradnja z vhodom beta poda -PsafeerBeta",
            "inputs:" in ci and "beta:" in ci and "SAFEER_BETA: ${{ github.event.inputs.beta }}" in ci
            and '"-PsafeerBeta=$SAFEER_BETA"' in ci and "${{ github.event.inputs.beta }}\"" not in ci)
    # Brez zastavice ostane navadna razlicica (stevilka se z izdajami spreminja, zato samo oblika brez pripone).
    preveri("brez zastavice ostane ime razlicice kot prej",
            len(re.findall(r"versionName = '[0-9]+\.[0-9]+\.[0-9]+'", gradle)) >= 2)
    nast = vsebina.get("os/NastavitveActivity.kt", "")
    preveri("stikalo »Nov zaganjalnik (beta)« v Nastavitvah, po pravilu vidnosti",
            "ZaganjalnikBetaPravila.stikaloVidno(tv, si.safeer.tv.BuildConfig.BETA, ZaganjalnikBeta.vklopljen(this))" in nast and "R.string.pk_beta" in nast)
    preveri("preklop odpre domaci zaslon znova (stari ali novi)", re.search(r"fun preklopiBeto\(\) \{\s*ZaganjalnikBeta\.nastavi\(this, !ZaganjalnikBeta\.vklopljen\(this\)\)", nast) is not None)
    beta = vsebina.get("os/ZaganjalnikBeta.kt", "")
    print("\n== Usenet ponudnik: vezava (del G) ==")
    glasba = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    okno = (SRC / "os/UsenetPonudnikOkno.kt").read_text(encoding="utf-8") if (SRC / "os/UsenetPonudnikOkno.kt").is_file() else ""
    nntp = (SRC / "os/UsenetNntp.kt").read_text(encoding="utf-8") if (SRC / "os/UsenetNntp.kt").is_file() else ""
    usk2 = (SRC / "os/UskladitevKljucev.kt").read_text(encoding="utf-8")
    preveri("Moji viri › Usenet ponudnik v Medijskem centru", "UsenetPonudnikOkno.pokazi(this)" in glasba)
    preveri("Preveri uporabi shranjenega ponudnika in UsenetNntp z mejo racuna",
            "UsenetNntp.prijava(pp, gg, protokoli, shramba.meje)" in okno and "shramba.zaBazen(p)" in okno)
    preveri("Preveri samo na delovni niti", re.search(r"Thread\(\{\s*val kljuc = try \{\s*shramba\.preveri\(", okno) is not None)
    preveri("UsenetNntp: zasedi pred povezavo, sprosti v finally",
            re.search(r"if \(!meje\.zasedi\(p\)\) return.*?finally \{.*?meje\.sprosti\(p\)", nntp, re.S) is not None)
    preveri("UsenetNntp: TLS z varniParametri", "P.varniParametri(s.sslParameters)" in nntp)
    preveri("UsenetNntp in okno ne pisejo v dnevnik", "Log." not in nntp and "Log." not in okno and "println" not in nntp)
    preveri("geslo se v oknu nikoli ne pokaze (polje prazno ob odprtju)", 'polje(t("POLJE_GESLO"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "")' in okno)
    preveri("ponudnik v ozjem krogu prek secrets.sync (posiljanje, prejem, grob)",
            "P.KLJUC_USENET_PONUDNIK] = UsenetPonudnikPravila.Json.pisi(" in usk2 and
            "UsenetPonudnik.get(c).prejetoIzKroga(pp, g)" in usk2 and "UsenetPonudnik.get(c).pozabi(); true" in usk2)

    preveri("ista shramba in kljuc kot pravila (vrnitev ne brise nicesar)",
            'const val KLJUC = "zaganjalnik_beta"' in beta and ".putBoolean(KLJUC, da).apply()" in beta and ".clear()" not in beta)
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
