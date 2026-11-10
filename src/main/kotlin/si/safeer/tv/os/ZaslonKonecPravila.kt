package si.safeer.tv.os

/**
 * Pravila za konec seje zaslona racunalnika in napake ob `screen.start`.
 * Brez Androida in org.json, da se da preizkusiti na JVM.
 */
object ZaslonKonecPravila {
    enum class Odlocitev {
        PONOVI,
        KONCAJ_ZAJEM_PADA,
        KONCAJ_DOSTOP,
        KONCAJ_DRUGO
    }

    private const val PREDSTAVKA_ZAJEM = "zajem:"
    private const val PREDSTAVKA_IZHOD = "izhod_"

    /**
     * Odlocitev ob napaki `screen.start` ([koda]) ali koncu po kanalu seje ([konec], [razlog]):
     * - `zajem_pada` ali `konec == "zajem"` (tudi zapakirano `zajem:...`) -> [Odlocitev.KONCAJ_ZAJEM_PADA] (nikoli ponovitev),
     * - `ni_dovoljeno` ali `konec == "dostop"` -> [Odlocitev.KONCAJ_DOSTOP],
     * - druge kode napak (`ni_programa`, ...) ali drugi konci seje (`ni_okna`, `program_zaprt`, `na_namizju`, `prevzeto:...`) -> [Odlocitev.KONCAJ_DRUGO],
     * - brez kode in brez konca seje (izpad povezave: KONCANO, NAPAKA, NEDOSEGLJIV, izid == null med vracanjem) -> [Odlocitev.PONOVI].
     */
    fun odlocitev(koda: String?, konec: String?, razlog: String? = null): Odlocitev {
        val c = koda?.trim().orEmpty()
        val k = konec?.trim().orEmpty()
        if (c == "zajem_pada" || k == "zajem" || k.startsWith(PREDSTAVKA_ZAJEM)) return Odlocitev.KONCAJ_ZAJEM_PADA
        if (c == "ni_dovoljeno" || k == "dostop") return Odlocitev.KONCAJ_DOSTOP
        if (c.isNotEmpty() || k.isNotEmpty()) return Odlocitev.KONCAJ_DRUGO
        return Odlocitev.PONOVI
    }

    /**
     * Preveri nezaupanja vreden razlog z druge naprave: za prikaz je dovoljena samo oblika
     * `izhod_<1-3 ASCII stevke>` (npr. `izhod_234`). Za vse drugo vrne null.
     */
    fun varenRazlog(razlog: String?): String? {
        if (razlog == null) return null
        val minDolzina = PREDSTAVKA_IZHOD.length + 1
        val maxDolzina = PREDSTAVKA_IZHOD.length + 3
        if (razlog.length !in minDolzina..maxDolzina || !razlog.startsWith(PREDSTAVKA_IZHOD)) return null
        for (i in PREDSTAVKA_IZHOD.length until razlog.length) {
            if (razlog[i] !in '0'..'9') return null
        }
        return razlog
    }

    /**
     * Sestavi sporocilo ob odpovedi zajema: osnovnemu besedilu doda kodo razloga v oklepaju,
     * ce je veljavne oblike `izhod_<1-3 ASCII stevke>`.
     */
    fun sporocilo(osnovno: String, razlog: String?): String {
        val varen = varenRazlog(razlog) ?: return osnovno
        return "$osnovno ($varen)"
    }

    /** Izvlece neobdelan razlog iz besedila stanja `zajem:<razlog>`. */
    fun razlogIzStanja(besedilo: String?): String? =
        if (besedilo != null && besedilo.startsWith(PREDSTAVKA_ZAJEM)) besedilo.removePrefix(PREDSTAVKA_ZAJEM) else null
}
