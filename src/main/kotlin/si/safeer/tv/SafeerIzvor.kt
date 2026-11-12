package si.safeer.tv

/**
 * Kdo sme biti »izvor« ob vrnitvi iz brskalnika (dodatek iz_safeer_os). MainActivity je izvozena, zato paketa iz
 * namere NE zaupamo: tuja aplikacija bi lahko deklarirala dejavnost z imenom si.safeer.tv.os.DomovActivity in
 * brskalnik bi jo ob Nazaj zagnal (obhod zagona iz ozadja, phishing). Pregled apk-24 (S1).
 */
object SafeerIzvor {
    private const val PREDPONA = "si.safeer."

    /** Samo paket Safeer z ENAKIM podpisom kot brskalnik (podpisUjema = PackageManager.SIGNATURE_MATCH). */
    fun jeZaupan(paket: String?, lastniPaket: String, podpisUjema: Boolean): Boolean {
        if (paket.isNullOrBlank()) return false
        if (paket == lastniPaket) return true
        return paket.startsWith(PREDPONA) && paket.length > PREDPONA.length && podpisUjema
    }
}
