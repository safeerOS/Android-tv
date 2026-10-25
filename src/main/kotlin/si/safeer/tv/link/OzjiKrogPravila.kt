package si.safeer.tv.link

/**
 * Ozji krog zaupanja (»Schengen«, isto kot core/link_dostop.py in safeer_os.py na racunalniku): programe, datoteke in
 * zaslon druge naprave ta naprava pokaze in odpre SAMO, ce je tista naprava v ozjem krogu TE naprave - ce ji je ta
 * naprava sama odprla vsaj eno zmoznost (cast/Dostop). Lastnik, 17. 10. 2026: »Telefon, ki ni v ozjem krogu zaupanja,
 * med napravami za ogled programov in datotek ne smemo niti videti.« Neposreden klic za napravo zunaj kroga se zavrne s
 * kodo [KODA]; tista naprava ne dobi nobenega ukaza.
 *
 * Tu so samo pravila (brez Androida), da jih preizkus tests/OzjiKrogPravilaTest.kt preveri v navadnem JVM.
 */
object OzjiKrogPravila {
    /** Ista koda kot na racunalniku (safeer_os.py: {"ok": False, "koda": "ni_ozji_krog"}). */
    const val KODA = "ni_ozji_krog"

    /** Naprava je v ozjem krogu, ce ji je tu odprta vsaj ena zmoznost; ta naprava sama je vedno v njem. */
    fun vKrogu(zmoznosti: Set<*>, taNaprava: Boolean): Boolean = taNaprava || zmoznosti.isNotEmpty()

    /** Iz seznama ostanejo samo naprave v ozjem krogu, v istem vrstnem redu. Napaka pri preverbi = ni v krogu. */
    fun <T> samoKrog(naprave: List<T>, vKrogu: (T) -> Boolean): List<T> =
        naprave.filter { n -> try { vKrogu(n) } catch (_: Throwable) { false } }

    /**
     * Izbira v Nastavitvah (»Ozji krog zaupanja«): nova dovoljenja za naprave, ki so se spremenile. Naprava, ki ostane v
     * krogu, obdrzi, kar ji je bilo odprto (delni dostop ostane delni); nova v krogu dobi [vse]; izbrisana nima nicesar
     * vec (ostane v Safeer Linku in pomaga pri povezavi). Naprave brez spremembe v izidu ni.
     */
    fun <Z> spremembe(prej: Map<String, Set<Z>>, izbrane: Set<String>, vse: Set<Z>): Map<String, Set<Z>> {
        val izid = LinkedHashMap<String, Set<Z>>()
        for (id in prej.keys + izbrane) {
            val bilo = prej[id].orEmpty()
            val novo = when {
                id !in izbrane -> emptySet()
                bilo.isNotEmpty() -> bilo
                else -> vse
            }
            if (novo != bilo) izid[id] = novo
        }
        return izid
    }

    /**
     * Ali je katera naprava v [potem] (izid [spremembe] ali nov zapis dostopa) na novo dobila zmoznost [z]. Ob tem ta
     * naprava takoj vprasa za dovoljenja Androida, ki jih zmoznost potrebuje (lastnik 25. 10. 2026: ena privolitev v
     * Linku, brez locenih stikal). Odstranitev ali ze prej odprta zmoznost ne vprasa nicesar.
     */
    fun <Z> novaZmoznost(prej: Map<String, Set<Z>>, potem: Map<String, Set<Z>>, z: Z): Boolean =
        potem.any { (id, dano) -> z in dano && z !in prej[id].orEmpty() }
}
