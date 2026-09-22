# Safeer Internet Gateway v0.10 — Android Application Gateway

Telefon ni tethering/NAT usmerjevalnik. Safeer na telefonu sprejme `internet.open` samo po seznanjenem TLS Safeer Link WebSocketu, izbere dovoljeno Android `Network`, razresi cilj prek te Network, zavrne lokalne/zasebne naslove, odpre svoj TCP socket in ga pred connectom veze z `Network.bindSocket()`.

Protokol: `internet.open`, `internet.opened`, `internet.data`, `internet.close`, `internet.error`. Hub v sender polje vedno vpise dejansko povezano napravo. Kosi so omejeni na 24 KiB, telefon na 8 hkratnih tokov. Cellular je opt-in, roaming locen opt-in, dejanski bajti v obe smeri se stejejo in se trajno hranijo; ob dosezeni omejitvi se mobilni tokovi zaprejo.

`internet.gateway` se oglasi samo, ce je gateway uporabnisko vklopljen. Privzeto je izklopljen. v0.10 ne uporablja Android tetheringa, ne spreminja default route in ne zajema prometa drugih aplikacij na telefonu.

Opomba za naslednji korak: dodati telefonski UI za gateway/mobile/roaming/limit in requester adapter v Linux Controlu/TV/tablici za ta isti tokovni protokol. Trenutni v0.10 je provider/data-plane na telefonu in hub forwarding; brez requester adapterja se sam od sebe se ne uporablja kot sistemski internet drugih naprav.

Telefonske nastavitve imajo zdaj vrstico **Internet prek Safeer Linka**. Uporabnik posebej vklopi gateway, mobilne podatke, roaming in omejitev MB. Sprememba sprozi ponovno registracijo Link povezave, zato se `internet.gateway` pojavi/izgine brez ponovne namestitve aplikacije.
