import si.safeer.tv.os.YoutubeKanalVrsta as K
import si.safeer.tv.os.YoutubeKanalVrsta.Vrsta

private var napakKanalVrsta = 0
private fun preveriKanalVrsta(ime: String, ok: Boolean) { if (!ok) { napakKanalVrsta++; println("FAIL $ime") } }

fun main() {
    preveriKanalVrsta("trajanje m:ss", K.trajanje("3:51") == 231)
    preveriKanalVrsta("trajanje h:mm:ss", K.trajanje("1:02:03") == 3723)
    preveriKanalVrsta("trajanje slabo", K.trajanje("3:5") == null && K.trajanje("a:bc") == null && K.trajanje("") == null && K.trajanje(null) == null && K.trajanje("1:75") == null)
    // Glasba
    preveriKanalVrsta("uradni video", K.vrsta("Rick Astley - Dance (Live at The O2, London, April 2026)", "Rick Astley", 231) == Vrsta.GLASBA)
    preveriKanalVrsta("izvajalec - naslov brez oznak", K.vrsta("Nirvana - Lithium", "Nirvana", 257) == Vrsta.GLASBA)
    preveriKanalVrsta("kanal Topic", K.vrsta("Lithium", "Nirvana - Topic", 257) == Vrsta.GLASBA)
    preveriKanalVrsta("brez trajanja samo z znacilnostjo", K.vrsta("Song (Official Audio)", "X", null) == Vrsta.GLASBA)
    preveriKanalVrsta("brez trajanja in znacilnosti ostalo", K.vrsta("Nirvana - Lithium", "Nirvana", null) == Vrsta.OSTALO)
    // Ostalo
    preveriKanalVrsta("podcast", K.vrsta("Podcast #12 - Glasba in zivljenje", "Ana", 3000) == Vrsta.OSTALO)
    preveriKanalVrsta("track by track ni skladba", K.vrsta("Rick Astley - '50' Track By Track (10 Years Later)", "Rick Astley", 1200) == Vrsta.OSTALO)
    preveriKanalVrsta("predolgo = zbirka", K.vrsta("Best of 2026 - mix (Official Audio)", "X", 3600) == Vrsta.OSTALO)
    preveriKanalVrsta("vlog", K.vrsta("Moj dan - vlog", "Ana", 600) == Vrsta.OSTALO)
    preveriKanalVrsta("brez znacilnosti ostalo", K.vrsta("Kako popravim kolo", "Marko", 480) == Vrsta.OSTALO)
    preveriKanalVrsta("turneja ni skladba", K.vrsta("Rick Astley - The Never Book Tour Dublin 2024", "Rick Astley", 412) == Vrsta.OSTALO)
    preveriKanalVrsta("kratek zapis z vzorcem ni skladba", K.vrsta("Rick Astley - What's in your fridge?", "Rick Astley", 66) == Vrsta.OSTALO)
    preveriKanalVrsta("vzorec pod 100 s ni skladba", K.vrsta("A - B", "X", 99) == Vrsta.OSTALO && K.vrsta("A - B", "X", 100) == Vrsta.GLASBA)
    // Kratki
    preveriKanalVrsta("shorts zavihek", K.vrsta("Nirvana - Lithium", "Nirvana", null, jeShorts = true) == Vrsta.KRATKI)
    preveriKanalVrsta("do 60 s", K.vrsta("Dance (Official Video)", "X", 45) == Vrsta.KRATKI)
    preveriKanalVrsta("#shorts v naslovu", K.vrsta("Fun #Shorts", "X", null) == Vrsta.KRATKI)
    preveriKanalVrsta("61 s ni kratek", K.vrsta("A - B (Official Video)", "X", 61) == Vrsta.GLASBA)
    println(if (napakKanalVrsta == 0) "YoutubeKanalVrstaTest: VSE V REDU" else "YoutubeKanalVrstaTest: $napakKanalVrsta napak")
    if (napakKanalVrsta > 0) kotlin.system.exitProcess(1)
}
