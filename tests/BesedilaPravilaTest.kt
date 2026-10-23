package si.safeer.tv.os

/** Naloga 89: besedila pesmi iz LRCLIB - razclenitev LRC, trenutna vrstica, ciscenje naslova, poizvedba, vir. */
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus besedil pesmi (LRCLIB)")

    println("\n== razclenitev LRC ==")
    val v = BesedilaPravila.razcleni("[ar:X]\n[offset:+500]\n[00:12.00][01:30.5]Refren\n[00:05.123]Prva\n[00:20]\nbrez casa\n")
    preveriEnako("vrstice po casu, vec casov v vrstici, offset, prazna vrstica, oznake in vrstice brez casa odpadejo",
        listOf(BesedilaPravila.Vrstica(4_623, "Prva"), BesedilaPravila.Vrstica(11_500, "Refren"), BesedilaPravila.Vrstica(19_500, ""),
            BesedilaPravila.Vrstica(90_000, "Refren")), v)
    preveriEnako("stotinke, desetinke in tisocinke", listOf(500L, 500L, 500L),
        listOf("[00:00.5]a", "[00:00.50]a", "[00:00.500]a").map { BesedilaPravila.razcleni(it).first().ms })
    preveriEnako("negativen offset premakne pozneje", 1_500L, BesedilaPravila.razcleni("[offset:-500]\n[00:01.00]a").first().ms)
    preveriEnako("offset ne da negativnega casa", 0L, BesedilaPravila.razcleni("[offset:+5000]\n[00:01.00]a").first().ms)
    preveriEnako("navadno besedilo brez casov: prazno (prikaze se kot navadno)", emptyList<BesedilaPravila.Vrstica>(),
        BesedilaPravila.razcleni("Prva vrstica\nDruga vrstica"))
    preveriEnako("prazno", emptyList<BesedilaPravila.Vrstica>(), BesedilaPravila.razcleni(""))

    println("\n== trenutna vrstica ==")
    preveriEnako("pred prvo", -1, BesedilaPravila.trenutna(v, 1_000))
    preveriEnako("tocno ob zacetku vrstice", 1, BesedilaPravila.trenutna(v, 11_500))
    preveriEnako("med vrsticama", 1, BesedilaPravila.trenutna(v, 15_000))
    preveriEnako("po zadnji", 3, BesedilaPravila.trenutna(v, 999_000))
    preveriEnako("brez vrstic", -1, BesedilaPravila.trenutna(emptyList(), 5_000))

    println("\n== naslov in izvajalec za iskanje ==")
    preveriEnako("YouTube: Izvajalec - Naslov (Official Video), kanal VEVO", "Coldplay" to "Yellow",
        BesedilaPravila.iskalniPar("Coldplay - Yellow (Official Video)", "ColdplayVEVO"))
    preveriEnako("kanal - Topic", "Coldplay" to "Yellow", BesedilaPravila.iskalniPar("Yellow", "Coldplay - Topic"))
    preveriEnako("oglati oklepaj z oznako", "Ed Sheeran" to "Shape of You", BesedilaPravila.iskalniPar("Shape of You [Official Lyric Video]", "Ed Sheeran"))
    preveriEnako("brez izvajalca: iz naslova, (Live) odpade", "Siddharta" to "Ledena", BesedilaPravila.iskalniPar("Siddharta - Ledena (Live)", ""))
    preveriEnako("feat. v oklepaju odpade", "Eminem" to "Lose Yourself", BesedilaPravila.iskalniPar("Lose Yourself (feat. X)", "Eminem"))
    preveriEnako("feat. brez oklepaja odpade", "Adele" to "Hello", BesedilaPravila.iskalniPar("Hello feat. Someone", "Adele"))
    preveriEnako("vsebinski oklepaj ostane", "Izvajalec" to "Moja pesem (Akusticna)", BesedilaPravila.iskalniPar("Moja pesem (Akusticna)", "Izvajalec"))
    preveriEnako("pomislaj v naslovu pravega izvajalca ostane", "Kanal Nekdo" to "Ena - Dva - Tri", BesedilaPravila.iskalniPar("Ena - Dva - Tri", "Kanal Nekdo"))

    println("\n== poizvedba, vir, smisel ==")
    preveriEnako("poizvedba z dolzino", "artist_name=Coldplay&track_name=Yellow+%26+Blue&duration=266",
        BesedilaPravila.poizvedba("Coldplay", "Yellow & Blue", 266))
    preveriEnako("poizvedba brez dolzine", "artist_name=A&track_name=B", BesedilaPravila.poizvedba("A", "B", 0))
    preveriEnako("vir: lrclib.net", true, BesedilaPravila.jeVirLrclib("lrclib.net"))
    preveriEnako("vir: s shemo, potjo in presledkom s tipkovnice", true, BesedilaPravila.jeVirLrclib("https://lrclib. net/docs"))
    preveriEnako("vir: podobna domena ne", false, BesedilaPravila.jeVirLrclib("lrclib.net.evil.example"))
    preveriEnako("vir: drugo ne", false, BesedilaPravila.jeVirLrclib("example.com"))
    preveriEnako("smiselno: pesem", true, BesedilaPravila.smiselno(radio = false, vZivo = false, filmAliSerija = false))
    preveriEnako("smiselno: radio ne", false, BesedilaPravila.smiselno(radio = true, vZivo = false, filmAliSerija = false))
    preveriEnako("smiselno: kanal v zivo ne", false, BesedilaPravila.smiselno(radio = false, vZivo = true, filmAliSerija = false))
    preveriEnako("smiselno: film ne", false, BesedilaPravila.smiselno(radio = false, vZivo = false, filmAliSerija = true))
    val dan = 24 * 3_600_000L
    preveriEnako("»ni besedila« velja 6 dni", true, BesedilaPravila.niBesedilaVelja(0, 6 * dan))
    preveriEnako("»ni besedila« po 7 dneh ne", false, BesedilaPravila.niBesedilaVelja(0, 7 * dan))
    preveriEnako("»ni besedila«: ura nazaj ne", false, BesedilaPravila.niBesedilaVelja(10 * dan, 0))
    preveriEnako("kljuc: velike crke ne stejejo, dolzina v isti petsekundni skupini ista", BesedilaPravila.kljuc("coldplay", "yellow", 266), BesedilaPravila.kljuc("Coldplay", "Yellow", 267))
    preveriEnako("kljuc: druga dolzina je drug posnetek", false, BesedilaPravila.kljuc("a", "b", 200) == BesedilaPravila.kljuc("a", "b", 260))

    if (napak == 0) println("BesedilaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
