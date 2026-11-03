package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki
import java.io.File

/**
 * Lupa proti skupnim primerom (tests/lupa-vektorji.json - ISTA datoteka kot v Safeer-linux, kjer jo preverja
 * assets/os/lupa.js): filtri, skupine, ujemanje, vrste aplikacij, zadetki po filtrih, zgodovina in besedila v sestih
 * jezikih. Ena resnica za TV, namizje in prihodnji zaganjalnik za telefon in tablico (3. 11. 2026).
 * Zagon iz korena projekta (pot do primerov: -Dlupa.vektorji=... ali tests/lupa-vektorji.json).
 */
private var napakV = 0

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napakV++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun obj(s: String): JsonLahki.Pogled = JsonLahki.objekt(s) ?: error("ni objekt: ${s.take(80)}")
private fun seznam(s: String): List<String> = JsonLahki.elementi(s) ?: error("ni seznam: ${s.take(80)}")
private fun niz(s: String): String? = if (s.trim() == "null") null else JsonLahki.niz(s)
private fun nizi(s: String): List<String> = seznam(s).map { niz(it) ?: error("ni niz") }
private fun JsonLahki.Pogled.vsebina(k: String): String = surovo(k) ?: error("ni kljuca $k")

private class Vnos(val ime: String, val vrsta: String, val url: String?)

/** Besedila virov Android za jezik (values-sl …; anglescina v values-en ali values). */
private fun viri(res: File, jezik: String): Map<String, String> {
    val mapa = File(res, "values-$jezik").takeIf { it.isDirectory } ?: File(res, "values")
    val izid = HashMap<String, String>()
    val vzorec = Regex("<string name=\"([A-Za-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
    mapa.listFiles { f -> f.name.endsWith(".xml") }?.forEach { f ->
        for (m in vzorec.findAll(f.readText())) izid[m.groupValues[1]] = m.groupValues[2]
            .replace("\\'", "'").replace("\\\"", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
    return izid
}

/** {q}, {ime}, {obseg}, {n} po vrsti prvega pojava -> %1$s, %2$s …; {n} je stevilo (%k$d). */
private fun vAndroid(b: String): String {
    val vrsta = ArrayList<String>()
    return Regex("\\{(\\w+)\\}").replace(b) { m ->
        val ime = m.groupValues[1]
        if (ime !in vrsta) vrsta.add(ime)
        "%" + (vrsta.indexOf(ime) + 1) + "$" + (if (ime == "n") "d" else "s")
    }
}

fun main() {
    println("Lupa proti skupnim primerom (lupa-vektorji.json)")
    val pot = System.getProperty("lupa.vektorji") ?: listOf("tests/lupa-vektorji.json", "lupa-vektorji.json").first { File(it).isFile }
    val v = obj(File(pot).readText())

    println("\n== filtri in skupine ==")
    val obsegi = nizi(v.vsebina("obsegi"))
    enako("vrstni red filtrov", obsegi, LupaPravila.OBSEGI)
    val skupine = obj(v.vsebina("skupine"))
    val vrstica = obj(v.vsebina("vrstica_iskanja"))
    val ponudi = obj(v.vsebina("ponudi_vse"))
    for (o in obsegi) {
        enako("skupine $o", nizi(skupine.vsebina(o)), LupaPravila.skupine(o))
        enako("vrstica iskanja $o", vrstica.logicno(o), LupaPravila.vrsticaIskanja(o))
        enako("ponudi Vse $o", ponudi.logicno(o), LupaPravila.ponudiVse(o))
    }
    for (x in seznam(v.vsebina("obseg")).map(::obj)) enako("obseg ${x.surovo("vhod")}", x.niz("izhod"), LupaPravila.obseg(x.niz("vhod")))
    for (x in seznam(v.vsebina("obseg_po")).map(::obj))
        enako("obsegPo ${x.niz("trenutni")} ${x.logicno("zaprta")}", x.niz("izhod"), LupaPravila.obsegPo(x.niz("trenutni")!!, x.logicno("zaprta")!!))

    println("\n== ujemanje in vrste aplikacij ==")
    for (x in seznam(v.vsebina("ujemanje")).map(::obj))
        enako("ujemanje ${x.niz("ime")} / ${x.niz("q")}", x.stevilo("ocena")!!.toInt(), LupaPravila.ujemanje(x.niz("ime")!!, x.niz("q")!!))
    for (x in seznam(v.vsebina("vrsta_aplikacije")).map(::obj))
        enako("vrsta ${x.niz("ime")}", x.niz("vrsta"), LupaPravila.vrstaAplikacije("", x.niz("ime")!!, false, false))
    for (x in seznam(v.vsebina("vrsta_spletne")).map(::obj))
        enako("spletna ${x.niz("url")}", x.niz("vrsta"), LupaPravila.vrstaSpletne(x.niz("url")!!, x.niz("ime")!!))

    println("\n== zadetki po filtrih ==")
    val najvec = obj(v.vsebina("najvec_v_skupini"))
    for (z in seznam(v.vsebina("zadetki")).map(::obj)) {
        val q = z.niz("q")!!
        val vnosi = seznam(z.vsebina("vnosi")).map(::obj).map { Vnos(it.niz("ime")!!, it.niz("vrsta")!!, it.niz("url")) }
        val pricakovano = obj(z.vsebina("po_filtrih"))
        for (o in obsegi) {
            val n = najvec.stevilo(if (o == LupaPravila.OBSEG_VSE) "vse" else "izbran")!!.toInt()
            val apl = LupaPravila.aplikacijePoSkupinah(vnosi.filter { it.vrsta == "aplikacija" }, q, n, { it.ime }) {
                if (it.url != null) LupaPravila.vrstaSpletne(it.url, it.ime) else LupaPravila.vrstaAplikacije("", it.ime, false, false)
            }
            val dobljeno = LupaPravila.skupine(o).mapNotNull { s ->
                val seznamS = when (s) {
                    LupaPravila.OBSEG_APLIKACIJE, LupaPravila.SKUPINA_VIDEOTEKE, LupaPravila.SKUPINA_NOVICE -> apl[s].orEmpty()
                    LupaPravila.OBSEG_DATOTEKE -> LupaPravila.najboljsi(vnosi.filter { it.vrsta == "datoteka" }, q, n) { it.ime }
                    LupaPravila.OBSEG_NASTAVITVE -> LupaPravila.najboljsi(vnosi.filter { it.vrsta == "nastavitev" }, q, n) { it.ime }
                    else -> emptyList()
                }
                if (seznamS.isEmpty()) null else s to seznamS.map { it.ime }
            }
            val zelja = seznam(pricakovano.vsebina(o)).map { par -> seznam(par).let { niz(it[0])!! to nizi(it[1]) } }
            enako("»$q« v $o", zelja, dobljeno)
        }
    }

    println("\n== zgodovina ==")
    for (x in seznam(v.vsebina("za_zgodovino")).map(::obj)) enako("zaZgodovino »${x.niz("niz")}«", x.niz("izid"), LupaPravila.zaZgodovino(x.niz("niz")!!))
    for (x in seznam(v.vsebina("ocisti_zgodovino")).map(::obj))
        enako("ocisti ${nizi(x.vsebina("vhod"))}", nizi(x.vsebina("izid")),
            LupaPravila.ocistiZgodovino(nizi(x.vsebina("vhod")), x.stevilo("najvec")!!.toInt()))
    for (x in seznam(v.vsebina("dodaj_v_zgodovino")).map(::obj))
        enako("dodaj »${x.niz("niz")}«", nizi(x.vsebina("izid")), LupaPravila.dodajVZgodovino(nizi(x.vsebina("seznam")), x.niz("niz")!!))

    println("\n== velikost gumbov ==")
    for (x in seznam(v.vsebina("velikost_gumbov")).map(::obj)) {
        val sirine = seznam(x.vsebina("sirine")).map { it.trim().toFloat() }
        val zelja = x.stevilo("izid")?.toFloat()
        enako("prostor ${x.stevilo("prostor")}", zelja, LupaPravila.velikostGumbov(sirine, x.stevilo("dodatek")!!.toFloat(), x.stevilo("prostor")!!.toFloat()))
    }

    println("\n== besedila (sl, en, de, es, fr, it) ==")
    val res = listOf(File("res"), File("../res")).firstOrNull { it.isDirectory }
    if (res == null) { napakV++; println("  NAPAKA  mape res ni") } else {
        val besedila = obj(v.vsebina("besedila"))
        val tv = obj(v.vsebina("besedila_tv"))
        for (j in listOf("sl", "en", "de", "es", "fr", "it")) {
            val b = besedila.objekt(j) ?: run { napakV++; println("  NAPAKA  ni jezika $j"); null } ?: continue
            val r = viri(res, j)
            for (k in tv.kljuci().filter { it != "opis" }) {
                val ime = tv.niz(k)!!
                enako("$j $k ($ime)", b.niz(k)?.let(::vAndroid), r[ime])
            }
        }
    }

    println(if (napakV == 0) "\nVSE OK" else "\nNAPAK: $napakV")
    if (napakV > 0) kotlin.system.exitProcess(1)
}
