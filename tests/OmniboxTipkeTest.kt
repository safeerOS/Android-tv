package si.safeer.tv

import si.safeer.tv.OmniboxTipke.Cilj

/** Daljinec v plosci predlogov naslovne vrstice (TV 3. 11. 2026: predloga ni bilo mogoce izbrati). */
fun main() {
    // Prvi Nazaj ob odprti tipkovnici: zapre jo tipkovnica sama, plosca ostane (prej: obe hkrati, fokus na stran).
    check(!OmniboxTipke.nazajPrestreziPredTipkovnico())
    // DOL iz polja gre v predloge (prej vedno na stran).
    check(OmniboxTipke.dolIzPolja(ploscaVidna = true, cipov = 8, vrstic = 8) == Cilj.PRVI_CIP)
    check(OmniboxTipke.dolIzPolja(ploscaVidna = true, cipov = 0, vrstic = 3) == Cilj.PRVA_VRSTICA)
    check(OmniboxTipke.dolIzPolja(ploscaVidna = false, cipov = 8, vrstic = 8) == Cilj.STRAN)
    check(OmniboxTipke.dolIzPolja(ploscaVidna = true, cipov = 0, vrstic = 0) == Cilj.STRAN)
    // GOR s cipa nazaj v polje; med vrsticami odloci iskanje fokusa.
    check(OmniboxTipke.gorVPlosci(vCipih = true, vrstica = -1, cipov = 8) == Cilj.POLJE)
    check(OmniboxTipke.gorVPlosci(vCipih = false, vrstica = 0, cipov = 0) == Cilj.POLJE)
    check(OmniboxTipke.gorVPlosci(vCipih = false, vrstica = 0, cipov = 8) == Cilj.SISTEM)
    check(OmniboxTipke.gorVPlosci(vCipih = false, vrstica = 3, cipov = 8) == Cilj.SISTEM)
    // Fokus v plosci ali v polju: vneseno besedilo in plosca ostaneta; drugam: urejanje se konca.
    check(!OmniboxTipke.koncajUrejanje(vPolju = false, vPlosci = true))
    check(!OmniboxTipke.koncajUrejanje(vPolju = true, vPlosci = false))
    check(OmniboxTipke.koncajUrejanje(vPolju = false, vPlosci = false))
    println("OmniboxTipkeTest: OK")
}
