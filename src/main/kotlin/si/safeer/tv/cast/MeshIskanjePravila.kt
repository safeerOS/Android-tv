package si.safeer.tv.cast

/**
 * Revizija RAM/CPU 8. 11. 2026 (#2): hub z vklopljenim Link Mesh je vsakih 15 s iskal sosede po mDNS (4 s iskanja z
 * zaklepom multicast), tudi ko so bili vsi sosedje ze povezani in se ni spremenilo nic. Zdaj se presledek ob mirovanju
 * podvaja do [NAJVEC_MS]; vsaka sprememba (nov kandidat za klic, drug nabor sosedov) ga vrne na [ZACETNO_MS].
 */
object MeshIskanjePravila {
    const val ZACETNO_MS = 15_000L
    const val NAJVEC_MS = 120_000L

    /** Naslednji presledek iskanja: po spremembi zacetni, sicer dvakratnik prejsnjega do [NAJVEC_MS]. */
    fun naslednje(prejsnjeMs: Long, sprememba: Boolean): Long =
        if (sprememba || prejsnjeMs < ZACETNO_MS) ZACETNO_MS else minOf(prejsnjeMs * 2, NAJVEC_MS)

    /** Sprememba = smo koga klicali ali se je nabor sosedov spremenil (tudi odhod soseda). */
    fun sprememba(sosedjePrej: Set<String>, sosedjeZdaj: Set<String>, klicanih: Int): Boolean =
        klicanih > 0 || sosedjePrej != sosedjeZdaj
}
