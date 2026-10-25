package si.safeer.tv.link

import si.safeer.tv.link.DeljenjePravila.Konec
import si.safeer.tv.link.DeljenjePravila.Nadaljevanje
import si.safeer.tv.link.DeljenjePravila.Pot
import si.safeer.tv.link.DeljenjePravila.Potrditev

/**
 * Deljenje zaslona po vzoru DeX brez posebnih pravic (link/DeljenjePravila, nacrt »dex-upravljanje« A1-A4).
 * Prej je vsak »Odpri tukaj« istega racunalnika zahteval novo soglasje in odprl novo okno (Daljinec.pretociAplikacijo,
 * prebrano 25. 10. 2026), Safeer Vnos zagona iz ozadja ni odprl, zaklep (Android 15 QPR1+) pa je deljenje koncal brez
 * besede racunalniku.
 */
fun main() {
    // ---- A1: ena potrditev na ogled
    fun p(od: String = "A", cel: Boolean = false, deli: Boolean = true, cilj: String = "A", zacenja: String = "", caka: Boolean = false) =
        DeljenjePravila.pot(od, cel, deli, cilj, zacenja, caka)
    // Zaslon ze delimo racunalniku A, A odpre se eno aplikacijo: samo jo odpremo, brez novega soglasja.
    check(p() == Pot.ODPRI_V_SEJI)
    // A zeli cel zaslon (»Povezani zasloni«), ki ga ze vidi: nic novega.
    check(p(cel = true) == Pot.ZE_DELIM)
    // Delimo drugemu racunalniku (B): A potrebuje svoje soglasje (novo deljenje bo zamenjalo staro).
    check(p(cilj = "B") == Pot.NOVA_SEJA)
    check(p(cilj = "B", cel = true) == Pot.NOVA_SEJA)
    // Ne delimo nikomur: nova seja.
    check(p(deli = false, cilj = "") == Pot.NOVA_SEJA)
    // Brez posiljatelja ne vemo, cigavo je deljenje.
    check(p(od = "", cilj = "") == Pot.NOVA_SEJA)
    // Soglasje za A je pravkar dano, storitev se zaganja: velja kot tekoce deljenje A ...
    check(p(deli = false, cilj = "", zacenja = "A") == Pot.ODPRI_V_SEJI)
    // ... in oznaka zagona velja pred tekocim deljenjem: B se zaganja, A se tece - A potrebuje novo soglasje (R6-K3).
    check(p(cilj = "A", zacenja = "B") == Pot.NOVA_SEJA)
    check(p(od = "B", cilj = "A", zacenja = "B") == Pot.ODPRI_V_SEJI)
    // Deljenje A stoji, ker je naprava zaklenjena (projekcije ni): brez novega soglasja ne gre.
    check(p(caka = true) == Pot.NOVA_SEJA)
    check(p(caka = true, cel = true) == Pot.NOVA_SEJA)

    // ---- A2: zagon iz ozadja in rezervno obvestilo
    // Android 9 zagona iz ozadja ne omejuje.
    check(DeljenjePravila.smeIzOzadja(28, false, false, false))
    // Android 10+: samo s prekrivanjem, Safeer Vnosom ali v ospredju.
    check(!DeljenjePravila.smeIzOzadja(34, false, false, false))
    check(DeljenjePravila.smeIzOzadja(34, true, false, false))
    check(DeljenjePravila.smeIzOzadja(34, false, true, false))
    check(DeljenjePravila.smeIzOzadja(36, false, false, true))
    // Brez dovoljenja obvestilo takoj; z dovoljenjem in preverbo sele, ce se okno ni odprlo.
    check(DeljenjePravila.namigTakoj(neposredno = false, preverljivo = true))
    check(DeljenjePravila.namigTakoj(neposredno = true, preverljivo = false))
    check(!DeljenjePravila.namigTakoj(neposredno = true, preverljivo = true))
    check(!DeljenjePravila.namigPozneje(true))
    check(DeljenjePravila.namigPozneje(false))
    check(DeljenjePravila.namigPozneje(null))

    // ---- A3: »Vedno dovoli« in trajno dovoljenje Androida
    check(DeljenjePravila.zaupano(vednoDovoli = true, vKrogu = true))
    // Naprava, ki ji je uporabnik medtem vzel ves dostop (ni vec v ozjem krogu), ni zaupana, ceprav je zapis ostal.
    check(!DeljenjePravila.zaupano(vednoDovoli = true, vKrogu = false))
    check(!DeljenjePravila.zaupano(vednoDovoli = false, vKrogu = true))
    // Brez trajnega dovoljenja vprasa Android - Safeer ne doda drugega vprasanja (ne glede na zaupanje).
    check(DeljenjePravila.potrditev(projekcijaDovoljena = false, zaupano = false) == Potrditev.SISTEMSKA)
    check(DeljenjePravila.potrditev(projekcijaDovoljena = false, zaupano = true) == Potrditev.SISTEMSKA)
    // S trajnim dovoljenjem Android ne vprasa vec: nezaupano napravo vprasa Safeer, zaupane ne.
    check(DeljenjePravila.potrditev(projekcijaDovoljena = true, zaupano = false) == Potrditev.NASA)
    check(DeljenjePravila.potrditev(projekcijaDovoljena = true, zaupano = true) == Potrditev.BREZ)
    // Gumb »Vedno dovoli« samo za napravo v ozjem krogu, ki se ni zaupana; nikoli za to napravo samo.
    check(DeljenjePravila.ponudiZaupanje(vKrogu = true, vednoDovoli = false, taNaprava = false))
    check(!DeljenjePravila.ponudiZaupanje(vKrogu = false, vednoDovoli = false, taNaprava = false))
    check(!DeljenjePravila.ponudiZaupanje(vKrogu = true, vednoDovoli = true, taNaprava = false))
    check(!DeljenjePravila.ponudiZaupanje(vKrogu = true, vednoDovoli = false, taNaprava = true))

    // ---- A4: zaklep
    fun k(zaklenjeno: Boolean = true, prizgan: Boolean = false, ustavljam: Boolean = false, tok: Boolean = true) =
        DeljenjePravila.konecProjekcije(zaklenjeno, prizgan, ustavljam, tok)
    // Android je projekcijo ustavil ob zaklepu (zaslon ugasnjen, zaklepni zaslon): pocakamo na odklep.
    check(k() == Konec.ZAKLEP)
    // »Ustavi« na zaklepnem zaslonu (zaslon prizgan) je uporabnikova odlocitev: konec.
    check(k(prizgan = true) == Konec.NAVADEN)
    // Ni zaklepa (npr. »Ustavi« v vrstici stanja): konec.
    check(k(zaklenjeno = false) == Konec.NAVADEN)
    // Konec je sprozil Safeer sam (Prekini, apps.close, zamenjava): konec.
    check(k(ustavljam = true) == Konec.NAVADEN)
    // Racunalnik se ne gleda (tok do Huba se ni vzpostavljen): ni kam pokazati sporocila - konec.
    check(k(tok = false) == Konec.NAVADEN)
    // Po odklepu: zaupana naprava samodejno, druga z obvestilom; po roku konec.
    val zacetek = 1_000_000L
    check(DeljenjePravila.poOdklepu(true, zacetek, zacetek + 5_000) == Nadaljevanje.SAMODEJNO)
    check(DeljenjePravila.poOdklepu(false, zacetek, zacetek + 5_000) == Nadaljevanje.OBVESTILO)
    check(DeljenjePravila.poOdklepu(true, zacetek, zacetek + DeljenjePravila.CAKA_ODKLEP_MS - 1) == Nadaljevanje.SAMODEJNO)
    check(DeljenjePravila.poOdklepu(true, zacetek, zacetek + DeljenjePravila.CAKA_ODKLEP_MS) == Nadaljevanje.KONEC)
    check(DeljenjePravila.poOdklepu(false, zacetek, zacetek + DeljenjePravila.CAKA_ODKLEP_MS) == Nadaljevanje.KONEC)
    // Druga vrstica na racunalniku: potrditev na napravi je potrebna, razen pri zaupani napravi s trajnim dovoljenjem, ki
    // sme okno odpreti iz ozadja (brez tega je potreben vsaj tap na obvestilo).
    for (izOzadja in listOf(true, false)) {
        check(DeljenjePravila.poOdklepuPotrdi(zaupano = false, projekcijaDovoljena = false, izOzadja = izOzadja))
        check(DeljenjePravila.poOdklepuPotrdi(zaupano = false, projekcijaDovoljena = true, izOzadja = izOzadja))
        check(DeljenjePravila.poOdklepuPotrdi(zaupano = true, projekcijaDovoljena = false, izOzadja = izOzadja))
    }
    check(!DeljenjePravila.poOdklepuPotrdi(zaupano = true, projekcijaDovoljena = true, izOzadja = true))
    check(DeljenjePravila.poOdklepuPotrdi(zaupano = true, projekcijaDovoljena = true, izOzadja = false))
    // Med cakanjem na odklep vnos z racunalnika ne gre na zaklepni zaslon.
    check(!DeljenjePravila.vnosDovoljen(cakaNaOdklep = true))
    check(DeljenjePravila.vnosDovoljen(cakaNaOdklep = false))
    // Novo soglasje za isti racunalnik nadaljuje isti tok (isto okno na racunalniku); za drugega zacne novo deljenje.
    check(DeljenjePravila.nadaljujVIstemToku(true, "A", "A"))
    check(!DeljenjePravila.nadaljujVIstemToku(true, "A", "B"))
    check(!DeljenjePravila.nadaljujVIstemToku(false, "A", "A"))
    check(!DeljenjePravila.nadaljujVIstemToku(true, "", ""))

    // ---- Meritev M1: viden znak, odgovor po dejanskem zacetku, besedilo brez obvestil
    // Brez okna za soglasje (trajno dovoljenje) samo z obvestilom ali nasim gumbom na zaslonu; OnePlus 9 brez obeh: ne.
    check(!DeljenjePravila.smeZacetiBrezOkna(projekcijaDovoljena = true, obvestila = false, prekrivanje = false))
    check(DeljenjePravila.smeZacetiBrezOkna(projekcijaDovoljena = true, obvestila = true, prekrivanje = false))
    check(DeljenjePravila.smeZacetiBrezOkna(projekcijaDovoljena = true, obvestila = false, prekrivanje = true))
    // Z oknom za soglasje je zacetek viden (uporabnik ga potrdi).
    check(DeljenjePravila.smeZacetiBrezOkna(projekcijaDovoljena = false, obvestila = false, prekrivanje = false))
    // Odgovor pocaka na zacetek samo brez vprasanja in z zagonom iz ozadja.
    check(DeljenjePravila.pocakajZacetek(Potrditev.BREZ, izOzadja = true))
    check(!DeljenjePravila.pocakajZacetek(Potrditev.BREZ, izOzadja = false))
    check(!DeljenjePravila.pocakajZacetek(Potrditev.NASA, izOzadja = true))
    check(!DeljenjePravila.pocakajZacetek(Potrditev.SISTEMSKA, izOzadja = true))
    check(DeljenjePravila.CAKA_ZACETEK_MS < 8_000L)
    // Zacetek je potrjen, ko deljenje tece prav tej napravi in ga je Hub sprejel.
    check(DeljenjePravila.zacetekPotrjen(true, "A", "A", "znak"))
    check(!DeljenjePravila.zacetekPotrjen(true, "A", "A", ""))
    check(!DeljenjePravila.zacetekPotrjen(true, "B", "A", "znak"))
    check(!DeljenjePravila.zacetekPotrjen(false, "A", "A", "znak"))
    check(!DeljenjePravila.zacetekPotrjen(true, "", "", "znak"))
    // »Obvestilo je ze na zaslonu« samo, ce so obvestila vklopljena.
    check(DeljenjePravila.brezZagona(obvestila = true) == DeljenjePravila.BrezZagona.OBVESTILO)
    check(DeljenjePravila.brezZagona(obvestila = false) == DeljenjePravila.BrezZagona.NASTAVITVE)

    // --- pregled dex-a (Grok, 25. 10. 2026)
    // U2: med vprasanjem za deljenje zaslona noben vnos (naprava bi sama tapnila »Dovoli«).
    check(DeljenjePravila.zavrnitevVnosa(cakaNaOdklep = false, zaklenjeno = false, vprasanjeOdprto = true) == DeljenjePravila.ZavrnitevVnosa.VPRASANJE)
    // U6: zaklenjena naprava zavrne vnos tudi, ko projekcija tece naprej (Android pred 15 QPR1).
    check(DeljenjePravila.zavrnitevVnosa(cakaNaOdklep = false, zaklenjeno = true, vprasanjeOdprto = false) == DeljenjePravila.ZavrnitevVnosa.ZAKLENJENO)
    check(DeljenjePravila.zavrnitevVnosa(cakaNaOdklep = true, zaklenjeno = false, vprasanjeOdprto = false) == DeljenjePravila.ZavrnitevVnosa.CAKA_NA_ODKLEP)
    check(DeljenjePravila.zavrnitevVnosa(cakaNaOdklep = false, zaklenjeno = false, vprasanjeOdprto = false) == DeljenjePravila.ZavrnitevVnosa.NI)
    // U1: neznan nacin PROJECT_MEDIA velja kot trajno dovoljenje (Safeer vprasa sam, zahteva znak).
    check(DeljenjePravila.projekcijaPoNacinu(null))
    check(DeljenjePravila.projekcijaPoNacinu(0) && !DeljenjePravila.projekcijaPoNacinu(1) && !DeljenjePravila.projekcijaPoNacinu(3))
    check(DeljenjePravila.potrditev(DeljenjePravila.projekcijaPoNacinu(null), zaupano = false) == DeljenjePravila.Potrditev.NASA)
    // U3: znak mora biti res viden: brez obvestil in brez prikazanega gumba se deljenje konca.
    check(!DeljenjePravila.znakZagotovljen(projekcijaDovoljena = true, obvestila = false, gumbPrikazan = false))
    check(DeljenjePravila.znakZagotovljen(true, false, true) && DeljenjePravila.znakZagotovljen(true, true, false))
    check(DeljenjePravila.znakZagotovljen(false, false, false))       // sistemsko okno je bilo pokazano
    // U4: »Vedno dovoli« samo napravi iz ozjega kroga, nikoli tej napravi.
    check(DeljenjePravila.smeZaupati(vKrogu = true, taNaprava = false))
    check(!DeljenjePravila.smeZaupati(vKrogu = false, taNaprava = false) && !DeljenjePravila.smeZaupati(true, true))
    // U5: dejanje iz obvestila samo za napravo iz obvestila, ne med cakanjem na odklep, s ponovno preverbo ponudbe.
    check(DeljenjePravila.zaupajIzObvestila(true, tece = true, cilj = "n-a", ciljNamere = "n-a", cakaNaOdklep = false, ponudi = true))
    check(!DeljenjePravila.zaupajIzObvestila(true, true, "n-a", "n-b", false, true))     // zamenjan cilj
    check(!DeljenjePravila.zaupajIzObvestila(true, true, "n-a", "n-a", true, true))      // caka na odklep
    check(!DeljenjePravila.zaupajIzObvestila(true, true, "n-a", "n-a", false, false))    // ni vec v krogu
    check(!DeljenjePravila.zaupajIzObvestila(true, false, "n-a", "n-a", false, true))    // deljenje ne tece
    check(DeljenjePravila.zaupajIzObvestila(false, true, "n-a", "n-a", true, false))     // preklic vedno
    // U7: umik iz ozjega kroga izbrise zaupanje.
    check(DeljenjePravila.zaupanjaVKrogu(setOf("n-a", "n-b")) { it == "n-a" } == setOf("n-a"))
    check(DeljenjePravila.zaupanjaVKrogu(setOf("n-a")) { throw IllegalStateException() }.isEmpty())

    println("DeljenjePravilaTest: OK")
}
