package si.safeer.tv.os

import java.io.File

/**
 * Naloga 134: skupni preizkusni primeri za Android in namizje (tests/skupni-primeri/debrid-serije.json). Ista datoteka je
 * v repozitorijih Safeer-linux in Safeer-windows (tests/test_skupni_primeri.py) - pravilo, spremenjeno samo na eni
 * strani, tu ali tam pade. Testi s kotlinc nimajo knjiznice JSON, zato je tu majhen bralnik.
 */
private var napak = 0
private var primerov = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    primerov++
    if (pricakovano == dobljeno) return
    napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno")
}

/** Majhen bralnik JSON: objekt -> Map, seznam -> List, stevilo -> Long ali Double, null -> null. */
private class Bralnik(private val s: String) {
    private var i = 0
    fun beri(): Any? { presledki(); val v = vrednost(); presledki(); return v }
    private fun presledki() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun vrednost(): Any? {
        presledki()
        return when (val c = s[i]) {
            '{' -> objekt(); '[' -> seznam(); '"' -> niz()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> if (c == '-' || c.isDigit()) stevilo() else error("nepricakovan znak '$c' na $i")
        }
    }
    private fun objekt(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++; presledki()
        if (s[i] == '}') { i++; return m }
        while (true) {
            presledki(); val k = niz(); presledki(); i++ // ':'
            m[k] = vrednost(); presledki()
            if (s[i++] == '}') return m
        }
    }
    private fun seznam(): List<Any?> {
        val l = ArrayList<Any?>(); i++; presledki()
        if (s[i] == ']') { i++; return l }
        while (true) { l += vrednost(); presledki(); if (s[i++] == ']') return l }
    }
    private fun niz(): String {
        val sb = StringBuilder(); i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (val e = s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(s[i])
            i++
        }
        i++; return sb.toString()
    }
    private fun stevilo(): Any {
        val z = i; i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(z, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.niz(k: String) = this[k] as String?
private fun Map<String, Any?>.cel(k: String) = (this[k] as Long?)?.toInt()
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.nizi(k: String) = this[k] as List<String?>
/** Bajti iz zapisa hex (prvi bajti toka v skupnih primerih). */
private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Izid ali stanje kljuca v skupnih primerih: ime z malimi crkami (»ne_velja«, »nepreverjen«). */
private fun imeIzida(e: Enum<*>) = e.name.lowercase(java.util.Locale.ROOT)
private fun preverbaIz(s: String) = DebridPravila.Preverba.valueOf(s.uppercase(java.util.Locale.ROOT))
private fun stanjeIz(s: String) = DebridPravila.StanjeKljuca.valueOf(s.uppercase(java.util.Locale.ROOT))

private fun meritev(m: Map<String, Any?>) =
    DebridPravila.Meritev(m.niz("ponudnik")!!, m["ping_ms"] as Long, m["bajtov"] as Long, m["cas_ms"] as Long)

/** Izracun pravila za primer - imena funkcij so skupna (Python), tu preslikana v Kotlin. */
@Suppress("UNCHECKED_CAST")
private fun izracunaj(f: String, v: Map<String, Any?>): Any? = when (f) {
    "vrstni_red" -> DebridPravila.vrstniRed(v.nizi("z_kljucem").filterNotNull().toSet(), v.niz("prednostna"))
    "aktivna" -> DebridPravila.aktivna(v.nizi("z_kljucem").filterNotNull().toSet(), v.niz("prednostna"), v["izklopljeno"] as Boolean)
    "ocisti_kljuc" -> DebridPravila.ocistiKljuc(v.niz("vnos")!!)
    "razlog_za_uporabnika" -> DebridPravila.razlogZaUporabnika(v.nizi("napake").filterNotNull())
    "poskusi_s_cakanjem" -> DebridPravila.poskusiSCakanjem(v["samo_predpomnjeno"] as Boolean, v.nizi("napake").filterNotNull())
    "rd_vrsta_napake" -> DebridPravila.rdVrstaNapake(v.cel("http")!!, v.cel("koda"))
    "ad_vrsta_napake" -> DebridPravila.adVrstaNapake(v.cel("http")!!, v.niz("koda"))
    "torbox_vrsta_napake" -> TorBoxPravila.vrstaNapake(v.cel("http")!!, v.niz("koda"))
    "hashi_za_preverbo" -> DebridPravila.hashiZaPreverbo(v.nizi("hashi"))
    "predpomnjeni_naprej" -> DebridPravila.predpomnjeniNaprej(v.nizi("hashi"), v.nizi("predpomnjeni").filterNotNull().toSet()).map { it.toLong() }
    "samodejni_torrent" -> DebridPravila.samodejniTorrent(v.niz("aktivna"), v.nizi("hashi"), v.nizi("predpomnjeni").filterNotNull().toSet()).toLong()
    "najhitrejsa" -> DebridPravila.najhitrejsa((v["meritve"] as List<Map<String, Any?>>).map { meritev(it) })
    "opis_meritve" -> DebridPravila.opisMeritve(meritev(v))
    "izberi_datoteko" -> TorBoxPravila.izberiDatoteko((v["datoteke"] as List<Map<String, Any?>>).map {
        TorBoxPravila.Datoteka(it["id"] as Long, it.niz("ime")!!, it["velikost"] as Long, "") }, v.cel("indeks") ?: -1)?.ime
    "je_epizoda" -> SerijePravila.jeEpizoda(v.niz("id")!!)
    "id_epizode_imdb" -> SerijePravila.idEpizodePoImdb(v.niz("imdb"), v.niz("id_epizode")!!, v.cel("sezona")!!, v.cel("epizoda")!!)
    "imdb_iz_meta" -> SerijePravila.imdbIzMeta(v.niz("imdb_polje"), v.niz("id")!!)
    // Webtor rezerva in usklajevanje kljucev (tests/skupni-primeri/webtor-rezerva.json).
    "skrivna_predpona_dodatka" -> SkrivnostiPravila.skrivnaPredponaDodatka(v.niz("naslov")!!)
    "skrivna_predpona_toka" -> SkrivnostiPravila.skrivnaPredponaToka(v.niz("naslov")!!)
    "v_nadomestek" -> SkrivnostiPravila.vNadomestek(v.niz("naslov")!!)
    "za_prikaz" -> SkrivnostiPravila.zaPrikaz(v.niz("naslov")!!)
    "odstrani_tok_ob_shranjevanju" -> SkrivnostiPravila.odstraniTokObShranjevanju(v.niz("osnova")!!, v.niz("tok")!!)
    "webtor_oznaka" -> WebtorPravila.razcleni(v.niz("ime")!!).let { listOf(it.vrsta.name, it.ime) }
    "je_webtor_dodatek" -> WebtorPravila.jeWebtorDodatek(v.niz("id"), v.niz("osnova")!!)
    "webtor_nauceni_gostitelj" -> WebtorPravila.naucenGostitelj(v.niz("zacetni")!!, v.niz("koncni")!!, v.cel("koda")!!)
    "webtor_odlocitev" -> WebtorPravila.odlocitev(v.cel("koda")!!, v["prvi_bajt_ms"] as Long?, v["potekel"] as Boolean,
        v.niz("vsebina")?.let { WebtorPravila.Vsebina.valueOf(it) }).name
    "webtor_vrstni_red" -> WebtorPravila.vrstniRed(
        (v["nasi"] as List<List<Any?>?>).map { k -> k?.let { WebtorPravila.kljuc(it[0] as String?, (it[1] as Long).toInt()) } },
        (v["webtor"] as List<List<Any?>>).map { WebtorPravila.kljuc(it[0] as String?, (it[1] as Long).toInt()) to WebtorPravila.vrstaIz(it[2] as String) },
        v["imamo_torbox"] as Boolean, v["samo_takoj"] as Boolean? ?: false,
        (v["datoteke"] as List<String>?)?.map { WebtorPravila.Datoteka.valueOf(it) }).map { it.toLong() }
    // Verzija 3 (25. 10. 2026 popoldne): datoteka toka Webtor iz JWT, pregled vsebine sonde.
    "webtor_kljuc_toka" -> WebtorPravila.kljucWebtor(v.niz("hash"), v.niz("url")!!)?.let { listOf(it.hash, it.idx.toLong()) }
    "webtor_je_video_ime" -> WebtorPravila.jeVideoIme(v.niz("ime")!!)
    "webtor_datoteka" -> WebtorPravila.datoteka(v.cel("idx")!!, v.niz("ime_toka")!!, v.cel("nas_idx"), v.niz("nas_ime")!!).name
    "webtor_posoda" -> WebtorPravila.posoda(hex(v.niz("glava_hex")!!)).name
    "webtor_avi_kodek" -> WebtorPravila.aviKodek(hex(v.niz("glava_hex")!!))
    "webtor_pregled" -> WebtorPravila.pregled(v.niz("vrsta"), hex(v.niz("glava_hex")!!), v["skupaj"] as Long,
        (v["pricakovano_gb"] as Number).toDouble(), { it in v.nizi("podprti") }).let { listOf(it.vsebina.name, it.posoda.name, it.kodek) }
    "webtor_skupna_velikost" -> WebtorPravila.skupnaVelikost(v.cel("koda")!!, v.niz("content_range"), v["content_length"] as Long)
    "webtor_rok_sonde" -> WebtorPravila.rokSonde(v["zacetek_prve"] as Long?, v["zdaj"] as Long, v.cel("poskusov")!!)
    "webtor_steje_kot_poskus" -> WebtorPravila.stejeKotPoskus(WebtorPravila.Izid.valueOf(v.niz("izid")!!), v["trajanje_ms"] as Long)
    "vrstni_red_korakov" -> WebtorPravila.koraki(v["ima_usenet"] as Boolean).map { it.name }
    "izberi_usenet" -> WebtorPravila.izberiUsenet(v["izidi"] as List<Boolean?>, v["rok_potekel"] as Boolean).toLong()
    "scit_dovoljena" -> si.safeer.tv.scit.ScitIzjeme.dovoljena(v.niz("ime")!!, (v["naucene"] as Map<String, Any?>).mapValues { it.value as Long }, v["zdaj"] as Long)
    "scit_primeren_za_ucenje" -> si.safeer.tv.scit.ScitIzjeme.primerenZaUcenje(v.niz("gostitelj")!!)
    "uskladitev_novejsi" -> UskladitevKljucevPravila.novejsi(v["cas"] as Long, v.niz("izvor")!!, v["izbrisan"] as Boolean,
        (v["star"] as List<Any?>?)?.let { UskladitevKljucevPravila.Meta(it[0] as Long, it[1] as String, it[2] as Boolean, "") })
    // Preverba kljucev debrid in indeksatorja (tests/skupni-primeri/kljuci-preverba.json, 25. 10. 2026). Telo odgovora pride
    // kot Map iz bralnika - iste funkcije polj kot v aplikaciji (TorBox.preveri, Debrid.preveriKljuc), izidi z malimi crkami.
    "kljuc_iz_vnosa" -> if (v.niz("ponudnik") == UsenetPravila.VRSTA) UsenetPravila.kljucIzVnosa(v.niz("vnos")!!)
        else DebridPravila.kljucIzVnosa(v.niz("vnos")!!, v.niz("ponudnik")!!)
    "je_vzorec_kljuca" -> DebridPravila.jeVzorecKljuca(v.niz("kljuc")!!)
    "preverba_torbox" -> (v["telo"] as Map<String, Any?>?).let { t ->
        val d = t?.get("data") as? Map<String, Any?>
        DebridPravila.preverbaTorBoxOdgovor(v.cel("http")!!, t?.get("success"), t?.get("error"), d != null, d?.get("plan"))
            .let { listOf(imeIzida(it.first), it.second?.toLong()) }
    }
    "preverba_rd" -> (v["telo"] as Map<String, Any?>?).let { t ->
        imeIzida(DebridPravila.preverbaRdOdgovor(v.cel("http")!!, t?.get("error_code"), t?.get("id"), t?.get("username")))
    }
    "preverba_ad" -> (v["telo"] as Map<String, Any?>?).let { t ->
        imeIzida(DebridPravila.preverbaAdOdgovor(v.cel("http")!!, t?.get("status"), (t?.get("error") as? Map<String, Any?>)?.get("code")))
    }
    "preverba_indeksatorja" -> imeIzida(UsenetPravila.preverbaIndeksatorja(v.cel("http")!!, v.niz("telo")!!))
    "naslov_preverbe_indeksatorja" -> UsenetPravila.naslovPreverbe(v.niz("osnova")!!, v.niz("kljuc")!!)
    "preverba_iz_napake" -> imeIzida(DebridPravila.preverbaIzNapake(v.niz("vrsta")!!))
    "stanje_po_preverbi" -> DebridPravila.stanjePoPreverbi(preverbaIz(v.niz("preverba")!!))?.let { imeIzida(it) }
    "za_uskladitev" -> DebridPravila.zaUskladitev(v.niz("stanje")?.let { stanjeIz(it) })
    "sprejmi_prejetega" -> DebridPravila.sprejmiPrejetega(preverbaIz(v.niz("preverba")!!))
    "preveri_zdaj" -> DebridPravila.preveriZdaj(v.niz("stanje")?.let { stanjeIz(it) }, v["zadnjic_ms"] as Long, v["zdaj_ms"] as Long)
    else -> error("neznano pravilo: $f")
}

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    println("Preizkus skupnih primerov (Android in namizje)")
    val pot = args.firstOrNull() ?: "tests/skupni-primeri/debrid-serije.json"
    val koren = Bralnik(File(pot).readText(Charsets.UTF_8)).beri() as Map<String, Any?>
    for (p in koren["primeri"] as List<Map<String, Any?>>) {
        val f = p.niz("f")!!
        val vhod = p["vhod"] as Map<String, Any?>
        val dobljeno = try { izracunaj(f, vhod) } catch (e: Exception) { "izjema ${e.javaClass.simpleName}: ${e.message}" }
        preveriEnako("$f $vhod", p["izhod"], dobljeno)
    }
    println("  primerov: $primerov (verzija ${koren["verzija"]})")
    if (napak == 0) println("SkupniPrimeriTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
