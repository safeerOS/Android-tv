package si.safeer.tv.os

import java.net.URI
import java.net.URLEncoder

/**
 * Naloga 141: Usenet prek TorBoxa. NZB najde uporabnikov indeksator (Newznab), prenese in pretaka ga TorBox (paket Pro).
 * Ciste odlocitve brez Androida in omrezja (preizkus: tests/UsenetPravilaTest.kt); odjemalec je [Usenet], prenos [TorBox.usenet].
 *
 * Iz dokumentacije (ne ugibano):
 * - Newznab (specifikacija nZEDb docs/newznab_api_specification.txt, prebrano 24. 10. 2026): `t=movie&imdbid=0058935`
 *   (IMDb brez »tt«), `t=tvsearch&imdbid=…&season=13&ep=13`, `apikey` v naslovu; izid je RSS - `item` z `title`, `guid`,
 *   `enclosure url` (naslov NZB, ki vsebuje kljuc uporabnika) in `newznab:attr name="size"`; napaka je
 *   `<error code="…" description="…"/>`: 100 napacni podatki, 101 racun zaustavljen, 102 ni pravic, 500 meja zahtev,
 *   501 meja prenosov, 900 neznano. `imdbid` ni v vsakem indeksatorju (Servarr/Torznab: priporoceno, ne obvezno).
 * - TorBox (uradni OpenAPI api.torbox.app, prebrano 24. 10. 2026): `usenet/createusenetdownload` (multipart: `file` ali
 *   `link`, `name`, `add_only_if_cached`), `usenet/mylist?id=`, `usenet/requestdl?token&usenet_id&file_id`.
 *   TorBox Search (search-api.torbox.app) ne obstaja vec (ime se ne razresi; odstranitev porocana 10. 10. 2026).
 *
 * Zasebnost: kljuc indeksatorja ostane na napravi - NZB prenese naprava sama in TorBoxu poslje samo datoteko (tudi
 * zato, ker nekateri indeksatorji streznike TorBoxa blokirajo). Povezave NZB (s kljucem) niso v toku ne v dnevniku.
 */
object UsenetPravila {
    const val VRSTA = "usenet"
    const val PREDPONA = "usenet:"

    /** Indeksator ni vrnil NZB (napacen kljuc, meja, napaka) - za sporocilo uporabniku. */
    const val NAPAKA_INDEKSATOR = "indeksator"

    /** Najvec zadetkov iskanja, ki gredo med tokove (TokIzbira jih uredi). */
    const val NAJVEC_ZADETKOV = 40

    /** NZB datoteka je nekaj sto kB do nekaj MB; vec ne sprejmemo. */
    const val NAJVEC_NZB_BAJTOV = 20 * 1024 * 1024

    /** Pregled A11: NZB (ali napovedana dolzina, -1 = ni znana) nad mejo zavrnemo z napako - odrezan NZB bi bil pokvarjen. */
    fun nzbPrevelik(bajtov: Long): Boolean = bajtov > NAJVEC_NZB_BAJTOV

    /** Rok branja odgovora indeksatorja pri iskanju (vzpostavitev povezave najvec [ISKANJE_POVEZAVA_MS]). */
    const val ISKANJE_ROK_MS = 8_000
    const val ISKANJE_POVEZAVA_MS = 5_000

    /**
     * Pregled A8: tako dolgo pocakamo na iskanje Usenet pri izbiri toka (povezava + branje), da je »Usenet prvi« zanesljiv -
     * tudi ce dodatki odgovorijo prej.
     */
    const val ISKANJE_CAKAJ_MS = (ISKANJE_POVEZAVA_MS + ISKANJE_ROK_MS + 500).toLong()

    /** Usenet prenos pri TorBoxu je hiter (streznik do streznika), a velik film traja - cakamo do 10 min z napredkom. */
    const val CAKAJ_PRENOSA_MS = 600_000L

    private fun jeLokalniGostitelj(h: String): Boolean {
        val g = h.lowercase().trimEnd('.')
        if (g == "localhost" || g.endsWith(".local")) return true
        // Pregled A10: samo pravi naslov IPv4 (stiri stevila 0-255) - ime »10.example.com« ni domace omrezje.
        val d = g.split('.')
        if (d.size != 4 || d.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return false
        val b = d.map { it.toInt() }
        if (b.any { it > 255 }) return false
        return b[0] == 127 || b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] in 16..31)
    }

    /**
     * Naslov indeksatorja, kot ga uporabnik vpise (»https://api.indeksator.example/api?«): osnova brez `/api`, poizvedbe in
     * koncne posevnice. Samo https - kljuc gre v naslovu; http samo do naprave v domacem omrezju (NZBHydra, Prowlarr).
     * Brez uporabniskega imena v naslovu. null = ni veljaven.
     */
    fun osnova(vnos: String): String? {
        val v = vnos.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val u = try { URI(v) } catch (_: Exception) { return null }
        val gost = u.host ?: return null
        if (u.rawUserInfo != null) return null
        val shema = u.scheme?.lowercase() ?: return null
        if (shema != "https" && !(shema == "http" && jeLokalniGostitelj(gost))) return null
        return shema + v.substring(u.scheme.length).removeSuffix("/api").trimEnd('/')
    }

    /**
     * Indeksator, ki ga je poslala druga naprava v ozjem krogu (skupaj s kljuci debrid, naloga 129): velja samo, ce sta
     * naslov in kljuc veljavna po istih pravilih kot vnos na tej napravi. null = ni poslan ali ni veljaven.
     */
    fun prejetIndeksator(naslov: String?, kljuc: String?): Pair<String, String>? {
        val o = naslov?.let { osnova(it) } ?: return null
        val k = kljuc?.let { ocistiKljuc(it) } ?: return null
        return o to k
    }

    /** Kljuc API indeksatorja: 8-128 znakov, crke, stevke, '-', '_' (Newznab: obicajno 32 heks). */
    fun ocistiKljuc(vnos: String): String? {
        val k = vnos.trim()
        return k.takeIf { it.length in 8..128 && it.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '_' } }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Naslov iskanja za id Stremio: film `tt1375666` -> t=movie, epizoda `tt0108778:1:1` -> t=tvsearch s sezono in epizodo.
     * Drugi id-ji (tmdb:, kitsu: ...) -> null: indeksatorji iscejo po IMDb.
     */
    fun naslovIskanja(osnova: String, kljuc: String, id: String): String? {
        val (imdb, sezona, epizoda) = deliId(id) ?: return null
        val skupno = "$osnova/api?apikey=${enc(kljuc)}&imdbid=$imdb&limit=100&extended=1"
        return if (sezona == null) "$skupno&t=movie" else "$skupno&t=tvsearch&season=$sezona&ep=$epizoda"
    }

    /** Ali za id Stremio sploh iscemo (film ali epizoda po IMDb). */
    fun iscemo(id: String): Boolean = deliId(id) != null

    /** IMDb brez »tt«, sezona in epizoda (null pri filmu); null = id ni za iskanje. */
    private fun deliId(id: String): Triple<String, Int?, Int?>? {
        val d = id.trim().split(':')
        val imdb = d[0].lowercase().takeIf { it.length in 3..12 && it.startsWith("tt") && it.drop(2).all { c -> c.isDigit() } }?.drop(2) ?: return null
        return when (d.size) {
            1 -> Triple(imdb, null, null)
            3 -> {
                val s = d[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val e = d[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                Triple(imdb, s, e)
            }
            else -> null
        }
    }

    data class Zadetek(val naslov: String, val povezava: String, val velikost: Long)

    /** Napaka Newznab (`<error code=…>`) v nase vrste napak; null = ni napake. */
    fun napaka(xml: String): String? {
        val m = Regex("<error\\s+code=\"(\\d+)\"").find(xml) ?: return null
        return when (m.groupValues[1].toIntOrNull()) {
            100, 101, 102 -> TorBoxPravila.NAPAKA_KLJUC
            500, 501 -> TorBoxPravila.NAPAKA_MEJA
            else -> NAPAKA_INDEKSATOR
        }
    }

    /**
     * Zadetki iz odgovora RSS. DOCTYPE/ENTITY zavrnemo pred razclenitvijo (XXE). Povezava NZB je `enclosure url`, sicer
     * `link`; samo https, ali isti gostitelj kot indeksator ([osnova] - npr. http v domacem omrezju). Brez naslova ali
     * povezave zadetek odpade; ponovljene povezave enkrat; najvec [NAJVEC_ZADETKOV].
     */
    fun zadetki(xml: String, osnova: String): List<Zadetek> {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return emptyList()
        val dok = try {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
            f.isExpandEntityReferences = false
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        } catch (_: Exception) { return emptyList() }
        val gostIndeksatorja = try { URI(osnova).host?.lowercase() } catch (_: Exception) { null }
        val elementi = dok.getElementsByTagName("item")
        val izid = ArrayList<Zadetek>()
        val videne = HashSet<String>()
        for (i in 0 until elementi.length) {
            val el = elementi.item(i) as? org.w3c.dom.Element ?: continue
            fun besedilo(ime: String) = el.getElementsByTagName(ime).item(0)?.textContent?.trim().orEmpty()
            val prilozeno = (el.getElementsByTagName("enclosure").item(0) as? org.w3c.dom.Element)?.getAttribute("url")?.trim().orEmpty()
            val povezava = prilozeno.ifBlank { besedilo("link") }
            val naslov = besedilo("title").replace(Regex("\\s+"), " ").take(200)
            if (naslov.isBlank() || !dovoljenaPovezava(povezava, gostIndeksatorja) || !videne.add(povezava)) continue
            var velikost = 0L
            val atributi = el.getElementsByTagName("newznab:attr")
            for (j in 0 until atributi.length) {
                val a = atributi.item(j) as? org.w3c.dom.Element ?: continue
                if (a.getAttribute("name") == "size") velikost = a.getAttribute("value").toLongOrNull() ?: 0L
            }
            if (velikost <= 0) velikost = (el.getElementsByTagName("enclosure").item(0) as? org.w3c.dom.Element)
                ?.getAttribute("length")?.toLongOrNull() ?: 0L
            // Indeksator isto izdajo pogosto vrne veckrat (izmerjeno 24. 10. 2026): enak naslov in velikost = enkrat.
            if (!videne.add(naslov + "|" + velikost)) continue
            izid += Zadetek(naslov, povezava, velikost.coerceAtLeast(0))
            if (izid.size >= NAJVEC_ZADETKOV) break
        }
        return izid
    }

    private fun dovoljenaPovezava(p: String, gostIndeksatorja: String?): Boolean {
        val u = try { URI(p) } catch (_: Exception) { return false }
        val gost = u.host?.lowercase() ?: return false
        return when (u.scheme?.lowercase()) {
            "https" -> true
            "http" -> gost == gostIndeksatorja && jeLokalniGostitelj(gost)
            else -> false
        }
    }

    /** Id toka za zadetek: »usenet:« + prvih 16 heks SHA-1 povezave - povezava (s kljucem) ostane samo v pomnilniku. */
    fun idToka(povezava: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(povezava.toByteArray(Charsets.UTF_8))
        return PREDPONA + d.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun jeUsenet(url: String): Boolean = url.startsWith(PREDPONA)

    /** Opis toka v obliki dodatkov (»… 💾 12.7 GB«), da ga TokIzbira razbere (locljivost, kodek, velikost). */
    fun opis(z: Zadetek): String =
        if (z.velikost <= 0) z.naslov else z.naslov + "\n💾 " + String.format(java.util.Locale.ROOT, "%.1f GB", z.velikost / 1_073_741_824.0)

    // ------------------------------------------------------------------ TorBox (Usenet)

    const val OSNOVA_TORBOX = "https://api.torbox.app/v1/api/usenet"
    fun naslovUstvari(): String = "$OSNOVA_TORBOX/createusenetdownload"
    fun naslovSeznam(id: Long): String = "$OSNOVA_TORBOX/mylist?id=$id&bypass_cache=true"
    fun naslovVsi(): String = "$OSNOVA_TORBOX/mylist?bypass_cache=true"

    /** POZOR: vsebuje kljuc TorBox (token) - samo za klic, nikoli v dnevnik, predvajalnik ali zgodovino. */
    fun naslovPrenosa(kljuc: String, id: Long, datotekaId: Long): String =
        "$OSNOVA_TORBOX/requestdl?token=${enc(kljuc)}&usenet_id=$id&file_id=$datotekaId"

    /**
     * Odgovor createusenetdownload z `add_only_if_cached`, ko prenosa ni v predpomnilniku: TorBox vrne neuspeh. Kljuc,
     * paket in meja ostanejo svoje napake (vrstaNapake TorBoxa); vse drugo pri »samo takoj« pomeni »ni pripravljeno«.
     */
    fun niPredpomnjeno(samoTakoj: Boolean, vrstaNapake: String): Boolean =
        samoTakoj && vrstaNapake !in setOf(TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.NAPAKA_PAKET, TorBoxPravila.NAPAKA_MEJA)

    /**
     * Samodejna izbira (lastnik, 24. 10. 2026: »automatsko se zažene najboljši vir … v našem primeru usenet«): Usenet gre
     * prvi, z cakanjem na prenos pri TorBoxu. Izmerjeno na TV 24. 10. 2026: 2,2 GB v 36 s - zato najvec [NAJVEC_GB_SAMODEJNO]
     * (nekaj minut); vecje izdaje (4K REMUX 50+ GB) ostanejo za rocno izbiro. Neznana velikost (0) je dovoljena.
     * [gb] po vrstnem redu kakovosti; vrne indekse za poskuse (najvec [POSKUSOV_SAMODEJNO]).
     */
    fun zaSamodejno(gb: List<Double>): List<Int> =
        gb.indices.filter { gb[it] <= NAJVEC_GB_SAMODEJNO }.take(POSKUSOV_SAMODEJNO)

    const val NAJVEC_GB_SAMODEJNO = 15.0
    const val POSKUSOV_SAMODEJNO = 2

    /** Ali je odgovor res NZB (XML z elementom `nzb`) - indeksator lahko namesto datoteke vrne napako ali stran HTML. */
    fun jeNzb(zacetek: String): Boolean = zacetek.contains("<nzb", ignoreCase = true) && !zacetek.contains("<!ENTITY", ignoreCase = true)
}
