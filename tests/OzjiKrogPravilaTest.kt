package si.safeer.tv.link

/**
 * Preizkus [OzjiKrogPravila] (isto kot tests/test_ozji_krog_naprave.py na racunalniku, lastnik 17. 10. 2026): programe,
 * datoteke in zaslon pokazemo samo za naprave v ozjem krogu te naprave; urejanje kroga v Nastavitvah.
 */
fun main() {
    val p = OzjiKrogPravila
    check(OzjiKrogPravila.KODA == "ni_ozji_krog") { "koda kot na racunalniku" }
    check(p.vKrogu(setOf("d"), taNaprava = false)) { "naprava z dovoljenjem je v krogu" }
    check(!p.vKrogu(emptySet<String>(), taNaprava = false)) { "naprava brez dovoljenja ni v krogu" }
    check(p.vKrogu(emptySet<String>(), taNaprava = true)) { "ta naprava je vedno v krogu" }

    // Seznam: ostanejo samo naprave v krogu, vrstni red ostane; napaka pri preverbi = ni v krogu (»ne vem« ni »da«).
    val krog = mapOf("n-tablica" to true, "n-tuji" to false, "n-racunalnik" to true)
    val izid = p.samoKrog(listOf("n-tablica", "n-tuji", "n-napaka", "n-racunalnik")) { id ->
        if (id == "n-napaka") throw IllegalStateException("branje") else krog.getValue(id)
    }
    check(izid == listOf("n-tablica", "n-racunalnik")) { "samoKrog: $izid" }

    // Urejanje v Nastavitvah: nova v krogu dobi vse, delni dostop ostane, odstranjena nic, nespremenjenih ni v izidu.
    val vse = setOf("d", "p", "v", "z")
    val prej = mapOf("n-delno" to setOf("d"), "n-polno" to vse, "n-zunaj" to emptySet(), "n-odstrani" to setOf("p", "z"))
    val sp = p.spremembe(prej, setOf("n-delno", "n-polno", "n-zunaj", "n-nova"), vse)
    check(sp == mapOf("n-zunaj" to vse, "n-nova" to vse, "n-odstrani" to emptySet())) { "spremembe: $sp" }
    check(p.spremembe(prej, setOf("n-delno", "n-polno", "n-odstrani"), vse).isEmpty()) { "brez sprememb ni zapisa" }
    check(p.spremembe(prej, emptySet(), vse).values.all { it.isEmpty() }) { "prazen krog: nihce nima nicesar" }

    // Ena privolitev v Linku (lastnik 25. 10. 2026): nova naprava v krogu ali na novo odprte datoteke -> vprasaj za dovoljenja.
    check(p.novaZmoznost(prej, sp, "d")) { "nova v krogu dobi datoteke" }
    check(!p.novaZmoznost(prej, mapOf("n-odstrani" to emptySet<String>()), "d")) { "odstranitev ne vprasa" }
    check(!p.novaZmoznost(prej, mapOf("n-delno" to setOf("d", "p")), "d")) { "datoteke ze prej: brez vprasanja" }
    check(p.novaZmoznost(prej, mapOf("n-odstrani" to setOf("p", "z", "d")), "d")) { "na novo odprte datoteke" }
    check(!p.novaZmoznost(prej, mapOf("n-zunaj" to setOf("p")), "d")) { "druga zmoznost ne vprasa za datoteke" }

    println("OzjiKrogPravilaTest: OK")
}
