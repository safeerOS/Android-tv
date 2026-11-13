package si.safeer.tv.cast

/**
 * Odtis, ki ga naprava podpise ob prijavi s kljucem (neodvisni pregled 12. 11. 2026, S2; apk-26 odlozeno, apk-28 popravljeno).
 * Podpis veze izziv na potrdilo TLS strani, s katero res govorimo ("safeer-link-auth\n<odtis>\n<nonce>\n<id>"). Odtis iz polja `fp` v
 * odgovoru sosedna je navedba druge strani: clan kroga, ki bi nas zavedel, bi nam podtaknil odtis DRUGEGA huba, nas podpis predal
 * temu hubu in se v njem izdajal za nas. Zato se podpise samo odtis, ki ga je naprava videla v rokovanju TLS ([opazen]);
 * polje [izJson] se ne uporabi nikoli. Brez opazenega odtisa prijave s podpisom ni (null).
 */
object OdtisPrijave {
    fun zaPodpis(opazen: String?, @Suppress("UNUSED_PARAMETER") izJson: String?): String? =
        opazen?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
