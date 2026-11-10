package si.safeer.tv.os

/**
 * Viri tokov (lastnik, 5. 11. 2026): dvojniki istega torrenta iz vec dodatkov so en vnos ([TokiPravila]), mrtev torrent
 * gre na konec tudi po ureditvi za hitrost ([TokIzbira.sejalciNaKonec]).
 */
fun main() {
    val o = "👤"
    val h1 = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
    val h2 = "0123456789abcdef0123456789abcdef01234567"
    val h3 = "1111111111111111111111111111111111111111"
    val h4 = "2222222222222222222222222222222222222222"
    val h5 = "3333333333333333333333333333333333333333"
    val h6 = "4444444444444444444444444444444444444444"
    fun magnet(h: String, ime: String, vararg tr: String) = "magnet:?xt=urn:btih:$h&dn=$ime" + tr.joinToString("") { "&tr=$it" }
    val tA = "udp%3A%2F%2Ftracker.a.example%3A1337%2Fannounce"
    val tB = "udp%3A%2F%2Ftracker.b.example%3A6969%2Fannounce"
    val tC = "https%3A%2F%2Ftracker.c.example%2Fannounce"
    // Primer odgovorov treh dodatkov za isti film (oblika, kot jo da Stremio.tok(): magnet z &tr= iz `sources`).
    val torrentio = listOf(
        TokiPravila.Vnos("torrent", magnet(h1, "Film.2024.1080p.mkv", tA, tB), "Torrentio\n1080p", "Film.2024.1080p.WEB-DL.DDP5.1.H.264-SKUPINA\n$o 120 💾 4.2 GB", "Torrentio", 0),
        TokiPravila.Vnos("torrent", magnet(h2, "Film.2024.2160p.mkv", tA), "Torrentio\n4k", "Film.2024.2160p.WEB-DL.DDP5.1.HDR.H.265\n$o 0 💾 15.1 GB", "Torrentio", 1),
        TokiPravila.Vnos("torrent", magnet(h3, "Film.2024.720p.mkv", tA), "Torrentio\n720p", "Film.2024.720p.WEB.x264\n$o 35 💾 1.1 GB", "Torrentio", 0),
        TokiPravila.Vnos("torrent", magnet(h4, "Film.2024.480p.mkv"), "Torrentio\n480p", "Film.2024.480p\n$o 4 💾 0.7 GB", "Torrentio", -1))
    val mediaFusion = listOf(
        TokiPravila.Vnos("torrent", magnet(h1, "Film.2024.1080p.mkv", tB, tC), "MediaFusion 1080p", "Film 2024 1080p\n$o 140", "MediaFusion", 0),
        TokiPravila.Vnos("torrent", magnet(h2.uppercase(), "Film.2024.2160p.mkv", tC), "MediaFusion 4K", "Film 2024 2160p\n$o 3", "MediaFusion", 1),
        TokiPravila.Vnos("torrent", magnet(h5, "Film.2024.1080p.x265.mkv", tC), "MediaFusion 1080p", "Film 2024 1080p x265 2.0 GB\n$o 60", "MediaFusion", 0))
    val comet = listOf(
        TokiPravila.Vnos("torrent", magnet(h1.uppercase(), "Film.2024.1080p.mkv", tA), "Comet 1080p", "Film.2024.1080p", "Comet", 0),
        // Ista izdaja, druga datoteka (paket): ni dvojnik.
        TokiPravila.Vnos("torrent", magnet(h3, "Film.2024.720p.mkv"), "Comet 720p", "Film.2024.720p.extra", "Comet", 2))
    // Debrid/http brez infoHash: nikoli po infoHash, tudi ce je besedilo enako.
    val debrid = listOf(
        TokiPravila.Vnos("url", "https://debrid.example/d/1/Film.mkv", "RD+ 1080p", "Film.2024.1080p\n$o 120", "Torrentio RD"),
        TokiPravila.Vnos("url", "https://debrid.example/d/2/Film.mkv", "RD+ 1080p", "Film.2024.1080p\n$o 120", "Torrentio RD"))
    // Webtor (url s hashem v podatkih): loceno od nasih torrentov; neznan indeks (-2) se ne zdruzi.
    val webtor = listOf(
        TokiPravila.Vnos("url", "https://webtor.example/resolve/a", "Webtor 1080p", "Film 1080p", "Webtor", 0, h1, WebtorPravila.VIR, listOf("tracker:$tA")),
        TokiPravila.Vnos("url", "https://webtor.example/resolve/b", "Webtor 720p", "Film 720p", "Webtor", WebtorPravila.IDX_NEZNAN, h6, WebtorPravila.VIR),
        TokiPravila.Vnos("url", "https://webtor.example/resolve/c", "Webtor 720p", "Film 720p", "Webtor", WebtorPravila.IDX_NEZNAN, h6, WebtorPravila.VIR))
    val vsi = torrentio + mediaFusion + comet + debrid + webtor
    val zdruzeni = TokiPravila.zdruziVnose(vsi)
    // Meritev (5. 11. 2026, ta primer): 14 vnosov -> 11 (h1 iz treh dodatkov, h2 iz dveh).
    println("TokiPravilaTest: tokov pred zdruzitvijo ${vsi.size}, po ${zdruzeni.size}")
    check(vsi.size == 14 && zdruzeni.size == 11) { "${vsi.size} -> ${zdruzeni.size}" }

    val (i1, z1) = zdruzeni[0]
    // Predstavnik: najbolje oznacen (Torrentio: velikost, zvok, kodek), sejalci najvec v skupini (MediaFusion 140).
    check(i1 == 0 && z1.dodatek == "Torrentio") { "$i1 $z1" }
    check(TokIzbira.sejalci(z1.ime + " " + z1.opis) == 140) { z1.opis }
    check(TokIzbira.opisi(z1.ime + " " + z1.opis).gb in 4.1..4.3) { z1.opis }
    check(TokiPravila.sledilnikiMagneta(z1.url) == listOf(tA, tB, tC)) { z1.url }
    check(TorBoxPravila.hashIzMagneta(z1.url) == h1 && z1.url.contains("&dn=Film.2024.1080p.mkv")) { z1.url }
    check(z1.izDodatkov == listOf("Torrentio", "MediaFusion", "Comet")) { z1.izDodatkov.toString() }
    // h2: veliko velik hash je isti torrent; predstavnik Torrentio (4K, 15 GB), sejalci 3 iz MediaFusion namesto 0.
    val (i2, z2) = zdruzeni[1]
    check(i2 == 1 && TokIzbira.sejalci(z2.ime + " " + z2.opis) == 3 && TokIzbira.opisi(z2.opis).visina == 2160) { "$i2 $z2" }
    check(z2.opis.contains("$o 3 ") && !z2.opis.contains("$o 0")) { z2.opis }
    // Ostali ostanejo, kot so (isti indeks, nespremenjeni).
    check(zdruzeni.drop(2).all { (i, v) -> vsi[i] == v }) { zdruzeni.toString() }
    check(zdruzeni.count { it.second.vir == WebtorPravila.VIR } == 3) { "Webtor ni zdruzen z nasimi torrenti, neznan indeks ne" }
    check(zdruzeni.count { it.second.vrsta == "url" && it.second.vir.isBlank() } == 2) { "debrid brez infoHash ostane" }
    // Webtor sledilniki: skupina z enim samim vnosom ostane nespremenjena.
    check(TokiPravila.zdruziVnose(webtor + webtor.first().copy(url = "https://webtor.example/resolve/d", sledilniki = listOf("tracker:$tB")))
        .first().second.sledilniki == listOf("tracker:$tA", "tracker:$tB"))

    // Kljuc: brez infoHash ni zdruzevanja; Webtor z neznanim indeksom ne; vrsta in vir locita.
    check(TokiPravila.kljuc("url", "https://x.example/a.mkv", "", "", -1) == null)
    check(TokiPravila.kljuc("url", "https://w.example/a", h1, WebtorPravila.VIR, WebtorPravila.IDX_NEZNAN) == null)
    check(TokiPravila.kljuc("torrent", magnet(h1, "a"), "", "", 0) != TokiPravila.kljuc("url", "https://w", h1, WebtorPravila.VIR, 0))
    check(TokiPravila.kljuc("torrent", magnet(h1, "a"), "", "", 0) != TokiPravila.kljuc("torrent", magnet(h1, "a"), "", "", -1))
    check(TokiPravila.kljuc("torrent", "magnet:?dn=brez-hasha", "", "", 0) == null)
    // Usenet nikoli.
    check(TokiPravila.kljuc(UsenetPravila.VRSTA, "https://indeks.example/nzb/1", "", "", -1) == null)

    // Meja sledilnikov: najvec 30, brez dvojnikov.
    val mnogo = (1..40).map { "udp%3A%2F%2Ft$it.example%3A1%2Fa" }
    val dolg = TokiPravila.zSledilniki(magnet(h1, "a", mnogo[0]), mnogo + mnogo)
    check(TokiPravila.sledilnikiMagneta(dolg).size == TokiPravila.NAJVEC_SLEDILNIKOV && TokiPravila.sledilnikiMagneta(dolg).distinct().size == 30) { dolg }
    check(TokiPravila.zSledilniki(magnet(h1, "a", tA), listOf(tA)) == magnet(h1, "a", tA))

    // Sejalci v besedilu: zamenja prvo navedbo ali doda.
    check(TokiPravila.zSejalci("Film 1080p Seeders: 12 Size 2 GB", 40) == "Film 1080p Seeders: 40 Size 2 GB")
    check(TokiPravila.zSejalci("Film\n$o 0 💾 2 GB", 7) == "Film\n$o 7 💾 2 GB")
    check(TokIzbira.sejalci(TokiPravila.zSejalci("Film 1080p 2 GB", 9)) == 9)
    check(TokiPravila.zSejalci("Film", -1) == "Film")

    // Sejalci po ureditvi za hitrost: mrtev torrent (0) zadnji, malo (< 3) za ostalimi, neznano nevtralno; nic ne izgine.
    val poHitrosti = listOf("A $o 0 1 GB", "B $o 2 1 GB", "C 2 GB", "D $o 50 3 GB")
    check(TokIzbira.sejalciNaKonec(poHitrosti) { it } == listOf("C 2 GB", "D $o 50 3 GB", "B $o 2 1 GB", "A $o 0 1 GB"))
    check(TokIzbira.razredSejalcev("X $o 0") == 2 && TokIzbira.razredSejalcev("X $o 2") == 1 && TokIzbira.razredSejalcev("X $o 3") == 0 &&
        TokIzbira.razredSejalcev("X") == 0)
    // 3D (lastnik, 10. 11. 2026): 3D izdaje na TV ni - ne v seznamu ne pri samodejni izbiri.
    for (b in listOf("Film.1998.1080p.3D.HSBS.BluRay.x264", "Film 1998 3D 1080p", "Film.1998.Half-SBS.1080p", "Film (1998) [3D] [H-SBS]",
        "Film.1998.1080p.BluRay.SBS.x264", "Film.1998.3D.Half-OU.1080p", "RD+ 1080p\nFilm.1998.3D.mkv", "Film 3D"))
        check(TokiPravila.je3d(b)) { b }
    // »3D« pred letnico je del naslova; besede, ki oznako le vsebujejo, niso 3D.
    for (b in listOf("Film.1998.1080p.BluRay.x264", "Jackass.3D.2010.1080p.BluRay", "Step Up 3D (2010) 720p", "Film.1998.WEB-DL.DDP5.1.H.264",
        "Film.1998.1080p.x264-SBSGROUP", "R3D.2011.1080p", "Film.1998.1080p 3 dni", "Thou.Shalt.Not.1998.720p", ""))
        check(!TokiPravila.je3d(b)) { b }
    check(TokiPravila.je3d("Jackass.3D.2010.1080p.3D.HSBS"))
    check(TokiPravila.brez3d(listOf("A 1080p", "B 3D 1080p", "C HSBS", "D 720p")) { it } == listOf("A 1080p", "D 720p"))
    val s3d = TokiPravila.zdruziVnose(listOf(
        TokiPravila.Vnos("torrent", magnet(h1, "Film.1998.3D.HSBS.mkv"), "Torrentio\n1080p", "Film.1998.1080p.3D.HSBS\n$o 900 💾 9 GB", "Torrentio", 0),
        TokiPravila.Vnos("url", "https://debrid.example/d/3/Film.mkv", "RD+ 1080p 3D", "Film.1998.Half-SBS", "Torrentio RD"),
        TokiPravila.Vnos("torrent", magnet(h2, "Film.1998.720p.mkv"), "Torrentio\n720p", "Film.1998.720p\n$o 5 💾 1 GB", "Torrentio", 0)))
    check(s3d.map { it.first } == listOf(2)) { s3d.toString() }
    println("TokiPravilaTest: OK")
}
