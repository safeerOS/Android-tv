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

    // Pregled 25. 10. 2026 D9: koncnica ostane tudi pri novem imenu s piko.
    preveriEnako("preimenuj: koncnica se doda", "dopust.jpg", P.zdruziIme("dopust", ".jpg"))
    preveriEnako("preimenuj: ime s piko obdrzi koncnico", "dopust 2026.10.jpg", P.zdruziIme("dopust 2026.10", ".jpg"))
    preveriEnako("preimenuj: ime ze ima koncnico", "dopust.jpg", P.zdruziIme("dopust.jpg", ".jpg"))
    preveriEnako("preimenuj: koncnica z velikimi crkami se ne podvoji", "dopust.JPG", P.zdruziIme("dopust.JPG", ".jpg"))
    preveriEnako("preimenuj: pika na koncu odpade", "dopust.jpg", P.zdruziIme("dopust. ", ".jpg"))
    preveriEnako("preimenuj: mapa brez koncnice", "Moja.mapa", P.zdruziIme("Moja.mapa", ""))
    preveriEnako("preimenuj: prazno ostane prazno", "", P.zdruziIme("  ", ".jpg"))
    preveriEnako("pregled 7: druga znana koncnica ostane", "film.mp4", P.zdruziIme("film.mp4", ".mkv"))
    preveriEnako("pregled 7: znana koncnica z velikimi crkami ostane", "Film.MP4", P.zdruziIme("Film.MP4", ".mkv"))
    preveriEnako("pregled 7: neznana koncnica - stara se doda", "film.v2.mkv", P.zdruziIme("film.v2", ".mkv"))
    preveriEnako("pregled 7: brez koncnice - stara se doda", "film.mkv", P.zdruziIme("film", ".mkv"))
    // Pregled Grok 5: vse vrste datotek, ki jih uporabnik vidi - ne samo predstavnost.
    preveriEnako("Grok 5: pdf za txt ostane", "racun.pdf", P.zdruziIme("racun.pdf", ".txt"))
    preveriEnako("Grok 5: zip ostane", "arhiv.zip", P.zdruziIme("arhiv.zip", ".rar"))
    preveriEnako("Grok 5: apk ostane", "safeer.apk", P.zdruziIme("safeer.apk", ".zip"))
    preveriEnako("Grok 5: m3u ostane", "kanali.m3u", P.zdruziIme("kanali.m3u", ".txt"))
    preveriEnako("Grok 5: nfo ostane", "film.nfo", P.zdruziIme("film.nfo", ".txt"))
    preveriEnako("Grok 5: velike crke DOCX", "Porocilo.DOCX", P.zdruziIme("Porocilo.DOCX", ".pdf"))
    preveriEnako("Grok 5: stevilka ni koncnica", "verzija 1.2.txt", P.zdruziIme("verzija 1.2", ".txt"))

    if (napak == 0) println("DatotekePravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
