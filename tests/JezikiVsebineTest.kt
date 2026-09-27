package si.safeer.tv.os

private data class JezikovniVnos(val naslov: String, val jezik: String)

fun main() {
    check(JezikiVsebine.oznaka("English") == "en") { "Ime jezika se mora pretvoriti v ISO oznako" }
    check(JezikiVsebine.oznaka("fra") == "fr") { "Tricrkovna ISO oznaka se mora poenotiti" }
    val vnosi = listOf(
        JezikovniVnos("English", "English"),
        JezikovniVnos("Français", "fra"),
        JezikovniVnos("Brez oznake", ""),
    )
    val vidni = JezikiVsebine.filtriraj(vnosi, setOf("fr")) { it.jezik }
    check(vidni.map { it.naslov } == listOf("English", "Brez oznake")) {
        "Izklopljen jezik mora izginiti, vnos brez jezika pa ostati: $vidni"
    }
    println("OK Jezikovni filter: vnos brez jezika ostane viden")
}
