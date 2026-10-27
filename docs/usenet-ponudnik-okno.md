# Okno "Moji viri -> Usenet ponudnik" (Android: telefon, tablica, TV)

Opis za vodjo (vkljucitev v GlasbaActivity naredi vodja). Vsa besedila so v
`UsenetPonudnikPravila.BESEDILA` (kljuc -> jezik sl/en/de/es/fr/it); okno jih bere z `besedilo(kljuc, jezik)`.
Besedila stanj v sl so dobesedno iz reference `core/usenet_ponudnik.py` (`_BESEDILA`).

## Kdaj se Usenet pokaze
- Brez vpisanega ponudnika: `stanje().vpisan == false`, Usenet ni viden; v "Moji viri" je samo vrstica
  "Usenet ponudnik" z besedilom `NI_VPISAN`.
- Safeer ne priporoca in ne vsebuje imen ponudnikov (tudi v namigih ne): primer v namigu je `news.example`.

## Polja (od zgoraj navzdol; na TV vsako polje v svoji vrstici, D-pad navzdol)
| Polje | Kljuc oznake | Vrsta | Privzeto | Pravilo (napaka -> kljuc besedila) |
|---|---|---|---|---|
| Ime | `POLJE_IME` | besedilo | prazno | <= 80 znakov, brez kontrolnih (`N_IME`) |
| Streznik | `POLJE_STREZNIK` | besedilo, `textUri`, brez samopopravka | prazno, namig `news.example` | ime ali IP, brez sheme/poti/vrat/presledkov, brez koncne pike (`N_GOSTITELJ`) |
| Vrata | `POLJE_VRATA` | stevilo | 563 | 1-65535 (`N_VRATA`) |
| Sifrirana povezava (TLS) | `POLJE_TLS` | stikalo | vklopljeno | izklop pokaze `OPOZORILO_BREZ_TLS` in potrditveno polje; brez TLS samo IP v domacem omrezju (`N_BREZ_TLS`, `N_BREZ_TLS_DOMA`), shranjeno, a preverba in bazen vrneta `BREZ_TLS` (pogon zna samo TLS) |
| Uporabnisko ime | `POLJE_UPORABNIK` | besedilo, brez samopopravka | prazno | 1-400 bajtov, brez kontrolnih (`N_UPORABNIK`) |
| Geslo | `POLJE_GESLO` | `textPassword` (upravitelj gesel sme izpolniti) | vedno prazno ob odprtju (geslo se nikoli ne prikaze) | 1-400 bajtov (`N_GESLO`) |
| Najvec povezav | `POLJE_POVEZAV` | stevilo | 8 | 1-100 (`N_POVEZAV`), namig `NAMIG_POVEZAV`; dejanska meja min(vnos, 50) na racun |

Gumbi: `GUMB_SHRANI`, `GUMB_PREVERI`, `GUMB_POZABI` (s potrditvijo).

## Vedenje
- **Shrani**: `Ponudnik.ustvari(...)` + `Geslo.iz(...)`; napaka `NeveljavenVnos.message` je kljuc besedila
  (sporocilo nikoli ne vsebuje vnosa). `UsenetPonudnik.shrani()` vrne false, ce Keystore ni na voljo -> okno pove,
  da geslo velja samo do zaprtja aplikacije. Shrani je izrecen nov vpis in EDINI odklepa zapah.
- **Preveri**: samo na klik, na delovni niti (`Dispatchers.IO`); na glavni niti `NapakaPonudnika(GLAVNA_NIT)`.
  Med preverbo je gumb onemogocen. Izid -> besedilo: `VELJA`, `NE_VELJA`, `ZAKLENJEN`, `NI_ODGOVORA`,
  `PREVEC_POVEZAV`, `PREPOGOSTO` (vec kot 1/min), `BREZ_TLS`. Nic v ozadju (ne ob odprtju okna, ne ob zagonu).
- **Zapah**: po `NE_VELJA` je Preveri `ZAKLENJEN` brez omrezja. V stanju `NE_VELJA`/`ZAKLENJEN` okno izprazni geslo,
  fokus da na polje Geslo, Preveri je onemogocen, dokler uporabnik ne vpise gesla in pritisne Shrani
  (Shrani odklene; nato sme Preveri takoj).
- **Pozabi**: brise zapis, geslo v Keystore, zapah in omejitev; Usenet izgine.
- **Stanje ob odprtju**: `stanje()` (brez omrezja): ime, streznik, vrata, TLS, povezave, `gesloShranjeno`, stanje,
  kljuc besedila. Uporabnik in geslo se ne vrneta (uporabnisko ime okno prebere iz `preberi()` samo za polje).
- **Ozji krog**: `zaKrog()` / `prejetoIzKroga()` samo prek secrets.sync (sifrirano). Zapah in izid preverbe se ne
  sinhronizirata; prejem ne odklene zapaha.

## Besedila (sl / en / de / es / fr / it)
Spodaj vsa besedila iz kode (tabelo je izpisal `BESEDILA` iz prevedene kode).

| Kljuc | sl | en | de | es | fr | it |
|---|---|---|---|---|---|---|
| `NASLOV` | Usenet ponudnik | Usenet provider | Usenet-Anbieter | Proveedor de Usenet | Fournisseur Usenet | Provider Usenet |
| `POLJE_IME` | Ime (neobvezno) | Name (optional) | Name (optional) | Nombre (opcional) | Nom (facultatif) | Nome (facoltativo) |
| `POLJE_STREZNIK` | Strežnik | Server | Server | Servidor | Serveur | Server |
| `POLJE_VRATA` | Vrata | Port | Port | Puerto | Port | Porta |
| `POLJE_TLS` | Šifrirana povezava (TLS) | Encrypted connection (TLS) | Verschlüsselte Verbindung (TLS) | Conexión cifrada (TLS) | Connexion chiffrée (TLS) | Connessione cifrata (TLS) |
| `POLJE_UPORABNIK` | Uporabniško ime | Username | Benutzername | Nombre de usuario | Nom d’utilisateur | Nome utente |
| `POLJE_GESLO` | Geslo | Password | Passwort | Contraseña | Mot de passe | Password |
| `POLJE_POVEZAV` | Največ povezav | Maximum connections | Maximale Verbindungen | Conexiones máximas | Connexions maximales | Connessioni massime |
| `NAMIG_POVEZAV` | Vpiši največ toliko, kolikor dovoli tvoj paket. Safeer jih ne odpre več. | Enter at most what your plan allows. Safeer never opens more than that. | Höchstens so viele, wie dein Tarif erlaubt. Safeer öffnet nie mehr. | Introduce como máximo las que permite tu plan. Safeer nunca abre más. | Indique au maximum ce que ton abonnement autorise. Safeer n’en ouvre jamais plus. | Inserisci al massimo quante ne consente il tuo piano. Safeer non ne apre mai di più. |
| `GUMB_SHRANI` | Shrani | Save | Speichern | Guardar | Enregistrer | Salva |
| `GUMB_PREVERI` | Preveri | Check | Prüfen | Comprobar | Vérifier | Verifica |
| `GUMB_POZABI` | Pozabi ponudnika | Forget provider | Anbieter entfernen | Olvidar proveedor | Oublier le fournisseur | Dimentica provider |
| `OPOZORILO_BREZ_TLS` | Brez TLS gre geslo po omrežju nešifrirano. Dovoljeno samo do naslova IP v domačem omrežju. | Without TLS the password travels unencrypted. Allowed only to an IP address on your home network. | Ohne TLS wird das Passwort unverschlüsselt übertragen. Nur für eine IP-Adresse im Heimnetz erlaubt. | Sin TLS la contraseña viaja sin cifrar. Solo se permite a una dirección IP de tu red doméstica. | Sans TLS, le mot de passe circule en clair. Autorisé uniquement vers une adresse IP de ton réseau local. | Senza TLS la password viaggia in chiaro. Consentito solo verso un indirizzo IP della rete domestica. |
| `NI_VPISAN` | Usenet se pokaže, ko v nastavitvah vpišeš svojega ponudnika NNTP. | Usenet appears once you enter your own NNTP provider in the settings. | Usenet erscheint, sobald du in den Einstellungen deinen eigenen NNTP-Anbieter einträgst. | Usenet aparece cuando introduces tu propio proveedor NNTP en los ajustes. | Usenet apparaît dès que tu saisis ton propre fournisseur NNTP dans les réglages. | Usenet compare quando inserisci il tuo provider NNTP nelle impostazioni. |
| `NEPREVERJEN` | Ponudnik je shranjen. Klikni »Preveri«, če želiš preizkusiti prijavo. | The provider is saved. Tap “Check” if you want to test the login. | Der Anbieter ist gespeichert. Tippe auf „Prüfen“, um die Anmeldung zu testen. | El proveedor está guardado. Pulsa «Comprobar» si quieres probar el inicio de sesión. | Le fournisseur est enregistré. Appuie sur « Vérifier » pour tester la connexion. | Il provider è salvato. Tocca «Verifica» se vuoi provare l’accesso. |
| `ni_gesla` | Geslo za ponudnika ni shranjeno. Vpiši ga znova v nastavitvah Useneta. | The provider password is not saved. Enter it again in the Usenet settings. | Das Passwort des Anbieters ist nicht gespeichert. Gib es in den Usenet-Einstellungen erneut ein. | La contraseña del proveedor no está guardada. Vuelve a introducirla en los ajustes de Usenet. | Le mot de passe du fournisseur n’est pas enregistré. Saisis-le à nouveau dans les réglages Usenet. | La password del provider non è salvata. Inseriscila di nuovo nelle impostazioni Usenet. |
| `VELJA` | Prijava pri ponudniku deluje. | Login to the provider works. | Die Anmeldung beim Anbieter funktioniert. | El inicio de sesión en el proveedor funciona. | La connexion au fournisseur fonctionne. | L’accesso al provider funziona. |
| `NE_VELJA` | Ponudnik je zavrnil prijavo. Preveri uporabniško ime in geslo in ju vpiši znova - do takrat Safeer gesla ne pošilja (zaščita pred zaklepom računa). | The provider rejected the login. Check your username and password and enter them again - until then Safeer does not send the password (protection against account lockout). | Der Anbieter hat die Anmeldung abgelehnt. Prüfe Benutzername und Passwort und gib sie erneut ein - bis dahin sendet Safeer das Passwort nicht (Schutz vor Kontosperre). | El proveedor rechazó el inicio de sesión. Revisa el usuario y la contraseña y vuelve a introducirlos; hasta entonces Safeer no envía la contraseña (protección contra el bloqueo de la cuenta). | Le fournisseur a refusé la connexion. Vérifie l’identifiant et le mot de passe puis saisis-les à nouveau - d’ici là, Safeer n’envoie pas le mot de passe (protection contre le blocage du compte). | Il provider ha rifiutato l’accesso. Controlla nome utente e password e inseriscili di nuovo - fino ad allora Safeer non invia la password (protezione dal blocco dell’account). |
| `ZAKLENJEN` | Prijava je bila zavrnjena, zato Safeer gesla ne pošilja več. Ko poverilnice preveriš pri ponudniku, jih vpiši znova. | The login was rejected, so Safeer no longer sends the password. Once you have checked your credentials with the provider, enter them again. | Die Anmeldung wurde abgelehnt, daher sendet Safeer das Passwort nicht mehr. Wenn du die Zugangsdaten beim Anbieter geprüft hast, gib sie erneut ein. | El inicio de sesión fue rechazado, así que Safeer ya no envía la contraseña. Cuando hayas comprobado tus credenciales con el proveedor, vuelve a introducirlas. | La connexion a été refusée, Safeer n’envoie donc plus le mot de passe. Après avoir vérifié tes identifiants auprès du fournisseur, saisis-les à nouveau. | L’accesso è stato rifiutato, quindi Safeer non invia più la password. Dopo aver verificato le credenziali con il provider, inseriscile di nuovo. |
| `NI_ODGOVORA` | Strežnik ni odgovoril. Preveri strežnik, vrata in omrežje. | The server did not respond. Check the server, port and network. | Der Server hat nicht geantwortet. Prüfe Server, Port und Netzwerk. | El servidor no respondió. Revisa el servidor, el puerto y la red. | Le serveur n’a pas répondu. Vérifie le serveur, le port et le réseau. | Il server non ha risposto. Controlla server, porta e rete. |
| `PREVEC_POVEZAV` | Ponudnik javlja preveč hkratnih povezav. Zapri druge programe ali zmanjšaj število povezav. | The provider reports too many simultaneous connections. Close other apps or lower the number of connections. | Der Anbieter meldet zu viele gleichzeitige Verbindungen. Schließe andere Programme oder verringere die Anzahl der Verbindungen. | El proveedor indica demasiadas conexiones simultáneas. Cierra otras aplicaciones o reduce el número de conexiones. | Le fournisseur signale trop de connexions simultanées. Ferme les autres applications ou réduis le nombre de connexions. | Il provider segnala troppe connessioni simultanee. Chiudi altre app o riduci il numero di connessioni. |
| `PREPOGOSTO` | Preverba je mogoča enkrat na minuto. Poskusi znova čez minuto. | You can check once per minute. Try again in a minute. | Eine Prüfung ist einmal pro Minute möglich. Versuche es in einer Minute erneut. | Solo se puede comprobar una vez por minuto. Inténtalo de nuevo en un minuto. | La vérification est possible une fois par minute. Réessaie dans une minute. | La verifica è possibile una volta al minuto. Riprova tra un minuto. |
| `brez_tls_ni_podprt` | Povezava brez TLS ni podprta. Vklopi TLS (vrata 563). | Connections without TLS are not supported. Turn on TLS (port 563). | Verbindungen ohne TLS werden nicht unterstützt. Aktiviere TLS (Port 563). | No se admiten conexiones sin TLS. Activa TLS (puerto 563). | Les connexions sans TLS ne sont pas prises en charge. Active TLS (port 563). | Le connessioni senza TLS non sono supportate. Attiva TLS (porta 563). |
| `usenet ponudnik: neveljaven gostitelj` | Vpiši samo ime strežnika ali naslov IP, brez http:// in vrat. | Enter only the server name or IP address, without http:// or a port. | Gib nur den Servernamen oder die IP-Adresse ein, ohne http:// und Port. | Introduce solo el nombre del servidor o la dirección IP, sin http:// ni puerto. | Saisis uniquement le nom du serveur ou l’adresse IP, sans http:// ni port. | Inserisci solo il nome del server o l’indirizzo IP, senza http:// né porta. |
| `usenet ponudnik: neveljavna vrata` | Vrata morajo biti število od 1 do 65535. | The port must be a number from 1 to 65535. | Der Port muss eine Zahl von 1 bis 65535 sein. | El puerto debe ser un número del 1 al 65535. | Le port doit être un nombre de 1 à 65535. | La porta deve essere un numero da 1 a 65535. |
| `usenet ponudnik: neveljavno uporabnisko ime` | Uporabniško ime ni veljavno. | The username is not valid. | Der Benutzername ist ungültig. | El nombre de usuario no es válido. | Le nom d’utilisateur n’est pas valide. | Il nome utente non è valido. |
| `usenet ponudnik: neveljavno geslo` | Geslo ni veljavno. | The password is not valid. | Das Passwort ist ungültig. | La contraseña no es válida. | Le mot de passe n’est pas valide. | La password non è valida. |
| `usenet ponudnik: najvec_povezav mora biti 1-100` | Število povezav mora biti od 1 do 100. | The number of connections must be from 1 to 100. | Die Anzahl der Verbindungen muss zwischen 1 und 100 liegen. | El número de conexiones debe estar entre 1 y 100. | Le nombre de connexions doit être compris entre 1 et 100. | Il numero di connessioni deve essere compreso tra 1 e 100. |
| `usenet ponudnik: neveljavno ime` | Ime je predolgo ali vsebuje nedovoljene znake. | The name is too long or contains invalid characters. | Der Name ist zu lang oder enthält ungültige Zeichen. | El nombre es demasiado largo o contiene caracteres no válidos. | Le nom est trop long ou contient des caractères non valides. | Il nome è troppo lungo o contiene caratteri non validi. |
| `usenet ponudnik: brez TLS samo z nevarno_brez_tls=True` | Za povezavo brez TLS potrdi opozorilo. | To connect without TLS, confirm the warning. | Bestätige die Warnung, um ohne TLS zu verbinden. | Para conectar sin TLS, confirma la advertencia. | Pour te connecter sans TLS, confirme l’avertissement. | Per connetterti senza TLS, conferma l’avviso. |
| `usenet ponudnik: brez TLS samo do naslova IP v domacem omrezju` | Brez TLS je dovoljen samo naslov IP v domačem omrežju. | Without TLS only an IP address on your home network is allowed. | Ohne TLS ist nur eine IP-Adresse im Heimnetz erlaubt. | Sin TLS solo se permite una dirección IP de tu red doméstica. | Sans TLS, seule une adresse IP de ton réseau local est autorisée. | Senza TLS è consentito solo un indirizzo IP della rete domestica. |
