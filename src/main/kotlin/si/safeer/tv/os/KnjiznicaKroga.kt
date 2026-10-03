package si.safeer.tv.os

/**
 * Knjiznica kroga (3. 10. 2026; Matej: "vsaka naprava je zadolzena za to, kar pocne najbolje, in sodeluje v krogu
 * solidarnosti"). Kar katera koli naprava v Safeer Linku prenese (film ali epizodo iz torrenta), se na vseh napravah
 * pokaze kot polica s plakati - predvaja ga naprava, ki ga hrani, z diska.
 *
 * Naprava, ki film prosi, pomocniku ob `magnet.stream` pove naslov in plakat (pozna ju iz dodatka); pomocnik ju
 * vrne v `magnet.list`. Surovega imena torrenta polica nikoli ne kaze. Naslov iz zasebnega dodatka ([ZasebniDodatki])
 * gre pomocniku samo z oznako "zasebno" - brez naslova in plakata - in ga na polici ni na nobeni napravi.
 *
 * Pravila so cista (brez Androida) - preizkus tests/KnjiznicaKrogaTest.kt.
 */
object KnjiznicaKroga {
    /** Kar naprava pove pomocniku (in si zapomni sama) o filmu, ki ga prosi. */
    data class Opis(val naslov: String, val plakat: String, val vrsta: String, val ref: String, val zaseben: Boolean)

    const val NAJVEC_NASLOV = 200
    const val NAJVEC_PLAKAT = 600
    const val NAJVEC_REF = 400

    /** Opis za zahtevo: zaseben naslov nosi samo oznako; plakat samo po https; vrsta "movie" / "series" ali prazno. */
    fun opis(naslov: String, plakat: String, mediaType: String, id: String, zaseben: Boolean): Opis =
        if (zaseben) Opis("", "", "", "", true)
        else Opis(naslov.trim().take(NAJVEC_NASLOV), plakat.trim().takeIf { it.startsWith("https://") }?.take(NAJVEC_PLAKAT).orEmpty(),
            when (mediaType) { "movie" -> "movie"; "series", "tv", "episode" -> "series"; else -> "" }, id.take(NAJVEC_REF), false)

    /** Opis iz tega, kar je poslala druga naprava (pomocnik ga shrani): brez naslova in brez oznake zasebnosti ni opisa. */
    fun sprejet(naslov: String, plakat: String, vrsta: String, ref: String, zaseben: Boolean): Opis? = when {
        zaseben -> Opis("", "", "", "", true)
        naslov.isBlank() -> null
        else -> Opis(naslov.trim().take(NAJVEC_NASLOV), plakat.trim().takeIf { it.startsWith("https://") }?.take(NAJVEC_PLAKAT).orEmpty(),
            vrsta.takeIf { it == "movie" || it == "series" }.orEmpty(), ref.take(NAJVEC_REF), false)
    }

    /** Zdruzi nov opis s shranjenim: kar je enkrat zasebno, ostane zasebno; prazna polja ne prepisejo znanih. */
    fun zdruzi(star: Opis?, nov: Opis): Opis = when {
        nov.zaseben || star?.zaseben == true -> Opis("", "", "", "", true)
        star == null -> nov
        else -> Opis(nov.naslov.ifBlank { star.naslov }, nov.plakat.ifBlank { star.plakat }, nov.vrsta.ifBlank { star.vrsta }, nov.ref.ifBlank { star.ref }, false)
    }

    /** Ali pomocnik vnos pove napravi [vprasa]: zasebnega samo tisti, ki ga je prosila. */
    fun pove(opis: Opis?, narocniki: Collection<String>, vprasa: String): Boolean =
        opis?.zaseben != true || (vprasa.isNotBlank() && vprasa in narocniki)

    /**
     * Naslov in plakat za polico ali null, ce vnosa ne pokazemo: zasebnega nikoli; brez naslova (starejsi pomocnik ali
     * starejsa naprava, ki opisa ni poslala) samo, ce si je ta naprava naslov zapomnila sama.
     */
    fun zaPolico(naslovPomocnika: String, plakatPomocnika: String, zasebenPomocnika: Boolean, lokalni: Opis?): Pair<String, String>? {
        if (zasebenPomocnika || lokalni?.zaseben == true) return null
        val naslov = naslovPomocnika.trim().ifBlank { lokalni?.naslov.orEmpty() }
        if (naslov.isBlank()) return null
        return naslov to plakatPomocnika.trim().takeIf { it.startsWith("https://") }.orEmpty().ifBlank { lokalni?.plakat.orEmpty() }
    }

    /** Isti film pri vec napravah je na polici enkrat: prednost ima koncan prenos, nato ta naprava, nato racunalnik. */
    fun <T> brezDvojnikov(vnosi: List<T>, hash: (T) -> String, koncan: (T) -> Boolean, tukaj: (T) -> Boolean, racunalnik: (T) -> Boolean): List<T> =
        vnosi.groupBy { hash(it) }.flatMap { (h, skupina) ->
            if (h.isBlank()) skupina
            else listOf(skupina.sortedWith(compareByDescending<T> { koncan(it) }.thenByDescending { tukaj(it) }.thenByDescending { racunalnik(it) }).first())
        }
}
