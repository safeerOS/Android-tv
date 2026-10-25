package si.safeer.tv.os

import android.content.Context
import si.safeer.tv.R

/**
 * Besedila neuspelega izida debrid storitve (TorBox, Real-Debrid, AllDebrid, Usenet prek TorBoxa) - ista v Medijskem
 * centru ([GlasbaActivity]) in pri magnet povezavah ([MagnetActivity]). [storitev] = ime storitve, ki je res padla.
 */
object DebridBesedila {
    fun sporocilo(c: Context, izid: TorBox.Izid, storitev: String): String = when (izid.napaka) {
        TorBoxPravila.NAPAKA_KLJUC -> c.getString(R.string.os_torbox_napaka_kljuc, storitev)
        // Pregled 6: »60 novih na uro« je meja TorBoxa - pri drugi storitvi njeno ime in meja paketa.
        TorBoxPravila.NAPAKA_MEJA -> if (izid.cakajMs > 0 && izid.ponudnik == DebridPravila.TORBOX)
                c.getString(R.string.os_torbox_napaka_meja, ((izid.cakajMs + 59_999) / 60_000).toInt())
            else c.getString(R.string.os_torbox_napaka_meja_paket, storitev)
        TorBoxPravila.NAPAKA_PAKET -> c.getString(R.string.os_torbox_napaka_paket, storitev)
        TorBoxPravila.NAPAKA_NI_DATOTEKE -> c.getString(R.string.os_torbox_napaka_ni_datoteke, storitev)
        TorBoxPravila.NAPAKA_PRENASA -> c.getString(R.string.os_torbox_napaka_prenasa, storitev)
        DebridPravila.NAPAKA_NI_PREDPOMNJENO -> c.getString(R.string.os_debrid_napaka_ni_predpomnjeno)
        UsenetPravila.NAPAKA_INDEKSATOR -> c.getString(R.string.os_usenet_napaka_indeksator)
        else -> c.getString(R.string.os_torbox_napaka_streznik, storitev)
    } + if (izid.opis.isNotBlank()) "\n\n" + izid.opis else ""

    fun naslov(c: Context, izid: TorBox.Izid, storitev: String): String =
        if (izid.napaka == DebridPravila.NAPAKA_NI_PREDPOMNJENO) c.getString(R.string.os_debrid_napaka_naslov_vse)
        else c.getString(R.string.os_torbox_napaka_naslov, storitev)
}
