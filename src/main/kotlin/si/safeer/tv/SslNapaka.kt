package si.safeer.tv

/**
 * Stran napake za neveljavno potrdilo (TLS) v zavihku. Cista logika brez Androida, preizkus v tests/SslNapakaTest.kt.
 *
 * TV 4. 11. 2026 (https://cgi-lib.berkeley.edu/ex/fup.html, »unable to get local issuer certificate«): onReceivedSslError je
 * nalaganje tiho preklical - naslovna vrstica je pokazala nov naslov, na zaslonu pa je ostala prejsnja stran, brez obvestila
 * (Toast samo enkrat na potrdilo in na TV lahko spregledan). Zdaj glavni okvir dobi lastno stran, ki pove, zakaj se stran
 * ne odpre, z gumbom Nazaj. Moznosti »vseeno nadaljuj« za javne strani ni (varnost); izjema Huba s pripetim odtisom in
 * vprasanje za naprave v domacem omrezju ostaneta v ChromiumEngineView.
 */
object SslNapaka {
    /** Nevtralen izvor strani napake (ne deli piskotkov in shrambe z blokirano stranjo); ?u= je blokirani naslov. */
    const val STRAN_URL = "safeer://ssl-napaka"
    /** Gumb Nazaj na strani napake. */
    const val NAZAJ_URL = "safeer://ssl-nazaj"

    /** Razlog iz SslError.getPrimaryError() (konstante android.net.http.SslError). */
    enum class Razlog { SE_NI_VELJAVNO, POTEKLO, NAPACNO_IME, NEZAUPAN_IZDAJATELJ, NEVELJAVEN_DATUM, NEVELJAVNO }

    fun razlog(primarnaNapaka: Int): Razlog = when (primarnaNapaka) {
        0 -> Razlog.SE_NI_VELJAVNO      // SSL_NOTYETVALID
        1 -> Razlog.POTEKLO             // SSL_EXPIRED
        2 -> Razlog.NAPACNO_IME         // SSL_IDMISMATCH
        3 -> Razlog.NEZAUPAN_IZDAJATELJ // SSL_UNTRUSTED
        4 -> Razlog.NEVELJAVEN_DATUM    // SSL_DATE_INVALID
        else -> Razlog.NEVELJAVNO       // SSL_INVALID in neznano
    }

    /**
     * Ali je napaka v glavnem dokumentu: SslError.url je naslov glavne zahteve (zadnja glavna zahteva ali trenutni naslov
     * pogleda). Napaka slike ali skripte strani ne sme zamenjati cele strani. Primerjava brez sidra (#), shema in gostitelj
     * brez razlike v velikih crkah, prazna pot = »/«, privzeta vrata 443 brez navedbe. Samo https.
     */
    fun jeGlavniOkvir(napakaUrl: String?, glavnaZahteva: String?, trenutniUrl: String?): Boolean {
        val n = normaliziraj(napakaUrl) ?: return false
        return n == normaliziraj(glavnaZahteva) || n == normaliziraj(trenutniUrl)
    }

    private fun normaliziraj(url: String?): String? = try {
        val u = java.net.URI(url?.trim().orEmpty())
        val shema = u.scheme?.lowercase()
        val gost = u.host?.lowercase()
        if (shema != "https" || gost.isNullOrEmpty()) null
        else {
            val vrata = if (u.port == -1 || u.port == 443) "" else ":${u.port}"
            val pot = u.rawPath.orEmpty().ifEmpty { "/" }
            "$shema://$gost$vrata$pot" + (u.rawQuery?.let { "?$it" } ?: "")
        }
    } catch (_: Exception) { null }

    /** Naslov strani napake z blokiranim naslovom (samo https; drugace null - strani ne pokazemo). */
    fun naslovStrani(blokirani: String): String? {
        if (normaliziraj(blokirani) == null) return null
        return STRAN_URL + "?u=" + java.net.URLEncoder.encode(blokirani, "UTF-8")
    }

    fun jeStran(url: String?): Boolean =
        url != null && (url.equals(STRAN_URL, ignoreCase = true) || url.startsWith("$STRAN_URL?", ignoreCase = true))

    /** Blokirani naslov iz naslova strani napake (za naslovno vrstico in zavihek); sicer null. */
    fun blokiraniNaslov(url: String?): String? {
        if (url == null || !jeStran(url)) return null
        val q = url.substringAfter('?', "")
        val u = q.split('&').firstOrNull { it.startsWith("u=") }?.substring(2) ?: return null
        val d = try { java.net.URLDecoder.decode(u, "UTF-8") } catch (_: Exception) { return null }
        return d.takeIf { normaliziraj(it) != null }
    }

    /** Naslov za naslovno vrstico in zavihek: namesto strani napake blokirani naslov (ob obnovi zavihka se znova preveri). */
    fun javniNaslov(url: String): String = blokiraniNaslov(url) ?: url

    /** Barve strani iz palete Safeer OS (R.color.os_*), kot #rrggbb. */
    class Barve(val ozadje: String, val kartica: String, val besedilo: String, val umirjeno: String,
                val opozorilo: String, val crta: String, val poudarek: String, val poudarekTemna: String)

    private val BARVA = Regex("^#[0-9a-fA-F]{6}$")

    fun html(naslov: String, sporocilo: String, razlog: String, gumb: String, gostitelj: String, url: String,
             jezik: String, b: Barve): String {
        fun barva(c: String) = if (BARVA.matches(c)) c else "#888888"
        return """<!DOCTYPE html>
<html lang="${kodiraj(jezik)}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>${kodiraj(naslov)}</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { background: ${barva(b.ozadje)}; color: ${barva(b.besedilo)}; font-family: Roboto, Arial, sans-serif; min-height: 100vh;
  display: flex; justify-content: center; align-items: center; padding: 24px; }
.kartica { background: ${barva(b.kartica)}; border: 1px solid ${barva(b.opozorilo)}; border-radius: 14px; padding: 32px 28px;
  max-width: 680px; width: 100%; }
h1 { font-size: 28px; color: ${barva(b.opozorilo)}; margin-bottom: 16px; line-height: 1.3; }
.gost { display: inline-block; border: 1px solid ${barva(b.crta)}; padding: 6px 14px; border-radius: 10px; font-family: monospace;
  font-size: 18px; margin-bottom: 18px; word-break: break-all; }
p { font-size: 19px; line-height: 1.6; color: ${barva(b.umirjeno)}; margin-bottom: 18px; }
.razlog { color: ${barva(b.besedilo)}; }
button { display: block; width: 100%; padding: 16px; border-radius: 12px; font-size: 21px; font-weight: 600; cursor: pointer;
  border: 3px solid transparent; background: ${barva(b.poudarekTemna)}; color: ${barva(b.poudarek)}; margin-top: 8px; }
button:focus { outline: none; border-color: ${barva(b.poudarek)}; }
.url { font-size: 14px; color: ${barva(b.umirjeno)}; margin-top: 16px; word-break: break-all; }
</style>
</head>
<body>
<div class="kartica" role="alert">
<h1>${kodiraj(naslov)}</h1>
<div class="gost">${kodiraj(gostitelj)}</div>
<p>${kodiraj(sporocilo)}</p>
<p class="razlog">${kodiraj(razlog)}</p>
<button id="nazaj" autofocus onclick="location.href='$NAZAJ_URL'">${kodiraj(gumb)}</button>
<div class="url">${kodiraj(url)}</div>
</div>
<script>document.getElementById('nazaj').focus();</script>
</body>
</html>"""
    }

    fun kodiraj(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
