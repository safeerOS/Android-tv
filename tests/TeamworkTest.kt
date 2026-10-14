package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private var napak = 0

private fun preveri(kaj: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $kaj") else { println("FAIL   $kaj"); napak++ }
}

fun main() {
    val p = TeamworkPravila
    println("== Teamwork na Androidu (isti protokol kot core/link_teamwork.py) ==")
    val g = p.glavaCelote(5, "a".repeat(64), "b".repeat(64))
    preveri("glava se zacne z MAGIJA", g.copyOfRange(0, 6).contentEquals("SFTW1\n".toByteArray()))
    val n = ((g[6].toInt() and 0xff) shl 24) or ((g[7].toInt() and 0xff) shl 16) or ((g[8].toInt() and 0xff) shl 8) or (g[9].toInt() and 0xff)
    preveri("dolzina glave", n == g.size - 10)
    val j = JSONObject(String(g, 10, n, Charsets.UTF_8))
    preveri("celota z velikostjo, sha256 in osnovo", j.getBoolean("celota") && j.getLong("velikost") == 5L && j.getString("osnova") == "b".repeat(64))
    preveri("naslov popravka", p.naslovPopravka("https://h:1/", "share:0:a b.txt") { it.replace(" ", "%20") } == "https://h:1/popravek/share:0:a%20b.txt")

    val f = File.createTempFile("teamwork", ".txt"); f.writeText("abc")
    preveri("sha256", p.sha256(f) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    f.delete()

    fun d(tw: Boolean, sha: String, vararg drugi: String) = JSONObject().put("teamwork", tw).put("sha256", sha).put("drugi", JSONArray(drugi.toList()))
    var o = p.odziv(d(true, "s1", "@tukaj"), false, "s1", true, false, "Safeer Control (racunalnik)")
    preveri("zacetek Teamworka, »@tukaj« je naprava lastnika", o.obvestilo == "zacetek" && o.drugi == listOf("racunalnik"))
    o = p.odziv(d(true, "s1", "Safeer OS (OnePlus)"), true, "s1", true, false, "x")
    preveri("ze vklopljen: brez obvestila, kratko ime", o.obvestilo == "" && o.drugi == listOf("OnePlus"))
    o = p.odziv(d(false, "s1"), true, "s1", true, false, "x")
    preveri("konec Teamworka", o.obvestilo == "konec")
    o = p.odziv(d(false, "s2"), false, "s1", true, false, "x")
    preveri("nova razlicica pri lastniku", o.novaRazlicica && o.obvestilo == "nova")
    o = p.odziv(d(false, "s2"), false, "s1", true, true, "x")
    preveri("lokalna neposlana sprememba: brez prenosa (spor resi lastnik)", !o.novaRazlicica)
    o = p.odziv(d(true, "s1"), false, "s1", false, false, "x")
    preveri("zaprta datoteka: ni Teamworka", !o.teamwork && o.obvestilo == "")
    preveri("varno ime", p.varnoIme("../.skrito/a") == "_._.skrito_a" && p.varnoIme("..") == "_." && p.varnoIme("  ") == "datoteka")

    preveri("isto ime iz razlicnih map: razlicni podmapi", p.mapaZaId("share:0:a/zapiski.txt") != p.mapaZaId("share:0:b/zapiski.txt"))
    preveri("podmapa je kratka in stalna", p.mapaZaId("x").length == 16 && p.mapaZaId("x") == p.mapaZaId("x"))

    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
