package si.safeer.tv.os

/** Preizkus [OmrezjePravila]: rod mobilnega omrezja iz sistemskih stevilk in pravilo »zdoma vsaj 4G«. */
fun main() {
    // TelephonyManager.NETWORK_TYPE_*: LTE 13, LTE_CA 19, IWLAN 18, NR 20; 3G: UMTS 3, HSDPA 8, HSUPA 9, HSPA 10, HSPA+ 15;
    // 2G: GPRS 1, EDGE 2, GSM 16.
    check(OmrezjePravila.rod(20) == "5g") { "NR (samostojni 5G)" }
    check(OmrezjePravila.rod(13) == "4g" && OmrezjePravila.rod(19) == "4g" && OmrezjePravila.rod(18) == "4g") { "LTE" }
    // 5G NSA: podatki tecejo po LTE, omrezje pa uporabniku kaze 5G (prikaz 3, 4 ali 5).
    for (prikaz in 3..5) check(OmrezjePravila.rod(13, prikaz) == "5g" && OmrezjePravila.rod(19, prikaz) == "5g") { "NSA $prikaz" }
    check(OmrezjePravila.rod(13, 1) == "4g" && OmrezjePravila.rod(13, 2) == "4g") { "LTE CA in Advanced Pro sta 4G" }
    check(OmrezjePravila.rod(18, 3) == "4g") { "klici prek Wi-Fi niso 5G" }
    for (t in listOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17)) check(OmrezjePravila.rod(t) == "3g") { "3G: $t" }
    for (t in listOf(1, 2, 4, 7, 11, 16)) check(OmrezjePravila.rod(t) == "2g") { "2G: $t" }
    check(OmrezjePravila.rod(10, 3) == "3g") { "prikaz ne povisa 3G" }
    check(OmrezjePravila.rod(0) == "celicno" && OmrezjePravila.rod(99) == "celicno" && OmrezjePravila.rod(-1) == "celicno") { "neznano" }

    // Zdoma vsaj 4G; zavrnemo samo zanesljivo slabse omrezje.
    for (v in listOf("wifi", "ethernet", "5g", "4g", "celicno", "")) check(OmrezjePravila.dovoliZdoma(v)) { "dovoljeno: $v" }
    for (v in listOf("3g", "2g")) check(!OmrezjePravila.dovoliZdoma(v)) { "zavrnjeno: $v" }

    check(OmrezjePravila.oznaka("wifi") == "Wi-Fi" && OmrezjePravila.oznaka("ethernet") == "Ethernet")
    check(OmrezjePravila.oznaka("5g") == "5G" && OmrezjePravila.oznaka("4g") == "4G" && OmrezjePravila.oznaka("3g") == "3G")
    check(OmrezjePravila.oznaka("celicno") == "" && OmrezjePravila.oznaka("") == "") { "neznano nima oznake" }
    println("OmrezjePravilaTest: OK")
}
