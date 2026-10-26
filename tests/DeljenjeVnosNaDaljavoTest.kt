package si.safeer.tv.link

// Pregled 100, N1 (26. 10. 2026): »Vedno dovoli« iz obvestila ne velja tik po vnosu z druge naprave (gledalec bi ga tapnil
// sam s Safeer Vnosom); preklic, tap uporabnika brez vnosa z daljave in stara pravila zaupajIzObvestila ostanejo.

/** Tekoce deljenje napravi »pc«, ponudba velja, naprava odklenjena - edino se cas vnosa spreminja. */
private fun zaupaj(zaupaj: Boolean, zadnjiVnosMs: Long, zdajMs: Long): Boolean =
    DeljenjePravila.zaupajIzObvestilaBrezVnosa(zaupaj, true, "pc", "pc", false, true, false, zadnjiVnosMs, zdajMs)

fun main() {
    // Lokalno v main: ime ne trci s pomocniki drugih preizkusov v istem paketu (vsi gredo v en jar).
    var napak = 0
    fun preveriEnako(dobljeno: Any?, pricakovano: Any?, opis: String) {
        if (dobljeno != pricakovano) {
            napak++
            println("NAPAKA: $opis: dobljeno=$dobljeno pricakovano=$pricakovano")
        }
    }
    val okno = DeljenjePravila.OKNO_VNOSA_MS
    val zdaj = 1_000_000L

    // Jedro N1: gledalec tapne »Vedno dovoli« s Safeer Vnosom - storitev namero dobi v nekaj ms.
    preveriEnako(zaupaj(true, zdaj - 20, zdaj), false, "tap z daljave tik pred namero ne zaupa")
    preveriEnako(zaupaj(true, zdaj, zdaj), false, "vnos v isti ms ne zaupa")
    preveriEnako(zaupaj(true, zdaj - okno, zdaj), false, "na meji okna se ne zaupa")
    // Dolga gesta tapne ob koncu: konec je v prihodnosti glede na zacetek.
    val dolga = DeljenjePravila.konecVnosa(zdaj, 10_000L)
    preveriEnako(zaupaj(true, dolga, zdaj + 9_000L), false, "dolg dotik (10 s) med gesto ne zaupa")
    preveriEnako(zaupaj(true, dolga, dolga + 1_000L), false, "dolg dotik tik po koncu geste ne zaupa")

    // Ostane: tap uporabnika brez vnosa z daljave (navaden lokalni tap).
    preveriEnako(zaupaj(true, 0L, zdaj), true, "brez vnosa z daljave lokalni tap zaupa")
    preveriEnako(zaupaj(true, zdaj - okno - 1, zdaj), true, "po oknu lokalni tap zaupa")
    // Ostane: preklic velja vedno, tudi tik po vnosu z daljave (zaupanje samo odvzame).
    preveriEnako(zaupaj(false, zdaj - 20, zdaj), true, "preklic tik po vnosu z daljave velja")
    preveriEnako(zaupaj(false, 0L, zdaj), true, "preklic brez vnosa velja")
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(false, true, "pc", "pc", true, false, true, zdaj, zdaj), true,
        "preklic med cakanjem na odklep in zaklenjeno velja")

    // Stara pravila (U5, N11) ostanejo tudi brez vnosa z daljave.
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(true, true, "pc", "tv", false, true, false, 0L, zdaj), false,
        "namera za drugo napravo ne zaupa")
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(true, true, "pc", "pc", true, true, false, 0L, zdaj), false,
        "med cakanjem na odklep ne zaupa")
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(true, true, "pc", "pc", false, false, false, 0L, zdaj), false,
        "brez ponudbe ne zaupa")
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(true, true, "pc", "pc", false, true, true, 0L, zdaj), false,
        "zaklenjeno ne zaupa")
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(false, false, "pc", "pc", false, true, false, 0L, zdaj), false,
        "preklic brez tekocega deljenja ne velja (staro obvestilo)")
    preveriEnako(DeljenjePravila.zaupajIzObvestilaBrezVnosa(false, true, "", "", false, true, false, 0L, zdaj), false,
        "prazen cilj ne velja")

    // Trajanje z omrezja je omejeno (stevilo z omrezja): ne negativno, ne vec kot najdaljsa gesta Androida.
    preveriEnako(DeljenjePravila.konecVnosa(zdaj, -5L), zdaj, "negativno trajanje = 0")
    preveriEnako(DeljenjePravila.konecVnosa(zdaj, Long.MAX_VALUE), zdaj + DeljenjePravila.NAJDALJSA_GESTA_MS, "trajanje odrezano")
    preveriEnako(DeljenjePravila.konecVnosa(zdaj, 60L), zdaj + 60L, "kratek dotik")

    if (napak > 0) { println("DeljenjeVnosNaDaljavoTest: $napak napak"); kotlin.system.exitProcess(1) }
    println("DeljenjeVnosNaDaljavoTest: OK")
}
