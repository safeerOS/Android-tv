# Safeer Media — lokalna priporočila (v6)

Safeer Media lahko zdaj uredi vsebine iz uporabnikovih lastnih spletnih virov v vrstico **Zate**.

## Zasebnost

Priporočila se izračunajo samo na napravi. Safeer ne pošilja zgodovine gledanja, priljubljenih ali profila priporočil na Safeer strežnik oziroma v zunanji priporočilni API.

## Signali

Algoritem uporablja samo podatke, ki jih Safeer že ima lokalno:

- `MediaNapredek` (Nadaljuj gledanje),
- priljubljene videe,
- nedavno predvajane videe,
- vrsto vsebine (film/serija),
- strukturirano zvrst, kadar jo vir objavi,
- izvor vsebine.

Kandidati za priporočila so izključno trenutne vsebine iz spletnih virov, ki jih je uporabnik sam dodal. Že gledana vsebina je močno znižana, ker ima nadaljevanje svojo ločeno vrstico.

Če še ni lokalnih signalov, Safeer ne izmišljuje osebnega profila in ne prikazuje lažne vrstice »Zate«; ostane »Priljubljeno v tvojih virih«.
