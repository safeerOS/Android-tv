package si.safeer.tv

/** Ciste odlocitve za TvKeyRouter (popravki TV-2, 15. 11. 2026); preizkus tests/PopravkiTv2Test.kt. */
object UsmerjevalnikTipkPravila {
    data class NazajSloj(val pogoltni: Boolean, val zastavica: Boolean)

    /**
     * Nazaj, ki je zaprl prekrivni sloj (preklopnik zavihkov, iskanje po strani): njegov spust in ponovitve pogoltnemo.
     * P8: zastavico je prej pocistil samo spust; ce ta ni prisel do usmerjevalnika (zaslon ugasnjen, nativno predvajanje
     * pogoltne vse razen DOWN), je bil naslednji Nazaj kjerkoli pogoltnjen brez dejanja. Nov pritisk (DOWN, repeatCount 0)
     * pomeni, da se je spust izgubil: zastavica ne velja vec in tipka gre naprej.
     */
    fun nazajPoSloju(zastavica: Boolean, dol: Boolean, ponovitev: Int): NazajSloj = when {
        !zastavica -> NazajSloj(pogoltni = false, zastavica = false)
        dol && ponovitev == 0 -> NazajSloj(pogoltni = false, zastavica = false)
        dol -> NazajSloj(pogoltni = true, zastavica = true)
        else -> NazajSloj(pogoltni = true, zastavica = false)
    }
}
