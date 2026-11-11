package si.safeer.tv.os

/**
 * Lastnik 11. 11. 2026: »uporabnik naj lahko uporabi prenosni disk za predpomnilnik, kjer je to mogoce«. Ciste odlocitve
 * (brez Androida in diska; preizkus tests/DiskPravilaTest.kt): ali odstranljiv nosilec uporabimo, in ce ne, zakaj ne.
 * Izdelek mora biti odlicen tudi BREZ diska - disk je samo dodatek; vsak razlog razen [Razlog.UPORABI] pomeni, da vse
 * deluje kot doslej (notranji pomnilnik). Meritev in zapis ureja [UsbPredpomnilnik] / [DiskPreizkus].
 */
object DiskPravila {
    const val MIB = 1024L * 1024

    /**
     * Najmanjsa hitrost zapisa (MB/s, 1 MB = 10^6 B), pri kateri disk sploh uporabimo. Utemeljitev: prejsnji USB kljuc, ki ga je
     * okvara upocasnila, je povzrocal zavrzene zapise Useneta; najpocasnejsi sodobni USB 2.0 nosilec trajno pise ~20-30 MB/s,
     * USB 3 nosilec (Intenso SSD, izmerjeno ~155 MB/s) bistveno vec. Film potrebuje najvec ~12,5 MB/s (100 Mbit/s), prag 20
     * da 1,6-kratno rezervo za hkratno branje. Pocasnejsi disk (stari kljuc, kartica) bi TV upocasnil, zato gre vse na TV.
     */
    const val PRAG_ZAPISA_MBS = 20.0

    /** Velikost preizkusnega zapisa ob priklopu: dovolj za zanesljivo povprecje, kratko tudi na 20 MB/s (~1,6 s). */
    const val PREIZKUS_BAJTOV = 32 * MIB

    /** Meritev velja 7 dni (disk se ne spremeni sam; okvara kljuca se pokaze tudi z zavrzenimi zapisi, [PredpomnilnikPravila.usbOdpoveduje]). */
    const val MERITEV_VELJA_MS = 7L * 24 * 3_600_000

    /**
     * Datotecni sistemi, ki jih jedro TV prepozna (izmerjeno 11. 11. 2026: vold »unsupported filesystem exfat«; ext2/3/4,
     * vfat in ntfs/fuseblk so podprti). exFAT TUKAJ NI - na TV se nosilec sploh ne priklopi. [fsInfo] je samo informativen:
     * aplikacija datotecnega sistema priklopljenega nosilca ne vidi (fuse), vidi samo stanje nosilca ([stanjeNepodprt]).
     */
    private val PODPRTI = setOf("vfat", "fat32", "fat", "ext2", "ext3", "ext4", "f2fs", "ntfs", "fuseblk")

    /** true = podprt, false = ni, null = neznan (prazen niz). */
    fun fsPodprt(fs: String?): Boolean? {
        val f = fs?.trim()?.lowercase().orEmpty()
        return if (f.isEmpty()) null else f in PODPRTI
    }

    /** Stanje nosilca, ki ga sistem pozna, a ne more priklopiti (StorageVolume.getState() == »unmountable«). */
    fun stanjeNepodprt(stanje: String?): Boolean = stanje.equals("unmountable", ignoreCase = true)

    /** Hitrost v MB/s (10^6 B/s); 0 pri neveljavnem casu. */
    fun mbNaSekundo(bajtov: Long, nanosekund: Long): Double =
        if (bajtov <= 0L || nanosekund <= 0L) 0.0 else bajtov / 1_000_000.0 / (nanosekund / 1_000_000_000.0)

    fun dovoljHiter(mbs: Double): Boolean = mbs >= PRAG_ZAPISA_MBS

    /** Meritev [izmerjenoOb] (0 = nikoli) se velja ob [zdaj]; ura iz prihodnosti (> 1 dan) ne velja. */
    fun meritevVelja(izmerjenoOb: Long, zdaj: Long): Boolean =
        izmerjenoOb > 0L && zdaj - izmerjenoOb in -86_400_000L..MERITEV_VELJA_MS

    /** Zakaj disk je ali ni v uporabi. [UPORABI] edini pomeni »da«. */
    enum class Razlog { UPORABI, IZKLOPLJEN, NI_DISKA, NEPODPRT, NI_PISLJIV, PREMALO_PROSTORA, MERIM, PREPOCASEN }

    /**
     * Nosilec, kot ga vidimo: [stanje] StorageVolume/Environment, [pisljiv] (preizkus zapisa v mapo aplikacije uspel),
     * [prostih] + [nasih] bajtov, [mbs] izmerjena hitrost zapisa ali null (se ni merjeno).
     */
    data class Nosilec(val odstranljiv: Boolean, val stanje: String, val pisljiv: Boolean, val prostih: Long, val nasih: Long, val mbs: Double?)

    /**
     * Odlocitev v korist uporabnika: disk uporabimo samo, ce je odstranljiv, priklopljen, pisljiv, ima dovolj prostora
     * ([PredpomnilnikPravila.PRAG_PROSTEGA]) in je zapis dovolj hiter. Dokler hitrost ni izmerjena ([Razlog.MERIM]), disk NI
     * v uporabi - TV nikoli ne caka na neznan nosilec. [izklopljeno] = uporabnik je izbral »Ta TV« (nikoli samodejno formatiranje).
     */
    fun oceni(izklopljeno: Boolean, n: List<Nosilec>): Razlog {
        if (izklopljeno) return Razlog.IZKLOPLJEN
        val odstranljivi = n.filter { it.odstranljiv }
        if (odstranljivi.isEmpty()) return Razlog.NI_DISKA
        val pripravljeni = odstranljivi.filter { it.stanje.equals("mounted", ignoreCase = true) }
        if (pripravljeni.isEmpty()) return if (odstranljivi.any { stanjeNepodprt(it.stanje) }) Razlog.NEPODPRT else Razlog.NI_DISKA
        val pisljivi = pripravljeni.filter { it.pisljiv }
        if (pisljivi.isEmpty()) return Razlog.NI_PISLJIV
        val prostorni = pisljivi.filter { it.prostih + it.nasih > PredpomnilnikPravila.PRAG_PROSTEGA }
        if (prostorni.isEmpty()) return Razlog.PREMALO_PROSTORA
        if (prostorni.any { it.mbs == null }) return Razlog.MERIM
        return if (prostorni.any { dovoljHiter(it.mbs ?: 0.0) }) Razlog.UPORABI else Razlog.PREPOCASEN
    }

    /**
     * Formatiranje: aplikacija ga NE izvede sama (potrebuje sistemsko pravico, ki je aplikacije nimajo). Ponudimo samo odprtje
     * sistemskega zaslona za shrambo in samo ob IZRECNI potrditvi, da ve, da se izbrise vse na disku. Ponudba velja samo, ce je
     * nosilec nepodprt - nikoli za delujoc disk.
     */
    fun smemOdpretiFormatiranje(razlog: Razlog, izrecnaPotrditev: Boolean): Boolean = razlog == Razlog.NEPODPRT && izrecnaPotrditev

    /** Ali pokazemo uporabniku sporocilo o disku (kljuc niza v virih): samo, ko mu je s tem kaj prihranjeno ali jasno. */
    fun sporociloKljuc(r: Razlog): String? = when (r) {
        Razlog.NEPODPRT -> "os_disk_nepodprt"
        Razlog.PREPOCASEN -> "os_disk_prepocasen"
        Razlog.NI_PISLJIV -> "os_disk_ni_pisljiv"
        Razlog.PREMALO_PROSTORA -> "os_disk_premalo_prostora"
        else -> null
    }
}
