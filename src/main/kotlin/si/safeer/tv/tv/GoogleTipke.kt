package si.safeer.tv

/**
 * Tipke daljinca na Googlovih straneh brez iskanja (prijava accounts.google.com ...), kjer nase skripte ni
 * (UserScriptManager: na Google nic ne vbrizgamo). Ciste odlocitve iz odgovora skripta strani, brez Androida
 * (tests/GoogleTipkeTest.kt).
 *
 * Lastnik 2. 11. 2026 (TV, prijava): GOR je vedno skocil v naslovno vrstico (klic _safeer_navigate_spatial, ki na
 * Googlu ne obstaja, vrne null), zato se fokus ni dal vrniti v polje »Vnesite geslo«; OK je bil vedno Enter, ta pa
 * potrditvenega polja »Prikazi geslo« ne preklopi, v obrazcu prijave pa ga ujame obrazec namesto povezave.
 */
object GoogleTipke {

    /** Kaj naredi OK na fokusiranem elementu. */
    sealed class Ok {
        /** Povezava ali neznano: pravi Enter (odpre povezavo kot uporabnik, tudi prek google.com/goto). */
        object Enter : Ok()
        /** Potrditveno polje, ki ga dotik ne doseze: Presledek ga preklopi (Enter ga ne). */
        object Presledek : Ok()
        /** Pravi dotik na sredini elementa (CSS px): polje odpre tipkovnico, gumb in potrditveno polje kliknejo enkrat. */
        data class Dotik(val x: Float, val y: Float) : Ok()
    }

    /** Odgovor skripta KLIK_FOKUSA: "enter", "space" ali "dotik:x,y". Karkoli drugega je Enter (kot prej). */
    fun ok(odgovor: String?): Ok {
        val o = (odgovor ?: "").trim().trim('"')
        if (o == "space") return Ok.Presledek
        if (o.startsWith("dotik:")) {
            val d = o.removePrefix("dotik:").split(',')
            if (d.size == 2) {
                val x = d[0].toFloatOrNull()
                val y = d[1].toFloatOrNull()
                if (x != null && y != null && x.isFinite() && y.isFinite() && x >= 0f && y >= 0f) return Ok.Dotik(x, y)
            }
        }
        return Ok.Enter
    }

    /** Skript: ali fokus na strani ni na nobenem elementu (vrh strani) - takrat gre GOR v naslovno vrstico. */
    const val JE_NA_VRHU = "(function(){var e=document.activeElement;return (!e||e===document.body||e===document.documentElement)?'vrh':'stran';})()"

    /**
     * GOR: v naslovno vrstico samo, ce na strani ni fokusiranega elementa (ali skript ni odgovoril); sicer
     * Shift+Tab na prejsnji element strani (polje za geslo, e-posta ...).
     */
    fun gorVNaslov(odgovor: String?): Boolean = (odgovor ?: "").trim().trim('"') != "stran"

    /**
     * Nazaj na Googlovem iskanju: odgovor GOOGLE_SMER('TUJE'). Ce je odprto okno v tujem okvirju (plosca racuna,
     * TV 2. 11. 2026), ga zapre Escape; sicer navaden Nazaj po zgodovini.
     */
    fun jeTuje(odgovor: String?): Boolean = (odgovor ?: "").trim().trim('"') == "tuje"
}
