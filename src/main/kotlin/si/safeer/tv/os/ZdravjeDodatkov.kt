package si.safeer.tv.os

/**
 * Zdravje dodatkov pri tokovih (lastnik, 5. 11. 2026: viri tokov). Dodatek, ki vec poizvedb zapored ne odgovori
 * (cas iztek, napaka) ali vrne prazno za vsebino, ki jo drugi dodatki imajo, je zadrzal izbiro toka do roka
 * (12 s) - uporabnik je cakal na vir, ki nic ne da. Po [PRAG] zaporednih neuspehih dodatek za [PRVI_PREMOR_MS]
 * (nato vsakic dvakrat dlje, najvec [NAJDALJSI_PREMOR_MS]) ne zadrzi izbire. Uspeh stevec ponastavi.
 * Stanje je samo v pomnilniku procesa. Cista pravila z vbrizgano uro (tests/ZdravjeDodatkovTest.kt).
 */
object ZdravjeDodatkov {
    const val PRAG = 3
    const val PRVI_PREMOR_MS = 10L * 60_000
    const val NAJDALJSI_PREMOR_MS = 60L * 60_000

    /** [zaporednih] neuspehov zapored; [doMs] konec premora (ura klicatelja); [premorMs] zadnji premor (za podaljsanje). */
    data class Stanje(val zaporednih: Int = 0, val doMs: Long = 0L, val premorMs: Long = 0L)

    enum class Izid { USPEH, NEUSPEH, NEVTRALNO }

    /**
     * Izid ene poizvedbe dodatka. [vprasan]: dodatek je bil za to vsebino res vprasan (tip in predpona id-ja ustrezata);
     * [odgovoril]: dal je odgovor (tudi »tega nimam«); [trajanjeMs] do odgovora proti [rokMs]; [predvajljivih] tokov
     * dodatka; [drugiPredvajljivih] tokov drugih dodatkov za isto vsebino. Prazno brez primerjave je nevtralno -
     * dodatek vsebine morda res nima.
     */
    fun izid(vprasan: Boolean, odgovoril: Boolean, trajanjeMs: Long, rokMs: Long, predvajljivih: Int, drugiPredvajljivih: Int): Izid = when {
        !vprasan -> Izid.NEVTRALNO
        !odgovoril -> Izid.NEUSPEH
        trajanjeMs > rokMs -> Izid.NEUSPEH
        predvajljivih > 0 -> Izid.USPEH
        drugiPredvajljivih > 0 -> Izid.NEUSPEH
        else -> Izid.NEVTRALNO
    }

    fun po(s: Stanje, izid: Izid, zdajMs: Long): Stanje = when (izid) {
        Izid.USPEH -> Stanje()
        Izid.NEVTRALNO -> s
        Izid.NEUSPEH -> {
            val n = s.zaporednih + 1
            when {
                n < PRAG -> s.copy(zaporednih = n)
                // Neuspeh med premorom (javni dodatek je vprasan v ozadju) premora ne podaljsa: en premor za en izpad.
                zdajMs < s.doMs -> s.copy(zaporednih = n)
                else -> {
                    val premor = if (s.premorMs <= 0L) PRVI_PREMOR_MS else minOf(s.premorMs * 2, NAJDALJSI_PREMOR_MS)
                    Stanje(n, zdajMs + premor, premor)
                }
            }
        }
    }

    /** Dodatek ta cas ne zadrzi izbire toka (premor po zaporednih neuspehih). */
    fun vPremoru(s: Stanje?, zdajMs: Long): Boolean = s != null && zdajMs < s.doMs

    /**
     * Kako vprasamo dodatek: [Nacin.CAKAJ] kot doslej, [Nacin.V_OZADJU] (javni dodatek v premoru: poizvedba gre, izbira ne
     * caka nanj - ista ena zahteva kot doslej) ali [Nacin.PRESKOCI] (dodatek z uporabnikovim kljucem v premoru: nobene
     * zahteve na uporabnikov racun, dokler premor traja - ponavljanje neuspesnih zahtev lahko racun zaklene).
     * Ce so v premoru VSI dodatki, ki jih ta poizvedba res vprasa ([vprasan], [dajeTokove]), cakamo na vse kot doslej:
     * brez tega uporabnik ne bi dobil nobenega toka. CodeRabbit 5. 11. 2026: prej je stelo cez vse dodatke - katalog brez
     * tokov (npr. Cinemeta) ni bil v premoru, zato je bil edini dodatek s tokovi (s kljucem, v premoru) preskocen.
     */
    enum class Nacin { CAKAJ, V_OZADJU, PRESKOCI }

    /**
     * Ali dodatek da tokove za [tip] in [id] - isto pravilo kot poizvedba ([Stremio.tokoviDodatkov], [Stremio.tipToka]):
     * vir `stream`, tip med `types` (prazno = vsi), id z eno od `idPrefixes` (prazno = vsi).
     */
    fun dajeTokove(viri: Collection<String>, tipi: Collection<String>, predpone: Collection<String>, tip: String, id: String): Boolean =
        "stream" in viri && (tipi.isEmpty() || tip in tipi) && (predpone.isEmpty() || predpone.any { id.startsWith(it) })

    /**
     * Ali poizvedba tokov dodatek vprasa - brez omrezja (CodeRabbit 5. krog, 5. 11. 2026): prej je preverba ob manjkajocem
     * manifestu tega zaporedno nalagala po omrezju PRED oddajo zahtev za tokove, pocasen nov dodatek je zadrzal predvajanje
     * (rok izbire se je zacel sele potem). Zdaj samo manifest iz pomnilnika; brez njega ([viri] null) dodatek steje kot
     * vprasan (kot pred to preverbo), manifest pa se nalozi v ozadju za naslednjic ([Stremio.vprasanZaTokove]).
     */
    fun vprasan(pozna: Boolean, viri: Collection<String>?, tipi: Collection<String>?, predpone: Collection<String>?,
                tip: String, idji: List<String>): Boolean {
        if (!pozna) return false
        if (viri == null) return true
        return idji.any { dajeTokove(viri, tipi.orEmpty(), predpone.orEmpty(), tip, it) }
    }

    fun nacini(vPremoru: List<Boolean>, sKljucem: List<Boolean>, vprasan: List<Boolean> = vPremoru.map { true }): List<Nacin> {
        if (vPremoru.indices.filter { vprasan.getOrElse(it) { true } }.all { vPremoru[it] }) return vPremoru.map { Nacin.CAKAJ }
        return vPremoru.indices.map { i ->
            when { !vPremoru[i] -> Nacin.CAKAJ; sKljucem.getOrElse(i) { true } -> Nacin.PRESKOCI; else -> Nacin.V_OZADJU }
        }
    }
}
