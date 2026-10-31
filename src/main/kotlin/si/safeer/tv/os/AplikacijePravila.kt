package si.safeer.tv.os

object AplikacijePravila {
    enum class DejanjeOdstranitve { ODSTRANI, ONEMOGOCI }

    /**
     * Določi, katero dejanje ponuditi za paket:
     * - ONEMOGOCI za sistemske aplikacije, ki niso bile posodobljene.
     * - ODSTRANI za vse ostale (uporabniške ali posodobljene sistemske).
     */
    fun dejanjeOdstranitve(jeSistemska: Boolean, jePosodobljenaSistemska: Boolean): DejanjeOdstranitve {
        return if (jeSistemska && !jePosodobljenaSistemska) DejanjeOdstranitve.ONEMOGOCI else DejanjeOdstranitve.ODSTRANI
    }
}
