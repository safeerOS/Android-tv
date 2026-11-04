package si.safeer.tv.scit

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

// Nabor Scita se gradi samo ob spremembi seznamov (raziskava 4. 11. 2026): tests/run_scit_tests.sh.
// Brez preverbe kljuca (gradnja ob vsakem zagonu) ta preizkus pade.
fun main() {
    val mapa = Files.createTempDirectory("safeer-scit-vir").toFile()
    try {
        val cilj = File(mapa, "domene.bin")
        var prebranih = 0
        fun seznami(vec: Boolean = false) = sequenceOf(
            sequenceOf("ads.example.com", "tracker.net").map { prebranih++; it },
            (if (vec) sequenceOf("phish.si", "novo.example") else sequenceOf("phish.si")).map { prebranih++; it },
        )
        val k1 = DomenskiNabor.kljucVira(listOf("hagezi-pro" to "a".repeat(64), "si-cert" to "b".repeat(64)))!!
        val k2 = DomenskiNabor.kljucVira(listOf("hagezi-pro" to "a".repeat(64), "si-cert" to "c".repeat(64)))!!
        preveri(k1 != k2 && k1.length == 64, "kljuc sledi vsoti seznama")
        preveri(DomenskiNabor.kljucVira(listOf("x" to "")) == null && DomenskiNabor.kljucVira(emptyList()) == null, "brez vsote ni kljuca")
        preveri(DomenskiNabor.kljucVira(listOf("a" to "1", "b" to "2")) != DomenskiNabor.kljucVira(listOf("b" to "2", "a" to "1")), "vrstni red")

        // prva gradnja
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k1, seznami()) == 3, "prva gradnja")
        val p = DomenskiNabor.Poizvedba.odpri(cilj) ?: throw AssertionError("format, ki ga bere ScitStoritev")
        preveri(p.stevilo == 3 && p.jeBlokirana("cdn.ads.example.com") && p.jeBlokirana("phish.si") && !p.jeBlokirana("example.com"), "iskanje")
        preveri(DomenskiNabor.steviloVGlavi(cilj) == 3 && cilj.length() == DomenskiNabor.GLAVA + 3L * 8, "glava in dolzina")

        // nespremenjen vhod: ni gradnje, seznami se sploh ne preberejo, datoteka ostane ista
        prebranih = 0
        cilj.setLastModified(1_000_000_000_000L)
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k1, seznami()) == -1, "nespremenjen vhod se ne gradi")
        preveri(prebranih == 0 && cilj.lastModified() == 1_000_000_000_000L, "brez branja in pisanja ($prebranih)")

        // spremenjen vhod: gradnja
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k2, seznami(vec = true)) == 4, "spremenjen vhod se gradi")
        preveri(DomenskiNabor.Poizvedba.odpri(cilj)!!.jeBlokirana("novo.example"), "nov vnos")
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k2, seznami(vec = true)) == -1, "nato spet brez gradnje")

        // brez vsote (neznan vhod) vedno gradi; po tem ni zapisanega kljuca
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, null, seznami()) == 3, "brez kljuca vedno gradi")
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k1, seznami()) == 3, "po gradnji brez kljuca ni zapisanega kljuca")

        // prekinjen zapis ali manjkajoca datoteka z istim kljucem: gradi znova
        cilj.writeBytes(cilj.readBytes().copyOf(DomenskiNabor.GLAVA + 8))
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k1, seznami()) == 3, "pokvarjena datoteka se zgradi znova")
        cilj.delete()
        preveri(DomenskiNabor.zapisiCeSpremenjeno(cilj, k1, seznami()) == 3, "manjkajoca datoteka se zgradi znova")

        // brez ostankov zacasnih datotek
        val ostanki = mapa.listFiles()!!.map { it.name }.sorted()
        preveri(ostanki == listOf("domene.bin", "domene.bin.vir"), "datoteke: $ostanki")

        // pretocni zapis da isti format kot prej (glava + urejene zgoscenke BE)
        val b = java.nio.ByteBuffer.wrap(cilj.readBytes())
        val m = ByteArray(5); b.get(m)
        preveri(String(m, Charsets.US_ASCII) == "SCIT1" && b.getInt() == 3, "glava SCIT1")
        val z = LongArray(3) { b.getLong() }
        preveri(z.toList() == listOf("ads.example.com", "tracker.net", "phish.si").map { DomenskiNabor.zgoscenka(it) }.sorted(), "urejene zgoscenke")
        println("DomenskiNaborVir: OK")
    } finally {
        mapa.deleteRecursively()
    }
    exitProcess(0)
}
