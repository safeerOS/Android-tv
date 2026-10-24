package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki

/**
 * Naloga 123: Safeer Add-on Protocol v1 (docs/DODATKI-PROTOKOL.md) - jedro SDK za Android-tv. Isti protokol imata
 * Safeer-linux in Safeer-windows (vsak svoj SDK v svojem jeziku), zato dodatek, napisan enkrat, dela na vseh napravah.
 *
 * Dodatek je Stremio dodatek z razsiritvami pod kljucem `safeer` v manifestu. Koda dodatka nikoli ne tece na napravi:
 * tu se samo PREBERE in PREVERI, kar dodatek opise (dejanja, dopolnitev, mape, nastavitve, spremembe); risanje je delo
 * Medijskega centra (naloga 124). Ciste funkcije brez Androida in brez org.json (JsonLahki) - preizkus:
 * tests/DodatkiProtokolTest.kt. Neveljaven vnos odpade sam; pokvarjena razsiritev nikoli ne onemogoci dela Stremio.
 */
object DodatkiProtokol {
    /** Verzija protokola, ki jo zna ta gostitelj (enaka kot `PROTOKOL-VERZIJA` v docs/DODATKI-PROTOKOL.md). */
    const val VERZIJA = 1

    const val NAJVEC_DEJANJ = 8
    const val NAJVEC_MAP = 4
    const val NAJVEC_NASTAVITEV = 20
    const val NAJVEC_MOZNOSTI = 20
    const val NAJVEC_NASLOV = 80
    const val NAJVEC_SPOROCILO = 500
    const val NAJVEC_ELEMENTOV = 200
    const val NAJVEC_ODGOVOR_B = 1024 * 1024

    val TIPI = setOf("movie", "series", "episode", "tv", "channel", "radio", "music")
    val IKONE = setOf("plus", "check", "heart", "star", "list", "folder", "download", "subtitles", "info", "share", "refresh", "lock")
    val VRSTE_NASTAVITEV = setOf("text", "secret", "toggle", "choice")
    private val ID = Regex("^[a-z0-9-]{1,40}$")
    private val KLJUC = Regex("^[a-z0-9_]{1,40}$")

    /** Besedilo po jezikih; [za] izbere jezik uporabnika, nato angleskega, nato prvega. */
    data class Besedilo(val po: Map<String, String>) {
        fun za(jezik: String): String = po[jezik.lowercase().substringBefore('-')] ?: po["en"] ?: po.values.firstOrNull().orEmpty()
    }

    data class Dejanje(val id: String, val na: Set<String>, val ikona: String, val naslov: Besedilo, val potrdi: Boolean)
    data class Mapa(val id: String, val ikona: String, val ime: Besedilo)
    data class Nastavitev(val kljuc: String, val vrsta: String, val obvezna: Boolean, val moznosti: List<String>,
                          val privzeto: String, val naslov: Besedilo) {
        val skrivnost: Boolean get() = vrsta == "secret"
    }

    /**
     * Prebrana razsiritev. [novejsi]: dodatek zahteva novejsi protokol - razsiritve so prezrte (del Stremio dela naprej),
     * uporabniku povemo, naj posodobi Safeer. [opozorila]: kaj je odpadlo in zakaj (za razvijalca, orodje za preverjanje).
     */
    data class Razsiritev(
        val protokol: Int,
        val novejsi: Boolean = false,
        val dejanja: List<Dejanje> = emptyList(),
        val dopolni: Set<String> = emptySet(),
        val mape: List<Mapa> = emptyList(),
        val nastavitve: List<Nastavitev> = emptyList(),
        val spremembe: Map<String, Besedilo> = emptyMap(),
        val opozorila: List<String> = emptyList(),
    ) {
        val imaSkrivnosti: Boolean get() = nastavitve.any { it.skrivnost }
    }

    /** Navadno besedilo: brez kontrolnih znakov in odvecnih presledkov, najvec [najvec] znakov. */
    fun cisto(s: String, najvec: Int): String =
        s.filter { !it.isISOControl() || it == ' ' }.replace(Regex("\\s+"), " ").trim().take(najvec)

    /** Besedilo iz polja: niz ali objekt {jezik: niz}; prazno ali neveljavno -> null. */
    fun besedilo(p: JsonLahki.Pogled, kljuc: String, najvec: Int = NAJVEC_NASLOV): Besedilo? = when (p.vrsta(kljuc)) {
        JsonLahki.Vrsta.NIZ -> cisto(p.nizAli(kljuc), najvec).takeIf { it.isNotEmpty() }?.let { Besedilo(mapOf("en" to it)) }
        JsonLahki.Vrsta.OBJEKT -> p.objekt(kljuc)?.let { o ->
            o.kljuci().mapNotNull { j -> o.niz(j)?.let { cisto(it, najvec) }?.takeIf { it.isNotEmpty() }?.let { j.lowercase() to it } }
                .toMap().takeIf { it.isNotEmpty() }?.let { Besedilo(it) }
        }
        else -> null
    }

    fun ikona(ime: String?): String = ime?.trim()?.lowercase()?.takeIf { it in IKONE } ?: "info"

    private fun objekti(p: JsonLahki.Pogled, kljuc: String): List<JsonLahki.Pogled> =
        p.surovo(kljuc)?.takeIf { p.vrsta(kljuc) == JsonLahki.Vrsta.SEZNAM }?.let { JsonLahki.elementi(it) }.orEmpty()
            .mapNotNull { JsonLahki.objekt(it) }

    /** Razsiritev iz besedila manifesta; null, ce manifest kljuca `safeer` nima (navaden dodatek Stremio). */
    fun preberi(manifest: String): Razsiritev? {
        val m = JsonLahki.objekt(manifest) ?: return null
        val s = m.objekt("safeer") ?: return null
        val opozorila = ArrayList<String>()
        val protokol = s.stevilo("protocol")?.toInt()?.takeIf { it >= 1 }
            ?: return Razsiritev(0, opozorila = listOf("protocol: manjka ali ni celo stevilo >= 1 - razsiritve prezrte"))
        if (protokol > VERZIJA) return Razsiritev(protokol, novejsi = true, opozorila = listOf("protocol $protokol > $VERZIJA: posodobi Safeer"))

        val dejanja = objekti(s, "actions").mapNotNull { a ->
            val id = a.nizAli("id").trim()
            val na = a.nizi("on").map { it.trim().lowercase() }.filter { it in TIPI }.toSet()
            val naslov = besedilo(a, "label")
            when {
                !ID.matches(id) -> { opozorila += "actions: neveljaven id \"${id.take(40)}\""; null }
                na.isEmpty() -> { opozorila += "actions/$id: on brez znanega tipa"; null }
                naslov == null -> { opozorila += "actions/$id: manjka label"; null }
                else -> {
                    if (a.ima("icon") && a.nizAli("icon").trim().lowercase() !in IKONE) opozorila += "actions/$id: neznana ikona -> info"
                    Dejanje(id, na, ikona(a.niz("icon")), naslov, a.logicno("confirm") == true)
                }
            }
        }.distinctBy { it.id }
        if (dejanja.size > NAJVEC_DEJANJ) opozorila += "actions: vec kot $NAJVEC_DEJANJ - odvec odpade"

        val dopolni = s.nizi("enrich").map { it.trim().lowercase() }.filter { it in TIPI }.toSet()

        val mape = objekti(s, "folders").mapNotNull { f ->
            val id = f.nizAli("id").trim()
            val ime = besedilo(f, "name")
            when {
                !ID.matches(id) -> { opozorila += "folders: neveljaven id \"${id.take(40)}\""; null }
                ime == null -> { opozorila += "folders/$id: manjka name"; null }
                else -> Mapa(id, ikona(f.niz("icon") ?: "folder"), ime)
            }
        }.distinctBy { it.id }
        if (mape.size > NAJVEC_MAP) opozorila += "folders: vec kot $NAJVEC_MAP - odvec odpade"

        val nastavitve = objekti(s, "settings").mapNotNull { n ->
            val kljuc = n.nizAli("key").trim()
            val vrsta = n.nizAli("type").trim().lowercase()
            val naslov = besedilo(n, "label")
            val moznosti = n.nizi("options").map { cisto(it, NAJVEC_NASLOV) }.filter { it.isNotEmpty() }.distinct().take(NAJVEC_MOZNOSTI)
            val privzeto = when (n.vrsta("default")) {
                JsonLahki.Vrsta.NIZ -> cisto(n.nizAli("default"), NAJVEC_SPOROCILO)
                JsonLahki.Vrsta.LOGICNO -> n.logicno("default").toString()
                JsonLahki.Vrsta.STEVILO -> n.surovo("default").orEmpty()
                else -> ""
            }
            when {
                !KLJUC.matches(kljuc) -> { opozorila += "settings: neveljaven key \"${kljuc.take(40)}\""; null }
                vrsta !in VRSTE_NASTAVITEV -> { opozorila += "settings/$kljuc: neznan type \"${vrsta.take(20)}\""; null }
                naslov == null -> { opozorila += "settings/$kljuc: manjka label"; null }
                vrsta == "choice" && moznosti.isEmpty() -> { opozorila += "settings/$kljuc: choice brez options"; null }
                // Privzeta skrivnost bi bila v manifestu za vse - ne sprejmemo je.
                vrsta == "secret" && privzeto.isNotEmpty() -> { opozorila += "settings/$kljuc: secret ne sme imeti default"; null }
                else -> Nastavitev(kljuc, vrsta, n.logicno("required") == true, moznosti,
                    if (vrsta == "choice" && privzeto !in moznosti) moznosti.first() else privzeto, naslov)
            }
        }.distinctBy { it.kljuc }
        if (nastavitve.size > NAJVEC_NASTAVITEV) opozorila += "settings: vec kot $NAJVEC_NASTAVITEV - odvec odpade"

        val spremembe = s.objekt("changelog")?.let { c ->
            c.kljuci().mapNotNull { v -> besedilo(c, v, NAJVEC_SPOROCILO)?.let { v.trim().take(20) to it } }.toMap()
        }.orEmpty()

        return Razsiritev(protokol, false, dejanja.take(NAJVEC_DEJANJ), dopolni, mape.take(NAJVEC_MAP),
            nastavitve.take(NAJVEC_NASTAVITEV), spremembe, opozorila)
    }

    /** Dejanja za vsebino tipa [tip] (vrstni red manifesta). */
    fun dejanjaZa(r: Razsiritev, tip: String): List<Dejanje> = r.dejanja.filter { tip.lowercase() in it.na }

    // ------------------------------------------------------------------ naslovi koncnih tock (relativno na osnovo dodatka)

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun brezKonca(osnova: String) = osnova.trimEnd('/')

    fun naslovDejanja(osnova: String, dejanje: String, tip: String, id: String): String =
        "${brezKonca(osnova)}/safeer/action/${enc(dejanje)}/${enc(tip)}/${enc(id)}.json"

    fun naslovDopolni(osnova: String, tip: String, id: String): String = "${brezKonca(osnova)}/safeer/enrich/${enc(tip)}/${enc(id)}.json"

    /** Pot je zaporedje delov, vsak zakodiran posebej (`/` med njimi ostane locilo). */
    fun naslovMape(osnova: String, mapa: String, pot: String = ""): String {
        val deli = pot.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        return "${brezKonca(osnova)}/safeer/folder/${enc(mapa)}" + (if (deli.isEmpty()) "" else "/" + deli.joinToString("/") { enc(it) }) + ".json"
    }

    // ------------------------------------------------------------------ nastavitve, poslane dodatku

    /**
     * Vrednost glave `X-Safeer-Config`: base64url (brez polnila) JSON objekta z vrednostmi nastavitev - nikoli v naslovu.
     * Kljuci, ki jih manifest ne opisuje, odpadejo (dodatek dobi samo, kar je sam zahteval).
     */
    fun glavaNastavitev(opis: List<Nastavitev>, vrednosti: Map<String, String>): String {
        val z = JsonLahki.Zapis()
        for (n in opis) {
            val v = vrednosti[n.kljuc] ?: n.privzeto
            if (n.vrsta == "toggle") z.logicno(n.kljuc, v == "true") else if (v.isNotEmpty()) z.niz(n.kljuc, v)
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(z.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Naloga 124: katere nastavitve gredo v glavi zahteve na [naslov]: po https vse; sicer samo navadne (skrivnost nikoli
     * nesifrirano - tudi ne v razvojnem nacinu, kjer gre lahko http na krajevni naslov).
     */
    fun nastavitveZaZahtevo(opis: List<Nastavitev>, naslov: String): List<Nastavitev> =
        if (naslov.trim().lowercase().startsWith("https://")) opis else opis.filter { !it.skrivnost }

    /** Krajevni naslov (razvojni nacin): localhost, 127.x, 10.x, 192.168.x, 172.16-31.x, ime .local. */
    fun jeKrajevni(gostitelj: String): Boolean {
        val g = gostitelj.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (g == "localhost" || g == "::1" || g.endsWith(".local")) return true
        val d = g.split('.').mapNotNull { it.toIntOrNull() }
        if (d.size != 4 || g.count { it == '.' } != 3) return false
        return d[0] == 127 || d[0] == 10 || (d[0] == 192 && d[1] == 168) || (d[0] == 172 && d[1] in 16..31)
    }

    /**
     * Ali sme zahteva na [naslov] iti: https vedno; http samo v razvojnem nacinu na krajevni naslov. Tako tudi skrivnosti
     * nastavitev nikoli ne gredo nesifrirano cez internet.
     */
    fun smePoslati(naslov: String, razvojni: Boolean): Boolean {
        val u = try { java.net.URI(naslov.trim()) } catch (_: Exception) { return false }
        val shema = u.scheme?.lowercase() ?: return false
        val gostitelj = u.host ?: return false
        return shema == "https" || (shema == "http" && razvojni && jeKrajevni(gostitelj))
    }

    /**
     * Naloga 125: vrsta zahteve za dnevnik razvojnega nacina - samo ime vira, nikoli pot ali poizvedba (pot dodatka ima
     * lahko kljuc storitve, npr. »/realdebrid=<kljuc>/«, id vsebine pa ni potreben za razhroscevanje oblike).
     */
    fun vrstaZahteve(url: String): String {
        val u = url.substringBefore('?')
        return listOf("/manifest.json" to "manifest", "/catalog/" to "catalog", "/meta/" to "meta", "/stream/" to "stream",
            "/subtitles/" to "subtitles", "/safeer/action/" to "action", "/safeer/enrich/" to "enrich", "/safeer/folder/" to "folder")
            .firstOrNull { (k, _) -> u.contains(k) }?.second ?: "drugo"
    }

    // ------------------------------------------------------------------ omejeno branje odgovora (pregled 25. 10. 2026)
    /**
     * Prebere najvec [najvec] bajtov odgovora dodatka iz [vhod]; vec (ali [dolzina] iz Content-Length nad mejo) - null,
     * brez branja ostanka. Prej je bila meja preverjena sele po readBytes() celega odgovora: velik odgovor je na TV
     * napolnil pomnilnik, preden ga je meja zavrnila. [dolzina] < 0 = neznana (chunked).
     */
    fun preberiOmejeno(vhod: java.io.InputStream, dolzina: Long, najvec: Int = NAJVEC_ODGOVOR_B): ByteArray? {
        if (najvec < 0 || dolzina > najvec) return null
        val izhod = java.io.ByteArrayOutputStream(if (dolzina in 0..najvec.toLong()) dolzina.toInt() else minOf(najvec, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var skupaj = 0L
        while (true) {
            // Beremo najvec do meje + 1 bajt: tako vemo, da je odgovor prevelik, ne da bi ga prebrali do konca.
            val n = vhod.read(buf, 0, minOf(buf.size.toLong(), najvec + 1L - skupaj).toInt())
            if (n < 0) break
            skupaj += n
            if (skupaj > najvec) return null
            izhod.write(buf, 0, n)
        }
        return izhod.toByteArray()
    }

    /** Kot [preberiOmejeno], kot besedilo UTF-8. */
    fun preberiOmejenoBesedilo(vhod: java.io.InputStream, dolzina: Long, najvec: Int = NAJVEC_ODGOVOR_B): String? =
        preberiOmejeno(vhod, dolzina, najvec)?.toString(Charsets.UTF_8)

    // ------------------------------------------------------------------ kljuci nastavitev na napravi (DodatkiNastavitve)
    /**
     * Id osnove dodatka v kljucih nastavitev in skrivnosti: SHA-256 cele osnove (hex, 64 znakov). Prej 32-bitni
     * String.hashCode - dve osnovi z enakim hashCode (npr. namerno izracunani) sta si delili skrivnosti.
     */
    fun idOsnove(osnova: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(osnova.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Stari id osnove (do pregleda 25. 10. 2026): 32-bitni String.hashCode - samo za enkratno selitev in pozabo. */
    fun stariIdOsnove(osnova: String): String = Integer.toHexString(osnova.hashCode())

    /**
     * Ali stari zapisi (kljuc s [stariIdOsnove]) nedvoumno pripadajo [osnova]: noben drug namescen dodatek ([vseOsnove])
     * nima istega starega id-ja. Sicer ne vemo, cigavi so, in jih ne damo nobenemu (uporabnik vrednost vpise znova).
     */
    fun smeSeliti(osnova: String, vseOsnove: Collection<String>): Boolean {
        val stari = stariIdOsnove(osnova)
        return vseOsnove.none { it != osnova && stariIdOsnove(it) == stari }
    }

    // ------------------------------------------------------------------ odgovori

    data class OdgovorDejanja(val ok: Boolean, val sporocilo: Besedilo?, val osvezi: Boolean)

    fun preberiDejanje(odgovor: String): OdgovorDejanja? {
        if (odgovor.length > NAJVEC_ODGOVOR_B) return null
        val o = JsonLahki.objekt(odgovor) ?: return null
        return OdgovorDejanja(o.logicno("ok") != false, besedilo(o, "message", NAJVEC_SPOROCILO), o.logicno("refresh") == true)
    }

    data class Element(val vrsta: String, val pot: String, val tip: String, val id: String, val ime: String, val plakat: String)
    data class VsebinaMape(val elementi: List<Element>, val naprej: String)

    /**
     * Odgovor mape: `folder` (pot) in `meta` (tip, id) z imenom; plakat samo https. Najvec [NAJVEC_ELEMENTOV], `next` za vec.
     */
    fun preberiMapo(odgovor: String): VsebinaMape? {
        if (odgovor.length > NAJVEC_ODGOVOR_B) return null
        val o = JsonLahki.objekt(odgovor) ?: return null
        val elementi = objekti(o, "items").mapNotNull { e ->
            val ime = cisto(e.nizAli("name"), NAJVEC_NASLOV)
            val plakat = e.nizAli("poster").trim().takeIf { it.startsWith("https://") }.orEmpty()
            when (e.nizAli("kind").trim().lowercase()) {
                "folder" -> e.nizAli("path").trim().takeIf { it.isNotEmpty() && ime.isNotEmpty() }?.let { Element("folder", it.take(512), "", "", ime, plakat) }
                "meta" -> {
                    val tip = e.nizAli("type").trim().lowercase()
                    val id = e.nizAli("id").trim()
                    if (tip.isEmpty() || id.isEmpty() || ime.isEmpty()) null else Element("meta", "", tip, id.take(256), ime, plakat)
                }
                else -> null
            }
        }.take(NAJVEC_ELEMENTOV)
        return VsebinaMape(elementi, o.nizAli("next").trim().take(512))
    }

    /**
     * Seznam sprememb, ki ga uporabnik vidi enkrat po posodobitvi dodatka: samo ob spremembi verzije manifesta
     * ([prejsnja] -> [zdaj]) in ce je za [zdaj] zapis. Prva namestitev (ni prejsnje) ga ne pokaze.
     */
    fun spremembeZaPrikaz(r: Razsiritev, prejsnja: String?, zdaj: String): Besedilo? =
        if (prejsnja.isNullOrBlank() || prejsnja == zdaj) null else r.spremembe[zdaj]
}
