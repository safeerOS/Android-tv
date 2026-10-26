package si.safeer.tv.os

import java.io.File

/**
 * Naloga 134: skupni preizkusni primeri za Android in namizje (tests/skupni-primeri: debrid-serije.json,
 * webtor-rezerva.json, usenet-pogon.json). Ista datoteka je v repozitorijih Safeer-linux in Safeer-windows
 * (tests/test_skupni_primeri.py) - pravilo, spremenjeno samo na eni strani, tu ali tam pade. Testi s kotlinc nimajo
 * knjiznice JSON, zato je tu majhen bralnik.
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

private fun meritev(m: Map<String, Any?>) =
    DebridPravila.Meritev(m.niz("ponudnik")!!, m["ping_ms"] as Long, m["bajtov"] as Long, m["cas_ms"] as Long)

private val B64D = java.util.Base64.getDecoder()
private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

/** Ukaz NNTP iz skupnih primerov (»AUTHINFO USER« ...) v fazo odjemalca. */
private fun fazaNntp(ukaz: String) = when (ukaz) {
    "AUTHINFO USER" -> UsenetNntp.Faza.UPORABNIK
    "AUTHINFO PASS" -> UsenetNntp.Faza.GESLO
    else -> UsenetNntp.Faza.valueOf(ukaz)
}

/** Izid yEnc v obliki skupnih primerov: zacetek od 1, konec vkljucno; telo null = prirezano (NNTP brez konca). */
private fun usenetYenc(telo: ByteArray?): Map<String, Any?> {
    if (telo == null) return mapOf("izid" to "prirezano")
    return try {
        val d = UsenetYenc.dekodiraj(telo)
        mapOf("izid" to "ok", "ime" to d.ime, "del" to d.del?.toLong(), "skupaj" to d.delov?.toLong(), "velikost" to d.velikostDatoteke,
            "zacetek" to d.zacetek + 1, "konec" to d.konec, "crc32" to String.format("%08x", d.crc32),
            "crc_preverjen" to d.crcPreverjen, "podatki_b64" to b64(d.podatki))
    } catch (e: UsenetYenc.NapakaYenc) {
        mapOf("izid" to e.vrsta.name.lowercase())
    }
}

private fun usenetNzb(bajti: ByteArray): Map<String, Any?> = try {
    val n = UsenetNzb.razcleni(bajti)
    mapOf("izid" to "ok", "naslov" to n.naslov, "ima_geslo" to n.imaGeslo, "datoteke" to n.datoteke.map { d ->
        mapOf("ime" to d.ime, "skupine" to d.skupine, "bajtov_nzb" to d.bajtov, "manjka" to d.manjka.toLong(),
            "segmenti" to d.segmenti.map { listOf(it.stevilka.toLong(), it.bajtov, it.id) })
    })
} catch (e: UsenetNzb.NapakaNzb) {
    mapOf("izid" to e.vrsta.name.lowercase())
}

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
    // Pogon Usenet, korak 1 (tests/skupni-primeri/usenet-pogon.json, lastnik datoteke je Safeer-linux).
    "yenc_dekodiraj" -> usenetYenc(B64D.decode(v.niz("telo_b64")!!))
    "nntp_telo" -> UsenetNntp.telo(B64D.decode(v.niz("surovo_b64")!!))?.let { mapOf("telo_b64" to b64(it.telo), "ostanek_b64" to b64(it.ostanek)) }
    "yenc_iz_nntp" -> usenetYenc(UsenetNntp.telo(B64D.decode(v.niz("surovo_b64")!!))?.telo)
    "nntp_izid" -> UsenetNntp.izid(fazaNntp(v.niz("ukaz")!!), v.niz("vrstica")!!).name.lowercase()
    // v3 (26. 10. 2026, zaklep racuna): ukrep bazena, odpiranje povezav, premor, poverilnica, odgovor za clanek.
    "nntp_ukrep" -> UsenetNntp.ukrep(UsenetNntp.Vrsta.valueOf(v.niz("izid")!!.uppercase()),
        UsenetNntp.FazaPovezave.valueOf(v.niz("faza")!!.uppercase()), v["stara"] as Boolean, v.cel("poskus")!!).name.lowercase()
    "nntp_sme_odpreti" -> UsenetNntp.smeOdpreti(v["ustavljen"] as Boolean, v["premor"] as Boolean, v.cel("odprtih")!!,
        v.cel("najvec")!!, v["uspela"] as Boolean, v["sonda"] as Boolean)
    "nntp_premor" -> UsenetNntp.novPremor(v.cel("najvec")!!, v.cel("odprtih")!!, v["premor_s"] as Long, v["tece"] as Boolean, v["zmanjsaj"] as Boolean)
        .let { mapOf("najvec" to it.first.toLong(), "premor_s" to it.second) }
    "nntp_poverilnica" -> UsenetNntp.varenArgument(v.niz("vrednost")!!)
    "nntp_id_odgovora" -> UsenetNntp.idOdgovoraUstreza(v.niz("vrstica")!!, v.niz("id")!!)
    // v4 (26. 10. 2026, Grok 44 D2): trajni zapah racuna brez gesla.
    "nntp_zapah_odtis" -> UsenetNntp.zapahOdtis(hex(v.niz("sol_hex")!!), v.niz("ponudnik")!!, v.niz("uporabnik")!!, v.niz("geslo")!!)
        .let { mapOf("racun" to it.first, "geslo" to it.second) }
    "nntp_id" -> UsenetNzb.cistId(v.niz("id")!!)
    "varno_ime" -> UsenetNzb.varnoIme(v.niz("ime")!!)
    "nzb_ime" -> UsenetNzb.imeIzZadeve(v.niz("subject")!!)
    "nzb_razcleni" -> usenetNzb(v.niz("xml")?.toByteArray(Charsets.UTF_8) ?: B64D.decode(v.niz("xml_b64")!!))
    "usenet_meje" -> UsenetNzb.Meje().let { m ->
        mapOf("nzb_bajtov" to UsenetPravila.NAJVEC_NZB_BAJTOV.toLong(), "nzb_datotek" to m.datotek.toLong(),
            "nzb_segmentov" to m.segmentov.toLong(), "nzb_elementov" to m.elementov.toLong(),
            "segmentov_datoteke" to m.segmentovDatoteke.toLong(),
            "telo_bajtov" to UsenetNntp.NAJVEC_TELESA.toLong(), "del_bajtov" to UsenetYenc.NAJVEC_DELA.toLong(),
            "ime_znakov" to UsenetNzb.NAJVEC_IMENA.toLong(), "ime_bajtov" to UsenetNzb.NAJVEC_IMENA_BAJTOV.toLong(),
            "naslov_znakov" to UsenetNzb.NAJVEC_NASLOVA.toLong(),
            "skupin" to m.skupin.toLong(), "vrstica_bajtov" to UsenetNntp.NAJVEC_VRSTICE.toLong(), "id_znakov" to UsenetNzb.NAJVEC_ID.toLong(),
            "poverilnica_bajtov" to UsenetNntp.NAJVEC_POVERILNICE.toLong(),
            "povezav_privzeto" to UsenetNntp.PRIVZETO_POVEZAV.toLong(), "povezav_najvec" to UsenetNntp.NAJVEC_POVEZAV.toLong(),
            "vrata" to UsenetNntp.VRATA.toLong(), "prosta_najdlje_s" to UsenetNntp.PROSTA_NAJDLJE_S,
            "premor_od_s" to UsenetNntp.PREMOR_OD_S, "premor_brez_odprtih_s" to UsenetNntp.PREMOR_BREZ_ODPRTIH_S,
            "premor_do_s" to UsenetNntp.PREMOR_DO_S, "rast_bazena_s" to UsenetNntp.RAST_BAZENA_S,
            "ziva_prijava_s" to UsenetNntp.ZIVA_PRIJAVA_S, "zapah_ponovitev" to UsenetNntp.ZAPAH_PONOVITEV.toLong(),
            "zapahov_najvec" to UsenetNntp.NAJVEC_ZAPAHOV.toLong())
    }
    "uskladitev_novejsi" -> UskladitevKljucevPravila.novejsi(v["cas"] as Long, v.niz("izvor")!!, v["izbrisan"] as Boolean,
        (v["star"] as List<Any?>?)?.let { UskladitevKljucevPravila.Meta(it[0] as Long, it[1] as String, it[2] as Boolean, "") })
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
