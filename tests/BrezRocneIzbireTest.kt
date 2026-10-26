package si.safeer.tv.os

// Lastnik 26. 10. 2026 (NALOGA 33, »shrani in pozabi«): uporabnik vira oziroma toka nikoli ne izbira - Safeer sam izbere
// najboljsega, nedosegljivega zamenja naslednji. GlasbaActivity.kt potrebuje Android, zato preverjamo izvorno kodo in
// nize: menija »Izberi vir« ni, razresiStremio in izberiTok nimata rocne veje, seznama tokov za izbiro ni.
// »Izberi vire« (os_media_izberi_vire) je izbira virov knjiznice, ne toka - ostane.

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) =
    if (pogoj) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis") }

/** Telo funkcije [ime] (od deklaracije do naslednje deklaracije »private fun« na istem zamiku). */
private fun telo(koda: String, ime: String): String {
    val z = koda.indexOf("    private fun $ime(")
    if (z < 0) return ""
    val k = koda.indexOf("\n    private fun ", z + 1).let { if (it < 0) koda.length else it }
    return koda.substring(z, k)
}

fun main(args: Array<String>) {
    println("Preizkus: brez rocne izbire toka (NALOGA 33)")
    val koda = java.io.File(args[0]).readText().replace("\r\n", "\n")
    val nizi = java.io.File(args[1]).listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
        .mapNotNull { java.io.File(it, "os_glasba_strings.xml").takeIf { f -> f.isFile } }
    preveri("nizi najdeni (${nizi.size} jezikov)", nizi.size >= 6)
    preveri("meni nima »Izberi vir« (R.string.os_media_izberi_tok)", "os_media_izberi_tok" !in koda)
    preveri("niz os_media_izberi_tok odstranjen v vseh jezikih", nizi.none { "\"os_media_izberi_tok\"" in it.readText() })
    preveri("»Izberi vire« (viri knjiznice) ostane", nizi.all { "\"os_media_izberi_vire\"" in it.readText() })
    preveri("nihce ne klice razresiStremio z rocno izbiro", !Regex("""razresiStremio\([^)]*rocno""").containsMatchIn(koda))
    for (f in listOf("razresiStremio", "predpomnjeniZa", "izberiSezono", "izberiEpizodo", "epizodeNi", "izberiTok")) {
        val t = telo(koda, f)
        preveri("$f obstaja in nima rocne veje", t.isNotEmpty() && "rocno" !in t)
    }
    val izbira = telo(koda, "izberiTok")
    preveri("izberiTok ne pokaze seznama tokov (AlertDialog ... setItems)", "setItems" !in izbira && "AlertDialog" !in izbira)
    preveri("izberiTok brez predvajljivega toka pove »ni na voljo«", "os_media_ni_na_voljo" in izbira)
    if (napak == 0) println("BrezRocneIzbireTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
