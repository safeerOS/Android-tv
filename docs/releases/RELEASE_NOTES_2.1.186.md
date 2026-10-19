# Safeer 2.1.186 · Safeer OS 0.5.62 · Predvajalnik 0.2.49

## Varnost: seznanitev v Safeer Link ni več dovoljenje za vsebine naprave

Do te izdaje je bila vsaka naprava, seznanjena v Safeer Link, enakovredna. Telefon druge osebe, dodan v Link zato, da pomaga pri povezavi, je v minuti po seznanitvi dobil vire in sezname Medijskega centra drugih naprav (izmerjeno 6. 10. 2026: 12 virov in 1 seznam), druge naprave so brale njegov seznam aplikacij, na voljo pa so mu bile tudi datoteke naprav, kaj se na njih predvaja in »Nadaljuj z druge naprave«.

Zdaj sta kroga dva:

- **Safeer Link je širši krog.** Vsaka seznanjena naprava pomaga pri povezavi (središče, posredovanje, pot prek mobilnega omrežja) in sprejme, kar ji kdo izrecno pošlje: besedilo, datoteko, sporočilo ali ponudbo »Pošlji na napravo«, ki se začne šele, ko jo uporabnik sprejme.
- **Dostop do vsebin je ožji krog.** Na vsaki napravi posebej in za vsako drugo napravo posebej odločiš, kaj ji odpreš: **Datoteke · Programi · Predvajalnik · Zaslon in upravljanje**. Brez tega naprava zahtevo zavrne sama – ne skrije le gumba na drugi napravi.
- **Kje:** Safeer Link → naprava → »Dostop te naprave«. V seznamu naprav pri vsaki piše »Poln dostop«, »Delni dostop« ali »Brez dostopa«.
- **Tvoje dosedanje naprave delajo naprej brez enega dotika.** Naprave, ki so bile s to napravo v Linku pred 6. 10. 2026, obdržijo poln dostop. Vsaka pozneje dodana naprava začne brez dostopa; naprava, ki je bila sama dodana po tem dnevu, ne podeduje nikogar.
- **Odvzem velja takoj,** tudi za že izdan žeton strežnika datotek.

Kaj je zaprto:

- Ukazi drugih naprav: datoteke, programi, kaj se predvaja in nadaljevanje, seznami in viri Medijskega centra, odpiranje strani ali toka, pomoč pri predvajanju, zaslon, tipke, glasnost. Ukaz, ki ga naprava ne pozna, zahteva vse štiri.
- Stran ali predvajanje, ki se odpre samo, zahteva »Predvajalnik«; zaslon druge naprave, ki se odpre sam, zahteva »Zaslon in upravljanje«; usklajevanje stanja (odprte strani, iskanja, mesta v filmih) sprejme naprava samo od naprave, ki ima vse štiri.
- Središče (naprava, ki gosti Safeer Link) stanje predvajanja in usklajevanje posreduje samo napravam, ki jih navede izvor; od naprav s starejšo različico samo znotraj ožjega kroga naprave s središčem. Posredovanim sporočilom pošiljatelja vpiše središče – pri straneh, nadzoru predvajanja in usklajevanju ga je prej lahko vpisala naprava sama.
- Katalog aplikacij naprave ne gre več v seznam naprav, ki ga dobi vsak član Linka.
- Kar pošlješ izrecno, ostane dosegljivo: datoteko, ki jo napravi pošlješ sam, ali predvajanje, ki ga nadaljuje naprava z odprtim »Predvajalnikom«, ta naprava lahko prebere – samo to datoteko, samo za branje.

Preverjeno v živo (6. 10. 2026; telefon s Safeer OS 0.5.62, računalnik s Safeer Control 2.1.61):

- Ob prvem zagonu je telefon vpisal dostop 12 dosedanjim napravam; naprava, dodana tisti dan, je v seznamu »lahko pomaga« in »Brez dostopa«.
- Ko je bil računalniku na telefonu vzet dostop »Programi«, je telefon njegovo zahtevo zavrnil sam (dnevnik: »Zavrnjeno: … nima dostopa za apps.list«), računalnik je izpisal, da naprava programov ne deli; podatki o napravi (»Predvajalnik« je ostal odprt) so delovali naprej. Po vrnitvi dostopa je seznam programov spet prišel.
- Ko je bil telefonu na računalniku vzet dostop, je računalnik zahtevo za datoteke zavrnil sam; telefon je pokazal, da računalnik ne deli nobene mape. Po vrnitvi so se mape pokazale takoj.
- Telefon druge osebe, dodan tisti dan in posodobljen na Safeer OS 0.5.62, je zahtevi računalnika za programe in za podatke o napravi zavrnil sam (njegov dnevnik kaže obe zavrnitvi). V Linku je ostal in njegove storitve Linka so po posodobitvi tekle naprej.

Samodejni preizkusi (pravila dostopa, središče, usklajevanje, prenos podatkov, tokovi, prevodi): vsi sklopi brez napak.

Česa ta izdaja še ne zapre:

- Središče vidi sporočila, ki jih usmerja. Naprava, ki gosti središče in bi imela predelano programsko opremo, bi lahko brala usmerjene ukaze ali ponaredila pošiljatelja. To zapre zaščita ukazov od naprave do naprave (načrtovano).
- Oznaka naprave še ni na vseh poteh vezana na njen ključ. Seznanjena naprava s predelano programsko opremo bi se lahko predstavila z oznako, ki je videti kot oznaka druge naprave, in dobila njen dostop; na napravi, ki nove različice še ni zagnala, bi se lahko vpisala z datumom pred 6. 10. 2026 in dostop podedovala. Tudi to zapre zaščita od naprave do naprave.
- Vsak član Linka še vedno lahko odstrani ali preimenuje napravo in vidi kodo za novo napravo.
- Ožji krog nastaviš na vsaki napravi posebej; samodejno usklajevanje med tvojimi napravami je naslednji korak.
- Naprava s starejšo različico odgovarja vsem, dokler ni posodobljena.
- Safeer Control za Windows in brskalnik Safeer za telefon te spremembe še nimata.

Ni preizkušeno v živo: zahteva, ki bi jo poslala naprava druge osebe (med preizkusom je spala; ista zavrnitev je preverjena s testnim telefonom, ki mu je bil dostop odvzet); televizor in tablica z novo različico; središče z novo različico na Androidu ob napravah s starejšo (pokrito s samodejnimi preizkusi).

## Predvajanje

- **Nov video se ne začne več v premoru.** Kadar si nov video zagnal, medtem ko je bil odprt zaslon predvajanja prejšnjega, se je novi začel v premoru; na televizorju je po nadaljevanju ostala zamrznjena slika. Preverjeno na telefonu in televizorju.
- **Zvočna sled z več kanali.** Kadar ima datoteka v istem jeziku več zvočnih sledi, predvajalnik izbere tisto, ki da na izhodu naprave več kanalov (na primer Dolby Digital Plus 5.1 namesto privzete stereo sledi AAC); sledi s komentarjem ne izbere sam. Preverjeno na televizorju.
- **Oznaka zvoka pod naslovom** pove, kaj gre iz zvočnikov, kadar to ni navaden stereo: na primer »Dolby Atmos« ali »Dolby Digital Plus 5.1«, kadar je tok predan zvočniku, in na televizorju »5.1 → stereo«, kadar prostorski zvok do zvočnika ne pride. Pri formatu, ki ga priključeni zvočnik ne navaja, piše samo ime formata. Preverjeno na televizorju.
- Izmerjeno na televizorju z zvočnikom na eARC: tok Dolby Digital Plus z Atmosom gre na izhod HDMI ARC nespremenjen (neposredni izhod, 8 kanalov). Kaj je dejansko na žici do zvočnika, iz Androida ni mogoče prebrati.
- **Zaslon računalnika:** med sejo aplikacija zadrži Wi-Fi v načinu z nizko zakasnitvijo. Izmerjeno na enem telefonu pri mirni sliki: 0,33 presledka na sekundo brez zaklepa, 0,17 z njim (meritvi nista bili hkrati).
- **Programi:** naprava, izbrana v vrstici naprav, ostane izbrana do 20 sekund, če za hip izgine iz Linka; ko se vrne, dobi svoje programe nazaj takoj (prej jih po kratki prekinitvi do 2 minuti ni bilo). V živo ni zanesljivo preverjeno.

---

English: **Security: pairing a device into Safeer Link is no longer permission to its content.** Until this release every paired device was equal: a phone of another person, added to the Link so that it helps with the connection, received the sources and playlists of the Media Centre of the other devices within a minute (measured: 12 sources and 1 playlist), other devices read its list of apps, and the files of the devices, what was playing on them and "Continue from another device" were open to it.

- **Safeer Link is the wider circle.** Every paired device helps with the connection (hub, relaying, a path over a mobile network) and receives what someone explicitly sends to it: text, a file, a message, or a "Send to device" offer that starts only after the user accepts it.
- **Access to content is the narrower circle.** On each device separately, and for each other device separately, you decide what you open to it: **Files · Programs · Player · Screen and control**. Without that the device refuses the request itself.
- **Where:** Safeer Link → a device → "Access of this device". The list shows "Full access", "Partial access" or "No access" for every device.
- **Your devices keep working without a single tap.** Devices that were in the Link with this device before 6 October 2026 keep full access; every device added later starts with none.
- **Taking access away works at once,** also for a file-server token that was already issued.
- Closed: commands from other devices (files, programs, what is playing and continuing it, playlists and sources, opening a page or stream, help with playback, the screen, keys, volume; an unknown command needs all four), a page or playback that opens by itself, the screen of another device that opens by itself, sync of state (accepted only from a device that has all four), playback state.
- The hub passes playback state and sync only to the devices their source lists; from devices with an older version only inside the narrower circle of the device hosting the hub. The hub now writes the sender into every message it forwards. The app catalogue of a device is no longer part of the device list every member receives.
- What you explicitly send stays reachable: that one file, read only.

Verified live (a phone with Safeer OS 0.5.62, a computer with Safeer Control 2.1.61): on first start the phone wrote down access for the 12 devices it already knew, the device added that day shows "can help" and "No access"; with "Programs" taken away from the computer the phone refused its request by itself and kept answering what stayed open; with access taken away from the phone the computer refused its request for files; giving access back worked at once in both directions. The phone of another person, added that day and updated to Safeer OS 0.5.62, refused the computer's requests for its programs and device details by itself (its log shows both refusals); it stayed in the Link and its Link services kept running after the update.

Not closed yet: the hub sees the messages it routes (a hub with modified software could read routed commands or forge the sender – device-to-device protection of commands is planned); a device id is not yet bound to the device key on every path – a paired device with modified software could present an id that looks like another device's and get its access, or, on a device that has not started the new version yet, enrol with a date before 6 October 2026 and inherit access (device-to-device protection closes this too); any member can still remove or rename a device and sees the pairing code of a new one; the narrower circle is set on each device separately; a device with an older version answers everyone until updated; Safeer Control for Windows and the Safeer browser for phones do not have this change yet.

Not tested live: a request sent by the device of another person (it was asleep; the same refusal was exercised with a test phone whose access had been taken away); a TV and a tablet with the new version; an Android hub with the new version next to devices with an older one (covered by automated tests).

**Playback.** A new video no longer starts paused when it is started while the playback screen of the previous one is open (on a TV the picture stayed frozen after resuming) – verified on a phone and a TV. When a file has several audio tracks in the same language, the player picks the one that gives more channels on the device's output (for example Dolby Digital Plus 5.1 instead of the default AAC stereo track) and does not pick a commentary track by itself – verified on a TV. A label under the title says what comes out of the speakers when it is not plain stereo – for example "Dolby Atmos" or "Dolby Digital Plus 5.1" when the stream is passed to the speaker, and on a TV "5.1 → stereo" when surround sound does not reach the speaker; for a format the connected speaker does not list, only the format name is shown – verified on a TV. Measured on a TV with a speaker on eARC: a Dolby Digital Plus stream with Atmos goes to the HDMI ARC output unchanged (direct output, 8 channels); what is on the wire to the speaker cannot be read from Android. Computer screen: during a session the app keeps Wi-Fi in low-latency mode – measured on one phone with a still picture: 0.33 stalls per second without the lock, 0.17 with it (the two measurements were not simultaneous). Programs: the device selected in the device row stays selected for up to 20 seconds if it drops out of the Link for a moment, and gets its programs back at once when it returns (before, they were missing for up to 2 minutes after a short interruption) – not reliably verified live.
