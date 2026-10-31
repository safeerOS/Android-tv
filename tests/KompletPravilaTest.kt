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
    
    // vnosi
    val shaDober = "a".repeat(64)
    val vnosDober = JSONObject().put("url", "https://test.si/app.apk").put("sha256", shaDober)
    val vnosDober2 = JSONObject().put("url", "https://test.si/app2.apk").put("sha256", shaDober)
    val manifest = JSONObject().put("android.si.safeer.tv", vnosDober).put("android.si.safeer.trgovina", vnosDober2)
    val paketi = listOf("si.safeer.tv", "si.safeer.trgovina")
    
    val vrnjeno = KompletPravila.vnosi(manifest, paketi)
    preveri("vrnjeno ni null", vrnjeno != null)
    preveriEnako("oba vnosa prisotna", 2, vrnjeno?.size)
    preveriEnako("url prvi", "https://test.si/app.apk", vrnjeno?.get("si.safeer.tv")?.url)
    preveriEnako("url drugi", "https://test.si/app2.apk", vrnjeno?.get("si.safeer.trgovina")?.url)
    
    val mankjaTrgovina = JSONObject().put("android.si.safeer.tv", vnosDober)
    preveriEnako("manifest brez vnosa za si.safeer.trgovina -> null", null, KompletPravila.vnosi(mankjaTrgovina, paketi))
    
    val slabUrl = JSONObject().put("android.si.safeer.tv", vnosDober).put("android.si.safeer.trgovina", JSONObject().put("url", "http://test.si/app.apk").put("sha256", shaDober))
    preveriEnako("url http:// -> null", null, KompletPravila.vnosi(slabUrl, paketi))
    
    val slabSha = JSONObject().put("android.si.safeer.tv", vnosDober).put("android.si.safeer.trgovina", JSONObject().put("url", "https://test.si/app.apk").put("sha256", "prekratek"))
    preveriEnako("sha256 prekratek -> null", null, KompletPravila.vnosi(slabSha, paketi))
    
    if (napak == 0) {
        println("KompletPravilaTest: OK")
    } else {
        println("KompletPravilaTest: NAPAK = $napak")
        exitProcess(1)
    }
}
