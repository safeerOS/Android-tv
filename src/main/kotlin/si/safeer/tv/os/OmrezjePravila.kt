package si.safeer.tv.os

/**
 * Omrezje naprave, ki gleda zaslon racunalnika: vrsta (za kakovost zdoma) in pravilo »vsaj 4G«.
 * Brez Androida, da se da preizkusiti na JVM; stevilke vrst so javne stalnice TelephonyManager.NETWORK_TYPE_* in
 * TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*.
 */
object OmrezjePravila {
    const val WIFI = "wifi"
    const val ETHERNET = "ethernet"
    const val G5 = "5g"
    const val G4 = "4g"
    const val G3 = "3g"
    const val G2 = "2g"
    /** Mobilno omrezje, katerega rodu ne poznamo. */
    const val MOBILNO = "celicno"
    const val NEZNANO = ""

    private val TRETJI = setOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17)    // UMTS, EVDO 0/A/B, HSDPA, HSUPA, HSPA, eHRPD, HSPA+, TD-SCDMA
    private val DRUGI = setOf(1, 2, 4, 7, 11, 16)                     // GPRS, EDGE, CDMA, 1xRTT, iDEN, GSM

    /**
     * Rod mobilnega omrezja. [tip]: vrsta podatkovnega omrezja (LTE 13, LTE_CA 19, IWLAN 18, NR 20 ...); [prikaz]: kaj
     * omrezje kaze uporabniku (NR_NSA 3, NR_NSA_MMWAVE 4, NR_ADVANCED 5) - 5G NSA je za podatke LTE, za uporabnika 5G.
     */
    fun rod(tip: Int, prikaz: Int = 0): String = when {
        tip == 20 -> G5
        (tip == 13 || tip == 19) && prikaz in 3..5 -> G5
        tip == 13 || tip == 19 || tip == 18 -> G4
        tip in TRETJI -> G3
        tip in DRUGI -> G2
        else -> MOBILNO
    }

    /**
     * Zaslon racunalnika zdoma: vsaj 4G - raje brez storitve kot slaba. Zavrnemo samo omrezje, za katero zanesljivo
     * vemo, da je slabse; neznano dovolimo.
     */
    fun dovoliZdoma(vrsta: String): Boolean = vrsta != G3 && vrsta != G2

    /** Kratka oznaka za »Podatke o povezavi« in sporocila (enaka v vseh jezikih); prazna, ce vrste ne poznamo. */
    fun oznaka(vrsta: String): String = when (vrsta) {
        WIFI -> "Wi-Fi"
        ETHERNET -> "Ethernet"
        G5 -> "5G"
        G4 -> "4G"
        G3 -> "3G"
        G2 -> "2G"
        else -> ""
    }
}
