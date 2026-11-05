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
        /** Cetrti slepi pregled 5. 11. 2026 (N-A): cilj je okvir - brez prave tipke in dotika, samo namig za kazalec. */
        object Kazalec : Ok()
        /** Peti slepi pregled 5. 11. 2026 (N1): brez odgovora, izjema ali neznan odgovor - nic (prej pravi Enter). */
        object Nic : Ok()
    }

    /** Odgovor skripta KLIK_FOKUSA: "enter", "space", "kazalec" ali "dotik:x,y". Karkoli drugega je [Ok.Nic], nikoli Enter. */
    fun ok(odgovor: String?): Ok {
        val o = (odgovor ?: "").trim().trim('"')
        if (o == "enter") return Ok.Enter
        if (o == "space") return Ok.Presledek
        if (o == "kazalec") return Ok.Kazalec
        if (o.startsWith("dotik:")) {
            val d = o.removePrefix("dotik:").split(',')
            if (d.size == 2) {
                val x = d[0].toFloatOrNull()
                val y = d[1].toFloatOrNull()
                if (x != null && y != null && x.isFinite() && y.isFinite() && x >= 0f && y >= 0f) return Ok.Dotik(x, y)
            }
        }
        return Ok.Nic
    }

    /**
     * Peti slepi pregled 5. 11. 2026 (N1): odgovor KLIK_FOKUSA velja samo za stran, za katero je bil poslan (isti zeton
     * dotika - nov ob vsaki strani) in ki je se vedno v Google nacinu. Prej je to preverjala samo veja Kazalec.
     */
    fun istaStran(zetonOk: String, zetonZdaj: String, seNaGooglu: Boolean): Boolean =
        seNaGooglu && zetonOk.length >= 32 &&
            java.security.MessageDigest.isEqual(zetonOk.toByteArray(Charsets.UTF_8), zetonZdaj.toByteArray(Charsets.UTF_8))

    private val NONCE = Regex("[0-9a-f]{16,64}")

    /**
     * KLIK_FOKUSA z imenom izvirnikov tega pogleda (ChromiumEngineView.nonceIzvirnikov, samo sestnajstiski). Brez veljavnega
     * dela (ali izvirniki niso registrirani: null) ime, ki ga ni - skript nima izvirnikov in ne vrne pravega dotika.
     */
    fun zIzvirniki(js: String, nonce: String?): String =
        js.replace("__SAFEER_NONCE_IZVIRNIKOV__", if (nonce != null && NONCE.matches(nonce)) nonce else "brez")

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
