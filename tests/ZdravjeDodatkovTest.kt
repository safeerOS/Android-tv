package si.safeer.tv.os

import si.safeer.tv.os.ZdravjeDodatkov.Izid
import si.safeer.tv.os.ZdravjeDodatkov.Nacin

/** Zdravje dodatkov pri tokovih (lastnik, 5. 11. 2026: viri tokov) - casi z vbrizgano uro. */
fun main() {
    val min = 60_000L
    var s = ZdravjeDodatkov.Stanje()
    var ura = 1_000_000L
    // Dva neuspeha: se ni premora.
    s = ZdravjeDodatkov.po(s, Izid.NEUSPEH, ura); s = ZdravjeDodatkov.po(s, Izid.NEUSPEH, ura)
    check(!ZdravjeDodatkov.vPremoru(s, ura) && s.zaporednih == 2) { s.toString() }
    // Uspeh ponastavi stevec.
    check(ZdravjeDodatkov.po(s, Izid.USPEH, ura) == ZdravjeDodatkov.Stanje())
    // Nevtralno ne spremeni nic.
    check(ZdravjeDodatkov.po(s, Izid.NEVTRALNO, ura) == s)
    // Tretji zaporedni: premor 10 min.
    s = ZdravjeDodatkov.po(s, Izid.NEUSPEH, ura)
    check(ZdravjeDodatkov.vPremoru(s, ura) && ZdravjeDodatkov.vPremoru(s, ura + 10 * min - 1) && !ZdravjeDodatkov.vPremoru(s, ura + 10 * min)) { s.toString() }
    // Neuspeh med premorom (poizvedba v ozadju) premora ne podaljsa.
    val medPremorom = ZdravjeDodatkov.po(s, Izid.NEUSPEH, ura + 5 * min)
    check(medPremorom.doMs == s.doMs && medPremorom.zaporednih == 4) { medPremorom.toString() }
    // Po izteku: prvi naslednji neuspeh takoj nov, dvakrat daljsi premor (20, 40, 60, 60 min).
    s = medPremorom
    for (pricakovano in listOf(20L, 40L, 60L, 60L)) {
        ura = s.doMs
        s = ZdravjeDodatkov.po(s, Izid.NEUSPEH, ura)
        check(s.doMs - ura == pricakovano * min) { "$pricakovano: $s" }
    }
    // Uspeh (tudi med premorom, v ozadju) dodatek takoj vrne.
    check(!ZdravjeDodatkov.vPremoru(ZdravjeDodatkov.po(s, Izid.USPEH, ura + 1), ura + 1))
    check(!ZdravjeDodatkov.vPremoru(null, ura))

    // Izid poizvedbe.
    check(ZdravjeDodatkov.izid(false, false, 0, 12_000, 0, 5) == Izid.NEVTRALNO) { "ni vprasan (tip/predpona)" }
    check(ZdravjeDodatkov.izid(true, false, 500, 12_000, 0, 0) == Izid.NEUSPEH) { "napaka / brez odgovora" }
    check(ZdravjeDodatkov.izid(true, true, 12_001, 12_000, 4, 0) == Izid.NEUSPEH) { "cas iztek" }
    check(ZdravjeDodatkov.izid(true, true, 3_000, 12_000, 4, 0) == Izid.USPEH)
    check(ZdravjeDodatkov.izid(true, true, 3_000, 12_000, 0, 7) == Izid.NEUSPEH) { "prazno, drugi imajo" }
    check(ZdravjeDodatkov.izid(true, true, 3_000, 12_000, 0, 0) == Izid.NEVTRALNO) { "prazno pri vseh: vsebine ni" }

    // Nacin: javni v premoru v ozadju, s kljucem preskocen; ce so v premoru vsi, cakamo na vse.
    check(ZdravjeDodatkov.nacini(listOf(false, true, true), listOf(false, false, true)) == listOf(Nacin.CAKAJ, Nacin.V_OZADJU, Nacin.PRESKOCI))
    check(ZdravjeDodatkov.nacini(listOf(true, true), listOf(true, false)) == listOf(Nacin.CAKAJ, Nacin.CAKAJ))
    check(ZdravjeDodatkov.nacini(emptyList(), emptyList()).isEmpty())
    println("ZdravjeDodatkovTest: OK")
}
