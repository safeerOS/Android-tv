package si.safeer.tv.os

/**
 * Komu na isti napravi zaupamo kot Safeer sosedu (slepi pregled TV 18. 11. 2026, S1): samo paketu, ki je omogocen IN ima
 * isti podpis kot mi. Prej je zadostovalo ime paketa: aplikacija brez dovoljenj z imenom manjkajocega soseda (npr. si.safeer.os)
 * je tiho izklopila Scit in Hub ter odgovarjala namesto njega. Vsi Safeer paketi so podpisani z istim kljucem (izmerjeno
 * 18. 11. 2026 na TV: si.safeer.os, tv, player, trgovina imajo isto potrdilo).
 */
object SosedZaupanje {
    fun jeZaupan(paket: String, mojPaket: String, omogocen: Boolean, podpisUjema: Boolean): Boolean =
        omogocen && (paket == mojPaket || podpisUjema)
}
