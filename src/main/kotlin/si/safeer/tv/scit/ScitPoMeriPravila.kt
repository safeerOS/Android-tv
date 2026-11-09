package si.safeer.tv.scit

/** Ciste funkcije (preizkus v JVM). */
object ScitPoMeriPravila {
    const val NAJVEC = 20
    const val NAJVEC_BAJTOV = 24 * 1024 * 1024

    /** Razlog, zakaj naslov ni sprejemljiv, ali null. Samo HTTPS, brez uporabnika/gesla v naslovu, z imenom gostitelja. */
    fun napaka(url: String): String? {
        val u = url.trim()
        if (u.length !in 12..2000) return "dolzina"
        if (!u.startsWith("https://")) return "https"
        if (u.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E }) return "znaki"
        val gostitelj = u.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in gostitelj || gostitelj.isEmpty() || '.' !in gostitelj) return "gostitelj"
        return null
    }

    /** Stalna oznaka filtra (ime datoteke agenta): »pm-« + 12 znakov zgoscenke naslova. */
    fun id(url: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
        return "pm-" + md.take(6).joinToString("") { "%02x".format(it) }
    }

    /** Kratko ime za zaslon: gostitelj in ime datoteke (npr. »raw.githubusercontent.com … hosts«). */
    fun ime(url: String): String {
        val brez = url.trim().removePrefix("https://")
        val gostitelj = brez.substringBefore('/')
        val datoteka = brez.substringBefore('?').substringAfterLast('/', "")
        return if (datoteka.isEmpty() || datoteka == gostitelj) gostitelj else "$gostitelj … $datoteka"
    }
}
