package si.safeer.tv

/**
 * Katera zahteva pripada predvajanju (manifest DASH, segment, licenca) in gre mimo filtra oglasov. Brez Androida, da jih
 * preverimo v JVM (tests/DashPravilaTest.kt).
 *
 * Zunanji pregled 2. 11. 2026: prej je zadoscal niz ».mpd«, »/license«, »widevine« ... kjerkoli v naslovu - tudi v
 * poizvedbi (adsbygoogle.js?x=.mpd) - in gostitelj manifesta, ki ga je stran sama javila po mostu. Zdaj odloca pot (brez
 * poizvedbe) ali znan streznik licenc, gostitelj pa samo, ce je manifest res prisel z njega po omrezju; oglasni
 * gostitelj (blokiran in ne zaupanja vreden) nikoli.
 */
object DashPravila {
    /** Znani ponudniki licenc (enako kot AdBlockEngine.DRM_DOMENE). */
    private val DRM_DOMENE = listOf(
        "widevine.com", "drmtoday.com", "castlabs.com", "expressplay.com",
        "axinom.com", "axprod.net", "axtest.net", "ezdrm.com", "buydrm.com", "irdeto.com", "verimatrix.com"
    )

    fun gostitelj(url: String?): String = GooglePravila.gostitelj(url)

    /** Manifest, segment ali licenca po POTI naslova (ne poizvedbi) ali po znanem strezniku licenc. */
    fun jeMedijskaZahteva(url: String): Boolean {
        val h = gostitelj(url)
        if (h.isEmpty()) return false
        val p = GooglePravila.pot(url)
        if (p.endsWith(".mpd") || p.endsWith(".m4s") || p.contains("/dash/")) return true
        if (p.contains("/license") || p.contains("/drm/") || p.contains("/widevine")) return true
        if (DRM_DOMENE.any { h == it || h.endsWith(".$it") }) return true
        return h.split('.').any { it.startsWith("license-proxy") }
    }

    /**
     * Ali zahtevo pustimo mimo filtra oglasov. [manifestGostitelj]: gostitelj, s katerega je manifest prisel po omrezju
     * ("" = ni ga). [oglasniGostitelj]: gostitelj je na seznamu blokiranih in ni zaupanja vreden - nikoli mimo.
     */
    fun prepusti(url: String, manifestGostitelj: String, oglasniGostitelj: Boolean): Boolean {
        if (oglasniGostitelj) return false
        if (jeMedijskaZahteva(url)) return true
        val h = gostitelj(url)
        return h.isNotEmpty() && manifestGostitelj.isNotEmpty() && h == manifestGostitelj.lowercase().trimEnd('.')
    }
}
