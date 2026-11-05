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

sc = beri("src/main/kotlin/si/safeer/tv/scit/ScitActivity.kt")
preveri("Scit: OK na ustavljenem imenu vprasa, ne spusti takoj", "({ spusti(z.ime) })" not in sc and "potrdiSpust(z.ime)" in sc and "R.style.Os_Okno" in sc[sc.find("fun potrdiSpust"):])
for jez in ("", "-sl", "-en", "-de", "-es", "-fr", "-it"):
    s = beri("res/values%s/scit_zaslon_strings.xml" % jez)
    preveri("Scit: vprasanje o spustu v values%s" % jez, all('name="%s"' % k in s for k in ("scit_z_spusti_vprasanje", "scit_z_spusti_da", "scit_z_spusti_ne")))

vn = beri("src/main/kotlin/si/safeer/tv/link/VnosStoritev.kt")
preveri("Vnos: okvir v WebViewu skrit, ce so domaci elementi", "jeVSpletnem(vz) &&" in vn and "VnosVklopPravila.skrijOkvirVSpletnem(true," in vn)
preveri("Vnos: okvir po umiritvi mej", "VnosVklopPravila.okvirPoMeritvi(" in vn and "narisanOkvir = zdajOkvir" in vn)

# Lastnik 4. 11. 2026 23:35: vsaka priprava (zacniPripravo) se mora na svoji poti tudi koncati (koncajPripravo z istim
# kljucem) - sicer kljuc ostane v pripraveVTeKu in naslednji klik na isto vsebino ne naredi nicesar.
for m in re.finditer(r"val (\w+) = (?:\w+ \?: )?zacniPripravo\(", g):
    ime = m.group(1)
    konec = min([i for i in (g.find("\n    private fun ", m.end()), g.find("\n    fun ", m.end())) if i > 0] or [len(g)])
    vrstica = g.count("\n", 0, m.start()) + 1
    preveri("Media: priprava '%s' (vrstica %d) se konca na svoji poti" % (ime, vrstica), ("koncajPripravo(%s)" % ime) in g[m.end():konec])

pk = beri("src/main/kotlin/si/safeer/tv/os/PokrajinaActivity.kt")
dok = pk[pk.find("private fun narisiDok"):pk.find("private fun osveziDokZnacke")]
preveri("Dom: napis spodnje vrstice v eni vrstici, mesto rezervirano (brez skoka)", "maxLines = 1" in dok and "View.GONE\n                }" not in dok and "bes.visibility = if (ima) View.VISIBLE else View.INVISIBLE" in dok)

akt = beri("res/drawable/bg_tab_card_active.xml")
privzeto_akt = akt[akt.rfind("<item>"):]
preveri("Zavihki: aktivni zavihek brez zelenega okvirja (zelen samo fokus)", 'state_focused="true"' in akt and "osMint" not in privzeto_akt)

preveri("Brskalnik: zagon z zaganjalnika pusti en zavihek", ma.count("pustiEnZavihekObZagonu()") >= 3)
preveri("Brskalnik: zavihek spletne aplikacije se ob izhodu unici", "tabManager.closeTab(this, aplikacija.id)" in ma and "ZavihkiPravila.unici(zvokVOzadju" in ma)

preveri("Zavihki: stran pod seznamom brez fokusa, fokus na aktivni kartici", "FOCUS_BLOCK_DESCENDANTS" in ma and "ZavihkiPravila.zacetnaKartica(" in ma and ma.count("tabSwitcherOverlay.visibility = View.GONE") == 1)
for p in ("res/drawable/bg_tab_card.xml", "res/drawable/bg_tab_card_active.xml"):
    preveri("Zavihki: izbrana kartica v GridView poudarjena (%s)" % p, 'state_selected="true"' in beri(p))

sc_on = vn[vn.find("override fun onServiceConnected"):vn.find("private var neprilagojena")]
preveri("Vnos: po ponovni vezavi storitve takoj preveri aplikacijo v ospredju", "preveriMiskoOpravilo" in sc_on)

preveri("Vnos: urejevalno polje cel okvir brez polnila", "VnosVklopPravila.skrajsajSirokOkvir(urejevalno" in vn and "VnosVklopPravila.polniloOkvirja(urejevalno)" in vn)

preveri("Dom: sirina gumbov ostane, izrecna pot LEVO/DESNO", "sirinaGumbaDoka" not in dok and "PokrajinaPravila.sosedaDoka(i, n)" in pk and "nextFocusRightId" in dok)

preveri("Dom: fokus na lupi ne odpre iskalnega nacina", "LupaPravila.odpriPlosco(LupaPravila.DogodekLupe.FOKUS)" in pk and "LupaPravila.odpriPlosco(LupaPravila.DogodekLupe.OK)" in pk)

preveri("Dom: okno Dodaj brez »Razdelek Safeer OS«", "pk_dodaj_razdelek" not in pk and "fun dodajRazdelek" not in pk)

preveri("Vnos: zbiranje drevesa samo za fokus v WebViewu (CodeRabbit 5. 11.)", "miska?.paket == null && jeVSpletnem(vz) &&" in vn and "skrijOkvirVSpletnem(true," in vn)
_ts = beri("assets/tv_spatial.js")
_fi = _ts[_ts.find("if (!samodejnoDovoljeno('fokus strani')"):]
preveri("Splet TV: fokus strani v polju vnosa pocisti staro izbiro (CodeRabbit 5. 11.)", "jeVnosnoPolje(ev.target)) { try { clearActive(); }" in _fi[:600])

# Pregled fokusa 5. 11. 2026 (najdba 9): tv_spatial.js hrani izbrani element v zaprtju; rocni razred ni izbira.
_sa = beri("assets/site_agent.js")
_pz = _sa[_sa.find("if (low.indexOf('player-closed') !== -1)"):]
# Drugi krog 5. 11. 2026: izbira prek izvoza _safeer_izberi_zadnjo_ploscico (ploscica iz zaprtja), ne prek
# _safeer_highlight z elementom, ki ga lahko nastavi stran.
preveri("Splet TV: po zaprtju predvajalnika izbira iz zaprtja, ne rocni razred ne element strani (pregled fokusa 5. 11.)",
        "window._safeer_izberi_zadnjo_ploscico()" in _pz[:1200] and "window._safeer_highlight(lastT)" not in _sa
        and "lastT.classList.add('safeer-active-card')" not in _sa)

if napake:
    print("NAPAKA zunanji pregled TV:", len(napake)); sys.exit(1)
print("zunanji pregled TV OK")
