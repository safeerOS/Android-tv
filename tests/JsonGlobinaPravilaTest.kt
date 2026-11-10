package si.safeer.tv.os

var stPreverb = 0

private fun preveri(pogoj: Boolean, sporocilo: String) {
    check(pogoj) { sporocilo }
    stPreverb++
}

fun main() {
    // prazno besedilo = 0
    preveri(JsonGlobinaPravila.globina("") == 0, "Prazno besedilo mora imeti globino 0")

    // {} = 1
    preveri(JsonGlobinaPravila.globina("{}") == 1, "{} mora imeti globino 1")

    // {"a":[1,{"b":2}]} = 3
    preveri(JsonGlobinaPravila.globina("{\"a\":[1,{\"b\":2}]}") == 3, "Gnezdeni json mora imeti globino 3")

    // zaklepaji v nizu {"a":"[[[{{{"} = 1
    preveri(JsonGlobinaPravila.globina("{\"a\":\"[[[{{{\"}") == 1, "Zaklepaji v nizu se ne smejo steti")

    // ubezan narekovaj {"a":"x\"[[","b":[1]} = 2
    preveri(JsonGlobinaPravila.globina("{\"a\":\"x\\\"[[\",\"b\":[1]}") == 2, "Ubezan narekovaj v nizu mora biti upostevan")

    // ubezana posevnica pred koncem niza {"a":"x\\","b":[[1]]} = 3
    preveri(JsonGlobinaPravila.globina("{\"a\":\"x\\\\\",\"b\":[[1]]}") == 3, "Ubezana posevnica mora biti upostevana")

    // 100000 x [ -> pregloboko = true
    val dolgNizBuilder = StringBuilder()
    for (i in 0 until 100000) {
        dolgNizBuilder.append("[")
    }
    val dolgNiz = dolgNizBuilder.toString()
    
    val zacetek = System.currentTimeMillis()
    val jePregloboko = JsonGlobinaPravila.pregloboko(dolgNiz, 64)
    val trajanje = System.currentTimeMillis() - zacetek
    preveri(jePregloboko, "100000 oklepajev mora biti pregloboko")
    preveri(trajanje < 50, "Preverjanje pregloboko mora biti hitro (< 50ms), bilo je \$trajanje ms")

    // 64 x [ + 64 x ] -> pregloboko = false
    val mejaBuilder = StringBuilder()
    for (i in 0 until 64) mejaBuilder.append("[")
    for (i in 0 until 64) mejaBuilder.append("]")
    preveri(!JsonGlobinaPravila.pregloboko(mejaBuilder.toString(), 64), "64 gnezdenj ne sme biti pregloboko")

    // 65 -> true
    val cezMejoBuilder = StringBuilder()
    for (i in 0 until 65) cezMejoBuilder.append("[")
    for (i in 0 until 65) cezMejoBuilder.append("]")
    preveri(JsonGlobinaPravila.pregloboko(cezMejoBuilder.toString(), 64), "65 gnezdenj mora biti pregloboko")

    // ]]]]{} -> globina 1
    preveri(JsonGlobinaPravila.globina("]]]]{}") == 1, "Neuravnotezeni zaklepaji ne smejo dati negativne globine")

    println("JsonGlobinaPravilaTest: OK (11 preverb)")
}
