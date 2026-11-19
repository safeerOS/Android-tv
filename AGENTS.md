# Pravila za AI pomocnike (Claude, Codex, Antigravity …) v tem repozitoriju

Skupna pravila vseh repozitorijev Safeer; najpopolnejsa razlicica je `AGENTS.md` v safeerOS/Safeer-linux.
Vsako od spodnjih pravil izhaja iz napake, ki je ze bila narejena.

## Zasebnost
- Nikoli v repo, commit, PR ali opombe izdaje: ime lastnika, domaca pot, uporabnisko ime, ime gostitelja, MAC,
  e-naslovi, posnetki zaslona naprav. V besedilu pisi »lastnik«.
- Gesla, zetoni in kljuci nikoli v repo; podpisni kljuci ostanejo na lastnikovem racunalniku.
- Tudi zgrajeni paketi (APK, exe, deb) ne smejo vsebovati domacih poti ali imen (Go: `-trimpath`; preveri s `strings`).

## Git in izdaje
- Avtor commitov je ekipa: `Safeer <info@safeer.si>`, brez vrstic `Co-Authored-By` ali `Claude-Session`.
- Brez `--force`, brez brisanja oznak in izdaj. Nic se ne objavi brez lastnikovega »da«.
- Pred potiskom nove veje preveri, kaj vse gre ven (`git rev-list <veja> --not <vrhovi iz git ls-remote>`): stevilo
  commitov, avtorje, vsebino. Nikoli ne potiskaj iz starega klona z zgodovino pred ciscenjem (19. 10. 2026: veja
  `obnova-zavihka` je v safeerOS odnesla stare commite z imenom lastnika). Nov repozitorij je najprej zaseben.
- Objava sele po neodvisnem pregledu (drug agent ali clovek, ki dela ni naredil) in po pregledu varnostno obcutljive
  kode (posodobitve, dostop naprav, dovoljenja, DNS).

## Varnost kode
- Ce program z visjimi pravicami (root, sistemska storitev, druga aplikacija) uporabi datoteko, ki jo lahko spremeni
  manj privilegiran program, jo mora sam prekopirati in preveriti KOPIJO ali dobiti vsebino neposredno. Preverba pred
  predajo ne zadosca: datoteko lahko zamenja med preverbo in uporabo (neodvisni pregled 19. 10. 2026).
- Po vsaki spremembi gradnje (prevajalnik, povezovalnik, optimizacija) preveri zascite izvorne kode v paketu: pri
  knjiznicah ELF `readelf -lW` -> `GNU_STACK ... RW` brez E (19. 10. 2026: BOLT `--use-gnu-stack` je naredil izvrsljiv
  sklad). Hitrost ni razlog za manj zascite.
- Vsak varnostni popravek ima preizkus, ki brez popravka pade (preveri tako, da popravek zacasno umaknes).

## Meritve in javne trditve
- Odlocamo z izmerjenimi podatki: izmenicne meritve istega dne, enaki pogoji, vsaj tri ponovitve. Stevilka gre v opombe
  ali javno (GitHub, gorvodni projekti) sele, ko je tako izmerjena.

## Zapisnik
- Kar ni delovalo, vodi lastnik izven repozitorija (SAFEER-ZNANJE.md, razdelek ZAPISNIK). Preveri ga pred poskusom in
  dopisi novo napako.

## Predvajanje in Medijski center (odlocitve lastnika 6.-7. 11. 2026)
- Izdelek mora biti odlicen BREZ USB kljuca ali zunanjega diska (vecina uporabnikov ju nima); USB je samo dodatek.
  Vsako meritev predvajanja najprej brez USB. Notranji predpomnilnik ne steje kot USB.
- Usenet na TV vedno po neposredni poti (UsenetMedia3Vir); lokalni HTTP streznik samo za Cast/drugo napravo. Seja pripada
  predvajanju in med pavzo ne potece.
- Zapis filtra »na voljo« velja, dokler je vsebina pri viru (brez casovnega izteka); odstrani ga samo 0 izdaj ob kliku ali
  naslov, ki ga ni vec v katalogu. Katalog 1x na dan: preveri samo nove naslove.
- Klik uporabnika ima prednost pred preverbami v ozadju - samo pravi klik, ne fokus ali predpriprava. »Meja« indeksatorja
  ni »ni na voljo«.
- 3D izdaj (3D, SBS, HSBS, Half-OU ...) na TV NI (lastnik 10. 11. 2026: »3D ne sme prikazovati na TV« - film je bil v dveh polovicah
  slike): ne v seznamu virov, ne pri samodejni izbiri, in naslov, ki ima samo 3D, ne velja za »na voljo« (TokiPravila.je3d).
- Oznaka podnapisov/izvirnika (»Subbed« ...) pri viru ENEGA dodatka ne kaznuje neoznacenih virov DRUGIH dodatkov (lastnik 10. 11.
  2026): »neoznacen = sinhronizacija« velja samo znotraj istega dodatka (TokIzbira.uredi, parameter dodatek).
- Naslov, ki ga uporabnik ne more predvajati, se NE prikaze (lastnik 10. 11. 2026: kdor veckrat na teden naleti na »ni na voljo«,
  postane slabe volje). Vsak vir ima SVOJ filter: Usenet kaze, kar je pri Usenetu, TorBox svoje, ostali viri svoje. Filter preveri
  samo svoj vir - brez trojnega preverjanja vseh virov za vsak naslov, ker to upocasni nalaganje. Vir, ki ni odgovoril, ni »nima«.
- TorBox ima v mrezi Filmi/Serije svoj filter vira, enako kot Usenet (lastnik 10. 11. 2026): pri izbiri »TorBox« so v mrezi samo
  naslovi, ki jih ima TorBox pripravljene. Ista pravila (FilterViraPravila) in ista vrsta preverb - brez kopije UsenetFilter.
- TorBox lahko pripravi tudi naslov, ki ga se nima (lastnik 10. 11. 2026): to uporabniku ponudimo, a mora PRED izbiro vedeti, da
  priprava traja najdlje. Pripravljeni naslovi so prvi in brez oznake; nepripravljen ima jasno oznako in se nikoli ne zacne sam.
  Ce ima naslov takoj predvajljiv tok pri drugem viru (npr. neposredna povezava dodatka), to ponudimo PRED pripravo - je hitreje.
  Podatek vzamemo iz odgovora dodatkov, ki ga ze imamo (brez nove poizvedbe).
- Vdelani mehki podnapisi v jeziku uporabnika imajo prednost pred zunanjimi (OpenSubtitles).
- Mreza, ne vodoravne police; skrita kartica ne sme biti izbirna z daljincem.
- Iskanje (lastnik 7. 11. 2026): novo iskanje vedno zacne s praznim poljem in odprto tipkovnico (OK na polju z zadetki,
  lupa, vrnitev v Iskanje); zadnje iskanje ostane kot predlog; OK isce samo spremenjeno besedilo; tipkovnica ne sme biti
  odvisna od sistemske (lastna mreza crk s C S Z); iskanje brez razlike velikih crk in sumnikov.
- Zgledi za Medijski center (samo zamisli, preverjeno v kodi, kaj ze imamo): basswaves/kinema (MIT, Dom, iskanje) in
  lastnikov prvi prototip StreamNexus HD (sistemska vrsta Android TV, slovenski opisi) - vgradnih streznikov ne prevzemamo.

## Knjiznice (izmerjeno 6. 11. 2026)
- Koda, ki jo knjiznica klice sele med izvajanjem (ExoPlayer LoadControl, Renderer, MediaSource), mora preglasiti podpise,
  ki jih PRAVA razlicica res klice (javap na .aar v gradle cache) - prevod tega ne ujame (media3 1.11: sesutje ob zagonu).
- Preizkus preverja pravi razred prek javne meje, ne kopije kode v preizkusu; casovni preizkusi morajo dati isti izid na
  obremenjenem racunalniku.

## Upravljanje naprav - tipkovnica
- Navidezna tipkovnica pri upravljanju druge naprave (lastnik, 10. 11. 2026): kadar upravljas s TABLICE ali TELEFONA (dotik) racunalnik ali TV in je za vnos potrebna tipkovnica (polje za besedilo dobi fokus ali ga tapnes), se tipkovnica na tablici/telefonu pokaze SAMA - pri deljenju namizja in pri locenem zaslonu. Kadar upravljas z RACUNALNIKA (racunalnik, TV, tablico ali telefon), se navidezna tipkovnica NE pokaze - racunalnik ima svojo. Odloca vrsta naprave, ki upravlja, ne naprave, ki jo upravljamo.

## Medijski center - enako na vseh napravah
- TV je vzorec, ki ga je uporabnik navajen (lastnik, 10. 11. 2026): Medijski center na Android telefonu in tablici ter na Linuxu
  in Windows se obnasa ENAKO kot na TV - isti filtri mreze (izbira vira Usenet ali TorBox filtrira mrezo), isti vrstni red, iste
  oznake, isti potek do predvajanja. Razlika samo tam, kjer jo zahteva naprava (dotik, miska, tipkovnica, velikost zaslona).
  Vsaka sprememba Medijskega centra na TV ima v isti nalogi preverbo telefona, tablice in namizja; najdena razlika je napaka.
- Strezniki in viri v Medijskem centru (lastnik, 10. 11. 2026): na TV, racunalniku, tablici in telefonu omogocimo samo tisto, kar se na tej napravi dejansko lahko uporabi (doda, poveze in predvaja - izmerjeno, ne predpostavljeno). Vrsta streznika, ki na napravi ne dela, se tam ne pokaze; ki dela, se pokaze na vseh napravah, kjer dela.

- Ko je lastnik v službi, spi ali je odsoten, delaš naprej vse, kar zmoreš brez njega (lastnik 12. 11. 2026): dolge preizkuse zaženi v ozadju z dnevnikom in jih preveri v ločenem klicu; poročilo vsebuje, kaj je narejeno (z dokazom, polne poti) in kaj čaka na njegov »da« (objava, potisk, žetoni, prijave z njegovimi podatki).
- Plakati in naslovnice (lastnik 15. 11. 2026: »film in plakat sta na voljo, dokler je film na voljo«): na disku (notranji, USB, Link) plakat NE zastara po casu od prenosa. Gre sele, ko ga 90 dni nihce ne prikaze (`PredpomnilnikPravila.NASLOVNICA_NEUPORABA`; vsak prikaz osvezi zadnjo uporabo) ali ko LRU zahteva prostor; meja diska ni tesna (USB je velik in hiter). RAM: blaga stopnja onTrimMemory odnese polovico plakatov, vse sele RUNNING_CRITICAL, od MODERATE navzgor in onLowMemory (`slikeOstanejoOdstotkov`). Meje RAM za USB ne spreminjaj brez meritve ms na plakat.
- Priljubljeni filmi in serije (lastnik 15. 11. 2026: »enostavno dosegljivi pri filtru, kjer je Priporoceno«; dosegljivost je bistvo, sicer ni koristi): v razvrstitvi mreze Filmi | Serije je izbira »Priljubljene (N)« PRVA, pred Priporoceno, vidna takoj ob odprtju okna izbire (iz privzete izbire en premik). Uporablja obstojeci seznam `MedijskiViri.priljubljene` (srce), ne drugega; samo cele vsebine, ne epizode; brez priljubljenih izbire ni (nic lazne prazne izbire). Pravila: `PriljubljeneMrezaPravila`.
- Priljubljene v mrezi Filmi | Serije: LOCENA zastavica `mrezaSamoPriljubljene`, nikoli vrednost v skupni `razvrstitve[VIDEO]` (police in gumb Razvrsti in filtriraj imajo 5 nacinov; vrednost 5 je sesula zaslon). Ko zadnja priljubljena izgine, se zastavica ponastavi. Izbira upošteva zvrst in loci zasebne dodatke; seznam se predpomni po `MedijskiViri.verzijaPriljubljenih`.
- Ura predpomnilnika (15. 11. 2026): cas pod `PredpomnilnikPravila.SMISELNA_URA_MS` (TV z uro iz 1970) je neznan - plakatov in vnosov po casu ne brisemo (samo LRU ob polnem disku). onTrimMemory prihaja v MainActivity IN OsActivity: delno praznjenje slik najvec enkrat na 3 s (`smeTrimati`).

## Kje beri in kam zapisuj (lastnik 18. 11. 2026: »da se moje delo ne bo izgubilo ali pozabilo«)
Izven repozitorija, v mapi `outputs` na lastnikovem racunalniku (pot ti da naloga ali vodja; v repozitorij je ne pisi):
- **Pred delom beri:** `SAFEER-TEMELJ.md` (izdelki, repozitoriji, kako se gradi, kje so kljuci - samo poti, vizija, prispevki skupnosti), `UKAZI-LASTNIKA.md` (lastnikovi odprti ukazi in prednostni projekti), `STANJE-ZDAJ.md` (kaj tece zdaj), `SAFEER-ZNANJE.md` razdelek ZAPISNIK (kaj ni delovalo).
- **Po delu zapisi:** opravljeno -> oznaka `[OPRAVLJENO <datum> <commit/dokaz>]` v `UKAZI-LASTNIKA.md`; nov lastnikov ukaz -> vanjo v ISTEM obratu, z uro in njegovimi besedami; neuspel poskus -> ZAPISNIK; spremenjeno stanje -> `STANJE-ZDAJ.md`; nov izdelek, pot gradnje ali kljuca, nova odlocitev lastnika -> `SAFEER-TEMELJ.md`; stalno pravilo za vse agente -> ta AGENTS.md.
- **Dnevnik dela (obvezno):** pred delom preberi zadnjih ~100 vrstic `DNEVNIK-DELA.md`; ob KONCU vsake naloge (tudi nedokoncane ali ustavljene) dopisi blok: naslov (datum, agent, projekt/veja), narocilo, narejeno (commiti, dokaz), ni narejeno (in zakaj), stanje (necommitano, procesi, tmp za pospravit), naslednji korak. Drug agent mora iz dnevnika sam razbrati, kje smo, brez opominjanja.
- Brez dostopa do teh datotek (agent v oblaku ali kopija): na koncu porocila razdelek »Za zapis« z natancnimi vrsticami, ki jih vodja vpise. Nikoli ne spusti dela samo zato, ker ga ni kam zapisati.
- V repozitorij nikoli: osebni podatki, poti z uporabniskim imenom, kljuci in skrivnosti (te datoteke jih ne vsebujejo).
- **Sporočeno lastniku (lastnik 19. 11. 2026):** vse, kar poročaš lastniku, zapiši tudi v dnevnik (vrstica »SPOROČENO LASTNIKU«); pred poročilom preberi, kaj je že sporočeno, in ne ponavljaj istega ali istega dela dvakrat.
