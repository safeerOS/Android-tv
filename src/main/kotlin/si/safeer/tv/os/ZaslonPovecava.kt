package si.safeer.tv.os

/**
 * Povecava slike racunalnika z dvema prstoma (telefon, tablica). Gumbi na racunalniku so na majhnem
 * zaslonu drobni; kdor racunalnik upravlja z dotikom, si del zaslona pribliza in potem zadene, kar
 * vidi pod prstom. Tu je samo racun (brez Androida), da ga lahko preizkusimo v navadnem JVM.
 *
 * Slika v osnovni legi lezi v pravokotniku (osnovaL, osnovaT, osnovaW, osnovaH) znotraj povrsine
 * (sirina x visina). Povecana je [merilo]-krat, njen levi zgornji kot je ([levo], [vrh]). Povecana
 * slika povrsino vedno pokrije, kolikor jo lahko: ce je sirsa od nje, ob robu ni praznine; ce je
 * ozja, je na sredini. Izjema je desni rob: tam sta cez sliko gumba (meni seje, 1x), zato se sme
 * povecana slika odmakniti od njega za [odmikDesno] - sicer ura in ikone v desnem spodnjem kotu
 * racunalnika pod gumboma ne bi bile dosegljive prav takrat, ko jih je najlazje zadeti.
 *
 * Dva prsta:
 *  - nepovecana slika: prsta narazen ali skupaj = povecava; vzporeden poteg gor ali dol = drsenje
 *    vsebine na racunalniku (kolesce), kot doslej;
 *  - povecana slika: prsta premikata sliko (in jo povecujeta); ko slika navpicno pride do roba,
 *    se ostanek potega vrne kot drsenje vsebine - uporabnik bere naprej z isto kretnjo.
 * Odlocitev velja do konca kretnje, da slika med drsenjem ne "diha".
 */
class ZaslonPovecava(private val gostota: Float) {

    enum class Nacin { NEODLOCENO, DRSENJE, POVECAVA }

    var merilo = 1f
        private set
    var levo = 0f
        private set
    var vrh = 0f
        private set
    var nacin = Nacin.NEODLOCENO
        private set

    private var sirina = 0f
    private var visina = 0f
    private var osnovaL = 0f
    private var osnovaT = 0f
    private var osnovaW = 0f
    private var osnovaH = 0f
    private var odmikDesno = 0f

    private var zacetniRazmik = 1f
    private var zacetnoMerilo = 1f
    private var zacetekY = 0f
    private var zadnjiX = 0f
    private var zadnjiY = 0f
    private var razmikSledi = false

    val povecano: Boolean get() = merilo > 1.01f

    /** Mere povrsine in osnovna lega slike (ob zacetku pretoka, vrtenju, deljenem zaslonu). Povecava ostane. */
    fun nastaviOsnovo(sirina: Int, visina: Int, l: Int, t: Int, w: Int, h: Int) {
        this.sirina = sirina.toFloat(); this.visina = visina.toFloat()
        osnovaL = l.toFloat(); osnovaT = t.toFloat(); osnovaW = w.toFloat(); osnovaH = h.toFloat()
        if (!povecano) ponastavi() else postavi(levo, vrh)
    }

    /** Sirina pasu ob desnem robu povrsine, ki ga prekrivata gumba (tocke povrsine). */
    fun nastaviOdmikDesno(tocke: Float) {
        odmikDesno = maxOf(0f, tocke)
        if (povecano) postavi(levo, vrh)
    }

    /** Nazaj na cel zaslon racunalnika. */
    fun ponastavi() {
        merilo = 1f; levo = osnovaL; vrh = osnovaT
        nacin = Nacin.NEODLOCENO
    }

    /** Povecava na dano merilo okoli tocke povrsine (gumb v meniju: okoli sredine). */
    fun povecajNa(novo: Float, okoliX: Float, okoliY: Float) {
        if (osnovaW <= 0f || osnovaH <= 0f) return
        val m = novo.coerceIn(1f, NAJVEC)
        val k = m / merilo
        merilo = m
        postavi(okoliX - (okoliX - levo) * k, okoliY - (okoliY - vrh) * k)
        if (!povecano) ponastavi()
    }

    /** Drugi prst se je dotaknil zaslona: sredina med prstoma in razmik med njima (tocke povrsine). */
    fun zacni(sredinaX: Float, sredinaY: Float, razmik: Float) {
        nacin = if (povecano) Nacin.POVECAVA else Nacin.NEODLOCENO
        zacetniRazmik = maxOf(razmik, 1f)
        zacetnoMerilo = merilo
        zacetekY = sredinaY
        zadnjiX = sredinaX; zadnjiY = sredinaY
        razmikSledi = false
    }

    /**
     * Prsta sta se premaknila. Vrne navpicni premik (v tockah povrsine, navzdol pozitivno), ki naj
     * postane drsenje vsebine na racunalniku; 0, ce je premik porabila slika ali se nismo odlocili.
     */
    fun premakni(sredinaX: Float, sredinaY: Float, razmik: Float): Float {
        if (osnovaW <= 0f || osnovaH <= 0f) return 0f
        if (nacin == Nacin.NEODLOCENO) {
            val sprememba = kotlin.math.abs(razmik - zacetniRazmik)
            if (sprememba > PRAG_RAZMIKA_DP * gostota && sprememba > zacetniRazmik * PRAG_RAZMIKA_DELEZ) {
                nacin = Nacin.POVECAVA
                // Od tod naprej: brez skoka za pot, ki sta jo prsta naredila do odlocitve.
                zacetniRazmik = maxOf(razmik, 1f); zacetnoMerilo = merilo; razmikSledi = true
                zadnjiX = sredinaX; zadnjiY = sredinaY
            } else if (kotlin.math.abs(sredinaY - zacetekY) > PRAG_DRSENJA_DP * gostota) {
                nacin = Nacin.DRSENJE
                zadnjiY = sredinaY
                return sredinaY - zacetekY
            } else {
                return 0f
            }
        }
        if (nacin == Nacin.DRSENJE) {
            val d = sredinaY - zadnjiY
            zadnjiY = sredinaY
            return d
        }
        // Povecava in premik hkrati. Dokler se razmik komaj spremeni, merila ne popravljamo: med
        // premikanjem povecane slike prsta nikoli nista popolnoma enako narazen.
        if (!razmikSledi && kotlin.math.abs(razmik / zacetniRazmik - 1f) > MRTVI_PAS) {
            razmikSledi = true
            zacetniRazmik = maxOf(razmik, 1f); zacetnoMerilo = merilo
        }
        val novo = if (razmikSledi) (zacetnoMerilo * razmik / zacetniRazmik).coerceIn(1f, NAJVEC) else merilo
        val k = novo / merilo
        // Tocka slike, ki je bila pod sredino prstov, ostane pod njo.
        val zeljenL = sredinaX - (zadnjiX - levo) * k
        val zeljenT = sredinaY - (zadnjiY - vrh) * k
        merilo = novo
        postavi(zeljenL, zeljenT)
        zadnjiX = sredinaX; zadnjiY = sredinaY
        // Slika je navpicno na robu (ali navpicno sploh nima kam) in prsta vleceta naprej: ostanek je
        // drsenje vsebine. Med samim povecevanjem (razmik se spreminja) ne drsimo - to bi bila dva
        // ucinka ene kretnje.
        val ostanek = zeljenT - vrh
        return if (povecano && k == 1f) ostanek else 0f
    }

    /** Prsta sta se dvignila. Skoraj nepovecana slika skoci nazaj na cel zaslon. */
    fun koncaj() {
        if (merilo < SKOK_NAZAJ) ponastavi()
        nacin = Nacin.NEODLOCENO
    }

    private fun postavi(l: Float, t: Float) {
        levo = omeji(l, osnovaW * merilo, sirina, osnovaL, osnovaW, odmikDesno)
        vrh = omeji(t, osnovaH * merilo, visina, osnovaT, osnovaH, 0f)
    }

    /**
     * Lega po eni osi. Slika, vecja od povrsine, jo pokriva (brez praznine ob robu). Manjsa od
     * povrsine ostane na sredini - pri merilu 1 je to natanko osnovna lega.
     */
    private fun omeji(lega: Float, velikost: Float, povrsina: Float, osnova: Float, osnovnaVelikost: Float,
                      odmikNaKoncu: Float): Float {
        if (povrsina <= 0f) return osnova
        return if (velikost >= povrsina) lega.coerceIn(povrsina - velikost - odmikNaKoncu, 0f)
        else osnova + (osnovnaVelikost - velikost) / 2f
    }

    companion object {
        /** Stirikrat je dovolj, da je tudi najmanjsi gumb racunalnika vecji od prsta. */
        const val NAJVEC = 4f
        const val PRAG_RAZMIKA_DP = 22f
        const val PRAG_RAZMIKA_DELEZ = 0.08f
        const val PRAG_DRSENJA_DP = 12f
        const val MRTVI_PAS = 0.06f
        const val SKOK_NAZAJ = 1.06f
    }
}
