package si.safeer.tv.os

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Po kateri poti beremo z druge naprave: neposredno (isto omrezje) ali prek Global Linka (rele do
 * njenega Huba). Pravila so brez Androida, da jih preverimo na navadnem JVM.
 *
 * Doma je neposredna pot prva in rele samo zasilni izhod. Zdoma uporabnik ne sme cakati na neposredni
 * poskus v omrezje, v katerem ga ni (prej sest sekund pri vsaki datoteki in pri vsakem previjanju po
 * minuti premora): kje smo, pove kratka sonda, ugotovitev pa velja, dokler jo raba potrjuje.
 */
object PotDoNaprave {
    enum class Pot { NEPOSREDNO, RELE }

    /** Kako dolgo ugotovitvi o poti verjamemo, preden jo preverimo znova. Vsak uspeh jo podaljsa. */
    const val VELJA_MS = 60_000L

    private class Znano(val pot: Pot, val doMs: Long)

    private val znano = ConcurrentHashMap<String, Znano>()

    /** Naprave, za katere preverjanje neposredne poti v ozadju ze tece. */
    private val preverjam = ConcurrentHashMap<String, Boolean>()

    /** Ugotovitev velja samo za isto napravo na istem naslovu (nikoli za drugo na istem IP). */
    fun kljuc(naprava: String, url: String): String {
        val u = try { URI(url) } catch (_: Throwable) { null }
        return "$naprava|${u?.host.orEmpty()}:${u?.port ?: -1}"
    }

    /** Zadnja pot, po kateri je naprava odgovorila (tudi ce je ugotovitev ze zastarela), ali null. */
    fun zadnja(kljuc: String): Pot? = znano[kljuc]?.pot

    fun zapomni(kljuc: String, pot: Pot, zdajMs: Long) { znano[kljuc] = Znano(pot, zdajMs + VELJA_MS) }

    fun pocisti() { znano.clear(); preverjam.clear() }

    /**
     * Vrstni red poskusov za zahtevo do naprave:
     *  - brez releja ([releMogoc] = false) samo neposredno;
     *  - [zdoma] (povezava v Link sama tece prek releja): rele prvi;
     *  - sveza ugotovitev: znana pot prva;
     *  - nic znanega ali zastarelo »neposredno«: odloci [sonda] (kratek poskus povezave) - doma traja
     *    trenutek, zdoma pa prihrani dolg neposredni poskus;
     *  - zastarelo »rele«: zahteva gre takoj prek releja, [sonda] pa v ozadju ([vOzadju]) preveri, ali smo
     *    spet doma. Uporabnik zdoma tako caka samo prvic.
     */
    fun vrstniRed(kljuc: String, zdoma: Boolean, releMogoc: Boolean, ura: () -> Long,
                  sonda: () -> Boolean, vOzadju: (() -> Unit) -> Unit): List<Pot> {
        if (!releMogoc) return listOf(Pot.NEPOSREDNO)
        if (zdoma) return listOf(Pot.RELE, Pot.NEPOSREDNO)
        val z = znano[kljuc]
        val prva = when {
            z != null && z.doMs > ura() -> z.pot
            z != null && z.pot == Pot.RELE -> {
                if (preverjam.putIfAbsent(kljuc, true) == null) {
                    try {
                        vOzadju {
                            try { zapomni(kljuc, if (sonda()) Pot.NEPOSREDNO else Pot.RELE, ura()) }
                            finally { preverjam.remove(kljuc) }
                        }
                    } catch (_: Throwable) { preverjam.remove(kljuc) }
                }
                Pot.RELE
            }
            else -> (if (sonda()) Pot.NEPOSREDNO else Pot.RELE).also { zapomni(kljuc, it, ura()) }
        }
        return if (prva == Pot.RELE) listOf(Pot.RELE, Pot.NEPOSREDNO) else listOf(Pot.NEPOSREDNO, Pot.RELE)
    }

    /**
     * Naslov iz dokumentacijskega obsega (192.0.2.0/24, RFC 5737): naprava brez naslova v domacem omrezju (mobilni
     * podatki) ga da v opis svojega streznika datotek. Na njem ni nikogar - do naprave se pride samo prek njenega
     * Huba, zato sonde in neposrednega poskusa sploh ne delamo (uporabnik ne caka na nekaj, kar ne more uspeti).
     */
    fun brezOmrezja(url: String): Boolean {
        val gostitelj = try { URI(url).host } catch (_: Throwable) { null } ?: return false
        val zadnji = gostitelj.removePrefix("192.0.2.")
        return zadnji != gostitelj && zadnji.length in 1..3 && zadnji.all { it in '0'..'9' }
    }

    /** Poti streznika datotek naprave (racunalnik: /m/ tok torrenta; Android: /magnet/). Hub jih streze pod /cast. */
    val POTI_STREZNIKA = listOf("/d/", "/thumb/", "/m/", "/live/", "/magnet/")

    /**
     * Isti naslov prek releja: `https://naprava:vrata/d/<id>?q` postane
     * `https://127.0.0.1:<vrataReleja>/cast/d/<id>?q`. Hub naprave streze pod `/cast` vse poti njenega streznika
     * datotek ([POTI_STREZNIKA]): datoteko, slicico, sprotni tok in tok torrenta - zdoma mora delovati isto kot
     * doma. Null za druge poti in kadar releja ni.
     */
    fun relejniNaslov(url: String, vrataReleja: Int): String? {
        if (vrataReleja <= 0) return null
        val u = try { URI(url) } catch (_: Throwable) { return null }
        val pot = u.rawPath ?: return null
        if (POTI_STREZNIKA.none { pot.startsWith(it) }) return null
        return "https://127.0.0.1:$vrataReleja/cast$pot" + (u.rawQuery?.let { "?$it" } ?: "")
    }
}
