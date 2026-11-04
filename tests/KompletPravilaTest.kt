import org.json.JSONObject
import si.safeer.tv.os.KompletPravila
import kotlin.system.exitProcess

var napak = 0

fun preveri(opis: String, uspeh: Boolean) {
    if (uspeh) {
        println("  OK   $opis")
    } else {
        println("  NAPAKA $opis")
        napak++
    }
}

fun <T> preveriEnako(opis: String, pricakovano: T, dobljeno: T) {
    if (pricakovano == dobljeno) {
        println("  OK   $opis (pricakovano=$pricakovano, dobljeno=$dobljeno)")
    } else {
        println("  NAPAKA $opis (pricakovano=$pricakovano, dobljeno=$dobljeno)")
        napak++
    }
}

fun main() {
    println("Preizkus pravil kompleta")
    
    // manjkajoci
    val vsa = setOf("si.safeer.tv", "si.safeer.trgovina")
    preveriEnako("TV os brez obeh", listOf("si.safeer.tv", "si.safeer.trgovina"), KompletPravila.manjkajoci(true, true, emptySet()))
    preveriEnako("TV os z enim (brskalnik)", listOf("si.safeer.trgovina"), KompletPravila.manjkajoci(true, true, setOf("si.safeer.tv")))
    preveriEnako("TV os z enim (trgovina)", listOf("si.safeer.tv"), KompletPravila.manjkajoci(true, true, setOf("si.safeer.trgovina")))
    preveriEnako("TV os z obema", emptyList<String>(), KompletPravila.manjkajoci(true, true, vsa))
    preveriEnako("telefon brez obeh", emptyList<String>(), KompletPravila.manjkajoci(false, true, emptySet()))
    preveriEnako("brskalnik na TV", emptyList<String>(), KompletPravila.manjkajoci(true, false, emptySet()))
    
    // vnosi - tedenski pregled 4. 11. 2026 (S2): prava oblika razlicice.json (gnezdena, kot Posodobitve.preveri).
    // Vzorec: https://safeer.si/os/razlicice.json 4. 11. 2026 (polje "novo" izpusceno), si.safeer.trgovina ga ni.
    // tests/stubs/JSONObject.kt ne razclenjuje besedila: vzorec je zgrajen s put() v isti gnezdeni obliki.
    val pravi = JSONObject().put("generirano", "2026-11-04").put("linux", JSONObject()).put("android", JSONObject()
        .put("si.safeer.os", JSONObject().put("razlicica", "0.5.110").put("koda", 135)
            .put("url", "https://safeer.si/os/safeer-os-tv-0.5.110.apk")
            .put("sha256", "47b8be13cff001ed86e0fee85966ab9b47704b654d13e0ac1f4dc5193997eb73").put("velikost", 23113242))
        .put("si.safeer.tv", JSONObject().put("razlicica", "2.1.232").put("koda", 341)
            .put("url", "https://safeer.si/browser/assets/tv/safeer-browser-tv-2.1.232.apk")
            .put("sha256", "de1916b07b3da0300c52620cfb2581887c83854fa29320c24444e59cc54dd587").put("velikost", 11476298)))
    val paketi = listOf("si.safeer.tv", "si.safeer.trgovina")
    val vrnjeno = KompletPravila.vnosi(pravi, paketi)
    preveri("pravi razlicice.json: ni null", vrnjeno != null)
    preveriEnako("TV Browser najden v gnezdeni obliki", "https://safeer.si/browser/assets/tv/safeer-browser-tv-2.1.232.apk", vrnjeno?.get("si.safeer.tv")?.url)
    preveriEnako("sha256 TV Browserja", "de1916b07b3da0300c52620cfb2581887c83854fa29320c24444e59cc54dd587", vrnjeno?.get("si.safeer.tv")?.sha256)
    preveriEnako("trgovine v razlicice.json ni - ne ponudimo", setOf("si.safeer.tv"), vrnjeno?.keys)

    val shaDober = "a".repeat(64)
    fun manifest(vararg vnosi: Pair<String, JSONObject>) = JSONObject().put("android", JSONObject().apply { vnosi.forEach { put(it.first, it.second) } })
    val vnosDober = JSONObject().put("url", "https://test.si/app.apk").put("sha256", shaDober)
    val oba = KompletPravila.vnosi(manifest("si.safeer.tv" to vnosDober,
        "si.safeer.trgovina" to JSONObject().put("url", "https://test.si/app2.apk").put("sha256", shaDober)), paketi)
    preveriEnako("oba vnosa prisotna", 2, oba?.size)
    preveriEnako("url drugi", "https://test.si/app2.apk", oba?.get("si.safeer.trgovina")?.url)
    preveriEnako("ploski kljuc (stara napacna oblika) ni vnos", emptyMap<String, KompletPravila.Vnos>(),
        KompletPravila.vnosi(JSONObject().put("android.si.safeer.tv", vnosDober), paketi))
    preveriEnako("url http:// -> null", null, KompletPravila.vnosi(manifest("si.safeer.tv" to vnosDober,
        "si.safeer.trgovina" to JSONObject().put("url", "http://test.si/app.apk").put("sha256", shaDober)), paketi))
    preveriEnako("sha256 prekratek -> null", null, KompletPravila.vnosi(manifest("si.safeer.tv" to
        JSONObject().put("url", "https://test.si/app.apk").put("sha256", "prekratek")), paketi))

    if (napak == 0) {
        println("KompletPravilaTest: OK")
    } else {
        println("KompletPravilaTest: NAPAK = $napak")
        exitProcess(1)
    }
}
