#!/usr/bin/env python3
"""Zunanji pregled Glasbe 3. 11. 2026: odlocitve iz OsPravilaTest (vrstaZZvokom, zaGlasbo, jeKoncert) morajo klicati pravi
razredi (GlasbaActivity - Android). Brez popravka pade."""
import pathlib, re, sys

OS = pathlib.Path(__file__).resolve().parent.parent / "src/main/kotlin/si/safeer/tv/os"
napak = 0


def preveri(opis, pogoj):
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    napak += 0 if pogoj else 1


t = (OS / "GlasbaActivity.kt").read_text(encoding="utf-8")
t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
g = re.sub(r"(?m)^\s*//.*$", "", t)
preveri("klik na skladbo: vrsta samo z zvokom", "OsPravila.vrstaZZvokom(seznam.size, i," in g)
preveri("klik na skladbo: prednost istemu izvajalcu", "seznam[k].izvajalec.equals(sk.izvajalec, ignoreCase = true)" in g)
preveri("jezik: izjema samo koncert", g.count("OsPravila.jeKoncert(it.video, Stremio.jeEnota(it), it.genres, it.naslov)") == 2)
preveri("priljubljene v Glasbi po zaGlasbo", "skladbe(p.filter { OsPravila.zaGlasbo(" in g and "skladbe(p.filterNot { it.video || it.radio })" not in g)
s = re.search(r"private fun sorodnePolice\(.*?\n    \}\n", g, re.S)
s = s.group(0) if s else ""
preveri("prazna Glasba: brez radijskih postaj", "os_media_sorodno_postaje" not in s)
preveri("prazna Glasba: TV kanali samo v Radiu", "if (radio && zaTv.isNotEmpty())" in s)
preveri("prazna Glasba: nedavne skladbe in seznami", "R.string.os_media_nedavno" in s and "seznamiZaGlasbo()" in s)
preveri("Najnovejse: Jamendo po redJamenda", "order=${OsPravila.redJamenda(najnovejse)}" in (OS / "Jamendo.kt").read_text(encoding="utf-8"))
preveri("Najnovejse: sprememba nalozi vir znova", "!= (nacini[i].first == RAZVRSTI_NAJNOVEJSE)) odpriMrezo(GLASBA)" in g)
preveri("Na tej napravi: skupina tudi na TV", "(if (vlc || glasbaNaNapravi) listOf(SKUPINA_NAPRAVA to true)" in g and "OsPravila.glasbaNaTejNapravi(" in g)
preveri("oznaka video iz besedil (6 jezikov), ne trdo napisana", "\"▶ Video\"" not in g and "R.string.os_media_oznaka_video" in g)
preveri("Glasba: video vrsta nad zvocno mrezo", "policaVidea(videoVsi)" in g and "skladbe(zvocni)" in g)
# Zunanji pregled Video 3. 11. 2026
preveri("mreza filmov: najvec MREZA_HKRATI", "OsPravila.MREZA_HKRATI - o.caka.get()" in g and "minOf(o.proracun, 12 - o.caka.get()" not in g)
preveri("mreza filmov: ne med tipkami in po kliku", "OsPravila.smemPreverjatiMrezo(klikNaFilm, odTipke)" in g)
preveri("klik na film ustavi plakate in preverjanje", re.search(r"private fun razresiStremio\(.*?klikNaFilm = true\s+izprazniVrstoSlik", g, re.S) is not None)
preveri("plakati: onTrimMemory in OOM izpraznita vrsto", "izprazniVrstoSlik(\"onTrimMemory" in g and "izprazniVrstoSlik(\"OutOfMemoryError\")" in g)
preveri("plakati: niti po nitiSlik", "OsPravila.nitiSlik(Naprava.jeTelevizor(this))" in g)
preveri("mreza filmov: brez drugega podatkiRazdelka", "if (!OsPravila.brskanjeVprasaRazdelek(popolne))" in g)
preveri("mreza: ponovno risanje samo ob drugem stevilu stolpcev", "|${razvrstitev(m.razdelek)}|$stolpci\"" in g)
preveri("Wikidata samo za prve kartice", "val vprasaj = OsPravila.zaWikidata(" in g)
preveri("mreza filmov: prvi izris z diska en zaslon", "OsPravila.prviIzrisZDiska(" in g)
preveri("klik na film: najprej dodatek, ki je nasel", "OsPravila.najprejEnDodatek(" in g and "najprej = Stremio.razstavi(sk)?.first" in g)
# Zunanji pregled podnapisov 3. 11. 2026
pa = re.sub(r"(?m)^\s*//.*$", "", re.sub(r"/\*.*?\*/", "", (OS / "PredvajanjeActivity.kt").read_text(encoding="utf-8"), flags=re.S))
preveri("podnapisi: sele po READY 1 s", "OsPravila.podnapisiZdaj(pripravljen, pripravljenOd, zdaj)" in pa)
preveri("podnapisi: samo zadnji dodatek", "OsPravila.dodatkiZaPodnapise(" in pa)
preveri("podnapisi: brez zadetka se ostali dodatki (pregled 4. 11.)", "OsPravila.ostaliZaPodnapise(vsi, naslovi)" in pa)
preveri("podnapisi: med predvajanjem ob premoru", "OsPravila.podnapisZdajAliObPremoru(p.isPlaying)" in pa)
preveri("podnapisi: HLS na gumbu, brez iskanja", "GlasbaStoritev.jeHls()" in pa and "R.string.podnapisi_ne_gre else" in pa)
# Zunanji pregled iskalne vrstice 3. 11. 2026
preveri("iskanje: OK po okIskanja", "OsPravila.okIskanja(q)" in g)
preveri("iskanje: DOL po dolIskanja, brez poizvedbe", "OsPravila.dolIskanja(q," in g and "== OsPravila.TipkaIskanja.ISCI) { if (!prikaziNaDrugi(q)) isci(q) }" not in g)
preveri("iskanje: Nazaj po vidnosti tipkovnice (WindowInsets)", "OsPravila.nazajIskanja(tipkovnicaVidna(polje))" in g and "WindowInsets.Type.ime()" in g)
# Preverba TV 5. 11. 2026 (t0511b)
ss = re.search(r"private fun izberiSezono\(.*?\n    \}\n", g, re.S)
ss = ss.group(0) if ss else ""
preveri("N2 izbrana sezona: robni pas, ne okvir fokusa", ss.count("znakIzbraneSezone()") == 2 and ss.count("setStroke(") == 1
        and "setLayerWidth(0, " in g and "getColor(R.color.os_mint)" in g)
preveri("N9 fokus na zadnji epizodi ob ponovnem odprtju", "EpizodePravila.fokusNaEpizodi(" in ss and "seznamEpizod.requestFocus()" in ss)
pl = re.search(r"private fun osveziPlosco\(.*?\n    \}\n", g, re.S)
pl = pl.group(0) if pl else ""
zd = re.search(r"private fun osveziZdaj\(.*?\n    \}\n", g, re.S)
zd = zd.group(0) if zd else ""
preveri("N12 tik ne nastavlja istega besedila (dogodek dostopnosti vsako sekundo)", pl != "" and zd != ""
        and re.search(r"\?\.text = ", pl) is None and re.search(r"\b(zdajNaslov|zdajIzvajalec|zdajCas)\.text = ", zd) is None
        and "OsPravila.drugoBesedilo(v.text, b)" in g)
pr = (OS.parent / "link/Pretok.kt").read_text(encoding="utf-8")
preveri("P1 velikost toka: zeton samo pripeti napravi, brez preusmeritev", "HubPreusmeritve.zahtevaVelikosti(url, odtis, zeton, glave)" in pr
        and "HubPreusmeritve.brez(si.safeer.tv.os.PripetiVir.odjemalecZaStreznik(odtis).newBuilder())" in pr and "\"X-Safeer-Token\", zeton" not in pr)
# CodeRabbit 5. 11. 2026 (cr-tvseja-0511): neuspel krog Webtorja v diagnostiki zapise vsakega kandidata, ne samo prvega.
preveri("diagnostika Webtor: vsak kandidat kroga", "DiagnostikaPredvajanja.poskusiKroga(this@GlasbaActivity, \"webtor\", kandidati.map { it.url }" in g
        and "kandidati.first().url" not in g)
print("VSE OK" if napak == 0 else f"NAPAK: {napak}")
sys.exit(1 if napak else 0)
