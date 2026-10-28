package si.safeer.tv.os

/**
 * Naloga 42 F (27. 10. 2026): beta novega TV zaganjalnika. Gradnja z -PsafeerBeta=N ima razlicico »<x.y.z>-beta.N«
 * (build.gradle) in v Nastavitvah stikalo »Nov zaganjalnik (beta)«. Vrnitev na starega spremeni samo stikalo - vse druge
 * nastavitve (tema, ozadje, priljubljene, vreme, lupa) ostanejo. Ciste odlocitve (tests/ZaganjalnikBetaPravilaTest.kt).
 */
object ZaganjalnikBetaPravila {
    const val KLJUC = "zaganjalnik_beta"

    /** Pripona razlicice za -PsafeerBeta (ista pravila kot build.gradle): "-beta.N" za N 1-9999, prazno brez bete. */
    fun pripona(beta: String?): String? {
        val b = beta?.trim().orEmpty()
        if (b.isEmpty()) return ""
        return if (Regex("[1-9][0-9]{0,3}").matches(b)) "-beta.$b" else null
    }

    /** Beta po imenu razlicice (npr. »0.5.111-beta.1«); 0 = ni beta. */
    fun stevilkaBete(imeRazlicice: String): Int =
        Regex("-beta\\.([1-9][0-9]{0,3})(?:$|-)").find(imeRazlicice)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * Stikalo v Nastavitvah: samo na televizorju; v beta gradnji vedno, sicer samo, ce je novi zaganjalnik vklopljen (da se
     * uporabnik po namestitvi izdaje brez bete lahko vrne na starega).
     */
    @Suppress("UNUSED_PARAMETER")
    fun stikaloVidno(jeTelevizor: Boolean, betaGradnja: Boolean, vklopljen: Boolean): Boolean = false

    /**
     * Lastnik 28. 10. 2026 (glavna izdaja 2.1.233 / 0.5.111): »samo novi« - na televizorju je vedno novi zaganjalnik
     * (Pokrajina); stikala za vrnitev na starega ni vec. Telefon in tablica imata svoj domaci zaslon.
     */
    fun vklopljen(jeTelevizor: Boolean): Boolean = jeTelevizor

    /** Preklop spremeni samo kljuc bete; vse druge nastavitve ostanejo nespremenjene. */
    fun poPreklopu(nastavitve: Map<String, Any?>, vklop: Boolean): Map<String, Any?> = nastavitve + (KLJUC to vklop)

    /**
     * Lastnik 27. 10. 2026: z novim zaganjalnikom (beta) na televizorju zasloni Safeer OS (Sporocila, Datoteke, Nastavitve
     * ...) nimajo stranske vrstice gumbov - ostane videz novega namizja, nazaj gre s tipko Nazaj. Brez bete in na dotik
     * ostane vrstica kot prej.
     */
    fun stranskaVrstica(jeTelevizor: Boolean, betaVklopljena: Boolean): Boolean = !(jeTelevizor && betaVklopljena)
}
