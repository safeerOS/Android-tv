package si.safeer.tv.os

/**
 * Izbira najboljsega toka brez uporabnikovega klika (Matej, 2. 10. 2026): film se zacne takoj, seznam virov je
 * le se rocna moznost. "Najboljsi" je tisti, ki ga TA naprava res predvaja (slika in zvok) in se zacne hitro -
 * ne nujno najvecji: 4K Dolby Vision z zvokom Atmos na telefonu brez teh dekodirnikov bi bil crna slika brez zvoka.
 * Cista pravila brez Androida, da jih preverimo v JVM (tests/TokIzbiraTest.kt).
 */
object TokIzbira {
    /** Kaj naprava zmore: visina zaslona (krajsa stranica najvecjega nacina), dekodirniki slike in zvoka. */
    data class Zmoznosti(val visina: Int = 1080, val hevc: Boolean = true, val av1: Boolean = false, val hdr: Boolean = false,
                         val dolbyVision: Boolean = false, val eac3: Boolean = false, val ac3: Boolean = false,
                         val dts: Boolean = false, val truehd: Boolean = false)

    /** Kar o toku pove njegovo ime in opis (tako tokove opisujejo dodatki: "2160p WEB-DL DDP5.1 Atmos DV HDR H.265 12.66 GB"). */
    data class Opis(val visina: Int, val hevc: Boolean, val av1: Boolean, val hdr: Boolean, val dv: Boolean,
                    val zvok: String, val slabPosnetek: Boolean, val gb: Double)

    private val V2160 = Regex("2160p?|\\b4k\\b|\\buhd\\b")
    private val V1440 = Regex("1440p")
    private val V1080 = Regex("1080[pi]?|\\bfhd\\b|full[ .-]?hd")
    private val V720 = Regex("720p?")
    private val V480 = Regex("480p?|576p?|\\bsd\\b|dvdrip")
    private val V360 = Regex("360p?|240p?")
    private val HEVC = Regex("x265|h[ .]?265|hevc")
    private val AV1 = Regex("\\bav1\\b")
    private val DV = Regex("\\bdv\\b|dolby[ .]?vision|\\bdovi\\b")
    private val HDR = Regex("hdr")
    private val TRUEHD = Regex("true[ .-]?hd")
    private val DTS = Regex("\\bdts")
    private val EAC3 = Regex("\\bddp|dd\\+|e-?ac-?3|atmos")
    private val AC3 = Regex("\\bdd[ .]?[257]|\\bac-?3\\b|dolby digital")
    private val SLAB = Regex("\\b(cam|hdcam|camrip|ts|hdts|telesync|tc|telecine|scr|screener)\\b")
    private val VELIKOST = Regex("(\\d+(?:[.,]\\d+)?)\\s*(gb|gib|mb|mib)")
    private val SEJALCI = Regex("(?:\uD83D\uDC64|seed(?:er)?s?\\s*[:=]?)\\s*(\\d{1,6})")

    /** Koliko sejalcev navaja opis torrenta ("👤 123", "Seeders: 12"); -1 = opis tega ne pove. */
    fun sejalci(besedilo: String): Int = SEJALCI.find(besedilo.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: -1

    fun opisi(besedilo: String): Opis {
        val t = besedilo.lowercase()
        val visina = when {
            V2160.containsMatchIn(t) -> 2160; V1440.containsMatchIn(t) -> 1440; V1080.containsMatchIn(t) -> 1080
            V720.containsMatchIn(t) -> 720; V480.containsMatchIn(t) -> 480; V360.containsMatchIn(t) -> 360; else -> 0
        }
        val zvok = when { TRUEHD.containsMatchIn(t) -> "truehd"; DTS.containsMatchIn(t) -> "dts"; EAC3.containsMatchIn(t) -> "eac3"
            AC3.containsMatchIn(t) -> "ac3"; else -> "" }
        val gb = VELIKOST.find(t)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            if (m.groupValues[2].startsWith("m")) n / 1024.0 else n
        } ?: 0.0
        return Opis(visina, HEVC.containsMatchIn(t), AV1.containsMatchIn(t), HDR.containsMatchIn(t), DV.containsMatchIn(t), zvok, SLAB.containsMatchIn(t), gb)
    }

    /** Vecja ocena = boljsi tok za to napravo. */
    fun ocena(besedilo: String, z: Zmoznosti): Int {
        val o = opisi(besedilo)
        val v = if (o.visina == 0) 700 else o.visina          // neznana locljivost: med 720p in 480p
        // Do locljivosti zaslona je vec boljse; nad njo le vecja datoteka brez koristi (se vedno predvajljivo).
        var tocke = if (v <= z.visina) v else z.visina - (v - z.visina) / 4
        if (o.hevc && !z.hevc) tocke -= 3000
        if (o.av1 && !z.av1) tocke -= 3000
        // Dolby Vision brez sloja HDR na napravi brez DV: napacne barve. Z rezervnim slojem HDR gre.
        if (o.dv && !z.dolbyVision) tocke -= if (o.hdr) 60 else 900
        if (o.hdr && !z.hdr) tocke -= 150
        // Zvok, ki ga naprava ne dekodira, pomeni film brez zvoka: huje kot nizja locljivost.
        val zvokGre = when (o.zvok) { "truehd" -> z.truehd; "dts" -> z.dts; "eac3" -> z.eac3; "ac3" -> z.ac3; else -> true }
        if (!zvokGre) tocke -= 1200
        if (o.slabPosnetek) tocke -= 800
        // Med enakovrednimi se manjsa datoteka zacne hitreje (nic cakanja).
        tocke -= (minOf(o.gb, 40.0) * 4).toInt()
        // Torrent: zacetek predvajanja doloca roj, ne naprava (izmerjeno 3. 10. 2026 - dobro podprt torrent stece v
        // nekaj sekundah, slabo podprt po minuti ali nikoli). Kjer opis pove stevilo sejalcev, ima podprt torrent
        // prednost tudi pred eno stopnjo visjo locljivostjo; brez sejalcev je zadnji.
        when (val s = sejalci(besedilo)) {
            -1 -> { }
            0 -> tocke -= 2500
            in 1..4 -> tocke -= 600
            in 5..19 -> tocke -= 150
            else -> tocke += minOf(60, s / 20)
        }
        return tocke
    }

    /** Tokovi od najboljsega do najslabsega za to napravo; pri enaki oceni ostane vrstni red dodatka. */
    fun <T> uredi(tokovi: List<T>, besedilo: (T) -> String, z: Zmoznosti): List<T> =
        tokovi.withIndex().sortedWith(compareByDescending<IndexedValue<T>> { ocena(besedilo(it.value), z) }.thenBy { it.index }).map { it.value }
}
