package si.safeer.tv.cast

/**
 * Pregled 5. 11. 2026 (S6, najprej na telefonu ae93970): OkHttp privzeto sledi preusmeritvam in glavo X-Safeer-Token
 * poslje tudi na novi naslov - Hub (ali kdor se v omrezju izdaja zanj) bi zeton preusmeril na drug gostitelj, tudi po
 * http. Odjemalci Huba zato preusmeritvam ne sledijo; odgovor 3xx je za klicatelja napaka (ni 200). Brez Androida
 * (tests/HubPreusmeritveTest.kt). Vir za telefon (tools/link-core-sync.sh).
 */
object HubPreusmeritve {
    fun brez(graditelj: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder =
        graditelj.followRedirects(false).followSslRedirects(false)

    /** Odzivna koda preverbe naslova: preusmeritev pomeni, da tu ni Huba (kot brez odgovora). */
    fun kodaPreverbe(koda: Int): Int = if (koda in 300..399) 0 else koda
}
