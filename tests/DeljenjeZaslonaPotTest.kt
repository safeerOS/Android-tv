package si.safeer.tv.link

// Pregled 100, N1 in N2 (26. 10. 2026): storitev in Daljinec rabita Android, zato preizkus bere njuno izvorno kodo (kot
// preveri_*.py) - da pot res klice pravilo iz DeljenjePravila in v pravem vrstnem redu. Argumenti: DeljenjeZaslonaStoritev.kt, Daljinec.kt.

fun main(args: Array<String>) {
    // Lokalno v main: ime ne trci s pomocniki drugih preizkusov v istem paketu (vsi gredo v en jar).
    var napak = 0
    fun preveriEnako(dobljeno: Any?, pricakovano: Any?, opis: String) {
        if (dobljeno != pricakovano) {
            napak++
            println("NAPAKA: $opis: dobljeno=$dobljeno pricakovano=$pricakovano")
        }
    }
    /** Telo funkcije, ki se zacne z [glava] (od prvega '{' do ujemajocega '}'); prazno, ce je ni. */
    fun telo(koda: String, glava: String): String {
        val z = koda.indexOf(glava)
        if (z < 0) return ""
        val odprt = koda.indexOf('{', z)
        var globina = 0
        var i = odprt
        while (i < koda.length) {
            if (koda[i] == '{') globina++
            if (koda[i] == '}') { globina--; if (globina == 0) return koda.substring(odprt, i + 1) }
            i++
        }
        return ""
    }
    /** Ali je [prej] v [besedilo] pred [potem] (oba morata biti). */
    fun pred(besedilo: String, prej: String, potem: String): Boolean {
        val a = besedilo.indexOf(prej)
        val b = besedilo.indexOf(potem)
        return a >= 0 && b >= 0 && a < b
    }
    if (args.size < 2) { println("Uporaba: DeljenjeZaslonaPotTestKt <DeljenjeZaslonaStoritev.kt> <Daljinec.kt>"); kotlin.system.exitProcess(2) }
    val storitev = java.io.File(args[0]).readText()
    val daljinec = java.io.File(args[1]).readText()

    // N1: dejanje iz obvestila gre skozi pravilo s casom vnosa z daljave, pred zapisom zaupanja.
    val ukaz = telo(storitev, "override fun onStartCommand(")
    val zaupanje = ukaz.substring(maxOf(0, ukaz.indexOf("ACTION_ZAUPAJ")))
    preveriEnako(pred(zaupanje, "DeljenjePravila.zaupajIzObvestilaBrezVnosa(", "ZaupanjeZaslona.nastavi("), true,
        "N1: zaupajIzObvestilaBrezVnosa pred ZaupanjeZaslona.nastavi")
    preveriEnako(zaupanje.contains("Daljinec.zadnjiVnosNaDaljavoMs"), true, "N1: storitev poda cas zadnjega vnosa z daljave")
    preveriEnako(zaupanje.contains("android.os.SystemClock.elapsedRealtime()"), true, "N1: ista monotona ura kot zapis")
    preveriEnako(storitev.contains("DeljenjePravila.zaupajIzObvestila("), false, "N1: nobene poti mimo casa vnosa")

    // N1: vsak vnos z daljave se zapise, preden doseze Safeer Vnos.
    val vnos = telo(daljinec, "private fun vnos(")
    preveriEnako(pred(vnos, "zabeleziVnosNaDaljavo(", "VnosStoritev."), true, "N1: vnos zapise cas pred VnosStoritev")
    preveriEnako(pred(vnos, "zabeleziVnosNaDaljavo(", "izidBrezVnosa("), true, "N1: vnos zapise cas tudi, ce bo zavrnjen")
    val plosek = telo(daljinec, "private fun igralniPloskek(")
    preveriEnako(pred(plosek, "zabeleziVnosNaDaljavo(", "VnosStoritev."), true, "N1: igralni plosek zapise cas pred VnosStoritev")
    preveriEnako(telo(daljinec, "fun zabeleziVnosNaDaljavo(").contains("android.os.SystemClock.elapsedRealtime()"), true,
        "N1: zapis z monotono uro")

    // N2: po odklepu znak in preverba (U3), sele potem konec cakanja (nit toka takrat spet poslje prave okvirje).
    val nadaljuj = telo(storitev, "private fun nadaljuj(")
    preveriEnako(pred(nadaljuj, "pokaziZnak()", "koncajCakanje()"), true, "N2: pokaziZnak pred koncajCakanje")
    preveriEnako(pred(nadaljuj, "DeljenjePravila.znakZagotovljen(", "koncajCakanje()"), true, "N2: znakZagotovljen pred koncajCakanje")
    val poPreverbi = nadaljuj.substring(maxOf(0, nadaljuj.indexOf("DeljenjePravila.znakZagotovljen(")))
    preveriEnako(pred(poPreverbi, "koncaj(", "koncajCakanje()"), true, "N2: brez znaka koncaj pred koncajCakanje")
    preveriEnako(pred(poPreverbi, "return true", "koncajCakanje()"), true, "N2: brez znaka ne nadaljuje")

    // Prvi zagon ostane: znak in U3 pred nitjo toka.
    preveriEnako(pred(ukaz, "pokaziZnak()", "nit = Thread("), true, "prvi zagon: pokaziZnak pred nitjo")
    preveriEnako(pred(ukaz.substring(maxOf(0, ukaz.indexOf("tece = true"))), "DeljenjePravila.znakZagotovljen(", "nit = Thread("), true,
        "prvi zagon: znakZagotovljen pred nitjo")

    if (napak > 0) { println("DeljenjeZaslonaPotTest: $napak napak"); kotlin.system.exitProcess(1) }
    println("DeljenjeZaslonaPotTest: OK")
}
