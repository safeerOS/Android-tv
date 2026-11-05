package si.safeer.tv.cast

import java.io.File
import java.io.IOException

// A8 (pregled 5. 11. 2026, preneseno s telefona ae93970): prejeta datoteka gre v .part in se sele cela preimenuje; ime je
// varno in ostane v mapi. Na TV tudi vKoncnoIme / zasediIme (HubTokovi za gostitelja, CastReceiverService.prevzemiDatoteko).
fun main() {
    val koren = java.nio.file.Files.createTempDirectory("safeer-shrani").toFile()
    try {
        val mapa = File(koren, "prenosi").apply { mkdirs() }
        val zunaj = File(koren, "zunaj").apply { mkdirs() }
        fun shrani(ime: String, vsebina: String) = HubTokovi.shraniVMapo(mapa, ime) { it.write(vsebina.toByteArray()) }

        val a = shrani("a.txt", "ena")
        check(a.name == "a.txt" && a.readText() == "ena")
        val a1 = shrani("a.txt", "dva")
        check(a1.name == "a (1).txt" && a1.readText() == "dva" && a.readText() == "ena") { "trk imena: ${a1.name}" }
        // Pot v imenu ostane v mapi.
        for (ime in listOf("../zunaj/x.txt", "..", "/etc/passwd", "..\\\\zunaj\\\\y.txt")) {
            val f = shrani(ime, "p")
            check(f.canonicalFile.parentFile == mapa.canonicalFile) { "ime $ime je pobegnilo: $f" }
        }
        check(zunaj.listFiles()!!.isEmpty()) { "nic izven mape" }
        // Simbolna povezava s koncnim imenom (tudi brez cilja) ne odnese pisanja izven mape.
        val tarca = File(zunaj, "tarca.txt")
        java.nio.file.Files.createSymbolicLink(File(mapa, "s.txt").toPath(), tarca.toPath())
        val s = shrani("s.txt", "skrivno")
        check(!tarca.exists()) { "pisanje skozi simbolno povezavo izven mape" }
        check(s.name != "s.txt" && s.readText() == "skrivno") { "drugo ime: ${s.name}" }
        // Prekinjen prenos ali napacen odtis: ne ostane ne koncna ne .part datoteka.
        val prej = mapa.list()!!.toSet()
        try {
            HubTokovi.shraniVMapo(mapa, "prekinjeno.bin") { it.write(ByteArray(10)); throw IOException("prekinjeno") }
            check(false) { "napaka se mora razsiriti" }
        } catch (_: IOException) { }
        check(mapa.list()!!.toSet() == prej) { "ostanki: ${mapa.list()!!.toSet() - prej}" }
        check(mapa.list()!!.none { it.endsWith(".part") })

        // TV: delna datoteka (».safeer-delno«) in koncno ime po preverjenem odtisu.
        val tarca2 = File(zunaj, "tarca2.bin")
        java.nio.file.Files.createSymbolicLink(File(mapa, "v.bin.safeer-delno").toPath(), tarca2.toPath())
        val delna = HubTokovi.zasediIme(mapa.canonicalFile, "v.bin.safeer-delno")
        check(delna.name != "v.bin.safeer-delno" && delna.isFile && !java.nio.file.Files.isSymbolicLink(delna.toPath())) { "delna skozi povezavo: ${delna.name}" }
        delna.writeText("vsebina")
        java.nio.file.Files.createSymbolicLink(File(mapa, "v.bin").toPath(), tarca2.toPath())
        val koncna = HubTokovi.vKoncnoIme(delna, mapa, "v.bin")
        check(!tarca2.exists() && koncna.readText() == "vsebina" && koncna.name == "v (1).bin" && !delna.exists()) { "koncno ime: ${koncna.name}" }
        val b = File(mapa, "b.bin").apply { writeText("prva") }
        val delna2 = HubTokovi.zasediIme(mapa.canonicalFile, "b.bin.safeer-delno").apply { writeText("druga") }
        val b1 = HubTokovi.vKoncnoIme(delna2, mapa, "../b.bin")
        check(b.readText() == "prva" && b1.name == "b (1).bin" && b1.readText() == "druga") { "obstojeca datoteka prepisana: ${b1.name}" }
        println("ShraniVMapoTest: OK")
    } finally {
        koren.deleteRecursively()
    }
}
