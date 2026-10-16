package si.safeer.tv.os

/**
 * Seznam »Povezani zasloni« na telefonu in tablici: katere naprave iz Safeer Linka pokazemo, v kaksnem stanju in v
 * kaksnem vrstnem redu. Cista pravila brez Androida (tests/SeznamZaslonovTest.kt); okno je v [PovezaniZasloni].
 *
 * Lastnik, 16. 10. 2026: kartica je vedno na domacem zaslonu, ob dotiku pa so na seznamu VSE naprave z zaslonom
 * (racunalniki, tudi Windows, televizor, tablica, drugi telefoni), ki jih ta naprava sme gledati. O dovoljenju odloca
 * vsaka naprava sama (core/link_dostop.py, cast/DostopPravila.kt); ta naprava ga izve le iz odgovora na tiho preverbo
 * v ozadju ([ProsnjaDostopa.V_OZADJU] - racunalnik ob njej ne odpre obvestila). Zato:
 *  - naprava brez dovoljenja ni nikoli prikazana kot dostopna; racunalnik, ki prosnje zna, ponudi »prosi za dostop«
 *    (ista pot kot do zdaj: zaslon racunalnika, potrditev »Dovoli« na racunalniku), prosnja, ki ze caka ali je
 *    zavrnjena, se pokaze kot taka; naprava brez dovoljenja in brez prosnje se ne pokaze;
 *  - naprava, ki je bila na seznamu, pa je zdaj ni v Linku, ostane oznacena »brez povezave« (ne izgine);
 *  - ta naprava sama (in njeni sorodniki z istim kljucem) ni nikoli na seznamu.
 */
object SeznamZaslonov {
    /** Zmoznost racunalnika, ki deli namizje (Safeer Control, Linux in Windows). */
    const val ZMOZNOST_NAMIZJE = "desktop"
    /** Naprava Android, ki deli cel zaslon (link/Daljinec.ZMOZNOST_DELI_ZASLON - tu prepisano, da pravila ostanejo brez Androida). */
    const val ZMOZNOST_DELI_ZASLON = "zaslon.deli"
    /** Toliko naprav brez povezave si zapomnimo (najnovejse ostanejo). */
    const val NAJVEC_ZNANIH = 16

    enum class Dostop {
        /** Preverba se ni odgovorila. Dotik poskusi (dovoljenje preveri naprava sama). */
        PREVERJAM,
        /** Naprava je odgovorila: ta naprava sme gledati njen zaslon. */
        DOSTOPNO,
        /** Prosnja za dostop caka na potrditev na tisti napravi. */
        CAKA,
        /** Uporabnik je na tisti napravi dostop zavrnil. */
        ZAVRNJENO,
        /** Racunalnik zna prosnjo, a je se ni: ponudimo »prosi za dostop« (obvestilo na racunalniku). */
        PROSNJA,
        /** Brez dovoljenja in brez prosnje (ali deljenje izklopljeno): ne pokazemo. */
        SKRITO,
        /** Preverba ni dobila odgovora (stara razlicica, potek). Dotik poskusi; dovoljenje preveri naprava sama. */
        NI_ODGOVORA,
    }

    /** Naprava v Linku, kolikor jo pravila potrebujejo (iz LinkOdjemalec.Naprava). */
    data class Naprava(val id: String, val ime: String, val platforma: String, val zmoznosti: List<String>,
                       /** Fizicna naprava (kljuc); sorodniki z istim kljucem so ena naprava. Prazno brez kljuca. */
                       val fizicna: String = "")

    /** Naprava, ki je bila na seznamu (shramba za »brez povezave«). */
    data class Znana(val id: String, val ime: String, val platforma: String, val racunalnik: Boolean,
                     val fizicna: String = "", val dostop: Dostop = Dostop.DOSTOPNO)

    data class Vnos(val id: String, val ime: String, val platforma: String, val racunalnik: Boolean,
                    val povezana: Boolean, val dostop: Dostop) {
        /** Dotik nekaj naredi: odpre zaslon, prosi zanj ali pocaka na potrditev. Zavrnjeno in brez povezave le pojasnita. */
        val dotik: Boolean get() = povezana && dostop != Dostop.ZAVRNJENO && dostop != Dostop.SKRITO
    }

    enum class Prazno {
        /** Safeer Link ni povezan in nobene naprave si se ne zapomnimo. */
        NI_LINKA,
        /** V Linku ni nobene druge naprave z zaslonom. */
        NI_NAPRAV,
        /** Naprave z zaslonom so, a nobena tej napravi ne dovoli gledanja. */
        NI_DOVOLJENJ,
    }

    fun zmoreZaslon(zmoznosti: List<String>): Boolean =
        ZMOZNOST_NAMIZJE in zmoznosti || ZMOZNOST_DELI_ZASLON in zmoznosti

    fun jeRacunalnik(zmoznosti: List<String>): Boolean = ZMOZNOST_NAMIZJE in zmoznosti

    /**
     * Stanje iz odgovora na tiho preverbo. [ok]: polje "ok" (null = odgovora ni bilo), [koda]/[razlog]/[prosnja] kot v
     * [ProsnjaDostopa.stanje]. [racunalnik]: naprava Android prosnje za dostop ne pozna, zato je njena zavrnitev
     * vedno SKRITO.
     */
    fun izOdgovora(ok: Boolean?, koda: String?, razlog: String?, prosnja: String?, racunalnik: Boolean): Dostop {
        if (ok == null) return Dostop.NI_ODGOVORA
        val stanje = ProsnjaDostopa.stanje(koda, razlog, prosnja)
        return when (stanje) {
            null -> if (ok) Dostop.DOSTOPNO else Dostop.NI_ODGOVORA
            ProsnjaDostopa.Stanje.CAKA -> if (racunalnik) Dostop.CAKA else Dostop.SKRITO
            ProsnjaDostopa.Stanje.ZAVRNJENO -> if (racunalnik) Dostop.ZAVRNJENO else Dostop.SKRITO
            ProsnjaDostopa.Stanje.NI_DOSTOPA -> if (racunalnik) Dostop.PROSNJA else Dostop.SKRITO
            ProsnjaDostopa.Stanje.IZKLOPLJENO -> Dostop.SKRITO
        }
    }

    /** Sorodniki z istim kljucem so ena naprava; ostane tisti z vec zmoznostmi (kot LinkOdjemalec.drugeZaPrikaz). */
    private fun brezPodvojenih(naprave: List<Naprava>): List<Naprava> {
        val izhod = LinkedHashMap<String, Naprava>()
        for (n in naprave) {
            val kljuc = n.fizicna.ifBlank { "id:" + n.id }
            val prej = izhod[kljuc]
            if (prej == null || n.zmoznosti.size > prej.zmoznosti.size) izhod[kljuc] = n
        }
        return izhod.values.toList()
    }

    /** Naprave z zaslonom v Linku, brez te naprave ([jeTa]) in brez podvojenih sorodnikov. */
    fun zZaslonom(zive: List<Naprava>, jeTa: (Naprava) -> Boolean): List<Naprava> =
        brezPodvojenih(zive.filter { zmoreZaslon(it.zmoznosti) && !jeTa(it) })

    /**
     * Seznam za prikaz. [stanja]: kar so preverbe ta cas odgovorile (po id). [znane]: naprave, ki so bile na seznamu
     * (zadnje stanje); nedavno preverjena naprava ob vrnitvi v Link do nove preverbe obdrzi svoje stanje.
     */
    fun seznam(zive: List<Naprava>, jeTa: (Naprava) -> Boolean, znane: List<Znana>, stanja: Map<String, Dostop>): List<Vnos> {
        val izhod = ArrayList<Vnos>()
        val zivi = zZaslonom(zive, jeTa)
        for (n in zivi) {
            val prej = znane.firstOrNull { it.id == n.id }
            val dostop = stanja[n.id] ?: prej?.dostop?.takeIf { it == Dostop.CAKA || it == Dostop.ZAVRNJENO } ?: Dostop.PREVERJAM
            if (dostop == Dostop.SKRITO) continue
            izhod.add(Vnos(n.id, n.ime.ifBlank { n.id }, n.platforma, jeRacunalnik(n.zmoznosti), true, dostop))
        }
        // Brez povezave: zapomnjene, ki jih zdaj v Linku ni (ne po id ne po fizicni napravi). Ta naprava nikoli.
        val idji = zive.map { it.id }.toSet()
        val fizicne = zive.map { it.fizicna }.filter { it.isNotBlank() }.toSet()
        for (z in znane) {
            if (z.id in idji || (z.fizicna.isNotBlank() && z.fizicna in fizicne)) continue
            if (z.dostop == Dostop.SKRITO) continue
            if (jeTa(Naprava(z.id, z.ime, z.platforma, emptyList(), z.fizicna))) continue
            izhod.add(Vnos(z.id, z.ime.ifBlank { z.id }, z.platforma, z.racunalnik, false, z.dostop))
        }
        return izhod.sortedWith(compareBy<Vnos>({ red(it) }, { it.ime.lowercase() }))
    }

    /** Najprej, kar je mogoce gledati, nato prosnje, nato brez povezave. */
    private fun red(v: Vnos): Int = when {
        !v.povezana -> 4
        v.dostop == Dostop.DOSTOPNO -> 0
        v.dostop == Dostop.PREVERJAM || v.dostop == Dostop.NI_ODGOVORA -> 1
        v.dostop == Dostop.CAKA || v.dostop == Dostop.PROSNJA -> 2
        else -> 3
    }

    /** Prazno stanje za prikaz ali null, ce je na seznamu vsaj ena naprava. */
    fun prazno(vnosi: List<Vnos>, zive: List<Naprava>, jeTa: (Naprava) -> Boolean, povezan: Boolean): Prazno? {
        if (vnosi.isNotEmpty()) return null
        if (!povezan) return Prazno.NI_LINKA
        return if (zZaslonom(zive, jeTa).isEmpty()) Prazno.NI_NAPRAV else Prazno.NI_DOVOLJENJ
    }

    /**
     * Nov zapis znanih naprav: zive z znanim stanjem gredo na zacetek (najnovejse), naprava, ki je zdaj SKRITO
     * (dovoljenje odvzeto), izpade; najvec [NAJVEC_ZNANIH]. Stanja PREVERJAM ne zapisemo - napravo z zapisom obdrzi
     * prejsnje stanje.
     */
    fun zapomni(znane: List<Znana>, zive: List<Naprava>, jeTa: (Naprava) -> Boolean, stanja: Map<String, Dostop>): List<Znana> {
        val nove = ArrayList<Znana>()
        val izpadli = HashSet<String>()
        for (n in zZaslonom(zive, jeTa)) {
            val prej = znane.firstOrNull { it.id == n.id }
            val dostop = when (val s = stanja[n.id]) {
                null, Dostop.PREVERJAM, Dostop.NI_ODGOVORA -> prej?.dostop
                else -> s
            }
            if (dostop == null) continue
            if (dostop == Dostop.SKRITO) { izpadli.add(n.id); continue }
            nove.add(Znana(n.id, n.ime.ifBlank { prej?.ime ?: n.id }, n.platforma, jeRacunalnik(n.zmoznosti), n.fizicna, dostop))
        }
        val zeNotri = nove.map { it.id }.toSet()
        val fizicne = nove.map { it.fizicna }.filter { it.isNotBlank() }.toSet()
        for (z in znane) {
            if (z.id in zeNotri || z.id in izpadli || (z.fizicna.isNotBlank() && z.fizicna in fizicne)) continue
            if (jeTa(Naprava(z.id, z.ime, z.platforma, emptyList(), z.fizicna))) continue
            nove.add(z)
        }
        return nove.take(NAJVEC_ZNANIH)
    }

    // Zapis: ena vrstica na napravo, polja loci tabulator (imena ga nimajo - odstranimo ga).

    fun vNiz(znane: List<Znana>): String = znane.take(NAJVEC_ZNANIH).joinToString("\n") { z ->
        listOf(z.id, z.ime, z.platforma, if (z.racunalnik) "1" else "0", z.fizicna, z.dostop.name)
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').take(120) }
    }

    fun izNiza(s: String?): List<Znana> {
        if (s.isNullOrBlank()) return emptyList()
        return s.lineSequence().mapNotNull { vrstica ->
            val p = vrstica.split('\t')
            if (p.size < 6 || p[0].isBlank()) return@mapNotNull null
            val dostop = Dostop.values().firstOrNull { it.name == p[5] } ?: return@mapNotNull null
            Znana(p[0], p[1], p[2], p[3] == "1", p[4], dostop)
        }.take(NAJVEC_ZNANIH).toList()
    }
}
