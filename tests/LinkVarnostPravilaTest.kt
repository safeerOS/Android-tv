package si.safeer.tv.link

import si.safeer.tv.os.KnjiznicaKroga

/** Manjse varnostne odlocitve Safeer Linka iz pregleda 10. 11. 2026 (2.1, 2.2, 2.4, 3.1, 4.1). */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private fun vir(izvorna: String, pot: String): String = java.io.File(izvorna, pot).readText()

/** 2.2: zasebni prenos odstrani samo naprava, ki ga je narocila. */
private fun odstranitevPrenosa(izvorna: String) {
    val zaseben = KnjiznicaKroga.Opis("", "", "", "", true)
    val javen = KnjiznicaKroga.Opis("Sintel", "", "movie", "", false)
    preveri("zasebnega odstrani narocnik", KnjiznicaKroga.smeOdstraniti(zaseben, listOf("tablica"), "tablica"))
    preveri("zasebnega ne odstrani druga naprava", !KnjiznicaKroga.smeOdstraniti(zaseben, listOf("tablica"), "tv"))
    preveri("zasebnega ne odstrani naprava brez oznake", !KnjiznicaKroga.smeOdstraniti(zaseben, listOf("tablica"), ""))
    preveri("zasebnega brez narocnikov ne odstrani nobena druga naprava", !KnjiznicaKroga.smeOdstraniti(zaseben, emptyList(), "tv"))
    preveri("uporabnik te naprave odstrani tudi zasebnega", KnjiznicaKroga.smeOdstraniti(zaseben, listOf("tablica"), null))
    preveri("javnega odstrani vsaka naprava s predvajalnikom (kot prej)", KnjiznicaKroga.smeOdstraniti(javen, listOf("tablica"), "tv"))
    preveri("prenos brez opisa kot prej", KnjiznicaKroga.smeOdstraniti(null, emptyList(), "tv"))
    if (izvorna.isEmpty()) return
    val motor = vir(izvorna, "si/safeer/tv/os/MagnetMotor.kt")
    preveri("MagnetMotor pred odstranitvijo preveri narocnika",
        motor.substringAfter("fun odstraniZacasnega(c: Context, id: Int, vprasa: String? = null): Boolean {").trimStart()
            .startsWith("if (!smeOdstranitiZacasnega(c, id, vprasa)) return false"))
    preveri("magnet.remove poda posiljatelja", vir(izvorna, "si/safeer/tv/link/Daljinec.kt")
        .contains("MagnetPomoc.odstrani(context, parametri, parametri.optString(PARAM_POSILJATELJ, \"\"))"))
    preveri("MagnetPomoc odstrani v imenu posiljatelja", vir(izvorna, "si/safeer/tv/link/MagnetPomoc.kt").contains("MagnetMotor.odstraniZacasnega(app, id, posiljatelj)"))
}

/** 2.4: naslovi za sprejem zvoka so omejeni, brez te naprave same; glava ima skupen rok. */
private fun sprejemZvoka(izvorna: String) {
    val P = LinkVarnostPravila
    preveri("naslov iz host je prvi, brez ponovitev", P.nasloviZvoka("192.168.1.5", listOf("10.0.0.5", "192.168.1.5")) == listOf("192.168.1.5", "10.0.0.5"))
    val veliko = (1..200).map { "10.0.0.$it" }
    preveri("seznam je omejen", P.nasloviZvoka("", veliko).size == P.NAJVEC_NASLOVOV_ZVOKA && P.NAJVEC_NASLOVOV_ZVOKA <= 8)
    for (n in listOf("127.0.0.1", "localhost", "::1", "[::1]", "::ffff:127.0.0.1", "169.254.1.1", "0.0.0.0", "fe80::1"))
        preveri("zvok ne gre na $n", P.nasloviZvoka(n, emptyList()).isEmpty())
    preveri("neveljavni zapisi odpadejo", P.nasloviZvoka("a b", listOf("x/y", "ime@gost", "", "  ", "a".repeat(300))).isEmpty())
    preveri("ime in IPv6 v domacem omrezju ostaneta", P.nasloviZvoka("racunalnik.lan", listOf("fd00::5")) == listOf("racunalnik.lan", "fd00::5"))
    preveri("prepovedani ne zasedejo mest veljavnim", P.nasloviZvoka("127.0.0.1", listOf("localhost", "192.168.1.9")) == listOf("192.168.1.9"))
    preveri("rok se ni mimo", !P.rokPotekel(1000L, 1000L + P.ROK_GLAVE_ZVOKA_MS - 1, P.ROK_GLAVE_ZVOKA_MS))
    preveri("rok je mimo", P.rokPotekel(1000L, 1000L + P.ROK_GLAVE_ZVOKA_MS, P.ROK_GLAVE_ZVOKA_MS))
    preveri("ura nazaj steje kot mimo", P.rokPotekel(1000L, 999L, P.ROK_GLAVE_ZVOKA_MS))
    if (izvorna.isEmpty()) return
    val zvok = vir(izvorna, "si/safeer/tv/link/ZvokSprejemnik.kt")
    preveri("ZvokSprejemnik naslove omeji s pravilom", zvok.contains("val naslovi = LinkVarnostPravila.nasloviZvoka(p.optString(\"host\", \"\"), surovi)"))
    preveri("ZvokSprejemnik glavo bere s skupnim rokom", zvok.contains("LinkVarnostPravila.rokPotekel(zacetek,") && zvok.contains("if (potekel()) throw java.net.SocketTimeoutException"))
}

/** 4.1: v dnevnik brez zetonov, polnih oznak naprav in imen datotek. */
private fun dnevnik(izvorna: String) {
    val P = LinkVarnostPravila
    val polna = "n-0123456789abcdef-control"
    val kratka = P.oznakaZaDnevnik(polna)
    preveri("oznaka naprave brez sredine kljuca", kratka == "n-…cdef-control" && !kratka.contains("0123456789ab"))
    preveri("jedro brez programa", P.oznakaZaDnevnik("n-0123456789abcdef") == "n-…cdef")
    preveri("starejsa oznaka skrajsana", P.oznakaZaDnevnik("televizor-dnevna-soba") == "te…ba")
    preveri("kratka oznaka ne gre cela", P.oznakaZaDnevnik("abc") == "ab…")
    preveri("prazna oznaka", P.oznakaZaDnevnik("") == "(brez oznake)")
    preveri("prelom vrstice ne pride v dnevnik", !P.oznakaZaDnevnik("n-0123456789abcdef-a\nLAZNA VRSTICA").contains('\n'))
    preveri("dolga oznaka je omejena", P.oznakaZaDnevnik("n-0123456789abcdef-" + "x".repeat(500)).length <= 40)
    if (izvorna.isEmpty()) return
    val predaja = vir(izvorna, "si/safeer/tv/os/Predaja.kt")
    preveri("Predaja v dnevnik ne pise surovega odgovora (zeton streznika)", !predaja.contains("izid?.toString()"))
    preveri("Predaja v dnevnik ne pise polnih oznak, imen in naslovov naprav",
        predaja.lines().filter { it.trimStart().startsWith("log(") }.none { v ->
            listOf("\$posiljatelj", "\${cilj.id}", "\${n.id}", "\${it.id}", "\${it.ime}", "\${it.naslov}", ".naslov}").any { v.contains(it) } })
    // Dnevnik v link/ (brez datotek, ki jih ureja druga naloga): brez imen datotek, polnih oznak in besedila zetonov.
    val prepovedano = listOf("\$posiljatelj", "\${posiljatelj}", "\$ime", "\${t.ime}", "\${o.ime}", "\${t.datoteka.ime}", "\$zeton", "\${zeton}",
        "naslovi.joinToString()", "\$n:\$vrata")
    for (ime in listOf("MagnetPomoc.kt", "Pretok.kt", "Shramba.kt", "Pretvorba.kt", "ZvokSprejemnik.kt")) {
        val slabe = vir(izvorna, "si/safeer/tv/link/$ime").lines().filter { it.contains("Log.") && prepovedano.any { p -> it.contains(p) } }
        preveri("$ime: dnevnik brez imen datotek, polnih oznak in zetonov (${slabe.size})", slabe.isEmpty())
    }
}

/** 2.1: files.search ima isto dvojno preverbo umika kot files.list. */
private fun iskanje(izvorna: String) {
    if (izvorna.isEmpty()) return
    val isci = vir(izvorna, "si/safeer/tv/link/DatotekeStreznik.kt").substringAfter("fun isci(context: Context, poizvedba: String, idNaprave: String): JSONObject {")
        .substringBefore("private fun imeZbirke")
    preveri("isci zavrne umaknjeno napravo pred iskanjem",
        isci.contains("if (idNaprave.isNotBlank() && umaknjena(context, idNaprave))") &&
            isci.indexOf("umaknjena(context, idNaprave)") < isci.indexOf("KrajevneDatoteke.najdi"))
}

/** 3.1: cas sporocila klepeta je najvec zdaj. */
private fun casKlepeta(izvorna: String) {
    val P = LinkVarnostPravila
    val zdaj = "2026-11-10T18:00:00+00:00"
    preveri("pretekli cas posiljatelja ostane", P.casSporocila("2026-11-10T17:59:30Z", zdaj) == "2026-11-10T17:59:30+00:00")
    preveri("cas z delci sekunde in +00:00", P.casSporocila("2026-11-10T17:00:00.123+00:00", zdaj) == "2026-11-10T17:00:00+00:00")
    preveri("cas iz prihodnosti postane zdaj", P.casSporocila("2099-01-01T00:00:00Z", zdaj) == zdaj)
    preveri("sekunda v prihodnosti postane zdaj", P.casSporocila("2026-11-10T18:00:01Z", zdaj) == zdaj)
    preveri("tocno zdaj ostane", P.casSporocila("2026-11-10T18:00:00Z", zdaj) == zdaj)
    preveri("nerazumljiv cas je cas prejema", P.casSporocila("jutri", zdaj) == zdaj && P.casSporocila("", zdaj) == zdaj)
    preveri("cas brez UTC je cas prejema", P.casSporocila("2026-11-10T17:00:00+02:00", zdaj) == zdaj)
    if (izvorna.isEmpty()) return
    preveri("KlepetLinka cas omeji s pravilom", vir(izvorna, "si/safeer/tv/os/KlepetLinka.kt")
        .contains("LinkVarnostPravila.casSporocila(iso, SporocilaKanali.cas(null))"))
}

fun main(args: Array<String>) {
    val izvorna = args.firstOrNull().orEmpty()
    odstranitevPrenosa(izvorna)    // 2.2
    sprejemZvoka(izvorna)          // 2.4
    dnevnik(izvorna)               // 4.1
    iskanje(izvorna)               // 2.1
    casKlepeta(izvorna)            // 3.1
    println("LinkVarnostPravilaTest: OK ($preverb preverb)")
}
