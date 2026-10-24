package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Nedelujoca vsebina z razlogom ([NedelujocePravila]) - naloga 108 (Live TV) in 109 (YouTube). */
fun main() {
    println("Preizkus pravil nedelujoce vsebine")
    val P = NedelujocePravila
    preveriEnako("sonda: HTTP 404", "http:404", P.izSonde("HTTP 404 po 1 preusmeritvah"))
    preveriEnako("sonda: imenik", P.STREZNIK, P.izSonde("imenik ne pozna a.test (ime=false, kontrola=true)"))
    preveriEnako("sonda: zavrnjena povezava", P.NEODZIVEN, P.izSonde("ConnectException (kontrola=true)"))
    preveriEnako("sonda: rok", P.NEODZIVEN, P.izSonde("rok povezave (kontrola=true)"))
    preveriEnako("sonda: drugo", P.NI_ZNANO, P.izSonde("napaka SSLException"))
    preveriEnako("koda HTTP iz razloga", 503, P.kodaHttp("http:503"))
    preveriEnako("ni razlog HTTP", 0, P.kodaHttp(P.NI_TOKA))
    preveriEnako("404 je trajen", true, P.trajen("http:404"))
    preveriEnako("503 ni trajen", false, P.trajen("http:503"))
    preveriEnako("ni toka ni trajen (izpad dodatka)", false, P.trajen(P.NI_TOKA))
    preveriEnako("zaseben je trajen", true, P.trajen(P.ZASEBEN))
    preveriEnako("ni znano ni trajen", false, P.trajen(P.NI_ZNANO))
    preveriEnako("zapis tja in nazaj", "http:404" to "Arena Sport 1", P.izZapisa(P.vZapis("http:404", "Arena\tSport 1")))
    preveriEnako("star zapis brez imena", P.NI_TOKA to "", P.izZapisa(P.NI_TOKA))
    preveriEnako("brez zapisa", "" to "", P.izZapisa(null))

    // Naloga 109: glasba z YouTuba.
    preveriEnako("YT: zaseben", P.ZASEBEN, P.izNapakeYoutube("Private video\nSign in if you've been granted access to this video"))
    preveriEnako("YT: drzava", P.DRZAVA, P.izNapakeYoutube("The uploader has not made this video available in your country"))
    preveriEnako("YT: clani", P.CLANI, P.izNapakeYoutube("Join this channel to get access to members-only content like this video"))
    preveriEnako("YT: starost", P.PRIJAVA, P.izNapakeYoutube("Sign in to confirm your age"))
    preveriEnako("YT: odstranjen", P.NI_NA_VOLJO, P.izNapakeYoutube("Video unavailable\nThis video has been removed by the uploader"))
    preveriEnako("YT: avtorske pravice", P.NI_NA_VOLJO, P.izNapakeYoutube("This video is no longer available due to a copyright claim"))
    preveriEnako("YT: splosna napaka - ne vemo", P.NI_ZNANO, P.izNapakeYoutube("An error occurred. Please try again later."))
    preveriEnako("YT: prazno", P.NI_ZNANO, P.izNapakeYoutube(""))
    preveriEnako("vrsta: prvi brez razloga", 0, P.prviPredvajljiv(listOf(null, P.ZASEBEN), 0))
    preveriEnako("vrsta: preskoci trajne", 3, P.prviPredvajljiv(listOf(null, P.ZASEBEN, "http:404", P.NI_TOKA), 1))
    preveriEnako("vrsta: zacasen razlog ne preskoci", 1, P.prviPredvajljiv(listOf(null, "http:503"), 1))
    preveriEnako("vrsta: nic vec", -1, P.prviPredvajljiv(listOf(P.ZASEBEN, P.DRZAVA), 0))
    preveriEnako("vrsta: zunaj", -1, P.prviPredvajljiv(listOf(null), 5))
    preveriEnako("id iz watch", "dQw4w9WgXcQ", P.idYoutube("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
    preveriEnako("id iz youtu.be", "dQw4w9WgXcQ", P.idYoutube("https://youtu.be/dQw4w9WgXcQ"))
    preveriEnako("id iz shorts", "abcdefghijk", P.idYoutube("https://www.youtube.com/shorts/abcdefghijk"))
    preveriEnako("ni YouTube", null, P.idYoutube("https://example.org/watch?x=1"))
    preveriEnako("izlocen zadetek", listOf("b" to 200), P.brezIzlocenih(listOf("a" to 200, "b" to 200), setOf("a")))

    if (napak == 0) println("NedelujocePravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
