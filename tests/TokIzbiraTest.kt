package si.safeer.tv.os

fun main() {
    val telefon = TokIzbira.Zmoznosti(visina = 1080, hevc = true, eac3 = false, ac3 = false)
    val tv4k = TokIzbira.Zmoznosti(visina = 2160, hevc = true, hdr = true, dolbyVision = true, eac3 = true, ac3 = true)
    val tokovi = listOf(
        "Dodatek 4K [A] [12.66 GB] FILM.2020.2160p.REPACK.WEB-DL.MULTi.DDP5.1.Atmos.DV.HDR.H.265-SKUPINA.mkv HDR DV A",
        "Dodatek 2160p [A] [18.61 GB] FILM.2020.2160p.WEB-DL.MULTi.DDP5.1.Atmos.H.265-SKUPINA.mkv A",
        "Dodatek 1080p [3.1 GB] FILM.2020.1080p.WEB-DL.AAC.H.264.mkv",
        "Dodatek 720p [1.2 GB] FILM.2020.720p.WEB-DL.AAC.H.264.mkv",
        "CAM 1080p [1.9 GB] FILM.2020.1080p.HDCAM.AAC.mkv"
    )
    // Opis iz imena: locljivost, kodek, HDR/DV, zvok, velikost.
    val o = TokIzbira.opisi(tokovi[0])
    check(o.visina == 2160 && o.hevc && o.hdr && o.dv && o.zvok == "eac3" && o.gb in 12.6..12.7) { o }
    check(TokIzbira.opisi("Film 700 MB 480p").gb in 0.68..0.69)
    check(TokIzbira.opisi("Film.2019.1080p.BluRay.DTS.x264").zvok == "dts")
    check(TokIzbira.opisi("Film brez podatkov").visina == 0)
    // Telefon 1080p brez dekodirnika Dolby: 1080p z zvokom AAC, ne 4K brez zvoka; posnetek iz kina zadnji med 1080p.
    val zaTelefon = TokIzbira.uredi(tokovi, { it }, telefon)
    check(zaTelefon.first() == tokovi[2]) { zaTelefon.first() }
    check(zaTelefon.indexOf(tokovi[3]) < zaTelefon.indexOf(tokovi[0])) { "720p z zvokom pred 4K brez zvoka" }
    check(zaTelefon.indexOf(tokovi[4]) > zaTelefon.indexOf(tokovi[2]))
    // Televizor 4K z Dolby Vision in Dolby zvokom: 4K, manjsa datoteka prej (hitrejsi zacetek).
    val zaTv = TokIzbira.uredi(tokovi, { it }, tv4k)
    check(zaTv.first() == tokovi[0]) { zaTv.first() }
    check(zaTv[1] == tokovi[1])
    // Brez dekodirnika HEVC tok H.265 ni izbran, ce obstaja drug.
    val brezHevc = TokIzbira.uredi(listOf("A 1080p x265 2 GB", "B 720p x264 1 GB"), { it }, TokIzbira.Zmoznosti(hevc = false))
    check(brezHevc.first().startsWith("B"))
    // Enaka ocena: vrstni red dodatka ostane.
    check(TokIzbira.uredi(listOf("X 1080p", "Y 1080p"), { it }, telefon) == listOf("X 1080p", "Y 1080p"))
    // Sejalci (torrent): opis jih pove kot "👤 123" ali "Seeders: 12"; brez podatka -1.
    check(TokIzbira.sejalci("Film.2020.1080p.WEB\n\uD83D\uDC64 245 \uD83D\uDCBE 2.1 GB") == 245)
    check(TokIzbira.sejalci("Film 1080p Seeders: 12 Size 2 GB") == 12)
    check(TokIzbira.sejalci("Film 1080p 2 GB") == -1)
    // Podprt torrent 720p pred slabo podprtim 1080p (zacne se takoj); mrtev torrent je zadnji.
    val roj = TokIzbira.uredi(listOf("A 1080p \uD83D\uDC64 2 3 GB", "B 720p \uD83D\uDC64 300 1 GB", "C 1080p \uD83D\uDC64 0 3 GB", "D 1080p \uD83D\uDC64 80 3 GB"), { it }, telefon)
    check(roj == listOf("D 1080p \uD83D\uDC64 80 3 GB", "B 720p \uD83D\uDC64 300 1 GB", "A 1080p \uD83D\uDC64 2 3 GB", "C 1080p \uD83D\uDC64 0 3 GB")) { roj }
    // Na televizorju 4K z nekaj sejalci 4K ostane pred 1080p z veliko sejalci.
    check(TokIzbira.uredi(listOf("E 1080p \uD83D\uDC64 500 2 GB", "F 2160p \uD83D\uDC64 15 12 GB"), { it }, tv4k).first().startsWith("F"))
    // ---- Rok veljavnosti povezave (podpisane, casovno omejene povezave do shrambe v oblaku) ----
    val zdaj = 1_791_054_000L                                  // 3. 10. 2026 19:00:00 UTC
    val podpisana = "https://racun.shramba.example/mapa/film.mkv?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=k%2F20261003%2Fauto%2Fs3%2Faws4_request" +
        "&X-Amz-Date=20261003T161807Z&X-Amz-Expires=10800&X-Amz-Signature=abc&X-Amz-SignedHeaders=host"
    // Podpisana ob 16:18:07 za 3 ure -> ob 19:00:00 velja se 18 min 7 s; ob 19:18:07 potece.
    check(TokIzbira.veljaSe(podpisana, zdaj) == 1087L) { TokIzbira.veljaSe(podpisana, zdaj).toString() }
    check(!TokIzbira.potekla(podpisana, zdaj) && TokIzbira.potekla(podpisana, zdaj + 1087) && TokIzbira.potekla(podpisana, zdaj + 1030))
    check(TokIzbira.veljaSe("https://storage.example/f.mp4?X-Goog-Date=20261003T180000Z&X-Goog-Expires=7200&X-Goog-Signature=x", zdaj) == 3600L)
    check(TokIzbira.veljaSe("https://cdn.example/f.mkv?Expires=${zdaj + 500}&Signature=x&Key-Pair-Id=y", zdaj) == 500L)
    check(TokIzbira.veljaSe("https://cdn.example/f.mkv?token=abc&expires=${zdaj - 30}", zdaj) == -30L)
    check(TokIzbira.veljaSe("https://cdn.example/f.m3u8?hdnts=exp=${zdaj + 60}~acl=/*~hmac=ff", zdaj) == 60L)
    check(TokIzbira.veljaSe("https://cdn.example/f.m3u8?hdnts=st=1~exp=${zdaj + 90}~hmac=ff", zdaj) == 90L)
    check(TokIzbira.veljaSe("https://racun.blob.example/f.mkv?sv=2022&se=2026-10-03T20%3A00%3A00Z&sr=b&sig=x", zdaj) == 3600L)
    check(TokIzbira.veljaSe("https://racun.blob.example/f.mkv?se=2026-10-03T19:30Z&sig=x", zdaj) == 1800L)
    // Brez roka: trajna povezava, stevilka, ki ni cas, in parameter z drugim pomenom.
    check(TokIzbira.veljaSe("https://datoteke.example/api/file/AbCd1234?download", zdaj) == null)
    check(TokIzbira.veljaSe("https://cdn.example/f.mkv?id=1234567890&exp=12", zdaj) == null)
    check(TokIzbira.veljaSe("https://cdn.example/f.mkv?expires=never", zdaj) == null)
    check(TokIzbira.veljaSe("https://cdn.example/f.mkv?X-Amz-Date=20261003T161807Z", zdaj) == null)
    // 1. 1. 1970 in prestopno leto (racun dni brez java.time).
    check(TokIzbira.veljaSe("https://x.example/f?X-Amz-Date=19700101T000000Z&X-Amz-Expires=0", 0) == 0L)
    check(TokIzbira.veljaSe("https://x.example/f?X-Amz-Date=20240229T120000Z&X-Amz-Expires=60", 1_709_208_000L) == 60L)
    // Ista datoteka pri dveh gostiteljih: trajna povezava je pred casovno omejeno, tudi ce jo dodatek navede drugo.
    data class Tok(val opis: String, val url: String)
    val trajna = "https://datoteke.example/api/file/AbCd1234?download"
    val par = listOf(Tok("Dodatek 2160p [A] [4.63 GB] FILM.2020.2160p.HDR.HEVC.mkv", podpisana), Tok("Dodatek 2160p [B] [4.63 GB] FILM.2020.2160p.HDR.HEVC.mkv", trajna))
    check(TokIzbira.uredi(par, { it.opis }, tv4k).first().url == podpisana) { "brez naslova ostane vrstni red dodatka" }
    check(TokIzbira.uredi(par, { it.opis }, tv4k, { it.url }, zdaj - 3600).first().url == trajna) { "trajna pred omejeno" }
    // Casovno omejena 4K (velja se dve uri) ostane pred trajno 1080p: rok je le jezicek na tehtnici.
    val visja = listOf(Tok("Dodatek 1080p [2 GB] FILM.2020.1080p.mkv", trajna), Tok("Dodatek 2160p [9 GB] FILM.2020.2160p.HEVC.mkv", podpisana))
    check(TokIzbira.uredi(visja, { it.opis }, tv4k, { it.url }, zdaj - 6000).first().url == podpisana)
    // Tik pred iztekom (manj kot 20 min): za drugimi iste locljivosti, a pred nizjo locljivostjo.
    val trije = listOf(Tok("Dodatek 2160p [5 GB] FILM.2020.2160p.HEVC.mkv", podpisana), Tok("Dodatek 2160p [20 GB] FILM.2020.2160p.HEVC.mkv", trajna),
        Tok("Dodatek 1080p [2 GB] FILM.2020.1080p.mkv", trajna + "2"))
    check(TokIzbira.uredi(trije, { it.opis }, tv4k, { it.url }, zdaj).map { it.opis.substringAfter("[").substringBefore("]") } == listOf("20 GB", "5 GB", "2 GB"))
    // Potekla povezava je zadnja od vseh - tudi za tokom, ki ga naprava slabse predvaja.
    val potekli = TokIzbira.uredi(trije, { it.opis }, tv4k, { it.url }, zdaj + 5000)
    check(potekli.last().url == podpisana && potekli.first().opis.contains("20 GB")) { potekli.toString() }
    // ---- Dolby Atmos: prednost samo tam, kjer ga zvocna veriga res odda ----
    check(TokIzbira.opisi("FILM.2020.1080p.WEB-DL.DDP5.1.Atmos.H.264").atmos && !TokIzbira.opisi("FILM.2020.1080p.WEB-DL.DDP5.1.H.264").atmos)
    val zAtmosom = TokIzbira.Zmoznosti(visina = 1080, eac3 = true, ac3 = true, atmos = true)
    val brezAtmosa = TokIzbira.Zmoznosti(visina = 1080, eac3 = true, ac3 = true)
    val dva = listOf("A 1080p [3.0 GB] FILM.2020.1080p.WEB-DL.AAC.H.264", "B 1080p [6.5 GB] FILM.2020.1080p.WEB-DL.DDP5.1.Atmos.H.264")
    check(TokIzbira.uredi(dva, { it }, zAtmosom).first().startsWith("B")) { "veriga odda Atmos: tok z Atmosom prvi, ceprav je vecji" }
    check(TokIzbira.uredi(dva, { it }, brezAtmosa).first().startsWith("A")) { "brez Atmosa v verigi ostane manjsa datoteka" }
    // Locljivosti Atmos ne prehiti; zelo velike datoteke tudi ne.
    check(TokIzbira.uredi(listOf("C 2160p [9 GB] FILM.2160p.HEVC.AAC", "D 1080p [4 GB] FILM.1080p.DDP5.1.Atmos"), { it },
        TokIzbira.Zmoznosti(visina = 2160, eac3 = true, atmos = true)).first().startsWith("C"))
    check(TokIzbira.uredi(listOf("E 1080p [2 GB] FILM.1080p.AAC", "F 1080p [20 GB] FILM.1080p.DDP5.1.Atmos"), { it }, zAtmosom).first().startsWith("E"))
    // TrueHD Atmos: brez dekodirnika in brez predaje je to film brez zvoka; s sprejemnikom, ki TrueHD sprejme, gre.
    val remux = listOf("G 1080p [4 GB] FILM.1080p.AAC", "H 1080p [9 GB] FILM.1080p.BluRay.TrueHD.7.1.Atmos")
    check(TokIzbira.uredi(remux, { it }, zAtmosom).first().startsWith("G"))
    check(TokIzbira.uredi(remux, { it }, zAtmosom.copy(truehd = true)).first().startsWith("H"))
    println("TokIzbiraTest: OK")
}
