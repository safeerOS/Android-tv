package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis (pricakovano=$pricakovano, dobljeno=$dobljeno)") }
}

/** Lastnik 27. 10. 2026: glasba z Useneta (iskanje pri indeksatorju, album kot vrsta skladb). Imena so izmisljena. */
fun main() {
    val G = UsenetGlasbaPravila
    println("== iskanje (Newznab, kategorija 3000) ==")
    preveriEnako("naslov iskanja", "https://indeksnik.example/api?apikey=k%261&t=search&cat=3000&q=moj+album&limit=50&extended=1",
        G.naslovIskanja("https://indeksnik.example/", "k&1", " moj album "))
    preveriEnako("prekratka beseda: brez zahteve", null, G.naslovIskanja("https://indeksnik.example", "k", "a"))
    preveriEnako("brez kljuca: brez zahteve", null, G.naslovIskanja("https://indeksnik.example", "", "album"))
    preveriEnako("iskanje skladbe (t=music&track)", "https://indeksnik.example/api?apikey=k&t=music&cat=3000&track=prva+pesem&limit=50&extended=1",
        G.naslovIskanjaSkladbe("https://indeksnik.example", "k", "prva pesem"))
    preveriEnako("zdruzeno brez dvojnikov, najprej izdaje", listOf("a", "b", "c"), G.zdruzi(listOf("a", "b"), listOf("b", "c")) { it })
    println("== skladbe izdaje ==")
    val imena = listOf("album.par2", "02 - Druga.flac", "cover.jpg", "01 - Prva.flac", "10 - Deseta.flac", "Izvajalec.Album.Sample.flac",
        "album.nfo", null, "03_Tretja.MP3", "album.part01.rar")
    preveriEnako("samo zvok, po stevilki, brez vzorca in rar", listOf(3, 1, 8, 4), G.skladbe(imena))
    preveriEnako("brez zvoka (album v RAR): prazno", emptyList<Int>(), G.skladbe(listOf("a.rar", "a.r00", "a.par2")))
    preveriEnako("najvec skladb", G.NAJVEC_SKLADB, G.skladbe((1..500).map { "%03d.mp3".format(it) }).size)
    preveriEnako("vse koncnice", true, listOf("a.flac", "a.mp3", "a.m4a", "a.aac", "a.ogg", "a.opus", "a.wav").all { G.jeZvok(it) })
    preveriEnako("video ni zvok", false, G.jeZvok("film.mkv"))
    preveriEnako("zacne pri iskani skladbi", 2, G.zacetnaSkladba(listOf("Prva", "Druga pesem", "Tretja Pesem!"), "tretja pesem"))
    preveriEnako("iskal izvajalca: zacne pri prvi", 0, G.zacetnaSkladba(listOf("Prva", "Druga"), "Izvajalec"))
    preveriEnako("prazno iskanje: prva", 0, G.zacetnaSkladba(listOf("Prva"), " "))
    println("== imena ==")
    preveriEnako("ime skladbe", "Prva skladba", G.imeSkladbe("01 - Prva skladba.flac"))
    preveriEnako("ime s podcrtaji in potjo", "Tretja pesem", G.imeSkladbe("CD1/03_Tretja_pesem.mp3"))
    preveriEnako("prazno ime", "Skladba", G.imeSkladbe(null))
    preveriEnako("izvajalec iz naslova", "Izvajalec", G.izvajalec("Izvajalec - Album (2020) [FLAC]"))
    preveriEnako("brez izvajalca", "", G.izvajalec("Album"))
    // Lastnik 27. 10. 2026: priljubljeni album se po imenu najde v novem iskanju.
    val shranjen = "Linkin.Park-Unshatter.OST-2026-FLAC"
    preveriEnako("isti album ne glede na pike in velike crke", true, G.istiAlbum(shranjen, "linkin park unshatter ost 2026 flac"))
    preveriEnako("drug album ni isti", false, G.istiAlbum(shranjen, "Linkin.Park-Meteora-2003-FLAC"))
    preveriEnako("med zadetki: ista velikost ima prednost", 2,
        G.izberiShranjenega(listOf("Drugo" to 1L, shranjen to 900L, "Linkin Park Unshatter OST 2026 FLAC" to 1_000_000L), shranjen, 1_000_500L))
    preveriEnako("albuma ni vec: -1", -1, G.izberiShranjenega(listOf("Drugo" to 1L), shranjen, 5L))
    // Lastnik 27. 10. 2026 (TV, album z Useneta): imena kot so prisla z Useneta.
    val lp = G.lepeSkladbe(listOf(
        "01-linkin_park-waiting_for_the_end__unshatter_film_soundtrack.mp3",
        "02-linkin_park-numb__unshatter_film_soundtrack.mp3",
        "03-linkin_park-in_the_end__unshatter_film_soundtrack.mp3",
        "04-linkin_park-break_collapse__interlude___unshatter_film_soundtrack.mp3"))
    preveriEnako("album: naslov brez izvajalca in albuma, velike zacetnice", "Waiting for the End", lp[0].first)
    preveriEnako("album: izvajalec ostane v svojem polju", "Linkin Park", lp[0].second)
    preveriEnako("album: kratek naslov", "Numb", lp[1].first)
    preveriEnako("album: vsi imajo izvajalca", setOf("Linkin Park"), lp.map { it.second }.toSet())
    preveriEnako("album: izvajalec iz naslova izdaje ima prednost", "Linkin Park",
        G.lepeSkladbe(listOf("01 - Numb.flac", "02 - Faint.flac"), "Linkin Park")[0].second)
    val pravilno = G.lepeSkladbe(listOf("01 - AC-DC Live.flac", "02 - Re-Arrange.flac", "03 - Hold On.flac"))
    preveriEnako("pravilno zapisana imena ostanejo (vezaj v naslovu ni izvajalec)", listOf("AC-DC Live", "Re-Arrange", "Hold On"), pravilno.map { it.first })
    preveriEnako("brez skupnega izvajalca: izvajalec prazen", "", pravilno[0].second)
    preveriEnako("ena skladba brez vzorca: ime ostane", "Song", G.lepeSkladbe(listOf("01 - song.mp3"))[0].first)
    preveriEnako("lep naslov z pikami", "Izvajalec Album 2020 FLAC", G.lepNaslov("Izvajalec.Album.2020.FLAC"))
    preveriEnako("velikost MB", "350 MB", G.velikost(350L * 1024 * 1024))
    preveriEnako("velikost GB", "1.5 GB", G.velikost(1536L * 1024 * 1024))
    println(if (napak == 0) "UsenetGlasbaPravilaTest: OK" else "UsenetGlasbaPravilaTest: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
    // Lastnik 28. 10. 2026: pri »luknja« druga izdaja istega albuma, ne drug album, ne neuspela.
    run {
        val n = listOf("Linkin Park - Meteora (2003) [FLAC]", "Linkin Park - Meteora (2003) [MP3]", "Linkin Park - Hybrid Theory (2000)",
            "Linkin Park - Meteora (2003) [FLAC] (repost)")
        val d = UsenetGlasbaPravila.drugeIzdaje(n, n[0], 5) { it == 3 }
        if (d != listOf(1)) { println("  NAPAKA  druge izdaje: $d"); kotlin.system.exitProcess(1) } else println("  ok  druge izdaje istega albuma brez neuspelih")
        val j = UsenetGlasbaPravila.jedroAlbuma("Linkin_Park-Meteora-2003-FLAC-GRP")
        if (j != "linkin park meteora") { println("  NAPAKA  jedro albuma: $j"); kotlin.system.exitProcess(1) } else println("  ok  jedro albuma iz imena izdaje")
    }
    // Lastnik 28. 10. 2026: kartica Usenet - album, leto, oblika.
    run {
        val G2 = UsenetGlasbaPravila
        fun je(opis: String, p: Any?, d: Any?) { if (p != d) { println("  NAPAKA  $opis: $d"); kotlin.system.exitProcess(1) } else println("  ok  $opis") }
        je("presledki: album brez letnice in oblike", UsenetGlasbaPravila.OpisIzdaje("Linkin Park", "Hybrid Theory", "2000", "FLAC"),
            G2.opisIzdaje("Linkin Park - Hybrid Theory (2000) [FLAC]"))
        je("pike in vezaji", UsenetGlasbaPravila.OpisIzdaje("Linkin Park", "Unshatter", "2026", "FLAC"), G2.opisIzdaje("Linkin.Park-Unshatter-2026-FLAC-GRP"))
        je("kartica: izvajalec vrstico nad albumom", "Linkin Park\nHybrid Theory" to "2000 · MP3 · 91 MB",
            G2.kartica("Linkin Park - Hybrid Theory (2000) [MP3]", 91L * 1024 * 1024))
        je("kartica brez izvajalca: samo album", "Unshatter" to "2026", G2.kartica("Unshatter 2026", 0L))
        je("brez vezaja: celo ime brez oznak", "Unshatter", G2.opisIzdaje("Unshatter 2026 FLAC").album)
    }
}
