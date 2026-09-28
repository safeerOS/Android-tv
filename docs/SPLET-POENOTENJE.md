# Splet v Safeer OS za Android

## Stanje pred poenotenjem

- `DomovActivity` (okus `os`) je iz menija, kartice, hitrega gumba, iskanja in spletnih aplikacij
  odpiral `Brskalnik.namera(...)`, torej vgrajeni `MainActivity`.
- `DomovTabletActivity` (okusa `tablica` in `telefon`) je uporabljal isto namero; metoda
  `Brskalnik.mobilni(...)` je vračala `null`, zato se tuj mobilni brskalnik ni zagnal.
- Navaden Splet je `MainActivity` že ovil s `StranskaVrstica`, spletne aplikacije pa so prek dodatka
  `spletna_aplikacija` preklopile v celozaslonski način in skrile lupino.
- Začetna stran je bila `brave_home.html`. Zgornji del je imel eno mobilno vrstico brez vidne
  vrstice zavihkov, gumba Naprej, značke stanja Safeer Linka in neposrednega števca Ščita.
- `LahkaStran` je ločen skriti WebView za spletne medijske vire in ni pot od menija Splet.

## Stanje po poenotenju

- Vsi trije okusi Safeer OS ostanejo v `MainActivity`, navaden splet in spletne aplikacije pa ohranijo
  `StranskaVrstica`; v pokončnem telefonu se chrome skrči na eno vrstico in seznam zavihkov odpre
  gumb s številom.
- Začetna stran je `file:///android_asset/splet/splet.html` (na TV z `?tv=1`) in uporablja nespremenjena
  skupna sredstva Windows/Linux/Android.
- Široki zaslon ima vrstico zavihkov, `+` za zadnjim zavihkom, stanje Linka ter vrstico
  Nazaj/Naprej/Osveži/Domov, naslov z varnostno oznako in zvezdico, Ščit ter meni.
- Bližnjice berejo in spreminjajo obstoječi vir `SpletneAplikacije`; nova kopija nastavitev ni dodana.
- Most `SafeerAndroid.sporocilo(json)` se doda samo začetni strani, pred tujo navigacijo pa odstrani.
  Dovoljeni sta le varna `http(s)` navigacija v istem zavihku in dialog za dodajanje bližnjice.
- WebView ostaja `ChromiumEngineView`, zato ostanejo vključeni AdBlock, Threat Shield, BankGuard,
  Safe Browsing in obstoječa pravila za pojavna okna, piškotke ter dovoljenja.
- Na TV obstoječi `tv_spatial.js` še naprej vodi fokus po začetni strani; obroba elementov je del
  skupnega sloga `?tv=1`. Tipka Nazaj najprej uporabi zgodovino, na njenem robu pa vrne fokus v
  stransko vrstico Safeer OS.
