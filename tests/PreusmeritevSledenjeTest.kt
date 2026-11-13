package si.safeer.tv.os

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

/**
 * Neodvisni pregled 12. 11. 2026 (S1, S2): rocno sledenje preusmeritvam. Skrivne glave (X-Safeer-Config) samo prvotnemu
 * gostitelju, piskotek in Referer samo prvemu skoku, najvec 5 skokov; zasebni naslovi (127.0.0.1, domace omrezje) niso blokirani.
 */
fun main(args: Array<String>) {
    class Odziv(val naslov: String, val koda: Int, val location: String?)
    class Skok(val naslov: String, val skrivne: Boolean, val prvi: Boolean)
    fun poganjaj(prvotni: String, strezniki: Map<String, Pair<Int, String?>>, skoki: MutableList<Skok>): Odziv =
        PreusmeritevPravila.sledi(prvotni,
            odpri = { n, skrivne, prvi -> skoki += Skok(n, skrivne, prvi); val (k, l) = strezniki[n] ?: (200 to null); Odziv(n, k, l) },
            koda = { it.koda }, location = { it.location }, zapri = { })

    // tuji gostitelj: glave ne gredo naprej, piskotek samo prvi skok
    val s1 = ArrayList<Skok>()
    val o1 = poganjaj("https://dodatek.example/manifest.json", mapOf(
        "https://dodatek.example/manifest.json" to (302 to "https://cdn.drugje.example/m.json"),
        "https://cdn.drugje.example/m.json" to (301 to "https://dodatek.example/nazaj.json")), s1)
    preveri(s1.map { it.naslov } == listOf("https://dodatek.example/manifest.json", "https://cdn.drugje.example/m.json", "https://dodatek.example/nazaj.json"), "poti $s1")
    preveri(s1.map { it.skrivne } == listOf(true, false, true), "skrivne glave samo prvotnemu gostitelju")
    preveri(s1.map { it.prvi } == listOf(true, false, false), "piskotek/Referer samo prvi skok (tudi ob vrnitvi na prvotnega gostitelja)")
    preveri(o1.koda == 200, "zadnji odgovor")

    // zasebni naslovi niso blokirani (osebni dodatki, lokalni Usenet): preusmeritev v domace omrezje in 127.0.0.1 se sledi
    val s2b = ArrayList<Skok>()
    poganjaj("http://127.0.0.1:7000/a", mapOf("http://127.0.0.1:7000/a" to (302 to "http://192.168.1.20:5076/c")), s2b)
    preveri(s2b.map { it.naslov } == listOf("http://127.0.0.1:7000/a", "http://192.168.1.20:5076/c"), "127.0.0.1 -> domace omrezje: sledi se")
    preveri(s2b.map { it.skrivne } == listOf(true, false), "zasebni drug gostitelj: brez skrivnih glav")

    // meja skokov: 1 + 5 odprtij, nato se odgovor 302 vrne klicatelju
    val s3 = ArrayList<Skok>()
    val zanka = (0..20).associate { "https://a.example/$it" to (302 to "https://a.example/${it + 1}") }
    val o3 = poganjaj("https://a.example/0", zanka, s3)
    preveri(s3.size == PreusmeritevPravila.NAJVEC_SKOKOV + 1 && PreusmeritevPravila.NAJVEC_SKOKOV == 5, "najvec 5 skokov, odprtij ${s3.size}")
    preveri(o3.koda == 302, "po meji se odgovor vrne")

    // https se ne spusti na http; neznana shema se ne sledi
    val s4 = ArrayList<Skok>()
    poganjaj("https://a.example/", mapOf("https://a.example/" to (302 to "http://a.example/x")), s4)
    preveri(s4.size == 1, "https -> http ni sledeno")

    // vir: Stremio in bajtiSlike uporabljata sledi(); instanceFollowRedirects ni vklopljen
    val os = java.io.File(args.firstOrNull() ?: ".", "si/safeer/tv/os")
    val stremio = java.io.File(os, "Stremio.kt").readText()
    val spletni = java.io.File(os, "SpletniVir.kt").readText()
    preveri("PreusmeritevPravila.sledi(prvotni," in stremio && "if (config != null && skrivne)" in stremio, "Stremio: odpriDodatek gre skozi sledi, glava samo ob skrivne")
    val slike = spletni.substringAfter("fun bajtiSlike(").substringBefore("// ---------")
    preveri("PreusmeritevPravila.sledi(naslov," in slike && "if (prvi)" in slike && "instanceFollowRedirects = false" in slike, "bajtiSlike: sledi, piskotek samo ob prvem skoku")
    println("PreusmeritevSledenjeTest: OK ($stevilo preverb)")
}
