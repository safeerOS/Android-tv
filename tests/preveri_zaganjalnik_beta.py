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
    # Lastnik 27. 10. 2026 (zgled Safeer Control na Linuxu): Safeer Link odpre celoten zaslon »Povezane naprave«.
    naprave_act = (SRC / "os/NapraveActivity.kt").read_text(encoding="utf-8")
    preveri("Safeer Link v vrstici odpre celoten zaslon Povezane naprave",
            "PokrajinaPravila.LINK -> odpri(Intent(this, NapraveActivity::class.java))" in pokrajina and "napraveKroga" not in pokrajina)
    preveri("glava kot na Linuxu: Povezane naprave, + Poveži novo napravo, Osveži (samo z beto)",
            "if (!ZaganjalnikBeta.vklopljen(this)) return" in naprave_act and "R.string.pk_link_povezane" in naprave_act
            and "R.string.pk_link_osvezi" in naprave_act and "PrijavaActivity::class.java" in naprave_act.split("private fun dodajGumbeGlave")[1][:2500])
    preveri("zaslon in upravljanje samo za napravo iz ozjega kroga",
            re.search(r"if \(OzjiKrog\.vKrogu\(this, n\.id\)\) \{.*?NapraveKrogaPravila\.pot\(", naprave_act, re.S) is not None)
    domov = (SRC / "os/DomovActivity.kt").read_text(encoding="utf-8")
    for pot, nap in (("PovezaniZasloni.zaprosi(this, link, r)", "PovezaniZasloni.zaprosi(this, link, n)"),
                     ("Intent(this, ZaslonActivity::class.java)", "Intent(this, ZaslonActivity::class.java)"),
                     ("DatotekeActivity.EXTRA_RACUNALNIK", "DatotekeActivity.EXTRA_RACUNALNIK")):
        preveri(f"ista pot kot kartica Zaslon: {pot}", nap in naprave_act and pot in domov)
    print("\n== aplikacije na domacem zaslonu: ena vrstica (lastnik 27. 10.) ==")
    preveri("ena vodoravna vrstica priljubljenih (brez mreze vseh aplikacij)",
            "PokrajinaPravila.vrstaPriljubljenih(" in pokrajina and "HorizontalScrollView(this)" in pokrajina and "mrezaAplikacij" not in pokrajina)
    preveri("+ Dodaj: aplikacija s TV ali spletna (skupna SpletnaDodaj)",
            "kartica(null, R.drawable.os_ikona_plus, getString(R.string.os_spletne_dodaj)) { dodajAplikacijo() }" in pokrajina
            and "Intent(this, AplikacijeTvActivity::class.java)" in pokrajina and "SpletnaDodaj.izberi(this)" in pokrajina
            and "SpletnaDodaj.izberi(this)" in domov)
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
    # Lastnik 27. 10. 2026 (TV): v Sporocilih se tipkovnica ne odpre sama ob fokusu (pokrila je pol zaslona), samo na OK.
    spor = (SRC / "os/SporocilaActivity.kt").read_text(encoding="utf-8")
    preveri("Sporocila: tipkovnica na TV sele na OK (Odgovor in Isci)",
            "tipkovnicaSeleNaOk(odgovor)" in spor and "tipkovnicaSeleNaOk(iskanje)" in spor
            and "e.showSoftInputOnFocus = false" in spor and "FEATURE_LEANBACK" in spor.split("private fun tipkovnicaSeleNaOk")[1][:300])
    stranska = (SRC / "os/StranskaVrstica.kt").read_text(encoding="utf-8")
    preveri("z beto na TV zasloni brez stranske vrstice",
            "private val brezVrstice = !ZaganjalnikBetaPravila.stranskaVrstica(!dotik, ZaganjalnikBeta.vklopljen(dejavnost))" in stranska)
    # Beta 4 na TV: vrstica se je se videla - prilagodiSirino je vidnost ob vsaki postavitvi nastavil znova. Odlocitev mora
    # biti prva veja v prilagodiSirino in nikjer drugje se vrstica ne sme pokazati mimo nje.
    prilagodi = stranska.split("fun prilagodiSirino()")[1].split("\n    fun ")[0]
    preveri("brez vrstice: prva veja v prilagodiSirino (ne samo ob gradnji)",
            re.search(r"when \{\s*brezVrstice -> \{ drsnik\.visibility = View\.GONE", prilagodi) is not None)
    preveri("vrstico pokaze samo prilagodiSirino", stranska.count("drsnik.visibility = View.VISIBLE") == prilagodi.count("drsnik.visibility = View.VISIBLE"))
    preveri("Sporocila: med pisanjem odgovora z odprto tipkovnico glava izgine",
            "val pisem = odgovor.hasFocus() && ((tv && odgovor.showSoftInputOnFocus) ||" in spor)
    preveri("Sporocila: OK na polju Odgovor vklopi nacin pisanja, Nazaj ga zapusti",
            "e.post { uskladiPisanje() }" in spor and "KeyEvent.KEYCODE_BACK && (v as EditText).showSoftInputOnFocus" in spor)
    preveri("Sporocila: med pisanjem vidno zadnje sporocilo, dejanja pogovora skrita",
            "listOfNotNull(dejanjaPogovora.parent as? View)" in spor and "sporocilaDrsnik.scrollTo(0, (sporocilaSeznam.height" in spor)
    # Lastnik 27. 10. 2026: tudi Nastavitve in vsi drugi zasloni z beto na TV brez stranske vrstice - vsi gredo skozi
    # StranskaVrstica.ovij (tam se vrstica skrije); zaslon s svojo vrstico mimo nje bi to obsel.
    for z in ("os/NastavitveActivity.kt", "os/DovoljenjaActivity.kt", "os/SporocilaActivity.kt", "os/DatotekeActivity.kt",
              "os/NapraveActivity.kt", "os/AplikacijeHostaActivity.kt", "os/ZapiskiActivity.kt", "os/GlasbaActivity.kt",
              "MainActivity.kt"):
        preveri(f"{z}: stranska vrstica samo prek StranskaVrstica.ovij", "StranskaVrstica.ovij(" in (SRC / z).read_text(encoding="utf-8"))
    preveri("stranska vrstica nastane samo v StranskaVrstica (zasebni konstruktor)", "class StranskaVrstica private constructor(" in stranska)
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
    lastni = (SRC / "os/UsenetLastni.kt").read_text(encoding="utf-8") if (SRC / "os/UsenetLastni.kt").is_file() else ""
    usenet = (SRC / "os/Usenet.kt").read_text(encoding="utf-8")
    glasba = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    preveri("Preveri: shranjen ponudnik, odjemalec Grok (UsenetNntp) z mejo racuna",
            "UsenetLastni.prijava(a, pp, gg, shramba.meje)" in okno and "shramba.zaBazen(p)" in okno)
    preveri("prijava in film: zasedi pred povezavo, sprosti v finally / ob koncu",
            re.search(r"if \(!meje\.zasedi\(p\)\).*?finally \{\s*meje\.sprosti\(p\)", lastni, re.S) is not None
            and "s.meje.sprosti(s.ponudnik)" in lastni)
    preveri("film: en naenkrat (ustavi pred novim), Pozabi ustavi film", "ustavi()   // en film naenkrat" in lastni
            and "UsenetLastni.ustavi(); shramba.pozabi()" in okno)
    preveri("geslo ne gre v dnevnik", not re.search(r"Log\.[a-z]\([^)]*(geslo|vrednost)", lastni))
    print("\n== Usenet v Medijskem centru: lasten pogon (lastnik 27. 10.) ==")
    preveri("Usenet.tok: z vpisanim ponudnikom lasten pogon (NZB od indeksatorja -> UsenetLastni.predvajaj)",
            "if (UsenetLastniPravila.lastni(lastni, UsenetLastni.naVoljo(c))) return tokLastni(c, idToka, ime)" in usenet
            and "return UsenetLastni.predvajaj(c, nzb, ime)" in usenet)
    preveri("Usenet v Medijskem centru tudi brez TorBoxa (lasten ponudnik)", "lastni = UsenetLastni.naVoljo(c))" in usenet)
    preveri("samodejna izbira: z lastnim ponudnikom Usenet sam, zaporedno, brez okna vira",
            "if (UsenetLastni.naVoljo(app)) {" in glasba and "lastni = true) }" in glasba)
    preveri("vzporedna preverba TorBoxa ne odpre lastnega pogona", "t.datoteka, true, {}, lastni = false)" in glasba)
    preveri("film gre v obstojeci predvajalnik kot navaden naslov (127.0.0.1, zeton)",
            "TorBox.Izid(url = streznik.url, ponudnik = UsenetLastniPravila.PONUDNIK)" in lastni)
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
