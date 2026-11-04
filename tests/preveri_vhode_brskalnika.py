#!/usr/bin/env python3
"""Tedenski pregled 4. 11. 2026 (UXSS): vsak vhod, po katerem pride naslov od zunaj v WebView, gre skozi
SpletVarnostPravila.vhodniNaslov (namera VIEW, dodatek cast_url, spletna_aplikacija, cast.url in share.screen iz
Safeer Linka). Pravilo samo preverja SpletVarnostPravilaTest.kt; ta preverba pazi, da ga klicejo vsi vhodi.
Pade, ce kateri vhod preverbo izgubi (npr. ob preurejanju kode)."""
import pathlib
import re
import sys

KOREN = pathlib.Path(__file__).resolve().parent.parent
TV = KOREN / "src/main/kotlin/si/safeer/tv"
PREVERBA = re.compile(r"varenVhod\(|SpletVarnostPravila\.vhodniNaslov\(")


def telo(besedilo: str, glava: str, vrstic: int) -> str:
    """Prvih nekaj vrstic od glave naprej (preverba mora biti na zacetku, pred nalaganjem)."""
    i = besedilo.find(glava)
    if i < 0:
        return ""
    return "\n".join(besedilo[i:].splitlines()[:vrstic])


def main() -> int:
    napake = []
    pravila = [
        # (datoteka, glava, stevilo vrstic, niz, pred katerim mora biti preverba, ali None)
        ("MainActivity.kt", "private fun incomingBrowseUrl(", 10, None),
        ("MainActivity.kt", "private fun obravnavajCastNamero(", 8, "onCastUrlReceived("),
        ("MainActivity.kt", "override fun onCastUrlReceived(", 4, "loadUrl("),
        ("MainActivity.kt", "override fun onShareScreenStarted(", 3, "loadUrl("),
        ("MainActivity.kt", "internal fun vklopiNacinAplikacije(", 4, "nacinAplikacije = naslov"),
        ("MainActivity.kt", '"si.safeer.tv.ACTION_OPEN_URL" ->', 3, None),
        ("cast/CastReceiverService.kt", "private fun odpriVBrskalniku(", 5, "mobilniNaslov"),
        ("cast/CastReceiverService.kt", '"cast.url" -> {', 12, "mainHandler.post"),
        ("cast/CastReceiverService.kt", '"share.screen" -> {', 16, "mainHandler.post"),
        ("os/LinkOdjemalec.kt", '"cast.url" -> {', 10, "naNaslov("),
    ]
    # Zadnja vrata: obe razlicici ChromiumEngineView.loadUrl zavrneta javascript:/data:.
    cev = (TV / "ChromiumEngineView.kt").read_text(encoding="utf-8")
    for glava in ("override fun loadUrl(url: String) {", "override fun loadUrl(url: String, additionalHttpHeaders"):
        if "SpletVarnostPravila.skriptniNaslov(url)" not in telo(cev, glava, 4):
            napake.append(f"ChromiumEngineView.kt: '{glava}' brez skriptniNaslov")
    for datoteka, glava, vrstic, pred in pravila:
        besedilo = (TV / datoteka).read_text(encoding="utf-8")
        del_ = telo(besedilo, glava, vrstic)
        if not del_:
            napake.append(f"{datoteka}: ni najden '{glava}'")
            continue
        m = PREVERBA.search(del_)
        if not m:
            napake.append(f"{datoteka}: '{glava}' brez SpletVarnostPravila.vhodniNaslov")
        elif pred and pred in del_ and del_.find(pred) < m.start():
            napake.append(f"{datoteka}: '{glava}' preveri naslov sele po '{pred}'")
    main_kt = (TV / "MainActivity.kt").read_text(encoding="utf-8")
    # S1: dodatek spletna_aplikacija gre vedno skozi spletnaIzNamere (oba vhoda), zasciteni vhod ima dovoljenje LINK.
    if main_kt.count("spletnaIzNamere(intent,") != 2:
        napake.append("MainActivity.kt: spletna_aplikacija iz namere brez spletnaIzNamere (onCreate in onNewIntent)")
    if re.search(r"webViewContainer\.post \{ zvokVOzadju = ozadje; vklopiNacinAplikacije\(", main_kt):
        napake.append("MainActivity.kt: spletna_aplikacija odpre cez cel zaslon brez odlocitve")
    man = (KOREN / "okusi/brskalnik/AndroidManifest.xml").read_text(encoding="utf-8")
    m = re.search(r"<activity-alias[^>]*si\.safeer\.tv\.SafeerVhod[^>]*>", man, re.S)
    if not m or 'android:permission="si.safeer.tv.permission.LINK"' not in m.group(0):
        napake.append("okusi/brskalnik: zasciteni vhod SafeerVhod brez dovoljenja LINK")
    if re.search(r"if \(!data\.isNullOrEmpty\(\)\) return data\b", main_kt):
        napake.append("MainActivity.kt: incomingBrowseUrl vrne dataString brez preverbe")
    # S11: obe okni prijave s QR (BROWSABLE safeer://link/qr) opozorita, da mora kodo poskenirati uporabnik sam - 6 jezikov.
    qr = (TV / "link/QrPrijavaActivity.kt").read_text(encoding="utf-8")
    if qr.count("opozoriloKoda()") < 3:
        napake.append("QrPrijavaActivity.kt: okno prijave ali pridruzitve brez opozorila o kodi")
    for jezik in ("sl", "en", "de", "es", "fr", "it"):
        if f'"{jezik}" to "' not in qr:
            napake.append(f"QrPrijavaActivity.kt: opozorilo o kodi brez jezika {jezik}")
    for n in napake:
        print("NAPAKA:", n)
    if napake:
        return 1
    print(f"preveri_vhode_brskalnika: OK ({len(pravila)} vhodov)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
