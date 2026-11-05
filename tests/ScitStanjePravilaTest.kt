// Pregled Nastavitev in Scita (5. 11. 2026): dejansko stanje Scita (prekinjen ni »Vklopljeno«), ponovni vklop na
// zaslonu Scita, SERVFAIL za poizvedbe brez odgovora in nabor grozenj, odprt enkrat. Staticne preverbe izvorne kode
// padejo na kodi pred popravkom.  tests/run_scit_tests.sh (argument: pot do src/main/kotlin)
package si.safeer.tv.scit

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess
import si.safeer.tv.scit.ScitStanjePravila.Gumb
import si.safeer.tv.scit.ScitStanjePravila.Neodgovorjena
import si.safeer.tv.scit.ScitStanjePravila.Stanje

private var padloStanje = 0

private fun preveriStanje(pogoj: Boolean, opis: String) {
    if (pogoj) println("OK   $opis") else { padloStanje++; println("PADE $opis") }
}

fun main(args: Array<String>) {
    val P = ScitStanjePravila
    // 1: drug VPN prevzame tunel (onRevoke) - zastavica ostane, storitev ne tece.
    preveriStanje(P.stanje(vklopljen = true, tece = false) == Stanje.PREKINJEN, "vklopljen, a ne tece = prekinjen (ne Vklopljeno)")
    preveriStanje(P.stanje(true, true) == Stanje.TECE, "vklopljen in tece")
    preveriStanje(P.stanje(false, false) == Stanje.IZKLOPLJEN && P.stanje(false, true) == Stanje.IZKLOPLJEN, "izklopljen")
    preveriStanje(P.gumbi(Stanje.PREKINJEN) == listOf(Gumb.VKLOPI_ZNOVA, Gumb.IZKLOPI), "prekinjen: najprej ponovni vklop, izklop ostane")
    preveriStanje(P.gumbi(Stanje.TECE) == listOf(Gumb.IZKLOPI) && P.gumbi(Stanje.IZKLOPLJEN) == listOf(Gumb.VKLOPI), "tece / izklopljen: en gumb")

    // 10: poizvedba brez odgovora. Dva streznika, ponovitev 1200 ms, potek 8000 ms.
    fun n(zdaj: Long, zadnjic: Long, naslednji: Int) = P.neodgovorjena(zdaj, 0L, zadnjic, naslednji, 2, 1_200L, 8_000L)
    preveriStanje(n(500, 0, 1) == Neodgovorjena.CAKAJ, "prvi vprasan pred 0,5 s: caka")
    preveriStanje(n(1_200, 0, 1) == Neodgovorjena.PONOVI, "prvi molci 1,2 s: naslednji streznik")
    preveriStanje(n(2_400, 1_200, 2) == Neodgovorjena.CAKAJ, "vsi vprasani, zadnji pred 1,2 s: se caka (pocasen pravi odgovor ima prednost)")
    preveriStanje(n(1_200 + P.ROK_ZADNJEGA_MS - 1, 1_200, 2) == Neodgovorjena.CAKAJ, "tik pred rokom zadnjega: caka")
    preveriStanje(n(1_200 + P.ROK_ZADNJEGA_MS, 1_200, 2) == Neodgovorjena.SERVFAIL, "vsi vprasani in rok minil: SERVFAIL (prej tiho opusceno)")
    preveriStanje(n(8_001, 7_900, 1) == Neodgovorjena.SERVFAIL, "potek 8 s: SERVFAIL, ne molk")
    preveriStanje(P.ROK_ZADNJEGA_MS in 2_000L..4_000L, "rok zadnjega je med 2 in 4 s (manj od 5 s Androida)")
    preveriStanje(P.neodgovorjena(1_000, 0, 0, 1, 1, 1_200, 8_000) == Neodgovorjena.CAKAJ, "en streznik, 1 s: caka")

    // 3: nabor grozenj odprt enkrat, znova samo ob spremembi.
    val mapa = Files.createTempDirectory("scit-stanje").toFile()
    try {
        val f = File(mapa, "grozne.bin")
        DomenskiNabor.zapisi(f, sequenceOf(sequenceOf("zlo.example", "slabo.example")))
        val prvi = DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, null)
        preveriStanje(prvi != null && prvi.jeBlokirana("zlo.example"), "prvo odprtje")
        preveriStanje(DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, prvi) === prvi, "nespremenjena datoteka: isti nabor, brez ponovnega mapiranja")
        DomenskiNabor.zapisi(f, sequenceOf(sequenceOf("drugo.example")))
        f.setLastModified(prvi!!.spremenjena + 5_000)
        val drugi = DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, prvi)
        preveriStanje(drugi !== prvi && drugi?.jeBlokirana("drugo.example") == true, "spremenjena datoteka: odprta znova")
        f.delete()
        preveriStanje(DomenskiNabor.Poizvedba.odpriCeSpremenjeno(f, drugi) == null, "izbrisana datoteka: ni nabora")
    } finally { mapa.deleteRecursively() }

    // Staticne preverbe izvorne kode (padejo pred popravkom 5. 11. 2026).
    val src = File(args.getOrNull(0) ?: error("pot do src/main/kotlin"))
    val storitev = File(src, "si/safeer/tv/scit/ScitStoritev.kt").readText()
    val ponovi = storitev.substringAfter("private fun ponoviNeodgovorjene()").substringBefore("private fun zankaOdgovorov()")
    preveriStanje("odgovorNapakaZa" in ponovi && "cakajoce.remove(id, c)" in ponovi, "ScitStoritev: neodgovorjena dobi SERVFAIL, odstranitev atomarna")
    val zaslon = File(src, "si/safeer/tv/scit/ScitActivity.kt").readText()
    preveriStanje("Gumb.VKLOPI_ZNOVA" in zaslon, "ScitActivity: prekinjen ponudi ponovni vklop")
    val preberi = zaslon.substringAfter("private fun preberi(): Pogled").substringBefore("private fun danes()")
    preveriStanje("Poizvedba.odpri(" !in preberi && "zasebniDnsObide()" !in preberi, "ScitActivity.preberi: brez mapiranja ob vsakem branju in brez poizvedb omrezij")
    val narisi = zaslon.substringAfter("private fun narisi(prisilno: Boolean").substringBefore("private fun izrisi(")
    preveriStanje("vOzadju" in narisi, "ScitActivity.narisi: branje na delovni niti")
    val nastavitve = File(src, "si/safeer/tv/os/NastavitveActivity.kt").readText()
    preveriStanje("vklop(Scit.jeVklopljen(this))" !in nastavitve && "ScitStanjePravila.stanje(" in nastavitve, "Nastavitve: vrstica Scita kaze dejansko stanje")

    if (padloStanje > 0) { println("ScitStanjePravila: $padloStanje padlo"); exitProcess(1) }
    println("ScitStanjePravila: vse v redu")
}
