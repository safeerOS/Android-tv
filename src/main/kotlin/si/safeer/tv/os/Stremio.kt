package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Odjemalec javnega protokola dodatkov Stremio (enako kot Safeer za Windows, stremio_dodatki.py):
 * manifest.json -> katalogi (/catalog/{tip}/{id}.json, iskanje /catalog/{tip}/{id}/search=....json)
 * -> meta (/meta/{tip}/{id}.json, epizode serij) -> tokovi (/stream/{tip}/{id}.json).
 *
 * Safeer ne prilaga nobenega dodatka; naslove vnese uporabnik. Kot v Stremiu tokove za izbran film ali
 * epizodo vprasamo VSE uporabnikove dodatke, ki ponujajo vir "stream" za ta tip (katalog ima lahko en
 * dodatek, tokove drug). Predvajamo `url` (http/https); `externalUrl`/`ytId` odpre brskalnik; `infoHash`
 * odpre Magnet povezave. Brez obvodov DRM. Vsi klici so sinhroni - klicatelj jih pozene v niti.
 */
object Stremio {
    private const val PREDPONA = "stremio|"

    data class Katalog(val dodatek: String, val imeDodatka: String, val tip: String, val id: String, val ime: String,
                       val iskanje: Boolean, val obvezni: List<String>,
                       /** Obvezni dodatni parametri s prvo ponujeno moznostjo (genre=Action), kot jih Stremio pokaze v Discover. */
                       val privzeti: List<Pair<String, String>> = emptyList(),
                       /** Moznosti parametra `genre` (zvrsti ali leta), kot jih katalog oglasi - za filter zvrsti v mrezi Filmi | Serije. */
                       val zvrsti: List<String> = emptyList(),
                       /** Katalog oglasi stranjenje (extra »skip«): naslednjo stran da tudi, ce je stran manjsa od obicajne (naloga 57c). */
                       val strani: Boolean = false) {
        /** Ali katalog brez uporabnikovega filtra sploh vrne vsebino (vsi obvezni parametri imajo privzeto moznost). */
        val prikazen: Boolean get() = obvezni.all { o -> privzeti.any { it.first == o } }

        /** Naloga 60: naslovi, iz katerih ocenimo jezik kataloga (najvec [OsPravila.VZOREC_JEZIKA], brez ponovitev). Zunaj
         *  konstruktorja: ne vpliva na equals/hashCode (katalog je kljuc v OdprtKatalog.preneseno). */
        private val vzorecJezika = LinkedHashSet<String>()
        /** Ocenjen jezik kataloga ([OsPravila.jezikKataloga]); "" = neznan. Manifest (in z njim katalog) je v [manifesti]. */
        @Volatile var jezik: String = ""
            private set

        /** Doda naslove strani v vzorec, dokler ni poln, in vrne oceno jezika kataloga. Klic iz delovne niti. */
        fun oceniJezik(naslovi: List<String>): String = synchronized(vzorecJezika) {
            if (vzorecJezika.size < OsPravila.VZOREC_JEZIKA) {
                val prej = vzorecJezika.size
                for (n in naslovi) { if (vzorecJezika.size >= OsPravila.VZOREC_JEZIKA) break; if (n.isNotBlank()) vzorecJezika += n }
                if (vzorecJezika.size != prej) jezik = OsPravila.jezikKataloga(vzorecJezika)
            }
            jezik
        }
    }

    data class Manifest(val osnova: String, val ime: String, val viri: Set<String>, val tipi: Set<String>,
                        val predpone: List<String>, val katalogi: List<Katalog>,
                        /** Zaseben dodatek ([ZasebniDodatki]): ne gre med police, mreze, iskanje in na druge naprave. */
                        val zaseben: Boolean = false,
                        /** Naloga 79b: `id` manifesta (isti dodatek pod drugim naslovom, npr. z narocnisko kodo). */
                        val id: String = "",
                        /** Naloga 123/124: razsiritve Safeer (kljuc `safeer`, docs/DODATKI-PROTOKOL.md); null = navaden dodatek. */
                        val razsiritev: DodatkiProtokol.Razsiritev? = null,
                        /** `version` manifesta (za seznam sprememb enkrat po posodobitvi). */
                        val verzija: String = "")

    data class Epizoda(val id: String, val ime: String, val sezona: Int, val epizoda: Int,
                       /** Tokovi, ki jih dodatek poda ze v metapodatkih posnetka (`videos[].streams`); prazno = vprasamo /stream. */
                       val tokovi: List<Tok> = emptyList())

    data class Tok(val vrsta: String, val url: String, val ime: String, val opis: String, val dodatek: String,
                   /** Torrent: katera datoteka (fileIdx), -1 = najvecji video. */
                   val datoteka: Int = -1,
                   /** `behaviorHints.proxyHeaders.request`: glave, ki jih tok zahteva (User-Agent, Referer, Origin, Cookie ...). */
                   val glave: Map<String, String> = emptyMap(),
                   /** Webtor rezerva: infoHash izdaje pri toku »url« (Webtor; ujemanje z nasim torrentom), sicer prazno. */
                   val hash: String = "",
                   /** Webtor rezerva: [WebtorPravila.VIR] za tokove dodatka Webtor, sicer prazno. */
                   val vir: String = "",
                   /** Webtor rezerva: [WebtorPravila.Vrsta.name] iz predpone imena toka. */
                   val oznakaVira: String = "",
                   /** Webtor rezerva: sledilniki toka (`sources` »tracker:...«) za magnet, ce torrent dodamo TorBoxu v ozadju. */
                   val sledilniki: List<String> = emptyList(),
                   /** `behaviorHints.filename` (torrent in Webtor): ali je datoteka [datoteka] video ([WebtorPravila.datoteka]). */
                   val imeDatoteke: String = "")

    private val manifesti = ConcurrentHashMap<String, Manifest>()

    // ------------------------------------------------------------------ diagnostika na zahtevo razvijalca

    private const val DNEVNIK = "SafeerOsKatalogi"
    /** Vklop: `adb shell setprop log.tag.SafeerOsKatalogi DEBUG`. Brez tega se ne zapise nic. */
    internal fun dnevnik(): Boolean = try { android.util.Log.isLoggable(DNEVNIK, android.util.Log.DEBUG) } catch (_: Throwable) { false }
    private fun oznakaDodatka(osnova: String) = "d-" + Integer.toHexString(osnova.hashCode())
    private val zabelezeniKatalogi = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Katalogi dodatkov po razredih (tip, id, ime, moznosti zvrsti) - brez naslovov; zasebni dodatki se ne zapisejo. */
    fun dnevnikKatalogov(naslovi: List<String>) {
        if (!dnevnik()) return
        for (n in naslovi) {
            val m = manifest(n) ?: continue
            if (m.zaseben || !zabelezeniKatalogi.add(m.osnova)) continue
            val o = oznakaDodatka(m.osnova)
            android.util.Log.d(DNEVNIK, "$o ime=${m.ime} viri=${m.viri} tipi=${m.tipi} predpone=${m.predpone.take(8)} katalogov=${m.katalogi.size}")
            for (k in m.katalogi) android.util.Log.d(DNEVNIK, "$o ${razred(k.tip)} tip=${k.tip} id=${k.id} ime=${k.ime} prikazen=${k.prikazen} " +
                "obvezni=${k.obvezni} iskanje=${k.iskanje} zvrsti(${k.zvrsti.size})=${k.zvrsti.take(60)}")
        }
    }

    /** Oblika toka za dnevnik: katera polja ima, shema in koncnica naslova, kljuci namigov - brez gostitelja in imen. */
    private fun oblikaToka(s: JSONObject): String {
        val polja = listOf("url", "ytId", "externalUrl", "infoHash", "playerFrameUrl", "nzbUrl").filter { s.optString(it).isNotBlank() }
        val u = s.optString("url")
        val oblika = if (u.isBlank()) "" else try { val x = URL(u); x.protocol + " ." + x.path.substringAfterLast('/').substringAfterLast('.', "").take(6) } catch (_: Exception) { "?" }
        val n = s.optJSONObject("behaviorHints")
        val namigi = ArrayList<String>().apply { n?.keys()?.forEach { add(it) } }
        val glave = ArrayList<String>().apply { n?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.keys()?.forEach { add(it) } }
        return "polja=$polja oblika=$oblika namigi=$namigi glave=$glave"
    }

    // Kam sodi vsebina dodatka (lastnik, 2. 10. 2026): uporabnik doda dodatek, Safeer ga sam razvrsti v pravi razdelek.
    const val FILM = "film"
    const val SERIJA = "serija"
    /** TV v zivo: kanali, prenosi dogodkov in sporta. */
    const val TV = "tv"
    /** Glasba in drug zvok (albumi, skladbe, seznami, podkasti, zvocne knjige). */
    const val GLASBA = "glasba"
    const val RADIO = "radio"
    /** Drug video (kanali z videi, anime, "other"): razdelek Video. */
    const val VIDEO = "video"

    /**
     * Razred kataloga: po tipu, razen kadar ime dodatka ali kataloga pove, da je vsebina glasba ([OsPravila.glasbenoIme]) -
     * glasbeni dodatek, ki se oglasa kot filmi ali drug video, sodi pod Glasbo (lastnik, 5. 10. 2026). Kanali v zivo,
     * radio in glasba ostanejo, kjer so.
     */
    fun razredKataloga(k: Katalog): String {
        val r = razred(k.tip)
        return if ((r == FILM || r == SERIJA || r == VIDEO) && (OsPravila.glasbenoIme(k.imeDodatka) || OsPravila.glasbenoIme(k.ime))) GLASBA else r
    }

    /** Razred vsebine iz tipa dodatka (`type` kataloga ali vnosa). Neznan tip je video - nic se ne izgubi. */
    fun razred(tip: String): String = when (tip.trim().lowercase()) {
        "movie" -> FILM
        "series" -> SERIJA
        "tv", "live", "livetv", "iptv", "events", "event", "sports", "sport" -> TV
        "radio", "radios" -> RADIO
        "music", "audio", "album", "albums", "song", "songs", "track", "tracks", "playlist", "playlists",
        "podcast", "podcasts", "audiobook", "audiobooks" -> GLASBA
        else -> VIDEO
    }

    private val RX_NAGLASI = Regex("\\p{M}+")
    private fun poenostavi(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(RX_NAGLASI, "")
    /** Ali ime vsebuje vse iskane besede (brez razlikovanja velikih crk in naglasov: "pop tv" najde "POP TV HD"). */
    fun ujema(ime: String, beseda: String): Boolean {
        val i = poenostavi(ime)
        val besede = poenostavi(beseda).split(Regex("\\s+")).filter { it.isNotBlank() }
        return besede.isNotEmpty() && besede.all { it in i }
    }

    private val bazen = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "safeer-stremio").apply { isDaemon = true } }

    fun osnova(naslov: String): String {
        var n = naslov.trim()
        if (n.startsWith("stremio://")) n = "https://" + n.removePrefix("stremio://")
        if (n.endsWith("/manifest.json")) n = n.removeSuffix("/manifest.json")
        return n.trimEnd('/')
    }

    /**
     * Predpomnilnik odgovorov dodatkov (v pomnilniku): uporabnik ne caka dvakrat na isto (lastnik, 2. 10. 2026).
     * Katalog 15 min (police razdelka Video in mreza Filmi | Serije berejo iste strani), metapodatki 1 uro,
     * tokovi 3 min (ponoven dotik istega filma, vnaprej nalozeni tokovi ob izbiri kartice z daljincem).
     */
    private val odgovori = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    /** Naloga 73: veljavnost po vrsti zahtevka ([OsPravila.politikaZahteve]): katalog 30 min, tok 3 min, meta in podnapisi 1 h. */
    private fun veljavnost(url: String): Long = OsPravila.politikaZahteve(url, false).veljaMs

    /** Naloga 73: zahtevek tece za preverjanje v ozadju (nizja prednost, krajsi rok - [OsPravila.politikaZahteve]). */
    private val zahtevekVOzadju = ThreadLocal<Boolean>()

    /** Klic [delo] kot zahtevek v ozadju (glej [zahtevekVOzadju]). */
    private inline fun <T> vOzadju(delo: () -> T): T {
        val prej = zahtevekVOzadju.get()
        zahtevekVOzadju.set(true)
        try { return delo() } finally { zahtevekVOzadju.set(prej) }
    }

    /** Naloga 73: zahtevek tece s prednostjo niti svoje vrste (tok ob kliku pred katalogi v ozadju); prejsnja se povrne. */
    private inline fun <T> sPrednostjo(prednost: Int, delo: () -> T): T {
        val prej = try { android.os.Process.getThreadPriority(android.os.Process.myTid()) } catch (_: Throwable) { null }
        val menjam = prej != null && prej != prednost
        if (menjam) try { android.os.Process.setThreadPriority(prednost) } catch (_: Throwable) { }
        try { return delo() } finally { if (menjam) try { android.os.Process.setThreadPriority(prej!!) } catch (_: Throwable) { } }
    }

    /**
     * Naloga 73 (»circuit breaker« po dodatku): ali smemo dodatek zdaj vprasati v ozadju. Po [OsPravila.IZPADOV_ZA_PRESKOK]
     * izpadih je izklopljen [OsPravila.PRESKOK_MS]; potem gre en poskusni zahtevek (polodprto), ostali cakajo na njegov izid.
     * Predvajanje (tok ob kliku) gre mimo, kot doslej.
     */
    private fun smemVprasati(osnova: String): Boolean {
        var smem = false
        val zdaj = System.currentTimeMillis()
        odzivi.compute(osnova) { _, prej ->
            val novo = OsPravila.dovoliZahtevo(prej, zdaj)
            smem = novo != null
            (novo ?: prej)?.takeIf { it != OsPravila.Odziv() }
        }
        return smem
    }

    private fun json(url: String): JSONObject? {
        val velja = veljavnost(url)
        if (velja > 0) odgovori[url]?.let { (cas, o) -> if (System.currentTimeMillis() - cas < velja) return o }
        val o = prenesiJson(url) ?: return null
        // Praznega seznama tokov ne hranimo: dodatek je morda le zacasno brez odgovora.
        val prazniTokovi = "/stream/" in url && (o.optJSONArray("streams")?.length() ?: 0) == 0
        if (velja > 0 && !prazniTokovi) {
            if (odgovori.size > 300) odgovori.clear()
            odgovori[url] = System.currentTimeMillis() to o
        }
        return o
    }

    /** Koda HTTP zadnjega prenosa v tej niti (0 = dodatek ni odgovoril): locimo "tega nimam" (404) od izpada. */
    private val zadnjaKoda = ThreadLocal<Int>()

    /** Glave zadnjega odgovora z omejitvijo (429, 403) v tej niti, imena z malimi crkami: dodatek z njimi pove, koliko naj pocakamo. */
    private val zadnjeGlave = ThreadLocal<Map<String, String>>()

    /** Dodatek je odgovoril, da vsebine nima (ne: napaka streznika, prijave ali omejitve - to je izpad). */
    internal fun kodaPomeniNima(koda: Int) = koda in 400..499 && koda !in setOf(401, 403, 407, 408, 425, 429)

    /**
     * Odzivnost dodatkov (osnova -> [OsPravila.Odziv], naloga 57d): dodatek, ki zaporedoma ne odgovori, v ozadju
     * (katalogi, preverjanje) preskocimo do naslednjega preizkusa - brez porabe proracuna. Predvajanje ga vprasa vedno.
     */
    private val odzivi = ConcurrentHashMap<String, OsPravila.Odziv>()

    private fun osnovaIzNaslova(url: String): String? =
        listOf("/catalog/", "/stream/", "/meta/", "/safeer/", "/manifest.json").firstNotNullOfOrNull { k -> url.indexOf(k).takeIf { it > 0 }?.let { url.substring(0, it) } }

    private fun zabeleziOdziv(url: String, koda: Int) {
        val o = osnovaIzNaslova(url) ?: return
        val uspeh = koda in 200..499 && koda != 408 && koda != 425
        odzivi.compute(o) { _, prej -> OsPravila.poOdzivu(prej, uspeh, System.currentTimeMillis(), koda).takeIf { it != OsPravila.Odziv() } }
    }

    // ------------------------------------------------------------------ naloga 124: razsiritve Safeer (protokol v1)

    /**
     * Glava `X-Safeer-Config` za zahtevo na [url]: vrednosti nastavitev dodatka, ki jih je opisal sam. Skrivnosti samo po
     * https ([DodatkiProtokol.smePoslati]); po http gredo le navadne nastavitve. Brez nastavitev ali konteksta: null.
     */
    private fun glavaNastavitev(url: String): String? {
        val o = osnovaIzNaslova(url) ?: return null
        val opis = manifesti[o]?.razsiritev?.nastavitve?.takeIf { it.isNotEmpty() } ?: return null
        val c = aplikacija ?: return null
        val dovoljene = DodatkiProtokol.nastavitveZaZahtevo(opis, url)
        if (dovoljene.isEmpty()) return null
        return DodatkiProtokol.glavaNastavitev(dovoljene, DodatkiNastavitve.vrednosti(c, o, dovoljene))
    }

    /**
     * Naloga 127: razsiritve dodatka se na tej napravi sploh dajo uporabiti (https, ali http na krajevni naslov v razvojnem
     * nacinu - [DodatkiProtokol.smePoslati]). Sicer kartic map, gumbov dejanj in »O vsebini« ne kazemo.
     */
    fun razsiritveUporabne(naslov: String): Boolean = DodatkiProtokol.smePoslati(osnova(naslov) + "/", DodatkiRazvoj.vklopljen)

    /** Razsiritev brez omrezja (samo, kar je ze v pomnilniku) - za risanje na glavni niti. Novejsi protokol: null. */
    fun razsiritevZdaj(naslov: String): DodatkiProtokol.Razsiritev? = manifesti[osnova(naslov)]?.razsiritev?.takeIf { !it.novejsi && it.protokol > 0 }

    /** Dodatek zahteva novejsi protokol (posodobi Safeer) - samo iz pomnilnika. */
    fun zahtevaNovejsiSafeer(naslov: String): Boolean = manifesti[osnova(naslov)]?.razsiritev?.novejsi == true

    /** Dejanja dodatka, iz katerega je [s], za njen tip (samo iz pomnilnika). */
    fun dejanjaZa(s: Jamendo.Skladba): List<DodatkiProtokol.Dejanje> {
        val (osnova, tip, _) = razstavi(s) ?: return emptyList()
        if (!razsiritveUporabne(osnova)) return emptyList()
        val r = razsiritevZdaj(osnova) ?: return emptyList()
        return DodatkiProtokol.dejanjaZa(r, tip)
    }

    /** Izvede dejanje [d] za [s] (delovna nit). null = dodatek ni odgovoril ali odgovor ni veljaven. */
    fun izvediDejanje(s: Jamendo.Skladba, d: DodatkiProtokol.Dejanje): DodatkiProtokol.OdgovorDejanja? {
        val (osnova, tip, id) = razstavi(s) ?: return null
        val url = DodatkiProtokol.naslovDejanja(osnova, d.id, tip, id)
        if (!DodatkiProtokol.smePoslati(url, DodatkiRazvoj.vklopljen)) return null
        val o = prenesiJson(url) ?: return null
        val odgovor = DodatkiProtokol.preberiDejanje(o.toString())
        // Dodatek je nekaj spremenil: stari odgovori katalogov tega dodatka ne veljajo vec.
        if (odgovor?.osvezi == true) pozabiOdgovore(osnova)
        return odgovor
    }

    /** Ali dodatek vsebine [s] ponuja dopolnitev v ozadju (enrich) za njen tip. */
    fun ponujaDopolnitev(s: Jamendo.Skladba): Boolean {
        val (osnova, tip, _) = razstavi(s) ?: return false
        if (!razsiritveUporabne(osnova)) return false
        return tip.lowercase() in (razsiritevZdaj(osnova)?.dopolni ?: return false)
    }

    /** Dopolnitev v ozadju (enrich, delovna nit): objekt meta, ki ga dodatek doda k [s]; null = ni ali ni odgovoril. */
    fun dopolni(s: Jamendo.Skladba): JSONObject? {
        if (!ponujaDopolnitev(s)) return null
        val (osnova, tip, id) = razstavi(s) ?: return null
        val url = DodatkiProtokol.naslovDopolni(osnova, tip, id)
        if (!DodatkiProtokol.smePoslati(url, DodatkiRazvoj.vklopljen)) return null
        return prenesiJson(url)?.optJSONObject("meta")
    }

    /** Vsebina mape dodatka (delovna nit): elementi in za vsak `meta` kartica tega dodatka (predvajanje kot vedno). */
    fun mapa(naslov: String, mapa: String, pot: String): Pair<DodatkiProtokol.VsebinaMape, List<Jamendo.Skladba?>>? {
        val osnova = osnova(naslov)
        val url = DodatkiProtokol.naslovMape(osnova, mapa, pot)
        if (!DodatkiProtokol.smePoslati(url, DodatkiRazvoj.vklopljen)) return null
        val o = prenesiJson(url) ?: return null
        val v = DodatkiProtokol.preberiMapo(o.toString()) ?: return null
        val kartice = v.elementi.map { e ->
            if (e.vrsta != "meta") null
            else vnos(JSONObject().put("id", e.id).put("type", e.tip).put("name", e.ime).put("poster", e.plakat), osnova)
        }
        return v to kartice
    }

    /**
     * Naloga 125 (razvojni nacin): »Znova nalozi dodatke« - pozabi manifeste (pomnilnik in disk), predpomnjene odgovore in
     * zaklepe vseh [naslovi]; naslednje branje vzame vse sveze z dodatka.
     */
    fun znovaNalozi(naslovi: List<String>) {
        manifesti.clear()
        odgovori.clear()
        try { mapaPredpomnilnika?.listFiles { f -> f.name.startsWith("manifest-") && f.name.endsWith(".json") }?.forEach { it.delete() } } catch (_: Exception) { }
        naslovi.forEach { pozabiZaklep(it) }
    }

    /** Naloga 126: ali so manifesti vseh [naslovi] ze v pomnilniku (brez omrezja). */
    fun manifestiVPomnilniku(naslovi: List<String>): Boolean = naslovi.all { manifesti.containsKey(osnova(it)) }

    /** Naloga 126: nalozi manifeste [naslovi] (disk ali splet; delovna nit) - za kartice map in nastavitev v Mojih virih. */
    fun naloziManifeste(naslovi: List<String>) { for (n in naslovi) try { manifest(n) } catch (_: Exception) { } }

    /** Pozabi predpomnjene odgovore dodatka (nove nastavitve, dejanje z `refresh`). */
    fun pozabiOdgovore(naslov: String) {
        val o = osnova(naslov)
        odgovori.keys.removeAll { it.startsWith("$o/") }
    }

    /** Seznam sprememb za prikaz enkrat po posodobitvi dodatka ([DodatkiProtokol.spremembeZaPrikaz]); zapomni si verzijo. */
    fun spremembeZaPrikaz(c: Context, naslov: String): DodatkiProtokol.Besedilo? {
        val m = manifesti[osnova(naslov)] ?: return null
        val r = m.razsiritev?.takeIf { !it.novejsi } ?: return null
        if (m.verzija.isBlank()) return null
        val prejsnja = DodatkiNastavitve.videnaVerzija(c, m.osnova)
        if (prejsnja == m.verzija) return null
        DodatkiNastavitve.zapomniVerzijo(c, m.osnova, m.verzija)
        return DodatkiProtokol.spremembeZaPrikaz(r, prejsnja, m.verzija)
    }

    /** Naloga 113: stanje odzivnosti dodatka (od kdaj, koda) za oznako v Mojih virih; null = odgovarja. */
    fun odziv(naslov: String): OsPravila.Odziv? = odzivi[osnova(naslov)]

    /** Dodatek zaporedoma ne odgovarja (3 izpadi v 10 min): v ozadju ga preskocimo 30 min, v Mojih virih je oznaka. */
    /** Naloga 88: koda HTTP zadnjega zahtevka te niti (0 = brez odgovora ali varovalka), za dnevnik izida kataloga. */
    fun zadnjaKodaNiti(): Int = zadnjaKoda.get() ?: 0

    fun neOdgovarja(naslov: String): Boolean = OsPravila.neOdgovarja(odzivi[osnova(naslov)], System.currentTimeMillis())

    /**
     * Preverba dodatka ob vnosu (naloga 57d): manifest mora odgovoriti z 200 in veljavnim JSON (id ali resources) v
     * ~10 s. Spec streznik se zbuja: ob izpadu (0, 5xx) se en poskus cez 5 s. Vrne 200 ali kodo napake (0 = ni odgovora).
     * Klic iz ozadja.
     */
    fun preveriObVnosu(naslov: String): Int {
        val url = osnova(naslov) + "/manifest.json"
        fun enkrat(): Int {
            val j = prenesiJson(url, 10_000)
            val koda = zadnjaKoda.get() ?: 0
            return if (j != null && (j.has("id") || j.has("resources"))) 200 else if (koda in 200..299) 0 else koda
        }
        val prva = enkrat()
        if (prva == 200 || !OsPravila.vnosZnova(prva)) return prva
        try { Thread.sleep(5_000) } catch (_: InterruptedException) { return prva }
        return enkrat()
    }

    private fun prenesiJson(url: String, rok: Int = 0): JSONObject? {
        zadnjaKoda.set(0)
        zadnjeGlave.set(emptyMap())
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        // Pregled S5: nadomestek, katerega skrivnosti ta naprava ne pozna (Keystore) - nobene zahteve na nadomestni naslov.
        if (!SkrivniDodatki.pozna(aplikacija, url)) return null
        // Naloga 73: rok in prednost po vrsti zahtevka (katalog / meta / tok / podnapisi / manifest).
        val politika = OsPravila.politikaZahteve(url, zahtevekVOzadju.get() == true)
        val cas = if (rok > 0) rok else politika.rokMs
        return sPrednostjo(politika.prednost) { prenesiJsonZdaj(url, cas) }
    }

    private fun prenesiJsonZdaj(url: String, cas: Int): JSONObject? {
        // Vsaka poizvedba po tokovih steje v urni proracun dodatka - tudi tista za predvajanje ([TempoDodatka]).
        if ("/stream/" in url) stej(url.substringBefore("/stream/"))
        // Naloga 72: tudi poizvedbe po podnapisih stejejo v urni proracun dodatka (TempoDodatka).
        if ("/subtitles/" in url) stej(url.substringBefore("/subtitles/"))
        val zacetek = android.os.SystemClock.elapsedRealtime()
        return try {
            // Webtor rezerva: nadomestek dodatka s poverilnico -> pravi naslov samo za ta klic (nikoli v dnevnik).
            val c = URL(SkrivniDodatki.dejanski(aplikacija, url)).openConnection() as HttpURLConnection
            c.connectTimeout = cas; c.readTimeout = cas
            c.setRequestProperty("User-Agent", "Safeer-Predvajalnik/1.0 (+https://safeer.si)")
            c.setRequestProperty("Accept", "application/json")
            // Naloga 124: jezik uporabnika in nastavitve dodatka (glava, nikoli v naslovu - docs/DODATKI-PROTOKOL.md 4).
            c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
            glavaNastavitev(url)?.let { c.setRequestProperty("X-Safeer-Config", it) }
            try {
                val koda = c.responseCode
                zadnjaKoda.set(koda)
                zabeleziOdziv(url, koda)
                if (koda == 429 || koda == 403) zadnjeGlave.set(try {
                    c.headerFields.mapNotNull { (k, v) -> if (k == null) null else v?.firstOrNull()?.let { k.lowercase() to it } }.toMap()
                } catch (_: Exception) { emptyMap() })
                if (koda !in 200..299) { DodatkiRazvoj.zapisi(url, koda, 0, android.os.SystemClock.elapsedRealtime() - zacetek); return null }
                // Pregled D7: odgovor dodatka najvec NAJVEC_ODGOVORA_DODATKA bajtov - vecjega zavrnemo, preden ga beremo v
                // pomnilnik in razclenimo (prej readBytes() brez meje: zlonameren dodatek bi napolnil pomnilnik).
                if (OsPravila.odgovorPrevelik(c.contentLengthLong)) { prevelik(url); return null }
                val bajti = c.inputStream.use { beriOmejeno(it, OsPravila.NAJVEC_ODGOVORA_DODATKA) } ?: run { prevelik(url); return null }
                val besedilo = String(bajti, Charsets.UTF_8)
                DodatkiRazvoj.zapisi(url, koda, besedilo.length, android.os.SystemClock.elapsedRealtime() - zacetek)
                JSONObject(besedilo)
            } finally { c.disconnect() }
        } catch (_: Exception) { if ((zadnjaKoda.get() ?: 0) == 0) zabeleziOdziv(url, 0); null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Pregled D7: najvec [meja] bajtov; null, ce jih je vec (beremo najvec meja + 1, da to vemo). */
    private fun beriOmejeno(vhod: java.io.InputStream, meja: Int): ByteArray? {
        val izhod = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (izhod.size() <= meja) {
            val n = vhod.read(buf, 0, minOf(buf.size, meja + 1 - izhod.size()))
            if (n < 0) break
            izhod.write(buf, 0, n)
        }
        return if (izhod.size() > meja) null else izhod.toByteArray()
    }

    /** Pregled D7: tok, ki po [meja] bajtih vrze napako - za tokovno branje ([prenesiTokovno]) brez celega odgovora v pomnilniku. */
    private class OmejenVhod(vhod: java.io.InputStream, private val meja: Long) : java.io.FilterInputStream(vhod) {
        private var prebrano = 0L
        private fun stej(n: Long) { if (n > 0) { prebrano += n; if (prebrano > meja) throw java.io.IOException("odgovor dodatka prevelik") } }
        override fun read(): Int { val b = super.read(); if (b >= 0) stej(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); stej(n.toLong()); return n }
        override fun skip(n: Long): Long { val k = super.skip(n); stej(k); return k }
    }

    private fun prevelik(url: String) {
        try { android.util.Log.w("SafeerStremio", "odgovor dodatka ${try { URL(url).host } catch (_: Exception) { "?" }} prevelik - zavrnjen") } catch (_: Throwable) { }
    }

    /** Mapa za predpomnilnik manifestov na disku (nastavi jo aplikacija ob zagonu); brez nje le pomnilnik. */
    @Volatile var mapaPredpomnilnika: java.io.File? = null
    /** Naloga 87: kontekst aplikacije za strani kanalov na USB ([MedijskiPredpomnilnik.stranKanalov]); nastavi [pripravi]. */
    @Volatile private var aplikacija: Context? = null

    /**
     * Manifest dodatka: z diska (do 24 ur), sicer s spleta. Po hladnem zagonu so katalogi in tokovi tako na voljo
     * brez cakanja na manifest vsakega dodatka posebej; nedosegljiv dodatek uporabi zadnji znani manifest.
     */
    private fun manifestJson(osnova: String): JSONObject? {
        val datoteka = mapaPredpomnilnika?.let { java.io.File(it, "manifest-" + Integer.toHexString(osnova.hashCode()) + ".json") }
        val zDiska = try { datoteka?.takeIf { it.isFile }?.let { JSONObject(it.readText()) } } catch (_: Exception) { null }
        // Naloga 73: manifest z diska je dober takoj (zagon in odprtje TV v zivo ne cakata na omrezje); star osvezimo v ozadju.
        when (OsPravila.manifestZDiska(if (zDiska == null) -1L else System.currentTimeMillis() - (datoteka?.lastModified() ?: 0L))) {
            "uporabi" -> return zDiska
            "uporabi+osvezi" -> { osveziManifest(osnova, datoteka); return zDiska }
        }
        val svez = prenesiJson("$osnova/manifest.json")
        if (svez != null) try { datoteka?.parentFile?.mkdirs(); datoteka?.writeText(svez.toString()) } catch (_: Exception) { }
        return svez ?: zDiska
    }

    /** Manifesti, ki se ta hip osvezujejo v ozadju (vsak najvec enkrat hkrati). */
    private val osvezujem = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Naloga 73: svez manifest v ozadju; nov se uporabi ob naslednjem branju ([manifesti] ga pozabi). */
    private fun osveziManifest(osnova: String, datoteka: java.io.File?) {
        if (!osvezujem.add(osnova)) return
        try { bazen.execute {
            try {
                val svez = prenesiJson("$osnova/manifest.json") ?: return@execute
                try { datoteka?.parentFile?.mkdirs(); datoteka?.writeText(svez.toString()) } catch (_: Exception) { }
                manifesti.remove(osnova)
            } finally { osvezujem.remove(osnova) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { osvezujem.remove(osnova) }
    }

    fun manifest(naslov: String): Manifest? {
        val o = osnova(naslov)
        manifesti[o]?.let { return it }
        val m = manifestJson(o) ?: return null
        val ime = m.optString("name").ifBlank { o }
        val viri = mutableSetOf<String>()
        m.optJSONArray("resources")?.let { a -> for (i in 0 until a.length()) {
            val r = a.opt(i); viri += if (r is JSONObject) r.optString("name") else r.toString() } }
        val tipi = mutableSetOf<String>()
        m.optJSONArray("types")?.let { a -> for (i in 0 until a.length()) tipi += a.optString(i) }
        val predpone = mutableListOf<String>()
        m.optJSONArray("idPrefixes")?.let { a -> for (i in 0 until a.length()) predpone += a.optString(i) }
        val katalogi = mutableListOf<Katalog>()
        m.optJSONArray("catalogs")?.let { a -> for (i in 0 until a.length()) {
            val k = a.optJSONObject(i) ?: continue
            val tip = k.optString("type"); val id = k.optString("id")
            if (tip.isBlank() || id.isBlank()) continue
            val podprti = mutableSetOf<String>(); val obvezni = mutableListOf<String>()
            val privzeti = mutableListOf<Pair<String, String>>()
            val zvrsti = mutableListOf<String>()
            k.optJSONArray("extra")?.let { e -> for (j in 0 until e.length()) {
                val x = e.optJSONObject(j) ?: continue
                val imeX = x.optString("name")
                podprti += imeX
                if (imeX == "genre") x.optJSONArray("options")?.let { o -> for (q in 0 until o.length()) o.optString(q).takeIf { it.isNotBlank() }?.let { zvrsti += it } }
                if (x.optBoolean("isRequired")) {
                    obvezni += imeX
                    // Stremio v Discover obvezni parameter nastavi na prvo moznost (npr. zvrst): enako tu.
                    x.optJSONArray("options")?.optString(0)?.takeIf { it.isNotBlank() }?.let { privzeti += imeX to it }
                } } }
            k.optJSONArray("extraSupported")?.let { e -> for (j in 0 until e.length()) podprti += e.optString(j) }
            k.optJSONArray("extraRequired")?.let { e -> for (j in 0 until e.length()) obvezni += e.optString(j) }
            katalogi += Katalog(o, ime, tip, id, k.optString("name").ifBlank { id }, "search" in podprti, obvezni.distinct(), privzeti, zvrsti, "skip" in podprti)
        } }
        // Zaseben dodatek ([ZasebniDodatki]): tako se oznaci sam, v manifestu (uradno polje protokola).
        val zaseben = m.optJSONObject("behaviorHints")?.optBoolean("adult") == true
        zapomniZasebnost(o, zaseben)
        zapomniIme(o, ime)
        // Naloga 124: razsiritve Safeer; pokvarjena razsiritev nikoli ne ustavi dela Stremio.
        val razsiritev = try { DodatkiProtokol.preberi(m.toString()) } catch (_: Exception) { null }
        if (razsiritev != null && razsiritev.opozorila.isNotEmpty()) try {
            android.util.Log.i("SafeerDodatki", "${gostitelj(o)}: ${razsiritev.opozorila.size} opozoril (${razsiritev.opozorila.first().take(80)})")
        } catch (_: Throwable) { }
        return Manifest(o, ime, viri, tipi, predpone, katalogi, zaseben, m.optString("id").trim(), razsiritev, m.optString("version").trim())
            .also { manifesti[o] = it }
    }

    // ------------------------------------------------------------------ zasebni dodatki (ZasebniDodatki)

    private const val PREFS_ZASEBNI = "safeer_stremio_zasebni"
    /** Shrambi oznak in premorov (ne Context: predmet zivi ves cas procesa). null = [pripravi] se ni bil klican. */
    @Volatile private var shrambaZasebnih: android.content.SharedPreferences? = null
    @Volatile private var shrambaPremorov: android.content.SharedPreferences? = null
    @Volatile private var shrambaZaklepov: android.content.SharedPreferences? = null
    private val zaklepi = ConcurrentHashMap<String, OsPravila.Zaklep>()
    /** Naloga 79b: osnova -> povezava za odklep (shema, gostitelj, pot). V isti shrambi s predpono [PREDPONA_ODKLEPA]. */
    private val odklepi = ConcurrentHashMap<String, String>()
    private const val PREDPONA_ODKLEPA = "odklep|"
    /** Naloga 120: vsebina (tip|id), ki jo dodatki dajejo samo za placilo - cas zapisa (ms). */
    private const val PREDPONA_PLACLJIVO = "placljivo|"
    private val placljivo = ConcurrentHashMap<String, Long>()
    /** osnova -> ali je zaseben dodatek. Zapis prezivi ponovni zagon, da odlocitev ne caka na omrezje. */
    private val znaniZasebni = ConcurrentHashMap<String, Boolean>()

    /** Vstopne tocke s Contextom (zaslon, storitev, Link) pripravijo predpomnilnik manifestov in znane oznake. */
    fun pripravi(ctx: Context) {
        if (shrambaZasebnih != null) return
        synchronized(znaniZasebni) {
            if (shrambaZasebnih != null) return
            val a = ctx.applicationContext
            // Naloga 125: razvojni nacin dodatkov (privzeto izklopljen).
            try { DodatkiRazvoj.nalozi(a) } catch (_: Exception) { }
            // Pregled S1: dodatki s poverilnico iz prejsnje razlicice dobijo nadomestek, PREDEN se tu preberejo nastavitve
            // (zasebni, premori, zaklepi, tempo) - sicer bi jih pomnilnik zapisal nazaj s pravim naslovom.
            try { MedijskiViri.preseliSkrivne(a) } catch (_: Throwable) { }
            if (mapaPredpomnilnika == null) mapaPredpomnilnika = java.io.File(a.cacheDir, "stremio")
            aplikacija = a
            // Webtor rezerva: skrivni deli naslovov dodatkov (nadomestek -> pravi naslov tik pred klicem).
            try { SkrivniDodatki.nalozi(a) } catch (_: Throwable) { }
            // Kaj vemo o lastnih knjiznicah dodatkov (katere dokazano dajo tokove) - pred prvim risanjem.
            ZaupanjeKnjiznic.pripravi(a)
            try {
                val p = a.getSharedPreferences(PREFS_ZASEBNI, Context.MODE_PRIVATE)
                p.all.forEach { (k, v) -> if (v is Boolean) znaniZasebni.putIfAbsent(k, v) else if (v is String && k.startsWith(KLJUC_IMENA)) znanaImena.putIfAbsent(k.removePrefix(KLJUC_IMENA), v) }
                // Kar smo spoznali pred pripravo (manifest brez Contexta), zapisemo zdaj.
                val e = p.edit(); znaniZasebni.forEach { (k, v) -> e.putBoolean(k, v) }; e.apply()
            } catch (_: Exception) { }
            // Premori dodatkov iz prejsnjega zagona (ponovni zagon ne sme znova sproziti omejitve).
            try {
                val zdaj = System.currentTimeMillis()
                val p = a.getSharedPreferences(PREFS_PREMOR, Context.MODE_PRIVATE)
                val e = p.edit()
                p.all.forEach { (k, v) -> if (v is Long && v > zdaj && v < zdaj + PREMOR_NAJVEC_MS) premorDo.putIfAbsent(k, v) else e.remove(k) }
                e.apply()
                shrambaPremorov = p
            } catch (_: Exception) { }
            // Naloga 79: kateri dodatki kanalov dajejo tokove samo z narocnino - po zagonu njihovih kanalov ne pokazemo vnaprej.
            try {
                val p = a.getSharedPreferences(PREFS_ZAKLEPI, Context.MODE_PRIVATE)
                p.all.forEach { (k, v) ->
                    if (k.startsWith(PREDPONA_ODKLEPA)) OsPravila.povezavaOdklepa(v as? String)?.let { odklepi.putIfAbsent(k.removePrefix(PREDPONA_ODKLEPA), it) }
                    else if (k.startsWith(PREDPONA_PLACLJIVO)) (v as? Long)?.let { placljivo.putIfAbsent(k.removePrefix(PREDPONA_PLACLJIVO), it) }
                    else OsPravila.Zaklep.izNiza(v as? String)?.let { zaklepi.putIfAbsent(k, it) }
                }
                shrambaZaklepov = p
            } catch (_: Exception) { }
            // Poizvedbe zadnje ure in kar smo se o meji dodatkov naucili ([TempoDodatka]): ponovni zagon jih ne pozabi.
            try {
                val zdaj = System.currentTimeMillis()
                val p = a.getSharedPreferences(PREFS_TEMPO, Context.MODE_PRIVATE)
                val e = p.edit()
                p.all.forEach { (k, v) ->
                    if (k == KLJUC_OMEJITVE) {
                        // Zadnja omejitev (tu ali drugje v domu): po ponovnem zagonu naprava se vedno ve, da mora z zalogo pocakati.
                        val ob = (v as? String)?.toLongOrNull() ?: 0L
                        if (ob in zdaj - 24 * 3_600_000L..zdaj) { if (ob > premorOb) premorOb = ob } else e.remove(k)
                        return@forEach
                    }
                    val stanje = TempoDodatka.Stanje.izNiza((v as? String)?.substringBefore('|'))
                    val ura = TempoDodatka.Ura.izNiza((v as? String)?.substringAfter('|', ""))
                    // Dodatek, o katerem ni vec kaj vedeti (odstranjen ali dolgo nerabljen), iz shrambe izgine.
                    if (stanje == TempoDodatka.Stanje() && ura.vsota(zdaj) == 0) e.remove(k)
                    else { tempo.putIfAbsent(k, stanje); ure.putIfAbsent(k, ura) }
                }
                e.apply()
                shrambaTempa = p
            } catch (_: Exception) { }
            shrambaZasebnih = try { a.getSharedPreferences(PREFS_ZASEBNI, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        }
    }

    private fun zapomniZasebnost(osnova: String, da: Boolean) {
        if (znaniZasebni.put(osnova, da) == da) return
        try { shrambaZasebnih?.edit()?.putBoolean(osnova, da)?.apply() } catch (_: Exception) { }
    }

    /** osnova -> ime dodatka iz manifesta. Zapis prezivi ponovni zagon: kartica dodatka ne kaze naslova streznika, dokler se manifest ne nalozi znova. */
    private val znanaImena = ConcurrentHashMap<String, String>()
    private const val KLJUC_IMENA = "ime:"

    private fun zapomniIme(osnova: String, ime: String) {
        if (ime.isBlank() || ime.length > 120 || znanaImena.put(osnova, ime) == ime) return
        try { shrambaZasebnih?.edit()?.putString(KLJUC_IMENA + osnova, ime)?.apply() } catch (_: Exception) { }
    }

    /** Ali je zaseben dodatek, kolikor ze vemo (brez omrezja); null = njegovega manifesta se nismo videli. */
    fun zasebenZnano(naslov: String): Boolean? = znaniZasebni[osnova(naslov)]

    /** Kot [zasebenZnano], a neznan manifest prenese (klic z delovne niti); null = dodatek ni dosegljiv. */
    fun zaseben(naslov: String): Boolean? = zasebenZnano(naslov) ?: manifest(naslov)?.zaseben

    /** Kartica (film, epizoda, video) iz zasebnega dodatka - brez omrezja. */
    fun jeZasebna(s: Jamendo.Skladba): Boolean =
        (jeEnota(s) && razstavi(s)?.first?.let { znaniZasebni[it] } == true) || KnjiznicaKroga.jeZasebenPrenos(s.id)

    /**
     * Kartica iz lastne knjiznice dodatka po pravilu ([RazpolozljivostPravila.lastnaKnjiznica]) - brez omrezja; dokler
     * manifesta dodatka ne poznamo, velja ne (kartico preverimo kot vsako drugo). Kanali in postaje v zivo niso
     * knjiznica: te preverja mreza kanalov ([imaLastenTok], MrtviKanali).
     */
    fun izLastneKnjiznice(s: Jamendo.Skladba): Boolean {
        val (osnova, tip, id) = razstavi(s) ?: return false
        val m = manifesti[osnova] ?: return false
        val r = razred(tip)
        if (r == TV || r == RADIO) return false
        return RazpolozljivostPravila.lastnaKnjiznica(DomPreverjanjePravila.veljaven(Razpolozljivost.kljuc(tip, id)), m.viri, m.tipi, m.predpone, tip, id)
    }

    /** Ali knjiznica dodatka te kartice dokazano daje tokove ([ZaupanjeKnjiznic.stanje]): true / false / null = se ne vemo. */
    fun zaupanjeKnjiznice(s: Jamendo.Skladba): Boolean? = razstavi(s)?.first?.let { ZaupanjeKnjiznic.stanje(it) }

    /** Kartica iz lastne knjiznice, ki dokazano daje tokove: pokazemo jo brez poizvedbe za vsak naslov posebej. */
    fun lastnaKnjiznica(s: Jamendo.Skladba): Boolean = izLastneKnjiznice(s) && zaupanjeKnjiznice(s) == true

    /** Kartica iz lastne knjiznice, za katero vzorec se ni odgovoril: ne kazemo je, dokler ne vemo, da se da predvajati. */
    fun knjiznicaCaka(s: Jamendo.Skladba): Boolean = izLastneKnjiznice(s) && zaupanjeKnjiznice(s) == null

    /** Kartica iz lastne knjiznice dodatka, ki tokov zanjo dokazano ne daje: ne kazemo je. */
    fun knjiznicaNeDela(s: Jamendo.Skladba): Boolean = izLastneKnjiznice(s) && zaupanjeKnjiznice(s) == false

    /** Dotik kartice iz lastne knjiznice: dodatek je tok dal ([uspeh]) ali je odgovoril, da ga nima. */
    fun zabeleziKnjiznico(s: Jamendo.Skladba, uspeh: Boolean) {
        if (izLastneKnjiznice(s)) razstavi(s)?.first?.let { ZaupanjeKnjiznic.poDotiku(it, uspeh) }
    }

    /**
     * Vzorec lastne knjiznice dodatka: nekaj njenih naslovov ([RazpolozljivostPravila.vzorecKnjiznice]) vprasamo po
     * tokovih pri dodatku samem; prvi, ki se da predvajati, zadosca. Najvec tri poizvedbe na dodatek in na pol dneva,
     * zato brez cakanja na zeton; dodatka v premoru ne sprasujemo. Klic iz ozadja. true / false = dokaz, null = ne
     * vemo (dodatek ni odgovoril). [torrent]: kateri torrent steje na tej napravi ([torrentGre]).
     */
    fun vzorciKnjiznico(osnova: String, naslovi: List<Jamendo.Skladba>, torrent: Long): Boolean? = vOzadju {
        vzorciKnjiznicoZdaj(osnova, naslovi, torrent)
    }

    private fun vzorciKnjiznicoZdaj(osnova: String, naslovi: List<Jamendo.Skladba>, torrent: Long): Boolean? {
        val m = manifesti[osnova] ?: return null
        if ("stream" !in m.viri || vPremoru(osnova) || !smemVprasati(osnova)) return null
        val odgovori = ArrayList<Boolean?>()
        for (i in RazpolozljivostPravila.vzorecKnjiznice(naslovi.size)) {
            val je = try { tokVzorca(m, naslovi[i], torrent) } catch (_: Exception) { null }
            odgovori += je
            // Dokaz je tu ali pa dodatek ne odgovarja: naprej ne sprasujemo.
            if (je != false) break
        }
        val izid = RazpolozljivostPravila.izidVzorca(odgovori)
        try {
            android.util.Log.i("SafeerStremio", "knjiznica dodatka ${if (m.zaseben) "(zaseben)" else gostitelj(osnova)}: vzorec ${odgovori.size} od ${naslovi.size} naslovov -> " +
                when (izid) { true -> "daje tokove"; false -> "tokov ne daje, kartic ne kazemo"; null -> "brez odgovora" })
        } catch (_: Throwable) { }
        return izid
    }

    /** Ali ima naslov vzorca pri svojem dodatku tok, ki ga ta naprava predvaja; null = dodatek ni odgovoril. */
    private fun tokVzorca(m: Manifest, s: Jamendo.Skladba, torrent: Long): Boolean? {
        val (_, tip, id) = razstavi(s) ?: return null
        val r = razred(tip)
        // Posnetek na YouTubu je pri videospotu, glasbi in kanalu z videi vsebina sama, pri filmu ali seriji napovednik
        // (isto pravilo kot ob dotiku, GlasbaActivity.izberiTok).
        val posnetek = s.mediaType.equals("MusicVideo", ignoreCase = true) || (r != FILM && r != SERIJA)
        fun steje(t: Tok) = when (t.vrsta) { "url" -> true; "torrent" -> torrentGre(t, torrent); "napovednik" -> posnetek; else -> false }
        var idToka = id
        if (tip == "series" || r == GLASBA || r == VIDEO) {
            // Serija, album, kanal z videi: tok ima posnetek (epizoda), ne enota sama - kot pri predvajanju.
            val ep = epizodeZa(m.osnova, tip, id)
            val prva = (if (tip == "series") ep.firstOrNull { it.sezona >= 1 } else null) ?: ep.firstOrNull()
            if (prva != null) {
                if (prva.tokovi.any { steje(it) }) return true
                idToka = prva.id
            } else if (tip == "series") return null
        }
        poizvedbVOzadju.incrementAndGet()
        val d = json("${m.osnova}/stream/${enc(tip)}/${enc(idToka)}.json")
        if (d == null) {
            val koda = zadnjaKoda.get() ?: 0
            if (koda == 429 || koda == 403) zacniPremor(m.osnova)
            return if (kodaPomeniNima(koda)) false else null
        }
        val a = d.optJSONArray("streams") ?: return false
        return (0 until a.length()).any { i -> a.optJSONObject(i)?.let { tok(it, m.ime) }?.let { steje(it) } == true }
    }

    /** Manifeste dodatkov, ki jih se ne poznamo, prenese v ozadju (odlocitev pri usklajevanju virov med napravami). */
    fun spoznaj(naslovi: List<String>) {
        naslovi.filter { zasebenZnano(it) == null }.take(8).forEach { n ->
            try { bazen.execute { try { manifest(n) } catch (_: Exception) { } } } catch (_: Exception) { }
        }
    }

    /** Vsi katalogi enega dodatka, ki brez filtra vrnejo vsebino - za izrecno odprt dodatek (Moji viri). */
    fun katalogiDodatka(naslov: String): List<Katalog> = manifest(naslov)?.katalogi.orEmpty().filter { it.prikazen }

    /** Ime dodatka, ce je manifest ze nalozen (brez omrezja - za glavno nit). */
    fun imeIzPredpomnilnika(naslov: String): String? = osnova(naslov).let { o -> manifesti[o]?.ime ?: znanaImena[o] }

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)
    /** (osnova kataloskega dodatka, tip, id vnosa) iz id-ja kartice. */
    fun razstavi(s: Jamendo.Skladba): Triple<String, String, String>? {
        val d = s.id.removePrefix(PREDPONA).split('|', limit = 3)
        return if (d.size == 3) Triple(d[2].substringBefore('#'), d[0], d[1]) else null
    }
    /**
     * Enota dodatka, ki ga uporabnik nima vec (izbrisal ga je tu ali na drugi napravi v Linku): ne da se predvajati,
     * zato je ne kazemo v »Nadaljuj gledanje« in »Nazadnje predvajano« (lastnik: kar se ne da predvajati, ne kazemo).
     * Javni katalog Cinemeta je vedno namescen. Zapis ostane - ce dodatek vrne, se naslov vrne z njim.
     */
    fun brezDodatka(s: Jamendo.Skladba, nasloviDodatkov: Collection<String>): Boolean {
        if (!jeEnota(s)) return false
        val dodatek = razstavi(s)?.first ?: return false
        // Cinemeta je bila do naloge 57 vedno namescena (vgrajen vir); zdaj velja kot vsak dodatek: ce je uporabnik nima,
        // njenih kartic ne kazemo.
        return SeznamiPravila.brezDodatka(dodatek, nasloviDodatkov.map { osnova(it) }, osnova(CINEMETA), MedijskiViri.VGRAJENI_SPLETNI_VIRI)
    }

    fun jeSerija(s: Jamendo.Skladba) = razstavi(s)?.second == "series"
    /** Razred enote dodatka (FILM, SERIJA, TV, GLASBA, RADIO, VIDEO). */
    fun razredEnote(s: Jamendo.Skladba): String = razred(razstavi(s)?.second.orEmpty())
    fun jeVZivo(s: Jamendo.Skladba) = jeEnota(s) && razredEnote(s) == TV

    private fun vnos(m: JSONObject, osnova: String, tipKataloga: String = "", glasbeno: Boolean = false): Jamendo.Skladba? {
        val id = m.optString("id").ifBlank { return null }
        val tip = m.optString("type").ifBlank { tipKataloga.ifBlank { "movie" } }
        val leto = m.optString("releaseInfo").ifBlank { m.optString("year") }
        val r = razred(tip)
        // Glasba in radio: pod naslovom je izvajalec (kdo igra), ne letnica - uporabnik mora vedeti, koga pricakovati
        // (lastnik, 2. 10. 2026). Dodatki ga podajo razlicno, zato po vrsti: artist/author, igralci, reziser, opis.
        val podnaslov = if (r == GLASBA || r == RADIO) izvajalec(m).ifBlank { leto } else leto
        // Naloga 63: vnos, ki se oglasa kot film, po svojih podatkih pa je serija (epizode v `videos`, obdobje »2016–2021«):
        // na kartici in v mrezi Filmi | Serije velja za serijo. Brez nove poizvedbe - samo iz odgovora kataloga.
        val epizodeVnosa = m.optJSONArray("videos")?.let { v -> (0 until v.length()).mapNotNull { i -> v.optJSONObject(i)?.let { x -> x.optInt("season") to x.optInt("episode") } } }.orEmpty()
        val serijaPoPodatkih = tip == "movie" && OsPravila.vrstaIzMeta("", leto, epizodeVnosa) == "series"
        if (serijaPoPodatkih || (tip == "series" && id.startsWith("tt"))) VrsteNaslovov.zapomni(listOf(VrsteNaslovov.kljucVnosa(osnova, id)), VrsteNaslovov.SERIJA)
        // Naloga 66: drzave izvora iz odgovora kataloga (Cinemeta jih da pri vsakem vnosu) - za filter jezika, brez poizvedbe.
        (m.optString("imdb_id").takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") })?.let { VrsteNaslovov.zapomniDrzave(it, drzaveIz(m)) }
        return Jamendo.Skladba(PREDPONA + tip + "|" + id + "|" + osnova, m.optString("name").ifBlank { id }, podnaslov,
            // Webtor rezerva: slika s poverilnico dodatka v naslovu ne gre v zgodovino in sezname.
            slikaVnosa(m).let { if (SkrivniDodatki.vsebujeSkrivnost(it)) "" else it }, "", "$osnova/meta/${enc(tip)}/${enc(id)}.json",
            // Glasba in radio sta zvok (kartica in predvajalnik za zvok), vse ostalo video. Naloga 77: glasba z id-jem IMDb
            // (koncert iz Music Concerts) je posnetek - video.
            radio = r == RADIO, video = (r != GLASBA && r != RADIO) || OsPravila.glasbaJeVideo(r == GLASBA, id),
            // FILM / SERIJA za oznako na kartici in za filter Filmi | Serije (SpletniVir.vrstaVsebine).
            // Videospot (glasbeni katalog): ne gre med filme in serije (SpletniVir.vrstaVsebine).
            mediaType = if (glasbeno || OsPravila.glasbaJeVideo(r == GLASBA, id)) "MusicVideo" else if (serijaPoPodatkih) "tvseries" else when (tip) { "movie" -> "movie"; "series" -> "tvseries"; else -> "" },
            year = leto.take(4).toIntOrNull() ?: 0,
            // Id IMDb (tt...), kot ga rabi vecina dodatkov: isti film iz vec katalogov/dodatkov je ena kartica.
            // Pregled Grok 6: tudi katalog z id-jem tmdb:/kitsu:, ki poda imdb_id (isti film - eno mesto v »Nadaljuj«).
            imdbId = SerijePravila.imdbIzMeta(m.optString("imdb_id"), id).orEmpty(),
            genres = (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { g -> (0 until g.length()).mapNotNull { g.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
            // Ocena (IMDb), kadar jo katalog poda: na kartici kot zvezdica, kot pri drugih virih.
            rating = m.optString("imdbRating").toDoubleOrNull() ?: 0.0)
    }

    /**
     * Kartica naslova, ki ga poznamo samo po id-ju IMDb (seznam naslovov v izbranem jeziku): enaka kot kartica javnega
     * kataloga Cinemeta - podrobnosti, epizode in tokovi gredo po isti poti.
     */
    fun vnosPoImdb(tip: String, imdb: String, naslov: String, leto: Int, jezik: String = ""): Jamendo.Skladba {
        val osnova = osnova(CINEMETA)
        return Jamendo.Skladba(PREDPONA + tip + "|" + imdb + "|" + osnova, naslov, if (leto > 0) leto.toString() else "",
            "https://images.metahub.space/poster/small/$imdb/img", "", "$osnova/meta/${enc(tip)}/${enc(imdb)}.json",
            video = true, mediaType = if (tip == "movie") "movie" else "tvseries", year = leto, imdbId = imdb, language = jezik)
    }

    /** Slika vnosa: plakat, sicer logotip, ozadje ali slicica - katerokoli sliko dodatek poda, raje kot prazno kartico. */
    internal fun slikaVnosa(m: JSONObject): String =
        listOf("poster", "logo", "background", "thumbnail", "image", "icon").firstNotNullOfOrNull { k ->
            m.optString(k).trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.orEmpty()

    /** Izvajalec glasbenega vnosa, kot ga dodatki podajo: artist/author, prvi igralci, reziser, sicer kratek opis. */
    internal fun izvajalec(m: JSONObject): String {
        fun niz(k: String): String = when (val v = m.opt(k)) {
            is String -> v.trim()
            is org.json.JSONArray -> (0 until minOf(v.length(), 2)).mapNotNull { v.optString(it).trim().takeIf { s -> s.isNotBlank() } }.joinToString(", ")
            else -> ""
        }
        listOf("artist", "artists", "author", "cast", "director").forEach { k -> niz(k).takeIf { it.isNotBlank() }?.let { return it.take(60) } }
        // Opis: samo kratka prva vrstica (npr. "Siddharta" ali "Pop · Slovenija"), dolgega besedila ne kazemo pod naslovom.
        return m.optString("description").lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length <= 48 }.orEmpty()
    }

    /** Stran kataloga (`skip` = koliko vnosov preskociti, kot v Stremiu); obvezni parametri s privzeto moznostjo, iskanje kot `search=`. */
    fun katalog(k: Katalog, iskanje: String = "", skip: Int = 0, zvrst: String = ""): List<Jamendo.Skladba> =
        katalogAliIzpad(k, iskanje, skip, zvrst) ?: emptyList()

    /**
     * Kot [katalog], le da izpad (dodatek ni odgovoril ali je odgovoril z napako streznika, prijave ali omejitve) vrne
     * null. Prazna stran in »tega nimam« (404) sta odgovor - prazen seznam.
     */
    fun katalogAliIzpad(k: Katalog, iskanje: String = "", skip: Int = 0, zvrst: String = ""): List<Jamendo.Skladba>? {
        // Dodatek, ki ne odgovarja (naloga 57d): do naslednjega preizkusa ga ne sprasujemo - to je izpad, ne prazen katalog.
        // Naloga 90c: iskanje uporabnika gre mimo (YouTubio je pocasen in bi ga varovalka po izpadih zaprla za 30 min).
        if (!OsPravila.iskanjeMimoVarovalke(iskanje) && !smemVprasati(k.dodatek)) { zadnjaKoda.set(0); return null }
        // Naloga 73: katalog kanalov (IPTV: tisoce vnosov na stran) beremo tokovno in hranimo samo kartice.
        if (iskanje.isBlank() && razredKataloga(k) == TV) return katalogKanalov(k, skip, zvrst)
        val pot = potKataloga(k, iskanje, skip, zvrst)
        val d = json("$pot.json") ?: return if (kodaPomeniNima(zadnjaKoda.get() ?: 0)) emptyList() else null
        val a = d.optJSONArray("metas") ?: d.optJSONArray("metasDetailed") ?: return emptyList()
        if (dnevnik() && manifesti[k.dodatek]?.zaseben != true) {
            val prvi = a.optJSONObject(0)
            val kljuci = ArrayList<String>().apply { prvi?.keys()?.forEach { add(it) } }
            val zvrstiPrvih = (0 until minOf(a.length(), 6)).map { i -> a.optJSONObject(i)?.let { m -> (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.toString() ?: "-" } }
            android.util.Log.d(DNEVNIK, "${oznakaDodatka(k.dodatek)} katalog ${k.tip}/${k.id} zvrst=$zvrst skip=$skip iskanje=${iskanje.isNotBlank()} -> ${a.length()} vnosov; " +
                "polja prvega=$kljuci; tip prvega=${prvi?.optString("type")}; zvrsti prvih=$zvrstiPrvih")
        }
        // Vnos glasbenega kataloga, ki se oglasa kot film ali drug video, je videospot (ne film).
        val glasbeno = razred(k.tip) != GLASBA && razredKataloga(k) == GLASBA
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { m -> vnos(m, k.dodatek, k.tip, glasbeno) } }
    }

    /** Pot strani kataloga brez ».json«: obvezni parametri s privzeto moznostjo, iskanje, zvrst in `skip`. */
    private fun potKataloga(k: Katalog, iskanje: String, skip: Int, zvrst: String): String {
        var pot = "${k.dodatek}/catalog/${enc(k.tip)}/${enc(k.id)}"
        val dodatno = (if (iskanje.isNotBlank()) listOf("search" to iskanje) else emptyList()) +
            // Izbrana zvrst (moznost kataloga, npr. "Comedy") nadomesti privzeto moznost parametra genre.
            (if (zvrst.isNotBlank()) listOf("genre" to zvrst) else emptyList()) +
            k.privzeti.filter { it.first != "search" && (zvrst.isBlank() || it.first != "genre") && (iskanje.isBlank() || it.first in k.obvezni) } +
            (if (skip > 0) listOf("skip" to skip.toString()) else emptyList())
        if (dodatno.isNotEmpty()) pot += "/" + dodatno.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        return pot
    }

    // ------------------------------------------------------------------ katalogi kanalov: tokovno branje (naloga 73)

    /**
     * Polja vnosa, ki jih kartica kanala rabi ([vnos]); vse drugo (dolgi opisi, povezave, tokovi in videi v meta) preskocimo.
     * Prej: cel odgovor kot niz, nato JSONObject, ta pa 15 min v predpomnilniku - pri dodatku IPTV z 213 katalogi (lastnik,
     * 23. 10. 2026) ~55 s dodeljevanja (GC vsakih 0,6 s).
     */
    private val POLJA_KANALA = setOf("id", "type", "name", "poster", "logo", "background", "thumbnail", "image", "icon",
        "genres", "genre", "releaseInfo", "year", "description", "imdbRating", "imdb_id", "country")
    /** Najvec vnosov s strani kataloga kanalov (dodatek IPTV da lahko vse kanale drzave naenkrat). */
    const val NAJVEC_NA_STRANI_KANALOV = 1000
    /**
     * Razclenjene strani katalogov kanalov (kartice, ne JSON), 15 min. Naloga 86 (M4): meja po STEVILU KARTIC
     * ([OsPravila.NAJVEC_KARTIC_STRANI_KANALOV]), ne strani - prej 48 strani po do 1000 kartic (25-50 MB na sibkem TV).
     */
    private val straniKanalov = object : android.util.LruCache<String, Pair<Long, List<Jamendo.Skladba>>>(OsPravila.NAJVEC_KARTIC_STRANI_KANALOV) {
        override fun sizeOf(key: String, value: Pair<Long, List<Jamendo.Skladba>>): Int = OsPravila.velikostStraniKanalov(value.second.size)
    }

    /** Naloga 92: predpomnilnik na USB v uporabi - manj strani v pomnilniku ([PredpomnilnikPravila.mejaStraniKanalov]), ostale z USB. */
    fun prilagodiPomnilnik(usb: Boolean) = straniKanalov.resize(PredpomnilnikPravila.mejaStraniKanalov(usb, OsPravila.NAJVEC_KARTIC_STRANI_KANALOV))

    /** Naloga 92: pomnilniski pritisk z USB - strani gredo iz pomnilnika (naslednje branje z USB, [katalogKanalov]). */
    fun sprostiStraniKanalov() = straniKanalov.evictAll()

    /** Stran kataloga kanalov kot [katalogAliIzpad]; null = izpad (tudi neveljaven ali pretrgan odgovor). */
    private fun katalogKanalov(k: Katalog, skip: Int, zvrst: String): List<Jamendo.Skladba>? {
        val url = potKataloga(k, "", skip, zvrst) + ".json"
        val zdaj = System.currentTimeMillis()
        straniKanalov.get(url)?.let { (cas, s) -> if (zdaj - cas < veljavnost(url)) return s }
        // Naloga 87: stran z USB (po ponovnem zagonu aplikacije, ce se velja) - pred omrezjem. Zasebne strani ostanejo na tej napravi.
        val app = aplikacija
        if (app != null) MedijskiPredpomnilnik.stranKanalov(app, url, veljavnost(url))?.let { z -> straniKanalov.put(url, z); return z.second }
        val stran = prenesiTokovno(url) { r -> beriVnoseKataloga(r, k) }
            ?: return if (kodaPomeniNima(zadnjaKoda.get() ?: 0)) emptyList() else null
        straniKanalov.put(url, zdaj to stran)
        if (app != null && stran.isNotEmpty()) MedijskiPredpomnilnik.shraniStranKanalov(app, url, stran)
        return stran
    }

    /** {"metas": [...]} tokovno: vsak vnos samo s [POLJA_KANALA], najvec [NAJVEC_NA_STRANI_KANALOV]. */
    private fun beriVnoseKataloga(r: android.util.JsonReader, k: Katalog): List<Jamendo.Skladba> {
        val izid = ArrayList<Jamendo.Skladba>()
        val glasbeno = razred(k.tip) != GLASBA && razredKataloga(k) == GLASBA
        r.beginObject()
        while (r.hasNext()) {
            val ime = r.nextName()
            if ((ime != "metas" && ime != "metasDetailed") || r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT || izid.size >= NAJVEC_NA_STRANI_KANALOV) { r.skipValue(); continue }
                val m = JSONObject()
                r.beginObject()
                while (r.hasNext()) {
                    val polje = r.nextName()
                    if (polje !in POLJA_KANALA) { r.skipValue(); continue }
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER ->
                            m.put(polje, r.nextString().let { if (polje == "description") it.take(200) else it })
                        android.util.JsonToken.BEGIN_ARRAY -> {
                            val a = org.json.JSONArray()
                            r.beginArray()
                            while (r.hasNext()) { if (r.peek() == android.util.JsonToken.STRING && a.length() < 8) a.put(r.nextString()) else r.skipValue() }
                            r.endArray()
                            m.put(polje, a)
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                vnos(m, k.dodatek, k.tip, glasbeno)?.let { izid += it }
            }
            r.endArray()
        }
        r.endObject()
        return izid
    }

    /** Kot [prenesiJson], le da odgovor bere tokovno ([beri]) - brez celega niza in drevesa JSON v pomnilniku. */
    private fun <T> prenesiTokovno(url: String, beri: (android.util.JsonReader) -> T): T? {
        zadnjaKoda.set(0)
        zadnjeGlave.set(emptyMap())
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        // Pregled S5: nadomestek, katerega skrivnosti ta naprava ne pozna (Keystore) - nobene zahteve na nadomestni naslov.
        if (!SkrivniDodatki.pozna(aplikacija, url)) return null
        val politika = OsPravila.politikaZahteve(url, true)
        val cas = politika.rokMs
        return sPrednostjo(politika.prednost) { try {
            // Webtor rezerva: nadomestek dodatka s poverilnico -> pravi naslov samo za ta klic (nikoli v dnevnik).
            val c = URL(SkrivniDodatki.dejanski(aplikacija, url)).openConnection() as HttpURLConnection
            c.connectTimeout = cas; c.readTimeout = cas
            c.setRequestProperty("User-Agent", "Safeer-Predvajalnik/1.0 (+https://safeer.si)")
            c.setRequestProperty("Accept", "application/json")
            // Naloga 124: jezik uporabnika in nastavitve dodatka (glava, nikoli v naslovu - docs/DODATKI-PROTOKOL.md 4).
            c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
            glavaNastavitev(url)?.let { c.setRequestProperty("X-Safeer-Config", it) }
            try {
                val koda = c.responseCode
                zadnjaKoda.set(koda)
                zabeleziOdziv(url, koda)
                if (koda == 429 || koda == 403) zadnjeGlave.set(try {
                    c.headerFields.mapNotNull { (k, v) -> if (k == null) null else v?.firstOrNull()?.let { k.lowercase() to it } }.toMap()
                } catch (_: Exception) { emptyMap() })
                DodatkiRazvoj.zapisi(url, koda, -1, 0L)
                if (koda !in 200..299) return null
                // Pregled D7: tudi tokovno branje ima trdo mejo (napovedana dolzina in dejansko prebrani bajti).
                if (OsPravila.odgovorPrevelik(c.contentLengthLong, 2 * OsPravila.NAJVEC_ODGOVORA_DODATKA)) { prevelik(url); return null }
                android.util.JsonReader(java.io.InputStreamReader(java.io.BufferedInputStream(
                    OmejenVhod(c.inputStream, 2L * OsPravila.NAJVEC_ODGOVORA_DODATKA), 16 * 1024), Charsets.UTF_8)).use { beri(it) }
            } finally { c.disconnect() }
        } catch (_: Exception) { if ((zadnjaKoda.get() ?: 0) == 0) zabeleziOdziv(url, 0); null } }
    }

    /** Javni katalog metapodatkov Stremia (Cinemeta: filmi in serije po priljubljenosti, epizode) - samo
     *  metapodatki, tokove dajo uporabnikovi dodatki. Isti vir, kot ga Stremio sam kaze v Discover. */
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    /**
     * Uporabnikovi dodatki + javni katalog Cinemeta (kot v Stremiu, kjer je Cinemeta vedno namescena): ko dodas
     * dodatke Stremio, so filmi in serije na voljo, uporabnik ne raziskuje, kateri dodatek je katalog (lastnik,
     * 1. 10. 2026). Uporabnikovi katalogi so pred Cinemeto.
     */
    fun zKatalogom(naslovi: List<String>): List<String> {
        // Naloga 57: brez vgrajenih virov katalog Cinemeta ni vec dodan sam - samo, ce ga je dodal uporabnik.
        return SeznamiPravila.zKatalogom(naslovi, CINEMETA, MedijskiViri.VGRAJENI_SPLETNI_VIRI) { osnova(it) }
    }

    /** Katalogi razdelka Video (filmi, nato serije, nato drug video: kanali, anime ...); brez tistih, ki brez filtra ne vrnejo nicesar. */
    fun prikazniKatalogi(naslovi: List<String>): List<Katalog> = naslovi.mapNotNull { manifest(it) }.filter { !it.zaseben }
        .flatMap { m -> m.katalogi.filter { it.prikazen && razredKataloga(it) in setOf(FILM, SERIJA, VIDEO) } }
        .sortedBy { when (it.tip) { "movie" -> 0; "series" -> 1; else -> 2 } }

    /**
     * Katalogi dodatkov izbranega razreda: TV (razdelek TV v zivo), GLASBA (Glasba), RADIO (Radio). Katalog, ki ga
     * dodatek sam oznaci kot zasebno kategorijo ([KanaliPravila.jeZasebna]), ne gre v skupne sezname; [zZasebnimi]
     * ga vrne klicatelju, ki njegove vnose izloci tudi iz kataloga »vse«.
     */
    fun katalogiRazreda(naslovi: List<String>, razred: String, zZasebnimi: Boolean = false): List<Katalog> = naslovi.mapNotNull { manifest(it) }.filter { !it.zaseben }
        .flatMap { m -> m.katalogi.filter { it.prikazen && razredKataloga(it) == razred && (zZasebnimi || !zasebenKatalog(it)) } }

    /**
     * Naloga 77: glasbeni katalog s posnetki (video): tip je glasba, dodatek pa vnose oznaci z id-ji IMDb (Music Concerts:
     * tip music, predpone [tt]). Sodi v skupino videospotov, ne med zvok.
     */
    fun jeVideoKatalog(k: Katalog): Boolean {
        if (razred(k.tip) != GLASBA) return false
        val p = manifesti[k.dodatek]?.predpone.orEmpty()
        return p.isNotEmpty() && p.all { it == "tt" }
    }

    /**
     * Naloga 78: ali ima kateri od dodatkov [naslovi] glasbene posnetke (videospote ali koncerte, [jeVideoKatalog]). Samo
     * iz znanih manifestov (brez omrezja - klic z glavne niti); neznan manifest steje kot ne.
     */
    fun imaVideospote(naslovi: List<String>): Boolean = naslovi.mapNotNull { manifesti[osnova(it)] }.filter { !it.zaseben }.any { m ->
        m.katalogi.any { k -> k.prikazen && razredKataloga(k) == GLASBA && !zasebenKatalog(k) && (razred(k.tip) != GLASBA || jeVideoKatalog(k)) }
    }

    /** Naloga 77: dodatek ima samo kataloge za iskanje ([OsPravila.samoIskanje]); null, dokler manifesta ne poznamo. Brez omrezja. */
    fun samoIskanje(naslov: String): Boolean? = manifesti[osnova(naslov)]?.let { m -> OsPravila.samoIskanje(m.katalogi.map { it.prikazen to it.iskanje }) }

    /**
     * Naloga 77: tip za poizvedbo po tokovih ([OsPravila.tipToka]): »music« z id-jem IMDb vprasamo kot »movie«, ce noben
     * dodatek ne da toka za »music«. Manifeste vzame iz predpomnilnika (klic iz ozadja).
     */
    fun tipToka(naslovi: List<String>, tip: String, id: String): String {
        if (!OsPravila.jeImdb(id) || tip == "movie" || tip == "series") return tip
        val zaTip = naslovi.mapNotNull { try { manifest(it) } catch (_: Exception) { null } }
            .any { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        return OsPravila.tipToka(tip, id, zaTip)
    }

    internal fun zasebenKatalog(k: Katalog) = KanaliPravila.jeZasebna(KanaliPravila.oznakaKataloga(k.imeDodatka, k.ime))


    /** Katalog z vnaprej izbrano moznostjo zvrsti: stran in `skip` gresta po obicajni poti ([katalog]). */
    fun zMoznostjo(k: Katalog, moznost: String): Katalog =
        if (moznost.isBlank()) k else k.copy(privzeti = k.privzeti.filter { it.first != "genre" } + ("genre" to moznost))

    /** Katalogi TV kanalov v zivo iz uporabnikovih dodatkov - gredo v razdelek TV v zivo (lastnik, 1. 10. 2026). */
    fun katalogiTv(naslovi: List<String>): List<Katalog> = katalogiRazreda(naslovi, TV, zZasebnimi = true)

    /** V kateri razdelek sodi dodatek po svojih katalogih: video (filmi, serije), sicer TV, glasba ali radio. */
    fun glavniRazred(naslov: String): String {
        val razredi = manifest(naslov)?.katalogi.orEmpty().map { razredKataloga(it) }.toSet()
        return when {
            razredi.isEmpty() || FILM in razredi || SERIJA in razredi || VIDEO in razredi -> VIDEO
            TV in razredi -> TV
            GLASBA in razredi -> GLASBA
            else -> RADIO
        }
    }

    /**
     * Iskanje po imenu v dodatkih VSEH vrst (filmi, serije, TV v zivo, glasba, radio), vsi katalogi hkrati.
     * Katalog z lastnim iskanjem vprasamo z `search=`; kataloge kanalov, postaj in glasbe, ki iskanja ne
     * podpirajo (vecina dodatkov TV v zivo), preiscemo sami po imenu (stran kataloga je v predpomnilniku).
     */
    fun isci(naslovi: List<String>, beseda: String, tudiZasebni: Boolean = false, samoIskanje: Boolean? = null): List<Jamendo.Skladba> {
        // Zasebni dodatki niso v skupnem iskanju (ZasebniDodatki): isce se v njih samo izrecno ([tudiZasebni]).
        // Naloga 81: [samoIskanje] = true samo katalogi brez mreze, ki zahtevajo iskanje (YouTubio), false = brez njih, null = vsi -
        // Media Center jih vprasa v locenem opravilu z daljsim rokom.
        val katalogi = naslovi.mapNotNull { manifest(it) }.filter { tudiZasebni || !it.zaseben }.flatMap { it.katalogi }
            .filter { tudiZasebni || !zasebenKatalog(it) }
            .filter { k -> when (samoIskanje) { null -> true; true -> k.iskanje && !k.prikazen; false -> k.prikazen || !k.iskanje } }
        val zIskanjem = katalogi.filter { it.iskanje }.distinctBy { it.dodatek + "|" + it.tip }
        val pokriti = zIskanjem.map { it.dodatek + "|" + it.tip }.toSet()
        val brezIskanja = if (samoIskanje == true) emptyList() else katalogi.filter { !it.iskanje && it.prikazen && (it.dodatek + "|" + it.tip) !in pokriti &&
            razredKataloga(it) in setOf(TV, RADIO, GLASBA) }.take(16)
        val opravila = zIskanjem.map { k -> k to java.util.concurrent.Callable { iskanjeVKatalogu(k, beseda) } } +
            brezIskanja.map { k -> k to java.util.concurrent.Callable { katalog(k).filter { ujema(it.naslov, beseda) }.take(20) } }
        if (opravila.isEmpty()) return emptyList()
        // Naloga 77: katalog samo za iskanje (YouTubio: iskanje na YouTubu prek streznika dodatka traja dlje) dobi daljsi rok;
        // ostali kot doslej 10 s. Kar ne odgovori v roku, preklicemo.
        val zacetek = System.currentTimeMillis()
        val niti = opravila.map { (k, c) -> (if (!k.prikazen) ROK_SAMO_ISKANJA_MS else ROK_ISKANJA_MS) to bazen.submit(c) }
        return niti.flatMap { (rok, f) ->
            try { f.get((zacetek + rok - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: Exception) { f.cancel(true); emptyList() }
        }
            .distinctBy { it.id }.filter { tudiZasebni || !ZasebniKanali.jeZaseben(it.id) }      // naloga 76: zasebne kategorije javnih dodatkov
    }

    /** Naloga 81: dodatek|tip, za katerega je oblika toka ze zapisana v dnevnik. */
    private val zapisaneOblike: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Naloga 81: ime dodatka zadetka za vrstico izvorov (iz predpomnilnika manifesta). */
    fun imeIzvora(s: Jamendo.Skladba): String? = razstavi(s)?.first?.let { o -> manifesti[o]?.ime ?: znanaImena[o] }?.let { OsPravila.imeIzvora(it) }

    private const val ROK_ISKANJA_MS = 10_000L
    private const val ROK_SAMO_ISKANJA_MS = 15_000L

    /** Iskanje v katalogu z `search=`; dnevnik razvijalca pove, koliko zadetkov je dal in v kolikem casu (naloga 77). */
    private fun iskanjeVKatalogu(k: Katalog, beseda: String): List<Jamendo.Skladba> {
        val od = System.currentTimeMillis()
        val r = katalogAliIzpad(k, beseda)
        if (dnevnik() && manifesti[k.dodatek]?.zaseben != true) android.util.Log.d(DNEVNIK, "${oznakaDodatka(k.dodatek)} iskanje ${k.tip}/${k.id} " +
            "samo iskanje=${!k.prikazen} -> ${r?.size ?: -1} zadetkov v ${System.currentTimeMillis() - od} ms")
        return r.orEmpty().take(20)
    }

    /**
     * Posnetki enote (`videos` v metapodatkih): epizode serije, skladbe albuma, videi kanala. Serija: ostevilcene
     * epizode po sezonah (kot do zdaj); ostalo v vrstnem redu dodatka, tudi brez sezone in stevilke.
     */
    fun epizode(s: Jamendo.Skladba): List<Epizoda> {
        val (osnova, tip, id) = razstavi(s) ?: return emptyList()
        return epizodeZa(osnova, tip, id)
    }

    /**
     * Prva epizoda serije z javnim id-jem (IMDb) po javnem katalogu: po njej preverjevalec doma preveri serijo kot
     * celoto, kadar ga vprasa druga naprava (ta poslje samo kljuc, ne svoje kartice). null = katalog serije ne pozna.
     */
    fun prvaEpizoda(id: String, naslovi: List<String>): String? {
        // Naloga 57: metapodatke vprasamo uporabnikove dodatke, ki jih za serije s tem id-jem dajejo - ne Cinemete, ki je
        // uporabnik morda nima (koda ne doda vira sama).
        val viri = zKatalogom(naslovi).mapNotNull { n -> try { manifest(n) } catch (_: Exception) { null } }
            .filter { m -> "meta" in m.viri && (m.tipi.isEmpty() || "series" in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        for (m in viri) {
            val ep = (try { epizodeZa(m.osnova, "series", id) } catch (_: Exception) { emptyList() }).firstOrNull { it.sezona >= 1 }?.id
            if (ep != null) return ep
        }
        return null
    }

    /** Naloga 130: id IMDb vnosa iz metapodatkov ([metaZa]); kljuc »osnova|id«. */
    private val imdbVnosov = ConcurrentHashMap<String, String>()

    /** Id IMDb enote: s kartice, iz id-ja ali iz ze prebranih metapodatkov njenega dodatka; null = ga ne poznamo. */
    fun imdbZa(s: Jamendo.Skladba): String? {
        val (osnova, _, id) = razstavi(s) ?: return SerijePravila.imdbIzMeta(s.imdbId, "")
        return SerijePravila.imdbIzMeta(s.imdbId, id) ?: imdbVnosov["$osnova|$id"]
    }

    /**
     * Naloga 130: epizode serije po id-ju IMDb pri drugih uporabnikovih dodatkih z metapodatki (dodatek kartice jih ni dal).
     * Kot pri [prvaEpizoda]: samo dodatki, ki jih je uporabnik dodal; [razen] = osnova dodatka kartice.
     */
    fun epizodePoImdb(naslovi: List<String>, imdb: String, razen: String): List<Epizoda> {
        val viri = zKatalogom(naslovi).mapNotNull { n -> try { manifest(n) } catch (_: Exception) { null } }
            .filter { m -> m.osnova != razen && "meta" in m.viri && (m.tipi.isEmpty() || "series" in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { imdb.startsWith(it) }) }
        for (m in viri) {
            val ep = (try { epizodeZa(m.osnova, "series", imdb) } catch (_: Exception) { emptyList() }).filter { it.sezona >= 1 && it.epizoda >= 1 }
            if (ep.isNotEmpty()) return ep
        }
        return emptyList()
    }

    /** Ali je naslov dodatka javni katalog Cinemeta (uporabnik ga je dodal sam). */
    fun jeCinemeta(naslov: String): Boolean = osnova(naslov) == osnova(CINEMETA)

    private fun epizodeZa(osnova: String, tip: String, id: String): List<Epizoda> = metaZa(osnova, tip, id)?.second.orEmpty()

    /**
     * Metapodatki enote: (vrsta po [OsPravila.vrstaIzMeta], posnetki kot pri [epizode]); null = dodatek ni odgovoril.
     * Naloga 63: vrsto si zapomni ([VrsteNaslovov]) - iz odgovora, ki ga preverjanje serij, preverjevalec doma
     * ([prvaEpizoda]) ali odprt plakat ze dobijo; nove poizvedbe zaradi tega ni. Film zapomni samo, ce ga je dodatek
     * dal kot serijo (vprasan za serijo, odgovori film brez epizod).
     */
    private fun metaZa(osnova: String, tip: String, id: String): Pair<String?, List<Epizoda>>? {
        val m = json("$osnova/meta/${enc(tip)}/${enc(id)}.json")?.optJSONObject("meta") ?: return null
        // Naloga 130: id IMDb vnosa (dodatki s torrenti, npr. Torrentio, poznajo samo tega) - za epizode z drugim id-jem.
        SerijePravila.imdbIzMeta(m.optString("imdb_id"), id)?.let { imdbVnosov["$osnova|$id"] = it }
        val v = m.optJSONArray("videos")
        val imeDodatka = manifesti[osnova]?.ime.orEmpty()
        val vsi = if (v == null) emptyList() else (0 until v.length()).mapNotNull { i ->
            val x = v.optJSONObject(i) ?: return@mapNotNull null
            val vid = x.optString("id").ifBlank { return@mapNotNull null }
            val tokovi = x.optJSONArray("streams")?.let { a -> (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { o ->
                tok(o, imeDodatka)?.let { prilagodi(it, o, osnova, manifesti[osnova]?.id.orEmpty()) } } } }.orEmpty()
            Epizoda(vid, x.optString("title").ifBlank { x.optString("name") }, x.optInt("season"), x.optInt("episode"), tokovi)
        }
        val ostevilcene = vsi.filter { it.sezona > 0 || it.epizoda > 0 }
        val vrsta = OsPravila.vrstaIzMeta(m.optString("type"), m.optString("releaseInfo"), vsi.map { it.sezona to it.epizoda })
        if (vrsta == VrsteNaslovov.SERIJA || (vrsta == VrsteNaslovov.FILM && tip == "series" && ostevilcene.isEmpty()))
            VrsteNaslovov.zapomni(listOfNotNull(VrsteNaslovov.kljucVnosa(osnova, id),
                m.optString("imdb_id").takeIf { it.startsWith("tt") }?.let { "i:" + it.lowercase() }), vrsta)
        // Naloga 66: drzave izvora iz istega odgovora (brez nove poizvedbe).
        (m.optString("imdb_id").takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") })?.let { VrsteNaslovov.zapomniDrzave(it, drzaveIz(m)) }
        val serija = tip == "series" || vrsta == VrsteNaslovov.SERIJA
        return vrsta to (if (serija && ostevilcene.isNotEmpty()) ostevilcene.sortedWith(compareBy({ it.sezona }, { it.epizoda })) else vsi)
    }

    /** Naloga 66: polje `country` metapodatkov - niz (»United States, United Kingdom«) ali seznam; "" = ga ni. */
    private fun drzaveIz(m: JSONObject): String =
        m.optJSONArray("country")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ") }
            ?: m.optString("country")

    /** Naloga 63: ali katalogni dodatek enote daje metapodatke za njen tip in id (klik na plakat jih sme vprasati). Brez omrezja. */
    fun dajeMeta(s: Jamendo.Skladba): Boolean {
        val (osnova, tip, id) = razstavi(s) ?: return false
        val m = manifesti[osnova] ?: return false
        return "meta" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) })
    }

    /** Naloga 63: vrsta in posnetki enote iz metapodatkov njenega dodatka (predpomnilnik 1 h); null = ni odgovora. Klic iz niti. */
    fun vrstaInPosnetki(s: Jamendo.Skladba): Pair<String?, List<Epizoda>>? {
        val (osnova, tip, id) = razstavi(s) ?: return null
        return metaZa(osnova, tip, id)
    }

    /** Naloga 63: ista enota kot serija (tokovi epizod gredo na /stream/series/<id epizode>, kot v Stremiu). */
    fun kotSerija(s: Jamendo.Skladba): Jamendo.Skladba {
        val d = s.id.removePrefix(PREDPONA).split('|', limit = 3)
        if (!jeEnota(s) || d.size != 3) return s
        return s.copy(id = PREDPONA + "series|" + d[1] + "|" + d[2], mediaType = "tvseries")
    }

    private fun tok(s: JSONObject, imeDodatka: String): Tok? {
        // Naloga 130: JSON null (»name": null) org.json vrne kot niz »null« - na seznamu virov je bil napis »null«.
        val ime = SerijePravila.niz(s.optString("name")).ifBlank { imeDodatka }
        val opis = SerijePravila.niz(s.optString("title")).ifBlank { SerijePravila.niz(s.optString("description")) }
        // proxyHeaders.request (SDK: behaviorHints): glave, brez katerih streznik toka ne odgovori.
        val glave = LinkedHashMap<String, String>()
        s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
            for (k in h.keys()) { val v = h.optString(k); if (k.isNotBlank() && v.isNotBlank()) glave[k] = v }
        }
        return when {
            s.optString("url").startsWith("http") ->
                if (jeObvestilo(s, ime, opis)) null else Tok("url", s.optString("url"), ime, opis, imeDodatka, glave = glave)
            // `externalUrl` ni tok, ampak povezava na stran: prosnja za donacijo, vabilo v Discord, "No streams found",
            // trgovina ... Tega uporabniku nikoli ne kazemo (lastnik, 2. 10. 2026) - vnos izpustimo.
            // Naloga 81: izjema je posnetek na YouTubu (YouTubio da posnetek kot povezavo) - vsebina, predvajamo jo kot doslej (splet).
            OsPravila.jeYoutubePovezava(s.optString("externalUrl")) -> Tok("napovednik", s.optString("externalUrl").trim(), ime, opis, imeDodatka)
            s.optString("externalUrl").isNotBlank() -> null
            // Napovednik (YouTube) ni vsebina sama: ne steje kot predvajanje, ponudimo ga le pri rocni izbiri vira.
            s.optString("ytId").isNotBlank() -> Tok("napovednik", "https://www.youtube.com/watch?v=" + s.optString("ytId"), ime, opis, imeDodatka)
            s.optString("infoHash").isNotBlank() -> {
                // Sledilniki iz `sources` ("tracker:udp://..."), da racunalnik hitreje najde vire.
                val viri = s.optJSONArray("sources")
                val tr = (0 until (viri?.length() ?: 0)).mapNotNull { viri?.optString(it) }
                    .filter { it.startsWith("tracker:") }.joinToString("") { "&tr=" + enc(it.removePrefix("tracker:")) }
                val ime2 = s.optJSONObject("behaviorHints")?.optString("filename").orEmpty()
                Tok("torrent", "magnet:?xt=urn:btih:" + s.optString("infoHash") + (if (ime2.isNotBlank()) "&dn=" + enc(ime2) else "") + tr,
                    ime, opis, imeDodatka, s.optInt("fileIdx", -1), imeDatoteke = SerijePravila.niz(ime2))
            }
            else -> null
        }
    }

    /**
     * Webtor rezerva (25. 10. 2026): tok dodatka s poverilnico dobi nadomestek brez skrivnosti - tak gre v predvajalnik,
     * zgodovino, Link; pravi naslov vstavi sele [SkrivniDodatki.dejanski]. Pregled S2/S4: tok, ki se zacne z osnovo dodatka
     * (Webtor `.../resolve/<JWT>`), dobi nadomestek dodatka (velja tudi drugim napravam v ozjem krogu); tok s svojo
     * skrivnostjo (kljuc v poti, zeton v poizvedbi) nadomestek samo v pomnilniku.
     * Tok dodatka Webtor (pregled V1: `id` Webtorja IN dodatek na https://webtor.io - [WebtorPravila.jeWebtorDodatek]): ime
     * brez predpone `[⚡WT]`/`[WT]`/`[⚡TB]`, vrsta predpone, izdaja (infoHash in fileIdx, sicer iz JWT) za ujemanje z nasimi
     * torrenti in sledilniki za magnet.
     */
    /** Oblika naslova toka Webtor brez JWT se zapise enkrat (brez vrednosti - [WebtorPravila.oblikaJwt]). */
    @Volatile private var oblikaJwtZapisana = false

    private fun prilagodi(t: Tok, o: JSONObject, osnova: String, idManifesta: String): Tok {
        var x = t
        if (x.vrsta == "url" && (SkrivnostiPravila.jeNadomestek(osnova) || SkrivnostiPravila.skrivnaPredponaDodatka(osnova) != null))
            x = x.copy(url = SkrivniDodatki.zakrijTok(x.url, osnova))
        if (x.vrsta == "url" && WebtorPravila.jeWebtorDodatek(idManifesta, osnova)) {
            val oznaka = WebtorPravila.razcleni(x.ime)
            val hashToka = SerijePravila.niz(o.optString("infoHash"))
            // Izmerjeno 25. 10. 2026 (OnePlus 9): datoteka je SAMO `idx` v JWT - to predvaja /resolve. Polje fileIdx odgovora
            // Webtorja indeks 0 izpusti (omitempty), tok dodatka brez fileIdx pa pri Webtorju postane datoteka 0 (naslovnica).
            // JWT beremo iz PRAVEGA naslova [t] - nadomestek ga lahko skrije (gradnja dcc8dce: vseh 48 tokov brez JWT).
            val k = WebtorPravila.kljucWebtor(hashToka, t.url)
            if ((k == null || k.idx == WebtorPravila.IDX_NEZNAN) && !oblikaJwtZapisana) {
                oblikaJwtZapisana = true
                android.util.Log.w("SafeerWebtor", "tok brez JWT: ${WebtorPravila.oblikaJwt(t.url)}")
            }
            val viri = o.optJSONArray("sources")
            val sledilniki = (0 until minOf(viri?.length() ?: 0, 30)).mapNotNull { viri?.optString(it) }.filter { it.startsWith("tracker:") }
            x = x.copy(ime = oznaka.ime.ifBlank { WebtorPravila.IME }, vir = WebtorPravila.VIR, oznakaVira = oznaka.vrsta.name,
                hash = k?.hash.orEmpty(), datoteka = k?.idx ?: WebtorPravila.IDX_NEZNAN, sledilniki = sledilniki,
                imeDatoteke = SerijePravila.niz(o.optJSONObject("behaviorHints")?.optString("filename").orEmpty()))
        }
        return x
    }

    /** Vnos med tokovi, ki je obvestilo (prosnja za donacijo, vabilo v Discord, "No streams found"): glej [ObvestilaTokov]. */
    internal fun jeObvestilo(s: JSONObject, ime: String, opis: String): Boolean {
        val namigi = s.optJSONObject("behaviorHints")
        val znakiToka = namigi != null &&
            (namigi.has("filename") || namigi.has("videoSize") || namigi.has("videoHash") || namigi.has("proxyHeaders"))
        return ObvestilaTokov.je(s.optString("url"), "$ime $opis", znakiToka)
    }

    /**
     * Ali torrent na tej napravi steje kot predvajanje. [meja]: Long.MAX_VALUE = vsak (pomocnik v Linku ali veliko
     * prostora), 0 = nobeden, sicer najvecja datoteka (bajti), ki gre na prosti prostor - velikost pove opis toka;
     * torrent brez znane velikosti takrat ne steje (ne obljubimo filma, ki ne gre na napravo).
     */
    fun torrentGre(t: Tok, meja: Long): Boolean = when (meja) {
        0L -> false
        Long.MAX_VALUE -> true
        else -> TokIzbira.opisi(t.ime + " " + t.opis).gb.let { it > 0.0 && it * 1024.0 * 1024.0 * 1024.0 <= meja }
    }

    /** Tok, ki ga ta naprava lahko predvaja kot vsebino (ne napovednik). */
    /** Naloga 141: tudi Usenet (prek TorBoxa) - take tokove da samo iskanje pri indeksatorju (Usenet.isci). */
    fun jePredvajljiv(t: Tok) = t.vrsta == "url" || t.vrsta == "torrent" || t.vrsta == UsenetPravila.VRSTA

    // ------------------------------------------------------------------ vljudnost do dodatkov (preverjanje v ozadju)

    /**
     * Preverjanje, kaj se da predvajati, poslje dodatku veliko poizvedb, ki jih uporabnik ni izrecno sprozil. Dodatek
     * jih sme imeti za zlorabo (3. 10. 2026: "403 Rate limit exceeded (Scraping/Abuse)" - potem ne dela niti
     * predvajanje). Zato: najvec [ZETONI_NAJVEC] poizvedb takoj, nato [ZETONI_NA_S] na sekundo na dodatek, in premor
     * (15 min, ob ponovitvi dvakrat dlje, do 2 h), ko dodatek odgovori 429 ali 403. Predvajanje ([tokovi]) ne caka.
     * Meja je nizka namenoma: 3. 10. 2026 je dodatek zavrnil napravo ze po ~37 poizvedbah v 15 s, v enem domu pa
     * isti dodatek sprasuje vec naprav z istega naslova. Premor prezivi ponovni zagon ([pripravi]).
     * Se nizja od 3. 10. 2026 zvecer: ze en sam telefon je s 6 + 0,5/s (okoli 28 poizvedb v 45 s) sprozil omejitev v
     * manj kot minuti po odprtju Medijskega centra - potem dodatek ni dal tokov niti za film, ki ga je uporabnik izbral.
     * Zdaj 4 takoj in nato ena na 8 s: polica se preveri pocasneje (izid se hrani ure), predvajanje pa dela.
     * 4. 10. 2026: tudi to ni dovolj - ena sama naprava je po daljsem brskanju spet sprozila omejitev. Dodatek steje
     * tudi vsoto v daljsem casu, zato ima vsak dodatek se urni proracun, ki se uci ([TempoDodatka], [proracun]).
     */
    private const val ZETONI_NAJVEC = 4.0
    private const val ZETONI_NA_S = 0.125
    private const val PREMOR_MS = 15 * 60_000L
    private const val PREMOR_NAJVEC_MS = 2 * 3_600_000L
    private class Vedro { var zetoni = ZETONI_NAJVEC; var cas = System.currentTimeMillis() }
    private val vedra = ConcurrentHashMap<String, Vedro>()
    private val premorDo = ConcurrentHashMap<String, Long>()
    private val premorKorak = ConcurrentHashMap<String, Int>()
    private const val PREFS_PREMOR = "safeer_stremio_premor"
    /** Naloga 79: dodatki kanalov brez prostih tokov ([OsPravila.Zaklep]), osnova -> zapis. */
    private const val PREFS_ZAKLEPI = "safeer_stremio_zaklepi"

    // Urni proracun dodatka ([TempoDodatka]): poizvedbe zadnje ure in meja za preverjanje v ozadju, oboje po dodatku.
    private const val PREFS_TEMPO = "safeer_stremio_tempo"
    private val ure = ConcurrentHashMap<String, TempoDodatka.Ura>()
    private val tempo = ConcurrentHashMap<String, TempoDodatka.Stanje>()
    private val tempoShranjenOb = ConcurrentHashMap<String, Long>()
    /** Dodatki, katerih premor je nas lasten (porabljen urni proracun) in ne omejitev dodatka. */
    private val premorProracuna: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val zaklepPremora = Any()
    @Volatile private var shrambaTempa: android.content.SharedPreferences? = null

    /** Kljuc v shrambi proracuna, ki ni naslov dodatka: kdaj je dodatek dom nazadnje omejil ([premorOb]). */
    private const val KLJUC_OMEJITVE = "#omejitev"

    private fun zapomniOmejitev(zdaj: Long) {
        premorOb = zdaj
        try { shrambaTempa?.edit()?.putString(KLJUC_OMEJITVE, zdaj.toString())?.apply() } catch (_: Exception) { }
    }

    private fun gostitelj(osnova: String) = try { URL(osnova).host } catch (_: Exception) { "?" }

    private fun ura(osnova: String) = ure.getOrPut(osnova) { TempoDodatka.Ura() }

    /** Meja dodatka; vsak dan brez omejitve se nekoliko vrne proti privzeti. */
    private fun stanjeTempa(osnova: String): TempoDodatka.Stanje {
        val s = tempo[osnova] ?: TempoDodatka.Stanje()
        val o = TempoDodatka.okrevaj(s, System.currentTimeMillis())
        if (o != s) { tempo[osnova] = o; shraniTempo(osnova, takoj = true) }
        return o
    }

    private fun stej(osnova: String) {
        ura(osnova).dodaj(System.currentTimeMillis())
        shraniTempo(osnova, takoj = false)
    }

    /** Stevec se spreminja z vsako poizvedbo: v shrambo gre najvec na pol minute, sprememba meje pa takoj. */
    private fun shraniTempo(osnova: String, takoj: Boolean) {
        val p = shrambaTempa ?: return
        val zdaj = System.currentTimeMillis()
        if (!takoj && zdaj - (tempoShranjenOb[osnova] ?: 0L) in 0 until 30_000L) return
        tempoShranjenOb[osnova] = zdaj
        try { p.edit().putString(osnova, (tempo[osnova] ?: TempoDodatka.Stanje()).vNiz() + "|" + ura(osnova).vNiz()).apply() } catch (_: Exception) { }
    }

    /**
     * Ali urni proracun dodatka se dovoli poizvedbo v ozadju. Ce ne, zacne premor, dokler ga ni spet nekaj: seznami
     * kazejo ze preverjene naslove, ostale naprave v domu pocakajo ([premori]), predvajanje pa dela naprej.
     */
    private fun proracun(osnova: String): Boolean {
        val zdaj = System.currentTimeMillis()
        val s = stanjeTempa(osnova)
        val u = ura(osnova)
        val vUri = u.vsota(zdaj)
        if (TempoDodatka.smeVOzadju(s, vUri)) return true
        synchronized(zaklepPremora) {
            if ((premorDo[osnova] ?: 0L) > zdaj) return false
            val trajanje = TempoDodatka.cakajNaProracun(s, u, zdaj)
            premorProracuna.add(osnova)
            premorDo[osnova] = zdaj + trajanje
            try { shrambaPremorov?.edit()?.putLong(osnova, zdaj + trajanje)?.apply() } catch (_: Exception) { }
            try { android.util.Log.i("SafeerStremio", "dodatek ${gostitelj(osnova)}: urni proracun je porabljen ($vUri od ${s.naUro}), preverjanje v ozadju pocaka ${trajanje / 60_000} min") } catch (_: Throwable) { }
        }
        return false
    }

    /** Ali urni proracun dodatkov, ki dajejo tokove, se dovoli delo na zalogo (zadnje na vrsti). Nic ne porabi. */
    fun smeNaZalogo(naslovi: List<String>): Boolean {
        val zdaj = System.currentTimeMillis()
        return naslovi.all { n ->
            val o = osnova(n)
            manifesti[o]?.let { "stream" in it.viri } == false || TempoDodatka.smeNaZalogo(stanjeTempa(o), ura(o).vsota(zdaj))
        }
    }

    /** Najbolj obremenjen dodatek: »poizvedb zadnje ure/meja na uro« - za dnevnik in meritve (brez imen). */
    fun opisProracuna(): String {
        val zdaj = System.currentTimeMillis()
        return ure.entries.maxByOrNull { it.value.vsota(zdaj) }?.let { "${it.value.vsota(zdaj)}/${stanjeTempa(it.key).naUro}" } ?: "0"
    }

    /**
     * Kdaj (System.currentTimeMillis) se preverjanje v ozadju nadaljuje: najzgodnejsi konec premora med dodatki [naslovi],
     * ki dajejo tokove in so v premoru (omejitev poizvedb ali porabljen urni proracun); 0 = noben ni v premoru.
     * Za napis »nadaljujem cez N min« (naloga 55b). Nic ne porabi.
     */
    fun premorKoncaOb(naslovi: List<String>): Long {
        val zdaj = System.currentTimeMillis()
        return naslovi.mapNotNull { n ->
            val o = osnova(n)
            if (manifesti[o]?.let { "stream" in it.viri } == false) null else premorDo[o]?.takeIf { it > zdaj }
        }.minOrNull() ?: 0L
    }

    /** Dodatek je odgovoril, da omejuje poizvedbe: v ozadju ga do konca premora ne sprasujemo. */
    fun vPremoru(naslov: String): Boolean = (premorDo[osnova(naslov)] ?: 0L) > System.currentTimeMillis()

    /** Koliko poizvedb v ozadju (preverjanje, kaj se da predvajati) je ta naprava poslala dodatkom od zagona - za dnevnik in meritve. */
    val poizvedbVOzadju = java.util.concurrent.atomic.AtomicInteger(0)

    /** Kdaj (System.currentTimeMillis) je kateri dodatek nazadnje omejil poizvedbe - tu ali na drugi napravi v Linku. */
    @Volatile var premorOb = 0L
        private set

    /**
     * Kdaj je uporabnik nazadnje zahteval tokove za predvajanje ([tokovi]). Delo na zalogo (preverjevalec doma) po
     * tem nekaj casa pocaka: poizvedbe doma so najprej za tistega, ki zdaj nekaj gleda.
     */
    @Volatile var uporabnikOb = 0L
        private set

    /**
     * Koliko poizvedb v ozadju dodatki ta hip se dovolijo (najmanjse vedro med dodatki, ki dajejo tokove); 0, ce je
     * kateri v premoru. Nic ne porabi - preverjevalec doma po tem presodi, ali sme delati na zalogo.
     */
    fun zetonov(naslovi: List<String>): Double {
        val zdaj = System.currentTimeMillis()
        var najmanj = ZETONI_NAJVEC
        for (n in naslovi) {
            val o = osnova(n)
            if (manifesti[o]?.let { "stream" in it.viri } == false) continue
            if ((premorDo[o] ?: 0L) > zdaj) return 0.0
            val v = vedra[o] ?: continue
            val z = synchronized(v) { (v.zetoni + (zdaj - v.cas).coerceAtLeast(0) / 1000.0 * ZETONI_NA_S).coerceAtMost(ZETONI_NAJVEC) }
            if (z < najmanj) najmanj = z
        }
        return najmanj
    }

    /**
     * Premori, ki se tecejo (osnova dodatka -> koliko ms se). Dodatek omejuje po domacem naslovu, ne po napravi, zato
     * si jih naprave v Linku povedo ([SeznamiSink]): ko dodatek omeji eno, pocakajo vse.
     */
    fun premori(): Map<String, Long> {
        val zdaj = System.currentTimeMillis()
        return premorDo.filterValues { it > zdaj }.mapValues { it.value - zdaj }
    }

    /** Dodatek je omejil drugo napravo v istem domu: tudi ta ga v ozadju ne sprasuje, dokler premor ne mine. */
    fun prevzemiPremor(naslov: String, seMs: Long) {
        if (seMs <= 0L) return
        val o = osnova(naslov)
        val zdaj = System.currentTimeMillis()
        val konec = zdaj + seMs.coerceAtMost(PREMOR_NAJVEC_MS)
        // Minuta razlike ni nov premor (naprave si ga povedo veckrat).
        if ((premorDo[o] ?: 0L) >= konec - 60_000L) return
        premorDo[o] = konec
        zapomniOmejitev(zdaj)
        try { shrambaPremorov?.edit()?.putLong(o, konec)?.apply() } catch (_: Exception) { }
        try { android.util.Log.i("SafeerStremio", "dodatek ${try { URL(o).host } catch (_: Exception) { "?" }} omejuje poizvedbe (sporocila naprava v Linku): premor ${(konec - zdaj) / 60_000} min") } catch (_: Throwable) { }
    }

    /** Dodatek je odgovoril, da omejuje poizvedbe (429, 403). Klic iz niti, ki je odgovor prejela (koda in glave). */
    private fun zacniPremor(osnova: String) {
        val koda = zadnjaKoda.get() ?: 0
        val glave = zadnjeGlave.get().orEmpty()
        synchronized(zaklepPremora) {
            val zdaj = System.currentTimeMillis()
            // Vec hkratnih odgovorov je en premor; nas lasten premor (porabljen proracun) pa omejitve dodatka ne skrije.
            val lasten = premorProracuna.remove(osnova)
            if ((premorDo[osnova] ?: 0L) > zdaj && !lasten) return
            val korak = (premorKorak[osnova] ?: 0).coerceAtMost(3)
            premorKorak[osnova] = korak + 1
            // Ce dodatek pove, koliko naj pocakamo, velja njegova beseda; sicer 15 min in ob ponovitvi dvakrat dlje.
            val trajanje = TempoDodatka.cakajPoGlavah(glave, zdaj) ?: (PREMOR_MS shl korak).coerceAtMost(PREMOR_NAJVEC_MS)
            premorDo[osnova] = zdaj + trajanje
            zapomniOmejitev(zdaj)
            // Meja je nizja, kot smo mislili: nova je polovica tega, kar je naprava v zadnji uri res poslala.
            val poslanih = ura(osnova).vsota(zdaj)
            val novo = TempoDodatka.poOmejitvi(stanjeTempa(osnova), poslanih, zdaj)
            tempo[osnova] = novo
            shraniTempo(osnova, takoj = true)
            try { shrambaPremorov?.edit()?.putLong(osnova, zdaj + trajanje)?.apply() } catch (_: Exception) { }
            try {
                val opis = TempoDodatka.opisGlav(glave).let { if (it.isEmpty()) "" else "; $it" }
                android.util.Log.i("SafeerStremio", "dodatek ${gostitelj(osnova)} omejuje poizvedbe: premor ${trajanje / 60_000} min (koda $koda, v zadnji uri $poslanih poizvedb, nova meja ${novo.naUro} na uro$opis)")
            } catch (_: Throwable) { }
        }
    }

    /**
     * Pocaka na zeton za poizvedbo v ozadju pri dodatku; false = dodatek je v premoru, nit je prekinjena ali vprasanje
     * ne velja vec ([seVelja]: uporabnik je odsel z zaslona) - takrat zetona ne vzame.
     */
    private fun zeton(osnova: String, seVelja: () -> Boolean = { true }): Boolean {
        // Dodatek, ki ne odgovarja (naloga 57d): v ozadju ga preskocimo, zetona ne porabimo.
        if (!smemVprasati(osnova)) return false
        val v = vedra.getOrPut(osnova) { Vedro() }
        while (true) {
            if ((premorDo[osnova] ?: 0L) > System.currentTimeMillis() || !seVelja()) return false
            if (!proracun(osnova)) return false
            val cakaj = synchronized(v) {
                val zdaj = System.currentTimeMillis()
                v.zetoni = (v.zetoni + (zdaj - v.cas).coerceAtLeast(0) / 1000.0 * ZETONI_NA_S).coerceAtMost(ZETONI_NAJVEC)
                v.cas = zdaj
                if (v.zetoni >= 1.0) { v.zetoni -= 1.0; 0L } else ((1.0 - v.zetoni) / ZETONI_NA_S * 1000).toLong() + 5
            }
            if (cakaj == 0L) return true
            // Po kosih: cakajoce vprasanje, ki ne velja vec, odneha v sekundi (in ne sele, ko bi prislo na vrsto).
            try { Thread.sleep(minOf(cakaj, 1_000L)) } catch (_: InterruptedException) { return false }
        }
    }

    /** Poizvedba ni bila poslana (uporabnik je medtem odsel): zeton gre nazaj v vedro. */
    private fun vrniZeton(osnova: String) {
        val v = vedra[osnova] ?: return
        synchronized(v) { v.zetoni = (v.zetoni + 1.0).coerceAtMost(ZETONI_NAJVEC) }
    }

    /**
     * Ali so vsi dodatki, ki bi jih za ta naslov vprasali po tokovih (tip in predpona id-ja), v premoru - naslova zdaj
     * ni mogoce preveriti. Brez omrezja: uposteva manifeste, ki jih ze poznamo.
     */
    fun vprasaniVPremoru(naslovi: List<String>, tip0: String, id: String): Boolean {
        // Naloga 77: isti tip kot pri predvajanju (koncert »music« kot »movie«).
        val tip = OsPravila.tipToka(tip0, id, naslovi.mapNotNull { manifesti[osnova(it)] }
            .any { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip0 in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) })
        val d = naslovi.mapNotNull { manifesti[osnova(it)] }
            .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        return d.isNotEmpty() && d.all { vPremoru(it.osnova) }
    }

    /** Ali dodatek daje tokove (vir "stream"); null, dokler njegovega manifesta ne poznamo. Brez omrezja. */
    fun dajeTokove(naslov: String): Boolean? = manifesti[osnova(naslov)]?.let { "stream" in it.viri }

    /** Kaj tok zahteva od naprave ([RazpolozljivostPravila.potreba]): 0 = neposreden, velikost torrenta ali »vsak torrent«. */
    private fun potreba(t: Tok): Long? = RazpolozljivostPravila.potreba(t.vrsta == "url", t.vrsta == "torrent",
        if (t.vrsta == "torrent") TokIzbira.opisi(t.ime + " " + t.opis).gb else 0.0)

    /**
     * Kaj dodatki ponujajo za ta naslov, kot meji glede na to, kaj naprava zmore ([RazpolozljivostPravila.Izid]) - isti
     * odgovor zato velja za vsako napravo in vsak nacin. null = ne vemo (kateri dodatek ni odgovoril in nobeden nima
     * nicesar) - takrat nicesar ne sklepamo. [torrent]: kateri torrent steje na tej napravi ([torrentGre]); prvi
     * dodatek s tokom, ki ga ta naprava zmore, zadosca. [seVelja]: preverjanje v ozadju velja le, dokler uporabnik
     * gleda seznam - ko ne velja vec, dodatka ne vprasamo in vrnemo null (klicatelj iz tega ne sklepa nicesar).
     */
    fun razpolozljivo(naslovi: List<String>, tip0: String, id: String, torrent: Long, seVelja: () -> Boolean = { true }): RazpolozljivostPravila.Izid? {
        if (naslovi.isEmpty() || !seVelja()) return null
        // Naloga 77: isti tip kot pri predvajanju (koncert »music« kot »movie«).
        val tip = tipToka(naslovi, tip0, id)
        val manifestiDodatkov = naslovi.map { manifest(it) }
        // Dodatek, ki ga trenutno ne dosezemo (brez omrezja), bi vsebino morda imel: ne sklepamo "ni na voljo".
        var neznano = manifestiDodatkov.any { it == null }
        val vsiDodatki = manifestiDodatkov.filterNotNull()
            .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        // Vljudnost do dodatkov: preverjanje tece v ozadju, zato vsak dodatek dobi najvec nekaj poizvedb na sekundo
        // (zeton), dodatka, ki je odgovoril, da poizvedbe omejuje, pa nekaj casa sploh ne sprasujemo (premor).
        val dodatki = vsiDodatki.filter { zeton(it.osnova, seVelja) }
        // Med cakanjem na zeton je uporabnik odsel (zaprl seznam, zacel film, Domov): ne vprasamo nicesar - poizvedbe
        // doma so za tistega, ki zdaj nekaj gleda ali isce. Neporabljeni zetoni gredo nazaj.
        if (!seVelja()) { dodatki.forEach { vrniZeton(it.osnova) }; return null }
        if (dodatki.size != vsiDodatki.size) neznano = true
        // Vsak dodatek vrne potrebe svojih tokov (prazno = odgovoril je, a nima nicesar); null = ni odgovoril.
        val niti = dodatki.map { m -> bazen.submit<List<Long>?> {
            poizvedbVOzadju.incrementAndGet()
            // Brez odgovora ne vemo nicesar; odgovor "tega nimam" (404) pa je odgovor - dodatek vsebine nima.
            val d = vOzadju { json("${m.osnova}/stream/${enc(tip)}/${enc(id)}.json") }
                ?: return@submit run {
                    val koda = zadnjaKoda.get() ?: 0
                    if (koda == 429 || koda == 403) zacniPremor(m.osnova)
                    if (kodaPomeniNima(koda)) emptyList() else null
                }
            premorKorak.remove(m.osnova)
            val a = d.optJSONArray("streams") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { tok(it, m.ime) } }.mapNotNull { potreba(it) }
        } }
        fun odgovori() = niti.map { f -> if (f.isDone) (try { f.get() } catch (_: Exception) { null }) else null }
        // Prvi dodatek s tokom, ki ga ta naprava zmore, zadosca (na pocasne ne cakamo); "ni" velja sele, ko so odgovorili vsi.
        val rok = System.currentTimeMillis() + 15_000
        while (true) {
            val o = odgovori()
            val potrebe = o.filterNotNull().flatten()
            if (potrebe.any { it <= torrent }) return RazpolozljivostPravila.izTokov(potrebe, vsiOdgovorili = !neznano && o.none { it == null })
            if (niti.all { it.isDone } || System.currentTimeMillis() > rok) break
            try { Thread.sleep(40) } catch (_: InterruptedException) { return null }
        }
        val o = odgovori()
        return RazpolozljivostPravila.izTokov(o.filterNotNull().flatten(), vsiOdgovorili = !neznano && o.none { it == null })
    }

    /**
     * Ali ima kanal pri SVOJEM dodatku tok, ki ga lahko predvajamo: true / false; null = ne vemo (dodatek tokov ne daje,
     * ni odgovoril ali poizvedbe omejuje - takrat nehamo sprasevati, [vPremoru]). Preverjanje mreze kanalov v ozadju
     * vprasa samo dodatek, iz katerega je kartica; drugih dodatkov ne obremenjuje. Odgovor ostane v predpomnilniku
     * ([veljavnost]), zato dotik preverjenega kanala ne poslje novega vprasanja.
     */
    /** Ali kanal pri njegovem dodatku sploh lahko preverimo: dodatek sam daje tokove (ni samo katalog). */
    fun dajeLastneTokove(s: Jamendo.Skladba): Boolean {
        val osnova = razstavi(s)?.first ?: return false
        return manifesti[osnova]?.let { "stream" in it.viri } == true
    }

    fun imaLastenTok(s: Jamendo.Skladba, sonda: ((Tok) -> Boolean?)? = null): Boolean? {
        val (osnova, tip, id) = razstavi(s) ?: return null
        val m = manifesti[osnova] ?: return null
        if ("stream" !in m.viri || vPremoru(osnova) || !smemVprasati(osnova)) return null
        poizvedbVOzadju.incrementAndGet()
        val d = vOzadju { json("$osnova/stream/${enc(tip)}/${enc(id)}.json") }
        if (d == null) {
            val koda = zadnjaKoda.get() ?: 0
            if (dnevnik()) android.util.Log.d(DNEVNIK, "${oznakaDodatka(osnova)} tok kanala: brez odgovora, koda=$koda")
            if (koda == 429 || koda == 403) zacniPremor(osnova)
            return if (kodaPomeniNima(koda)) false else null
        }
        val a = d.optJSONArray("streams")
        val tokovi = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { tok(it, m.ime) } }.filter { jePredvajljiv(it) }
        if (razred(tip) == TV) zabeleziTokove(osnova, a, tokovi.size)
        if (dnevnik() && tokovi.isEmpty()) android.util.Log.d(DNEVNIK, "${oznakaDodatka(osnova)} tok kanala: tokov=${a?.length() ?: -1}, predvajljivih=0, " +
            "polja odgovora=${d.keys().asSequence().toList()}, oblika prvega=${a?.optJSONObject(0)?.let { oblikaToka(it) } ?: "-"}, " +
            "besedilo prvega=${a?.optJSONObject(0)?.let { (it.optString("name") + " | " + it.optString("title").ifBlank { it.optString("description") }).replace('\n', ' ').take(160) } ?: "-"}")
        // Naloga 79: samo obvestila (externalUrl »Unlock Premium« ...) niso dokaz, da kanala ni - zaklenjen je dodatek, ne tok.
        if (tokovi.isEmpty() && (a?.length() ?: 0) > 0) return null
        if (tokovi.isEmpty()) return false
        // Dodatek tok nasteje, streznik toka pa s te naprave morda ne odgovori ([SondaToka]): vprasamo prva neposredna
        // tokova (tista, ki bi ju predvajali; drugi je rezerva). Eden odgovori -> kanal je; oba dokazano ne -> ni ga.
        val neposredni = tokovi.filter { it.vrsta == "url" }.take(2)
        if (sonda == null || neposredni.isEmpty()) return true
        var neznano = false
        for (t in neposredni) when (sonda(t)) { true -> return true; null -> neznano = true; false -> { } }
        return if (neznano) null else false
    }

    // ------------------------------------------------------------------ podnapisi (naloga 72)

    /** Dodatek daje podnapise za ta tip in id (vir »subtitles«, tip, predpona). */
    private fun dajePodnapise(m: Manifest, tip: String, id: String) =
        "subtitles" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) })

    /**
     * Ali kateri od uporabnikovih dodatkov daje podnapise za ta naslov - brez omrezja, iz znanih manifestov (predvajanje
     * filma iz dodatka jih je prebralo, [tokovi]). Brez takega dodatka Safeer podnapisov ne isce (nobene zahteve).
     */
    fun imaPodnapise(naslovi: List<String>, tip: String, id: String): Boolean =
        naslovi.mapNotNull { manifesti[osnova(it)] }.any { dajePodnapise(it, tip, id) }

    data class PodnapisDodatka(val url: String, val jezik: String, val dodatek: String)

    /**
     * Naloga 72: podnapisi naslova iz uporabnikovih dodatkov (OpenSubtitles v3 ...): GET {osnova}/subtitles/{tip}/{id}.json,
     * odgovor {subtitles: [{id, url, lang}]} ([OsPravila.podnapisiDodatka]). Vsak dodatek najvec enkrat na uro (predpomnilnik),
     * z zetonom in urnim proracunom kot preverjanje ([zeton], [TempoDodatka]); dodatek v premoru ali brez odgovora preskocimo.
     * Klic iz niti.
     */
    fun podnapisi(naslovi: List<String>, tip: String, id: String): List<PodnapisDodatka> {
        val dodatki = naslovi.mapNotNull { manifesti[osnova(it)] }.filter { dajePodnapise(it, tip, id) }
        return dodatki.flatMap { m ->
            val url = "${m.osnova}/subtitles/${enc(tip)}/${enc(id)}.json"
            // Ze prebran odgovor (1 h) ne porabi zetona; nov gre skozi vljudnost do dodatka.
            if (odgovori[url] == null && !zeton(m.osnova)) return@flatMap emptyList()
            val d = json(url) ?: run {
                val koda = zadnjaKoda.get() ?: 0
                if (koda == 429 || koda == 403) zacniPremor(m.osnova)
                return@flatMap emptyList()
            }
            val a = d.optJSONArray("subtitles") ?: return@flatMap emptyList()
            val vnosi = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("url") to it.optString("lang") } }
            OsPravila.podnapisiDodatka(vnosi).map { PodnapisDodatka(it.url, it.jezik, m.ime) }
        }
    }

    /** Tokovi za film ali epizodo iz vseh dodatkov, ki ponujajo vir "stream" za ta tip in predpono id-ja. */
    fun tokovi(naslovi: List<String>, tip: String, id: String): List<Tok> = tokoviZOdzivom(naslovi, tip, id).tokovi

    /**
     * Pregled B1: tokovi in kaj so dodatki odgovorili - [placilnih] obvestil placilnega zidu, [odgovorili] = vsak dodatek je
     * dal odgovor (tudi »tega nimam«). Placljivo NE zapise: to odloci klicatelj enkrat po vseh dodatkih ([zabeleziPlacljivoPoVseh]).
     */
    class OdgovorTokov(val tokovi: List<Tok>, val placilnih: Int, val odgovorili: Boolean)

    fun tokoviZOdzivom(naslovi: List<String>, tip: String, id: String): OdgovorTokov {
        val placilnih = java.util.concurrent.atomic.AtomicInteger(0)
        val brezOdgovora = java.util.concurrent.atomic.AtomicBoolean(false)
        val vsi = tokoviDodatkov(naslovi, tip, id, placilnih, brezOdgovora)
        return OdgovorTokov(vsi, placilnih.get(), !brezOdgovora.get())
    }

    /**
     * Pregled B1: placljivo za vsebino enkrat, po odgovorih VSEH dodatkov ([OsPravila.placljivoPoVsehOdgovorih]) - prej je
     * odlocil zadnji odgovor posameznega dodatka in film s prostimi tokovi je izginil za 7 dni.
     */
    fun zabeleziPlacljivoPoVseh(tip: String, id: String, placilnih: Int, predvajljivih: Int, vsiOdgovorili: Boolean) {
        val k = "$tip|$id"
        val prej = placljivo[k]
        val novo = OsPravila.placljivoPoVsehOdgovorih(prej, placilnih, predvajljivih, vsiOdgovorili, System.currentTimeMillis())
        if (novo == prej) return
        if (novo == null) placljivo.remove(k) else placljivo[k] = novo
        try { shrambaZaklepov?.edit()?.apply { if (novo == null) remove(PREDPONA_PLACLJIVO + k) else putLong(PREDPONA_PLACLJIVO + k, novo) }?.apply() } catch (_: Exception) { }
    }

    private fun tokoviDodatkov(naslovi: List<String>, tip: String, id: String, placilnih: java.util.concurrent.atomic.AtomicInteger,
                               brezOdgovora: java.util.concurrent.atomic.AtomicBoolean): List<Tok> =
        naslovi.also { uporabnikOb = System.currentTimeMillis() }
        // Pregled S5: dodatek s poverilnico, ki ga ta naprava ne zna odkleniti, ni »izpad« - samo ga ne vprasamo.
        .filter { SkrivniDodatki.pozna(aplikacija, it) }
        // Dodatek, ki ga ne dosezemo (manifesta ni), bi vsebino morda imel: to je izpad, ne "ni na voljo".
        .mapNotNull { manifest(it) ?: run { zadnjiIzpad.set(System.currentTimeMillis()); brezOdgovora.set(true); null } }
        .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) &&
            (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        .flatMap { m ->
            val naslov = "${m.osnova}/stream/${enc(tip)}/${enc(id)}.json"
            var d = json(naslov)
            if (d == null && !kodaPomeniNima(zadnjaKoda.get() ?: 0)) {
                // Dodatek ni odgovoril (omejuje poizvedbe, napaka streznika, omrezje). Uporabnik caka na film: preverjanje
                // v ozadju ustavimo (premor) in poskusimo se enkrat; ce spet nic, to zabelezimo kot izpad.
                val koda = zadnjaKoda.get() ?: 0
                if (koda == 429 || koda == 403) zacniPremor(m.osnova)
                try { Thread.sleep(1_200) } catch (_: InterruptedException) { brezOdgovora.set(true); return@flatMap emptyList<Tok>() }
                d = json(naslov)
                if (d == null && !kodaPomeniNima(zadnjaKoda.get() ?: 0)) {
                    zadnjiIzpad.set(System.currentTimeMillis())
                    brezOdgovora.set(true)
                    try { android.util.Log.i("SafeerStremio", "dodatek ${try { URL(m.osnova).host } catch (_: Exception) { "?" }} ni dal tokov: koda ${zadnjaKoda.get() ?: 0}") } catch (_: Throwable) { }
                }
            }
            val a = d?.optJSONArray("streams") ?: JSONArray()
            // Naloga 120: vnosi, ki niso tok, ampak placilni zid (»Unlock Premium« ...).
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                val t = tok(o, m.ime)
                if ((t == null || !jePredvajljiv(t)) && OsPravila.jePlacljivoObvestilo(listOf(o.optString("name"), o.optString("title"),
                        o.optString("description"), o.optString("externalUrl")).joinToString(" "))) placilnih.incrementAndGet()
            }
            if (dnevnik() && !m.zaseben) {
                android.util.Log.d(DNEVNIK, "${oznakaDodatka(m.osnova)} tokovi $tip: odgovor=${d != null} koda=${zadnjaKoda.get() ?: 0} stevilo=${a.length()}")
                for (i in 0 until minOf(a.length(), 8)) a.optJSONObject(i)?.let { android.util.Log.d(DNEVNIK, "${oznakaDodatka(m.osnova)}   tok $i: ${oblikaToka(it)}") }
            }
            // Naloga 81: oblika toka pri prvem predvajanju vsebine dodatka, ki ni film ali serija (YouTubio: url ali ytId?) - enkrat
            // na dodatek in tip v procesu, tudi brez dnevnika razvijalca (samo oblika, brez naslova toka).
            if (tip != "movie" && tip != "series" && a.length() > 0 && zapisaneOblike.add(m.osnova + "|" + tip)) try {
                android.util.Log.i("SafeerStremio", "dodatek ${gostitelj(m.osnova)} tok $tip: stevilo=${a.length()}, prvi: ${a.optJSONObject(0)?.let { oblikaToka(it) } ?: "-"}")
            } catch (_: Throwable) { }
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> tok(o, m.ime)?.let { prilagodi(it, o, m.osnova, m.id) } } }
                .also { t -> if (d != null && razred(tip) == TV) zabeleziTokove(m.osnova, a, t.count { jePredvajljiv(it) }) }
        }

    /**
     * Naloga 79: odgovor dodatka po tokovih za kanal ([OsPravila.poOdgovoruTokov]). Dodatek, ki za [OsPravila.ZAKLENJEN_PO]
     * zaporednih kanalov vrne samo obvestila, je »brez prostih tokov« (na disku, [OsPravila.ZAKLENJEN_VELJA_MS]).
     */
    // Naloga 86 (K1): preberi-spremeni-zapisi je nedeljivo - preverjanja mreze (3) in pregled (2) tecejo hkrati.
    @Synchronized private fun zabeleziTokove(osnova: String, streams: JSONArray?, predvajljivih: Int) {
        val vnosov = streams?.length() ?: 0
        // Naloga 79b: povezava za odklep (externalUrl obvestila) - samo varna, samo gostitelj in pot, ne v dnevnik.
        if (predvajljivih == 0 && vnosov > 0) (0 until vnosov).firstNotNullOfOrNull { i -> OsPravila.povezavaOdklepa(streams?.optJSONObject(i)?.optString("externalUrl")) }
            ?.let { p -> if (odklepi[osnova] != p) { odklepi[osnova] = p; try { shrambaZaklepov?.edit()?.putString(PREDPONA_ODKLEPA + osnova, p)?.apply() } catch (_: Exception) { } } }
        val zdaj = System.currentTimeMillis()
        val prej = zaklepi[osnova]
        val novo = OsPravila.poOdgovoruTokov(prej ?: OsPravila.Zaklep(), vnosov, predvajljivih, zdaj)
        if (novo == (prej ?: OsPravila.Zaklep())) return
        if (novo == OsPravila.Zaklep()) zaklepi.remove(osnova) else zaklepi[osnova] = novo
        try {
            shrambaZaklepov?.edit()?.apply { if (novo == OsPravila.Zaklep()) remove(osnova) else putString(osnova, novo.vNiz()) }?.apply()
        } catch (_: Exception) { }
        if (OsPravila.jeZaklenjen(novo, zdaj) != OsPravila.jeZaklenjen(prej, zdaj)) try {
            android.util.Log.i("SafeerStremio", "dodatek ${gostitelj(osnova)}: ${if (OsPravila.jeZaklenjen(novo, zdaj)) "tokovi samo z narocnino (${novo.zaporednih} kanalov zapored samo obvestila)" else "spet daje tokove"}")
        } catch (_: Throwable) { }
    }

    /** Naloga 79: dodatek kanalov ta cas daje tokove samo z narocnino (sklep velja). Brez omrezja. */
    fun brezProstihTokov(naslov: String): Boolean = OsPravila.jeZaklenjen(zaklepi[osnova(naslov)], System.currentTimeMillis())

    /** Naloga 79: kanal je iz dodatka brez prostih tokov. */
    fun brezProstihTokovKanala(s: Jamendo.Skladba): Boolean = razstavi(s)?.first?.let { brezProstihTokov(it) } == true

    /** Naloga 82: dodatek je za mrezo TV v zivo zaklenjen ([OsPravila.zaklenjenZaMrezo]) - tudi po izteku sklepa brez zivega toka. */
    fun zaklenjenZaMrezo(naslov: String): Boolean = OsPravila.zaklenjenZaMrezo(zaklepi[osnova(naslov)], System.currentTimeMillis())

    /** Naloga 82: kanal je iz dodatka, ki je za mrezo zaklenjen ([zaklenjenZaMrezo]). */
    fun zaklenjenKanalZaMrezo(s: Jamendo.Skladba): Boolean = razstavi(s)?.first?.let { zaklenjenZaMrezo(it) } == true

    /**
     * Naloga 90b: en poskus prvega kanala dodatka (klic z delovne niti). Predvajljiv tok pobrise zapis »brez prostih tokov«
     * ([zabeleziTokove] - uporabnik je dodatku placal); obvestilo (externalUrl) ga pusti. Vrne true, ce dodatek zdaj ni vec zaklenjen.
     */
    fun poskusiOdklep(naslov: String): Boolean {
        val k = katalogiTv(listOf(naslov)).firstOrNull() ?: return !zaklenjenZaMrezo(naslov)
        val (_, tip, id) = katalog(k).firstNotNullOfOrNull { razstavi(it) } ?: return !zaklenjenZaMrezo(naslov)
        tokovi(listOf(naslov), tip, id)
        // Naloga 120: dodatek je odklenjen (uporabnik je placal) - tudi njegova placljiva vsebina se pokaze znova.
        if (!zaklenjenZaMrezo(naslov)) { pozabiPlacljivo(); return true }
        return false
    }

    /** Naloga 79: zadnji odgovor dodatka za kanal je imel samo obvestila (tudi pred sklepom) - kanal ni mrtev. */
    fun samoObvestilaKanala(s: Jamendo.Skladba): Boolean = razstavi(s)?.first?.let { (zaklepi[it]?.zaporednih ?: 0) > 0 } == true

    /** Naloga 79: rocni pregled (»Preveri kanale zdaj«) sklep pozabi - dodatek se preveri znova. */
    fun pozabiZaklep(naslov: String) {
        val o = osnova(naslov)
        val bil = zaklepi.remove(o) != null
        val povezava = odklepi.remove(o) != null
        // Naloga 120: po rocnem pregledu se placljiva vsebina preveri znova (ne vemo, katera je bila tega dodatka).
        pozabiPlacljivo()
        if (bil || povezava) try { shrambaZaklepov?.edit()?.remove(o)?.remove(PREDPONA_ODKLEPA + o)?.apply() } catch (_: Exception) { }
    }

    /** Naloga 120: vsa placljiva vsebina se pokaze in preveri znova (rocni pregled, odklenjen dodatek). */
    private fun pozabiPlacljivo() {
        if (placljivo.isEmpty()) return
        try { val e = shrambaZaklepov?.edit(); placljivo.keys.forEach { e?.remove(PREDPONA_PLACLJIVO + it) }; e?.apply() } catch (_: Exception) { }
        placljivo.clear()
    }

    /**
     * Naloga 120: vsebine dodatka, ki je uporabnik nima odklenjene, ne kazemo: dodatki zanjo dajejo samo placilni zid
     * ([OsPravila.placljivoSkrito]) ali je kanal iz dodatka, ki je za mrezo zaklenjen (79/82). [pokaziZaklenjene] je
     * uporabnikova izbira »Pokazi zaklenjene« v TV v zivo.
     */
    fun neodklenjena(s: Jamendo.Skladba, pokaziZaklenjene: Boolean = false): Boolean {
        if (pokaziZaklenjene) return false
        val (_, tip, id) = razstavi(s) ?: return false
        return OsPravila.placljivoSkrito(placljivo["$tip|$id"], System.currentTimeMillis(), false) ||
            (jeVZivo(s) && zaklenjenKanalZaMrezo(s))
    }

    /** Naloga 79b: povezava za odklep pri dodatku ([OsPravila.povezavaOdklepa]); null, ce je dodatek ni dal. */
    fun povezavaOdklepa(naslov: String): String? = odklepi[osnova(naslov)]

    /** Naloga 79b: id manifesta dodatka (iz predpomnilnika); "" = neznan. */
    fun idManifesta(naslov: String): String = manifesti[osnova(naslov)]?.id.orEmpty()

    /**
     * Kdaj (System.currentTimeMillis) je poizvedba po tokovih za PREDVAJANJE nazadnje ostala brez odgovora dodatka
     * (omejitev poizvedb, napaka streznika, omrezje). Prazen seznam tokov takrat ne pomeni "vsebine ni": uporabniku
     * povemo, da dodatek ne odgovarja, naslov pa ostane na zaslonu.
     */
    val zadnjiIzpad = java.util.concurrent.atomic.AtomicLong(0L)

    /** Ali je zadnja poizvedba po tokovih (v zadnje pol minute) ostala brez odgovora dodatka. */
    fun dodatekNiOdgovoril(): Boolean = System.currentTimeMillis() - zadnjiIzpad.get() < 30_000
}
