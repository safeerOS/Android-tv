package si.safeer.tv

import java.io.File

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    val svoj = "si.safeer.tv"
    preveri("Safeer OS z enakim podpisom velja", SafeerIzvor.jeZaupan("si.safeer.os", svoj, true))
    preveri("Safeer OS z drugacnim podpisom NE velja", !SafeerIzvor.jeZaupan("si.safeer.os", svoj, false))
    preveri("tuj paket z ujemanjem podpisa NE velja (predpona)", !SafeerIzvor.jeZaupan("com.napadalec", svoj, true))
    preveri("predpona brez imena NE velja", !SafeerIzvor.jeZaupan("si.safeer.", svoj, true))
    preveri("podoben paket brez pike NE velja", !SafeerIzvor.jeZaupan("si.safeerx.os", svoj, true))
    preveri("null in prazno NE veljata", !SafeerIzvor.jeZaupan(null, svoj, true) && !SafeerIzvor.jeZaupan(" ", svoj, true))
    preveri("lasten paket velja (obstojec izhod finish())", SafeerIzvor.jeZaupan(svoj, svoj, false))
    // Vir: MainActivity res uporabi preverbo, preden zapomni paket.
    val vir = File(System.getProperty("main.vir") ?: "src/main/kotlin/si/safeer/tv/MainActivity.kt").readText()
    val i = vir.indexOf("private fun zapomniIzvor")
    val telo = if (i >= 0) vir.substring(i, minOf(vir.length, i + 700)) else ""
    preveri("zapomniIzvor kliče SafeerIzvor.jeZaupan", telo.contains("SafeerIzvor.jeZaupan"))
    if (napak > 0) { println("NAPAK: $napak"); System.exit(1) } else println("Vsi preizkusi so uspesni")
}
