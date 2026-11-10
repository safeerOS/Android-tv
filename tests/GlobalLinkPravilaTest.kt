package si.safeer.tv.link

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

fun main() {
    val P = GlobalLinkPravila
    // Vrsta posiljanja
    preveri(P.odlocitevVrste(1_000_001L, 11_000L, false) == GlobalLinkPravila.Akcija.PREKINI, "vrsta polna 11 s -> prekini")
    preveri(P.odlocitevVrste(1_000_001L, 10_000L, false) == GlobalLinkPravila.Akcija.CAKAJ, "natanko 10 s se caka")
    preveri(P.odlocitevVrste(1_000_001L, 5_000L, false) == GlobalLinkPravila.Akcija.CAKAJ, "polna vrsta, cas se ni potekel")
    preveri(P.odlocitevVrste(1_000_000L, 2_000L, false) == GlobalLinkPravila.Akcija.NADALJUJ, "vrsta se sprazni -> nadaljuj")
    preveri(P.odlocitevVrste(1_000_000L, 60_000L, false) == GlobalLinkPravila.Akcija.NADALJUJ, "prazna vrsta ne prekine niti po dolgem casu")
    preveri(P.odlocitevVrste(1_000_001L, 0L, true) == GlobalLinkPravila.Akcija.PREKINI, "zaprta vticnica -> takoj")
    preveri(P.odlocitevVrste(0L, 0L, true) == GlobalLinkPravila.Akcija.PREKINI, "zaprta vticnica, prazna vrsta")
    // Zamiki: zaporedje ob stalnih napakah je 5, 10, 20 ... 300 s (prvi poskus NE caka 10 s)
    var naVrsti = P.ZAMIK_PRVI_MS
    val zaporedje = ArrayList<Long>()
    repeat(9) { val zdaj = P.zamikZdaj(naVrsti, 1_000L); zaporedje.add(zdaj); naVrsti = P.naslednjiZamik(zdaj) }
    preveri(zaporedje == listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), "zaporedje $zaporedje")
    preveri(P.zamikZdaj(300_000L, 61_000L) == 5_000L, "po zdravi povezavi spet 5 s")
    preveri(P.zamikZdaj(300_000L, 60_000L) == 5_000L, "meja zdrave povezave je vkljucena")
    preveri(P.zamikZdaj(300_000L, 59_999L) == 300_000L, "kratka povezava zamika ne ponastavi")
    preveri(P.zamikZdaj(0L, 0L) == 5_000L, "nikoli manj kot 5 s (brez vrtenja v prazno)")
    preveri(P.naslednjiZamik(200_000L) == 300_000L, "najvec 5 min")
    println("GlobalLinkPravilaTest: OK ($stevilo preverb)")
}
