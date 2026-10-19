/**
 * Safeer Control — daljinec v Safeer Linku.
 *
 * Ko sta napravi povezani prek Safeer Linka, telefon (ali racunalnik) upravlja televizor:
 * z glasom, s tipkami, z dotikom aplikacije. Vse gre po Linku kot `control.command`;
 * odgovor pride kot `control.result`. Stran ne vidi ne zetona ne omrezja - za vse prosi
 * most (window.SafeerLink.ukaz / poslusaj), odgovori pridejo v safeerLinkOdziv in od tam
 * v SafeerDaljinec.odziv / SafeerDaljinec.govor.
 *
 * Glas se razume tukaj, brez oblaka: kratka slovnica v jeziku uporabnika (glasnost, tipke,
 * aplikacije po imenu, strani, iskanje). Kar ne razumemo, gre kot iskanje na napravo -
 * uporabnik vedno vidi, kaj smo slisali in kaj smo naredili.
 *
 * Ista datoteka je v brskalniku za telefon, televizor in Linux.
 */
(function () {
  "use strict";

  var most = window.SafeerLink || null;
  var el = function (id) { return document.getElementById(id); };
  var api = function () { return window.SafeerLinkStran || {}; };

  // ----------------------------------------------------------------
  // Besedila (6 jezikov, kot Safeer Link)
  // ----------------------------------------------------------------

  var BESEDILA = {
    sl: {
      upravljam: "Upravljam", nazajNaLink: "Nazaj na Safeer Link", pokaziZaslon: "Pokaži zaslon naprave",
      samodejno: "Samodejno", govori: "Govori", poslji: "Pošlji",
      govoriNamig: "Pritisni mikrofon in povej, npr. »odpri YouTube« ali »glasneje«",
      pisiNamig: "Vpiši ukaz, npr. »odpri YouTube«, »glasneje« ali »program 25«",
      vnosNamig: "Ukaz, stran ali iskanje …",
      domov: "Domov", nazaj: "Nazaj", predvajaj: "Predvajaj", meni: "Meni",
      stranGor: "Stran gor", stranDol: "Stran dol", prejsnji: "Prejšnji", naslednji: "Naslednji",
      aplikacije: "Aplikacije na napravi", aplikacijeNa: "Aplikacije na napravi »{ime}«", sredisce: "Središče Safeer Linka: {ime}", osvezi: "Osveži", vec: "Več",
      znovaZazeni: "Znova zaženi Safeer", pocistiPredpomnilnik: "Počisti predpomnilnik", stanjeNaprave: "Stanje naprave",
      poslusam: "Poslušam …", obdelujem: "Razumem …", nicSlisano: "Nič nisem slišal. Poskusi znova.",
      dovoljenje: "Dovoli mikrofon, nato pritisni znova.", niGovora: "Glasovno upravljanje na tej napravi ni na voljo.",
      napakaGovora: "Prepoznava ni uspela. Poskusi znova.",
      brezOdgovora: "Naprava se ni odzvala. Je Safeer na njej odprt?", agentRazmislja: "Agent razmišlja …", agentSeja: "(agent še {min} min)",
      niPovezave: "Ni povezave s Safeer Linkom.",
      poslano: "Poslano", tipka: "Tipka {ime}", odpiram: "Odpiram {ime}", iscem: "Iščem »{kaj}«", iscemNaRacunalniku: "Iščem na računalniku: »{kaj}«",
      iscemYoutube: "YouTube: »{kaj}«", odpiramStran: "Odpiram stran", glasnostNa: "Glasnost {n} %",
      glasneje: "Glasneje", tisje: "Tišje", utisano: "Utišano", zvokNazaj: "Zvok je nazaj",
      nisemRazumel: "Nisem razumel: »{kaj}« — iščem to na napravi.",
      aplikacijaNiNamescena: "Aplikacije »{ime}« ni na napravi.",
      nalagamAplikacije: "Nalagam aplikacije …", brezAplikacij: "Naprava ni vrnila seznama aplikacij.",
      posnetekOsvezen: "Zaslon ob {cas}", posnetekNiUspel: "Posnetka ni bilo mogoče narediti.",
      odprtaStran: "Odprto: {naslov}", naprava: "naprava", niVOspredju: "Safeer na napravi ni odprt — odpri stran ali pritisni Domov.",
      niNaRacunalniku: "Te tipke na računalniku ni — uporabi miško ali tipkovnico.",
      neMoreVOspredje: "Safeer se na napravi ne more odpreti sam: na njej odpri Safeer Link in pritisni »Dovoli«.",
      kanal: "Program {n}", predvajanje: "Predvajanje", pavza: "Pavza",
      drugaNaprava: "Ta daljinec upravlja »{ime}«. {naprava} izberi med napravami v Safeer Linku.",
      vrstaTv: "Televizor", vrstaTablica: "Tablico", vrstaTelefon: "Telefon", vrstaRacunalnik: "Računalnik"
    },
    en: {
      upravljam: "Controlling", nazajNaLink: "Back to Safeer Link", pokaziZaslon: "Show device screen",
      samodejno: "Auto", govori: "Speak", poslji: "Send",
      govoriNamig: "Tap the microphone and say e.g. “open YouTube” or “louder”",
      pisiNamig: "Type a command, e.g. “open YouTube”, “louder” or “channel 25”",
      vnosNamig: "Command, page or search …",
      domov: "Home", nazaj: "Back", predvajaj: "Play", meni: "Menu",
      stranGor: "Page up", stranDol: "Page down", prejsnji: "Previous", naslednji: "Next",
      aplikacije: "Apps on the device", aplikacijeNa: "Apps on “{ime}”", sredisce: "Safeer Link hub: {ime}", osvezi: "Refresh", vec: "More",
      znovaZazeni: "Restart Safeer", pocistiPredpomnilnik: "Clear cache", stanjeNaprave: "Device status",
      poslusam: "Listening …", obdelujem: "Got it …", nicSlisano: "I didn't hear anything. Try again.",
      dovoljenje: "Allow the microphone, then tap again.", niGovora: "Voice control is not available on this device.",
      napakaGovora: "Recognition failed. Try again.",
      brezOdgovora: "The device did not respond. Is Safeer open on it?", agentRazmislja: "Agent is thinking …", agentSeja: "(agent for {min} more min)",
      niPovezave: "Not connected to Safeer Link.",
      poslano: "Sent", tipka: "Key {ime}", odpiram: "Opening {ime}", iscem: "Searching “{kaj}”", iscemNaRacunalniku: "Searching the computer: “{kaj}”",
      iscemYoutube: "YouTube: “{kaj}”", odpiramStran: "Opening page", glasnostNa: "Volume {n} %",
      glasneje: "Louder", tisje: "Quieter", utisano: "Muted", zvokNazaj: "Sound is back",
      nisemRazumel: "Didn't understand “{kaj}” — searching for it on the device.",
      aplikacijaNiNamescena: "The app “{ime}” is not on the device.",
      nalagamAplikacije: "Loading apps …", brezAplikacij: "The device returned no app list.",
      posnetekOsvezen: "Screen at {cas}", posnetekNiUspel: "Could not take a screenshot.",
      odprtaStran: "Open: {naslov}", naprava: "device", niVOspredju: "Safeer is not open on the device — open a page or press Home.",
      niNaRacunalniku: "That key does not exist on a computer — use the mouse or keyboard.",
      neMoreVOspredje: "Safeer cannot open by itself on the device: open Safeer Link there and press “Allow”.",
      kanal: "Channel {n}", predvajanje: "Playing", pavza: "Pause",
      drugaNaprava: "This remote controls “{ime}”. To control the {naprava}, pick it from your devices in Safeer Link.",
      vrstaTv: "TV", vrstaTablica: "tablet", vrstaTelefon: "phone", vrstaRacunalnik: "computer"
    },
    de: {
      upravljam: "Steuere", nazajNaLink: "Zurück zu Safeer Link", pokaziZaslon: "Gerätebildschirm zeigen",
      samodejno: "Automatisch", govori: "Sprechen", poslji: "Senden",
      govoriNamig: "Tippe auf das Mikrofon und sage z. B. „öffne YouTube“ oder „lauter“",
      pisiNamig: "Gib einen Befehl ein, z. B. „öffne YouTube“, „lauter“ oder „Programm 25“",
      vnosNamig: "Befehl, Seite oder Suche …",
      domov: "Start", nazaj: "Zurück", predvajaj: "Wiedergabe", meni: "Menü",
      stranGor: "Seite hoch", stranDol: "Seite runter", prejsnji: "Zurück", naslednji: "Weiter",
      aplikacije: "Apps auf dem Gerät", aplikacijeNa: "Apps auf „{ime}“", sredisce: "Safeer-Link-Zentrale: {ime}", osvezi: "Aktualisieren", vec: "Mehr",
      znovaZazeni: "Safeer neu starten", pocistiPredpomnilnik: "Cache leeren", stanjeNaprave: "Gerätestatus",
      poslusam: "Ich höre …", obdelujem: "Verstanden …", nicSlisano: "Ich habe nichts gehört. Versuch es noch einmal.",
      dovoljenje: "Mikrofon erlauben, dann erneut tippen.", niGovora: "Sprachsteuerung ist auf diesem Gerät nicht verfügbar.",
      napakaGovora: "Erkennung fehlgeschlagen. Versuch es noch einmal.",
      brezOdgovora: "Das Gerät hat nicht geantwortet. Ist Safeer dort geöffnet?", agentRazmislja: "Der Agent denkt nach …", agentSeja: "(Agent noch {min} Min.)",
      niPovezave: "Nicht mit Safeer Link verbunden.",
      poslano: "Gesendet", tipka: "Taste {ime}", odpiram: "Öffne {ime}", iscem: "Suche „{kaj}“", iscemNaRacunalniku: "Suche auf dem Computer: „{kaj}“",
      iscemYoutube: "YouTube: „{kaj}“", odpiramStran: "Öffne Seite", glasnostNa: "Lautstärke {n} %",
      glasneje: "Lauter", tisje: "Leiser", utisano: "Stumm", zvokNazaj: "Ton ist wieder da",
      nisemRazumel: "„{kaj}“ nicht verstanden — suche danach auf dem Gerät.",
      aplikacijaNiNamescena: "Die App „{ime}“ ist nicht auf dem Gerät.",
      nalagamAplikacije: "Lade Apps …", brezAplikacij: "Das Gerät hat keine App-Liste geliefert.",
      posnetekOsvezen: "Bildschirm um {cas}", posnetekNiUspel: "Screenshot nicht möglich.",
      odprtaStran: "Offen: {naslov}", naprava: "Gerät", niVOspredju: "Safeer ist auf dem Gerät nicht geöffnet — Seite öffnen oder Start drücken.",
      niNaRacunalniku: "Diese Taste gibt es am Computer nicht — Maus oder Tastatur benutzen.",
      neMoreVOspredje: "Safeer kann sich auf dem Gerät nicht selbst öffnen: dort Safeer Link öffnen und „Erlauben“ drücken.",
      kanal: "Programm {n}", predvajanje: "Wiedergabe", pavza: "Pause",
      drugaNaprava: "Diese Fernbedienung steuert „{ime}“. Wähle {naprava} unter den Geräten in Safeer Link.",
      vrstaTv: "den Fernseher", vrstaTablica: "das Tablet", vrstaTelefon: "das Handy", vrstaRacunalnik: "den Computer"
    },
    es: {
      upravljam: "Controlando", nazajNaLink: "Volver a Safeer Link", pokaziZaslon: "Mostrar pantalla del dispositivo",
      samodejno: "Automático", govori: "Hablar", poslji: "Enviar",
      govoriNamig: "Toca el micrófono y di, p. ej., «abre YouTube» o «más alto»",
      pisiNamig: "Escribe una orden, p. ej. «abre YouTube», «más alto» o «canal 25»",
      vnosNamig: "Comando, página o búsqueda …",
      domov: "Inicio", nazaj: "Atrás", predvajaj: "Reproducir", meni: "Menú",
      stranGor: "Página arriba", stranDol: "Página abajo", prejsnji: "Anterior", naslednji: "Siguiente",
      aplikacije: "Apps en el dispositivo", aplikacijeNa: "Apps en «{ime}»", sredisce: "Centro de Safeer Link: {ime}", osvezi: "Actualizar", vec: "Más",
      znovaZazeni: "Reiniciar Safeer", pocistiPredpomnilnik: "Borrar caché", stanjeNaprave: "Estado del dispositivo",
      poslusam: "Escuchando …", obdelujem: "Entendido …", nicSlisano: "No he oído nada. Inténtalo de nuevo.",
      dovoljenje: "Permite el micrófono y vuelve a tocar.", niGovora: "El control por voz no está disponible en este dispositivo.",
      napakaGovora: "El reconocimiento falló. Inténtalo de nuevo.",
      brezOdgovora: "El dispositivo no respondió. ¿Está Safeer abierto en él?", agentRazmislja: "El agente está pensando …", agentSeja: "(agente {min} min más)",
      niPovezave: "Sin conexión con Safeer Link.",
      poslano: "Enviado", tipka: "Tecla {ime}", odpiram: "Abriendo {ime}", iscem: "Buscando «{kaj}»", iscemNaRacunalniku: "Buscando en el ordenador: «{kaj}»",
      iscemYoutube: "YouTube: «{kaj}»", odpiramStran: "Abriendo página", glasnostNa: "Volumen {n} %",
      glasneje: "Más alto", tisje: "Más bajo", utisano: "Silenciado", zvokNazaj: "Sonido activado",
      nisemRazumel: "No entendí «{kaj}»: lo busco en el dispositivo.",
      aplikacijaNiNamescena: "La app «{ime}» no está en el dispositivo.",
      nalagamAplikacije: "Cargando apps …", brezAplikacij: "El dispositivo no devolvió la lista de apps.",
      posnetekOsvezen: "Pantalla a las {cas}", posnetekNiUspel: "No se pudo capturar la pantalla.",
      odprtaStran: "Abierto: {naslov}", naprava: "dispositivo", niVOspredju: "Safeer no está abierto en el dispositivo: abre una página o pulsa Inicio.",
      niNaRacunalniku: "Esa tecla no existe en un ordenador: usa el ratón o el teclado.",
      neMoreVOspredje: "Safeer no puede abrirse solo en el dispositivo: abre allí Safeer Link y pulsa «Permitir».",
      kanal: "Canal {n}", predvajanje: "Reproduciendo", pavza: "Pausa",
      drugaNaprava: "Este mando controla «{ime}». Elige {naprava} entre los dispositivos de Safeer Link.",
      vrstaTv: "el televisor", vrstaTablica: "la tableta", vrstaTelefon: "el teléfono", vrstaRacunalnik: "el ordenador"
    },
    fr: {
      upravljam: "Je contrôle", nazajNaLink: "Retour à Safeer Link", pokaziZaslon: "Afficher l'écran de l'appareil",
      samodejno: "Auto", govori: "Parler", poslji: "Envoyer",
      govoriNamig: "Touchez le micro et dites p. ex. « ouvre YouTube » ou « plus fort »",
      pisiNamig: "Tapez une commande, p. ex. « ouvre YouTube », « plus fort » ou « chaîne 25 »",
      vnosNamig: "Commande, page ou recherche …",
      domov: "Accueil", nazaj: "Retour", predvajaj: "Lecture", meni: "Menu",
      stranGor: "Page haut", stranDol: "Page bas", prejsnji: "Précédent", naslednji: "Suivant",
      aplikacije: "Applis sur l'appareil", aplikacijeNa: "Applis sur « {ime} »", sredisce: "Centre Safeer Link : {ime}", osvezi: "Actualiser", vec: "Plus",
      znovaZazeni: "Redémarrer Safeer", pocistiPredpomnilnik: "Vider le cache", stanjeNaprave: "État de l'appareil",
      poslusam: "J'écoute …", obdelujem: "Compris …", nicSlisano: "Je n'ai rien entendu. Réessayez.",
      dovoljenje: "Autorisez le micro, puis touchez à nouveau.", niGovora: "La commande vocale n'est pas disponible sur cet appareil.",
      napakaGovora: "La reconnaissance a échoué. Réessayez.",
      brezOdgovora: "L'appareil n'a pas répondu. Safeer y est-il ouvert ?", agentRazmislja: "L'agent réfléchit …", agentSeja: "(agent encore {min} min)",
      niPovezave: "Pas de connexion à Safeer Link.",
      poslano: "Envoyé", tipka: "Touche {ime}", odpiram: "J'ouvre {ime}", iscem: "Recherche « {kaj} »", iscemNaRacunalniku: "Recherche sur l'ordinateur : « {kaj} »",
      iscemYoutube: "YouTube : « {kaj} »", odpiramStran: "J'ouvre la page", glasnostNa: "Volume {n} %",
      glasneje: "Plus fort", tisje: "Moins fort", utisano: "Muet", zvokNazaj: "Le son est revenu",
      nisemRazumel: "Je n'ai pas compris « {kaj} » — je le cherche sur l'appareil.",
      aplikacijaNiNamescena: "L'appli « {ime} » n'est pas sur l'appareil.",
      nalagamAplikacije: "Chargement des applis …", brezAplikacij: "L'appareil n'a pas renvoyé de liste d'applis.",
      posnetekOsvezen: "Écran à {cas}", posnetekNiUspel: "Capture d'écran impossible.",
      odprtaStran: "Ouvert : {naslov}", naprava: "appareil", niVOspredju: "Safeer n'est pas ouvert sur l'appareil — ouvrez une page ou appuyez sur Accueil.",
      niNaRacunalniku: "Cette touche n'existe pas sur un ordinateur — utilisez la souris ou le clavier.",
      neMoreVOspredje: "Safeer ne peut pas s'ouvrir tout seul sur l'appareil : ouvrez-y Safeer Link et appuyez sur « Autoriser ».",
      kanal: "Chaîne {n}", predvajanje: "Lecture", pavza: "Pause",
      drugaNaprava: "Cette télécommande contrôle « {ime} ». Choisissez {naprava} parmi les appareils de Safeer Link.",
      vrstaTv: "le téléviseur", vrstaTablica: "la tablette", vrstaTelefon: "le téléphone", vrstaRacunalnik: "l'ordinateur"
    },
    it: {
      upravljam: "Controllo", nazajNaLink: "Torna a Safeer Link", pokaziZaslon: "Mostra lo schermo del dispositivo",
      samodejno: "Automatico", govori: "Parla", poslji: "Invia",
      govoriNamig: "Tocca il microfono e di' ad es. «apri YouTube» o «più forte»",
      pisiNamig: "Scrivi un comando, ad es. «apri YouTube», «più forte» o «canale 25»",
      vnosNamig: "Comando, pagina o ricerca …",
      domov: "Home", nazaj: "Indietro", predvajaj: "Riproduci", meni: "Menu",
      stranGor: "Pagina su", stranDol: "Pagina giù", prejsnji: "Precedente", naslednji: "Successivo",
      aplikacije: "App sul dispositivo", aplikacijeNa: "App su «{ime}»", sredisce: "Centro Safeer Link: {ime}", osvezi: "Aggiorna", vec: "Altro",
      znovaZazeni: "Riavvia Safeer", pocistiPredpomnilnik: "Svuota la cache", stanjeNaprave: "Stato del dispositivo",
      poslusam: "In ascolto …", obdelujem: "Capito …", nicSlisano: "Non ho sentito nulla. Riprova.",
      dovoljenje: "Consenti il microfono, poi tocca di nuovo.", niGovora: "Il controllo vocale non è disponibile su questo dispositivo.",
      napakaGovora: "Riconoscimento non riuscito. Riprova.",
      brezOdgovora: "Il dispositivo non ha risposto. Safeer è aperto lì?", agentRazmislja: "L'agente sta pensando …", agentSeja: "(agente ancora {min} min)",
      niPovezave: "Nessuna connessione a Safeer Link.",
      poslano: "Inviato", tipka: "Tasto {ime}", odpiram: "Apro {ime}", iscem: "Cerco «{kaj}»", iscemNaRacunalniku: "Cerco sul computer: «{kaj}»",
      iscemYoutube: "YouTube: «{kaj}»", odpiramStran: "Apro la pagina", glasnostNa: "Volume {n} %",
      glasneje: "Più forte", tisje: "Più piano", utisano: "Silenziato", zvokNazaj: "Audio riattivato",
      nisemRazumel: "Non ho capito «{kaj}»: lo cerco sul dispositivo.",
      aplikacijaNiNamescena: "L'app «{ime}» non è sul dispositivo.",
      nalagamAplikacije: "Carico le app …", brezAplikacij: "Il dispositivo non ha restituito l'elenco delle app.",
      posnetekOsvezen: "Schermo alle {cas}", posnetekNiUspel: "Impossibile catturare lo schermo.",
      odprtaStran: "Aperto: {naslov}", naprava: "dispositivo", niVOspredju: "Safeer non è aperto sul dispositivo: apri una pagina o premi Home.",
      niNaRacunalniku: "Quel tasto non esiste su un computer: usa il mouse o la tastiera.",
      neMoreVOspredje: "Safeer non può aprirsi da solo sul dispositivo: apri lì Safeer Link e premi «Consenti».",
      kanal: "Canale {n}", predvajanje: "In riproduzione", pavza: "Pausa",
      drugaNaprava: "Questo telecomando controlla «{ime}». Scegli {naprava} tra i dispositivi di Safeer Link.",
      vrstaTv: "il televisore", vrstaTablica: "il tablet", vrstaTelefon: "il telefono", vrstaRacunalnik: "il computer"
    }
  };

  var jezik = (function () {
    var oznaka = "";
    try { if (api().jezik) oznaka = String(api().jezik || ""); } catch (e) {}
    try { if (!oznaka && most && most.jezik) oznaka = String(most.jezik() || ""); } catch (e) {}
    if (!oznaka) oznaka = (navigator.language || "en");
    oznaka = oznaka.toLowerCase().slice(0, 2);
    return BESEDILA[oznaka] ? oznaka : "en";
  })();

  function t(kljuc, nadomestki) {
    var niz = BESEDILA[jezik][kljuc];
    if (niz === undefined) niz = BESEDILA.en[kljuc];
    if (niz === undefined) niz = BESEDILA.sl[kljuc] || "";
    if (nadomestki) {
      for (var k in nadomestki) {
        if (Object.prototype.hasOwnProperty.call(nadomestki, k)) niz = niz.split("{" + k + "}").join(String(nadomestki[k]));
      }
    }
    return niz;
  }

  function prevedi() {
    var vsi = document.querySelectorAll("#zaslonDaljinec [data-d]");
    for (var i = 0; i < vsi.length; i++) {
      var niz = t(vsi[i].getAttribute("data-d"));
      if (niz) vsi[i].textContent = niz;
    }
    var naslovi = document.querySelectorAll("#zaslonDaljinec [data-d-naslov]");
    for (var j = 0; j < naslovi.length; j++) {
      var n = t(naslovi[j].getAttribute("data-d-naslov"));
      if (n) { naslovi[j].setAttribute("aria-label", n); naslovi[j].setAttribute("title", n); }
    }
    var polja = document.querySelectorAll("#zaslonDaljinec [data-d-placeholder]");
    for (var p = 0; p < polja.length; p++) {
      var ph = t(polja[p].getAttribute("data-d-placeholder"));
      if (ph) polja[p].setAttribute("placeholder", ph);
    }
  }

  // ----------------------------------------------------------------
  // Stanje
  // ----------------------------------------------------------------

  var stanje = {
    naprava: null,          // {id, ime, zmoznosti}
    odprt: false,
    cakajoci: {},           // ref -> {cb, dejanje, timer}
    aplikacije: [],         // [{package, label, icon}]
    aplikacijeZa: "",       // id naprave, za katero velja seznam
    glasnost: null,
    utisano: false,
    samodejno: false,
    posnetekTimer: null,
    poslusam: false,
    zadnjeSliseno: ""
  };
  var stevec = 0;

  function pokazi(id, vidno) { var e = el(id); if (e) e.hidden = !vidno; }
  function besedilo(id, niz) { var e = el(id); if (e) e.textContent = niz == null ? "" : String(niz); }

  function znaGovor() {
    try { return !!(most && most.znaGovor && most.znaGovor()); } catch (e) { return false; }
  }

  // ----------------------------------------------------------------
  // Posiljanje ukazov
  // ----------------------------------------------------------------

  function ukaz(dejanje, parametri, cb, casDoOdgovora, drugaNaprava) {
    var n = drugaNaprava || stanje.naprava;
    if (!n || !most || !most.ukaz) {
      if (cb) cb({ ok: false, message: t("niPovezave") });
      return null;
    }
    var ref = "d" + Date.now().toString(36) + "-" + (++stevec);
    var vnos = { cb: cb || null, dejanje: dejanje, timer: null };
    vnos.timer = setTimeout(function () {
      if (stanje.cakajoci[ref]) {
        delete stanje.cakajoci[ref];
        if (cb) cb({ ok: false, message: t("brezOdgovora"), casovna: true });
      }
    }, casDoOdgovora || 8000);
    stanje.cakajoci[ref] = vnos;
    try {
      most.ukaz(n.id, dejanje, JSON.stringify(parametri || {}), ref);
    } catch (e) {
      clearTimeout(vnos.timer);
      delete stanje.cakajoci[ref];
      if (cb) cb({ ok: false, message: t("niPovezave") });
    }
    return ref;
  }

  /** Odgovor naprave (control.result) ali zavrnitev sredisca (control.ack). */
  function odziv(podatki) {
    if (!podatki) return;
    var ref = String(podatki.ref || "");
    var vnos = stanje.cakajoci[ref];
    if (!vnos) return;
    clearTimeout(vnos.timer);
    delete stanje.cakajoci[ref];
    if (vnos.cb) vnos.cb(podatki);
  }

  /** Stabilne oznake napak z naprave v jezik uporabnika; besedilo naprave je le rezerva. */
  function prevodKode(koda, rezerva) {
    var prevodi = {
      ni_v_ospredju: t("niVOspredju"), neznano_dejanje: t("brezOdgovora"),
      ni_namescena: t("aplikacijaNiNamescena", { ime: rezerva.replace(/^Aplikacija\s+/, "").replace(/\s+ni namescena$/, "") }),
      ni_na_racunalniku: t("niNaRacunalniku"), naprava_ni_povezana: t("niPovezave")
    };
    return prevodi[koda] || rezerva;
  }

  // Kratek utrip na tipki, da uporabnik vidi, da je dotik prijel, tudi ce naprava odgovori pozneje.
  function utrip(gumb) {
    if (!gumb) return;
    gumb.classList.add("utrip");
    setTimeout(function () { gumb.classList.remove("utrip"); }, 180);
  }

  function pokaziOdgovor(izid, uspehBesedilo) {
    var o = el("daljinecOdgovor");
    if (!o) return;
    var ok = !!(izid && izid.ok);
    var niz = ok ? (uspehBesedilo || (izid && izid.message) || t("poslano")) : ((izid && izid.message) || t("brezOdgovora"));
    if (!ok && izid && izid.koda) niz = prevodKode(izid.koda, niz);
    o.textContent = niz;
    o.classList.toggle("napaka", !ok);
    var s = el("daljinecStanje");
    if (s && !ok) { s.textContent = niz; s.classList.add("napaka"); }
    else if (s && ok && s.classList.contains("napaka")) { s.classList.remove("napaka"); osveziStanje(); }
    if (ok && stanje.samodejno) nacrtujPosnetek(900);
  }

  // ----------------------------------------------------------------
  // Tipke, glasnost, drsenje
  // ----------------------------------------------------------------

  function tipka(ime, gumb) {
    utrip(gumb);
    ukaz("key", { key: ime }, function (izid) { pokaziOdgovor(izid, t("tipka", { ime: imeTipke(ime) })); });
  }

  function imeTipke(ime) {
    var imena = { home: t("domov"), back: t("nazaj"), play_pause: t("predvajaj"), pause: t("pavza"), menu: t("meni"),
      previous: t("prejsnji"), next: t("naslednji"), up: "↑", down: "↓", left: "←", right: "→", ok: "OK" };
    return imena[ime] || ime;
  }

  function drsenje(smer, gumb) {
    utrip(gumb);
    ukaz("scroll", { direction: smer }, function (izid) { pokaziOdgovor(izid, smer === "up" ? t("stranGor") : t("stranDol")); });
  }

  function glasnost(parametri, gumb) {
    utrip(gumb);
    ukaz("volume", parametri, function (izid) {
      if (izid && izid.ok && izid.data) narisiGlasnost(izid.data);
      var opis = parametri.direction === "up" ? t("glasneje") : parametri.direction === "down" ? t("tisje")
        : parametri.level != null ? t("glasnostNa", { n: parametri.level })
        : (izid && izid.data && izid.data.muted ? t("utisano") : t("zvokNazaj"));
      pokaziOdgovor(izid, opis);
    });
  }

  function narisiGlasnost(podatki) {
    if (!podatki) return;
    if (typeof podatki.level === "number") stanje.glasnost = podatki.level;
    stanje.utisano = !!podatki.muted;
    var raven = el("daljinecGlasnostRaven");
    if (raven) raven.style.height = (stanje.glasnost == null ? 0 : Math.max(0, Math.min(100, stanje.glasnost))) + "%";
    besedilo("daljinecGlasnostVrednost", stanje.glasnost == null ? "–" : (stanje.utisano ? "🔇" : stanje.glasnost + "%"));
    var u = document.querySelector("#daljinecGlasnost .utisaj");
    if (u) u.classList.toggle("utisano", stanje.utisano);
  }

  // ----------------------------------------------------------------
  // Aplikacije
  // ----------------------------------------------------------------

  function naloziAplikacije(prisilno) {
    var n = stanje.naprava;
    if (!n) return;
    if (!prisilno && stanje.aplikacijeZa === n.id && stanje.aplikacije.length) { narisiAplikacije(); return; }
    besedilo("daljinecAplikacijeOpomba", t("nalagamAplikacije"));
    ukaz("apps", { icons: true }, function (izid) {
      if (izid && izid.ok && izid.data && izid.data.apps) {
        stanje.aplikacije = izid.data.apps;
        stanje.aplikacijeZa = n.id;
        besedilo("daljinecAplikacijeOpomba", "");
        narisiAplikacije();
        try { window.localStorage.setItem("safeer_daljinec_apps_" + n.id, JSON.stringify(stanje.aplikacije.map(function (a) { return { package: a.package, label: a.label }; }))); } catch (e) {}
      } else {
        // Brez seznama (npr. racunalnik): plosco aplikacij skrijemo, ne kazemo napake.
        var podprto = !(izid && /ni na voljo|not available|unsupported|Neznano dejanje/i.test(izid.message || ""));
        stanje.aplikacije = [];
        narisiAplikacije();
        besedilo("daljinecAplikacijeOpomba", podprto ? ((izid && izid.message) || t("brezAplikacij")) : "");
        pokazi("daljinecAplikacijePanel", podprto);
      }
    }, 15000);
  }

  function narisiAplikacije() {
    var mreza = el("daljinecAplikacije");
    if (!mreza) return;
    mreza.innerHTML = "";
    pokazi("daljinecAplikacijePanel", true);
    stanje.aplikacije.forEach(function (a) {
      var gumb = document.createElement("button");
      gumb.className = "aplikacija";
      gumb.setAttribute("data-paket", a.package);
      gumb.setAttribute("title", a.label || a.package);
      if (a.icon) {
        var slika = document.createElement("img");
        slika.alt = "";
        slika.src = a.icon;
        gumb.appendChild(slika);
      } else {
        var znak = document.createElement("span");
        znak.className = "aplikacijaZnak";
        znak.textContent = (a.label || a.package || "?").trim().charAt(0).toUpperCase();
        gumb.appendChild(znak);
      }
      var ime = document.createElement("span");
      ime.textContent = a.label || a.package;
      gumb.appendChild(ime);
      gumb.addEventListener("click", function () { zazeniAplikacijo(a, gumb); });
      mreza.appendChild(gumb);
    });
  }

  function zazeniAplikacijo(a, gumb) {
    utrip(gumb);
    ukaz("launch_app", { package: a.package }, function (izid) { pokaziOdgovor(izid, t("odpiram", { ime: a.label || a.package })); });
  }

  /** Aplikacija po (delu) imena: "youtube" najde YouTube, "brskalnik" Safeer. */
  function najdiAplikacijo(ime) {
    var iskano = normaliziraj(ime);
    if (!iskano) return null;
    var kandidati = stanje.aplikacije.slice();
    // Sopomenke, ki jih ljudje uporabljajo namesto pravega imena aplikacije (prva ima prednost).
    var sopomenke = {
      "youtube": ["youtube"],
      "jutub": ["youtube"], "yutub": ["youtube"], "yt": ["youtube"],
      "brskalnik": ["safeer", "browser", "chrome"], "browser": ["safeer", "browser"],
      "navegador": ["safeer", "browser"], "navigateur": ["safeer", "browser"],
      "filmi": ["netflix"], "filme": ["netflix"],
      "serije": ["netflix"], "movies": ["netflix"],
      "televizija": ["xplore", "live tv", "tv"], "televizijo": ["xplore", "live tv", "tv"], "tv v zivo": ["xplore", "live tv"],
      "fernsehen": ["xplore", "live tv", "tv"], "television": ["xplore", "live tv", "tv"], "televisione": ["xplore", "live tv", "tv"],
      "glasba": ["spotify", "youtube music"], "music": ["spotify", "youtube music"],
      "nastavitve": ["settings", "nastavitve"], "settings": ["settings"], "einstellungen": ["settings"]
    };
    // Sopomenke imajo prednost pred dobesednim imenom ("jutub" -> YouTube).
    // Kar nima sopomenk, se isce dobesedno.
    var iskanja = (sopomenke[iskano] || [iskano]).slice();
    if (iskanja.indexOf(iskano) < 0) iskanja.push(iskano);
    // Tocke: tocno ime > ime se zacne z iskanim > iskano je beseda v imenu > del imena (>= 4 znaki)
    // > del paketa (>= 5 znakov, po segmentih, da "yt" ne najde "playtv"). Zgodnejsa sopomenka
    // ima prednost pred poznejso pri enakih tockah.
    var najboljsa = null, najTocke = 0;
    for (var i = 0; i < iskanja.length; i++) {
      var q = normaliziraj(iskanja[i]);
      if (!q) continue;
      var qBrez = q.replace(/\s+/g, "");
      for (var j = 0; j < kandidati.length; j++) {
        var oznaka = normaliziraj(kandidati[j].label || "");
        var oznakaBrez = oznaka.replace(/\s+/g, "");
        var segmenti = String(kandidati[j].package || "").toLowerCase().split(".");
        var tocke = 0;
        if (oznaka === q || oznakaBrez === qBrez) tocke = 100;
        else if (oznaka.indexOf(q + " ") === 0 || oznakaBrez.indexOf(qBrez) === 0 && qBrez.length >= 3) tocke = 80;
        else if ((" " + oznaka + " ").indexOf(" " + q + " ") >= 0) tocke = 70;
        else if (q.length >= 4 && oznakaBrez.indexOf(qBrez) >= 0) tocke = 50;
        else if (qBrez.length >= 5 && segmenti.some(function (seg) { return seg === qBrez || seg.indexOf(qBrez) >= 0; })) tocke = 30;
        if (tocke > najTocke) { najTocke = tocke; najboljsa = kandidati[j]; }
      }
      if (najboljsa && najTocke >= 70) break;
    }
    return najboljsa;
  }

  function normaliziraj(niz) {
    return String(niz || "").toLowerCase()
      .replace(/[čć]/g, "c").replace(/š/g, "s").replace(/ž/g, "z").replace(/đ/g, "d")
      .replace(/[àáâä]/g, "a").replace(/[èéêë]/g, "e").replace(/[ìíîï]/g, "i").replace(/[òóôö]/g, "o").replace(/[ùúûü]/g, "u").replace(/ß/g, "ss").replace(/ñ/g, "n")
      .replace(/[^a-z0-9 .\-\/:_]+/g, " ").replace(/\s+/g, " ").trim();
  }

  // ----------------------------------------------------------------
  // Glas: slovnica ukazov (brez oblaka)
  // ----------------------------------------------------------------

  /** Glagol x predmet: "ustavi" + "glasbo" -> "ustavi glasbo" (pavza in nadaljevanje s predmetom). */
  function kombinacije(prvi, drugi) {
    var izid = [];
    for (var i = 0; i < prvi.length; i++) for (var j = 0; j < drugi.length; j++) izid.push(prvi[i] + " " + drugi[j]);
    return izid;
  }

  /**
   * Besede po jezikih. Vsak vnos je seznam korenov; ujemanje je po celih besedah.
   * Oznaki pri frazah za glasnost: "^fraza$" velja samo kot cel ukaz, "fraza$" samo na koncu stavka
   * ("sound on" ne sme ujeti "turn off the sound on the phone").
   * naprave: fraze o napravi; "na" kjerkoli v stavku, "gola" (tozilnik: "utisaj televizor") samo ob kratkem ukazu.
   */
  var SLOVNICA = {
    sl: {
      glasneje: ["glasneje", "glasnejse", "povecaj glasnost", "zvisaj glasnost", "bolj naglas", "naglas", "glasnost gor"],
      tisje: ["tisje", "tise", "zmanjsaj glasnost", "znizaj glasnost", "stisaj", "manj naglas", "glasnost dol", "bolj tiho"],
      utisaj: ["utisaj", "brez zvoka", "tiho", "izklopi zvok", "ugasni zvok", "izkljuci zvok", "zvok izklopi", "zvok ugasni", "zvok izkljuci",
        "izklopi ton", "ugasni ton"],
      zvok: ["vklopi zvok", "odkleni zvok", "zvok nazaj", "prizgi zvok", "vkljuci zvok", "zvok vklopi", "zvok prizgi", "zvok vkljuci"],
      glasnostNa: ["glasnost na", "glasnost", "zvok na"],
      domov: ["domov", "domaca stran", "zacetna stran", "na zacetek"],
      nazaj: ["nazaj", "zapri", "pojdi nazaj"],
      meni: ["meni"],
      gor: ["gor", "navzgor"], dol: ["dol", "navzdol"], levo: ["levo"], desno: ["desno"], ok: ["potrdi", "ok", "v redu", "izberi", "klikni"],
      predvajaj: ["predvajaj", "nadaljuj", "play", "nadaljuj predvajanje", "nadaljuj s predvajanjem", "nadaljuj z predvajanjem"]
        .concat(kombinacije(["nadaljuj"], ["glasbo", "video", "film", "posnetek", "pesem", "serijo", "oddajo"])),
      // "daj na pavzo": "daj" je mesalo, ostane "na pavzo".
      pavza: ["pavza", "ustavi", "stop", "pocakaj", "premor", "na pavzo", "pavzo", "daj na pavzo", "daj pavzo", "pavziraj", "zaustavi"]
        .concat(kombinacije(["ustavi", "zaustavi", "pavziraj", "prekini", "pavza"], ["glasbo", "predvajanje", "video", "posnetek", "film", "pesem", "serijo", "oddajo", "to"])),
      naslednji: ["naslednji", "naprej"], prejsnji: ["prejsnji", "prejsnja"],
      stranGor: ["stran gor", "drsi gor", "pomakni gor"], stranDol: ["stran dol", "drsi dol", "pomakni dol", "listaj"],
      odpri: ["odpri", "zazeni", "zagni", "pokazi", "prikazi", "vklopi", "prizgi", "pojdi na"],
      program: ["program", "kanal", "preklopi na"],
      youtube: ["youtube", "jutub", "yutub"],
      predvajajGlasbo: ["predvajaj", "zavrti", "poslusaj", "poslusal bi", "pesem", "komad", "glasbo", "glasba"],
      poisci: ["poisci", "isci", "najdi", "google", "kdo je", "kaj je", "kje je", "vreme"],
      posnetek: ["posnetek", "kaj je na zaslonu", "pokazi zaslon", "zaslon"],
      znovaZazeni: ["znova zazeni", "ponovno zazeni", "restart"], pocisti: ["pocisti predpomnilnik", "pocisti", "sprosti pomnilnik"],
      stran: ["stran", "povezavo", "naslov"],
      polnilaNaslova: ["stran", "spletno stran", "spletna stran", "spletni strani", "spletisce", "povezavo", "povezava", "naslov", "domeno"],
      pika: ["pika"],
      naprave: {
        tv: { na: ["na televizorju", "na televiziji", "na tv", "na tv-ju", "na tvju", "na televizor"], gola: ["televizor", "televizijo", "tv"] },
        tablica: { na: ["na tablici", "na tabletu", "na ipadu", "na tablico", "na tablet"], gola: ["tablico", "tablica", "tablet"] },
        telefon: { na: ["na telefonu", "na mobitelu", "na mobiju", "na pametnem telefonu", "na telefon", "na mobitel"], gola: ["telefon", "mobitel", "mobi"] },
        racunalnik: { na: ["na racunalniku", "na pc", "na pc-ju", "na pcju", "na prenosniku", "na prenosnem racunalniku", "na laptopu", "na racunalnik", "na prenosnik"],
          gola: ["racunalnik", "pc", "prenosnik", "laptop"] }
      },
      mesalo: ["prosim", "na napravi", "lahko", "mi", "daj"]
    },
    en: {
      glasneje: ["louder", "volume up", "turn it up", "increase volume", "raise volume", "turn up the volume", "turn the volume up",
        "turn up the sound", "turn the sound up", "raise the volume", "increase the volume", "^turn up$"],
      tisje: ["quieter", "volume down", "turn it down", "decrease volume", "lower volume", "softer", "turn down the volume", "turn the volume down",
        "turn down the sound", "turn the sound down", "lower the volume", "decrease the volume", "reduce the volume", "^turn down$"],
      utisaj: ["mute", "silence", "no sound", "sound off", "turn off the sound", "turn the sound off", "switch off the sound", "switch the sound off",
        "turn off sound", "turn sound off", "turn off the audio", "turn the audio off"],
      zvok: ["unmute", "sound back", "sound on$", "turn on the sound", "turn the sound on$", "switch on the sound", "switch the sound on$",
        "turn on sound", "turn sound on$", "turn on the audio", "turn the audio on$"],
      glasnostNa: ["volume to", "volume", "set volume"],
      domov: ["home", "home screen", "start page"],
      nazaj: ["back", "go back", "close"],
      meni: ["menu"],
      gor: ["up"], dol: ["down"], levo: ["left"], desno: ["right"], ok: ["ok", "select", "confirm", "enter", "click"],
      predvajaj: ["play", "resume", "continue", "unpause", "resume playback", "continue playback", "continue playing", "keep playing"]
        .concat(kombinacije(["resume"], ["it", "the music", "music", "the video", "the movie", "the film", "the song", "the show"])),
      pavza: ["pause", "stop", "hold"]
        .concat(kombinacije(["pause", "stop", "hold"], ["it", "this", "that", "the music", "music", "the video", "video", "playback", "the playback",
          "the movie", "the film", "the song", "the show", "the stream"])),
      naslednji: ["next", "skip"], prejsnji: ["previous", "back one"],
      stranGor: ["page up", "scroll up"], stranDol: ["page down", "scroll down", "scroll"],
      odpri: ["open", "launch", "start", "show", "run", "go to"],
      program: ["channel", "program", "switch to"],
      youtube: ["youtube"],
      predvajajGlasbo: ["play", "listen to", "put on", "song", "music"],
      poisci: ["search for", "search", "find", "google", "look up", "who is", "what is", "where is", "weather"],
      posnetek: ["screenshot", "show screen", "what is on the screen", "screen"],
      znovaZazeni: ["restart", "reboot safeer"], pocisti: ["clear cache", "clean cache", "free memory"],
      stran: ["page", "link", "website", "url"],
      polnilaNaslova: ["the website", "the web site", "the site", "the page", "the web page", "the webpage", "the url", "the link", "the address",
        "website", "web site", "site", "page", "web page", "webpage", "url", "link", "address"],
      pika: ["dot"],
      naprave: {
        tv: { na: ["on the tv", "on tv", "on my tv", "on the television", "on the telly", "on the tv set", "to the tv", "to my tv", "to the television"], gola: ["the tv", "my tv", "tv", "the television", "the telly"] },
        tablica: { na: ["on the tablet", "on my tablet", "on tablet", "on the ipad", "on my ipad", "to the tablet", "to my tablet"], gola: ["the tablet", "my tablet", "tablet", "the ipad"] },
        telefon: { na: ["on the phone", "on my phone", "on phone", "on the mobile", "on my mobile", "on the smartphone", "on my smartphone",
          "on the cell phone", "on my cell phone", "on the iphone", "on my iphone", "to the phone", "to my phone"], gola: ["the phone", "my phone", "phone", "the mobile", "the smartphone"] },
        racunalnik: { na: ["on the computer", "on my computer", "on computer", "on the pc", "on my pc", "on pc", "on the laptop", "on my laptop", "to the computer", "to my computer", "to the pc", "to the laptop"],
          gola: ["the computer", "my computer", "computer", "the pc", "my pc", "pc", "the laptop", "my laptop", "laptop"] }
      },
      mesalo: ["please", "on the device", "can you", "could you", "for me"]
    },
    de: {
      glasneje: ["lauter", "lautstarke hoch", "lautstarke erhohen"], tisje: ["leiser", "lautstarke runter", "lautstarke verringern"],
      utisaj: ["stumm", "ton aus", "kein ton", "ton ausschalten", "ton abschalten", "stummschalten", "stumm schalten", "mute", "ton ab$"],
      zvok: ["ton an$", "ton wieder", "laut schalten", "ton einschalten", "ton anschalten", "ton wieder an", "stummschaltung aufheben", "stummschaltung aus", "stumm aus"],
      glasnostNa: ["lautstarke auf", "lautstarke"],
      domov: ["start", "startseite", "home", "zuhause"], nazaj: ["zuruck", "schliessen"], meni: ["menu"],
      gor: ["hoch", "nach oben", "oben"], dol: ["runter", "nach unten", "unten"], levo: ["links"], desno: ["rechts"], ok: ["ok", "auswahlen", "bestatigen", "klicken"],
      predvajaj: ["abspielen", "weiter", "wiedergabe", "play", "fortsetzen", "fortfahren", "setze fort", "weiterspielen", "weiter abspielen"]
        .concat(kombinacije(["wiedergabe", "musik", "video", "film"], ["fortsetzen"])),
      pavza: ["pause", "stopp", "stop", "anhalten", "pausieren", "pausiere", "stoppe", "stoppen", "halt an", "halte an", "mach pause", "pause machen"]
        .concat(kombinacije(["pausiere", "stoppe", "stopp", "stop"], ["die musik", "musik", "das video", "video", "die wiedergabe", "wiedergabe", "den film", "film", "das lied", "es"]))
        .concat(kombinacije(["die musik", "musik", "das video", "video", "die wiedergabe", "wiedergabe", "den film", "film", "das lied"], ["pausieren", "stoppen", "anhalten"]))
        .concat(kombinacije(["halte", "halt"], ["die musik an", "das video an", "die wiedergabe an", "den film an", "das lied an", "es an"])),
      naslednji: ["nachster", "nachste", "weiter"], prejsnji: ["vorheriger", "vorherige", "zuruck"],
      stranGor: ["seite hoch", "nach oben scrollen"], stranDol: ["seite runter", "nach unten scrollen", "scrollen"],
      odpri: ["offne", "offnen", "starte", "starten", "zeige", "zeig", "mach", "geh zu"],
      program: ["programm", "kanal", "sender", "schalte auf", "umschalten auf"],
      youtube: ["youtube"], predvajajGlasbo: ["spiel", "spiele", "abspielen", "lied", "musik", "hore"],
      poisci: ["suche nach", "suche", "such", "finde", "google", "wer ist", "was ist", "wo ist", "wetter"],
      posnetek: ["screenshot", "bildschirm zeigen", "bildschirm"],
      znovaZazeni: ["neu starten", "neustart"], pocisti: ["cache leeren", "speicher freigeben"],
      stran: ["seite", "link", "webseite"],
      polnilaNaslova: ["die seite", "die webseite", "die website", "die internetseite", "seite", "webseite", "website", "internetseite", "den link", "link", "die adresse", "adresse"],
      pika: ["punkt"],
      naprave: {
        tv: { na: ["auf dem fernseher", "am fernseher", "im fernseher", "auf dem tv", "am tv", "im tv", "auf meinem fernseher", "auf den fernseher", "an den fernseher"], gola: ["den fernseher", "fernseher", "den tv", "das tv", "tv"] },
        tablica: { na: ["auf dem tablet", "am tablet", "auf meinem tablet", "auf dem ipad", "am ipad", "aufs tablet", "auf das tablet"], gola: ["das tablet", "tablet", "das ipad"] },
        telefon: { na: ["auf dem handy", "am handy", "auf meinem handy", "auf dem telefon", "am telefon", "auf dem smartphone", "am smartphone", "aufs handy", "auf das handy"],
          gola: ["das handy", "handy", "das telefon", "das smartphone"] },
        racunalnik: { na: ["auf dem computer", "am computer", "auf meinem computer", "auf dem pc", "am pc", "auf dem laptop", "am laptop", "auf dem rechner", "am rechner", "auf den computer", "auf den pc"],
          gola: ["den computer", "computer", "den pc", "pc", "den laptop", "laptop", "den rechner"] }
      },
      mesalo: ["bitte", "kannst du", "mir"]
    },
    es: {
      glasneje: ["mas alto", "sube el volumen", "subir volumen", "mas volumen", "sube el sonido", "subir el volumen"],
      tisje: ["mas bajo", "baja el volumen", "bajar volumen", "menos volumen", "baja el sonido", "bajar el volumen"],
      utisaj: ["silencio", "silenciar", "silencia", "sin sonido", "mute", "quita el sonido", "quitar el sonido", "apaga el sonido", "apagar el sonido", "apaga el audio", "quita el audio", "sin audio",
        "desactiva el sonido", "desactivar el sonido"],
      zvok: ["activar sonido", "^sonido$", "quitar silencio", "quita el silencio", "quitar el silencio", "activa el sonido", "activar el sonido",
        "pon el sonido", "enciende el sonido"],
      glasnostNa: ["volumen a", "volumen al", "volumen"],
      domov: ["inicio", "pantalla de inicio", "casa"], nazaj: ["atras", "volver", "cerrar"], meni: ["menu"],
      gor: ["arriba"], dol: ["abajo"], levo: ["izquierda"], desno: ["derecha"], ok: ["ok", "seleccionar", "confirmar", "aceptar", "clic"],
      predvajaj: ["reproducir", "reproduce", "continuar", "play", "reanudar", "reanuda", "quita la pausa", "quitar la pausa"]
        .concat(kombinacije(["reanudar", "reanuda", "continuar", "continua"], ["la reproduccion", "la musica", "el video", "la pelicula"])),
      pavza: ["pausa", "pausar", "parar", "detener", "stop", "para", "deten", "pon en pausa", "ponlo en pausa", "en pausa", "pausalo", "detenlo", "paralo"]
        .concat(kombinacije(["pausa", "pausar", "para", "parar", "deten", "detener", "pon en pausa"], ["la musica", "musica", "el video", "video",
          "la reproduccion", "reproduccion", "la pelicula", "pelicula", "la cancion", "esto"]))
        .concat(kombinacije(["pon"], ["la musica en pausa", "el video en pausa", "la reproduccion en pausa", "la pelicula en pausa"])),
      naslednji: ["siguiente"], prejsnji: ["anterior"],
      stranGor: ["pagina arriba", "subir"], stranDol: ["pagina abajo", "bajar", "desplazar"],
      odpri: ["abre", "abrir", "inicia", "iniciar", "lanza", "muestra", "muestrame", "ensename", "abreme", "ve a"],
      program: ["canal", "programa", "cambia a", "pon el"],
      youtube: ["youtube"], predvajajGlasbo: ["pon", "reproduce", "escuchar", "cancion", "musica"],
      poisci: ["busca", "buscar", "encuentra", "google", "quien es", "que es", "donde esta", "tiempo"],
      posnetek: ["captura", "muestra la pantalla", "pantalla"],
      znovaZazeni: ["reiniciar", "reinicia"], pocisti: ["borrar cache", "limpiar cache", "liberar memoria"],
      stran: ["pagina", "enlace", "web"],
      polnilaNaslova: ["la pagina", "la pagina web", "la web", "el sitio", "el sitio web", "pagina", "pagina web", "web", "sitio", "sitio web", "el enlace", "enlace", "la direccion"],
      pika: ["punto"],
      naprave: {
        tv: { na: ["en la tele", "en la television", "en el televisor", "en la tv", "en el tv", "en mi tele", "en mi televisor", "al televisor", "a la tele"], gola: ["la tele", "la television", "el televisor", "televisor", "la tv", "tv"] },
        tablica: { na: ["en la tableta", "en la tablet", "en el tablet", "en mi tableta", "en el ipad", "a la tableta"], gola: ["la tableta", "tableta", "la tablet"] },
        telefon: { na: ["en el telefono", "en mi telefono", "en el movil", "en mi movil", "en el celular", "en mi celular", "al movil", "al telefono"], gola: ["el telefono", "el movil", "movil", "el celular"] },
        racunalnik: { na: ["en el ordenador", "en mi ordenador", "en el computador", "en la computadora", "en mi computadora", "en el pc", "en mi pc", "en el portatil", "al ordenador"],
          gola: ["el ordenador", "ordenador", "la computadora", "el pc", "pc", "el portatil"] }
      },
      mesalo: ["por favor", "puedes", "me"]
    },
    fr: {
      glasneje: ["plus fort", "monte le son", "augmente le volume", "monter le volume", "monte le volume", "augmente le son"],
      tisje: ["moins fort", "baisse le son", "baisser le volume", "diminue le volume", "baisse le volume", "diminue le son"],
      utisaj: ["muet", "coupe le son", "sans son", "silence", "couper le son", "eteins le son", "eteindre le son", "desactive le son", "mets en sourdine", "sourdine", "mode muet"],
      zvok: ["remets le son", "^son$", "active le son", "activer le son", "rallume le son", "remettre le son", "retablis le son", "enleve la sourdine", "^le son$"],
      glasnostNa: ["volume a", "volume"],
      domov: ["accueil", "page d accueil", "maison"], nazaj: ["retour", "reviens", "ferme"], meni: ["menu"],
      gor: ["haut", "en haut"], dol: ["bas", "en bas"], levo: ["gauche"], desno: ["droite"], ok: ["ok", "valide", "confirme", "selectionne", "clique"],
      predvajaj: ["lecture", "lire", "joue", "continue", "play", "reprendre", "reprends", "enleve la pause"]
        .concat(kombinacije(["reprends", "reprendre", "relance"], ["la lecture", "la musique", "la video", "le film"])),
      pavza: ["pause", "arrete", "stop", "mets en pause", "mets pause", "en pause", "stoppe"]
        .concat(kombinacije(["pause", "mets en pause", "arrete", "stoppe", "stop", "interromps"], ["la musique", "musique", "la video", "video",
          "la lecture", "lecture", "le film", "film", "la chanson", "ca"]))
        .concat(kombinacije(["mets"], ["la musique en pause", "la video en pause", "la lecture en pause", "le film en pause", "ca en pause"])),
      naslednji: ["suivant", "suivante"], prejsnji: ["precedent", "precedente"],
      stranGor: ["page haut", "defiler vers le haut", "monte"], stranDol: ["page bas", "defiler vers le bas", "descends", "defile"],
      odpri: ["ouvre", "ouvrir", "lance", "lancer", "demarre", "montre", "montre-moi", "montre moi", "ouvre-moi", "va sur", "va a"],
      program: ["chaine", "programme", "passe sur", "mets la"],
      youtube: ["youtube"], predvajajGlasbo: ["joue", "mets", "ecouter", "ecoute", "chanson", "musique"],
      poisci: ["cherche", "recherche", "trouve", "google", "qui est", "qu est ce que", "ou est", "meteo"],
      posnetek: ["capture", "montre l ecran", "ecran"],
      znovaZazeni: ["redemarre", "redemarrer"], pocisti: ["vide le cache", "vider le cache", "libere la memoire"],
      stran: ["page", "lien", "site"],
      polnilaNaslova: ["le site", "le site web", "la page", "la page web", "site", "site web", "page", "le lien", "lien", "l adresse", "adresse"],
      pika: ["point"],
      naprave: {
        tv: { na: ["sur la tele", "sur la television", "sur le televiseur", "sur la tv", "a la tele", "a la television", "sur ma tele", "vers la tele"], gola: ["la tele", "la television", "le televiseur", "televiseur", "la tv", "tv"] },
        tablica: { na: ["sur la tablette", "sur ma tablette", "sur l ipad", "vers la tablette"], gola: ["la tablette", "tablette", "l ipad"] },
        telefon: { na: ["sur le telephone", "sur mon telephone", "sur le portable", "sur mon portable", "sur le mobile", "sur mon mobile", "sur le smartphone", "vers le telephone"],
          gola: ["le telephone", "telephone", "le portable", "le mobile", "le smartphone"] },
        racunalnik: { na: ["sur l ordinateur", "sur mon ordinateur", "sur l ordi", "sur mon ordi", "sur le pc", "sur mon pc", "vers l ordinateur"],
          gola: ["l ordinateur", "ordinateur", "l ordi", "le pc", "pc"] }
      },
      mesalo: ["s il te plait", "s il vous plait", "peux tu", "moi"]
    },
    it: {
      glasneje: ["piu forte", "alza il volume", "aumenta il volume", "piu alto", "alza l audio", "alza il suono"],
      tisje: ["piu piano", "abbassa il volume", "diminuisci il volume", "piu basso", "abbassa l audio", "abbassa il suono"],
      utisaj: ["muto", "silenzio", "togli l audio", "senza audio", "spegni l audio", "disattiva l audio", "togli il suono", "spegni il suono", "metti in muto", "silenzia",
        "disattiva il suono", "spegni audio", "togli audio", "disattiva audio"],
      zvok: ["riattiva l audio", "^audio$", "rimetti l audio", "attiva l audio", "accendi l audio", "riattiva il suono", "togli il muto", "togli muto"],
      glasnostNa: ["volume a", "volume al", "volume"],
      domov: ["home", "pagina iniziale", "inizio", "casa"], nazaj: ["indietro", "torna", "chiudi"], meni: ["menu"],
      gor: ["su", "sopra"], dol: ["giu", "sotto"], levo: ["sinistra"], desno: ["destra"], ok: ["ok", "conferma", "seleziona", "clicca", "invio"],
      predvajaj: ["riproduci", "continua", "play", "riprendi", "togli la pausa"]
        .concat(kombinacije(["riprendi", "continua"], ["la riproduzione", "la musica", "il video", "il film"])),
      pavza: ["pausa", "ferma", "stop", "metti in pausa", "in pausa", "interrompi", "fermalo", "metti pausa"]
        .concat(kombinacije(["metti in pausa", "pausa", "ferma", "stoppa", "interrompi", "blocca"], ["la musica", "musica", "il video", "video",
          "la riproduzione", "riproduzione", "il film", "film", "la canzone"]))
        .concat(kombinacije(["metti"], ["la musica in pausa", "il video in pausa", "la riproduzione in pausa", "il film in pausa"])),
      naslednji: ["successivo", "prossimo", "avanti"], prejsnji: ["precedente"],
      stranGor: ["pagina su", "scorri su"], stranDol: ["pagina giu", "scorri giu", "scorri"],
      odpri: ["apri", "aprire", "avvia", "lancia", "mostra", "mostrami", "fammi vedere", "aprimi", "vai su", "vai a"],
      program: ["canale", "programma", "metti il", "passa a"],
      youtube: ["youtube"], predvajajGlasbo: ["metti", "riproduci", "ascolta", "ascoltare", "canzone", "musica"],
      poisci: ["cerca", "trova", "google", "chi e", "cos e", "dov e", "meteo"],
      posnetek: ["screenshot", "mostra lo schermo", "schermo"],
      znovaZazeni: ["riavvia", "riavviare"], pocisti: ["svuota la cache", "pulisci la cache", "libera la memoria"],
      stran: ["pagina", "link", "sito"],
      polnilaNaslova: ["il sito", "il sito web", "la pagina", "la pagina web", "sito", "sito web", "pagina", "il link", "link", "l indirizzo", "indirizzo"],
      pika: ["punto"],
      naprave: {
        tv: { na: ["sulla tv", "sul televisore", "in tv", "alla tv", "sulla televisione", "in televisione", "sulla mia tv"], gola: ["la tv", "tv", "il televisore", "televisore", "la televisione"] },
        tablica: { na: ["sul tablet", "sul mio tablet", "sull ipad", "al tablet"], gola: ["il tablet", "tablet", "l ipad"] },
        telefon: { na: ["sul telefono", "sul mio telefono", "sul cellulare", "sul mio cellulare", "sul telefonino", "sullo smartphone", "al telefono", "al cellulare"],
          gola: ["il telefono", "telefono", "il cellulare", "cellulare", "il telefonino", "lo smartphone"] },
        racunalnik: { na: ["sul computer", "sul mio computer", "sul pc", "sul mio pc", "sul portatile", "al computer"], gola: ["il computer", "computer", "il pc", "pc", "il portatile"] }
      },
      mesalo: ["per favore", "puoi", "mi"]
    }
  };

  function slovnica() { return SLOVNICA[jezik] || SLOVNICA.en; }

  function odrezi(niz, predpone) {
    // Vrne ostanek za prvo predpono, ki se ujema na zacetku (najdaljsa najprej), ali null.
    var urejene = predpone.slice().sort(function (a, b) { return b.length - a.length; });
    for (var i = 0; i < urejene.length; i++) {
      var p = urejene[i];
      if (niz === p) return "";
      if (niz.indexOf(p + " ") === 0) return niz.slice(p.length + 1).trim();
    }
    return null;
  }

  /** Ali je fraza v nizu kot cele besede; "^fraza$" samo kot cel niz, "fraza$" samo na koncu. */
  function ujemaFrazo(niz, b) {
    var zacetek = b.charAt(0) === "^", konec = b.charAt(b.length - 1) === "$";
    var f = b.slice(zacetek ? 1 : 0, konec ? b.length - 1 : b.length);
    if (niz === f) return true;
    if (zacetek && konec) return false;
    var naKoncu = niz.slice(-(f.length + 1)) === " " + f;
    if (konec) return naKoncu;
    return niz.indexOf(f + " ") === 0 || niz.indexOf(" " + f + " ") >= 0 || naKoncu;
  }

  function vsebuje(niz, besede) {
    for (var i = 0; i < besede.length; i++) if (ujemaFrazo(niz, besede[i])) return true;
    return false;
  }

  /** Dolzina najdaljse fraze iz seznama, ki je v nizu (0 = nobena). */
  function najdaljse(niz, besede) {
    var naj = 0;
    for (var i = 0; i < besede.length; i++) {
      var dolzina = besede[i].replace(/^\^|\$$/g, "").length;
      if (dolzina > naj && ujemaFrazo(niz, besede[i])) naj = dolzina;
    }
    return naj;
  }

  // Koncnice domen, ki jih sprejmemo, ko naslov sestavimo iz narekovanih delov ("24 ur . com", "siol pika net")
  // ali ga vzamemo iz stavka ("open the website 24ur.com"). En sam vpisan zeton (npr. "nekaj.xyz") velja kot doslej.
  var ZNANE_KONCNICE = ["com", "net", "org", "si", "eu", "io", "de", "at", "hr", "it", "uk", "co", "tv", "app", "dev", "info", "me",
    "fr", "es", "ch", "nl", "be", "pl", "cz", "sk", "hu", "rs", "ba", "mk", "us", "ca", "au", "jp", "se", "dk", "fi", "pt", "gr", "ro",
    "bg", "ie", "lu", "gov", "edu", "xyz", "online", "site", "shop", "store", "blog", "ai", "gg", "fm", "news", "live", "cloud", "tech", "biz", "pro"];
  var DOMENA = /^(?:https?:\/\/)?(?:[a-z0-9-]+\.)+([a-z]{2,24})(?::\d{1,5})?(?:\/\S*)?$/i;
  // Kratke besede, ki niso del narekovanega naslova ("na siol.net" ni "nasiol.net").
  var VEZNIKI = " na v in za pa po od do je the on of to and at in is auf am im und zu en el la de del y sur le les et du sul su e il di a o ";

  function znanaKoncnica(k) { return ZNANE_KONCNICE.indexOf(String(k || "").toLowerCase()) >= 0; }

  /**
   * Naslov strani iz (ociscenega) besedila ali null. Polnila spredaj ("spletno stran", "the website") gredo stran.
   * celota: naslov mora biti cel niz (konec razumi: "poisci novice na siol.net" je iskanje, ne stran).
   * Izmerjeno 19. 10. 2026: prej so se zdruzili vsi presledki ("siol.netnatablici", "thewebsite24ur.com").
   */
  function najdiNaslov(niz, celota) {
    var g = slovnica();
    var s = String(niz || "").trim();
    for (var k = 0; k < 3 && s; k++) { var o = odrezi(s, g.polnilaNaslova || g.stran || []); if (!o) break; s = o; }
    if (!s) return null;
    var besede = izgovorjenePike(s.split(" "), g.pika || []);
    if (besede.length === 1) return DOMENA.test(besede[0]) ? besede[0] : null;
    // Narekovano s presledki ("24 ur.com", "rtv slo.si"): zdruzimo kratke dele (do 3 crke ali s stevko),
    // ce dobimo znano koncnico; "vreme arso.si" ostane "arso.si".
    var spredaj = besede.slice(0, -1);
    var kratki = besede.length <= 4 && spredaj.every(function (b) {
      return (b.length <= 3 || /\d/.test(b)) && b.indexOf(".") < 0 && VEZNIKI.indexOf(" " + b + " ") < 0;
    });
    var skupaj = besede[0] === "www" ? "www." + besede.slice(1).join("") : besede.join(""), m = DOMENA.exec(skupaj);
    if (kratki && m && znanaKoncnica(m[1])) return skupaj;
    if (celota) return null;
    for (var i = 0; i < besede.length; i++) {
      var z = DOMENA.exec(besede[i]);
      if (z && (znanaKoncnica(z[1]) || /^(https?:\/\/|www\.)/i.test(besede[i]))) return besede[i];
    }
    return null;
  }

  /**
   * Izgovorjena pika ("siol pika net", "24ur dot com") samo na koncu kandidata za naslov in pred znano koncnico:
   * v drugem besedilu je "point de vue" ali "en punto de la tarde" navaden stavek.
   */
  function izgovorjenePike(besede, pike) {
    var n = besede.length;
    if (n < 3 || pike.indexOf(besede[n - 2]) < 0 || !znanaKoncnica(besede[n - 1])) return besede;
    var naslov = besede[n - 1], i = n - 2;
    while (i >= 1 && pike.indexOf(besede[i]) >= 0 && /^[a-z0-9-]+$/.test(besede[i - 1])) { naslov = besede[i - 1] + "." + naslov; i -= 2; }
    return besede.slice(0, i + 1).concat([naslov]);
  }

  /** Narekovana pika s presledkom pred znano koncnico ("siol . net") -> "siol.net"; drugih pik ne dotaknemo. */
  function zdruziNarekovanNaslov(niz) {
    var koncnice = "(" + ZNANE_KONCNICE.join("|") + ")(?=$|[\\s/,!?;:.])";
    return String(niz || "").replace(new RegExp("([0-9a-zà-ž])\\s+\\.\\s*" + koncnice, "gi"), "$1.$2");
  }

  function ocisti(niz) {
    // Locila na koncu besed (nekateri prepoznavalniki jih dodajo) stran; pika v naslovu ostane.
    var s = normaliziraj(zdruziNarekovanNaslov(niz).replace(/[.,!?;:]+(\s|$)/g, "$1"));
    var g = slovnica();
    (g.mesalo || []).forEach(function (m) {
      s = (" " + s + " ").split(" " + m + " ").join(" ").trim();
    });
    return s.replace(/\s+/g, " ").trim();
  }

  // ----------------------------------------------------------------
  // Glas: katera naprava (daljinec upravlja natanko eno)
  // ----------------------------------------------------------------

  var VRSTE_NAPRAV = ["tv", "tablica", "telefon", "racunalnik"];
  var KLJUC_VRSTE = { tv: "vrstaTv", tablica: "vrstaTablica", telefon: "vrstaTelefon", racunalnik: "vrstaRacunalnik" };
  // Ime naprave, kot ga je dal uporabnik ("Tablica v kuhinji", "Philips TV"), pove vrsto, ko je naprava ne pove sama.
  var IMENA_VRST = {
    tv: /[^a-z0-9](tv|televizor\w*|televizij\w*|television|televisor\w*|televiseur|televisione|fernseher|tele|philips|bravia)[^a-z0-9]/,
    tablica: /[^a-z0-9](tablic\w*|tablet\w*|tableta|tablette|ipad)[^a-z0-9]/,
    telefon: /[^a-z0-9](telefon\w*|telephone|phone|smartphone|iphone|handy|movil|mobitel\w*|cellulare)[^a-z0-9]/,
    racunalnik: /[^a-z0-9](racunalnik\w*|computer|computadora|pc|laptop|desktop|prenosnik\w*|rechner|ordenador|ordinateur|ordi|portatil|notebook|linux|windows|mac|macbook)[^a-z0-9]/
  };
  var NAGLASI = { a: "aàáâäã", c: "cčć", d: "dđ", e: "eèéêë", i: "iìíîï", n: "nñ", o: "oòóôö", s: "sš", u: "uùúûü", z: "zž" };
  var vzorciNaprav = {};

  /** Vrste upravljane naprave (lahko vec, npr. "handheld" = telefon ali tablica); prazno = ne vemo. */
  function vrsteUpravljane() {
    var n = stanje.naprava || {};
    var pl = String(n.platforma || ""), vr = String(n.vrsta || ""), vrste = [];
    // Protocol v1: platforma je zanesljiva; vrsta "screen" pomeni TV le brez platforme (link.js ikonaNapraveVSeznamu).
    if (pl === "tv" || (!pl && (vr === "tv" || vr === "screen" || n.vloga === "receiver"))) vrste.push("tv");
    if (pl === "tablet") vrste.push("tablica");
    if (pl === "phone") vrste.push("telefon");
    if (!pl && vr === "handheld") vrste.push("telefon", "tablica");
    if (pl === "linux" || pl === "windows" || pl === "macos" || (!pl && (vr === "computer" || vr === "control"))) vrste.push("racunalnik");
    var prijazno = "";
    try { prijazno = api().prijaznoIme ? String(api().prijaznoIme(n) || "") : ""; } catch (e) {}
    var ime = " " + normaliziraj(prijazno + " " + (n.ime || "")) + " ";
    for (var i = 0; i < VRSTE_NAPRAV.length; i++) {
      if (IMENA_VRST[VRSTE_NAPRAV[i]].test(ime) && vrste.indexOf(VRSTE_NAPRAV[i]) < 0) vrste.push(VRSTE_NAPRAV[i]);
    }
    return vrste;
  }

  function upravljaVrsto(vrsta) {
    var vrste = vrsteUpravljane();
    return !vrste.length || vrste.indexOf(vrsta) >= 0;
  }

  /** Fraza (male crke, brez sumnikov) -> vzorec za izvirno besedilo: sumniki, velike crke, opuscaj, vezaj. */
  function vzorecFraze(fraza) {
    return fraza.split(" ").map(function (beseda) {
      var v = "";
      for (var i = 0; i < beseda.length; i++) {
        var c = beseda.charAt(i);
        v += NAGLASI[c] ? "[" + NAGLASI[c] + "]" : c === "-" ? "[\\s-]?" : c.replace(/[.*+?^${}()|[\]\\\/]/g, "\\$&");
      }
      return v;
    }).join("[\\s'’-]+");
  }

  function vzorecNaprav(vrsta, kje) {
    var kljuc = jezik + ":" + vrsta + ":" + kje;
    if (vzorciNaprav[kljuc] !== undefined) return vzorciNaprav[kljuc];
    var fraze = ((slovnica().naprave || {})[vrsta] || {})[kje] || [];
    var urejene = fraze.slice().sort(function (a, b) { return b.length - a.length; });
    vzorciNaprav[kljuc] = urejene.length ? new RegExp("(^|[\\s,.;:!?¡¿«»\"“”„(])(?:" + urejene.map(vzorecFraze).join("|") + ")(?=$|[\\s,.;:!?…«»\"“”)])", "gi") : null;
    return vzorciNaprav[kljuc];
  }

  function brezFraze(niz, vzorec) {
    var najdeno = false;
    var ostanek = niz.replace(vzorec, function (cel, pred) { najdeno = true; return pred + " "; });
    return najdeno ? ostanek.replace(/\s+([,.;:!?])/g, "$1").replace(/^[\s,;:]+|[\s,;:]+$/g, "").replace(/\s+/g, " ").trim() : null;
  }

  /**
   * Fraze o napravi iz izvirnega besedila. Fraza za to napravo gre ven (nikoli v iskanje ali naslov);
   * fraza za drugo vrsto naprave vrne {druga: vrsta}, da ukaza ne izvedemo na napacni napravi.
   * Gole (tozilnik: "utisaj televizor", "pause the TV") veljajo samo ob kratkem ukazu za glasnost ali predvajanje,
   * da "poisci nov telefon" ostane iskanje.
   */
  function frazeNaprav(surovo) {
    var besedilo = surovo, druga = null, vrsta, ostanek, i;
    for (i = 0; i < VRSTE_NAPRAV.length; i++) {
      vrsta = VRSTE_NAPRAV[i];
      var vz = vzorecNaprav(vrsta, "na");
      ostanek = vz ? brezFraze(besedilo, vz) : null;
      if (ostanek === null) continue;
      if (upravljaVrsto(vrsta)) besedilo = ostanek;
      else if (!druga) druga = vrsta;
    }
    if (druga) return { besedilo: besedilo, druga: druga };
    var gole = [], brezGolih = besedilo;
    for (i = 0; i < VRSTE_NAPRAV.length; i++) {
      var vzg = vzorecNaprav(VRSTE_NAPRAV[i], "gola");
      ostanek = vzg ? brezFraze(brezGolih, vzg) : null;
      if (ostanek !== null) { gole.push(VRSTE_NAPRAV[i]); brezGolih = ostanek; }
    }
    if (gole.length) {
      var u = razumiUkaz(brezGolih);
      var kratek = u && (u.dejanje === "volume" || (u.dejanje === "key" && (u.parametri.key === "pause" || u.parametri.key === "play_pause")));
      if (kratek) {
        for (i = 0; i < gole.length; i++) if (!upravljaVrsto(gole[i])) return { besedilo: besedilo, druga: gole[i] };
        besedilo = brezGolih;
      }
    }
    return { besedilo: besedilo, druga: null };
  }

  function drugaNaprava(vrsta) {
    var n = stanje.naprava || {};
    var ime = "";
    try { ime = api().prijaznoIme ? String(api().prijaznoIme(n) || "") : ""; } catch (e) {}
    if (!ime) ime = n.ime || t("naprava");
    return { dejanje: "napaka", parametri: {}, drugaNaprava: vrsta, opis: t("drugaNaprava", { ime: ime, naprava: t(KLJUC_VRSTE[vrsta]) }) };
  }

  /**
   * Besedilo v ukaz: {dejanje, parametri, opis, ocisceno} ali null. ocisceno je besedilo brez fraze o napravi
   * (za iskanje na racunalniku). Ukaz za drugo napravo vrne napako z navodilom, katero napravo izbrati.
   */
  function razumi(besedilo) {
    var surovo = String(besedilo || "").trim();
    if (!surovo) return null;
    var nap = frazeNaprav(surovo);
    if (nap.druga) return drugaNaprava(nap.druga);
    var u = razumiUkaz(nap.besedilo);
    if (u) u.ocisceno = nap.besedilo;
    return u;
  }

  /**
   * Vrstni red je pomemben: najprej glasnost in tipke (kratke, nedvoumne), potem programi,
   * aplikacije po imenu, YouTube, strani, iskanje. Kar ostane, je iskanje.
   */
  function razumiUkaz(besedilo) {
    var surovo = String(besedilo || "").trim();
    var s = ocisti(surovo);
    if (!s) return null;
    var g = slovnica();
    var st = s.match(/\b(\d{1,3})\b/);
    var stevilka = st ? parseInt(st[1], 10) : null;

    // Glasnost: zmaga najdaljsa fraza. Izmerjeno 19. 10. 2026: "baisse le son" je bil "son" (zvok nazaj),
    // "malo bolj tiho" je bil "tiho" (utisaj), "quitar silencio" je bil "silencio" (utisaj).
    var smeri = [["utisaj", "mute", "utisano"], ["zvok", "unmute", "zvokNazaj"], ["glasneje", "up", "glasneje"], ["tisje", "down", "tisje"]];
    var smer = null, najDolzina = 0;
    for (var si = 0; si < smeri.length; si++) {
      var dolzina = najdaljse(s, g[smeri[si][0]] || []);
      if (dolzina > najDolzina) { najDolzina = dolzina; smer = smeri[si]; }
    }
    if (smer) return { dejanje: "volume", parametri: { direction: smer[1] }, opis: t(smer[2]) };
    if (vsebuje(s, g.glasnostNa) && stevilka != null && stevilka <= 100) {
      return { dejanje: "volume", parametri: { level: stevilka }, opis: t("glasnostNa", { n: stevilka }) };
    }

    // Posnetek, vzdrzevanje
    if (vsebuje(s, g.posnetek)) return { dejanje: "screenshot", parametri: {}, opis: t("pokaziZaslon") };
    if (vsebuje(s, g.znovaZazeni)) return { dejanje: "restart", parametri: {}, opis: t("znovaZazeni") };
    if (vsebuje(s, g.pocisti)) return { dejanje: "clear_cache", parametri: {}, opis: t("pocistiPredpomnilnik") };

    // Tipke (samo kratki ukazi: "nazaj", "domov", "gor", "ustavi glasbo" ...)
    var tipke = [["domov", "home"], ["nazaj", "back"], ["meni", "menu"], ["gor", "up"], ["dol", "down"], ["levo", "left"], ["desno", "right"],
      ["ok", "ok"], ["pavza", "pause"], ["naslednji", "next"], ["prejsnji", "previous"]];
    for (var i = 0; i < tipke.length; i++) {
      var besede = g[tipke[i][0]] || [];
      if (besede.indexOf(s) >= 0) return { dejanje: "key", parametri: { key: tipke[i][1] }, opis: t("tipka", { ime: imeTipke(tipke[i][1]) }) };
    }
    if ((g.predvajaj || []).indexOf(s) >= 0) return { dejanje: "key", parametri: { key: "play_pause" }, opis: t("predvajanje") };
    if ((g.stranGor || []).indexOf(s) >= 0) return { dejanje: "scroll", parametri: { direction: "up" }, opis: t("stranGor") };
    if ((g.stranDol || []).indexOf(s) >= 0) return { dejanje: "scroll", parametri: { direction: "down" }, opis: t("stranDol") };

    // Program / kanal po stevilki: "program 25", "preklopi na kanal 3" -> stevke kot tipke.
    if (vsebuje(s, g.program) && stevilka != null && stevilka <= 999) {
      return { dejanje: "digits", parametri: { digits: String(stevilka) }, opis: t("kanal", { n: stevilka }) };
    }

    // "odpri X" / "zazeni X": aplikacija po imenu, sicer stran, sicer iskanje.
    var ostanek = odrezi(s, g.odpri);
    if (ostanek !== null) {
      if (!ostanek) return { dejanje: "key", parametri: { key: "home" }, opis: t("domov") };
      if (vsebuje(ostanek, g.youtube) || ostanek === "youtube") {
        var pojem = odrezi(ostanek, g.youtube);
        if (pojem) return youtube(pojem);
        var yt = najdiAplikacijo("youtube");
        if (yt) return { dejanje: "launch_app", parametri: { package: yt.package }, opis: t("odpiram", { ime: yt.label }) };
        return { dejanje: "open_url", parametri: { url: "https://www.youtube.com/" }, opis: t("odpiram", { ime: "YouTube" }) };
      }
      var app = najdiAplikacijo(ostanek);
      if (app) return { dejanje: "launch_app", parametri: { package: app.package }, opis: t("odpiram", { ime: app.label }) };
      var naslov = najdiNaslov(ostanek, false);
      if (naslov) return stran(naslov);
      // "odpri stran o vremenu": brez naslova je to iskanje (prej https://ovremenu).
      var stranOst = odrezi(ostanek, g.polnilaNaslova || g.stran);
      if (stranOst) return iskanje(stranOst);
      if (ostanek.length <= 24 && stanje.aplikacije.length) {
        // Kratko ime, ki ga med aplikacijami ni: povemo, ne iscemo na slepo.
        return { dejanje: "napaka", parametri: {}, opis: t("aplikacijaNiNamescena", { ime: ostanek }) };
      }
      return iskanje(ostanek);
    }

    // YouTube: "youtube X", "predvajaj X", "zavrti X"
    var ytOst = odrezi(s, g.youtube);
    if (ytOst !== null) {
      if (ytOst) return youtube(ytOst);
      var yt2 = najdiAplikacijo("youtube");
      if (yt2) return { dejanje: "launch_app", parametri: { package: yt2.package }, opis: t("odpiram", { ime: yt2.label }) };
      return { dejanje: "open_url", parametri: { url: "https://www.youtube.com/" }, opis: t("odpiram", { ime: "YouTube" }) };
    }
    var glasbaOst = odrezi(s, g.predvajajGlasbo);
    if (glasbaOst !== null && glasbaOst) {
      var brezYt = odrezi(glasbaOst, (g.youtube || []).map(function (y) { return "na " + y; }).concat(g.youtube || [], ["on youtube", "auf youtube", "en youtube", "sur youtube", "su youtube"]));
      return youtube(brezYt !== null ? (brezYt || glasbaOst) : glasbaOst);
    }

    // Aplikacija samo po imenu ("netflix")
    var app2 = najdiAplikacijo(s);
    if (app2 && s.length <= 24) return { dejanje: "launch_app", parametri: { package: app2.package }, opis: t("odpiram", { ime: app2.label }) };

    // Naslov strani (cel ukaz je naslov)
    var naslov2 = najdiNaslov(s, true);
    if (naslov2) return stran(naslov2);

    // Iskanje
    var iskOst = odrezi(s, g.poisci);
    if (iskOst !== null) return iskanje(iskOst || surovo);
    return { dejanje: "iskanje", parametri: { q: surovo }, opis: t("nisemRazumel", { kaj: surovo }), neznano: true };
  }

  function stran(s) {
    var url = s.replace(/\s+/g, "");
    if (!/^https?:\/\//i.test(url)) url = "https://" + url;
    return { dejanje: "open_url", parametri: { url: url }, opis: t("odpiramStran") };
  }
  function iskanje(q) {
    return { dejanje: "open_url", parametri: { url: "https://www.google.com/search?q=" + encodeURIComponent(q) }, opis: t("iscem", { kaj: q }) };
  }
  function youtube(q) {
    var url = "https://www.youtube.com/results?search_query=" + encodeURIComponent(q);
    var yt = najdiAplikacijo("youtube");
    if (yt) return { dejanje: "open_in_app", parametri: { package: yt.package, url: url }, opis: t("iscemYoutube", { kaj: q }) };
    return { dejanje: "open_url", parametri: { url: url }, opis: t("iscemYoutube", { kaj: q }) };
  }

  /** Izvede razumljen ukaz (tudi vec tipk zapored, npr. stevke programa). */
  function izvediRazumljeno(u) {
    if (!u) return;
    besedilo("daljinecOdgovor", u.opis);
    var o = el("daljinecOdgovor");
    if (o) o.classList.toggle("napaka", u.dejanje === "napaka");
    if (u.dejanje === "napaka") return;
    if (u.dejanje === "iskanje") {
      ukaz("open_url", { url: "https://www.google.com/search?q=" + encodeURIComponent(u.parametri.q) }, function (izid) { pokaziOdgovor(izid, u.opis); });
      return;
    }
    if (u.dejanje === "digits") {
      var stevke = String(u.parametri.digits).split("");
      var i = 0;
      var naslednja = function () {
        if (i >= stevke.length) { pokaziOdgovor({ ok: true }, u.opis); return; }
        ukaz("key", { key: stevke[i++] }, function (izid) {
          if (!izid || !izid.ok) { pokaziOdgovor(izid); return; }
          setTimeout(naslednja, 250);
        });
      };
      naslednja();
      return;
    }
    if (u.dejanje === "open_in_app") {
      ukaz("open_in_app", u.parametri, function (izid) {
        if (izid && izid.ok) { pokaziOdgovor(izid, u.opis); return; }
        // Aplikacija povezave ne zna: odpremo jo v Safeerju.
        ukaz("open_url", { url: u.parametri.url }, function (izid2) { pokaziOdgovor(izid2, u.opis); });
      });
      return;
    }
    ukaz(u.dejanje, u.parametri, function (izid) {
      if (u.dejanje === "volume" && izid && izid.ok && izid.data) narisiGlasnost(izid.data);
      if (u.dejanje === "screenshot" && izid && izid.ok) { pokaziPosnetek(izid.data); return; }
      pokaziOdgovor(izid, u.opis);
    });
  }

  /** Naprava v Linku s Safeer Agentom (racunalnik z jezikovnim modelom, zmoznost "agent") ali null. */
  function agentNaprava() {
    try {
      var vse = JSON.parse(most && most.naprave ? most.naprave() : "[]") || [];
      for (var i = 0; i < vse.length; i++) {
        if ((vse[i].zmoznosti || []).indexOf("agent") >= 0) return vse[i];
      }
    } catch (e) {}
    return null;
  }

  /** Stavek, ki ga hitri prevajalnik ne zna (vec ukazov, druga naprava, nerazumljeno), gre agentu. */
  function zaAgenta(cisto, u) {
    if (!u || u.dejanje === "iskanje" || u.dejanje === "napaka") return true;
    // Trajanje seje ("za 30 minut", "do preklica") ali "ustavi agenta" zna samo agent.
    var tr = trajanjeAgenta(cisto);
    if (tr.stop || tr.minute !== null) return true;
    var s = ocisti(cisto);
    if (/(^|\s)(in|potem|nato|pa|and|then|und|dann|y|luego|et|puis|e|poi)(\s|$)/.test(s) && s.split(/\s+/).length > 3) return true;
    // Omenjena druga naprava (ne ta, ki jo daljinec upravlja): to zna samo agent.
    var vrste = [["tv", /(televiz|(^|\s)tv(\s|$))/], ["tablet", /(tablic|tablet)/], ["phone", /(telefon|mobitel|phone)/],
                 ["pc", /(racunalnik|prenosnik|computer|laptop|(^|\s)pc(\s|$))/]];
    // Vrste upravljane naprave kot v hitrem prevajalniku (platforma, vrsta, vloga, ime): "glasneje na televizorju"
    // na sprejemniku brez platforme ni druga naprava (fraze o napravi niso vec med mesalom, ki jih je prej brisalo).
    var ta = vrsteUpravljane().map(function (v) { return { tv: "tv", tablica: "tablet", telefon: "phone", racunalnik: "pc" }[v]; });
    for (var i = 0; i < vrste.length; i++) {
      if (vrste[i][1].test(s) && ta.indexOf(vrste[i][0]) < 0) return true;
    }
    return false;
  }

  /**
   * Trajanje seje agenta iz stavka (do minute natancno ali neomejeno): "za 30 minut", "eno uro", "1 h 15 min",
   * "pol ure", "do preklica"/"neomejeno", "for 2 hours". Vrne {minute: N (0 = neomejeno) ali null (ni omenjeno),
   * stop: true za "ustavi agenta", besedilo: stavek brez omembe trajanja}.
   */
  function trajanjeAgenta(cisto) {
    var s = normaliziraj(cisto);
    if (/^(ustavi agenta|stop agent|stop the agent|prekini agenta|agent stop|agent ustavi)$/.test(s)) {
      return { minute: null, stop: true, besedilo: "" };
    }
    var minute = null, ostanek = s;
    var neomejeno = /(^|\s)(do preklica|neomejeno|ves cas|brez omejitve|until (i say )?stop|indefinitely|forever|unbegrenzt|sin limite|illimite|senza limite)(\s|$)/;
    if (neomejeno.test(s)) { minute = 0; ostanek = s.replace(neomejeno, " "); }
    else {
      var vzorec = /(^|\s)(za |naslednjih |for |next |fur |durante |pendant |per )?(?:(\d+)\s*(?:hours?|stunden?|horas?|heures?|ur[aeo]?|uri|ore?|h)\b\s*(?:in\s*)?)?(?:(\d+)\s*(?:min|minut[aeo]?|minuti|minutes?|minuten|minutos?|minuti)\b)?/;
      var m = null, re = new RegExp(vzorec.source, "g"), x;
      while ((x = re.exec(s)) !== null) {
        if (x[3] || x[4]) { m = x; break; }
        if (re.lastIndex === x.index) re.lastIndex++;
      }
      if (m) {
        minute = (parseInt(m[3] || "0", 10) * 60) + parseInt(m[4] || "0", 10);
        ostanek = s.slice(0, m.index) + " " + s.slice(m.index + m[0].length);
      } else if (/(^|\s)(pol ure|half an hour|halbe stunde|media hora|demi-heure|mezz'?ora)(\s|$)/.test(s)) {
        minute = 30; ostanek = s.replace(/(za |for )?(pol ure|half an hour|halbe stunde|media hora|demi-heure|mezz'?ora)/, " ");
      } else if (/(^|\s)(eno uro|1 uro|an hour|one hour|eine stunde|una hora|une heure|un'?ora)(\s|$)/.test(s)) {
        minute = 60; ostanek = s.replace(/(za |for )?(eno uro|1 uro|an hour|one hour|eine stunde|una hora|une heure|un'?ora)/, " ");
      }
    }
    if (minute !== null && minute > 0) minute = Math.min(minute, 7 * 24 * 60);
    if (minute === null) return { minute: null, stop: false, besedilo: cisto };
    return { minute: minute, stop: false, besedilo: ostanek.replace(/\s+/g, " ").trim() || cisto };
  }

  function posljiAgentu(cisto, agent, u) {
    besedilo("daljinecOdgovor", t("agentRazmislja"));
    var o = el("daljinecOdgovor");
    if (o) o.classList.remove("napaka");
    var tr = trajanjeAgenta(cisto);
    var parametri = { text: tr.besedilo, here: stanje.naprava ? stanje.naprava.id : "" };
    if (tr.stop) parametri = { stop: true };
    else if (tr.minute !== null) parametri.minutes = tr.minute;
    ukaz("agent.ask", parametri, function (izid) {
      // Agent (model) ni dosegljiv ali se ni odzval: kar zna hitri prevajalnik, naredi on.
      if (u && izid && !izid.ok && (izid.code === "agent_ni_modela" || izid.casovna)) { izvediRazumljeno(u); return; }
      var niz = izid && izid.message;
      var ostane = izid && izid.ok && izid.data ? izid.data.session_left_s : null;
      if (niz && typeof ostane === "number" && ostane > 0) niz += " " + t("agentSeja", { min: Math.max(1, Math.round(ostane / 60)) });
      pokaziOdgovor(izid, niz);
    }, 45000, agent);
  }

  function izvediBesedilo(izgovorjeno) {
    // Parameter ne sme zakriti funkcije besedilo(): prej je iskanje na racunalniku (search.query) padlo s TypeError.
    var cisto = String(izgovorjeno || "").trim();
    if (!cisto) return;
    stanje.zadnjeSliseno = cisto;
    var e = el("daljinecSliseno");
    if (e) { e.textContent = "»" + cisto + "«"; e.classList.remove("namig"); }
    var u = razumi(cisto);
    // Safeer Agent (racunalnik z lokalnim modelom): vec ukazov v enem stavku, druge naprave, naravni stavki.
    var agent = agentNaprava();
    // Ukaz za drugo napravo zna agent; ce agent ni dosegljiv, uporabnik dobi navodilo, katero napravo izbrati.
    if (agent && zaAgenta(cisto, u)) { posljiAgentu(cisto, agent, u && (u.dejanje !== "napaka" || u.drugaNaprava) ? u : null); return; }
    // Fraza o napravi ("na računalniku") ne gre v iskanje; ukaz za drugo napravo se ne poslje nikamor.
    if (u && u.ocisceno) cisto = u.ocisceno;
    // Na racunalniku s Safeer OS (search.query) gre vse, kar ni ukaz daljinca (glasnost, tipke, drsenje, posnetek),
    // v iskalno lupo racunalnika - programi, datoteke, nastavitve, mediji in splet so tam na enem mestu.
    var ukazDaljinca = u && (!!u.drugaNaprava || ["volume", "key", "scroll", "digits", "screenshot", "restart", "clear_cache"].indexOf(u.dejanje) >= 0);
    if (stanje.isceNaRacunalniku && !ukazDaljinca) {
      var opis = t("iscemNaRacunalniku", { kaj: cisto });
      besedilo("daljinecOdgovor", opis);
      ukaz("search.query", { q: cisto }, function (izid) { pokaziOdgovor(izid, opis); });
      return;
    }
    izvediRazumljeno(u);
  }

  // ----------------------------------------------------------------
  // Glas: most -> stran
  // ----------------------------------------------------------------

  function mikrofon() {
    var gumb = el("daljinecMikrofon");
    if (!znaGovor()) { pokaziOdgovorNiz(t("niGovora"), true); return; }
    if (stanje.poslusam) {
      try { most.nehajPoslusati(); } catch (e) {}
      nastaviMikrofon("");
      return;
    }
    nastaviMikrofon("poslusam");
    var e = el("daljinecSliseno");
    if (e) { e.textContent = t("poslusam"); e.classList.add("namig"); }
    besedilo("daljinecOdgovor", "");
    try { most.poslusaj(jezik); } catch (err) { nastaviMikrofon(""); pokaziOdgovorNiz(t("napakaGovora"), true); }
  }

  function nastaviMikrofon(nacin) {
    var gumb = el("daljinecMikrofon");
    stanje.poslusam = nacin === "poslusam";
    if (!gumb) return;
    gumb.classList.toggle("poslusam", nacin === "poslusam");
    gumb.classList.toggle("obdelujem", nacin === "obdelujem");
  }

  function pokaziOdgovorNiz(niz, napaka) {
    var o = el("daljinecOdgovor");
    if (!o) return;
    o.textContent = niz;
    o.classList.toggle("napaka", !!napaka);
  }

  /** Odziv prepoznavalnika: {stanje: poslusam|delno|koncno|obdelujem|napaka, besedilo, koda}. */
  function govor(podatki) {
    if (!podatki) return;
    var s = podatki.stanje;
    var e = el("daljinecSliseno");
    if (s === "poslusam") {
      nastaviMikrofon("poslusam");
      if (e) { e.textContent = t("poslusam"); e.classList.add("namig"); }
    } else if (s === "delno") {
      if (e && podatki.besedilo) { e.textContent = "»" + podatki.besedilo + " …«"; e.classList.remove("namig"); }
    } else if (s === "obdelujem") {
      nastaviMikrofon("obdelujem");
      pokaziOdgovorNiz(t("obdelujem"), false);
    } else if (s === "koncno") {
      nastaviMikrofon("");
      if (podatki.besedilo) izvediBesedilo(podatki.besedilo);
      else { if (e) { e.textContent = t("nicSlisano"); e.classList.add("namig"); } }
    } else if (s === "napaka") {
      nastaviMikrofon("");
      pokaziOdgovorNiz("", false);
      var koda = podatki.koda || "";
      var niz = koda === "nic_slisano" ? t("nicSlisano") : koda === "dovoljenje" ? t("dovoljenje")
        : koda === "ni_prepoznavalnika" ? t("niGovora") : t("napakaGovora");
      if (e) { e.textContent = niz; e.classList.add("namig"); }
    }
  }

  // ----------------------------------------------------------------
  // Posnetek zaslona
  // ----------------------------------------------------------------

  function posnetek() {
    var g = el("daljinecPosnetekGumb");
    pokazi("daljinecPosnetek", true);
    if (g) g.classList.add("vklopljen");
    ukaz("screenshot", {}, function (izid) {
      if (izid && izid.ok) pokaziPosnetek(izid.data);
      else {
        besedilo("daljinecPosnetekOpomba", (izid && izid.message) || t("posnetekNiUspel"));
        if (izid && !izid.ok && stanje.samodejno) nacrtujPosnetek(6000);
      }
    }, 12000);
  }

  function pokaziPosnetek(podatki) {
    pokazi("daljinecPosnetek", true);
    var g = el("daljinecPosnetekGumb");
    if (g) g.classList.add("vklopljen");
    var slika = el("daljinecSlika");
    if (slika && podatki && podatki.image) slika.src = podatki.image;
    var zdaj = new Date();
    var cas = ("0" + zdaj.getHours()).slice(-2) + ":" + ("0" + zdaj.getMinutes()).slice(-2) + ":" + ("0" + zdaj.getSeconds()).slice(-2);
    besedilo("daljinecPosnetekOpomba", t("posnetekOsvezen", { cas: cas }));
    if (stanje.samodejno) nacrtujPosnetek(4000);
  }

  function nacrtujPosnetek(zamik) {
    if (stanje.posnetekTimer) clearTimeout(stanje.posnetekTimer);
    if (!stanje.odprt || !stanje.samodejno) return;
    stanje.posnetekTimer = setTimeout(function () {
      stanje.posnetekTimer = null;
      if (stanje.odprt && stanje.samodejno && document.visibilityState !== "hidden") posnetek();
      else if (stanje.samodejno) nacrtujPosnetek(4000);
    }, zamik);
  }

  function preklopiPosnetek() {
    var vidno = !el("daljinecPosnetek").hidden;
    if (vidno) {
      pokazi("daljinecPosnetek", false);
      var g = el("daljinecPosnetekGumb");
      if (g) g.classList.remove("vklopljen");
      stanje.samodejno = false;
      var st = el("daljinecSamodejno");
      if (st) st.checked = false;
      if (stanje.posnetekTimer) clearTimeout(stanje.posnetekTimer);
      stanje.posnetekTimer = null;
    } else {
      posnetek();
    }
  }

  // ----------------------------------------------------------------
  // Stanje naprave
  // ----------------------------------------------------------------

  function osveziStanje() {
    ukaz("status", {}, function (izid) {
      var s = el("daljinecStanje");
      if (!s) return;
      if (izid && izid.ok && izid.data) {
        var d = izid.data;
        if (d.volume != null) narisiGlasnost({ level: d.volume, muted: !!d.muted });
        var naslov = d.title || d.url || "";
        if (naslov && naslov.indexOf("android_asset") >= 0) naslov = t("domov");
        var neMore = d.foreground === false && d.can_wake === false;
        s.textContent = neMore ? t("neMoreVOspredje") : d.foreground === false ? t("niVOspredju") : (naslov ? t("odprtaStran", { naslov: naslov }) : (d.version ? "Safeer " + d.version : ""));
        s.classList.toggle("napaka", d.foreground === false);
        // Kar naprava ne zna, skrijemo: drsenje je le v brskalniku, aplikacije le na Androidu.
        var dejanja = d.actions || [];
        // Racunalnik s Safeer OS: kar povem v telefon, isce lupa na racunalniku (telefon je mikrofon zanj).
        stanje.isceNaRacunalniku = dejanja.indexOf("search.query") >= 0;
        pokazi("daljinecAplikacijePanel", dejanja.indexOf("launch_app") >= 0);
        pokazi("daljinecDrsenje", dejanja.indexOf("scroll") >= 0);
        var tipkeNaprave = d.keys || [];
        var dpad = el("daljinecDpad");
        if (dpad) dpad.style.visibility = tipkeNaprave.indexOf("ok") >= 0 ? "" : "hidden";
        if (dejanja.indexOf("launch_app") >= 0) naloziAplikacije(false);
      } else {
        s.textContent = (izid && izid.message) || t("brezOdgovora");
        s.classList.add("napaka");
      }
    }, 6000);
  }

  /** Katera naprava je sredisce Safeer Linka (na njej tece Link, prek nje gredo ukazi). */
  function osveziSredisce() {
    var e = el("daljinecSredisce");
    if (!e) return;
    var ime = "";
    try { ime = api().sredisce ? api().sredisce() : ""; } catch (err) { ime = ""; }
    e.textContent = ime ? t("sredisce", { ime: ime }) : "";
    e.style.display = ime ? "" : "none";
  }

  // ----------------------------------------------------------------
  // Odpiranje in zapiranje
  // ----------------------------------------------------------------

  var prevedeno = false;
  var pripeto = false;

  function odpri(naprava) {
    if (!naprava) return;
    stanje.naprava = naprava;
    stanje.odprt = true;
    if (!prevedeno) { prevedi(); prevedeno = true; }
    if (!pripeto) { pripni(); pripeto = true; }
    var ime = (api().prijaznoIme ? api().prijaznoIme(naprava) : (naprava.ime || t("naprava")));
    besedilo("daljinecIme", ime);
    besedilo("daljinecStanje", "");
    besedilo("daljinecAplikacijeNaslov", t("aplikacijeNa", { ime: ime }));
    osveziSredisce();
    besedilo("daljinecOdgovor", "");
    var e = el("daljinecSliseno");
    var govor = znaGovor();
    if (e) { e.textContent = t(govor ? "govoriNamig" : "pisiNamig"); e.classList.add("namig"); }
    var mik = el("daljinecMikrofon");
    // Brez prepoznave govora (racunalnik) mikrofona ne kazemo: ukaz se vpise v polje spodaj.
    if (mik) { mik.disabled = !govor; mik.style.display = govor ? "" : "none"; }
    pokazi("daljinecGlas", true);
    // Shranjen seznam aplikacij (brez ikon) pokazemo takoj; sveze pridejo iz naprave.
    if (stanje.aplikacijeZa !== naprava.id) {
      stanje.aplikacije = [];
      try {
        var shranjene = JSON.parse(window.localStorage.getItem("safeer_daljinec_apps_" + naprava.id) || "[]");
        if (shranjene && shranjene.length) { stanje.aplikacije = shranjene; stanje.aplikacijeZa = ""; }
      } catch (err) {}
    }
    narisiAplikacije();
    if (api().pokaziDaljinec) api().pokaziDaljinec(true);
    pokazi("zaslonDaljinec", true);
    try { window.scrollTo(0, 0); } catch (err) {}
    osveziStanje();
    if (api().televizor && api().televizor()) {
      setTimeout(function () { try { el("daljinecMikrofon").focus(); } catch (err) {} }, 60);
    }
  }

  function zapri() {
    stanje.odprt = false;
    stanje.naprava = null;
    stanje.samodejno = false;
    if (stanje.posnetekTimer) clearTimeout(stanje.posnetekTimer);
    stanje.posnetekTimer = null;
    if (stanje.poslusam) { try { most.nehajPoslusati(); } catch (e) {} nastaviMikrofon(""); }
    pokazi("daljinecPosnetek", false);
    var g = el("daljinecPosnetekGumb");
    if (g) g.classList.remove("vklopljen");
    pokazi("zaslonDaljinec", false);
    if (api().pokaziDaljinec) api().pokaziDaljinec(false);
  }

  /** Seznam naprav se je spremenil: ce nase ni vec, daljinec zapremo. */
  function naprave(seznam) {
    if (!stanje.odprt || !stanje.naprava) return;
    var se = (seznam || []).some(function (n) { return n.id === stanje.naprava.id; });
    if (!se) { zapri(); return; }
    osveziSredisce();
  }

  function pripni() {
    var nazaj = el("daljinecNazaj");
    if (nazaj) nazaj.addEventListener("click", zapri);
    var pg = el("daljinecPosnetekGumb");
    if (pg) pg.addEventListener("click", preklopiPosnetek);
    var sam = el("daljinecSamodejno");
    if (sam) sam.addEventListener("change", function () { stanje.samodejno = !!sam.checked; if (stanje.samodejno) posnetek(); });
    var mik = el("daljinecMikrofon");
    if (mik) mik.addEventListener("click", mikrofon);
    var obrazec = el("daljinecVnosObrazec");
    if (obrazec) obrazec.addEventListener("submit", function (ev) {
      ev.preventDefault();
      var v = el("daljinecVnos");
      if (!v) return;
      izvediBesedilo(v.value);
      v.value = "";
      try { v.blur(); } catch (e) {}
    });
    var koren = el("zaslonDaljinec");
    if (koren) koren.addEventListener("click", function (ev) {
      var cilj = ev.target;
      while (cilj && cilj !== koren && !(cilj.getAttribute && (cilj.getAttribute("data-tipka") || cilj.getAttribute("data-glasnost") || cilj.getAttribute("data-drsenje") || cilj.getAttribute("data-ukaz")))) cilj = cilj.parentNode;
      if (!cilj || cilj === koren) return;
      var k = cilj.getAttribute("data-tipka");
      if (k) { tipka(k, cilj); return; }
      var gl = cilj.getAttribute("data-glasnost");
      if (gl) { glasnost({ direction: gl }, cilj); return; }
      var dr = cilj.getAttribute("data-drsenje");
      if (dr) { drsenje(dr, cilj); return; }
      var uk = cilj.getAttribute("data-ukaz");
      if (uk) {
        utrip(cilj);
        ukaz(uk, {}, function (izid) {
          var o = el("daljinecVecOpomba");
          var niz = izid ? (izid.message || "") : t("brezOdgovora");
          if (uk === "status" && izid && izid.ok && izid.data) {
            var d = izid.data;
            niz = (d.model ? d.model + " · " : "") + (d.app || "") + " " + (d.version || "") + (d.android ? " · Android " + d.android : "") + (d.url ? " · " + d.url : "");
          }
          if (o) o.textContent = niz;
          if (uk !== "status") pokaziOdgovor(izid, izid && izid.message);
        }, 15000);
      }
    });
    var osv = el("daljinecAplikacijeOsvezi");
    if (osv) osv.addEventListener("click", function () { naloziAplikacije(true); });
    // Daljinec televizorja: Nazaj na strani zapre daljinec, ne cele strani Linka.
    document.addEventListener("keydown", function (ev) {
      if (!stanje.odprt) return;
      if (ev.key === "Escape" || ev.key === "Backspace" && !(ev.target && /input|textarea/i.test(ev.target.tagName))) {
        ev.preventDefault();
        zapri();
      }
    });
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "hidden" && stanje.poslusam) { try { most.nehajPoslusati(); } catch (e) {} nastaviMikrofon(""); }
    });
  }

  // --------------------------------------------------------------
  // Opcijski gamepad. Navadni daljinec ostane prvotni/defaultni nacin.
  // Preklop ne vpliva na druge funkcije telefona ali Continuity; ob izhodu vedno sprostimo vnos.
  function gamepadUkaz(dejanje, parametri) { ukaz(dejanje, parametri || {}, function () {}, 2500); }
  function gamepadSprosti() { gamepadUkaz("gamepad.release", {}); }
  function nacinKrmilnika(gamepad) {
    var nav = el("navadniDaljinec"), gp = el("gamepadPanel"), a = el("nacinDaljinec"), b = el("nacinGamepad");
    if (nav) nav.hidden = !!gamepad; if (gp) gp.hidden = !gamepad;
    if (a) a.classList.toggle("izbran", !gamepad); if (b) b.classList.toggle("izbran", !!gamepad);
    if (!gamepad) gamepadSprosti();
  }
  function pripraviGamepad() {
    var a=el("nacinDaljinec"), b=el("nacinGamepad");
    if(a) a.addEventListener("click",function(){nacinKrmilnika(false);});
    if(b) b.addEventListener("click",function(){nacinKrmilnika(true);});
    document.querySelectorAll("[data-gp]").forEach(function(g){
      var ime=g.getAttribute("data-gp"), dol=function(e){e.preventDefault();gamepadUkaz("gamepad.button",{button:ime,down:true});}, gor=function(e){e.preventDefault();gamepadUkaz("gamepad.button",{button:ime,down:false});};
      g.addEventListener("pointerdown",dol); g.addEventListener("pointerup",gor); g.addEventListener("pointercancel",gor); g.addEventListener("pointerleave",function(e){if(e.buttons)gor(e);});
    });
    document.querySelectorAll("[data-axis]").forEach(function(g){
      var x=g.getAttribute("data-axis").split(":"), os=x[0], vrednost=parseFloat(x[1]);
      g.addEventListener("pointerdown",function(e){e.preventDefault();gamepadUkaz("gamepad.axis",{axis:os,value:vrednost});});
      function nic(e){e.preventDefault();gamepadUkaz("gamepad.axis",{axis:os,value:0});}
      g.addEventListener("pointerup",nic);g.addEventListener("pointercancel",nic);g.addEventListener("pointerleave",function(e){if(e.buttons)nic(e);});
    });
    document.querySelectorAll(".gpStick").forEach(function(stick){
      var stran=stick.getAttribute("data-stick"), osx=stran+"_x", osy=stran+"_y", pika=stick.querySelector("i"), pid=null, zadnji=0;
      function premik(e){ if(pid!==e.pointerId)return; var r=stick.getBoundingClientRect(), dx=(e.clientX-(r.left+r.width/2))/(r.width/2), dy=(e.clientY-(r.top+r.height/2))/(r.height/2), m=Math.max(1,Math.sqrt(dx*dx+dy*dy)); dx/=m;dy/=m; if(pika){pika.style.transform="translate("+(dx*35)+"px,"+(dy*35)+"px)";} var z=Date.now(); if(z-zadnji>28){zadnji=z;gamepadUkaz("gamepad.axis",{axis:osx,value:dx});gamepadUkaz("gamepad.axis",{axis:osy,value:dy});}}
      function dol(e){pid=e.pointerId;stick.setPointerCapture(pid);premik(e);}
      function gor(e){if(pid!==e.pointerId)return;pid=null;if(pika)pika.style.transform="";gamepadUkaz("gamepad.axis",{axis:osx,value:0});gamepadUkaz("gamepad.axis",{axis:osy,value:0});}
      stick.addEventListener("pointerdown",dol);stick.addEventListener("pointermove",premik);stick.addEventListener("pointerup",gor);stick.addEventListener("pointercancel",gor);
    });
    window.addEventListener("pagehide",gamepadSprosti); document.addEventListener("visibilitychange",function(){if(document.visibilityState==="hidden")gamepadSprosti();});
    nacinKrmilnika(false);
  }
  if(document.readyState==="loading") document.addEventListener("DOMContentLoaded",pripraviGamepad); else pripraviGamepad();

  window.SafeerDaljinec = {
    odpri: odpri,
    zapri: zapri,
    odziv: odziv,
    govor: govor,
    naprave: naprave,
    jeOdprt: function () { return stanje.odprt; },
    razumi: razumi
  };
})();
