package si.safeer.tv.os

/**
 * Globina gnezdenja JSON pred razclenjevanjem odgovora tujega streznika (dodatek Stremio).
 * Pregled 5. in 9. 11. 2026: JSONObject nad odgovorom z nekaj tisoc gnezdenimi [ ali { sesuje nit s StackOverflowError
 * (ni Exception - catch ga ne ujame) in z njo aplikacijo na TV. En prehod, brez rekurzije in dodelitev.
 */
object JsonGlobinaPravila {
    const val NAJVEC_GLOBINA = 64

    fun globina(besedilo: String): Int {
        var maxGlobina = 0
        var trenutnaGlobina = 0
        var vNizu = false
        var ubezno = false

        for (i in 0 until besedilo.length) {
            val znak = besedilo[i]
            if (vNizu) {
                if (ubezno) {
                    ubezno = false
                } else if (znak == '\\') {
                    ubezno = true
                } else if (znak == '"') {
                    vNizu = false
                }
            } else {
                if (znak == '"') {
                    vNizu = true
                } else if (znak == '{' || znak == '[') {
                    trenutnaGlobina++
                    if (trenutnaGlobina > maxGlobina) {
                        maxGlobina = trenutnaGlobina
                    }
                } else if (znak == '}' || znak == ']') {
                    if (trenutnaGlobina > 0) {
                        trenutnaGlobina--
                    }
                }
            }
        }
        return maxGlobina
    }

    fun pregloboko(besedilo: String, meja: Int = NAJVEC_GLOBINA): Boolean {
        var trenutnaGlobina = 0
        var vNizu = false
        var ubezno = false

        for (i in 0 until besedilo.length) {
            val znak = besedilo[i]
            if (vNizu) {
                if (ubezno) {
                    ubezno = false
                } else if (znak == '\\') {
                    ubezno = true
                } else if (znak == '"') {
                    vNizu = false
                }
            } else {
                if (znak == '"') {
                    vNizu = true
                } else if (znak == '{' || znak == '[') {
                    trenutnaGlobina++
                    if (trenutnaGlobina > meja) {
                        return true
                    }
                } else if (znak == '}' || znak == ']') {
                    if (trenutnaGlobina > 0) {
                        trenutnaGlobina--
                    }
                }
            }
        }
        return false
    }
}
