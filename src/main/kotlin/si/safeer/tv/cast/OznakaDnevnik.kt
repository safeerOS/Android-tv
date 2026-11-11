package si.safeer.tv.cast

/**
 * Oznaka naprave za dnevnik (pregled 10. 11. 2026, 4.1; apk-24 S1): zacetek in zadnji znaki jedra, brez sredine -
 * dnevniki se kopirajo v porocila. Polna oznaka je `n-<16 znakov kljuca>-<program>`; iz nje ostane `n-…cdef-control`.
 * Samo vidni ASCII, omejena dolzina - oznako pise sredisce. Skupna datoteka Link Core (tools/link-core-sync.sh), zato brez
 * odvisnosti od link/; klice jo tudi link/LinkVarnostPravila.oznakaZaDnevnik.
 */
object OznakaDnevnik {
    fun oznaka(id: String): String {
        val cisto = buildString { for (c in id.take(128)) if (c in '!'..'~') append(c) else append('?') }
        if (cisto.isEmpty()) return "(brez oznake)"
        if (cisto.length <= 6) return cisto.take(2) + "…"
        if (cisto.startsWith("n-") && cisto.length >= 18) return "n-…" + cisto.substring(14, 18) + cisto.substring(18).take(16)
        return cisto.take(2) + "…" + cisto.takeLast(2)
    }
}
