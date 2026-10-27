package si.safeer.tv.os

import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.text.Normalizer

/**
 * Spletne storitve v Sporocilih (lastnik 27. 10. 2026: »sporocila kot Ferdium tudi na Androidu - tudi na TV, s tipkovnico
 * in misko, za uporabnike brez racunalnika«). Isti katalog kot na Linuxu (core/spletni_klepeti.py + ferdium-recipes,
 * izdela ga tools/izdelaj_spletne_klepete.py v assets/sporocila/spletni-klepeti.json) in ista pravila:
 *  - nobena storitev ni privzeto vklopljena; uporabnik doda samo tisto, kar rabi (vsaka zasede pomnilnik),
 *  - storitev je SAMO podatkovni vnos (ime, naslov, ikona) - nobene kode za posamezno stran (CONTRIBUTING.md),
 *  - stevilo neprebranih iz naslova strani (document.title), splosno za vse storitve.
 * Ciste odlocitve brez Androida (tests/SpletniKlepetiPravilaTest.kt).
 */
object SpletniKlepetiPravila {
    /** Vrsta kanala v SporocilaShramba. */
    const val VRSTA = "splet"
    const val KLEPET = "klepet"
    const val POSTA = "posta"
    const val AI = "ai"
    const val NAJVEC_KANALOV = 40
    const val NAJDALJSI_NASLOV = 2048
    const val NAJDALJSE_IME = 60
    const val NAJVEC_NEPREBRANIH = 9999
    val JEZIKI = listOf("sl", "en", "de", "es", "fr", "it")

    data class Storitev(val id: String, val ime: String, val pripis: String, val vrsta: String, val splet: String,
                        val prenos: String, val izmerjeno: Boolean, val ikona: String)

    class Katalog(val storitve: List<Storitev>, val pripisi: Map<String, Map<String, String>>) {
        fun poId(id: String): Storitev? = storitve.firstOrNull { it.id == id }

        /** Ime za vmesnik: lastno ime + preveden pripis (»Microsoft Teams (osebni)«). */
        fun ime(s: Storitev, jezik: String): String {
            if (s.pripis.isEmpty()) return s.ime
            val j = if (pripisi.containsKey(jezik)) jezik else "en"
            return "${s.ime} (${pripisi[j]?.get(s.pripis) ?: s.pripis})"
        }

        /** Iskanje po imenu (brez velikih crk in sumnikov); prazno = vse. Izmerjene najprej, nato po imenu. */
        fun isci(beseda: String, jezik: String): List<Storitev> {
            val b = cisto(beseda)
            val zadetki = if (b.isEmpty()) storitve else storitve.filter { s ->
                val c = cisto(ime(s, jezik) + " " + s.id + " " + gostitelj(s.splet))
                b.split(' ').all { c.contains(it) }
            }
            return zadetki.sortedWith(compareBy<Storitev>({ !it.izmerjeno }, { cisto(it.ime) }))
        }
    }

    /** Katalog iz assets/sporocila/spletni-klepeti.json; vnos brez https naslova ali imena izpade (ne podre seznama). */
    fun razcleni(besedilo: String): Katalog {
        val o = JSONObject(besedilo)
        val seznam = o.optJSONArray("storitve")
        val storitve = ArrayList<Storitev>()
        val videni = HashSet<String>()
        if (seznam != null) for (i in 0 until seznam.length()) {
            val v = seznam.optJSONObject(i) ?: continue
            val id = v.optString("id"); val ime = v.optString("ime"); val splet = v.optString("splet")
            if (id.isBlank() || ime.isBlank() || !id.matches(Regex("[A-Za-z0-9._-]{1,64}")) || !videni.add(id)) continue
            val naslov = try { preveriNaslov(splet) } catch (_: NeveljavenNaslov) { continue }
            storitve.add(Storitev(id, ime.take(NAJDALJSE_IME), v.optString("pripis"), v.optString("vrsta", KLEPET), naslov,
                v.optString("prenos"), v.optBoolean("izmerjeno"), v.optString("ikona")))
        }
        val pripisi = HashMap<String, Map<String, String>>()
        o.optJSONObject("pripisi")?.let { p ->
            for (j in p.keys()) { val m = p.optJSONObject(j) ?: continue; pripisi[j] = m.keys().asSequence().associateWith { m.optString(it) } }
        }
        return Katalog(storitve, pripisi)
    }

    fun cisto(t: String): String = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun gostitelj(naslov: String): String = try { (URI(naslov).host ?: "").lowercase().removePrefix("www.") } catch (_: Exception) { "" }

    // ------------------------------------------------------------------------------------------- neprebrano iz naslova
    private const val STEVILO = "(\\d{1,3}(?:[ \\u00a0\\u202f.,'’]\\d{3})+|\\d{1,5})"
    private val LOCILO = Regex("\\s[-–—|·•:]\\s")
    private val SPREDAJ_OKLEPAJ = Regex("^\\s*[(\\[]\\s*$STEVILO\\s*\\+?\\s*[)\\]]")
    private val SPREDAJ_STEVILO = Regex("^\\s*(\\d{1,5})\\+?\\s*[-–—|·•:]\\s")
    private val V_DELU_OKLEPAJ = Regex("[(\\[]\\s*(\\d{1,3}(?:[ \\u00a0\\u202f.,'’]\\d{3})+|\\d{1,3})\\s*\\+?\\s*[)\\]]")
    private val OZNAKA_BREZ_STEVILA = Regex("^\\s*(?:[*•●!]\\s|[(\\[]\\s*[*•●!]\\s*[)\\]])")

    private fun vStevilo(t: String): Int = t.filter { it.isDigit() }.toIntOrNull()?.coerceAtMost(NAJVEC_NEPREBRANIH) ?: NAJVEC_NEPREBRANIH

    /**
     * Stevilo neprebranih iz naslova strani - enaka pravila kot Linux (core/spletni_klepeti.neprebrano):
     *  1. stevilo v oklepaju na zacetku: »(3) WhatsApp«, »[12] Element«, »(99+) …«, tudi »(1 234) …«;
     *  2. stevilo na zacetku, ki mu sledi locilo: »4 · Klepet«;
     *  3. stevilo v oklepaju v PRVEM delu naslova (do » - «, » | « …), najvec tri stevke: »Inbox (5) - … - Gmail«
     *     (letnica v imenu sobe »Srečanje (2026)« ni stevilo sporocil);
     *  4. oznaka brez stevila na zacetku (»* Slack«, »• …«, »(•) …«) = vsaj eno;
     * sicer 0.
     */
    fun neprebrano(naslov: String?): Int {
        val s = naslov?.trim().orEmpty()
        if (s.isEmpty()) return 0
        for (v in listOf(SPREDAJ_OKLEPAJ, SPREDAJ_STEVILO)) v.find(s)?.let { return vStevilo(it.groupValues[1]) }
        val prvi = LOCILO.split(s, 2)[0]
        V_DELU_OKLEPAJ.find(prvi)?.let { return vStevilo(it.groupValues[1]) }
        return if (OZNAKA_BREZ_STEVILA.containsMatchIn(s)) 1 else 0
    }

    // ------------------------------------------------------------------------------------------------- lasten naslov
    /** Koda napake: prazno, https, poverilnice, gostitelj, dolzina. */
    class NeveljavenNaslov(val koda: String) : IllegalArgumentException(koda)

    private val DOVOLJEN_GOSTITELJ = Regex("^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$")
    private val SHEMA = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * Lasten klepet (Mattermost, Rocket.Chat, lasten Element …) in vsak vnos kataloga: samo https, brez imena ali gesla
     * v naslovu (geslo bi koncalo v nastavitvah in na zaslonu), z veljavnim gostiteljem. Vrne poenoten naslov.
     */
    fun preveriNaslov(naslov: String?): String {
        var s = naslov?.trim().orEmpty()
        if (s.isEmpty()) throw NeveljavenNaslov("prazno")
        if (s.length > NAJDALJSI_NASLOV) throw NeveljavenNaslov("dolzina")
        if (s.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) throw NeveljavenNaslov("gostitelj")
        if (!s.contains("://")) {
            val m = SHEMA.find(s)
            if (m != null && !(s.getOrNull(m.range.last + 1)?.isDigit() ?: false)) throw NeveljavenNaslov("https")
            s = "https://$s"
        }
        // Ime ali geslo pred »@« (tudi »https://zli.primer\\@klepet.primer«, ki ga URI sploh ne razcleni) - pred razclenitvijo.
        val zacetek = s.indexOf("://")
        if (zacetek >= 0 && s.substring(zacetek + 3).takeWhile { it != '/' && it != '?' && it != '#' }.contains('@')) {
            if (s.substring(0, zacetek).equals("https", ignoreCase = true)) throw NeveljavenNaslov("poverilnice")
        }
        val u = try { URI(s) } catch (_: Exception) { throw NeveljavenNaslov("gostitelj") }
        if (!u.scheme.equals("https", ignoreCase = true)) throw NeveljavenNaslov("https")
        val avtoriteta = u.rawAuthority ?: throw NeveljavenNaslov("gostitelj")
        if (u.rawUserInfo != null || avtoriteta.contains('@')) throw NeveljavenNaslov("poverilnice")
        // URI.host je null za gostitelja z neASCII crkami (»šola.si«) ali z neveljavnimi vrati (»x.org:99999«): takrat
        // avtoriteto razclenimo sami - gostitelj v IDN, vrata samo stevke do 65535.
        val (surov, stVrat) = if (u.host != null) u.host to u.port else {
            val a = u.authority ?: throw NeveljavenNaslov("gostitelj")
            if (a.contains('[')) throw NeveljavenNaslov("gostitelj")
            val d = a.lastIndexOf(':')
            if (d < 0) a to -1 else {
                val v = a.substring(d + 1)
                if (v.isEmpty() || v.length > 5 || !v.all { it in '0'..'9' }) throw NeveljavenNaslov("gostitelj")
                a.substring(0, d) to v.toInt()
            }
        }
        if (stVrat > 65535 || stVrat == 0) throw NeveljavenNaslov("gostitelj")
        var gost = surov.trimEnd('.').lowercase()
        if (gost.isEmpty()) throw NeveljavenNaslov("gostitelj")
        val ip = gost.startsWith("[") || gost.matches(Regex("^[0-9.]+$"))
        if (!ip) {
            gost = try { IDN.toASCII(gost, IDN.ALLOW_UNASSIGNED).lowercase() } catch (_: Exception) { throw NeveljavenNaslov("gostitelj") }
            if (!DOVOLJEN_GOSTITELJ.matches(gost)) throw NeveljavenNaslov("gostitelj")
        } else if (!gost.startsWith("[") && gost.split('.').let { d -> d.size != 4 || d.any { it.toIntOrNull()?.let { n -> n > 255 } ?: true } }) {
            throw NeveljavenNaslov("gostitelj")
        }
        val vrata = if (stVrat > 0) ":$stVrat" else ""
        val pot = u.rawPath.orEmpty().ifEmpty { "/" }
        return "https://$gost$vrata$pot" + (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
    }

    /** Ime za lasten naslov: gostitelj brez »www.«. */
    fun imeIzNaslova(naslov: String): String = gostitelj(naslov).take(NAJDALJSE_IME)

    /** Ostane v oknu storitve (http/https); vse drugo (intent:, mailto:, tel: …) odpre sistem - ali nic. */
    fun ostaneVOknu(naslov: String?): Boolean {
        val s = naslov?.trim()?.lowercase().orEmpty()
        return s.startsWith("https://") || s.startsWith("http://") || s.startsWith("about:blank") || s.startsWith("blob:")
    }

    /** Sistemu predamo samo varne sheme (nikoli javascript:, file:, content:). */
    fun zaSistem(naslov: String?): Boolean {
        val s = naslov?.trim()?.lowercase().orEmpty()
        return listOf("mailto:", "tel:", "sms:", "geo:", "market:", "whatsapp:", "tg:", "intent:").any { s.startsWith(it) }
    }

    /** Ime profila WebViewa (piskotki in shramba vsake storitve loceni); id kanala je 8 znakov iz UUID. */
    fun profil(kanalId: String): String = "sporocila-" + kanalId.filter { it.isLetterOrDigit() || it == '-' }.take(40)

    /**
     * Namizna stran privzeto na VSEH napravah (kot Ferdium in Linux): meritev 27. 10. 2026 na testnem telefonu - Messenger
     * z mobilnim brskalnikom pokaze samo »Prenesi aplikacijo Messenger«, z namiznim dela (stran je odzivna, tudi na
     * ozkem zaslonu). Mobilno stran uporabnik izbere sam (gumb v glavi, zapomni se za kanal).
     */
    fun namiznaPrivzeto(@Suppress("UNUSED_PARAMETER") siroko: Boolean): Boolean = true

    /** Namizni UA: Chrome na Linuxu z razlicico WebViewa naprave (nekatere strani mobilnega brskalnika ne podpirajo). */
    fun namizniUa(mobilni: String): String {
        val r = Regex("Chrome/([0-9.]+)").find(mobilni)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$r Safari/537.36"
    }
}
