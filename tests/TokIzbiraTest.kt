package si.safeer.tv.os

fun main() {
    val telefon = TokIzbira.Zmoznosti(visina = 1080, hevc = true, eac3 = false, ac3 = false)
    val tv4k = TokIzbira.Zmoznosti(visina = 2160, hevc = true, hdr = true, dolbyVision = true, eac3 = true, ac3 = true)
    val tokovi = listOf(
        "4KHDHub 4K [FSL] [12.66 GB] UNABOMBER.2026.2160p.REPACK.NF.WEB-DL.MULTi.DDP5.1.Atmos.DV.HDR.H.265-4kHdHub.Com.mkv HDR DV FSL",
        "HdHub 2160p [FSL] [18.61 GB] UNABOMBER.2026.2160p.NF.WEB-DL.MULTi.DDP5.1.Atmos.H.265-4kHdHub.Com.mkv FSL",
        "HdHub 1080p [3.1 GB] UNABOMBER.2026.1080p.WEB-DL.AAC.H.264.mkv",
        "HdHub 720p [1.2 GB] UNABOMBER.2026.720p.WEB-DL.AAC.H.264.mkv",
        "CAM 1080p [1.9 GB] UNABOMBER.2026.1080p.HDCAM.AAC.mkv"
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
    println("TokIzbiraTest: OK")
}
