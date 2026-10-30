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
            "PokrajinaPravila.vrstaDomov(" in pokrajina and "HorizontalScrollView(this)" in pokrajina and "mrezaAplikacij" not in pokrajina)
    # Lastnik 28. 10. 2026: odstrani iz priljubljenih in vrstni red (namescene in spletne skupaj), dolg pritisk na plosci.
    preveri("dolg pritisk: premakni, levo, desno, odstrani iz priljubljenih",
            "setOnLongClickListener { moznostiPloscice(id" in pokrajina and "R.string.os_priljubljen_odstrani" in pokrajina
            and "Priljubljene.shraniRedDomov(" in pokrajina and "PokrajinaPravila.tipkaPremikanja(" in pokrajina)
    preveri("po zgledu tv-simple-home: mirovanje in fokus na zadnji aplikaciji",
            "PokrajinaPravila.mirujePo(" in pokrajina and "zadnjaPloscica = id" in pokrajina)
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
    preveri("lupa novega zaganjalnika (DomacaLupa.prevzemi)", "DomacaLupa.prevzemi(this, polje, okvir" in pokrajina and "fun glas(a: Activity)" in lupa)

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
            "val pisem = !tv && odgovor.hasFocus() && SporocilaHitroPravila.tipkovnicaOdprta(" in spor)
    print("\n== Sporocila: pisanje odgovora na TV po skici (lastnik 27. 10.) ==")
    preveri("OK na polju Odgovor odpre pas pisanja, nato tipkovnico", "if (e === odgovor && packageManager.hasSystemFeature(" in spor
            and re.search(r"vstopiVPisanje\(\)\s*e\.post \{\s*e\.requestFocus\(\)", spor) is not None)
    preveri("Nazaj zapusti pas pisanja (polje in zaslon)", "if (pisanjeTece) { izstopiIzPisanja(); return }" in spor
            and "KeyEvent.KEYCODE_BACK && pisanjeTece" in spor)
    preveri("kanali osebe ob strani, odgovor gre po izbranem", "(listOf(p) + pogovoriOsebe(p)).distinctBy" in spor and "izbran = q" in spor)
    preveri("oblacki zadnjih sporocil, zadnje poudarjeno", "shramba.sporocila(p.kanalId, p.id).takeLast(4)" in spor and "poudarjeno = jeZadnje" in spor)
    preveri("vrstica Odgovor + Poslji se prestavi (ni podvojenega polja)", "pisanjeVnos.addView(vnosVrstica" in spor and spor.count("odgovor = EditText(") == 1)
    preveri("Sporocila: med pisanjem vidno zadnje sporocilo, dejanja pogovora skrita",
            "listOfNotNull(dejanjaPogovora.parent as? View)" in spor and "sporocilaDrsnik.scrollTo(0, (sporocilaSeznam.height" in spor)
    # Lastnik 27. 10. 2026: tudi Nastavitve in vsi drugi zasloni z beto na TV brez stranske vrstice - vsi gredo skozi
    # StranskaVrstica.ovij (tam se vrstica skrije); zaslon s svojo vrstico mimo nje bi to obsel.
    for z in ("os/NastavitveActivity.kt", "os/DovoljenjaActivity.kt", "os/SporocilaActivity.kt", "os/DatotekeActivity.kt",
              "os/NapraveActivity.kt", "os/AplikacijeHostaActivity.kt", "os/ZapiskiActivity.kt", "os/GlasbaActivity.kt",
              "MainActivity.kt"):
        preveri(f"{z}: stranska vrstica samo prek StranskaVrstica.ovij", "StranskaVrstica.ovij(" in (SRC / z).read_text(encoding="utf-8"))
    preveri("stranska vrstica nastane samo v StranskaVrstica (zasebni konstruktor)", "class StranskaVrstica private constructor(" in stranska)
    print("\n== Moji viri (lastnik 27. 10.) ==")
    viri_glasba = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8").split("private fun viriVrste()")[1].split("\n    private fun ")[0]
    preveri("Moji viri: brez seznamov predvajanja (so pri glasbi)", "MedijskiViri.seznami(" not in viri_glasba)
    preveri("Moji viri: ploscica Add a Usenet provider odpre okno ponudnika",
            "R.string.os_usenet_dodaj_ponudnika" in viri_glasba and "UsenetPonudnikOkno.pokazi(this)" in viri_glasba)
    preveri("besedili ploscice v 7 mapah", all((KOREN / "res" / m / "usenet_ponudnik_strings.xml").is_file() for m in
            ("values", "values-sl", "values-en", "values-de", "values-es", "values-fr", "values-it")))
    preveri("domaci zaslon brez oznake Safeer OS levo zgoraj", "R.string.os_app_name" not in pokrajina.split("private fun glava()")[1].split("\n    private fun ")[0])
    preveri("pas pisanja: oblacek najvec sirina pasu, zadnje sporocilo vidno v celoti",
            "if (it.maxWidth > najvec) it.maxWidth = najvec" in spor and "pas.scrollTo(0, (zadnji.bottom - pas.height).coerceAtLeast(0))" in spor
            and "scrollTo(pisanjeOblacki.width, 0)" not in spor)
    preveri("pas pisanja: Enter na tipkovnici TV poslje (ni vec ujet v tipkovnici)",
            "akcija == EditorInfo.IME_ACTION_SEND) { poslji(); true }" in spor and "odgovor.setRawInputType(InputType.TYPE_CLASS_TEXT" in spor)
    print("\n== dolg pritisk gor/dol: orodna vrstica v vseh aplikacijah (lastnik 27. 10.) ==")
    vnos = (SRC / "link/VnosStoritev.kt").read_text(encoding="utf-8")
    preveri("Safeer Vnos: dolg pritisk skoci na vrstico (VnosVklopPravila.vrstica), kratek gre naprej",
            "override fun onKeyEvent(" in vnos and "VnosVklopPravila.vrstica(" in vnos and "if (event.repeatCount == 0) return false" in vnos)
    preveri("samo tipki gor/dol in samo TV", "if (k != android.view.KeyEvent.KEYCODE_DPAD_DOWN && k != android.view.KeyEvent.KEYCODE_DPAD_UP) return false" in vnos
            and "if (!jeTelevizor()) return false" in vnos)
    for x in ("os", "dotik"):
        xml = (KOREN / "okusi" / x / "res/xml/safeer_vnos.xml").read_text(encoding="utf-8")
        preveri(f"safeer_vnos.xml ({x}): filter tipk", 'android:canRequestFilterKeyEvents="true"' in xml and "flagRequestFilterKeyEvents" in xml)
    print("\n== glasba z Useneta (lastnik 27. 10.) ==")
    gl = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    us = (SRC / "os/Usenet.kt").read_text(encoding="utf-8")
    la = (SRC / "os/UsenetLastni.kt").read_text(encoding="utf-8")
    preveri("iskanje glasbe pri indeksatorju (izvajalec in skladba) med zadetki", "Usenet.isciGlasbo(applicationContext, beseda)" in gl
            and "UsenetGlasbaPravila.naslovIskanjaSkladbe(" in us and "rezultati[14] as? List<UsenetPravila.Zadetek>" in gl)
    preveri("samo z lastnim ponudnikom in indeksatorjem", "fun glasbaNaVoljo(c: Context): Boolean = zaUporabo(c) != null && UsenetLastni.naVoljo(c)" in us)
    preveri("klik predvaja album v obstojecem predvajalniku, pri iskani skladbi", "predvajaj(seznam, UsenetGlasbaPravila.zacetnaSkladba(" in gl)
    preveri("album: ena seja in en bazen, Pozabi zapre vse skladbe", "s.strezniki.forEach { try { it.close() }" in la and "fun predvajajAlbum(" in la)
    okno_up = (SRC / "os/UsenetPonudnikOkno.kt").read_text(encoding="utf-8")
    preveri("okno ponudnika: po uspesni preverbi Zapri s fokusom", re.search(r"if \(kljuc == P\.VELJA\) \{\s*shrani\.text = t\(\"GUMB_ZAPRI\"\)", okno_up) is not None)
    gl2 = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    preveri("filter Torrenti prek: tudi Usenet (lasten ponudnik), izbira debrida ga izklopi",
            'DebridPravila.OZNAKA_PREDPOMNJEN + " Usenet"' in gl2 and "i == iVse -> { UsenetLastni.nastaviIzbran(this, true)" in gl2
            and "Debrid.nastaviAktivno(this, sKljucem[i]); UsenetLastni.nastaviIzbran(this, false)" in gl2)
    sp2 = (SRC / "os/SporocilaActivity.kt").read_text(encoding="utf-8")
    preveri("pas pisanja: en fokus (poudarek brez obrobe v barvi fokusa)",
            "if (poudarjeno) plosca(R.color.os_kartica_obroba, 1," in sp2 and "if (poudarjeno) plosca(R.color.os_mint" not in sp2)
    preveri("pas pisanja: pogovor navpicno, tvoje sporocilo desno pod prejetim",
            "pisanjeOblacki = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }" in sp2
            and 'gravity = if (sp.smer == "ven") android.view.Gravity.END else android.view.Gravity.START' in sp2
            and "HorizontalScrollView(this@SporocilaActivity).apply { isHorizontalScrollBarEnabled = false; addView(pisanjeOblacki)" not in sp2)
    pk = (SRC / "os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    nazaj = pk.split("override fun onBackPressed() {", 1)[1].split(chr(10) + "    }", 1)[0]
    preveri("lupa: Nazaj jo najprej zapre (prej je DomacaLupa.nazaj brez krmila vrnil false)",
            nazaj.strip().startswith("if (zapriLupo()) return"))
    nov = pk.split("override fun onNewIntent(intent: Intent?) {", 1)[1].split(chr(10) + "    }", 1)[0]
    preveri("lupa: Domov jo zapre in ne aktivira znova", "zapriLupo()" in nov and "requestFocus()" not in nov)
    preveri("lupa: odhod z domacega zaslona jo pocisti (onStop)", "zapriLupo()" in pk.split("override fun onStop() {", 1)[1].split(chr(10) + "    }", 1)[0])
    gl3 = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    preveri("Usenet: obvestilo in dnevnik po ponudniku (lasten pogon ni TorBox)",
            "UsenetLastniPravila.dnevnikPripravljenega(ponudnik)" in gl3 and "napisPredvajaPrek(ponudnik)" in gl3
            and "Usenet pripravljen pri TorBoxu; izbran" not in gl3
            and "Triple(t, it, izid.ponudnik.ifBlank { UsenetLastniPravila.PONUDNIK })" in gl3)
    gl4 = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    zp = gl4.split("private fun zacniPripravo(", 1)[1].split(chr(10) + "    }", 1)[0]
    preveri("priprava: viden pas Pripravljam od klika (ne kratek Toast)",
            "pokaziPripravljam(getString(R.string.os_media_pripravljam, sk.naslov))" in zp and "Toast" not in zp)
    preveri("priprava: lasten Usenet ni »prek debrid storitve«",
            "Toast.makeText(this, R.string.os_debrid_pripravljam" not in gl4 and "R.string.os_usenet_pripravljam" in gl4
            and "skrijPripravljam()      // predvajalnik je odprt" in gl4)
    md = (SRC / "os/MiniPredvajalnik.kt").read_text(encoding="utf-8")
    pk2 = (SRC / "os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    preveri("dock: na Pokrajini nad spodnjo vrstico (odmik ob postavitvi vrstice)",
            "dock?.nastaviRob(koren.height - dok.top + dp(8))" in pk2 and "dok.addOnLayoutChangeListener" in pk2
            and "k.addView(c, odmakni(lp, m))" in md)
    preveri("dock: Predvajaj pri filmu odpre celozaslonski predvajalnik (ne samo zvok)",
            "MiniPredvajalnikPravila.dejanjePredvajaj(videoTu(), igraZdaj)" in md and "\"dock:predvajaj\") { GlasbaStoritev.preklopi() }" not in md)
    preveri("dock: Nazaj ga skrije na Pokrajini", "dock?.skrijCeOdprt() == true" in pk2)
    pk3 = (SRC / "os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    na3 = (SRC / "os/NastavitveActivity.kt").read_text(encoding="utf-8")
    preveri("vreme: klik na uro ne odpre izbire kraja (lastnik 27. 10.)", "izberiKraj" not in pk3 and "VremeKraj.izberi" not in pk3)
    preveri("ura: brez okvirja in ni gumb", "isFocusable = false; isClickable = false" in pk3 and "background = steklo(28)" not in pk3)
    preveri("vreme: kraj se izbere v Nastavitvah novega zaganjalnika", "VremeKraj.izberi(this) { narisi() }" in na3)
    preveri("vreme: osvezi se med prikazom (tik ure, najvec na 30 min)",
            "osveziVreme()" in pk3.split("private val tikUre = object : Runnable {", 1)[1].split(chr(10) + "    }", 1)[0])
    te = (SRC / "ThreatBlockEngine.kt").read_text(encoding="utf-8")
    ce = (SRC / "ChromiumEngineView.kt").read_text(encoding="utf-8")
    preveri("OOM: seznam groznj se uredi v niti posodobitve, ne ob prvem iskanju",
            "newTrie.pripravi()" + chr(10) + "        threatTrie = newTrie" in te)
    preveri("OOM: zanka pojavnih oken ne ustvarja pogledov (PojavnaOknaMeja)",
            ce.index("PojavnaOknaMeja.dovoli(pojavnaOknaCasi") < ce.index("return naPojavnoOkno?.invoke(resultMsg)"))
    gl5 = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    preveri("mini predvajalnik tudi v Medijskem centru (zivljenjski cikel in Nazaj)",
            "dock = MiniPredvajalnik.naOknu(this)" in gl5 and "GlasbaStoritev.poslusalci.remove(dockPoslusalec)" in gl5
            and "if (dock?.skrijCeOdprt() == true) return" in gl5)
    gl6 = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    preveri("album z Useneta: lepi naslovi in izvajalec (UsenetGlasbaPravila.lepeSkladbe)",
            "UsenetGlasbaPravila.lepeSkladbe(album.skladbe.map { it.first }" in gl6)
    ul = (SRC / "os/UsenetLastni.kt").read_text(encoding="utf-8")
    preveri("album z Useneta: strezniki skladb ne potecejo po 5 min",
            "nedejavnostMs = UsenetLastniPravila.NEDEJAVNOST_ALBUMA_MS" in ul.split("fun predvajajAlbum(", 1)[1].split(chr(10) + "    }", 1)[0])
    ul2 = (SRC / "os/UsenetLastni.kt").read_text(encoding="utf-8")
    preveri("album z Useneta: en streznik, skladbe sproti (najvec 5 odprtih)",
            "AlbumIzvor(viri, UsenetUsb.ovij(c, nntp::body), profil)" in ul2 and "for ((ime, vir) in viri)" not in ul2)
    preveri("Usenet na USB: film in album gresta prek UsenetUsb.ovij (lastnik 28. 10.)",
            "val telo = UsenetUsb.ovij(c, nntp::body)" in ul2 and "UsenetBralnik.Bralnik(telo, vir, profil)" in ul2)
    gl7 = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    ua = (SRC / "os/UsenetAlbumi.kt").read_text(encoding="utf-8")
    preveri("priljubljeni albumi z Useneta: dolg pritisk in vrstica, brez povezave NZB v shrambi",
            "dolgo = { preklopiUsenetAlbum(z.naslov, z.velikost) }" in gl7 and "R.string.os_usenet_albumi" in gl7
            and "urediUsenetAlbum(a)" in gl7 and "fun preimenuj(" in ua
            and "povezava" not in ua.split("object UsenetAlbumi", 1)[1].replace("povezava NZB", ""))
    gs = (SRC / "os/GlasbaStoritev.kt").read_text(encoding="utf-8")
    pa = (SRC / "os/PredvajanjeActivity.kt").read_text(encoding="utf-8")
    preveri("predvajalnik: stikali samodejno naslednja skladba in album (privzeto vklopljeni)",
            "MiniPredvajalnikPravila.poKoncuSkladbe(samodejnoNaslednja(this), samodejnoAlbum(this)" in gs
            and "p.pauseAtEndOfMediaItems = !samodejnoNaslednja(this)" in gs and "R.string.os_samodejno_album" in pa)
    us = (SRC / "os/UsenetStreznik.kt").read_text(encoding="utf-8")
    preveri("album: naslednja skladba pripravljena vnaprej (ni preskakovanja ob prvi zahtevi)",
            "pripraviVnaprej(i + 1)" in us and "izvor.pripraviVnaprej(0)" in (SRC / "os/UsenetLastni.kt").read_text(encoding="utf-8"))
    pk4 = (SRC / "os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    preveri("domaci zaslon: vrstica aplikacij pod lupo (prostor pod njo, mini predvajalnik je ne prekriva)",
            pk4.index("vsebina.addView(drsnik,") < pk4.index("vsebina.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))") < pk4.index("vsebina.addView(dok,"))
    gk = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    uk = (SRC / "os/Usenet.kt").read_text(encoding="utf-8")
    preveri("kvota: NZB na napravi, neuspeli skriti, druga izdaja samo pri luknji",
            "albumIzShrambe(c, naslov, velikost)?.let { return it }" in uk and "UsenetNzbShramba.jeNeuspel(applicationContext, it.naslov, it.velikost)" in gk
            and "UsenetLastniPravila.poskusiDrugoIzdajo(album.napaka) && poskus < UsenetLastniPravila.NAJVEC_DRUGIH_IZDAJ" in gk
            and "Usenet.albumIzShrambe(app, a.naslov, a.velikost)" in gk)
    tm = (SRC / "TabManager.kt").read_text(encoding="utf-8")
    preveri("Splet: nov zavihek dobi fokus (daljinec ne obtici na orodni vrstici)",
            "if (fokusPrej !is android.widget.EditText) try { ciljniPogled.requestFocus() }" in tm.split("fun switchTab(", 1)[1].split(chr(10) + "    }", 1)[0])
    gz = (SRC / "os/GlasbaStoritev.kt").read_text(encoding="utf-8")
    preveri("predvajalnik: pokvarjen zvocni okvir (AC3 pri predaji zvocniku) ne ustavi videa",
            "if (napakaZvocnegaOkvirja(error)) {" in gz and "p.seekTo(p.currentPosition + 1_000); p.prepare(); p.play()" in gz
            and gz.index("if (napakaZvocnegaOkvirja(error)) {") < gz.index("val naprej = YoutubeVrstaPravila.naprejPoNapaki("))
    ul3 = (SRC / "os/UsenetLastni.kt").read_text(encoding="utf-8")
    album3 = ul3.split("fun predvajajAlbum(", 1)[1].split(chr(10) + "    }", 1)[0]
    preveri("album: nov album ne zapre albuma, ki igra, dokler ni pripravljen (isti bazen)",
            "val nntp = stara?.nntp ?:" in album3 and album3.index("if (stara == null) {") < album3.index("ustavi()")
            and "if (!s.lastnikBazena) return" in ul3 and album3.index("koncaj(stara)") < album3.index("synchronized(zaklep) { seja = s }"))
    preveri("Usenet: kartica kaze album, leto in obliko (ne odrezanega imena izdaje)",
            "UsenetGlasbaPravila.kartica(z.naslov, z.velikost)" in (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
            and "listOf(izvajalecPod, k.podnaslov)" in (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8"))
    pv = (SRC / "os/PredvajanjeActivity.kt").read_text(encoding="utf-8")
    preveri("YouTube igra v ozadju po Domov (ustavi ga mini predvajalnik); drug video se ustavi",
            "if (jeVideo() && !igraVOzadju() && !isChangingConfigurations && !novoPredvajanje) GlasbaStoritev.predvajalnik?.pause()" in pv
            and "YoutubeZvok.jeYoutube(it)" in pv)
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
            "if (UsenetLastniPravila.lastni(lastni, UsenetLastni.vUporabi(c))) return tokLastni(c, idToka, ime)" in usenet
            and "else UsenetLastni.predvajaj(c, nzb, ime)" in usenet)
    preveri("Usenet v Medijskem centru tudi brez TorBoxa (lasten ponudnik)", "lastni = UsenetLastni.vUporabi(c))" in usenet)
    preveri("samodejna izbira: z lastnim ponudnikom Usenet sam, zaporedno, brez okna vira",
            "if (UsenetLastni.vUporabi(app)) {" in glasba and "lastni = true) }" in glasba)
    preveri("vzporedna preverba TorBoxa ne odpre lastnega pogona", "t.datoteka, true, {}, lastni = false)" in glasba)
    preveri("film gre v obstojeci predvajalnik kot navaden naslov (127.0.0.1, zeton)",
            "TorBox.Izid(url = streznik.url, ponudnik = UsenetLastniPravila.PONUDNIK)" in lastni)
    preveri("geslo se v oknu nikoli ne pokaze (polje prazno ob odprtju)", 'polje(t("POLJE_GESLO"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "")' in okno)
    preveri("ponudnik v ozjem krogu prek secrets.sync (posiljanje, prejem, grob)",
            "P.KLJUC_USENET_PONUDNIK] = UsenetPonudnikPravila.Json.pisi(" in usk2 and
            "UsenetPonudnik.get(c).prejetoIzKroga(pp, g)" in usk2 and "UsenetPonudnik.get(c).pozabi(); true" in usk2)

    preveri("ista shramba in kljuc kot pravila (vrnitev ne brise nicesar)",
            'const val KLJUC = "zaganjalnik_beta"' in beta and ".putBoolean(KLJUC, da).apply()" in beta and ".clear()" not in beta)

    # Pregled 28. 10. 2026: dovoljenje istega podpisa napove vsak okus (skupni manifest), ne samo brskalnik.
    skupni = (KOREN / "AndroidManifest.xml").read_text(encoding="utf-8")
    preveri("dovoljenje LINK napovedano v skupnem manifestu z ravnjo signature",
            re.search(r'<permission\s+android:name="si\.safeer\.tv\.permission\.LINK"\s+android:protectionLevel="signature"\s*/>', skupni) is not None)
    for okus in sorted((KOREN / "okusi").glob("*/AndroidManifest.xml")):
        besedilo = okus.read_text(encoding="utf-8")
        preveri(f"okus {okus.parent.name} ne napove LINK z drugo ravnjo",
                re.search(r'<permission\s+android:name="si\.safeer\.tv\.permission\.LINK"', besedilo) is None)
    # Dnevnik TV 28. 10. 2026 08:51: ikone programov se dekodirajo v ozadju, ne na glavni niti.
    ah = (SRC / "os/AplikacijeHostaActivity.kt").read_text(encoding="utf-8")
    preveri("Aplikacije: ikone naprav v ozadju (ne na glavni niti)",
            "objavi(r.id, pretvori(" not in ah and "val seznam = pretvori(id, sur)" not in ah and "pretvorbe.execute {" in ah)
    # Lastnik 28. 10. 2026: v Povezanih napravah ob dostopu naprave vrstica »Glej zaslon in upravljaj«.
    preveri("Povezane naprave: ob dostopu vrstica Glej zaslon in upravljaj (brez dostopa odpre dostop)",
            "R.string.pk_link_zaslon_najprej_dostop" in naprave_act and "!vKrogu -> dostopNaprave(n)" in naprave_act
            and "PovezaniZasloni.zaprosi(this, link, n)" in naprave_act)
    # Preverba TV 28. 10. 2026: brez stranske vrstice Nazaj zapre zaslon v enem pritisku, fokus ne gre v nevidni meni.
    sv = (SRC / "os/StranskaVrstica.kt").read_text(encoding="utf-8")
    preveri("skrita stranska vrstica: Nazaj in smerne tipke ne vodijo v nevidni meni",
            "if (brezVrstice || dotik || spodnja || imaFokus()) return false" in sv and "        if (brezVrstice) return false\n" in sv)
    pk2 = (SRC / "os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    preveri("Domov ne odpre tipkovnice (showSoftInputOnFocus vedno nazaj na false)",
            "        poljeLupe.showSoftInputOnFocus = false\n        val vLupi" in pk2 and "override fun onWindowFocusChanged(imaFokus: Boolean)" in pk2
            and re.search(r'PokrajinaActivity"\s+android:exported="false"\s+android:windowSoftInputMode="stateAlwaysHidden', manifest) is not None)
    # Lastnik 28. 10. 2026: mreza Filmi | Serije pokaze vse naslove takoj (brez cakanja na vljudno preverjanje).
    gl = (SRC / "os/GlasbaActivity.kt").read_text(encoding="utf-8")
    preveri("mreza filmov ni stroga (vsi naslovi takoj)", "private fun strogaMreza(o: OdprtKatalog): Boolean = false" in gl
            and "if (!strogaMreza(o)) o.vec(0)" in gl)
    # TV 28. 10. 2026: tok Useneta, ki odpove pred zacetkom, ni koncna napaka - izbira gre naprej brez Useneta.
    st = (SRC / "os/GlasbaStoritev.kt").read_text(encoding="utf-8")
    preveri("Usenet brez zacetka: druga pot izbire toka", "if (druga != null && !zacelo)" in st and "obOdpovedi = null\n" in st
            and "GlasbaStoritev.obOdpovedi = {" in gl and "vsiTokovi.filter { it.vrsta != UsenetPravila.VRSTA }" in gl)
    # Lastnik 29. 10. 2026: plakati iskanja ne cakajo za plakati mreze (LIFO, zastarele zahteve izpadejo).
    preveri("plakati: zadnja zahteva prva, zastarela izpade", "override fun offer(e: Runnable): Boolean = offerFirst(e)" in gl
            and "if (!zeliSliko(naslov)) { preskoceno = true; return@execute }" in gl)
    # Lastnik 29. 10. 2026: fokus ne sme uiti iz prikazanih polj (skrit meni, nevidni pogledi).
    sv2 = (SRC / "os/StranskaVrstica.kt").read_text(encoding="utf-8")
    preveri("fokus: skrit meni ne dobi fokusa, varovalo na zaslonu", "if (brezVrstice || !drsnik.isShown) { fokusVsebine(); return }" in sv2
            and "if (drsnik.visibility != View.VISIBLE && drsnik.hasFocus()) post { fokusVsebine() }" in sv2
            and "window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { stari, nov -> varujFokus(stari, nov) }" in gl)
    # Lastnik 30. 10. 2026: odstranjevanje aplikacij iz Safeer OS potrebuje dovoljenje REQUEST_DELETE_PACKAGES.
    glavni = (KOREN / "AndroidManifest.xml").read_text(encoding="utf-8")
    preveri("odstranjevanje aplikacij: REQUEST_DELETE_PACKAGES",
            'android:name="android.permission.REQUEST_DELETE_PACKAGES"' in glavni)
    # Zaganjalnik (TV, telefon, tablica) mora vedno imeti vecjo mejo pomnilnika.
    for okus in ("os", "telefon", "tablica"):
        man = (KOREN / "okusi" / okus / "AndroidManifest.xml").read_text(encoding="utf-8")
        preveri(f"{okus}: android:largeHeap=\"true\"",
                re.search(r'android:largeHeap="true"\s+tools:replace="android:largeHeap"', man) is not None
                and 'largeHeap="false"' not in man)

    # Lastnik 29. 10. 2026: tipka Menu na daljincu odpre možnosti nastavitev televizorja
    pok = (SRC / "os/PokrajinaActivity.kt").read_text(encoding="utf-8")
    prav = (SRC / "os/PokrajinaPravila.kt").read_text(encoding="utf-8")
    preveri("tipka menija: PokrajinaPravila.jeTipkaMenija", "fun jeTipkaMenija(koda: Int): Boolean" in prav and "82," in prav)
    preveri("tipka menija: PokrajinaActivity odpre nastavitve TV", "PokrajinaPravila.jeTipkaMenija(dogodek.keyCode)" in pok
            and "odpriNastavitveTv()" in pok and "QuickSettingsActivity" in pok)

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
