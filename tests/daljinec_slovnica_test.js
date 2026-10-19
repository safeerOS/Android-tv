#!/usr/bin/env node
// Glasovna slovnica daljinca Safeer Linka (assets/link/daljinec.js, »hitri prevajalnik«) brez brskalnika.
//
// Ocena 19. 10. 2026 (66 ukazov sl + en) je pokazala: »turn off the sound on the phone« je vklopil zvok, naslov strani
// je zlepil cel stavek (»siol.netnatablici«), fraza o napravi je koncala v iskanju, »daj na pavzo« je bilo spletno
// iskanje. Preizkus je prenesen iz Linuxa (tests/test_daljinec_slovnica.js); na koncu so preizkusi za Android
// (Safeer Agent in iskanje na racunalniku), kjer stran zares izvede ukaz z mostom, kakrsnega pripne aplikacija.
//   node tests/daljinec_slovnica_test.js
'use strict';
const fs = require('fs'), path = require('path'), vm = require('vm');

const KODA = fs.readFileSync(path.join(__dirname, '..', 'assets', 'link', 'daljinec.js'), 'utf8');
// Casovniki daljinca (cakanje na odgovor naprave, 8-45 s) ne smejo drzati preizkusa odprtega.
function casovnik(f, ms) { const h = setTimeout(f, ms); if (h && h.unref) h.unref(); return h; }

function kontekst(okno, dokument, jezik) {
  const ctx = { window: okno, document: dokument, navigator: { language: jezik }, setTimeout: casovnik, clearTimeout, Date, JSON, Math,
    encodeURIComponent, String, Number, parseInt, RegExp, Object, Array };
  ctx.window.window = ctx.window;
  vm.createContext(ctx);
  vm.runInContext(KODA, ctx);
  return ctx.window.SafeerDaljinec;
}

function nalozi(jezik) {
  const okno = {
    SafeerLink: { jezik: () => jezik, ukaz: () => {}, znaGovor: () => false },
    SafeerLinkStran: { jezik: jezik, prijaznoIme: (n) => n.ime },
    localStorage: { getItem: () => null, setItem: () => {} },
    addEventListener: () => {},
  };
  const dokument = { getElementById: () => null, querySelectorAll: () => [], addEventListener: () => {}, visibilityState: 'visible' };
  const d = kontekst(okno, dokument, jezik);
  d.odpri({ id: 'tv-test', ime: 'Televizor', zmoznosti: ['remote'] });
  return d;
}

let napak = 0, preverjeno = 0;
function preveri(opis, pogoj) {
  preverjeno++;
  console.log((pogoj ? '  OK   ' : '  NAPAKA ') + opis);
  if (!pogoj) napak++;
}
function r(d, besedilo) { return d.razumi(besedilo); }

// ---- slovensko, brez seznama aplikacij ----
{
  const d = nalozi('sl');
  let u = r(d, 'glasneje'); preveri('glasneje -> volume up', u && u.dejanje === 'volume' && u.parametri.direction === 'up');
  u = r(d, 'malo tišje prosim'); preveri('tišje -> volume down', u && u.dejanje === 'volume' && u.parametri.direction === 'down');
  u = r(d, 'utišaj'); preveri('utišaj -> mute', u && u.parametri.direction === 'mute');
  u = r(d, 'glasnost na 30'); preveri('glasnost na 30 -> level 30', u && u.dejanje === 'volume' && u.parametri.level === 30);
  u = r(d, 'domov'); preveri('domov -> key home', u && u.dejanje === 'key' && u.parametri.key === 'home');
  u = r(d, 'nazaj'); preveri('nazaj -> key back', u && u.parametri.key === 'back');
  u = r(d, 'pavza'); preveri('pavza -> key pause', u && u.parametri.key === 'pause');
  u = r(d, 'predvajaj'); preveri('predvajaj (samo) -> play_pause', u && u.parametri.key === 'play_pause');
  u = r(d, 'preklopi na program 25'); preveri('program 25 -> stevke 25', u && u.dejanje === 'digits' && u.parametri.digits === '25');
  u = r(d, 'odpri safeer.si'); preveri('odpri safeer.si -> open_url https', u && u.dejanje === 'open_url' && u.parametri.url === 'https://safeer.si');
  u = r(d, 'poišči vreme v ljubljani'); preveri('poišči -> iskanje', u && u.dejanje === 'open_url' && /google\.com\/search\?q=vreme%20v%20ljubljani/.test(u.parametri.url));
  u = r(d, 'predvajaj siddharta'); preveri('predvajaj X -> youtube iskanje', u && u.dejanje === 'open_url' && /youtube\.com\/results\?search_query=siddharta/.test(u.parametri.url));
  u = r(d, 'odpri youtube'); preveri('odpri youtube (brez app) -> youtube.com', u && u.dejanje === 'open_url' && u.parametri.url === 'https://www.youtube.com/');
  u = r(d, 'kaj je na zaslonu'); preveri('kaj je na zaslonu -> screenshot', u && u.dejanje === 'screenshot');
  u = r(d, 'počisti predpomnilnik'); preveri('počisti -> clear_cache', u && u.dejanje === 'clear_cache');
  u = r(d, 'abrakadabra neznano'); preveri('neznano -> iskanje z opozorilom', u && u.dejanje === 'iskanje' && u.neznano === true);
  u = r(d, 'stran dol'); preveri('stran dol -> scroll down', u && u.dejanje === 'scroll' && u.parametri.direction === 'down');
  u = r(d, 'Nazaj.'); preveri('locilo na koncu ne moti', u && u.parametri.key === 'back');
  u = r(d, 'odpri safeer.si.'); preveri('pika za naslovom ne moti', u && u.dejanje === 'open_url' && u.parametri.url === 'https://safeer.si');
  // Imena, ki so lastnosti prototipa objekta, niso sopomenke aplikacij (prej TypeError v najdiAplikacijo).
  for (const ime of ['constructor', 'odpri constructor', '__proto__', 'odpri tostring']) {
    let izjema = null;
    try { u = r(d, ime); } catch (e) { izjema = e; }
    preveri('»' + ime + '« brez izjeme', izjema === null && u && typeof u.dejanje === 'string');
  }
}

// ---- slovensko, z aplikacijami naprave (shranjen seznam, ki ga odpri() prebere) ----
{
  const shranjene = JSON.stringify([{ package: 'org.droidtv.playtv', label: 'TV' }, { package: 'com.google.android.youtube.tv', label: 'YouTube' },
    { package: 'com.example.safeerbrowser', label: 'Safeer Browser' }, { package: 'com.netflix.ninja', label: 'Netflix' },
    { package: 'com.google.android.youtube.tv', label: 'YouTube' }]);
  const okno = {
    SafeerLink: { jezik: () => 'sl', ukaz: () => {}, znaGovor: () => false },
    SafeerLinkStran: { jezik: 'sl', prijaznoIme: (n) => n.ime },
    localStorage: { getItem: (k) => (k === 'safeer_daljinec_apps_tv1' ? shranjene : null), setItem: () => {} },
    addEventListener: () => {},
  };
  const dokument = { getElementById: () => null, querySelectorAll: () => [], addEventListener: () => {}, visibilityState: 'visible' };
  const d = kontekst(okno, dokument, 'sl');
  d.odpri({ id: 'tv1', ime: 'Televizor', zmoznosti: ['remote'] });
  let u = r(d, 'odpri youtube'); preveri('odpri youtube -> YouTube', u && u.dejanje === 'launch_app' && u.parametri.package === 'com.google.android.youtube.tv');
  u = r(d, 'zaženi netflix'); preveri('zaženi netflix -> launch_app', u && u.dejanje === 'launch_app' && u.parametri.package === 'com.netflix.ninja');
  u = r(d, 'netflix'); preveri('samo ime -> launch_app', u && u.dejanje === 'launch_app');
  u = r(d, 'odpri brskalnik'); preveri('odpri brskalnik -> Safeer Browser', u && u.dejanje === 'launch_app' && u.parametri.package === 'com.example.safeerbrowser');
  u = r(d, 'predvajaj linkin park na youtubu'); preveri('predvajaj X na youtubu -> open_in_app YouTube', u && u.dejanje === 'open_in_app' && u.parametri.package === 'com.google.android.youtube.tv' && /search_query=linkin%20park/.test(u.parametri.url));
  u = r(d, 'odpri spotify'); preveri('odpri neobstojece -> napaka, ne iskanje', u && u.dejanje === 'napaka');
  u = r(d, 'odpri tv'); preveri('odpri tv -> aplikacija TV (tocno ime)', u && u.dejanje === 'launch_app' && u.parametri.package === 'org.droidtv.playtv');
  u = r(d, 'yt'); preveri('yt ne najde playtv, ampak YouTube', u && u.dejanje === 'launch_app' && u.parametri.package === 'com.google.android.youtube.tv');
  u = r(d, 'odpri televizijo'); preveri('televizija -> ne playtv po naključju (ni xplore) -> TV', u && u.dejanje === 'launch_app' && u.parametri.package === 'org.droidtv.playtv');
}

// ---- angleško ----
{
  const d = nalozi('en');
  let u = r(d, 'volume up'); preveri('volume up', u && u.parametri.direction === 'up');
  u = r(d, 'set volume to 40'); preveri('set volume to 40', u && u.parametri.level === 40);
  u = r(d, 'go back'); preveri('go back -> back', u && u.parametri.key === 'back');
  u = r(d, 'open example.com'); preveri('open example.com -> url', u && u.dejanje === 'open_url' && u.parametri.url === 'https://example.com');
  u = r(d, 'search for cats'); preveri('search for cats', u && /q=cats/.test(u.parametri.url));
  u = r(d, 'play some jazz on youtube'); preveri('play X on youtube', u && /youtube\.com\/results/.test(u.parametri.url) && /jazz/.test(u.parametri.url) && !/youtube%20/.test(u.parametri.url));
  u = r(d, 'channel 12'); preveri('channel 12 -> digits', u && u.dejanje === 'digits' && u.parametri.digits === '12');
}

// ---- nemško ----
{
  const d = nalozi('de');
  let u = r(d, 'lauter'); preveri('de lauter', u && u.parametri.direction === 'up');
  u = r(d, 'öffne heise.de'); preveri('de öffne url', u && u.dejanje === 'open_url' && u.parametri.url === 'https://heise.de');
  u = r(d, 'suche nach wetter berlin'); preveri('de suche', u && /q=wetter%20berlin/.test(u.parametri.url));
}

// ---- regresije 19. 10. 2026 (ocena hitrega prevajalnika na 66 ukazih sl + en) ----
// Daljinec upravlja natanko eno napravo; prijaznoIme kot v link.js (tehnicni ID -> Televizor/Zaslon).
function prijaznoIme(n) { return n.ime && !/^[a-z0-9]+-[a-z0-9-]{4,}$/i.test(n.ime) ? n.ime : n.vloga === 'receiver' ? 'Televizor' : 'Zaslon'; }
function naloziNaprava(jezik, naprava, aplikacije) {
  const shranjene = JSON.stringify(aplikacije || []);
  const okno = {
    SafeerLink: { jezik: () => jezik, ukaz: () => {}, znaGovor: () => false },
    SafeerLinkStran: { jezik: jezik, prijaznoIme: prijaznoIme },
    localStorage: { getItem: (k) => (k === 'safeer_daljinec_apps_' + naprava.id ? shranjene : null), setItem: () => {} },
    addEventListener: () => {},
  };
  const dokument = { getElementById: () => null, querySelectorAll: () => [], addEventListener: () => {}, visibilityState: 'visible' };
  const d = kontekst(okno, dokument, jezik);
  d.odpri(naprava);
  return d;
}
const TELEFON = { id: 'tel1', ime: 'Pixel 7', platforma: 'phone' };
const TELEVIZOR = { id: 'tv1', ime: 'Dnevna soba', platforma: 'tv' };
const TABLICA_PO_IMENU = { id: 'tab1', ime: 'Tablica v kuhinji' };
const SPREJEMNIK = { id: 'rec1', ime: 'a1b2c3-d4e5f6', vloga: 'receiver' };
const RACUNALNIK = { id: 'pc1', ime: 'Prenosnik', platforma: 'linux' };
const YT = [{ package: 'com.google.android.youtube.tv', label: 'YouTube' }];
const smer = (u, s) => !!u && u.dejanje === 'volume' && u.parametri.direction === s;
const tipkaJe = (u, k) => !!u && u.dejanje === 'key' && u.parametri.key === k;
const urlJe = (u, url) => !!u && u.dejanje === 'open_url' && u.parametri.url === url;
const zavrnjeno = (u, vrsta) => !!u && u.dejanje === 'napaka' && u.drugaNaprava === vrsta && /\S/.test(u.opis || '');

// 1) Utisaj v vseh jezikih; "sound on the phone" ni "sound on" (prej: "turn off the sound on the phone" -> unmute).
const UTISAJ = {
  sl: [['izklopi zvok', 'mute'], ['ugasni zvok', 'mute'], ['izklopi zvok na telefonu', 'mute'], ['vklopi zvok', 'unmute']],
  en: [['turn off the sound', 'mute'], ['turn the sound off', 'mute'], ['switch off the sound', 'mute'], ['turn off the sound on the phone', 'mute'],
    ['turn the sound off on my phone', 'mute'], ['turn on the sound', 'unmute'], ['turn the sound on', 'unmute']],
  de: [['schalte den Ton aus', 'mute'], ['Ton ausschalten', 'mute'], ['Ton aus auf dem Handy', 'mute'], ['schalte den Ton an', 'unmute']],
  es: [['apaga el sonido', 'mute'], ['quita el sonido en el teléfono', 'mute'], ['desactiva el sonido', 'mute'], ['activa el sonido', 'unmute']],
  fr: [['coupe le son', 'mute'], ['éteins le son sur le téléphone', 'mute'], ['désactive le son', 'mute'], ['remets le son', 'unmute']],
  it: [["spegni l'audio", 'mute'], ["togli l'audio sul telefono", 'mute'], ['disattiva il suono', 'mute'], ["riattiva l'audio", 'unmute']],
};
for (const jezik of Object.keys(UTISAJ)) {
  const d = naloziNaprava(jezik, TELEFON);
  for (const [stavek, s] of UTISAJ[jezik]) preveri(`${jezik} »${stavek}« -> ${s}`, smer(r(d, stavek), s));
}
{
  const d = naloziNaprava('en', { id: 'x1', ime: 'Zaslon' });
  preveri('en »turn off the sound on the radio« -> mute, ne unmute', smer(r(d, 'turn off the sound on the radio'), 'mute'));
  const tv = naloziNaprava('en', TELEVIZOR);
  preveri('en TV: »turn off the sound on the phone« -> ni unmute, zavrnjeno (telefon)', zavrnjeno(r(tv, 'turn off the sound on the phone'), 'telefon'));
}

// 2) Naslov iz stavka: brez polnil in fraz o napravi (prej "https://siol.netnatablici", "thewebsite24ur.com").
{
  const tab = naloziNaprava('sl', TABLICA_PO_IMENU);
  preveri('sl tablica po imenu: »odpri siol.net na tablici« -> https://siol.net', urlJe(r(tab, 'odpri siol.net na tablici'), 'https://siol.net'));
  preveri('sl »odpri spletno stran 24ur.com« -> https://24ur.com', urlJe(r(tab, 'odpri spletno stran 24ur.com'), 'https://24ur.com'));
  preveri('sl narekovano »odpri 24 ur . com« -> https://24ur.com', urlJe(r(tab, 'odpri 24 ur . com'), 'https://24ur.com'));
  preveri('sl narekovano »odpri 24 ur pika com« -> https://24ur.com', urlJe(r(tab, 'odpri 24 ur pika com'), 'https://24ur.com'));
  preveri('sl narekovano »odpri www . siol . net« -> https://www.siol.net', urlJe(r(tab, 'odpri www . siol . net'), 'https://www.siol.net'));
  preveri('sl »odpri vreme arso.si« -> https://arso.si (dolge besede se ne zlepijo)', urlJe(r(tab, 'odpri vreme arso.si'), 'https://arso.si'));
  let u = r(tab, 'odpri stran o vremenu');
  preveri('sl »odpri stran o vremenu« ni naslov https://ovremenu', !!u && !/ovremenu/.test(String(u.parametri.url || '')));
  u = r(tab, 'odpri ab cd.qqq');
  preveri('sl neznana koncnica se ne zlepi (abcd.qqq)', !!u && !/abcd\.qqq/.test(String(u.parametri.url || '')));
}
const NASLOVI = {
  en: [['open the website 24ur.com', 'https://24ur.com'], ['open the site rtvslo.si on my phone', 'https://rtvslo.si'], ['open 24 ur dot com', 'https://24ur.com']],
  de: [['öffne die Webseite 24ur.com', 'https://24ur.com'], ['öffne siol.net auf dem Handy', 'https://siol.net'], ['öffne 24 ur punkt com', 'https://24ur.com']],
  es: [['abre la página web 24ur.com', 'https://24ur.com'], ['abre siol.net en el móvil', 'https://siol.net'], ['abre 24 ur punto com', 'https://24ur.com']],
  fr: [['ouvre le site 24ur.com', 'https://24ur.com'], ['ouvre siol.net sur le téléphone', 'https://siol.net'], ['ouvre 24 ur point com', 'https://24ur.com']],
  it: [['apri il sito 24ur.com', 'https://24ur.com'], ['apri siol.net sul telefono', 'https://siol.net'], ['apri 24 ur punto com', 'https://24ur.com']],
};
for (const jezik of Object.keys(NASLOVI)) {
  const d = naloziNaprava(jezik, TELEFON);
  for (const [stavek, url] of NASLOVI[jezik]) preveri(`${jezik} »${stavek}« -> ${url}`, urlJe(r(d, stavek), url));
}
{
  // Izgovorjena pika samo pred znano koncnico na koncu naslova; navaden stavek ostane iskanje.
  let u = r(naloziNaprava('es', TELEFON), 'busca a las tres en punto de la tarde');
  preveri('es »en punto de la tarde« ostane iskanje', !!u && /q=a%20las%20tres%20en%20punto%20de%20la%20tarde$/.test(u.parametri.url));
  u = r(naloziNaprava('fr', TELEFON), 'cherche le point de vue');
  preveri('fr »le point de vue« ostane iskanje', !!u && /q=le%20point%20de%20vue$/.test(u.parametri.url));
}

// 3) Fraza o napravi nikoli v iskanje ali naslov; ukaz za drugo napravo se ne izvede (prej YouTube iskanje "na telefonu").
const POKAZI_YOUTUBE = {
  sl: ['pokaži mi YouTube na telefonu', 'telefon'], en: ['show me YouTube on the phone', 'telefon'], de: ['zeig mir YouTube auf dem Handy', 'telefon'],
  es: ['muéstrame YouTube en el móvil', 'telefon'], fr: ['montre-moi YouTube sur le téléphone', 'telefon'], it: ['mostrami YouTube sul telefono', 'telefon'],
};
for (const jezik of Object.keys(POKAZI_YOUTUBE)) {
  const [stavek, vrsta] = POKAZI_YOUTUBE[jezik];
  const tel = naloziNaprava(jezik, TELEFON, YT);
  const u = r(tel, stavek);
  preveri(`${jezik} telefon: »${stavek}« -> aplikacija YouTube`, !!u && u.dejanje === 'launch_app' && u.parametri.package === YT[0].package);
  const tv = naloziNaprava(jezik, TELEVIZOR, YT);
  const z = r(tv, stavek);
  preveri(`${jezik} TV: »${stavek}« -> zavrnjeno z imenom naprave`, zavrnjeno(z, vrsta) && z.opis.indexOf('Dnevna soba') >= 0);
}
{
  const tel = naloziNaprava('sl', TELEFON);
  let u = r(tel, 'pokaži mi YouTube na telefonu');
  preveri('sl brez aplikacij: YouTube brez »na telefonu« v naslovu', urlJe(u, 'https://www.youtube.com/'));
  const pc = naloziNaprava('sl', RACUNALNIK);
  u = r(pc, 'poišči recepte na računalniku');
  preveri('sl »poišči recepte na računalniku« -> iskanje samo »recepte«', !!u && /q=recepte$/.test(u.parametri.url) && u.ocisceno === 'poišči recepte');
  u = r(pc, 'predvajaj Rock Radio na računalniku');
  preveri('sl »predvajaj Rock Radio na računalniku« -> YouTube »rock radio«', !!u && /search_query=rock%20radio$/.test(u.parametri.url));
  u = r(naloziNaprava('en', RACUNALNIK), 'search for Peppa Pig on my PC');
  preveri('en »search for Peppa Pig on my PC« -> iskanje »peppa pig«', !!u && /q=peppa%20pig$/.test(u.parametri.url));
  u = r(naloziNaprava('sl', SPREJEMNIK), 'glasneje na televizorju');
  preveri('sl sprejemnik (vloga receiver): »glasneje na televizorju« -> volume up', smer(u, 'up'));
  u = r(naloziNaprava('sl', TELEVIZOR), 'utišaj tablico');
  preveri('sl TV: »utišaj tablico« -> zavrnjeno (tablica)', zavrnjeno(u, 'tablica'));
  u = r(naloziNaprava('en', TELEVIZOR), 'pause the TV');
  preveri('en TV: »pause the TV« -> pause', tipkaJe(u, 'pause'));
  u = r(naloziNaprava('sl', TELEVIZOR), 'poišči nov telefon');
  preveri('sl TV: »poišči nov telefon« ostane iskanje', !!u && /q=nov%20telefon$/.test(u.parametri.url));
}

// 4) Pavza s predmetom (prej spletno iskanje "daj na pavzo", "ustavi glasbo").
const PAVZA = {
  sl: ['daj na pavzo', 'ustavi glasbo', 'ustavi predvajanje', 'ustavi glasbo na telefonu'],
  en: ['pause the music', 'stop the music', 'stop playback', 'pause it', 'stop the music on the phone'],
  de: ['pausiere die Musik', 'stoppe die Musik', 'Wiedergabe stoppen', 'halte die Musik an'],
  es: ['pausa la música', 'para la música', 'detén la reproducción', 'pon la música en pausa'],
  fr: ['mets la musique en pause', 'arrête la musique', 'arrête la lecture'],
  it: ['metti in pausa la musica', 'ferma la musica', 'interrompi la riproduzione'],
};
for (const jezik of Object.keys(PAVZA)) {
  const d = naloziNaprava(jezik, TELEFON);
  for (const stavek of PAVZA[jezik]) preveri(`${jezik} »${stavek}« -> key pause`, tipkaJe(r(d, stavek), 'pause'));
}
{
  preveri('sl »nadaljuj predvajanje« -> play_pause', tipkaJe(r(naloziNaprava('sl', TELEFON), 'nadaljuj predvajanje'), 'play_pause'));
  preveri('en »resume playback« -> play_pause', tipkaJe(r(naloziNaprava('en', TELEFON), 'resume playback'), 'play_pause'));
}

// ---- Android: izgovorjen stavek do mostu (Safeer Agent, iskanje na racunalniku) ----
// Stran zares poslje ukaz: most.ukaz zapise, kaj gre na katero napravo; odgovore vrne odziv().
function element() {
  const razredi = new Set();
  return { textContent: '', hidden: false, style: {}, addEventListener() {}, focus() {},
    classList: { add: (c) => razredi.add(c), remove: (c) => razredi.delete(c), contains: (c) => razredi.has(c),
      toggle: (c, v) => { const n = v === undefined ? !razredi.has(c) : !!v; if (n) razredi.add(c); else razredi.delete(c); return n; } } };
}
const AGENT = { id: 'agent1', ime: 'Delovni racunalnik', platforma: 'linux', zmoznosti: ['agent'] };
function naloziZMostom(jezik, naprava, agent, akcije) {
  const klici = [];
  const elementi = { daljinecOdgovor: element(), daljinecStanje: element() };
  const okno = {
    SafeerLink: { jezik: () => jezik, znaGovor: () => false,
      ukaz: (id, dejanje, json, ref) => { klici.push({ id: id, dejanje: dejanje, parametri: JSON.parse(json), ref: ref }); },
      naprave: () => JSON.stringify(agent ? [naprava, agent] : [naprava]) },
    SafeerLinkStran: { jezik: jezik, prijaznoIme: prijaznoIme },
    localStorage: { getItem: () => null, setItem: () => {} },
    addEventListener: () => {}, scrollTo: () => {},
  };
  const dokument = { getElementById: (id) => elementi[id] || null, querySelectorAll: () => [], addEventListener: () => {}, visibilityState: 'visible' };
  const d = kontekst(okno, dokument, jezik);
  d.odpri(naprava);
  // Naprava odgovori na "status" in pove, kaj zna (search.query = racunalnik s Safeer OS).
  const status = klici.filter((k) => k.dejanje === 'status')[0];
  if (status) d.odziv({ ref: status.ref, ok: true, data: { actions: akcije || [] } });
  klici.length = 0;
  const reci = (stavek) => d.govor({ stanje: 'koncno', besedilo: stavek });
  return { d: d, klici: klici, odgovor: elementi.daljinecOdgovor, reci: reci };
}
{
  // Brez agenta: ukaz za drugo napravo ne gre nikamor, uporabnik dobi navodilo z imenom naprave.
  const m = naloziZMostom('sl', TELEVIZOR, null);
  m.reci('pokaži mi YouTube na telefonu');
  preveri('Android brez agenta, TV: »pokaži mi YouTube na telefonu« -> nic poslano', m.klici.length === 0);
  preveri('Android brez agenta: navodilo z imenom naprave', m.odgovor.textContent.indexOf('Dnevna soba') >= 0 && m.odgovor.classList.contains('napaka'));
}
{
  // Z agentom: ukaz za drugo napravo dobi agent (cel stavek); ce agent nima modela, uporabnik dobi navodilo, ne napake agenta.
  const m = naloziZMostom('en', TELEVIZOR, AGENT);
  m.reci('show me YouTube on the phone');
  const k = m.klici[0];
  preveri('Android z agentom, TV: »show me YouTube on the phone« -> agent.ask s celim stavkom',
    m.klici.length === 1 && k.dejanje === 'agent.ask' && k.id === AGENT.id && k.parametri.text === 'show me YouTube on the phone');
  if (k) m.d.odziv({ ref: k.ref, ok: false, code: 'agent_ni_modela', message: 'agent_ni_modela' });
  preveri('Android agent brez modela -> navodilo, katero napravo izbrati (nic poslano na TV)',
    m.klici.length === 1 && m.odgovor.textContent.indexOf('Dnevna soba') >= 0);
}
{
  // Z agentom: fraza za TO napravo ni druga naprava (tudi sprejemnik brez platforme) - ukaz izvede daljinec sam.
  const m = naloziZMostom('sl', SPREJEMNIK, AGENT);
  m.reci('glasneje na televizorju');
  preveri('Android z agentom, sprejemnik: »glasneje na televizorju« -> volume up na sprejemniku',
    m.klici.length === 1 && m.klici[0].dejanje === 'volume' && m.klici[0].id === SPREJEMNIK.id && m.klici[0].parametri.direction === 'up');
  const t = naloziZMostom('sl', TELEVIZOR, AGENT);
  t.reci('glasneje na telefonu');
  preveri('Android z agentom, TV: »glasneje na telefonu« -> agent', t.klici.length === 1 && t.klici[0].dejanje === 'agent.ask');
}
{
  // Racunalnik s Safeer OS (search.query): fraza o napravi ne gre v iskalno lupo.
  const m = naloziZMostom('sl', RACUNALNIK, AGENT, ['search.query']);
  m.reci('poišči recepte na računalniku');
  preveri('Android racunalnik: »poišči recepte na računalniku« -> search.query »poišči recepte«',
    m.klici.length === 1 && m.klici[0].dejanje === 'search.query' && m.klici[0].id === RACUNALNIK.id && m.klici[0].parametri.q === 'poišči recepte');
  m.klici.length = 0;
  m.reci('utišaj na računalniku');
  preveri('Android racunalnik: »utišaj na računalniku« -> volume mute (ne iskanje)',
    m.klici.length === 1 && m.klici[0].dejanje === 'volume' && m.klici[0].parametri.direction === 'mute');
}

console.log();
console.log('Preverjeno: ' + preverjeno + ', napak: ' + napak);
if (napak) process.exit(1);
console.log('Vse v redu.');
