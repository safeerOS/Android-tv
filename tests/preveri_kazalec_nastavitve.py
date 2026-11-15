#!/usr/bin/env python3
"""Kazalec TV (lastnik 15. 11. 2026, K7, K8, K12): skrivanje ob mirovanju in ob pravi miski, hitrost v meniju v vseh jezikih.

Staticna preverba povezav (pravila so v tests/KazalecPravilaTest.kt in KazalecGibanjeTest.kt; tu je preverjeno, da jih uporabi vmesnik):
- K12: vrstica menija samo na televizorju, besedila v VSEH jezikih, ki jih datoteka ze ima, izbira se zapomni in velja takoj;
- K7: kazalec se skrije z enim odlozenim preizkusom (brez zanke), smerna tipka ga pokaze, prvi OK ga samo pokaze;
- K8: dogodek prave miske skrije navideznega, nasi sinteticni dogodki ne.
"""
import re
import sys
from pathlib import Path

KOREN = Path(__file__).resolve().parents[1]
SRC = KOREN / "src/main/kotlin/si/safeer/tv"
napak = 0


def beri(pot: Path) -> str:
    return pot.read_text(encoding="utf-8")


def preveri(opis: str, pogoj: bool) -> None:
    global napak
    print(("  OK   " if pogoj else "  NAPAKA ") + opis)
    if not pogoj:
        napak += 1


# --- K12: besedila v vseh jezikih ---
KLJUCI = ("menu_kazalec_hitrost", "kazalec_hitrost_pocasi", "kazalec_hitrost_srednje", "kazalec_hitrost_hitro")
jeziki = sorted(p.name for p in (KOREN / "res").iterdir() if re.fullmatch(r"values(-(sl|en|de|es|fr|it))?", p.name))
preveri("jeziki strings.xml: privzeti + sl, en, de, es, fr, it (%s)" % ", ".join(jeziki), len(jeziki) == 7)
for mapa in jeziki:
    s = beri(KOREN / "res" / mapa / "strings.xml")
    for k in KLJUCI:
        m = re.search(r'<string name="%s">([^<]+)</string>' % k, s)
        preveri("%s: %s" % (mapa, k), bool(m) and m.group(1).strip() != "")
pocasi = set()
for mapa in jeziki:
    m = re.search(r'name="kazalec_hitrost_pocasi">([^<]+)<', beri(KOREN / "res" / mapa / "strings.xml"))
    pocasi.add(m.group(1) if m else "")
preveri("besedila stopenj so prevedena (vsaj 5 razlicnih besed za Pocasi)", len(pocasi) >= 5)

lay = beri(KOREN / "res/layout/dialog_mobile_menu.xml")
preveri("meni: vrstica rowMenuKazalecHitrost z besedilom stopnje", "@+id/rowMenuKazalecHitrost" in lay and "@+id/txtKazalecHitrost" in lay and "@string/menu_kazalec_hitrost" in lay)
vrstica = re.search(r'<LinearLayout\s[^>]*rowMenuKazalecHitrost.*?>', lay, re.S)
preveri("meni: vrstica je privzeto skrita (pokaze jo samo televizor)", bool(vrstica) and 'android:visibility="gone"' in vrstica.group(0))

glavna = beri(SRC / "MainActivity.kt")
preveri("MainActivity: vrstica samo na televizorju", re.search(r"rowKazalecHitrost\.visibility = if \(si\.safeer\.tv\.os\.Naprava\.jeTelevizor\(this\)\) View\.VISIBLE else View\.GONE", glavna) is not None)
preveri("MainActivity: klik zakrozi stopnjo, shrani in uporabi takoj", all(t in glavna for t in (
    "KazalecGibanje.naslednjaStopnja(KazalecNastavitve.hitrostStopnja(this))", "KazalecNastavitve.nastaviHitrost(this, naslednja)",
    "virtualPointerView.gibNastavitve = KazalecGibanje.hitrost(naslednja)")))
preveri("MainActivity: shranjena hitrost velja ob zagonu", "virtualPointerView.gibNastavitve = KazalecGibanje.hitrost(KazalecNastavitve.hitrostStopnja(this))" in glavna)
preveri("MainActivity: vsa tri besedila stopenj", all(t in glavna for t in ("R.string.kazalec_hitrost_pocasi", "R.string.kazalec_hitrost_srednje", "R.string.kazalec_hitrost_hitro")))

nast = beri(SRC / "KazalecNastavitve.kt")
preveri("KazalecNastavitve: hitrost v loceni datoteki (izbira po gostiteljih jo ob 200 vnosih ne pobrise)",
        'PREFS_HITROST = "safeer_kazalec_hitrost"' in nast and "KazalecGibanje.veljavnaStopnja(" in nast)

# --- K8: prava miska ---
preveri("MainActivity: dogodek prave miske skrije navidezni kazalec (dispatchGenericMotionEvent)",
        "override fun dispatchGenericMotionEvent(ev: MotionEvent)" in glavna and
        "KazalecPravila.jeDogodekMiske(ev.source, ev.actionMasked)) virtualPointerView.skrijZaMisko()" in glavna and
        "return super.dispatchGenericMotionEvent(ev)" in glavna)

# --- K7: skrivanje ob mirovanju, pokaze tipka ---
poglej = beri(SRC / "VirtualPointerView.kt")
preveri("pogled: pravila skrivanja iz KazalecPravila (tipka, cas, miska, aktivnost)", all(t in poglej for t in (
    "KazalecPravila.skrivanjeTipka(", "KazalecPravila.skrivanjeCas(", "KazalecPravila.skrivanjeMiska(", "KazalecPravila.skrivanjeAktivnost(")))
preveri("pogled: en odlozen preizkus do izteka mirovanja (brez zanke na kratek razmik)",
        "postDelayed(skrijTeka, KazalecPravila.doSkritjaMs(skrivanje, SystemClock.uptimeMillis()))" in poglej and
        len(re.findall(r"postDelayed\(skrijTeka", poglej)) == 1)
preveri("pogled: skrit kazalec ni narisan, a nacin kazalca ostane (visibility INVISIBLE, ne isPointerVisible = false)",
        "visibility = INVISIBLE" in poglej and "if (!isPointerVisible || skrivanje.skrit) return" in poglej)
preveri("pogled: skrijZaMisko ne skrije med vlecenjem", "if (!isPointerVisible || vlecem || skrivanje.skrit) return" in poglej)
preveri("pogled: odlozen preizkus se ob odklopu odstrani", "removeCallbacks(skrijTeka)" in poglej and "override fun onDetachedFromWindow()" in poglej)

usmerjevalnik = beri(SRC / "tv/TvKeyRouter.kt")
preveri("TvKeyRouter: smerna tipka in OK pokazeta skritega kazalca", "host.virtualPointerView.tipka(event.eventTime)" in usmerjevalnik)
preveri("TvKeyRouter: prvi OK ob skritem kazalcu ga samo pokaze (brez klika)",
        "KazalecPravila.tipkaSamoPokaze(bilSkrit, keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)" in usmerjevalnik and
        "host.virtualPointerView.okPorabljen = true" in usmerjevalnik)
# Tipka mora biti obdelana PRED samo obdelavo gibanja, sicer bi smerna tipka premaknila skritega kazalca brez prikaza.
preveri("TvKeyRouter: pokaz je pred obdelavo smeri", usmerjevalnik.index("virtualPointerView.tipka(event.eventTime)") < usmerjevalnik.index("host.virtualPointerView.smerDol(si.safeer.tv.KazalecGibanje.DOL"))

# --- Slepi pregled TV2 (F1, F5): nacin kazalec/puscice samo ob novem dokumentu, izbira gostitelja velja, Google na puscicah ---
glavna = beri(SRC / "MainActivity.kt")
motor = beri(SRC / "ChromiumEngineView.kt")
profil = beri(SRC / "tv/SiteProfile.kt")
blok_url = glavna[glavna.index("wv.onUrlChanged = { newUrl ->"):glavna.index("wv.onNovDokument = ")]
preveri("F1 MainActivity: onUrlChanged (tudi history.replaceState) NE odloca nacina kazalca", "uporabiPrivzetiKazalec" not in blok_url)
preveri("F1 MainActivity: nacin kazalca samo ob novem dokumentu (onNovDokument)",
        "wv.onNovDokument = { novUrl ->" in glavna and "uporabiPrivzetiKazalec(novUrl)" in glavna)
zgodovina = motor[motor.index("override fun doUpdateVisitedHistory"):motor.index("override fun onPageCommitVisible")]
preveri("F1 ChromiumEngineView: doUpdateVisitedHistory (pushState, replaceState, #sidro) ne sprozi onNovDokument", "onNovDokument" not in zgodovina)
zacetek = motor[motor.index("override fun onPageStarted"):motor.index("override fun doUpdateVisitedHistory")]
preveri("F1 ChromiumEngineView: onPageStarted sprozi onNovDokument", "onNovDokument?.invoke" in zacetek)
konec = motor[motor.index("override fun onPageFinished", motor.index("override fun onPageStarted")):][:3500]
preveri("F1 ChromiumEngineView: onPageFinished sprozi onNovDokument samo za dokument iz onPageStarted",
        "if (novDokumentCaka)" in konec and "onNovDokument?.invoke" in konec)
preveri("F1 TvSite.isWatchPage gleda pot na znanem gostitelju (KazalecPravila.jeStranOgleda)",
        "KazalecPravila.jeStranOgleda(url)" in profil and 'u.contains("/watch")' not in profil)
preveri("F1 MainActivity: izbira za gostitelja velja tudi na strani za ogled (zapomnjenaZaStran)", "KazalecPravila.zapomnjenaZaStran(" in glavna)
preveri("F5 MainActivity: Googlove strani so privzeto na puscicah (stranZaTv z GooglePravila.jeGoogle)",
        "KazalecPravila.stranZaTv(" in glavna and "GooglePravila.jeGoogle(naslov))" in glavna)

if napak:
    sys.exit("preveri_kazalec_nastavitve: %d napak" % napak)
print("preveri_kazalec_nastavitve: OK")
