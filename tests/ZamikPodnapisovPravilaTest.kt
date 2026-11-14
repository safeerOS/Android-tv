package si.safeer.tv.os

fun main() {
    val z = ZamikPodnapisovPravila
    val NEZNANO = z.BREZ_TRAJANJA
    // Meje in koraki.
    check(z.omeji(99_999) == 30_000L && z.omeji(-99_999) == -30_000L && z.omeji(300) == 300L)
    check(z.spremeni(0, 100) == 100L && z.spremeni(29_950, 100) == 30_000L && z.spremeni(-29_950, -100) == -30_000L)
    // Zamik 0 ne spremeni nič.
    check(z.zamakni(5_000_000, 2_000_000, 0) == (5_000_000L to 2_000_000L))
    // Pozneje: napis se začne pozneje, trajanje ostane (podnapis prehiteva sliko).
    check(z.zamakni(5_000_000, 2_000_000, 1_500_000) == (6_500_000L to 2_000_000L))
    // Prej: začne se prej.
    check(z.zamakni(5_000_000, 2_000_000, -1_000_000) == (4_000_000L to 2_000_000L))
    // Prej pred začetkom videa: skrajšan, začne pri 0, konec ostane na zamaknjenem mestu.
    check(z.zamakni(500_000, 2_000_000, -1_000_000) == (0L to 1_500_000L)) { "delno pred 0 se skrajša" }
    // V celoti pred začetkom: ne pokažemo (tudi natanko na 0).
    check(z.zamakni(500_000, 400_000, -1_000_000) == null)
    check(z.zamakni(1_000_000, 1_000_000, -2_000_000) == null) { "konec natanko 0 ne šteje" }
    // Neznano trajanje (zadnji napis): ostane neznano, začetek ne pod 0.
    check(z.zamakni(5_000_000, NEZNANO, 1_000_000) == (6_000_000L to NEZNANO))
    check(z.zamakni(500_000, NEZNANO, -1_000_000) == (0L to NEZNANO))
    // Oblika besedila.
    check(z.oblika(0) == "0 s" && z.oblika(300) == "+0,3 s" && z.oblika(-1500) == "−1,5 s" && z.oblika(12_000) == "+12,0 s")
    check(z.trajanje(100) == "0,1 s" && z.trajanje(-500) == "0,5 s" && z.trajanje(2000) == "2,0 s")
    // Naslov za zamik iz Format.id (slepi pregled 14. 11. 2026, S3): dodatek, datoteka (SAF, file), predpona zdruzenega vira.
    check(z.naslovIzId("0:https://a/b.srt") == "https://a/b.srt")
    check(z.naslovIzId("1:0:http://a/b.srt") == "http://a/b.srt")
    check(z.naslovIzId("0:content://com.android.externalstorage.documents/document/primary%3AFilm.srt") ==
        "content://com.android.externalstorage.documents/document/primary%3AFilm.srt")
    check(z.naslovIzId("file:///storage/emulated/0/Film.srt") == "file:///storage/emulated/0/Film.srt")
    check(z.naslovIzId("2") == null && z.naslovIzId(null) == null && z.naslovIzId("sl:vgrajen") == null)
    println("ZamikPodnapisovPravilaTest OK")
}
