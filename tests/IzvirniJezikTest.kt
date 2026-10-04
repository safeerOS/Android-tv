package si.safeer.tv.os

fun main() {
    val j = IzvirniJezik
    // Seznam jezikov: kode so enolicne, dvocrkovne; vsak jezik ima predmet in vsaj eno domaco drzavo.
    check(j.KODE.distinct().size == j.KODE.size && j.KODE.all { it.length == 2 && it == it.lowercase() })
    check(j.JEZIKI.all { it.predmeti.isNotEmpty() && it.drzave.isNotEmpty() && (it.predmeti + it.drzave).all { q -> Regex("^Q[0-9]+$").matches(q) } })
    check(j.poKodi("sl")?.predmeti == listOf("Q9063") && j.poKodi("xx") == null)

    // Glavni jezik: en sam jezik je glavni, tudi ce drzave ne poznamo.
    check(j.glavni(listOf("Q1860"), listOf("Q30")) == listOf("en"))
    check(j.glavni(listOf("Q9176"), emptyList()) == listOf("ko"))
    // Vec jezikov: zmaga tisti, ki je doma v drzavi izvora (ameriski film z nekaj francoscine in japonscine je angleski).
    check(j.glavni(listOf("Q150", "Q1860", "Q5287"), listOf("Q30", "Q145")) == listOf("en"))
    check(j.glavni(listOf("Q652", "Q1860"), listOf("Q30")) == listOf("en"))
    check(j.glavni(listOf("Q1860", "Q5287"), listOf("Q17")) == listOf("ja"))
    // Vec domacih jezikov: anglescina (ameriski film z nemskim soproducentom ni nemski film).
    check(j.glavni(listOf("Q150", "Q188", "Q1860"), listOf("Q30", "Q183")) == listOf("en"))
    // Brez anglescine ostaneta oba (belgijski film v francoscini in nizozemscini).
    check(j.glavni(listOf("Q150", "Q7411"), listOf("Q31")).toSet() == setOf("fr", "nl"))
    // Nobeden ni doma v drzavi izvora: anglescina, sicer vsi.
    check(j.glavni(listOf("Q5287", "Q1860"), listOf("Q142")) == listOf("en"))
    check(j.glavni(listOf("Q5287", "Q9176"), listOf("Q142")).toSet() == setOf("ja", "ko"))
    // Srbohrvascina: jugoslovanski film sodi pod hrvascino, srbscino in bosanscino; hrvaski samo pod hrvascino.
    check(j.glavni(listOf("Q9301"), listOf("Q83286")).toSet() == setOf("hr", "sr", "bs"))
    check(j.glavni(listOf("Q9301"), listOf("Q224")) == listOf("hr"))
    check(j.glavni(listOf("Q9301"), emptyList()).toSet() == setOf("hr", "sr", "bs"))
    check(j.glavni(listOf("Q9301", "Q9063"), listOf("Q83286")).toSet() == setOf("sl", "hr", "sr", "bs"))
    // Slovenski film.
    check(j.glavni(listOf("Q9063"), listOf("Q215")) == listOf("sl"))
    // Kitajscina v vec predmetih (mandarinscina, kantonscina) je en jezik izbire.
    check(j.glavni(listOf("Q9192", "Q9186"), listOf("Q8646")) == listOf("zh"))
    // Jezik, ki ga izbira ne ponuja, ali brez jezika: ne vemo.
    check(j.glavni(listOf("Q123456789"), listOf("Q30")).isEmpty() && j.glavni(emptyList(), listOf("Q30")).isEmpty())

    // Poizvedba za paket: samo veljavni id-ji IMDb, brez podvojitev, najvec NAJVEC_V_PAKETU.
    val p = j.poizvedbaPaket(listOf("tt0111161", "tt0111161", "tt1375666", "nm0000001", "tt12\" } DROP", "", "tt123"))
    check(p.contains("VALUES ?imdb { \"tt0111161\" \"tt1375666\" }") && !p.contains("nm0000001") && !p.contains("DROP"))
    check(p.contains("wdt:P345") && p.contains("wdt:P364") && p.contains("wdt:P495"))
    val veliko = j.poizvedbaPaket((1..200).map { "tt%07d".format(it) })
    check(Regex("\"tt[0-9]+\"").findAll(veliko).count() == j.NAJVEC_V_PAKETU)
    check(veliko.length < 4000)

    // Odgovor paketa (resnicen primer, 4. 10. 2026): krizni produkt jezikov in drzav; neznan naslov manjka.
    val q = "http://www.wikidata.org/entity/"
    val odgovor = listOf("?imdb\t?jezik\t?drzava",
        "\"tt1375666\"\t<${q}Q150>\t<${q}Q30>", "\"tt1375666\"\t<${q}Q1860>\t<${q}Q30>", "\"tt1375666\"\t<${q}Q5287>\t<${q}Q30>",
        "\"tt1375666\"\t<${q}Q150>\t<${q}Q145>", "\"tt1375666\"\t<${q}Q1860>\t<${q}Q145>", "\"tt1375666\"\t<${q}Q5287>\t<${q}Q145>",
        "\"tt0361748\"\t<${q}Q150>\t<${q}Q30>", "\"tt0361748\"\t<${q}Q188>\t<${q}Q30>", "\"tt0361748\"\t<${q}Q1860>\t<${q}Q30>",
        "\"tt0361748\"\t<${q}Q150>\t<${q}Q183>", "\"tt0361748\"\t<${q}Q188>\t<${q}Q183>", "\"tt0361748\"\t<${q}Q1860>\t<${q}Q183>",
        "\"tt0245429\"\t<${q}Q5287>\t<${q}Q17>", "\"tt0111161\"\t<${q}Q1860>\t<${q}Q30>", "\"tt6751668\"\t<${q}Q9176>\t<${q}Q884>",
        "\"tt7654321\"\t\t<${q}Q30>", "\"tt7654322\"\t\t", "", "smeti brez tabulatorja").joinToString("\n")
    val jeziki = j.izPaketa(odgovor)
    check(jeziki["tt1375666"] == listOf("en") && jeziki["tt0361748"] == listOf("en") && jeziki["tt0245429"] == listOf("ja"))
    check(jeziki["tt0111161"] == listOf("en") && jeziki["tt6751668"] == listOf("ko"))
    // Naslov, ki ga Wikidata pozna, a brez jezika: prazen seznam (ne vemo). Naslov, ki ga ni v odgovoru: manjka.
    check(jeziki["tt7654321"] == emptyList<String>() && jeziki["tt7654322"] == emptyList<String>() && "tt9999999" !in jeziki)
    check(jeziki.size == 7)
    check(j.izPaketa("").isEmpty() && j.izPaketa("?imdb\t?jezik\t?drzava\n").isEmpty())

    // Poizvedba za seznam: jezik, njegove domace drzave, razredi filma ali serije, urejeno po prepoznavnosti, stran.
    val sl = j.poKodi("sl")!!
    val s = j.poizvedbaSeznam(sl, "movie", 60)
    check(s.contains("VALUES ?jezik { wd:Q9063 }") && s.contains("VALUES ?drzava { wd:Q215 wd:Q36704 wd:Q83286 }"))
    check(s.contains("wd:Q11424") && !s.contains("wd:Q5398426") && s.endsWith("LIMIT 60 OFFSET 60"))
    // Naslov karte: jezik vmesnika, sicer angleski, sicer domaci; brez podvojenih oznak in brez tujega besedila v poizvedbi.
    check(s.contains("LANG(?o0) = \"en\"") && s.contains("LANG(?o1) = \"sl\"") && s.contains("COALESCE(?o0, ?o1)") && !s.contains("?o2"))
    val nem = j.poizvedbaSeznam(j.poKodi("de")!!, "movie", vmesnik = "sl")
    check(nem.contains("LANG(?o0) = \"sl\"") && nem.contains("LANG(?o1) = \"en\"") && nem.contains("LANG(?o2) = \"de\"") && nem.contains("COALESCE(?o0, ?o1, ?o2)"))
    check(j.poizvedbaSeznam(sl, "movie", vmesnik = "sl\") } DROP").let { it.contains("LANG(?o0) = \"en\"") && !it.contains("DROP") })
    val serije = j.poizvedbaSeznam(j.poKodi("de")!!, "series")
    check(serije.contains("wd:Q5398426") && !serije.contains("wd:Q11424 ") && serije.endsWith("LIMIT 60 OFFSET 0"))
    check(j.poizvedbaSeznam(sl, "movie", -5, 100000).endsWith("LIMIT 200 OFFSET 0"))

    // Odgovor seznama (resnicen primer): naslov z oznako jezika, leto, stevilo clankov; podvojen id enkrat.
    val seznam = listOf("?imdb\t?naslov\t?leto\t?pov",
        "\"tt1224373\"\t\"Slovenian Girl\"@en\t2009\t13", "\"tt0043703\"\t\"Kekec\"@en\t1951\t10",
        "\"tt0043703\"\t\"Kekec\"@en\t1951\t10", "\"tt0055309\"\t\"Ples v dežju\"@sl\t\t11",
        "\"tt0049150\"\t\"Dolina \\\"miru\\\"\\tin \\\\ se\"@sl\t1956\t10", "\"tt0000001\"\t\t1900\t1", "\"nm0000001\"\t\"Oseba\"@en\t1950\t3").joinToString("\n")
    val naslovi = j.izSeznama(seznam)
    check(naslovi.map { it.imdb } == listOf("tt1224373", "tt0043703", "tt0055309", "tt0049150"))
    check(naslovi[0].naslov == "Slovenian Girl" && naslovi[0].leto == 2009 && naslovi[2].leto == 0)
    check(naslovi[3].naslov == "Dolina \"miru\" in \\ se")
    check(j.celica("<http://www.wikidata.org/entity/Q42>") == "Q42" && j.celica("2009") == "2009" && j.celica("\"a\"^^<x>") == "a")

    // Predpomnilnik: znan jezik velja dolgo, neznan nekaj dni; poskodovan zapis ali zapis iz prihodnosti ne velja.
    val zdaj = 2_000L * 86_400_000
    check(j.izZapisa(j.vZapis(listOf("en"), zdaj), zdaj) == listOf("en"))
    check(j.izZapisa(j.vZapis(listOf("hr", "sr", "bs"), zdaj), zdaj + 100L * 86_400_000) == listOf("hr", "sr", "bs"))
    check(j.izZapisa(j.vZapis(listOf("en"), zdaj), zdaj + j.ZNAN_VELJA_MS + 1) == null)
    check(j.izZapisa(j.vZapis(emptyList(), zdaj), zdaj + 86_400_000) == emptyList<String>())
    check(j.izZapisa(j.vZapis(emptyList(), zdaj), zdaj + j.NEZNAN_VELJA_MS + 1) == null)
    check(j.izZapisa(j.vZapis(listOf("xx", "en"), zdaj), zdaj) == listOf("en"))
    check(j.izZapisa(null, zdaj) == null && j.izZapisa("en", zdaj) == null && j.izZapisa("en;x", zdaj) == null)
    check(j.izZapisa(j.vZapis(listOf("en"), zdaj + 5_000), zdaj) == null)
    println("IzvirniJezikTest: OK")
}
