# Safeer 2.1.192 · Safeer OS 0.5.68 · Predvajalnik 0.2.54

Ta izdaja je gradnja 2.1.191 / Safeer OS 0.5.67 / Predvajalnik 0.2.53 z enim drobnim varnostnim popravkom v brskalniku (F2): **eno uporabnikovo dejanje odpre največ eno zunanjo aplikacijo.**

## Zunanji zagon iz spletne strani porabi dejanje

Stran sme odpreti drugo aplikacijo (namera, npr. trgovino) samo kratko (3 s) po uporabnikovem dejanju: tipka OK na daljincu ali dotik. Prej se je to dejanje po uspešnem zagonu obdržalo, zato bi lahko stran v teh 3 sekundah zagnala še eno aplikacijo. Zdaj se dejanje po uspešnem zagonu porabi; za naslednji zagon je potrebno novo dejanje. Zavrnjen ali neuspel zagon dejanja ne porabi. Pravilo je izluščeno v čist, preizkušen razred `SpletVarnostPravila.PorabljivoDejanje`; skrivanje vrstice brskalnika uporablja ločen čas in se ne spremeni.

Ostalo je kot v 2.1.191 (upravljanje zvočnika v omrežju, mreže v medijskem centru, trda ovira pred zapomnjeno SSL izjemo).
