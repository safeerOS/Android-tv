package si.safeer.tv.link

/**
 * Meje prenosov, ki jih druga naprava v Linku zacne na TEJ napravi: `storage.put` (Shramba) in `video.transcode`
 * (Pretvorba). Cista pravila brez Androida (tests/PrenosPravilaTest.kt).
 *
 * Pregled 10. 11. 2026 (1.2, 3.3): naprava z odprtimi datotekami ali predvajalnikom je lahko napovedala velikost 0 in
 * poslala poljubno dolg tok (disk se je polnil do konca), z vsakim ukazom je nastala nova nit, seznam opravil pa je
 * izrival nakljucno - tudi prenos, ki je se tekel. Zdaj: napovedana velikost je obvezna in omejena, branje se ustavi,
 * ko prebrano preseze napovedano, hkrati tecejo najvec [NAJVEC_PRENOSOV] prenosi in [NAJVEC_PRETVORB] pretvorba, iz
 * seznama gredo samo koncana opravila.
 */
object PrenosPravila {
    private const val GB = 1024L * 1024 * 1024

    /** Najvecja datoteka za Shrambo; nad tem je napoved skoraj gotovo napaka ali zloraba (prostor preverimo posebej). */
    const val NAJVEC_SHRAMBA_B = 64 * GB
    /** Najvecji izvirnik za pretvorbo: v predpomnilniku sta hkrati izvirnik in pretvorjeni video. */
    const val NAJVEC_PRETVORBA_B = 16 * GB
    const val NAJVEC_PRENOSOV = 2
    const val NAJVEC_PRETVORB = 1
    /** Koliko opravil (tekocih in koncanih) hranimo za `storage.status` oziroma `video.status`. */
    const val HRANI_PRENOSOV = 32
    const val HRANI_PRETVORB = 16

    const val KODA_ZAHTEVA = "napacna_zahteva"
    const val KODA_PREVELIKA = "prevelika"
    const val KODA_PROSTOR = "ni_prostora"
    const val KODA_PREOBREMENJEN = "preobremenjen"
    /** Vir je poslal vec, kot je napovedal. */
    const val KODA_PRESEGA = "presega_napoved"

    /**
     * Ali napoved velikosti dovoli prenos; null = da, sicer koda zavrnitve. [prosto] = prosti bajti (-1 = meritve ni:
     * »ne vem« ni »da«), [rezerva] = kar naprava obdrzi zase, [obljubljeno] = kar se morajo prenesti ze tekoci prenosi,
     * [mnozitelj] = kolikokrat napovedana velikost zasede disk (pretvorba: izvirnik + pretvorjeni video = 2).
     */
    fun preveriVelikost(velikost: Long, najvec: Long, prosto: Long, rezerva: Long, obljubljeno: Long = 0L, mnozitelj: Int = 1): String? {
        if (velikost <= 0L) return KODA_ZAHTEVA
        if (velikost > najvec) return KODA_PREVELIKA
        if (prosto < 0L || rezerva < 0L || obljubljeno < 0L) return KODA_PROSTOR
        // velikost <= najvec (64 GiB), mnozitelj majhen: vsota ne more cez Long.
        if (prosto - rezerva - obljubljeno < velikost * mnozitelj) return KODA_PROSTOR
        return null
    }

    /** Bralna zanka: ali je s kosom [kos] prebrano ([doslej] + kos) preseglo napovedano velikost. */
    fun presega(doslej: Long, kos: Int, napovedano: Long): Boolean = doslej + kos > napovedano

    /** Glava Content-Length ([dolzina], -1 = ni znana) ze pove, da bo telo daljse od napovedi. */
    fun glavaPresega(dolzina: Long, napovedano: Long): Boolean = dolzina > napovedano

    /** Ali je tekocih opravil ze toliko, da novega ne sprejmemo (koda [KODA_PREOBREMENJEN]). */
    fun preobremenjen(tekocih: Int, najvec: Int): Boolean = tekocih >= najvec

    /** Med pretvorbo: prostega je manj od rezerve naprave - pretvorjeni video je vecji od ocene, ustavimo. */
    fun zmanjkujeProstora(prosto: Long, rezerva: Long): Boolean = prosto in 0L until rezerva

    /** Opravilo za izrinjenje: [nastal] = zaporedna stevilka nastanka (vecja = novejse). */
    class Opis(val id: String, val nastal: Long, val koncano: Boolean)

    /**
     * Katera opravila gredo iz seznama, da jih ostane najvec [najvec]: samo KONCANA (uspeh ali napaka), najstarejsa
     * najprej. Tekoce opravilo ne gre nikoli - prej je `keys.firstOrNull()` vzel nakljucnega in racunalnik je za prenos,
     * ki je se tekel, dobil »ni_opravila«. Tekocih je najvec toliko, kot jih dovoli [preobremenjen].
     */
    fun izrini(opravila: List<Opis>, najvec: Int): List<String> {
        val prevec = (opravila.size - najvec).coerceAtLeast(0)
        return opravila.filter { it.koncano }.sortedBy { it.nastal }.take(prevec).map { it.id }
    }

    /** Napaka prenosa s kodo, ki gre v stanje opravila (brez besedila izjeme). */
    class Napaka(val koda: String) : Exception(koda)
}
