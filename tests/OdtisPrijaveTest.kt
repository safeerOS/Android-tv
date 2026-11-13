package si.safeer.tv.cast

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

/** Neodvisni pregled 12. 11. 2026 (S2): podpis prijave veze izziv na odtis, ki ga je naprava VIDELA v TLS, ne na navedbo druge strani. */
fun main(args: Array<String>) {
    val moj = "aa".repeat(32)
    val tuj = "bb".repeat(32)
    preveri(OdtisPrijave.zaPodpis(moj, tuj) == moj, "polje fp druge strani se ne uporabi")
    preveri(OdtisPrijave.zaPodpis(moj, null) == moj && OdtisPrijave.zaPodpis(moj, "") == moj, "brez fp: opazen odtis")
    preveri(OdtisPrijave.zaPodpis(null, tuj) == null && OdtisPrijave.zaPodpis("  ", tuj) == null, "brez opazenega odtisa: prijave s podpisom ni (fp ne nadomesti)")
    preveri(OdtisPrijave.zaPodpis(moj.uppercase(), null) == moj, "odtis v malih crkah (kot ga podpisuje KrogNaprave)")
    // Vir: nobeno od treh mest ne prebere fp iz odgovora za podpis
    val cast = java.io.File(args.firstOrNull() ?: ".", "si/safeer/tv/cast")
    for (ime in listOf("HubMesh.kt", "CastReceiverService.kt")) {
        val v = java.io.File(cast, ime).readText()
        val vrstice = v.lines().filter { "optString(\"fp\")" in it }
        preveri(vrstice.isNotEmpty() && vrstice.all { "OdtisPrijave.zaPodpis(" in it }, "$ime: fp iz JSON samo kot argument OdtisPrijave: $vrstice")
        preveri(!v.contains("""optString("fp").ifBlank"""), "$ime: fp ne nadomesti odtisa")
    }
    preveri(java.io.File(cast, "CastReceiverService.kt").readText().lines().count { "OdtisPrijave.zaPodpis(zaupnikHuba?.videni" in it } == 2, "sprejemnik: oba podpisa z odtisom iz TLS")
    println("OdtisPrijaveTest: OK ($stevilo preverb)")
}
