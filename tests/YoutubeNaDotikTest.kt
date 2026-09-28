package si.safeer.tv

private fun enako(dobljeno: String, pricakovano: String) {
    check(dobljeno == pricakovano) { "pricakovano $pricakovano, dobljeno $dobljeno" }
}

fun main() {
    enako(YoutubeNaDotik.prevedi("https://www.youtube.com/tv"), "https://m.youtube.com/")
    enako(YoutubeNaDotik.prevedi("https://www.youtube.com/tv#/"), "https://m.youtube.com/")
    enako(YoutubeNaDotik.prevedi("https://www.youtube.com/tv#/watch?v=dQw4w9WgXcQ"), "https://m.youtube.com/watch?v=dQw4w9WgXcQ")
    enako(YoutubeNaDotik.prevedi("https://www.youtube.com/tv#/watch?list=X&v=abcdefg123"), "https://m.youtube.com/watch?v=abcdefg123")
    enako(YoutubeNaDotik.prevedi("https://youtube.com/tv#/search?q=jazz%20radio"), "https://m.youtube.com/results?search_query=jazz%20radio")
    enako(YoutubeNaDotik.prevedi("https://www.youtube.com/tvshows"), "https://www.youtube.com/tvshows")
    enako(YoutubeNaDotik.prevedi("https://m.youtube.com/watch?v=abcdefg123"), "https://m.youtube.com/watch?v=abcdefg123")
    enako(YoutubeNaDotik.prevedi("https://safeer.si/"), "https://safeer.si/")
    println("YoutubeNaDotikTest: OK")
}
