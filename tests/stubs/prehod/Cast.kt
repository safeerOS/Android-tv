package si.safeer.tv.cast

/** Nadomestek: pravi KrogZaupanja (brez shrambe) namesto kroga iz nastavitev aplikacije. */
object KrogNaprave {
    @Volatile var krog = KrogZaupanja()
    fun krog(context: android.content.Context): KrogZaupanja = krog

    private val poslusalci = java.util.concurrent.ConcurrentHashMap<String, (android.content.Context) -> Unit>()
    fun obSpremembiKroga(ime: String, odziv: (android.content.Context) -> Unit) { poslusalci[ime] = odziv }
    /** Kar v aplikaciji naredi KrogNaprave.krog v k.obSpremembi. */
    fun javiSpremembo(context: android.content.Context) { for (odziv in poslusalci.values) odziv(context) }
}

class HubUsmerjevalnik {
    interface Shramba {
        fun beri(kljuc: String): String?
        fun pisi(kljuc: String, vrednost: String)
    }
}
