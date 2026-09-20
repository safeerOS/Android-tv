package si.safeer.tv.cast

/**
 * Meritev ozkega grla huba: kaj se zgodi, ko se veliko naprav poveze hkrati (po ponovnem zagonu
 * sredisca) in ko ena naprava ne bere svoje vticnice (zamrznjena aplikacija, izgubljen WiFi).
 *
 * Usmerjevalnik objavi seznam naprav vsem povezanim; ce je posiljanje blokirajoce, ena zataknjena
 * naprava zadrzi vse ostale. Test to izmeri v stevilkah, brez omrezja.
 */
object ObremenitevTest {

    private class Beleznik(val ime: String, val zakasnitevMs: Long = 0) : HubUsmerjevalnik.Odjemalec {
        override val naslov = "10.0.0.1"
        val prejeto = ArrayList<String>()
        var bajtov = 0L
        override fun poslji(besedilo: String) {
            if (zakasnitevMs > 0) Thread.sleep(zakasnitevMs)
            synchronized(prejeto) { prejeto.add(besedilo); bajtov += besedilo.length }
        }
        override fun zapri(koda: Int, razlog: String) {}
    }

    private fun registriraj(h: HubUsmerjevalnik, od: Beleznik, id: String) {
        h.obdelaj(od, """{"id":"r-$id","type":"cast.register","payload":{"device_id":"$id","name":"$id","role":"sender","capabilities":["url","control"]}}""")
    }

    private fun trdi(pogoj: Boolean, sporocilo: String) {
        if (!pogoj) throw AssertionError(sporocilo)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val naprav = 20

        // 1. Vihar ob ponovnem zagonu: vseh N naprav se poveze ena za drugo.
        run {
            val h = HubUsmerjevalnik()
            val naprave = (1..naprav).map { Beleznik("n-$it") }
            val zacetek = System.nanoTime()
            naprave.forEachIndexed { i, o -> registriraj(h, o, "n-${i + 1}") }
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            val sporocil = naprave.sumOf { it.prejeto.size }
            val bajtov = naprave.sumOf { it.bajtov }
            println("vihar: $naprav naprav v %.1f ms; poslanih sporocil $sporocil, bajtov $bajtov".format(ms))
            println("  na napravo: povprecno %.1f sporocil".format(sporocil.toDouble() / naprav))
            trdi(sporocil > 0, "nic poslanega")
        }

        // 2. Ena naprava ne bere (poslji traja 300 ms): koliko casa traja prijava naslednje naprave?
        run {
            val h = HubUsmerjevalnik()
            val hitre = (1..5).map { Beleznik("h-$it") }
            hitre.forEachIndexed { i, o -> registriraj(h, o, "h-${i + 1}") }
            val zataknjena = Beleznik("zataknjena", zakasnitevMs = 300)
            registriraj(h, zataknjena, "zataknjena")

            val nova = Beleznik("nova")
            val zacetek = System.nanoTime()
            registriraj(h, nova, "nova")
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            println("zataknjena naprava: prijava naslednje naprave je trajala %.0f ms".format(ms))

            // Koliko casa traja navadno posredovanje sporocila med dvema hitrima napravama,
            // medtem ko je v seznamu zataknjena naprava?
            val zacetek2 = System.nanoTime()
            h.obdelaj(hitre[0], """{"id":"s1","type":"share.text","target":"h-2","payload":{"text":"pozdrav"}}""")
            val ms2 = (System.nanoTime() - zacetek2) / 1_000_000.0
            println("  posredovanje besedila med hitrima napravama: %.0f ms".format(ms2))

            // Odklop zataknjene naprave (npr. ko WiFi pade) - tudi to objavi seznam vsem.
            val zacetek3 = System.nanoTime()
            h.odklopi(zataknjena)
            val ms3 = (System.nanoTime() - zacetek3) / 1_000_000.0
            println("  odklop zataknjene naprave: %.0f ms".format(ms3))
        }

        // 3. Vzporedno: 8 niti posilja sporocila, ena naprava je zataknjena.
        run {
            val h = HubUsmerjevalnik()
            val hitre = (1..8).map { Beleznik("p-$it") }
            hitre.forEachIndexed { i, o -> registriraj(h, o, "p-${i + 1}") }
            val zataknjena = Beleznik("p-zataknjena", zakasnitevMs = 200)
            registriraj(h, zataknjena, "p-zataknjena")
            val zacetek = System.nanoTime()
            val niti = (0 until 8).map { k ->
                Thread {
                    repeat(20) {
                        h.obdelaj(hitre[k], """{"id":"m$k-$it","type":"share.text","target":"p-${(k % 8) + 1}","payload":{"text":"x"}}""")
                    }
                }.apply { start() }
            }
            niti.forEach { it.join() }
            val ms = (System.nanoTime() - zacetek) / 1_000_000.0
            println("vzporedno: 8 niti x 20 sporocil v %.0f ms".format(ms))
        }
        println("OK ObremenitevTest")
    }
}
