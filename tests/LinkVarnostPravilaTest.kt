package si.safeer.tv.link

import si.safeer.tv.os.KnjiznicaKroga

/** Manjse varnostne odlocitve Safeer Linka iz pregleda 10. 11. 2026 (2.2, 2.4). */
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

fun main(args: Array<String>) {
    val izvorna = args.firstOrNull().orEmpty()
    odstranitevPrenosa(izvorna)    // 2.2
    sprejemZvoka(izvorna)          // 2.4
    println("LinkVarnostPravilaTest: OK ($preverb preverb)")
}
