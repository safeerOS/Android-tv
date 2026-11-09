package si.safeer.tv

/**
 * Lastnik 9. 11. 2026 (24ur.com): stran, ki ni narejena za TV, naj daljinec sam preklopi v kazalec (miska). Izbira lastnika:
 * privzeto kazalec, izjeme so strani za TV (nase strani, YouTube za TV, pregledovalnik PDF). Rocni preklop (rdeca/INFO,
 * gumb kazalca, drzan OK) si brskalnik zapomni za gostitelja - naslednjic stran odpre tako, kot jo je uporabnik zelel.
 */
object KazalecPravila {
    /** Gostitelj http(s) naslova (malo, brez pike na koncu) ali null. */
    fun gostitelj(naslov: String?): String? = try {
        val u = java.net.URI((naslov ?: "").trim())
        if (!u.scheme.equals("https", true) && !u.scheme.equals("http", true)) null
        else u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /**
     * Ali naj bo kazalec ob odprtju [naslov] vklopljen. [zapomnjeno] = uporabnikova izbira za gostitelja (null = ni je).
     * [zaTv] = stran je narejena za daljinec (nasa stran, YouTube za TV, pregledovalnik PDF).
     */
    fun privzetoKazalec(jeTelevizor: Boolean, naslov: String, zaTv: Boolean, zapomnjeno: Boolean?): Boolean {
        if (!jeTelevizor) return false
        if (gostitelj(naslov) == null) return false   // nasa domaca stran (file://, about:), brez gostitelja
        zapomnjeno?.let { return it }
        return !zaTv
    }

    /** Kazalec se sam skrije po 30 s samo, ce ni trajen (spletna aplikacija), ne vlece in ni privzet za stran, ki ni za TV. */
    fun samSkrij(trajen: Boolean, vlecem: Boolean, privzet: Boolean): Boolean = !trajen && !vlecem && !privzet
}
