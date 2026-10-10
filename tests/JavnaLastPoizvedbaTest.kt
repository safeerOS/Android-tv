package si.safeer.tv.os

fun main() {
    val p = JavnaLastPoizvedba
    // Brez izbire: ista poizvedba kot prej.
    check(p.q() == p.OSNOVA)
    // Jezik: koda ISO 639-2 in angleško ime (tako ju ima Internet Archive).
    check(p.imenaJezika("en") == listOf("eng", "English")) { p.imenaJezika("en").toString() }
    check(p.q(jezik = "en").endsWith(" AND language:(\"eng\" OR \"English\")"))
    check(p.imenaJezika("de") == listOf("deu", "German"))
    // Neveljaven jezik: brez pogoja.
    check(p.q(jezik = "xyz") == p.OSNOVA && p.q(jezik = "E1") == p.OSNOVA)
    // Zvrst: vsa imena zvrsti, brez posebnih znakov.
    check(p.q(predmeti = listOf("Comedy")).endsWith(" AND subject:(\"Comedy\")"))
    check(p.q(predmeti = listOf("Sci-Fi", "Science Fiction")).endsWith(" AND subject:(\"Sci-Fi\" OR \"Science Fiction\")"))
    check(p.q(predmeti = listOf("a\"b)")).endsWith("subject:(\"ab\")"))
    // Vse skupaj, iskanje brez posebnih znakov.
    check(p.q("His Girl: Friday!", "en", listOf("Comedy")) == p.OSNOVA + " AND title:(His Girl Friday) AND language:(\"eng\" OR \"English\") AND subject:(\"Comedy\")")
    println("OK JavnaLastPoizvedba")
}
