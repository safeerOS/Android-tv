package si.safeer.tv.os

import si.safeer.tv.R

object MedijskiCenterNastavitvePravila {
    enum class Skupina(val naslov: Int) {
        VIRI(R.string.os_viri_naslov),
        DOM(R.string.os_mediji_dom),
        PREDVAJANJE(R.string.ui_playback),
        SHRAMBA(R.string.nast_shramba),
        ZASEBNOST(R.string.nast_zasebnost),
        SPLOSNE(0)
    }

    enum class Postavka(val naslov: Int) {
        MOJI_VIRI(R.string.os_mediji_viri),
        DEBRID(R.string.os_debrid_in_usenet),
        VRSTICE(R.string.os_nastavitve_vrstice),
        SAMODEJNO_SKLADBA(R.string.os_samodejno_naslednja),
        SAMODEJNO_ALBUM(R.string.os_samodejno_album),
        SAMODEJNO_EPIZODA(R.string.os_samodejno_epizoda),
        PRESKOK_ODSEKOV(R.string.os_preskok_odsekov),
        UJEMANJE_OSVEZEVANJA(R.string.os_ujemanje_osvezevanja),
        PREDPOMNILNIK(R.string.os_predpomnilnik_naslov),
        POCISTI_NEDAVNO(R.string.os_media_pocisti_nedavno),
        SPLOSNE_NASTAVITVE(R.string.os_meni_nastavitve)
    }

    data class ZapisSkupine(val id: Skupina, val postavke: List<Postavka>)

    fun vidneSkupine(paket: String): List<ZapisSkupine> {
        val predvajalnik = paket == "si.safeer.player"
        val s = mutableListOf<ZapisSkupine>()
        
        s.add(ZapisSkupine(Skupina.VIRI, listOf(Postavka.MOJI_VIRI, Postavka.DEBRID)))
        s.add(ZapisSkupine(Skupina.DOM, listOf(Postavka.VRSTICE)))
        s.add(ZapisSkupine(Skupina.PREDVAJANJE, listOf(Postavka.SAMODEJNO_EPIZODA, Postavka.PRESKOK_ODSEKOV, Postavka.UJEMANJE_OSVEZEVANJA, Postavka.SAMODEJNO_SKLADBA, Postavka.SAMODEJNO_ALBUM)))
        s.add(ZapisSkupine(Skupina.SHRAMBA, listOf(Postavka.PREDPOMNILNIK)))
        s.add(ZapisSkupine(Skupina.ZASEBNOST, listOf(Postavka.POCISTI_NEDAVNO)))
        s.add(ZapisSkupine(Skupina.SPLOSNE, listOf(Postavka.SPLOSNE_NASTAVITVE)))
        
        return s
    }
}
