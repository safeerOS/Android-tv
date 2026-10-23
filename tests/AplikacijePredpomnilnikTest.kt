package si.safeer.tv.os

import si.safeer.tv.os.AplikacijePredpomnilnik.MajhenLru
import si.safeer.tv.os.AplikacijePredpomnilnik.Zapis

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

/** Preizkus [AplikacijePredpomnilnik]: zapis zadnjega seznama aplikacij na disk in majhen LRU za ikone. */
fun main() {
    println("Preizkus predpomnilnika seznama aplikacij")

    val seznam = listOf(
        Zapis("com.a.prvi", "Prvi", "com.a.prvi.Glavna"),
        Zapis("com.b.drugi", "Čitalnik\tz tabulatorjem", "com.b.drugi.Zacetek"),
        Zapis("com.c.tretji", "Tretji", "com.c.tretji.Leanback"),
    )
    val besedilo = AplikacijePredpomnilnik.kodiraj(seznam)
    val nazaj = AplikacijePredpomnilnik.razcleni(besedilo)
    preveriEnako("krog: paket in razred ostaneta", seznam.map { it.paket to it.razred }, nazaj?.map { it.paket to it.razred })
    preveriEnako("krog: sumniki ostanejo", "Čitalnik z tabulatorjem", nazaj?.get(1)?.ime)
    preveriEnako("krog: vrstni red ostane", listOf("Prvi", "Čitalnik z tabulatorjem", "Tretji"), nazaj?.map { it.ime })

    preveriEnako("ni datoteke", null, AplikacijePredpomnilnik.razcleni(null))
    preveriEnako("druga oblika (brez glave)", null, AplikacijePredpomnilnik.razcleni("com.a\tX\tIme\n"))
    preveriEnako("druga stevilka razlicice", null, AplikacijePredpomnilnik.razcleni("SAFEER-APLIKACIJE 9\ncom.a\tX\tIme\n"))
    preveriEnako("samo glava = prazen seznam", emptyList<Zapis>(), AplikacijePredpomnilnik.razcleni("SAFEER-APLIKACIJE 1\n"))

    val pokvarjena = "SAFEER-APLIKACIJE 1\ncom.a\tX\tPrvi\n\nsamo-paket\n\tRazred\tBrez paketa\ncom.b\tY\tDrugi\n"
    preveriEnako("pokvarjene vrstice se preskocijo", listOf("com.a", "com.b"),
        AplikacijePredpomnilnik.razcleni(pokvarjena)?.map { it.paket })
    preveriEnako("prazno ime = paket", "com.x",
        AplikacijePredpomnilnik.razcleni("SAFEER-APLIKACIJE 1\ncom.x\tR\t\n")?.single()?.ime)
    preveriEnako("kodiranje izpusti vnos brez razreda", 1,
        AplikacijePredpomnilnik.razcleni(AplikacijePredpomnilnik.kodiraj(listOf(Zapis("a", "A", ""), Zapis("b", "B", "R"))))?.size)

    preveri("enako: isti seznam", AplikacijePredpomnilnik.enako(seznam, seznam.toList()))
    preveri("razlicno: drugo ime", !AplikacijePredpomnilnik.enako(seznam, seznam.map { if (it.paket == "com.a.prvi") it.copy(ime = "Novo") else it }))
    preveri("razlicno: dodana aplikacija", !AplikacijePredpomnilnik.enako(seznam, seznam + Zapis("com.d", "D", "R")))
    preveri("razlicno: odstranjena aplikacija", !AplikacijePredpomnilnik.enako(seznam, seznam.drop(1)))
    preveriEnako("brez: odstranitev paketa", listOf("com.a.prvi", "com.c.tretji"),
        AplikacijePredpomnilnik.brez(seznam, "com.b.drugi").map { it.paket })
    preveriEnako("brez: neznan paket nic ne spremeni", seznam, AplikacijePredpomnilnik.brez(seznam, "ni.ga"))

    // LRU: ob polnem pade najdlje neuporabljen, branje ga osvezi.
    val lru = MajhenLru<String, Int>(3)
    lru.dodaj("a", 1); lru.dodaj("b", 2); lru.dodaj("c", 3)
    lru.daj("a")                                    // a je zdaj najsvezejsi, b najstarejsi
    lru.dodaj("d", 4)
    preveriEnako("lru: najstarejsi izpade", null, lru.daj("b"))
    preveriEnako("lru: prebrani ostane", 1, lru.daj("a"))
    preveriEnako("lru: velikost omejena", 3, lru.velikost())
    lru.odstrani("a")
    preveriEnako("lru: odstrani", null, lru.daj("a"))
    lru.pocisti()
    preveriEnako("lru: pocisti", 0, lru.velikost())
    val veliki = MajhenLru<Int, Int>(64)
    for (i in 0 until 500) veliki.dodaj(i, i)
    preveriEnako("lru: 500 vnosov, hrani najvec 64", 64, veliki.velikost())
    preveri("lru: ostanejo najnovejsi", veliki.daj(499) == 499 && veliki.daj(0) == null)

    if (napak == 0) println("AplikacijePredpomnilnikTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
