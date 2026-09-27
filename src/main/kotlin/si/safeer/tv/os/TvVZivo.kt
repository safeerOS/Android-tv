package si.safeer.tv.os

import java.util.Locale

/**
 * TV v zivo: samo uradni, javno objavljeni prenosi izdajateljev (preverjeno do posnetka, 27. 9. 2026),
 * brez posrednikov. Enak seznam kot Safeer OS na racunalniku (core/zakoniti_viri.py TV_V_ZIVO).
 * RTV SLO javnega toka nima: pokazemo jo samo uporabnikom iz Slovenije in odpremo uradno stran.
 */
object TvVZivo {
    private class Kanal(val id: String, val ime: String, val jezik: String, val tok: String,
                        val stran: String, val samoZa: String, val drzava: String)

    private val KANALI = listOf(
        Kanal("rtvslo", "RTV SLO v živo", "sl", "", "https://365.rtvslo.si/v-zivo", "SI", "SI"),
        Kanal("dw-en", "DW News", "en", "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", "", "", "DE"),
        Kanal("dw-de", "DW Deutsch", "de", "https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/index.m3u8", "", "", "DE"),
        Kanal("dw-es", "DW Español", "es", "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8", "", "", "DE"),
        Kanal("tagesschau24", "tagesschau24 (ARD)", "de", "https://tagesschau.akamaized.net/hls/live/2020115/tagesschau/tagesschau_1/master.m3u8", "", "", "DE"),
        Kanal("f24-en", "France 24 English", "en", "https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_5000.m3u8", "", "", "FR"),
        Kanal("f24-fr", "France 24 Français", "fr", "https://live.france24.com/hls/live/2037179/F24_FR_HI_HLS/master_5000.m3u8", "", "", "FR"),
        Kanal("f24-es", "France 24 Español", "es", "https://live.france24.com/hls/live/2037220/F24_ES_HI_HLS/master_5000.m3u8", "", "", "FR"),
        Kanal("aje", "Al Jazeera English", "en", "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8", "", "", "QA"),
        Kanal("trt-world", "TRT World", "en", "https://tv-trtworld.medya.trt.com.tr/master.m3u8", "", "", "TR"),
        Kanal("cbs-news", "CBS News 24/7", "en", "https://cbsn-us.cbsnstream.cbsnews.com/out/v1/55a8648e8f134e82a470f83d562deeca/master.m3u8", "", "", "US"),
        Kanal("arirang", "Arirang TV", "en", "https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8", "", "", "KR"),
        Kanal("redbull", "Red Bull TV", "en", "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8", "", "", "AT"),
    )

    /** Uradna stran prenosa (kadar izdajatelj javnega toka nima) - odpre se v brskalniku. */
    fun jeStran(sk: Jamendo.Skladba) = sk.id.startsWith("tv:") && sk.zvok.isBlank() && sk.povezava.isNotBlank()

    /** Kanali po drzavah: najprej drzava naprave, nato druge po abecedi (imena drzav v jeziku naprave). */
    fun poDrzavah(): List<Pair<String, List<Jamendo.Skladba>>> {
        val moja = Locale.getDefault().country.uppercase(Locale.ROOT)
        val jezik = Locale.getDefault()
        return KANALI.filter { it.samoZa.isEmpty() || it.samoZa == moja }
            .groupBy { it.drzava }
            .map { (drzava, kanali) ->
                val ime = Locale("", drzava).getDisplayCountry(jezik).ifBlank { drzava }
                ime to kanali.map { k ->
                    val jezikIme = Locale(k.jezik).getDisplayLanguage(jezik)
                    Jamendo.Skladba("tv:${k.id}", k.ime, "$ime · $jezikIme", "", k.tok, k.stran, video = true,
                        mime = if (k.tok.isNotBlank()) MedijskiViri.MIME_HLS else "")
                } to drzava
            }
            .sortedWith(compareBy({ it.second != moja }, { it.first.first }))
            .map { it.first }
    }
}
