package si.safeer.tv.os

/**
 * Naloga 133: kakovost glede na hitrost povezave. OK na kartici je na TV izbral 4K REMUX 77 GB (Inception) - na nasi
 * povezavi je tekel, na pocasnejsi bi zatikal. Tok, katerega bitna hitrost (velikost / dolzina) presega [DELEZ] izmerjene
 * hitrosti, gre za tiste, ki ustrezajo. Brez meritve ([HitrostPovezave]) ostane izbira, kot je. Ciste odlocitve
 * (preizkus: tests/KakovostPravilaTest.kt).
 */
object KakovostPravila {
    /** Delez izmerjene hitrosti, ki ga tok sme porabiti (rezerva za nihanje povezave in zvocne sledi). */
    const val DELEZ = 0.7

    /** Dolzine nimamo v podatkih dodatkov: film se steje 130 min, epizoda 50 min (raje vec kot manj - manjsa bitna hitrost). */
    fun minut(jeEpizoda: Boolean): Int = if (jeEpizoda) 50 else 130

    /** Bitna hitrost toka v bitih na sekundo; 0 = velikosti ne poznamo. */
    fun bitnaHitrost(gb: Double, minut: Int): Long =
        if (gb <= 0.0 || minut <= 0) 0L else (gb * 1_000_000_000.0 * 8.0 / (minut * 60.0)).toLong()

    /** Ali tok ustreza povezavi: neznana velikost ali hitrost vedno ustreza. */
    fun ustreza(gb: Double, minut: Int, hitrost: Long): Boolean {
        val b = bitnaHitrost(gb, minut)
        return b == 0L || hitrost <= 0L || b <= hitrost * DELEZ
    }

    /**
     * Vrstni red za povezavo: kar ustreza, ostane v svojem redu (kakovost po TokIzbira) in gre naprej; kar ne, za njim -
     * najprej manjse (vecja moznost, da tece). Brez meritve vrstni red ostane. Vrne indekse v [gb].
     */
    fun urediZaHitrost(gb: List<Double>, minut: Int, hitrost: Long): List<Int> {
        if (hitrost <= 0L) return gb.indices.toList()
        val da = gb.indices.filter { ustreza(gb[it], minut, hitrost) }
        val ne = gb.indices.filter { !ustreza(gb[it], minut, hitrost) }.sortedBy { gb[it] }
        return da + ne
    }

    /** Meritev se zapomni samo iz dovolj velikega vzorca (video, ne posamezen kos zvoka). */
    const val NAJMANJSI_VZOREC = 2L * 1024 * 1024

    fun vzorecVelja(bajtov: Long): Boolean = bajtov >= NAJMANJSI_VZOREC

    /**
     * Hitrost iz vzorca nalaganja (prejeti bajti v casu, ko je predvajalnik nalagal medpomnilnik): predvajalnik nalaga v
     * sunkih s polno hitrostjo, zato je to hitrost povezave do streznika toka. 0 = vzorec premajhen ali prekratek.
     */
    fun hitrostIzVzorca(bajtov: Long, ms: Long): Long =
        if (!vzorecVelja(bajtov) || ms < 1_000) 0L else bajtov * 8_000 / ms

    /** Glajenje: nova meritev steje 30 %, da en hiter ali pocasen trenutek ne obrne izbire. */
    fun zgladi(stara: Long, nova: Long): Long = if (stara <= 0L) nova else (stara * 0.7 + nova * 0.3).toLong()

    /** Meritev velja teden dni (povezava doma se ne spreminja pogosto; po selitvi naprave se popravi z novimi vzorci). */
    const val VELJA_MS = 7L * 24 * 3_600_000

    fun velja(kdajMs: Long, zdajMs: Long): Boolean = zdajMs - kdajMs in 0 until VELJA_MS
}
