package si.safeer.tv.link

/**
 * Pregled 10. 11. 2026 (1.2, 3.3): meje prenosov, ki jih druga naprava zacne na tej napravi (Shramba, Pretvorba).
 */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private const val GB = 1024L * 1024 * 1024

private fun velikost() {
    val najvec = PrenosPravila.NAJVEC_SHRAMBA_B
    preveri("velikost 0 ni dovoljena", PrenosPravila.preveriVelikost(0L, najvec, 100 * GB, 2 * GB) == "napacna_zahteva")
    preveri("negativna velikost ni dovoljena", PrenosPravila.preveriVelikost(-1L, najvec, 100 * GB, 2 * GB) == "napacna_zahteva")
    preveri("manjkajoca velikost (privzeto -1) ni dovoljena", PrenosPravila.preveriVelikost(-1L, najvec, -1L, 2 * GB) == "napacna_zahteva")
    preveri("nad zgornjo mejo", PrenosPravila.preveriVelikost(najvec + 1, najvec, 1000 * GB, 2 * GB) == "prevelika")
    preveri("tocno zgornja meja gre", PrenosPravila.preveriVelikost(najvec, najvec, 1000 * GB, 2 * GB) == null)
    preveri("Long.MAX ne preplavi racuna", PrenosPravila.preveriVelikost(Long.MAX_VALUE, najvec, 1000 * GB, 2 * GB) == "prevelika")
    preveri("dovolj prostora", PrenosPravila.preveriVelikost(1 * GB, najvec, 4 * GB, 2 * GB) == null)
    preveri("tocno do rezerve gre", PrenosPravila.preveriVelikost(2 * GB, najvec, 4 * GB, 2 * GB) == null)
    preveri("en bajt cez rezervo ne gre", PrenosPravila.preveriVelikost(2 * GB + 1, najvec, 4 * GB, 2 * GB) == "ni_prostora")
    preveri("prostor ni izmerjen: ne", PrenosPravila.preveriVelikost(1L, najvec, -1L, 2 * GB) == "ni_prostora")
    preveri("prostega manj od rezerve: ne", PrenosPravila.preveriVelikost(1L, najvec, 1 * GB, 2 * GB) == "ni_prostora")
    preveri("tekoci prenosi stejejo", PrenosPravila.preveriVelikost(1 * GB, najvec, 4 * GB, 2 * GB, obljubljeno = 1 * GB + 1) == "ni_prostora")
    preveri("tekoci prenosi stejejo (se gre)", PrenosPravila.preveriVelikost(1 * GB, najvec, 4 * GB, 2 * GB, obljubljeno = 1 * GB) == null)
    // Pretvorba: izvirnik in pretvorjeni video hkrati.
    val p = PrenosPravila.NAJVEC_PRETVORBA_B
    preveri("pretvorba: velikost 0 ni dovoljena", PrenosPravila.preveriVelikost(0L, p, 100 * GB, GB, mnozitelj = 2) == "napacna_zahteva")
    preveri("pretvorba: dvakratna velikost + rezerva", PrenosPravila.preveriVelikost(2 * GB, p, 5 * GB, GB, mnozitelj = 2) == null)
    preveri("pretvorba: premalo za dvakratno velikost", PrenosPravila.preveriVelikost(2 * GB, p, 5 * GB - 1, GB, mnozitelj = 2) == "ni_prostora")
    preveri("pretvorba: nad mejo", PrenosPravila.preveriVelikost(p + 1, p, 1000 * GB, GB, mnozitelj = 2) == "prevelika")
}

private fun branje() {
    preveri("kos do napovedi gre", !PrenosPravila.presega(900L, 100, 1000L))
    preveri("kos cez napoved prekine", PrenosPravila.presega(900L, 101, 1000L))
    preveri("prvi kos cez napoved prekine", PrenosPravila.presega(0L, 2, 1L))
    preveri("glava Content-Length cez napoved", PrenosPravila.glavaPresega(1001L, 1000L))
    preveri("glava enaka napovedi", !PrenosPravila.glavaPresega(1000L, 1000L))
    preveri("glava neznana (-1) ne zavrne", !PrenosPravila.glavaPresega(-1L, 1000L))
    // Zanka kot v Shramba.prenesi: vir poslje 10-krat vec, zapisano ne preseze napovedi.
    val napovedano = 1000L
    var zapisano = 0L
    var prekinjeno = false
    val kos = 256
    var poslano = 0L
    while (poslano < 10_000L && !prekinjeno) {
        poslano += kos
        if (PrenosPravila.presega(zapisano, kos, napovedano)) prekinjeno = true else zapisano += kos
    }
    preveri("predolg tok se prekine", prekinjeno)
    preveri("na disk gre najvec napovedano", zapisano <= napovedano)
    preveri("med pretvorbo: prostega manj od rezerve ustavi", PrenosPravila.zmanjkujeProstora(GB - 1, GB))
    preveri("med pretvorbo: prostega dovolj", !PrenosPravila.zmanjkujeProstora(GB, GB))
    preveri("med pretvorbo: brez meritve ne ustavi", !PrenosPravila.zmanjkujeProstora(-1L, GB))
}

private fun socasnost() {
    preveri("prvi prenos gre", !PrenosPravila.preobremenjen(0, PrenosPravila.NAJVEC_PRENOSOV))
    preveri("drugi prenos gre", !PrenosPravila.preobremenjen(1, PrenosPravila.NAJVEC_PRENOSOV))
    preveri("tretji prenos ne gre", PrenosPravila.preobremenjen(2, PrenosPravila.NAJVEC_PRENOSOV))
    preveri("najvec dva prenosa", PrenosPravila.NAJVEC_PRENOSOV == 2)
    preveri("prva pretvorba gre", !PrenosPravila.preobremenjen(0, PrenosPravila.NAJVEC_PRETVORB))
    preveri("druga pretvorba ne gre", PrenosPravila.preobremenjen(1, PrenosPravila.NAJVEC_PRETVORB))
    preveri("najvec ena pretvorba", PrenosPravila.NAJVEC_PRETVORB == 1)
    preveri("koda zavrnitve", PrenosPravila.KODA_PREOBREMENJEN == "preobremenjen")
}

private fun izrinjanje() {
    fun o(id: String, nastal: Long, koncano: Boolean) = PrenosPravila.Opis(id, nastal, koncano)
    preveri("pod mejo nic ne gre", PrenosPravila.izrini(listOf(o("a", 1, true), o("b", 2, false)), 2).isEmpty())
    // Najstarejse opravilo se tece: ne gre, gre najstarejse KONCANO.
    val seznam = listOf(o("tece-staro", 1, false), o("koncano-1", 2, true), o("koncano-2", 3, true), o("tece-novo", 4, false))
    preveri("gre najstarejse koncano, ne najstarejse", PrenosPravila.izrini(seznam, 3) == listOf("koncano-1"))
    preveri("dve odvec: obe koncani po vrsti", PrenosPravila.izrini(seznam, 2) == listOf("koncano-1", "koncano-2"))
    preveri("tekoce ne gre nikoli", PrenosPravila.izrini(seznam, 0) == listOf("koncano-1", "koncano-2"))
    preveri("sama tekoca: nic", PrenosPravila.izrini(listOf(o("x", 1, false), o("y", 2, false)), 1).isEmpty())
    val triintrideset = (1..33).map { o("p$it", it.toLong(), it != 1) }
    preveri("33 opravil, prvo se tece: gre drugo", PrenosPravila.izrini(triintrideset, PrenosPravila.HRANI_PRENOSOV) == listOf("p2"))
}

/** Pravila morajo biti v Shrambi in Pretvorbi res uporabljena (razreda rabita Android in tu ne teceta). */
private fun uporaba(izvorna: String) {
    if (izvorna.isEmpty()) return
    val shramba = java.io.File(izvorna, "si/safeer/tv/link/Shramba.kt").readText()
    val pretvorba = java.io.File(izvorna, "si/safeer/tv/link/Pretvorba.kt").readText()
    for ((ime, vir) in listOf("Shramba" to shramba, "Pretvorba" to pretvorba)) {
        preveri("$ime preveri napovedano velikost", vir.contains("PrenosPravila.preveriVelikost(velikost, PrenosPravila.NAJVEC_"))
        preveri("$ime prekine predolg tok", vir.contains("PrenosPravila.presega(") && vir.contains("PrenosPravila.glavaPresega("))
        preveri("$ime omeji socasna opravila", vir.contains("PrenosPravila.preobremenjen("))
        preveri("$ime izriva samo koncana", vir.contains("PrenosPravila.izrini(") && !vir.contains("opravila.keys.firstOrNull()"))
        preveri("$ime ne ustvarja niti brez meje", !vir.contains("Thread({") && vir.contains("izvajalec.execute"))
    }
    preveri("Pretvorba med pretvorbo pazi na prostor", pretvorba.contains("PrenosPravila.zmanjkujeProstora("))
}

fun main(args: Array<String>) {
    velikost()
    branje()
    socasnost()
    izrinjanje()
    uporaba(args.firstOrNull().orEmpty())
    println("PrenosPravilaTest: OK ($preverb preverb)")
}
