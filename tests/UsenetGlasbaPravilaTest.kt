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
    preveriEnako("lep naslov z pikami", "Izvajalec Album 2020 FLAC", G.lepNaslov("Izvajalec.Album.2020.FLAC"))
    preveriEnako("velikost MB", "350 MB", G.velikost(350L * 1024 * 1024))
    preveriEnako("velikost GB", "1.5 GB", G.velikost(1536L * 1024 * 1024))
    println(if (napak == 0) "UsenetGlasbaPravilaTest: OK" else "UsenetGlasbaPravilaTest: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
