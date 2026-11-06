package si.safeer.tv

/**
 * Googlovi gostitelji za Scit grozenj (5. 11. 2026, zasnova s telefona 3596b68). Prej TV ni imel nobene izjeme: ena
 * napacna ali podtaknjena vrstica (google.com, sites.google.com) v kateremkoli viru je blokirala iskanje, prijavo ali
 * vso platformo. Tri skupine: koren (nikoli), skupni gostitelji z uporabnisko vsebino (samo tocen naslov z vsaj dvema
 * deloma poti) in projekti (*.web.app, *.firebaseapp.com, *.appspot.com: cel projekt da, koren platforme nikoli).
 * Isto pravilo v DNS Scitu (ScitOdlocitev) in v Safeer-linux (core/scit_gostitelji.py); skupni primeri
 * tests/skupni-primeri/scit-pravila.json.
 */
internal object ScitGostitelji {
    enum class Vrsta { NAVADEN, KOREN, VSEBINA }

    // Iskanje in prijava, brez uporabniske vsebine; oauth2.googleapis.com in accounts.youtube.com rabi prijava.
    private val KOREN = setOf(
        "gstatic.com", "apis.google.com", "fonts.googleapis.com", "ajax.googleapis.com", "oauth2.googleapis.com",
        "accounts.youtube.com",
    )
    // Pregled 6. 11. 2026 (S1): consent.google.<pripona> je stran soglasja v EU - nanjo google.com preusmeri vsak nov brskalnik,
    // zato bi en podtaknjen vnos ustavil iskanje in prijavo.
    private val PREDPONE_KORENA = listOf("", "www.", "accounts.", "myaccount.", "consent.")

    // Skupni gostitelji, kjer objavlja kdorkoli: blokira se samo pot. www.googleapis.com streze tudi Google Cloud Storage
    // (/download/storage/v1/b/<vedro>/...), zato ni koren.
    private val VSEBINA = setOf(
        "sites.google.com", "docs.google.com", "script.google.com", "drive.google.com", "storage.googleapis.com",
        "firebasestorage.googleapis.com", "www.googleapis.com", "googleusercontent.com",
    )

    // Vsak podgostitelj je en projekt (zlo.web.app): ta sme biti blokiran v celoti, koren nikoli.
    private val NAJEMNIK = listOf("web.app", "firebaseapp.com", "appspot.com")

    // Domene, katerih vnos v viru nikoli ne blokira vseh gostiteljev pod njimi (poleg google.<pripona>).
    private val CELOTNA = setOf("googleapis.com", "gstatic.com", "googleusercontent.com", "web.app", "firebaseapp.com", "appspot.com")

    private fun cist(host: String) = host.trim().lowercase().trimEnd('.')

    // Izrecen seznam Googlovih pripon (GooglePravila), ne vzorec co.xx: google.co.xx lahko registrira kdorkoli in bi bil sicer koren.
    private fun jeGoogleDomena(h: String) = h.startsWith("google.") && GooglePravila.jeGooglePripona(h.removePrefix("google."))

    fun vrsta(host: String): Vrsta {
        val h = cist(host)
        if (h in KOREN || h.endsWith(".gstatic.com")) return Vrsta.KOREN
        if (PREDPONE_KORENA.any { h.startsWith(it) && jeGoogleDomena(h.removePrefix(it)) }) return Vrsta.KOREN
        // *.googleusercontent.com (lh3, doc-...): skupna infrastruktura, zato tudi tu samo pot.
        if (h in VSEBINA || h.endsWith(".googleusercontent.com")) return Vrsta.VSEBINA
        if (NAJEMNIK.any { h == it || h.endsWith(".$it") }) return Vrsta.VSEBINA
        return Vrsta.NAVADEN
    }

    /** Gostitelj enega projekta (zlo.web.app), ne koren platforme. */
    fun jeNajemnik(host: String): Boolean {
        val h = cist(host)
        return NAJEMNIK.any { h.endsWith(".$it") }
    }

    fun jeCelotnaDomena(domena: String): Boolean {
        val d = cist(domena)
        return d in CELOTNA || jeGoogleDomena(d)
    }

    /**
     * Ali sme pravilo za gostitelja/domeno [domena] blokirati: navaden gostitelj ali projekt da; koren, skupni gostitelj
     * in celotna platforma nikoli (DNS in drevo poznata samo gostitelja, ne poti).
     */
    fun smeGostitelj(domena: String): Boolean {
        val d = cist(domena)
        if (d.isEmpty() || jeCelotnaDomena(d)) return false
        return vrsta(d) == Vrsta.NAVADEN || jeNajemnik(d)
    }

    /**
     * DNS Scit (scit/ScitOdlocitev.kt, kot core/os_scit.py): vnos seznama OGLASOV sme veljati za skupni gostitelj
     * (afs.googleusercontent.com - Googlov oglasni streznik), ne pa za koren ali celotno domeno (google.com, web.app).
     */
    fun smeOglas(domena: String): Boolean {
        val d = cist(domena)
        return d.isNotEmpty() && !jeCelotnaDomena(d) && vrsta(d) != Vrsta.KOREN
    }

    /**
     * Pravilo za naslov velja na skupnem gostitelju samo z vsaj dvema delama poti (/view/ime, /macros/s/ID/exec).
     * Ena raven (/forms, /view, /drive) ali koren je vstopna stran izdelka in z njo bi blokirali vse uporabnike.
     * Pregled 6. 11. 2026 (S2): ena raven z neprazno poizvedbo tudi (drive.google.com/uc?export=download&id=X,
     * docs.google.com/uc?id=X) - pravilo naslova se ujema tocno s poizvedbo, zato vstopne strani /uc ne ustavi.
     */
    fun smePot(url: String, host: String): Boolean {
        if (jeNajemnik(host)) return true
        val zacetek = url.indexOf("://").takeIf { it >= 0 } ?: return false
        val ostanek = url.substring(zacetek + 3)
        val konecGostitelja = ostanek.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        if (konecGostitelja < 0 || ostanek[konecGostitelja] != '/') return false
        val brezSidra = ostanek.substring(konecGostitelja).substringBefore('#')
        val pot = brezSidra.substringBefore('?')
        val delov = pot.split('/').count { it.isNotEmpty() }
        return delov >= 2 || (delov == 1 && brezSidra.substringAfter('?', "").isNotEmpty())
    }
}
