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
    // Webtor rezerva (tests/skupni-primeri/webtor-rezerva.json).
    "skrivna_predpona" -> SkrivnostiPravila.skrivnaPredpona(v.niz("naslov")!!)
    "v_nadomestek" -> SkrivnostiPravila.vNadomestek(v.niz("naslov")!!)
    "za_prikaz" -> SkrivnostiPravila.zaPrikaz(v.niz("naslov")!!)
    "webtor_oznaka" -> WebtorPravila.razcleni(v.niz("ime")!!).let { listOf(it.vrsta.name, it.ime) }
    "webtor_odlocitev" -> WebtorPravila.odlocitev(v.cel("koda")!!, v["prvi_bajt_ms"] as Long?, v["potekel"] as Boolean).name
    "webtor_vrstni_red" -> WebtorPravila.vrstniRed(
        (v["nasi"] as List<List<Any?>?>).map { k -> k?.let { WebtorPravila.kljuc(it[0] as String?, (it[1] as Long).toInt()) } },
        (v["webtor"] as List<List<Any?>>).map { WebtorPravila.kljuc(it[0] as String?, (it[1] as Long).toInt()) to WebtorPravila.vrstaIz(it[2] as String) },
        v["imamo_torbox"] as Boolean).map { it.toLong() }
    "scit_dovoljena" -> si.safeer.tv.scit.ScitIzjeme.dovoljena(v.niz("ime")!!, v.nizi("dodatne").filterNotNull())
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
