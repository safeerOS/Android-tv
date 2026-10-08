# Safeer 2.1.196 · Safeer OS 0.5.72 · Predvajalnik 0.2.58

**Strani še vedno zaznajo blokator? Zaščita je zdaj temeljitejša.** Nekatere strani (npr. internetni radio) so kljub prejšnji posodobitvi pokazale »zaznali smo blokator oglasov«. Splošna zaščita (enaka za vse strani, brez receptov za posamezne) zdaj odgovori tudi na preverbe, ki so jih prej zgrešile:

- **Skriti vabni elementi** javijo običajen položaj in velikost (tudi preverba `offsetParent`).
- **Sledilne slikice** (npr. analitične pike) se naložijo, namesto da bi javile napako.
- **Oglasna knjižnica Google (`googletag`)** je videti pripravljena, zato strani ne čakajo nanjo v nedogled in ne sklepajo, da je blokirana.
- Seznam oglasnih in sledilnih naslovov, ki odgovorijo kot običajno, je razširjen (npr. IMA, Google Analytics, Tag Manager).

Blokiranje oglasov in groženj ostaja nespremenjeno; YouTube je izvzet. Dodan je preizkus, ki ponovi te preverbe.
