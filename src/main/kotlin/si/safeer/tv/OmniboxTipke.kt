package si.safeer.tv

/**
 * Daljinec v naslovni vrstici in plosci predlogov (kot Chrome na Android TV). Brez Androida, preizkus v
 * tests/OmniboxTipkeTest.kt.
 *
 * TV 3. 11. 2026: predloga ni bilo mogoce izbrati. Prvi Nazaj ob odprti tipkovnici je zaprl tipkovnico in plosco hkrati
 * (OmniboxPolje.onKeyPreIme), DOL iz polja je sel na stran, in ce bi fokus prisel v plosco, bi jo izguba fokusa polja
 * skrila. Zdaj: 1. Nazaj zapre samo tipkovnico, DOL gre na prvi cip ali predlog, GOR s cipa nazaj v polje, 2. Nazaj
 * zapre plosco.
 */
object OmniboxTipke {
    enum class Cilj { PRVI_CIP, PRVA_VRSTICA, STRAN, POLJE, SISTEM }

    /**
     * Nazaj pred tipkovnico (onKeyPreIme): ne prestrezemo. Odprto tipkovnico zapre ona sama in plosca ostane; ko je
     * tipkovnica zaprta, gre Nazaj v TvKeyRouter, ki zapre plosco.
     */
    fun nazajPrestreziPredTipkovnico(): Boolean = false

    /** DOL iz polja (tipkovnica je zaprta, sicer tipko dobi ona): v plosco, ce je vidna in ima kam. */
    fun dolIzPolja(ploscaVidna: Boolean, cipov: Int, vrstic: Int): Cilj = when {
        ploscaVidna && cipov > 0 -> Cilj.PRVI_CIP
        ploscaVidna && vrstic > 0 -> Cilj.PRVA_VRSTICA
        else -> Cilj.STRAN
    }

    /** GOR v plosci: s cipov (in s prve vrstice, kadar cipov ni) nazaj v polje, sicer navadno iskanje fokusa. */
    fun gorVPlosci(vCipih: Boolean, vrstica: Int, cipov: Int): Cilj = when {
        vCipih -> Cilj.POLJE
        vrstica == 0 && cipov == 0 -> Cilj.POLJE
        else -> Cilj.SISTEM
    }

    /** Fokus je zapustil polje: urejanje (vneseno besedilo, plosca) se konca samo, ce ni sel v plosco ali nazaj v polje. */
    fun koncajUrejanje(vPolju: Boolean, vPlosci: Boolean): Boolean = !vPolju && !vPlosci
}
