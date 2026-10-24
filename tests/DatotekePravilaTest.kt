package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 107: urejanje datotek te naprave ([DatotekePravila]). */
fun main() {
    println("Preizkus pravil urejanja datotek")
    val P = DatotekePravila
    preveriEnako("navadno ime", null, P.napakaImena("Pocitnice 2026.mp4"))
    preveriEnako("sumniki in presledki", null, P.napakaImena("Šola – zaključek.jpg"))
    preveriEnako("prazno", P.PRAZNO, P.napakaImena("   "))
    preveriEnako("posevnica (pot)", P.ZNAKI, P.napakaImena("../ven.mp4"))
    preveriEnako("dvopicje", P.ZNAKI, P.napakaImena("a:b.mp3"))
    preveriEnako("vprasaj", P.ZNAKI, P.napakaImena("kaj?.jpg"))
    preveriEnako("kontrolni znak", P.ZNAKI, P.napakaImena("a\nb.mp4"))
    preveriEnako("skrita datoteka", P.PIKA, P.napakaImena(".skrito.mp4"))
    preveriEnako("predolgo (bajti UTF-8)", P.DOLGO, P.napakaImena("č".repeat(130) + ".mp4"))
    preveriEnako("255 bajtov je se veljavno", null, P.napakaImena("a".repeat(251) + ".mp4"))

    preveriEnako("normaliziraj", "Movies/Pocitnice/", P.normaliziraj("/Movies/Pocitnice"))
    preveriEnako("normaliziraj prazno", "", P.normaliziraj(" / "))
    preveriEnako("sistemska mapa ne", false, P.smeUrejati("Android/data/com.drugi/files/"))
    preveriEnako("Android/media ne", false, P.smeUrejati("android/media/com.whatsapp/"))
    preveriEnako("neznana mapa ne", false, P.smeUrejati(""))
    preveriEnako("Movies da", true, P.smeUrejati("Movies/"))

    preveriEnako("pot iz DATA: notranji", "Movies/Pocitnice/", P.relativnaIzPoti("/storage/emulated/0/Movies/Pocitnice/a.mp4"))
    preveriEnako("pot iz DATA: USB", "DCIM/", P.relativnaIzPoti("/storage/1A2B-3C4D/DCIM/b.jpg"))
    preveriEnako("pot iz DATA: aplikacija", "Android/media/com.whatsapp/", P.relativnaIzPoti("/storage/emulated/0/Android/media/com.whatsapp/c.mp4"))
    preveriEnako("pot iz DATA: koren nosilca - ne vemo", "", P.relativnaIzPoti("/storage/emulated/0/d.mp4"))
    preveriEnako("pot iz DATA: neznana", "", P.relativnaIzPoti("/data/user/0/x/e.mp4"))
    preveriEnako("video iz Movies: DCIM, Pictures", listOf("DCIM/", "Pictures/"), P.ciljiPremika("video", "Movies/"))
    preveriEnako("video iz podmape Movies: tudi Movies", listOf("Movies/", "DCIM/", "Pictures/"), P.ciljiPremika("video", "Movies/Pocitnice/"))
    preveriEnako("slika iz DCIM/Camera: vse", listOf("Pictures/", "DCIM/"), P.ciljiPremika("image", "DCIM/Camera/"))
    preveriEnako("glasba iz Download: vse mape glasbe", listOf("Music/", "Podcasts/", "Ringtones/", "Alarms/", "Notifications/"), P.ciljiPremika("audio", "Download/"))
    preveriEnako("sistemska mapa: brez ciljev", emptyList<String>(), P.ciljiPremika("video", "Android/data/x/"))
    preveriEnako("neznana vrsta: brez ciljev", emptyList<String>(), P.ciljiPremika("pdf", "Documents/"))

    if (napak == 0) println("DatotekePravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
