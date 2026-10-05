package si.safeer.tv.cast

/** Kam po koncu deljenega zaslona, ki ga je v brskalniku odprl Safeer sam (ne uporabnik). */
object PoDeljenju {
    /**
     * Ali naj se brskalnik zapre (finish), namesto da gre vsa naloga v ozadje (moveTaskToBack).
     *
     * Zapremo ga le, ce je nastal samo za ta zaslon, ce je bil Safeer takrat na zaslonu in ce je pod njim v isti
     * nalogi druga dejavnost Safeerja (telefon in tablica: Domov Safeer OS je v isti nalogi kot brskalnik) - tako
     * uporabnik ostane natanko tam, kjer je bil. V vseh drugih primerih gre naloga v ozadje kot prej:
     *  - brskalnik je obstajal ze prej (uporabnikovi zavihki - ne zapiramo);
     *  - uporabnik je bil v drugi aplikaciji in je odprl obvestilo (naloga v ozadje ga vrne tja);
     *  - brskalnik je v nalogi sam (televizor: Safeer OS je svoja naloga in se pokaze, ko gre brskalnik v ozadje).
     */
    fun zapriBrskalnik(nastalZaTaZaslon: Boolean, safeerNaZaslonu: Boolean, samVNalogi: Boolean): Boolean =
        nastalZaTaZaslon && safeerNaZaslonu && !samVNalogi
}
