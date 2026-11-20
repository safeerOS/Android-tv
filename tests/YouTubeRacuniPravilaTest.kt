import si.safeer.tv.os.YouTubeRacuniPravila as P

fun preveri(pogoj: Boolean, opis: String) { if (!pogoj) throw AssertionError(opis) }

fun main() {
    val a = "SID=x; SAPISID=abc123; LOGIN_INFO=q"
    preveri(P.idRacuna(a) == P.idRacuna("LOGIN_INFO=z; SAPISID=abc123"), "isti SAPISID = isti racun")
    preveri(P.idRacuna(a) != P.idRacuna("SAPISID=drugi"), "drug SAPISID = drug racun")
    preveri(P.idRacuna("SID=x") == null, "brez SAPISID ni id-ja")
    preveri(P.idRacuna(a)!!.length == 16 && !P.idRacuna(a)!!.contains("abc123"), "id je odtis, ne vrednost")
    preveri(P.imenaPiskotkov(a) == listOf("SID", "SAPISID", "LOGIN_INFO"), "imena")
    preveri(P.imenaPiskotkov("") .isEmpty(), "prazno")
    preveri(P.predponaSeznama("Racun 1", "Racun 1") == "", "prvi brez predpone")
    preveri(P.predponaSeznama("Racun 2", "Racun 1") == "Racun 2 · ", "drugi s predpono")
    preveri(P.privzetoIme(3, "Racun %d") == "Racun 3", "ime")
    preveri(P.naslednja(emptyList(), "Racun %d") == 1, "prva stevilka")
    preveri(P.naslednja(listOf("Racun 1", "Racun 3"), "Racun %d") == 4, "najvecja + 1")
    preveri(P.naslednja(listOf("Racun 2", "moje ime"), "Racun %d") == 3, "tuje ime ne moti")
    println("YouTubeRacuniPravilaTest: OK")
}
