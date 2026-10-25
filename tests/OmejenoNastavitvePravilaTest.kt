package si.safeer.tv.link

import si.safeer.tv.link.OmejenoNastavitvePravila.Korak

/**
 * Voden tok za vklop Safeer Vnosa z omejenimi nastavitvami (link/OmejenoNastavitvePravila). Izmerjeno 25. 10. 2026 na
 * OnePlus 9 (Android 14, lokalna namestitev): tap na stikalo -> »Prepovedana nastavitev«.
 */
fun main() {
    fun k(vnos: Boolean = false, zaklenjeno: Boolean = true, poskusil: Boolean = false) =
        OmejenoNastavitvePravila.korak(vnos, zaklenjeno, poskusil)
    // Ze vklopljeno: nic.
    check(k(vnos = true) == Korak.VKLOPLJENO)
    check(k(vnos = true, zaklenjeno = true, poskusil = true) == Korak.VKLOPLJENO)
    // Omejene nastavitve so zaklenjene, uporabnik se ni poskusil vklopiti: najprej ga pripeljemo na stikalo (da se v ⋮ pokaze).
    check(k(zaklenjeno = true, poskusil = false) == Korak.ODPRI_DOSTOPNOST)
    // Tap na stikalo je »Prepovedana nastavitev« zavrnila: zdaj odkleni omejene nastavitve (⋮ -> Dovoli omejene -> PIN).
    check(k(zaklenjeno = true, poskusil = true) == Korak.ODKLENI_OMEJENE)
    // Omejene so odklenjene (allow), a stikalo se ni vklopljeno: nazaj na stikalo.
    check(k(zaklenjeno = false, poskusil = true) == Korak.VKLOPI_STIKALO)
    // Omejene niso ovira (allow ali Android < 13) in ni poskusa: stikalo takoj.
    check(k(zaklenjeno = false, poskusil = false) == Korak.ODPRI_DOSTOPNOST)

    // Omejene nastavitve veljajo samo za Android 13+ in lokalno namestitev (ne za sejo PackageInstaller / trgovino).
    check(OmejenoNastavitvePravila.omejenoVelja(34, OmejenoNastavitvePravila.PACKAGE_SOURCE_LOCAL_FILE))
    check(OmejenoNastavitvePravila.omejenoVelja(33, 3))
    check(!OmejenoNastavitvePravila.omejenoVelja(32, 3))        // Android 12: omejitve ni
    check(!OmejenoNastavitvePravila.omejenoVelja(34, 2))        // seja/trgovina (DOWNLOADED_FILE)
    check(!OmejenoNastavitvePravila.omejenoVelja(34, 1))        // STORE
    check(!OmejenoNastavitvePravila.omejenoVelja(34, 0))        // UNSPECIFIED

    println("OmejenoNastavitvePravilaTest: OK")
}
