package si.safeer.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Ciste odlocitve za DashPrevzem (popravki TV-2, 15. 11. 2026). Brez Androida, da jih preizkus na JVM res izvede
 * (tests/PopravkiTv2Test.kt).
 *
 * Pravilo za piskotke (P1/SL-S2, namig licence): vsak cilj - manifest, licenca, skok preusmeritve - dobi samo
 * piskotke, ki jih CookieManager vrne za ta cilj (enako kot bi jih brskalnik poslal njemu), nikoli piskotkov izvora
 * strani, ki je naslov podala. Prej je vsak naslov z ".mpd" ali "widevine"+"license" dobil se piskotke strani (tudi
 * HttpOnly), stran pa naslov izbere sama (slika, oglas, komentar).
 */
object DashVarnostPravila {
    /** Najvec znakov JSON glav z mostu in najvec glav (P11: prej brez omejitve). */
    const val MAKS_JSON_GLAV = 16 * 1024
    const val MAKS_GLAV = 32
    const val MAKS_PREUSMERITEV = 5
    private const val MAKS_NASLOV = 2048
    private const val MAKS_ZADETKOV = 64
    private const val MAKS_PREDPONA = 64

    data class Naslov(val shema: String, val gostitelj: String, val vrata: Int, val pot: String, val poizvedba: String)

    /**
     * Lasten razclenjevalnik http(s) (Uri ni na JVM, java.net.URI zavrne marsikaj, kar WebView poda). Gostitelj je za
     * zadnjim '@' v avtoriteti (kot WHATWG); '\' v avtoriteti, neveljavna vrata ali znaki = null (fail-closed:
     * brez piskotkov, brez prenosa).
     */
    fun razcleni(url: String?): Naslov? {
        val n = (url ?: "").trim()
        if (n.length > 8 * 1024) return null
        val i = n.indexOf("://")
        if (i <= 0) return null
        val shema = n.substring(0, i).lowercase(Locale.ROOT)
        if (shema != "http" && shema != "https") return null
        val ostanek = n.substring(i + 3)
        var konec = ostanek.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        if (konec < 0) konec = ostanek.length
        if (konec < ostanek.length && ostanek[konec] == '\\') return null
        val avt = ostanek.substring(0, konec).substringAfterLast('@')
        val gostitelj: String
        val vrataNiz: String
        if (avt.startsWith("[")) {
            val z = avt.indexOf(']')
            if (z < 0) return null
            gostitelj = avt.substring(0, z + 1).lowercase(Locale.ROOT)
            val po = avt.substring(z + 1)
            if (po.isNotEmpty() && !po.startsWith(":")) return null
            vrataNiz = po.removePrefix(":")
            if (!gostitelj.substring(1, gostitelj.length - 1).all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
        } else {
            gostitelj = avt.substringBefore(':').lowercase(Locale.ROOT).trimEnd('.')
            vrataNiz = if (':' in avt) avt.substringAfter(':') else ""
            if (!gostitelj.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) return null
        }
        if (gostitelj.isEmpty() || gostitelj == "[]") return null
        val vrata = if (vrataNiz.isEmpty()) -1 else (if (vrataNiz.length <= 5 && vrataNiz.all { it in '0'..'9' }) vrataNiz.toInt() else return null)
        if (vrata > 65535) return null
        val rep = ostanek.substring(konec)
        val brezSidra = rep.substringBefore('#')
        val pot = brezSidra.substringBefore('?').ifEmpty { "/" }
        val poizvedba = if ('?' in brezSidra) brezSidra.substringAfter('?') else ""
        return Naslov(shema, gostitelj, vrata, pot, poizvedba)
    }

    fun gostitelj(url: String?): String? = razcleni(url)?.gostitelj

    /** Izvor strani kot prej (shema://gostitelj, brez vrat in poti); "" = ni http(s). */
    fun izvor(url: String?): String {
        val n = razcleni(url) ?: return ""
        return "${n.shema}://${n.gostitelj}"
    }

    fun istiGostitelj(a: String?, b: String?): Boolean {
        val ga = gostitelj(a) ?: return false
        return ga == gostitelj(b)
    }

    fun jeHttps(url: String?): Boolean = razcleni(url)?.shema == "https"

    /** Glava s poverilnico (DDD2): brskalniki jih ob navzkrizni preusmeritvi odstranijo, mi tudi. */
    fun jeObcutljivaGlava(ime: String): Boolean {
        val n = ime.trim().lowercase(Locale.ROOT)
        return n == "authorization" || n == "proxy-authorization" || n == "x-api-key" ||
            n.contains("token") || n.contains("auth") || n.contains("dt-") || n.contains("custom-data")
    }

    /** DDD2: glave za skok preusmeritve; na drugem gostitelju od prvega brez glav s poverilnicami (Authorization, zetoni, DT). */
    fun glaveZaSkok(glave: Map<String, String>, prviCilj: String, cilj: String): Map<String, String> =
        if (istiGostitelj(prviCilj, cilj)) glave else glave.filterKeys { !jeObcutljivaGlava(it) }

    /**
     * DDD1: glave strani (Authorization, DT) smejo na licencni naslov samo, ce je ta na gostitelju, ki ga je stran sama napovedala
     * ([pageLicUrl]), ali - brez napovedi - na gostitelju izvora strani. Nikoli na tuj licencni naslov druge strani.
     */
    fun licencneGlaveSmejo(pageLicUrl: String, licUrl: String, izvorStrani: String): Boolean =
        if (pageLicUrl.isNotEmpty()) istiGostitelj(pageLicUrl, licUrl) else istiGostitelj(izvorStrani, licUrl)

    /**
     * Ali smemo cilj sami prenesti ali ga predati predvajalniku z glavami: https vedno; http samo na strani http istega
     * gostitelja (tam ni TLS, ki bi ga izdali). Prej tudi http s strani https - piskotki in licenca v odprtem besedilu.
     */
    fun smeOmrezje(cilj: String?, izvorStrani: String): Boolean {
        val n = razcleni(cilj) ?: return false
        if (n.shema == "https") return true
        val s = razcleni(izvorStrani) ?: return false
        return s.shema == "http" && s.gostitelj == n.gostitelj
    }

    /** Naslov, za katerega vprasamo CookieManager: samo cilj sam. null = brez piskotkov. */
    fun piskotkiZa(cilj: String?, izvorStrani: String): String? =
        if (smeOmrezje(cilj, izvorStrani)) (cilj ?: "").trim() else null

    fun preskociGlavo(ime: String): Boolean {
        val n = ime.trim().lowercase(Locale.ROOT)
        // Cookie: piskotke vedno sestavimo znova za cilj (shranjene glave so bile lahko namenjene drugemu gostitelju).
        return n == "content-length" || n == "host" || n == "connection" || n == "accept-encoding" ||
            n == "transfer-encoding" || n == "cookie" || n == "cookie2"
    }

    fun brezPiskotkov(glave: Map<String, String>): Map<String, String> {
        if (glave.keys.none { it.equals("Cookie", true) || it.equals("Cookie2", true) }) return glave
        val out = LinkedHashMap<String, String>()
        glave.forEach { (k, v) -> if (!k.equals("Cookie", true) && !k.equals("Cookie2", true)) out[k] = v }
        return out
    }

    /**
     * Glave za en cilj: dane glave brez Cookie/Host/...; piskotki samo iz [piskotki] za cilj sam; Referer in Origin samo
     * izvor (brez poti, kot brskalnik navzkrizno) in ne s strani https na cilj http.
     */
    fun sestaviGlave(
        cilj: String,
        glave: Map<String, String>,
        izvorStrani: String,
        ua: String,
        piskotki: (String) -> String?
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        glave.forEach { (k, v) -> if (k.isNotBlank() && !preskociGlavo(k)) out[k] = v }
        val zaPiskotke = piskotkiZa(cilj, izvorStrani)
        if (zaPiskotke != null) {
            val c = piskotki(zaPiskotke)
            if (!c.isNullOrBlank()) out["Cookie"] = c
        }
        if (out.keys.none { it.equals("User-Agent", true) }) out["User-Agent"] = ua
        val strani = razcleni(izvorStrani)
        if (strani != null && (jeHttps(cilj) || strani.shema == "http")) {
            if (out.keys.none { it.equals("Referer", true) }) out["Referer"] = "$izvorStrani/"
            if (out.keys.none { it.equals("Origin", true) }) out["Origin"] = izvorStrani
        }
        return out
    }

    /** Zdruzi glavi Cookie po IMENU: zadnja vrednost zmaga (P11: prej sta sli obe vrednosti istega imena). */
    fun zdruziPiskotke(a: String, b: String): String {
        val po = LinkedHashMap<String, String>()
        (a.split(';') + b.split(';')).map { it.trim() }.filter { it.isNotEmpty() }.forEach { del ->
            po[del.substringBefore('=').trim()] = del
        }
        return po.values.joinToString("; ")
    }

    /** JSON glav z mostu: prevelik niz zavrnemo, preden ga razclenimo (P11). */
    fun jsonGlavDovoljen(raw: String): Boolean = raw.isNotBlank() && raw.length <= MAKS_JSON_GLAV

    /** P3: seja (skrita vrstica, ExoPlayer) sele, ko je razclenitev potrdila manifest MPD. */
    fun imaSejo(mpdUrl: String, potrjen: Boolean, prepuscen: Boolean): Boolean =
        mpdUrl.isNotEmpty() && potrjen && !prepuscen

    /** P5.1: rezultat razclenitve velja samo za generacijo (tok + stran), za katero je bil zahtevan. */
    fun rezultatVelja(genZahteve: Long, genZdaj: Long): Boolean = genZahteve == genZdaj

    /** Naslednji skok preusmeritve (relativen naslov glede na trenutnega); null = ne sledimo. */
    fun preusmeritev(trenutni: String, location: String?): String? {
        val l = (location ?: "").trim()
        if (l.isEmpty() || l.length > MAKS_NASLOV) return null
        val nov = try {
            java.net.URI(trenutni).resolve(l).toString()
        } catch (_: Exception) {
            return null
        }
        return if (razcleni(nov) != null) nov else null
    }

    /** Odstrani privzeta vrata samo v avtoriteti (prej replace(":443/", "/") tudi v poti in pri http). */
    fun brezPrivzetihVrat(url: String): String {
        val n = razcleni(url) ?: return url
        val zac = url.indexOf("://") + 3
        var konec = url.indexOfFirst(zac) { it == '/' || it == '?' || it == '#' }
        if (konec < 0) konec = url.length
        val avt = url.substring(zac, konec)
        val privzeta = if (n.shema == "https") ":443" else ":80"
        return if (avt.endsWith(privzeta)) url.substring(0, konec - privzeta.length) + url.substring(konec) else url
    }

    private inline fun String.indexOfFirst(od: Int, p: (Char) -> Boolean): Int {
        for (i in od until length) if (p(this[i])) return i
        return -1
    }

    /**
     * Branje telesa z ENIM skupnim rokom (P4: prej samo rok posameznega branja - streznik, ki kaplja bajte, je drzal
     * edino nit v nedogled). Ob preteku roka null (nepopolno telo ne razclenimo).
     */
    fun beriZRokom(vhod: InputStream, maks: Int, konecNs: Long, ura: () -> Long = System::nanoTime): ByteArray? {
        val baos = ByteArrayOutputStream()
        val tmp = ByteArray(16 * 1024)
        while (baos.size() < maks) {
            if (ura() - konecNs > 0) return null
            val n = vhod.read(tmp, 0, minOf(tmp.size, maks - baos.size()))
            if (n < 0) break
            baos.write(tmp, 0, n)
        }
        return baos.toByteArray()
    }

    private fun jeBel(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
    private fun jeImeZnak(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-'

    /**
     * Licencni naslov iz MPD v LINEARNEM casu (P4). Isti zadetki in isti vrstni red kot prejsnji stirje regexi
     * (oznaka laurl, atribut laurl/licenseUrl/license_url, naslov z drmtoday/widevine/license, naslov z lic.), a
     * brez vracanja: vsako iskanje nadaljuje za koncem prejsnjega, iskanje '>' in '<' si zapomni zadnji zadetek.
     * Prej je 270 KiB presledkov za "laurl" pomenilo ~10^10 korakov na edini niti DashPrevzem.
     * [samoHttps]: licenca s piskotki samo prek https.
     */
    fun najdiLaurl(xml: String, samoHttps: Boolean = false): String? {
        if (xml.isEmpty()) return null
        val n = xml.length
        val found = LinkedHashSet<String>()
        fun dodaj(raw: String) {
            if (found.size >= MAKS_ZADETKOV || raw.length > MAKS_NASLOV) return
            if (raw.startsWith("http", ignoreCase = true)) found.add(raw.trimEnd('"', '\'', '<', '>', ' '))
        }

        // 1) <predpona:laurl ...>NASLOV</
        var gt = -2
        var lt = -2
        fun naslednji(znak: Char, od: Int, zadnji: Int): Int =
            if (zadnji != -2 && (zadnji >= od || zadnji == n)) zadnji else xml.indexOf(znak, od).let { if (it < 0) n else it }
        var pos = 0
        var zasedeno = 0
        while (true) {
            val i = xml.indexOf("laurl", pos, ignoreCase = true)
            if (i < 0) break
            pos = i + 1
            var j = i - 1
            if (j >= 0 && xml[j] == ':') j--
            val meja = maxOf(-1, i - 1 - MAKS_PREDPONA)
            while (j > meja && jeImeZnak(xml[j])) j--
            if (j < 0 || xml[j] != '<' || j < zasedeno) continue
            gt = naslednji('>', i + 5, gt)
            if (gt >= n) break
            lt = naslednji('<', gt + 1, lt)
            if (lt >= n || lt == gt + 1 || !xml.startsWith("</", lt)) continue
            dodaj(xml.substring(gt + 1, lt).trim())
            zasedeno = lt + 2
            pos = maxOf(pos, zasedeno)
        }

        // 2) laurl|licenseUrl|license_url, locila [="'>\s]+, nato https?://[^"'<\s]+
        val kljuci = listOf("laurl", "licenseurl", "license_url")
        val kje = IntArray(kljuci.size) { -2 }
        pos = 0
        while (true) {
            var i = -1
            var dolz = 0
            for (k in kljuci.indices) {
                if (kje[k] != -1 && kje[k] < pos) kje[k] = xml.indexOf(kljuci[k], pos, ignoreCase = true)
                if (kje[k] >= 0 && (i < 0 || kje[k] < i)) { i = kje[k]; dolz = kljuci[k].length }
            }
            if (i < 0) break
            var j = i + dolz
            val zac = j
            while (j < n && (xml[j] == '=' || xml[j] == '"' || xml[j] == '\'' || xml[j] == '>' || jeBel(xml[j]))) j++
            val sh = when {
                j == zac -> 0
                xml.startsWith("https://", j, ignoreCase = true) -> 8
                xml.startsWith("http://", j, ignoreCase = true) -> 7
                else -> 0
            }
            if (sh == 0 || j + sh >= n || xml[j + sh].let { it == '"' || it == '\'' || it == '<' || jeBel(it) }) {
                pos = maxOf(i + 1, j)
                continue
            }
            var e = j + sh
            while (e < n && !(xml[e] == '"' || xml[e] == '\'' || xml[e] == '<' || jeBel(xml[e]))) e++
            dodaj(xml.substring(j, e).trim())
            pos = e
        }

        // 3) in 4) naslovi kot zetoni do [\s"'<>]
        fun meja(c: Char) = jeBel(c) || c == '"' || c == '\'' || c == '<' || c == '>'
        for (prehod in 0..1) {
            pos = 0
            while (true) {
                val i = xml.indexOf("http", pos, ignoreCase = prehod == 0)
                if (i < 0) break
                val sh = when {
                    xml.startsWith("s://", i + 4, ignoreCase = prehod == 0) -> 8
                    xml.startsWith("://", i + 4) -> 7
                    else -> 0
                }
                if (sh == 0) { pos = i + 1; continue }
                var e = i + sh
                while (e < n && !meja(xml[e])) e++
                val telo = xml.substring(i + sh, e)
                val zadetek = if (prehod == 0) {
                    listOf("drmtoday", "license-proxy-widevine", "/widevine", "/license").any { telo.indexOf(it, 1, ignoreCase = true) >= 1 }
                } else {
                    telo.contains("lic.") || telo.contains("/lic/")
                }
                if (zadetek) {
                    dodaj(xml.substring(i, e))
                    pos = e
                } else {
                    pos = if (e > i + sh) e else i + 1
                }
            }
        }

        val kandidati = if (samoHttps) found.filter { it.startsWith("https://", ignoreCase = true) } else found.toList()
        return kandidati.firstOrNull { it.contains("drmtoday", true) }
            ?: kandidati.firstOrNull { it.contains("widevine", true) }
            ?: kandidati.firstOrNull()
    }
}
