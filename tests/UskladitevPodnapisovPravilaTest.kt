package si.safeer.tv.os

import kotlin.math.abs
import kotlin.math.roundToLong
import si.safeer.tv.os.UskladitevPodnapisovPravila.Izid
import si.safeer.tv.os.UskladitevPodnapisovPravila.Kandidat
import si.safeer.tv.os.UskladitevPodnapisovPravila.Napis

private val U = UskladitevPodnapisovPravila

private fun napisi(s: String): List<Napis> = s.split(';').filter { it.isNotBlank() }.map {
    val (a, b) = it.split(','); Napis(a.toLong(), b.toLong())
}

/** Napake (ms) za napise vira, ki imajo v resnici par (zacetek v 1 s): |nov zacetek - resnica|. */
private fun napake(vir: List<Napis>, iz: Izid?, resnica: List<Pair<Long, Long>>): List<Long> {
    val out = ArrayList<Long>()
    for ((i, n) in vir.withIndex()) {
        val p = resnica.minByOrNull { abs(it.first - n.zacetekMs) } ?: continue
        if (abs(p.first - n.zacetekMs) >= 1000) continue
        val nov = iz?.zacetek(i, n.zacetekMs) ?: n.zacetekMs
        out.add(abs(nov - p.second))
    }
    return out.sorted()
}

private fun kvantil(l: List<Long>, q: Double) = l[(l.size * q).toInt().coerceAtMost(l.size - 1)]

fun main() {
    val sl = napisi(CASI_SL_DSR)
    val ref = napisi(CASI_EN_BLURAY)
    val e03 = napisi(CASI_EN_E03)
    val resnica = napisi(CASI_RESNICA).map { it.zacetekMs to it.konecMs }
    check(sl.size == 472 && ref.size == 462 && e03.size == 523 && resnica.size == 379)

    // 1) Pravi primer 14. 11. 2026: slovenski DSR na videu BluRay. Brez poravnave mediana > 10 s, s poravnavo < 0,3 s.
    val brez = napake(sl, null, resnica)
    check(kvantil(brez, 0.5) > 10_000) { "izhodisce: mediana ${kvantil(brez, 0.5)} ms" }
    val t0 = System.nanoTime()
    val iz = U.poravnaj(sl, ref)
    val ms = (System.nanoTime() - t0) / 1_000_000
    val z = napake(sl, iz, resnica)
    println("DSR->BluRay: ${z.size} preverjenih, mediana ${kvantil(z, 0.5)} ms, p90 ${kvantil(z, 0.9)} ms, max ${z.last()} ms, " +
        "pokritost ${"%.2f".format(iz!!.pokritostPrej)} -> ${"%.2f".format(iz.pokritostPo)}, odsekov ${iz.odsekov}, $ms ms")
    check(z.size >= 350)
    check(kvantil(z, 0.5) <= 300) { "mediana ${kvantil(z, 0.5)}" }
    check(kvantil(z, 0.9) <= 800) { "p90 ${kvantil(z, 0.9)}" }
    check(z.last() <= 5000) { "brez neresnicnih skokov: max ${z.last()}" }
    check(U.sprejmi(iz, sl.size)) { "pravi par mora biti sprejet" }
    // Televizijska izdaja je pospesena na 25 fps: vecina napisov dobi razmerje okoli 25/23,976 (25/24 je 0,1 % manj).
    check(iz.razmerje.count { it > 1.04 } > sl.size / 2) { "razmerje 25 fps" }
    // Merilo casa (JVM, po ogrevanju): 3 ponovitve.
    val casi = (1..3).map { val t = System.nanoTime(); U.poravnaj(sl, ref); (System.nanoTime() - t) / 1_000_000 }
    println("cas poravnave (ms, 3 ponovitve): $casi")

    // 2) Napacna epizoda kot referenca: poravnave ne uporabimo.
    val napacna = U.poravnaj(sl, e03)
    check(!U.sprejmi(napacna, sl.size)) { "napacna epizoda: pokritost ${napacna?.pokritostPo} odsekov ${napacna?.odsekov}" }

    // 3) Ze usklajen podnapis (ista izdaja, majhen trepet): ne spreminjamo.
    val trepet = ref.mapIndexed { i, n -> val d = (i % 5 - 2) * 40L; Napis(n.zacetekMs + d, n.konecMs + d) }
    check(!U.sprejmi(U.poravnaj(trepet, ref), trepet.size)) { "ze usklajen ostane" }

    // 4) Sinteticno: druga izdaja s 25 fps in 5 s zacetka -> poravnano na 0,2 s natancno.
    val r = 23.976 / 25.0
    val pocasi = ref.map { Napis((it.zacetekMs * r).roundToLong() + 5000, (it.konecMs * r).roundToLong() + 5000) }
    val izS = U.poravnaj(pocasi, ref)!!
    check(U.sprejmi(izS, pocasi.size))
    val napS = pocasi.indices.map { abs(izS.zacetek(it, pocasi[it].zacetekMs) - ref[it].zacetekMs) }.sorted()
    check(kvantil(napS, 0.9) <= 200) { "sinteticno p90 ${kvantil(napS, 0.9)}" }

    // Meje sprejema posamezno: premajhna pokritost (napacna epizoda 0,58-0,63), premajhno izboljsanje, prevec odsekov.
    fun izid(prej: Double, po: Double, odsekov: Int) = Izid(DoubleArray(0), LongArray(0), prej, po, odsekov)
    check(!U.sprejmi(izid(0.30, 0.65, 1), 400)) { "pokritost pod 0,68" }
    check(U.sprejmi(izid(0.30, 0.70, 1), 400))
    check(!U.sprejmi(izid(0.88, 0.90, 1), 400)) { "izboljsanje pod 0,05" }
    check(!U.sprejmi(izid(0.30, 0.90, 22), 400) && U.sprejmi(izid(0.30, 0.90, 21), 400)) { "najvec 1 + n/20 odsekov" }

    // 5) Premalo napisov: brez poravnave.
    check(U.poravnaj(sl.take(5), ref) == null && !U.sprejmi(null, 5))

    // 6) Izbira izdaje: video HD, slovenski podnapis le DSR -> referenca je angleski BluRay (ne DVDRip, ne DSR).
    val slDsr = Kandidat("https://a/sl", "sl", "primer.serija.s01e02.dsr.xvid-alfa")
    val kand = listOf(
        Kandidat("https://a/en-dvd", "en", "Primer.Serija.S01.dvdrip.xvid-BETA"),
        Kandidat("https://a/en-dsr", "en", "Primer.Serija.S01E02.DSR.XviD-ALFA"),
        slDsr,
        Kandidat("https://a/en-br", "en", "Primer.Serija.S01.720p.BluRay.X264-GAMA"),
    )
    check(U.referenca(kand, slDsr, null, 1080)?.url == "https://a/en-br")
    // Video SD (576 vrstic): nic ni bistveno boljse - brez poravnave.
    check(U.referenca(kand, slDsr, null, 576) == null)
    // Odtis (ujemanje) ali enako ime datoteke: poravnava ni potrebna.
    check(U.referenca(kand, Kandidat("https://a/sl2", "sl", "", ujemanje = true), null, 1080) == null)
    check(U.tocke(slDsr, "Primer.Serija.S01E02.DSR.XviD-ALFA.avi", 1080) == 90)
    // Ime datoteke iz iste skupine in vira: BluRay iste skupine dobi vec kot WEB druge skupine.
    val ime = "Primer.Serija.S01E02.720p.BluRay.x264-GAMA.mkv"
    check(U.tocke(kand[3], ime, 720) > U.tocke(Kandidat("u", "en", "Primer.Serija.S01E02.1080p.WEB-DL-DELTA"), ime, 720))
    // Izbira v jeziku: boljsa izdaja ima prednost, ob enakih tockah prva po dodatku.
    val slBr = Kandidat("https://a/sl-br", "sl", "Primer.Serija.S01E02.1080p.BluRay")
    check(U.najboljsi(listOf(slDsr, slBr), "sl", null, 1080)?.url == "https://a/sl-br")
    check(U.najboljsi(listOf(slDsr, Kandidat("https://a/sl-x", "sl", "")), "sl", null, 576)?.url == "https://a/sl")
    check(U.najboljsi(kand, "de", null, 1080) == null)
    // Vezava (Android razredov preizkus JVM ne more pognati). 14. 11. 2026: iskanje podnapisov ob ZACETKU predvajanja je
    // jemalo prvega v jeziku po dodatku in reference ni pripravilo - podnapis iz druge izdaje je sel v tok neusklajen, pot
    // samodejnih podnapisov pa ga nato ni vec zamenjala (sled v jeziku ze obstaja).
    val izvor = System.getProperty("izvor.os") ?: error("manjka -Dizvor.os (mapa src/main/kotlin/si/safeer/tv/os)")
    val g = java.io.File(izvor, "GlasbaActivity.kt").readText()
    val zacetek = g.substringAfter("fun poisciPodnapise(t: Stremio.Tok) {", "").substringBefore("fun odpri(t: Stremio.Tok")
    check(zacetek.isNotEmpty()) { "iskanje podnapisov ob zacetku ni najdeno v GlasbaActivity.kt" }
    check("Podnapisi.najboljsi(najdeni, jezik, t.imeDatoteke, visina)" in zacetek) { "ob zacetku: izbira izdaje po pravilu" }
    check("Podnapisi.pripraviUskladitev(izbran, odgovor, t.imeDatoteke, visina)" in zacetek) { "ob zacetku: referenca za uskladitev" }
    // Izbira je objavljena pred pripravo reference (zagon po roku jo vzame), konec iskanja sele za njo.
    val objava = zacetek.indexOf("najdenPodnapis = izbran")
    val referenca = zacetek.indexOf("Podnapisi.pripraviUskladitev(")
    check(objava in 0 until referenca) { "ob zacetku: izbira objavljena pred referenco" }
    check(referenca < zacetek.indexOf("podnapisIskanjeKonec = true", objava)) { "ob zacetku: konec iskanja sele po referenci" }
    // »Prvi v jeziku po dodatku« ni vec izbira nikjer, kjer podnapise iscemo pri dodatkih.
    for (dat in listOf("GlasbaActivity.kt", "PredvajanjeActivity.kt")) {
        val v = java.io.File(izvor, dat).readText()
        check("Stremio.podnapisi(" in v) { dat }
        check(".firstOrNull { it.jezik == jezik }" !in v) { "$dat: prvi v jeziku po dodatku" }
    }
    println("UskladitevPodnapisovPravilaTest OK")
}
