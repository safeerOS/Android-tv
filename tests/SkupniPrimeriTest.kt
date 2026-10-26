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
    "preverba_rd" -> imeIzida(DebridPravila.preverbaRdOdgovor(v.cel("http")!!, (v["telo"] as Map<String, Any?>?)?.get("error_code")))
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
    // samo pozitivno znan javen. Android ozji ne bere iz odgovora: odloci po lastnem ozjem krogu ([prevzemZNavadnoUskladitvijo]).
    for (v in (koren["prevzem"] as? List<Map<String, Any?>>).orEmpty())
        preveriEnako("prevzem ${v.niz("opis") ?: v.niz("naslov")} (ozji=${v["ozji"]})", v["prevzem"],
            IptvPravila.prevzemZNavadnoUskladitvijo("seznam", v.niz("naslov")!!, IptvPravila.odOzjegaKroga(true, v["ozji"] as? Boolean)))
    preveriEnako("odgovor ozji=true, naprava v nasem krogu: vzemi vse", true, IptvPravila.odOzjegaKroga(true, true))
    preveriEnako("odgovor ozji=false: samo javno", false, IptvPravila.odOzjegaKroga(true, false))
    preveriEnako("odgovor brez kljuca (starejsa razlicica): samo javno", false, IptvPravila.odOzjegaKroga(true, null))
    preveriEnako("odgovor ozji=true, a naprava ni v nasem krogu: samo javno (krog je na napravo)", false, IptvPravila.odOzjegaKroga(false, true))
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
    if (napak == 0) println("SkupniPrimeriTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
