package si.safeer.tv.scit

import com.safeer.threatfeed.PlainListSource

/**
 * Kateri seznami brskalnika gredo v nabor Scita (domene.bin, grozne.bin). Cista funkcija brez Androida (preizkus v JVM).
 *
 * Scit je filter DNS: rabi samo imena domen. EasyList (raw, pravila za brskalnik) in seznami IPv4 (Feodo) v nabor ne gredo,
 * zato jih proces :scit ne prenasa in ne bere (4. 11. 2026, prej je :scit zagnal celega agenta brskalnika).
 */
object ScitViri {
    /**
     * Lastnik 9. 11. 2026: »doubleclick.net, ad.doubleclick.net, www.googleadservices.com, c.amazon-adsystem.com niso
     * blokirani - ni sprejemljivo«. HaGeZi PRO jih namenoma nima (meritev na TV 9. 11.: 0 neskladij s seznamom). Linux jih
     * od 5. 11. 2026 ustavi v DNS (core/scit_pravila.py, »oglasni_strezniki«); en Scit za vse OS - tu je ISTI seznam
     * (sledilci brez uporabniske strani; connect.facebook.net in graph.facebook.com namenoma ne: prijava in stran Facebook).
     * Vnos velja za domeno in vse poddomene (nabor je »wildcard«). Ob spremembi seznama povecaj razlicico v [VGRAJENE_ID].
     */
    val VGRAJENE: List<String> = listOf(
        // Google: oglasi (DoubleClick, AdSense, Ad Manager, Ads) in zbiranje meritev aplikacij (Firebase)
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "app-measurement.com", "firebaselogging-pa.googleapis.com",
        // Oglasne borze in povezovanje identitet (Amazon, The Trade Desk, LiveRamp, Neustar, Krux, Adobe)
        "amazon-adsystem.com", "adsrvr.org", "rlcdn.com", "agkn.com", "krxd.net", "cdn.krxd.net", "2o7.net",
        "everesttech.net",
        // Piksli za oglase in konverzije
        "pixel.facebook.com", "an.facebook.com", "analytics.tiktok.com", "analytics-sg.tiktok.com",
        "tr.snapchat.com", "ct.pinterest.com", "px.ads.linkedin.com", "snap.licdn.com", "static.ads-twitter.com",
        "ads-twitter.com", "bat.bing.com", "clarity.ms", "c.clarity.ms", "alb.reddit.com", "pixel.reddit.com",
        // Zbiralniki merjenja obcinstva
        "scorecardresearch.com", "sb.scorecardresearch.com", "imrworldwide.com", "hit.gemius.pl", "quantserve.com",
        "quantcount.com", "mc.yandex.ru", "mc.yandex.com",
    )
    const val VGRAJENE_ID = "safeer-vgrajeno-1"

    /** Par (id, vsota) vgrajenega seznama za kljuc nabora ([DomenskiNabor.kljucVira]). */
    fun vgrajeniKljuc(): Pair<String, String> = VGRAJENE_ID to VGRAJENE.joinToString(",")

    /** Seznam gre v domene.bin. */
    fun zaNabor(s: PlainListSource): Boolean = !s.raw && !s.ipv4

    /** Seznam gre tudi v grozne.bin (ne oglasi: izjema Scita ga nikoli ne umakne). */
    fun zaGrozne(s: PlainListSource): Boolean = zaNabor(s) && !s.dnsOnly

    /**
     * Seznam bere glavni proces brskalnika. Seznami samo za DNS (dnsOnly, HaGeZi PRO) ne: brskalnik jih ne uporablja, branje
     * 247 757 domen ob vsaki osvezitvi pa je na TV dvignilo kopico na 98/122 MB (4. 11. 2026). Bere jih samo proces :scit.
     */
    fun zaBrskalnik(s: PlainListSource): Boolean = !s.dnsOnly

    /** Viri za agenta seznamov v procesu :scit, v istem vrstnem redu kot v brskalniku (enak kljuc vira nabora). */
    fun viri(vsi: List<PlainListSource>): List<PlainListSource> = vsi.filter(::zaNabor)
}
