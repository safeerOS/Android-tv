package si.safeer.tv.os

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

/** Neodvisni pregled 12. 11. 2026 (N5): glave toka enega dodatka ne smejo veljati za tok drugega dodatka na istem gostitelju. */
fun main(args: Array<String>) {
    val G = GlaveTokaPravila
    val a = mapOf("Cookie" to "seja-dodatka-A")
    val b = mapOf("Cookie" to "seja-dodatka-B")
    preveri(G.zdruzi(null, a) == a, "prvi tok z glavami: glave gostitelja")
    preveri(G.zdruzi(a, a) == a, "isti dodatek (enake glave): ostanejo")
    preveri(G.zdruzi(a, b) == null, "drug dodatek na istem gostitelju: glave A ne veljajo ne za B ne obratno")
    preveri(G.zdruzi(a, emptyMap()) == null, "tok brez glav odstrani glave gostitelja")
    preveri(G.zdruzi(null, emptyMap()) == null, "brez glav: ni nic")
    preveri(!G.gostiteljSme(true) && G.gostiteljSme(false), "tok z lastnimi registriranimi glavami ne podeduje glav gostitelja")
    // Vir: SpletniVir res uporablja pravilo (zapomniGlaveToka in glaveZa)
    val vir = java.io.File(args.firstOrNull() ?: ".", "si/safeer/tv/os/SpletniVir.kt").readText()
    preveri("GlaveTokaPravila.zdruzi(glaveGostitelja[g], glave)" in vir, "zapomniGlaveToka gre skozi GlaveTokaPravila.zdruzi")
    preveri("GlaveTokaPravila.gostiteljSme(glaveToka.containsKey(url))" in vir, "glaveZa gre skozi GlaveTokaPravila.gostiteljSme")
    println("GlaveTokaPravilaTest: OK ($stevilo preverb)")
}
