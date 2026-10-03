package si.safeer.tv.os

/**
 * Zasebni dodatki (3. 10. 2026): dodatek, ki se v manifestu sam oznaci z `behaviorHints.adult`, ostane na napravi,
 * kjer ga je uporabnik dodal. Njegova vsebina se ne mesa v skupne police, mreze, priporocila in iskanje, ogled se ne
 * zapise v zgodovino in ne ponudi drugi napravi. Uporabnik ga odpre izrecno (Moji viri -> dodatek). Razlog: kar
 * nekdo doda na osebni napravi, se ne sme samo pojaviti na skupnem zaslonu. Pravila so cista - preizkus
 * tests/ZasebniDodatkiTest.kt.
 */
object ZasebniDodatki {
    /** Vir gre drugi napravi (in z nje sem) samo, ce ni dodatek ali ce VEMO, da dodatek ni zaseben; null = se ne vemo. */
    fun smeMedNaprave(jeDodatek: Boolean, zaseben: Boolean?): Boolean = !jeDodatek || zaseben == false

    /**
     * Zaseben dodatek, ki je sem prisel z usklajevanjem, preden je veljalo to pravilo (cas 1 = prevzet vir iz casa
     * pred usklajevanjem), tu izgine - brez sledi izbrisa, da na izvorni napravi ostane.
     */
    fun prevzetOdstranimo(zaseben: Boolean?, cas: Long): Boolean = zaseben == true && cas == 1L
}
