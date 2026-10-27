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

/** Izid ali stanje kljuca v skupnih primerih: ime z malimi crkami (»ne_velja«, »nepreverjen«). */
private fun imeIzida(e: Enum<*>) = e.name.lowercase(java.util.Locale.ROOT)
private fun preverbaIz(s: String) = DebridPravila.Preverba.valueOf(s.uppercase(java.util.Locale.ROOT))
private fun stanjeIz(s: String) = DebridPravila.StanjeKljuca.valueOf(s.uppercase(java.util.Locale.ROOT))
@Suppress("UNCHECKED_CAST")
private fun znani(v: Map<String, Any?>): Map<Int, Pair<Long, Long>> =
    (v["znani"] as List<List<Long>>).associate { it[0].toInt() to (it[1] to it[2]) }

private fun rarIzhod(b: ByteArray, velikost: Long): Map<String, Any?> {
    val izid = UsenetRar.preveri(b, velikost).name.lowercase()
    val z = try { UsenetRar.razcleniZvezek(b, velikost) } catch (_: UsenetRar.NapakaRar) { return mapOf("izid" to izid) }
    return mapOf("izid" to izid, "rar" to z.razlicica.toLong(), "zvezek" to z.stevilka?.toLong(), "vec_zvezkov" to z.vecZvezkov,
        "datoteke" to z.vnosi.map {
            mapOf("ime" to it.ime, "odmik" to it.odmik, "dolzina" to it.dolzina, "velikost" to it.velikost, "pred" to it.pred,
                "po" to it.po, "store" to it.store, "sifriran" to it.sifriran, "mapa" to it.mapa)
        })
}

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
    // v4 (26. 10. 2026, Grok 44 D2): zapah po racunu pri ponudniku, trajni zapah racuna brez gesla.
    "nntp_ponudnik" -> UsenetNntp.ponudnik(v.niz("gostitelj")!!)
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
            "zapahov_najvec" to UsenetNntp.NAJVEC_ZAPAHOV.toLong(), "napak_ukaza_zapored" to UsenetNntp.NAPAK_UKAZA_ZAPORED.toLong())
    }
    // Pogon Usenet, korak 2 (usenet-pogon.json v5): preslikava odmikov, RAR store, okno vnaprej.
    "tok_odmik" -> try {
        val u = UsenetTok.poisci(v["velikost"] as Long, v.cel("celota")!!, v["del_bajtov"] as Long?, znani(v), v["odmik"] as Long)
        mapOf("izid" to "ok", "segment" to u.stevilka.toLong(), "odmik" to u.odmik, "natancno" to u.natancno)
    } catch (e: UsenetTok.NapakaToka) { mapOf("izid" to e.izid.name.lowercase()) }
    "tok_segmenti" -> UsenetTok.segmentiOkna(v["velikost"] as Long, v.cel("celota")!!, v["del_bajtov"] as Long?, znani(v),
        v["odmik"] as Long, v["dolzina"] as Long).map { it.toLong() }
    "tok_okno" -> UsenetBralnik.oknoVnaprej(v["zaporedno_prebrano"] as Long, v["zacetno"] as Long, v["najvec"] as Long)
    "tok_profili" -> UsenetBralnik.PROFILI.mapValues { (_, p) -> listOf(p.okno, p.zacetno, p.lru, p.vzporedno.toLong()) }
    "rar_glava" -> rarIzhod(B64D.decode(v.niz("glava_b64")!!), v["velikost"] as Long)
    "rar_zvezki" -> UsenetRar.vrstniRedZvezkov(v.nizi("imena"))?.map { it.toLong() }
    "rar_sestavi" -> try {
        val b = (v["zvezki_b64"] as List<String>).map { B64D.decode(it) }
        val vel = v["velikosti"] as List<Long>
        val (ime, velikost, kosi) = UsenetRar.sestavi(b.indices.map { UsenetRar.razcleniZvezek(b[it], vel[it]) })
        mapOf("izid" to "ok", "ime" to ime, "velikost" to velikost, "kosi" to kosi.map { listOf(it.zvezek.toLong(), it.odmik, it.dolzina) })
    } catch (e: UsenetRar.NapakaRar) { mapOf("izid" to e.izid.name.lowercase()) }
    // v6 (26. 10. 2026, pregled Grok 58 in 61): meje preslikave (T1), vzorec kot beseda (T13), izbira datotek, meje.
    "tok_preslikava" -> try {
        mapOf("izid" to "ok", "celota" to UsenetTok.mejePreslikave(v.cel("stevil_v_nzb")!!, v.cel("manjka_v_nzb")!!,
            v["velikost"] as Long, v.cel("skupaj")).toLong())
    } catch (e: UsenetTok.NapakaToka) { mapOf("izid" to e.izid.name.lowercase()) }
    "tok_vzorec" -> UsenetBralnik.jeVzorec(v.niz("ime"))
    "tok_izbira" -> try {
        val nzb = UsenetNzb.Nzb((v["datoteke"] as List<List<Any?>>).map {
            UsenetNzb.Datoteka(it[0] as String, 0L, emptyList(), listOf(UsenetNzb.Segment(1, it[1] as Long, "a@primer.invalid")), 0)
        }, null, false)
        val (vrsta, indeksi) = UsenetBralnik.izberiDatoteke(nzb)
        mapOf("izid" to "ok", "vrsta" to vrsta, "indeksi" to indeksi.map { it.toLong() })
    } catch (e: UsenetBralnik.NapakaIzdaje) { mapOf("izid" to e.izid) }
    "tok_meje" -> mapOf("segmentov_datoteke" to UsenetTok.NAJVEC_SEGMENTOV_DATOTEKE.toLong(), "del_bajtov" to UsenetTok.NAJVEC_DELA,
        "priprava_hkrati" to UsenetBralnik.PRIPRAVA_HKRATI.toLong(), "priprave_bajtov" to UsenetBralnik.NAJVEC_PRIPRAVE_BAJTOV,
        "glav_segmentov" to UsenetBralnik.NAJVEC_GLAV_SEGMENTOV, "glav_bajtov" to UsenetBralnik.NAJVEC_GLAV_BAJTOV,
        "poskusov" to UsenetBralnik.NAJVEC_POSKUSOV.toLong(), "preklicev" to UsenetBralnik.NAJVEC_PREKLICEV.toLong(),
        "streznik_povezav" to UsenetStreznik.NAJVEC_POVEZAV.toLong(), "streznik_rok_s" to UsenetStreznik.ROK_POVEZAVE_MS / 1000,
        "vint_najvec" to UsenetRar.NAJVEC_VINT, "zvezkov_najvec" to UsenetRar.NAJVEC_ZVEZKOV)
    "uskladitev_novejsi" ->UskladitevKljucevPravila.novejsi(v["cas"] as Long, v.niz("izvor")!!, v["izbrisan"] as Boolean,
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
    // Skupni primeri v2 (26. 10. 2026): naslov indeksatorja (pregled Grok 31, H1), odtis stanja, varovalo racunov K1.
    "osnova_indeksatorja" -> UsenetPravila.osnova(v.niz("vnos")!!)
    "naslov_je_kljuc" -> UsenetPravila.naslovJeKljuc(v.niz("vnos")!!)
    "odtis_stanja" -> DebridPravila.odtisStanja(v.niz("kljuc")!!)
    "sme_klicati" -> DebridPravila.smeKlicati(v.niz("stanje")?.let { stanjeIz(it) })
    "preveri_zdaj" -> DebridPravila.preveriZdaj(v.niz("stanje")?.let { stanjeIz(it) }, v["zadnjic_ms"] as Long, v["zdaj_ms"] as Long)
    else -> error("neznano pravilo: $f")
}

/**
 * Skupni primeri razclenitve seznamov M3U (tests/skupni-primeri/iptv-m3u.json, lastnik Safeer-linux): Android preveri, kar
 * pozna njegov bralnik seznamov IPTV - ime (brez tehnicnih pripon), drzavo, jezik, logotip in izlocitev [Not 24/7]. Filmov,
 * serij in radia Android iz seznama IPTV ne loci: ti primeri so preskoceni in nasteti (razlika do Linuxa).
 */
@Suppress("UNCHECKED_CAST")
private fun iptvPrimeri(koren: Map<String, Any?>) {
    for (p in koren["primeri"] as List<Map<String, Any?>>) {
        val pr = p["pricakovano"] as Map<String, Any?>
        val opis = p.niz("opis")!!
        val vrsta = pr.niz("vrsta")
        if (pr["izlocen"] != true && vrsta != "tv-v-zivo") { println("  preskoceno (Android, vrsta $vrsta): $opis"); continue }
        val seznam = p.niz("seznam")!!
        val (d, j) = IptvPravila.izNaslovaSeznama(seznam)
        val b = IptvPravila.Bralnik(seznam, d, j)
        val vnosi = (p["vrstice"] as List<String>).mapNotNull { b.vrstica(it) }
        if (pr["izlocen"] == true) { preveriEnako("$opis: izlocen", 0, vnosi.size); continue }
        preveriEnako("$opis: en kanal", 1, vnosi.size)
        val v = vnosi.singleOrNull() ?: continue
        preveriEnako("$opis: ime", pr["ime"], KanaliPravila.imeKanala(v.ime))
        for ((k, dobljeno) in listOf("drzava" to v.drzava, "jezik" to v.jezik, "logo" to v.logo))
            if (pr.containsKey(k)) preveriEnako("$opis: $k", pr[k], dobljeno)
    }
}

/**
 * Skupni primeri zascite racuna IPTV (tests/skupni-primeri/iptv-racun.json, lastnik Safeer-linux; pregled 39, I1):
 * seznami.javni = [IptvPravila.javniBrezplacni] (sme v preverbo v ozadju), kanali.racun = [IptvPravila.jeVirSPoverilnico]
 * za naslov toka (nikoli v preverbo v ozadju, s tokom samo v ozji krog).
 */
@Suppress("UNCHECKED_CAST")
private fun iptvRacunPrimeri(koren: Map<String, Any?>) {
    for (s in koren["seznami"] as List<Map<String, Any?>>)
        preveriEnako("seznam ${s.niz("opis") ?: s.niz("url")}: javni", s["javni"], IptvPravila.javniBrezplacni(s.niz("url")!!))
    for (k in koren["kanali"] as List<Map<String, Any?>>)
        preveriEnako("kanal ${k.niz("opis") ?: k.niz("url")}: racun", k["racun"], IptvPravila.jeVirSPoverilnico(k.niz("url")!!, seznam = false))
    // Verzija 2 (Linux, pregled 46 Ö1): viri.ozji = vir gre samo v ozji krog ([smeZNavadnoUskladitvijo] false), viri.prikaz = zaslon.
    // Naslov se primerja SUROV (presledek ali nevidni znak okoli naslova ni pozitivno znan javen). Android nima oznake »javen«
    // (lastnikov zaslon ni narejen): primer z oznacen=true in ozji=false je tu strozji (ozji=true, samo gostitelj) - preveri se to.
    var oznakaPreskocena = 0
    for (v in (koren["viri"] as? List<Map<String, Any?>>).orEmpty()) {
        val n = v.niz("naslov")!!
        val opis = "vir ${v.niz("opis") ?: n.trim()}"
        if (v["oznacen"] == true && v["ozji"] == false) {
            oznakaPreskocena++
            preveriEnako("$opis: Android brez oznake javnega - ozji", true, !IptvPravila.smeZNavadnoUskladitvijo("seznam", n))
            continue
        }
        preveriEnako("$opis: ozji", v["ozji"], !IptvPravila.smeZNavadnoUskladitvijo("seznam", n))
        // Zaslon: Android ima svojo obliko (javni seznam brez sheme, drugo samo gostitelj brez vrat, naslov, ki ni http(s), »…«);
        // enako kot Linux pa nikoli skrivnosti. Naslov s presledkom okoli Android pred prikazom obreze (preverjeno zgoraj).
        val prikaz = IptvPravila.zaPrikaz(n)
        preveriEnako("$opis: na zaslonu brez gesla", true, listOf("skrito-geslo", "uporabnik", "00:1A:79").none { it in prikaz })
        val pricakovan = v.niz("prikaz")
        if (pricakovan != null && n == n.trim()) {
            val android = if (v["ozji"] == false) pricakovan.removePrefix("https://").removePrefix("http://")
                else if (n.lowercase().startsWith("http://") || n.lowercase().startsWith("https://")) pricakovan.substringBefore(':') else "…"
            preveriEnako("$opis: prikaz", android, prikaz)
        }
    }
    println("  oznaka »javen« (Android je ni): $oznakaPreskocena primer strozje")
    // Verzija 3 (Linux, pregled 51 Ê3): prevzem z lists.get. ozji true - vsak vir; false ali BREZ kljuca (null, starejsa razlicica) -
    // samo pozitivno znan javen. Android kljuc »ozji« bere iz odgovora (SeznamiSink) in ga zdruzi z lastnim ozjim krogom
    // ([odOzjegaKroga]: oboje mora drzati), nato odloci z [prevzemZNavadnoUskladitvijo].
    for (v in (koren["prevzem"] as? List<Map<String, Any?>>).orEmpty())
        preveriEnako("prevzem ${v.niz("opis") ?: v.niz("naslov")} (ozji=${v["ozji"]})", v["prevzem"],
            IptvPravila.prevzemZNavadnoUskladitvijo("seznam", v.niz("naslov")!!, IptvPravila.odOzjegaKroga(true, v["ozji"] as? Boolean)))
    preveriEnako("odgovor ozji=true, naprava v nasem krogu: vzemi vse", true, IptvPravila.odOzjegaKroga(true, true))
    preveriEnako("odgovor ozji=false: samo javno", false, IptvPravila.odOzjegaKroga(true, false))
    preveriEnako("odgovor brez kljuca (starejsa razlicica): samo javno", false, IptvPravila.odOzjegaKroga(true, null))
    preveriEnako("odgovor ozji=true, a naprava ni v nasem krogu: samo javno (krog je na napravo)", false, IptvPravila.odOzjegaKroga(false, true))
    // Verzija 4 (Linux, pregled 57; Grok 60): »caka« = vir, prejet z druge naprave, ob prejemu NE naredi nobene povezave. Povezavo
    // naredita samo dve poti prejema: vrsta »url« (prejetNaslov bere naslov prek [razvrsti], ce ni vir z racunom) in vrsta seznam
    // (SeznamiSink: osveziPoPrejemu); podkast, tok in drugo se ob prejemu ne berejo. Pravi poti, ne kopija odlocitve.
    var caka = 0
    for (c in (koren["caka"] as? List<Map<String, Any?>>).orEmpty()) {
        val tip = c.niz("tip")!!; val n = c.niz("naslov")!!
        var povezava = false
        when (tip) {
            "url" -> IptvPravila.prejetNaslov(n) { povezava = true; null }
            "seznam" -> povezava = IptvPravila.osveziPoPrejemu(tip, n)
            else -> povezava = false
        }
        preveriEnako("caka ($tip) ${c.niz("opis") ?: n}", c["caka"], !povezava)
        caka++
    }
    println("  caka: $caka primerov")
    // »racun_vira«: vir, ki ni pozitivno znan javen, je vir z racunom (premor in ena zahteva tudi pred prvim branjem).
    // Android nima oznake »javen« (lastnikov zaslon ni narejen): oznacen javen naslov je tu racun (strozje), oznacen z racunom ostane racun.
    // »vdelava« = [smeVdelatiStran]: stran HTML, ki jo uporabnik SAM doda in nima znakov poverilnice; prejeta nikoli.
    var racunov = 0; var vdelav = 0; var strozje = 0
    for (v in (koren["racun_vira"] as? List<Map<String, Any?>>).orEmpty()) {
        val n = v.niz("naslov")!!
        val opis = "racun_vira ${v.niz("opis") ?: n}"
        val racun = IptvPravila.virZRacunom("seznam", n)
        if (v["oznacen"] == true && v["racun"] == false) {
            strozje++
            preveriEnako("$opis: Android brez oznake javnega - racun", true, racun)
        } else {
            preveriEnako("$opis: racun", v["racun"], racun)
            racunov++
        }
        val vdelava = v["vdelava"] as? Boolean ?: continue
        preveriEnako("$opis: vdelava", vdelava, IptvPravila.smeVdelatiStran(n, v["prejet"] == true))
        vdelav++
    }
    println("  racun_vira: $racunov primerov, vdelava: $vdelav, strozje brez oznake javnega: $strozje")
    // Verzija 5 (Linux viri_sink.stanje_prejetega; lastnik 26. 10. 2026: vse naprave pripadajo uporabniku): stanje vira z druge naprave glede
    // na odgovor »ozji« (naprava je v ozjem krogu te naprave). javen = prenos takoj; prvo_uporaba = viden takoj, brez povezave ob prejemu
    // in v ozadju, uporaben ob prvi uporabi (Android: [uporabaPoPrejemu] + [smeSamodejnoPrenesti]); ni_prevzet = fail-closed.
    var ozjih = 0
    for (v in (koren["ozji_krog"] as? List<Map<String, Any?>>).orEmpty()) {
        val n = v.niz("naslov")!!
        // Verzija 6 (Grok 68, Ą3): v_krogu = posiljatelj je v ozjem krogu TE naprave (brez polja: true); samo odgovor »ozji« ne zadostuje.
        val vKrogu = v["v_krogu"] as? Boolean ?: true
        val odKroga = IptvPravila.odOzjegaKroga(vKrogu, v["ozji"] as? Boolean)
        val stanje = when {
            !IptvPravila.prevzemZNavadnoUskladitvijo("url", n, odKroga) -> "ni_prevzet"
            !IptvPravila.virZRacunom("seznam", n) -> if (IptvPravila.osveziPoPrejemu("seznam", n)) "javen" else "javen_brez_prenosa"
            else -> {
                val uporaba = IptvPravila.uporabaPoPrejemu(null, setOf(n), setOf(n), odKroga)
                val dokaz = IptvPravila.dokazDodanTu(null, setOf(n), setOf(n), prejeto = true)
                val uporabno = IptvPravila.smeUporabiti(n in dokaz, n in uporaba)
                val brezPovezave = IptvPravila.prejetNaslov(n) { null } != null && !IptvPravila.osveziPoPrejemu("seznam", n)
                if (uporabno && IptvPravila.smeSamodejnoPrenesti(n, uporabno, izrecno = false) && brezPovezave && n !in dokaz) "prvo_uporaba" else "caka"
            }
        }
        preveriEnako("ozji_krog ${v.niz("opis") ?: n} (ozji=${v["ozji"]})", v["stanje"], stanje)
        // Grok 68, Ą3: dokaz ozjega kroga = posiljatelj je v ozjem krogu TE naprave IN odgovor to potrjuje; polje v odgovoru samo ne zadostuje
        // (naprava zunaj kroga lahko poslje ozji = true): prvo_uporaba je nemogoc, vir z racunom se ne prevzame.
        val tuj = IptvPravila.odOzjegaKroga(false, v["ozji"] as? Boolean)
        val stanjeTuj = if (!IptvPravila.prevzemZNavadnoUskladitvijo("url", n, tuj)) "ni_prevzet" else if (!IptvPravila.virZRacunom("seznam", n)) "javen" else "prvo_uporaba"
        preveriEnako("ozji_krog, posiljatelj NI v nasem krogu: ${v.niz("opis") ?: n}", if (v["stanje"] == "javen") "javen" else "ni_prevzet", stanjeTuj)
        ozjih++
    }
    println("  ozji_krog: $ozjih primerov")
}

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    println("Preizkus skupnih primerov (Android in namizje)")
    val pot = args.firstOrNull() ?: "tests/skupni-primeri/debrid-serije.json"
    val koren = Bralnik(File(pot).readText(Charsets.UTF_8)).beri() as Map<String, Any?>
    if (koren.containsKey("seznami") && koren.containsKey("kanali")) {
        iptvRacunPrimeri(koren)
        println("  primerov: $primerov")
        if (napak == 0) println("SkupniPrimeriTest: OK") else { println("Napak: $napak"); System.exit(1) }
        return
    }
    if ((koren["primeri"] as List<Map<String, Any?>>).firstOrNull()?.containsKey("vrstice") == true) {
        iptvPrimeri(koren)
        println("  primerov: $primerov")
        if (napak == 0) println("SkupniPrimeriTest: OK") else { println("Napak: $napak"); System.exit(1) }
        return
    }
    for (p in koren["primeri"] as List<Map<String, Any?>>) {
        val f = p.niz("f")!!
        val vhod = p["vhod"] as Map<String, Any?>
        val dobljeno = try { izracunaj(f, vhod) } catch (e: Exception) { "izjema ${e.javaClass.simpleName}: ${e.message}" }
        preveriEnako("$f $vhod", p["izhod"], dobljeno)
    }
    println("  primerov: $primerov (verzija ${koren["verzija"]})")
    // usenet-pogon v6: natancno 560 primerov (pregled Grok 58 T14: »najmanj« ne ujame izgube primerov)
    if (pot.endsWith("usenet-pogon.json") && (koren["verzija"] != 6L || primerov != 560)) {
        println("usenet-pogon.json: pricakovana verzija 6 s 560 primeri"); System.exit(1)
    }
    if (napak == 0) println("SkupniPrimeriTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
