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

    println("DeljenjePravilaTest: OK")
}
