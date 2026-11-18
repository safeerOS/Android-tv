package si.safeer.tv.os

// Slepi pregled TV 18. 11. 2026, N5: ostanek toka med okvirji ima zgornjo mejo (brez nje OOM na 2 GB TV).
private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    println("ZaslonOstanek")
    val a = ByteArray(10) { 1 }; val b = ByteArray(5) { 2 }
    preveri("majhna ostanka se zdruzita", ZaslonOstanek.dodaj(a, b).size == 15 && ZaslonOstanek.dodaj(a, b)[10] == 2.toByte())
    var o = ByteArray(0)
    val okvir = ByteArray(8 * 1024 * 1024)            // najvecji dovoljen okvir, brez zacetne kode NAL
    var najvec = 0
    repeat(50) { o = ZaslonOstanek.dodaj(o, okvir); if (o.size > najvec) najvec = o.size }
    preveri("50 okvirjev brez zacetne kode: ostanek ne preseze ${ZaslonOstanek.NAJVEC} (najvec $najvec)", najvec <= ZaslonOstanek.NAJVEC)
    preveri("najnovejsi okvir se ohrani", o.size >= okvir.size)
    val pol = ByteArray(ZaslonOstanek.NAJVEC)
    preveri("tocno na meji se se zdruzi", ZaslonOstanek.dodaj(ByteArray(ZaslonOstanek.NAJVEC - 3), ByteArray(3)).size == ZaslonOstanek.NAJVEC)
    preveri("cez mejo ostane samo novo", ZaslonOstanek.dodaj(pol, b).size == 5)
    if (napak > 0) { println("PADLO: $napak"); kotlin.system.exitProcess(1) }
    println("VSE OK")
}
