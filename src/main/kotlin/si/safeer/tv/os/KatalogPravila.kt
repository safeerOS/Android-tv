package si.safeer.tv.os

object KatalogPravila {

    interface Naslov {
        val id: String
        val zadnjiVideni: Long
        fun sCasom(cas: Long): Naslov
    }

    /**
     * Svez pregled vira najvec ENKRAT NA DAN (v ozadju), ne ob vsakem odprtju.
     */
    fun potrebenSvez(zadnjiSvez: Long, zdaj: Long): Boolean {
        return zadnjiSvez == 0L || zdaj - zadnjiSvez >= 24L * 60 * 60 * 1000L
    }

    /**
     * Stari ostanejo na mestu, novi na zacetek police (kot najnovejsi).
     * zadnjiVideni se osvezi za tiste, ki so v novih.
     * Odstrani tiste z zadnjiVideni starejsim od 7 dni.
     */
    fun <T : Naslov> zdruzi(stare: List<T>, nove: List<T>, zdaj: Long): List<T> {
        val osemDni = 7L * 24 * 60 * 60 * 1000L
        val noviKljuc = nove.associateBy { it.id }
        
        // Zdruzi stare z osvezenim zadnjiVideni
        val obdelaniStari = stare.map { stara ->
            val vNovih = noviKljuc[stara.id]
            if (vNovih != null) stara.sCasom(zdaj) as T else stara
        }.filter { zdaj - it.zadnjiVideni <= osemDni }
        
        // Novi naslovi, ki jih preje ni bilo
        val stariIdji = stare.map { it.id }.toSet()
        val samoNovi = nove.filter { it.id !in stariIdji }.map { it.sCasom(zdaj) as T }
        
        return samoNovi + obdelaniStari
    }
}
