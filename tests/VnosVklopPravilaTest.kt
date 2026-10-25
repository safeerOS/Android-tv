package si.safeer.tv.link

import si.safeer.tv.link.VnosVklopPravila.Dejanje
import si.safeer.tv.link.VnosVklopPravila.Okvir
import si.safeer.tv.link.VnosVklopPravila.Pot
import si.safeer.tv.link.VnosVklopPravila.Smer
import si.safeer.tv.link.VnosVklopPravila.Spomin

/**
 * Ena privolitev za Safeer Vnos (link/VnosVklopPravila): niz storitev dostopnosti, izbira poti, ali naj Vnos tece, in
 * krizec pod Androidom 13. Izhodisce: lastnikov televizor 25. 10. 2026 10:44 - vklopljena com.philips.sourceenabler/.SourceService,
 * ki MORA ostati; WP28 S 10:48 - si.safeer.phone/si.safeer.tv.link.VnosStoritev, ki mora ostati ob preizkusu si.safeer.os.
 */
fun main() {
    val os = "si.safeer.os/si.safeer.tv.link.VnosStoritev"
    val philips = "com.philips.sourceenabler/.SourceService"
    val telefon = "si.safeer.phone/si.safeer.tv.link.VnosStoritev"
    val p = VnosVklopPravila

    // --- niz: dodajanje z ohranitvijo drugih
    check(p.dodaj(philips, os) == "$philips:$os")
    check(p.dodaj("$telefon:$philips", os) == "$telefon:$philips:$os")      // vrstni red drugih ostane
    check(p.dodaj(null, os) == os)
    check(p.dodaj("", os) == os)
    check(p.dodaj("null", os) == os)                                         // izpis `settings get` brez vrednosti
    check(p.dodaj(" $philips : ", os) == "$philips:$os")                    // prazni vnosi in presledki odpadejo
    check(p.dodaj("$philips:$os", os) == "$philips:$os")                    // ze vpisano: enkrat
    check(p.dodaj("$os:$philips:$os", os) == "$philips:$os")                // podvojeno: enkrat
    check(p.dodaj(philips, " $os ") == "$philips:$os")
    // Kratka oblika Androida (paket/.Razred) je ista komponenta; kratka oblika tuje storitve ostane, kot je.
    check(p.polna(philips) == "com.philips.sourceenabler/com.philips.sourceenabler.SourceService")
    check(p.vsebuje("si.safeer.tv/.link.VnosStoritev", "si.safeer.tv/si.safeer.tv.link.VnosStoritev"))
    check(p.dodaj("si.safeer.tv/.link.VnosStoritev", "si.safeer.tv/si.safeer.tv.link.VnosStoritev") == "si.safeer.tv/si.safeer.tv.link.VnosStoritev")
    // Druga aplikacija z istim razredom (si.safeer.phone) NI nasa komponenta: ostane.
    check(!p.vsebuje(telefon, os))
    check(p.dodaj(telefon, os) == "$telefon:$os")

    // --- niz: odstranjevanje z ohranitvijo drugih
    check(p.odstrani("$philips:$os", os) == philips)
    check(p.odstrani("$os:$philips", os) == philips)
    check(p.odstrani("$telefon:$os:$philips", os) == "$telefon:$philips")
    check(p.odstrani(os, os) == "")
    check(p.odstrani(null, os) == "")
    check(p.odstrani("null", os) == "")
    check(p.odstrani(philips, os) == philips)                                // ni vpisana: nic se ne spremeni
    check(p.odstrani("$os:$philips:$os", os) == philips)                    // podvojeno: vse nase odpade
    check(p.odstrani("si.safeer.tv/.link.VnosStoritev:$philips", "si.safeer.tv/si.safeer.tv.link.VnosStoritev") == philips)
    check(p.vsebuje("$philips:$os", os) && !p.vsebuje(philips, os) && !p.vsebuje(null, os) && !p.vsebuje("", os))

    // --- omejene nastavitve (AppOps access_restricted_settings): 0 allow, 1 ignore, 2 deny (errored), 3 default
    check(!p.omejenoZaklenjeno(30, 2))           // Android TV 11 (lastnikov televizor): omejitve ni
    check(!p.omejenoZaklenjeno(32, 1))
    check(p.omejenoZaklenjeno(34, 2))            // WP28 S 25. 10.: deny -> sistem zavrne
    check(p.omejenoZaklenjeno(34, 1))            // OnePlus 9 25. 10.: ignore
    check(p.omejenoZaklenjeno(33, 3))            // »default« po preklicu (appops set ... default)
    check(!p.omejenoZaklenjeno(34, 0))           // allow
    check(!p.omejenoZaklenjeno(34, null))        // neznano: poskusimo in izmerimo vezavo

    // --- izbira poti
    check(p.pot(vGradnji = false, aktivna = false, pisanje = true, omejenoZaklenjeno = false) == Pot.NI_STORITVE)
    check(p.pot(vGradnji = false, aktivna = true, pisanje = true, omejenoZaklenjeno = false) == Pot.NI_STORITVE)
    check(p.pot(vGradnji = true, aktivna = true, pisanje = false, omejenoZaklenjeno = true) == Pot.ZE_VKLOPLJENO)
    check(p.pot(vGradnji = true, aktivna = false, pisanje = true, omejenoZaklenjeno = false) == Pot.SAMODEJNO)
    check(p.pot(vGradnji = true, aktivna = false, pisanje = true, omejenoZaklenjeno = true) == Pot.MANJKA_ODKLEP)
    check(p.pot(vGradnji = true, aktivna = false, pisanje = false, omejenoZaklenjeno = false) == Pot.VODENO)
    check(p.pot(vGradnji = true, aktivna = false, pisanje = false, omejenoZaklenjeno = true) == Pot.VODENO)

    // --- ali naj Vnos tece: samo druga naprava, ki je se v krogu
    val krog = setOf("n-aaaaaaaaaaaaaaaa", "n-bbbbbbbbbbbbbbbb")
    val ta = "n-bbbbbbbbbbbbbbbb"
    check(p.zeleno(listOf("n-aaaaaaaaaaaaaaaa"), { it in krog }, { it == ta }))
    check(!p.zeleno(emptyList(), { it in krog }, { it == ta }))
    check(!p.zeleno(listOf("n-cccccccccccccccc"), { it in krog }, { it == ta }))      // umaknjena / ni v krogu
    check(!p.zeleno(listOf(ta), { it in krog }, { it == ta }))                         // ta naprava sama ne steje
    check(!p.zeleno(listOf(""), { true }, { false }))
    check(!p.zeleno(listOf("n-aaaaaaaaaaaaaaaa"), { throw IllegalStateException() }, { false }))  // »ne vem« ni »da«
    check(p.zeleno(listOf("n-cccccccccccccccc", "n-aaaaaaaaaaaaaaaa"), { it in krog }, { it == ta }))

    // --- uskladitev: brez pisanja nic; vklop in izklop samo ob razliki
    val nas = p.PO_VPISU                     // vpis je naredila aplikacija
    val nic = Spomin()                       // brez spomina (prva namestitev)
    check(p.dejanje(zeleno = true, vpisano = false, pisanje = true, spomin = nic) == Dejanje.VKLOPI)
    check(p.dejanje(zeleno = false, vpisano = true, pisanje = true, spomin = nas) == Dejanje.IZKLOPI)
    check(p.dejanje(zeleno = true, vpisano = true, pisanje = true, spomin = nas) == Dejanje.NIC)
    check(p.dejanje(zeleno = false, vpisano = false, pisanje = true, spomin = nas) == Dejanje.NIC)
    check(p.dejanje(zeleno = true, vpisano = false, pisanje = false, spomin = nic) == Dejanje.NIC)
    check(p.dejanje(zeleno = false, vpisano = true, pisanje = false, spomin = nas) == Dejanje.NIC)      // brez pisanja nic
    // Pregled tv-vnos 3: krog ni prebran (null) - nas vpis se izklopi (»ne vem« ni »da«), nov vklop ne.
    check(p.dejanje(zeleno = null, vpisano = true, pisanje = true, spomin = nas) == Dejanje.IZKLOPI)
    check(p.dejanje(zeleno = null, vpisano = false, pisanje = true, spomin = nic) == Dejanje.NIC)
    // Rocni vklop uporabnika (vpisal ga ni Safeer) izklop kroga ne umakne.
    check(p.dejanje(zeleno = false, vpisano = true, pisanje = true, spomin = Spomin(false, true, false)) == Dejanje.NIC)

    // --- pregled tv-vnos 4: rocni izklop uporabnika velja do nove privolitve
    val izklopil = p.opazi(nas, vpisano = false)
    check(izklopil.izklopilUporabnik && !izklopil.vpisaliMi && !izklopil.zadnjicVpisano)
    check(p.dejanje(zeleno = true, vpisano = false, pisanje = true, spomin = izklopil) == Dejanje.NIC)
    check(!p.smeVpisati(true, izklopil))                                  // tudi input.enable ga ne vpise sam
    check(p.smeVpisati(true, p.poPrivolitvi(izklopil)))                   // nova privolitev
    check(!p.opazi(p.poIzpisu(nas), vpisano = false).izklopilUporabnik)   // nas umik ni izklop uporabnika
    check(p.opazi(izklopil, vpisano = true).let { !it.izklopilUporabnik && it.zadnjicVpisano && !it.vpisaliMi })
    check(p.opazi(nic, vpisano = false) == nic)
    // --- pregled tv-vnos 1: aplikacija vpise samo, ce ozji krog to zahteva
    check(p.smeVpisati(true, nic) && !p.smeVpisati(false, nic) && !p.smeVpisati(null, nic))
    // --- pregled tv-vnos 4: QUICKBOOT_POWERON lahko poslje katerakoli aplikacija - Vnosa ne uskladi
    check(p.uskladiObOddaji("android.intent.action.BOOT_COMPLETED") && p.uskladiObOddaji("android.intent.action.MY_PACKAGE_REPLACED"))
    check(!p.uskladiObOddaji("android.intent.action.QUICKBOOT_POWERON") && !p.uskladiObOddaji("com.htc.intent.action.QUICKBOOT_POWERON"))
    check(!p.uskladiObOddaji(""))

    // --- krizec: smer
    check(p.smer("up") == Smer.GOR && p.smer("DOWN") == Smer.DOL && p.smer(" left ") == Smer.LEVO && p.smer("desno") == Smer.DESNO)
    check(p.smer("enter") == null && p.smer("") == null)

    // Mreza 3 x 2 ploscic (kot domaci zaslon televizorja): 300 x 200, razmik 20.
    fun pl(stolpec: Int, vrstica: Int) = Okvir(stolpec * 320, vrstica * 220, stolpec * 320 + 300, vrstica * 220 + 200)
    val mreza = listOf(pl(0, 0), pl(1, 0), pl(2, 0), pl(0, 1), pl(1, 1), pl(2, 1))
    val sredina = mreza[1]
    check(p.najblizji(sredina, mreza, Smer.DESNO) == 2)
    check(p.najblizji(sredina, mreza, Smer.LEVO) == 0)
    check(p.najblizji(sredina, mreza, Smer.DOL) == 4)
    check(p.najblizji(sredina, mreza, Smer.GOR) == -1)                 // nad prvo vrstico ni nicesar
    check(p.najblizji(mreza[2], mreza, Smer.DESNO) == -1)               // rob
    check(p.najblizji(mreza[4], mreza, Smer.GOR) == 1)
    // Zarek ima prednost: blizji element postrani ne premaga elementa naravnost desno.
    val od = Okvir(0, 0, 100, 100)
    val postrani = Okvir(110, 150, 160, 200)      // blizu, a pod zarkom
    val naravnost = Okvir(400, 20, 500, 80)       // dalec, v zarku
    check(p.najblizji(od, listOf(postrani, naravnost), Smer.DESNO) == 1)
    // Brez kandidata v zarku: najblizji po utezeni razdalji.
    val dalecPostrani = Okvir(600, 300, 700, 400)
    check(p.najblizji(od, listOf(dalecPostrani, postrani), Smer.DESNO) == 1)
    // Prazni okvirji, izhodisce samo in elementi za hrbtom odpadejo.
    check(p.najblizji(od, listOf(Okvir(200, 0, 200, 100), Okvir(0, 0, 100, 100), Okvir(-200, 0, -100, 100)), Smer.DESNO) == -1)
    check(p.najblizji(od, emptyList(), Smer.DOL) == -1)
    // Pregled tv-vnos 7: Enter klikne element s fokusom ali prednika z istim okvirjem, ne vecjega vsebnika.
    val gumb = Okvir(100, 100, 300, 160)
    check(p.ciljEnter(listOf(gumb to true, Okvir(0, 0, 1000, 800) to true)) == 0)
    check(p.ciljEnter(listOf(gumb to false, Okvir(0, 0, 1000, 800) to true)) == -1)      // prej: klik na vsebnik
    check(p.ciljEnter(listOf(gumb to false, Okvir(100, 100, 300, 160) to true)) == 1)     // kartica okoli besedila
    check(p.ciljEnter(listOf(gumb to false, Okvir(100, 100, 300, 160) to false, Okvir(0, 0, 9, 9) to true)) == -1)
    check(p.ciljEnter(emptyList()) == -1)
    // Brez fokusa: prvi zgoraj levo.
    check(p.prvi(listOf(pl(1, 1), pl(2, 0), pl(1, 0), Okvir(0, 0, 0, 0))) == 2)
    check(p.prvi(emptyList()) == -1)

    println("VnosVklopPravilaTest: OK")
}
