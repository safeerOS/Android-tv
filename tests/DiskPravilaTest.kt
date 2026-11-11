package si.safeer.tv.os

import si.safeer.tv.os.DiskPravila.Nosilec
import si.safeer.tv.os.DiskPravila.Razlog
import si.safeer.tv.os.PredpomnilnikPravila.GB
import si.safeer.tv.os.PredpomnilnikPravila.Zapis

/** Lastnik 11. 11. 2026: odlocanje »ali disk uporabimo« in preprecevanje nepotrebnega pisanja (DiskPravila, PredpomnilnikPravila). */
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus odlocanja o disku")
    val dober = Nosilec(true, "mounted", true, 900 * GB, 0, 155.0)

    println("\n== ocena ==")
    preveriEnako("hiter pisljiv disk: uporabi", Razlog.UPORABI, DiskPravila.oceni(false, listOf(dober)))
    preveriEnako("uporabnik izbral Ta TV: izklopljeno", Razlog.IZKLOPLJEN, DiskPravila.oceni(true, listOf(dober)))
    preveriEnako("brez nosilcev", Razlog.NI_DISKA, DiskPravila.oceni(false, emptyList()))
    preveriEnako("vgrajena zunanja shramba ni disk", Razlog.NI_DISKA, DiskPravila.oceni(false, listOf(dober.copy(odstranljiv = false))))
    preveriEnako("exFAT (unmountable): nepodprt", Razlog.NEPODPRT, DiskPravila.oceni(false, listOf(Nosilec(true, "unmountable", false, 0, 0, null))))
    preveriEnako("nepodprt + delujoc: uporabi delujocega", Razlog.UPORABI, DiskPravila.oceni(false, listOf(Nosilec(true, "unmountable", false, 0, 0, null), dober)))
    preveriEnako("odklopljen: ni diska", Razlog.NI_DISKA, DiskPravila.oceni(false, listOf(dober.copy(stanje = "unmounted"))))
    preveriEnako("ni pisljiv", Razlog.NI_PISLJIV, DiskPravila.oceni(false, listOf(dober.copy(pisljiv = false))))
    preveriEnako("1 GB prostega ni dovolj", Razlog.PREMALO_PROSTORA, DiskPravila.oceni(false, listOf(dober.copy(prostih = GB))))
    preveriEnako("poln disk z nasim predpomnilnikom ostane", Razlog.UPORABI, DiskPravila.oceni(false, listOf(dober.copy(prostih = 300 * 1024 * 1024, nasih = 2 * GB))))
    preveriEnako("se ni merjeno: merim (disk se ne uporablja)", Razlog.MERIM, DiskPravila.oceni(false, listOf(dober.copy(mbs = null))))
    preveriEnako("pocasen (USB 2.0 poln, 8 MB/s)", Razlog.PREPOCASEN, DiskPravila.oceni(false, listOf(dober.copy(mbs = 8.0))))
    preveriEnako("natanko na pragu je dovolj", Razlog.UPORABI, DiskPravila.oceni(false, listOf(dober.copy(mbs = DiskPravila.PRAG_ZAPISA_MBS))))
    preveriEnako("tik pod pragom ni", Razlog.PREPOCASEN, DiskPravila.oceni(false, listOf(dober.copy(mbs = 19.9))))

    println("\n== hitrost, datotecni sistem, meritev ==")
    preveriEnako("32 MiB v 1 s = 33,5 MB/s", 33.554432, DiskPravila.mbNaSekundo(32L * 1024 * 1024, 1_000_000_000L))
    preveriEnako("cas 0 ne deli z nic", 0.0, DiskPravila.mbNaSekundo(1000, 0))
    preveriEnako("vfat podprt", true, DiskPravila.fsPodprt("vfat"))
    preveriEnako("ext4 podprt", true, DiskPravila.fsPodprt("EXT4"))
    preveriEnako("exfat na TV ni podprt", false, DiskPravila.fsPodprt("exfat"))
    preveriEnako("neznan fs", null, DiskPravila.fsPodprt(""))
    preveriEnako("unmountable je nepodprt", true, DiskPravila.stanjeNepodprt("unmountable"))
    preveriEnako("mounted ni nepodprt", false, DiskPravila.stanjeNepodprt("mounted"))
    val zdaj = 1_000_000_000_000L
    preveriEnako("meritev od danes velja", true, DiskPravila.meritevVelja(zdaj - 1000, zdaj))
    preveriEnako("meritev nikoli ne velja", false, DiskPravila.meritevVelja(0, zdaj))
    preveriEnako("meritev stara 8 dni ne velja", false, DiskPravila.meritevVelja(zdaj - 8L * 24 * 3_600_000, zdaj))
    preveriEnako("meritev iz prihodnosti ne velja", false, DiskPravila.meritevVelja(zdaj + 3 * 24 * 3_600_000L, zdaj))
    // N2: neuspela ali prekinjena meritev velja kratko, uspela (tudi prepocasen disk) 7 dni.
    preveriEnako("N2: neuspela meritev od pred 1 minuto velja", true, DiskPravila.meritevVelja(zdaj - 60_000, zdaj, false))
    preveriEnako("N2: neuspela meritev stara 11 minut ne velja", false, DiskPravila.meritevVelja(zdaj - 11 * 60_000L, zdaj, false))
    preveriEnako("N2: neuspela meritev stara 1 dan ne velja", false, DiskPravila.meritevVelja(zdaj - 24L * 3_600_000, zdaj, false))
    preveriEnako("N2: uspela meritev stara 1 dan velja", true, DiskPravila.meritevVelja(zdaj - 24L * 3_600_000, zdaj, true))
    preveriEnako("N2: uspela meritev stara 6 dni velja", true, DiskPravila.meritevVelja(zdaj - 6L * 24 * 3_600_000, zdaj, true))
    preveriEnako("N2: neuspela meritev iz prihodnosti ne velja", false, DiskPravila.meritevVelja(zdaj + 3 * 24 * 3_600_000L, zdaj, false))
    preveriEnako("N2: neuspela meritev nikoli ne velja", false, DiskPravila.meritevVelja(0, zdaj, false))

    println("\n== casovna omejitev meritve (N3) ==")
    val mapa = java.io.File(System.getProperty("java.io.tmpdir"))
    val hiter = DiskPreizkus.izmeriZOmejitvijo(mapa, 5_000) { DiskPreizkus.Rezultat(true, 42.0) }
    preveriEnako("N3: hitra meritev vrne izid", true to 42.0, hiter.pisljiv to hiter.mbs)
    val t0 = System.nanoTime()
    val zatik = DiskPreizkus.izmeriZOmejitvijo(mapa, 300) { Thread.sleep(8_000); DiskPreizkus.Rezultat(true, 99.0) }
    val trajalo = (System.nanoTime() - t0) / 1_000_000
    preveriEnako("N3: zatik vrne neuspelo", false to 0.0, zatik.pisljiv to zatik.mbs)
    preveriEnako("N3: klicatelj ni zadrzan dlje od omejitve (< 3 s, trajalo $trajalo ms)", true, trajalo < 3_000)
    val izjema = DiskPreizkus.izmeriZOmejitvijo(mapa, 5_000) { throw IllegalStateException("odklop") }
    preveriEnako("N3: izjema meritve vrne neuspelo", false, izjema.pisljiv)

    println("\n== formatiranje samo ob izrecni potrditvi ==")
    preveriEnako("nepodprt + potrditev", true, DiskPravila.smemOdpretiFormatiranje(Razlog.NEPODPRT, true))
    preveriEnako("nepodprt brez potrditve", false, DiskPravila.smemOdpretiFormatiranje(Razlog.NEPODPRT, false))
    preveriEnako("delujoc disk nikoli", false, DiskPravila.smemOdpretiFormatiranje(Razlog.UPORABI, true))
    preveriEnako("pocasen disk nikoli", false, DiskPravila.smemOdpretiFormatiranje(Razlog.PREPOCASEN, true))
    preveriEnako("sporocilo samo, ko je kaj za povedati", null, DiskPravila.sporociloKljuc(Razlog.UPORABI))
    preveriEnako("sporocilo za nepodprt", "os_disk_nepodprt", DiskPravila.sporociloKljuc(Razlog.NEPODPRT))

    println("\n== brez nepotrebnega pisanja ==")
    val o = PredpomnilnikPravila.odtisVsebine("abc".toByteArray())
    preveriEnako("SHA-256 abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", o)
    preveriEnako("nov vnos", Zapis.NOV, PredpomnilnikPravila.kajZapisati(null, false, o, 0, 5))
    preveriEnako("isti odtis, isti cas: nic", Zapis.NIC, PredpomnilnikPravila.kajZapisati(o, true, o, 5, 5))
    preveriEnako("isti odtis, novejsi cas: samo osvezi", Zapis.SAMO_OSVEZI, PredpomnilnikPravila.kajZapisati(o, true, o, 5, 9))
    preveriEnako("drug odtis: nov zapis", Zapis.NOV, PredpomnilnikPravila.kajZapisati("x", true, o, 5, 5))
    preveriEnako("datoteka velike vrednosti manjka: nov zapis", Zapis.NOV, PredpomnilnikPravila.kajZapisati(o, false, o, 5, 9))
    preveriEnako("stara baza brez odtisa: nov zapis", Zapis.NOV, PredpomnilnikPravila.kajZapisati(null, true, o, 5, 9))

    if (napak == 0) println("DiskPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
